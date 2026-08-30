"""The `camremote` command-line interface.

Everything here is plumbing: build a parser from the command registry, connect to the agent named on
the command line, run the command, and turn whatever went wrong into an exit code. The verbs
themselves live in `camremote.commands`, one module apiece.

The agent's address is a **required argument**. There was once an mDNS discovery step and a `pair`
verb that saved the result, and they are gone: discovery proved unreliable across the handsets this
was tested on -- see `docs/DEVICES.md` -- and an address that is sometimes found is worse than one
that is always typed. The agent shows its own `host:port` in its notification, so the operator can
always read it off the phone.

Exit codes, because a script may depend on them:

===  ====================================================================
  0  the command succeeded
  1  the agent was reached and reported a failure
  2  the command line was wrong
  3  no agent could be reached
===  ====================================================================
"""

from __future__ import annotations

import argparse
import sys
from typing import Callable, Sequence, TextIO

from camremote.client import RemoteClient
from camremote.commands import COMMANDS, Context
from camremote.errors import CamRemoteError, CommandFailed, TransportError
from camremote.transport.http import HttpTransport

EXIT_OK = 0
EXIT_COMMAND_FAILED = 1
EXIT_USAGE = 2
EXIT_UNREACHABLE = 3

#: The port the agent listens on unless it has been told otherwise.
DEFAULT_PORT = 8099


class _Parser(argparse.ArgumentParser):
    """An argparse parser that reports usage errors instead of killing the process.

    The default `error()` calls `sys.exit`, which makes the CLI awkward to test and rude to embed.
    """

    def error(self, message: str):
        """Reports a usage error as an exception instead of exiting the process."""
        raise _UsageError(message, self.format_usage())


class _UsageError(Exception):
    """A bad command line, carrying the usage text to show alongside the complaint."""

    def __init__(self, message: str, usage: str):
        """Keeps the usage text with the complaint, so both can be printed together."""
        super().__init__(message)
        self.usage = usage


def build_parser() -> _Parser:
    """Builds the argument parser, with one subcommand per entry in the command registry.

    Driven by the registry rather than written out here, so adding a verb is one file and one
    line -- the same shape the agent uses for its own commands.
    """
    parser = _Parser(
        prog="camremote",
        description="Control a cam-remote Android agent over the local network.",
    )
    parser.add_argument(
        "--host",
        required=True,
        metavar="ADDRESS",
        help=(
            "Agent address, as shown in the notification on the device. "
            f"Accepts 10.0.0.8 or 10.0.0.8:{DEFAULT_PORT}."
        ),
    )
    parser.add_argument(
        "--port",
        type=int,
        help=f"Agent port (default: {DEFAULT_PORT}). Ignored if --host already names one.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="Seconds to wait for a reply (default: 60; a capture can take a while).",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        dest="as_json",
        help="Print the agent's raw JSON instead of prose.",
    )

    subparsers = parser.add_subparsers(dest="command", metavar="COMMAND")
    for command in COMMANDS:
        subparser = subparsers.add_parser(command.name, help=command.help, description=command.help)
        command.add_arguments(subparser)
        subparser.set_defaults(_command=command)

    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    connect: Callable[[str, int], RemoteClient] | None = None,
    out: TextIO | None = None,
    err: TextIO | None = None,
) -> int:
    """Runs one command.

    The seams (`connect`, `out`, `err`) exist so the whole CLI can be exercised in tests without a
    device or a network.
    """
    out = out or sys.stdout
    err = err or sys.stderr
    parser = build_parser()

    try:
        args = parser.parse_args(list(argv) if argv is not None else None)
    except _UsageError as error:
        print(f"{error}\n\n{error.usage}", file=err)
        return EXIT_USAGE

    command = getattr(args, "_command", None)
    if command is None:
        print(parser.format_help(), file=err)
        return EXIT_USAGE

    try:
        host, port = split_address(args.host, args.port)
        agent = (connect or _http_client(args.timeout))(host, port)
        context = Context(args=args, agent=agent, out=out, err=err, as_json=args.as_json)
        return command.run(context)

    except CommandFailed as error:
        print(f"error [{error.code}]: {error.message}", file=err)
        if error.remediation:
            print(f"  try: {error.remediation}", file=err)
        return EXIT_COMMAND_FAILED
    except TransportError as error:
        print(f"error: {error}", file=err)
        print(
            "  The address comes from the agent's notification on the device; check the phone is "
            "awake and on the same network.",
            file=err,
        )
        return EXIT_UNREACHABLE
    except CamRemoteError as error:
        print(f"error: {error}", file=err)
        return EXIT_COMMAND_FAILED
    except KeyboardInterrupt:
        print("interrupted", file=err)
        return EXIT_COMMAND_FAILED


def split_address(host: str, port: int | None) -> tuple[str, int]:
    """Splits `--host` into an address and a port.

    The agent's notification reads `Accepting commands on 10.0.0.8:8099`, so that whole string is
    accepted as-is rather than making the operator take it apart. An explicit `--port` is only
    consulted when the address does not already carry one.
    """
    address = host.strip()
    if not address:
        raise CamRemoteError("--host must name the agent's address")

    if address.count(":") == 1:
        address, _, written = address.partition(":")
        if not written.isdigit():
            raise CamRemoteError(f"--host has a port that is not a number, got '{written}'")
        port = int(written)

    port = DEFAULT_PORT if port is None else port
    if not 1 <= port <= 65535:
        raise CamRemoteError(f"Port must be between 1 and 65535, got {port}")
    if not address:
        raise CamRemoteError("--host must name the agent's address")
    return address, port


def _http_client(timeout: float) -> Callable[[str, int], RemoteClient]:
    """Returns the factory that builds a real HTTP-backed client.

    A factory rather than a direct construction so tests can substitute their own.
    """

    def connect(host: str, port: int) -> RemoteClient:
        """Connects to the agent at this address."""
        return RemoteClient(HttpTransport(host=host, port=port, timeout=timeout))

    return connect


if __name__ == "__main__":
    sys.exit(main())
