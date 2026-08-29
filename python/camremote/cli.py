"""The `camremote` command-line interface.

Everything here is plumbing: build a parser from the command registry, work out which agent to talk
to, run the command, and turn whatever went wrong into an exit code. The verbs themselves live in
`camremote.commands`, one module apiece.

Exit codes, because a script may depend on them:

===  ====================================================================
  0  the command succeeded
  1  the agent was reached and reported a failure (including a bad token)
  2  the command line was wrong
  3  no agent could be reached
===  ====================================================================
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Callable, Sequence, TextIO

from camremote import config
from camremote.client import RemoteClient
from camremote.commands import COMMANDS, Context
from camremote.discovery import discover as discover_agents
from camremote.errors import (
    AuthenticationError,
    CamRemoteError,
    CommandFailed,
    NoAgentFound,
    TransportError,
)
from camremote.transport.http import HttpTransport

EXIT_OK = 0
EXIT_COMMAND_FAILED = 1
EXIT_USAGE = 2
EXIT_UNREACHABLE = 3

DISCOVERY_TIMEOUT_SECONDS = 3.0

#: Shorter, because this runs on an error path where the answer is already bad news.
SUGGESTION_TIMEOUT_SECONDS = 1.5


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
    parser.add_argument("--host", help="Agent address. Discovered over mDNS when omitted.")
    parser.add_argument("--port", type=int, help=f"Agent port (default: {config.DEFAULT_PORT}).")
    parser.add_argument("--token", help="Bearer token. Normally supplied by 'camremote pair'.")
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="Seconds to wait for a reply (default: 60; a capture can take a while).",
    )
    parser.add_argument("--config", type=Path, help="Config file (default: ~/.camremote.toml).")
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
    connect: Callable[[config.AgentConfig], RemoteClient] | None = None,
    discover: Callable[[float], Sequence] | None = None,
    config_path: Path | None = None,
    out: TextIO | None = None,
    err: TextIO | None = None,
) -> int:
    """Runs one command.

    The seams (`connect`, `discover`, `config_path`, `out`, `err`) exist so the whole CLI can be
    exercised in tests without a device, a network, or the real home directory.
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

    path = config_path or args.config or config.DEFAULT_CONFIG_PATH
    finder = discover or (lambda timeout: discover_agents(timeout=timeout))

    try:
        resolved = config.resolve(args.host, args.port, args.token, path)
        client = None
        if command.needs_agent:
            resolved = _locate_agent(resolved, finder)
            client = (connect or _http_client(args.timeout))(resolved)

        context = Context(
            args=args,
            client=client,
            out=out,
            err=err,
            as_json=args.as_json,
            resolved=resolved,
            config_path=path,
            discover=finder,
        )
        return command.run(context)

    except AuthenticationError as error:
        print(f"error: {error}", file=err)
        return EXIT_COMMAND_FAILED
    except CommandFailed as error:
        print(f"error [{error.code}]: {error.message}", file=err)
        if error.remediation:
            print(f"  try: {error.remediation}", file=err)
        return EXIT_COMMAND_FAILED
    except TransportError as error:
        print(f"error: {error}", file=err)
        # Whatever was configured is not answering, so say what is -- the common case when a second
        # handset joins the bench and the saved config still names the first.
        for line in _other_agents_on_the_network(finder):
            print(line, file=err)
        return EXIT_UNREACHABLE
    except NoAgentFound as error:
        print(f"error: {error}", file=err)
        return EXIT_UNREACHABLE
    except CamRemoteError as error:
        print(f"error: {error}", file=err)
        return EXIT_COMMAND_FAILED
    except KeyboardInterrupt:
        print("interrupted", file=err)
        return EXIT_COMMAND_FAILED


def _other_agents_on_the_network(finder: Callable[[float], Sequence]) -> list[str]:
    """Suggestions for an unreachable agent, or nothing at all when there are none.

    Deliberately a suggestion rather than a silent fallback: quietly redirecting a `take-picture` to
    whichever phone happened to answer would be a memorable way to photograph the wrong room.
    """
    agents = list(finder(SUGGESTION_TIMEOUT_SECONDS))
    if not agents:
        return []
    return ["  Found these agents on the network:"] + [
        f"    {agent.describe()}  ->  --host {agent.host}" for agent in agents
    ]


def _locate_agent(
    resolved: config.AgentConfig,
    finder: Callable[[float], Sequence],
) -> config.AgentConfig:
    """Fills in the address by discovery when nothing has supplied one."""
    if resolved.host:
        return resolved

    agents = finder(DISCOVERY_TIMEOUT_SECONDS)
    if not agents:
        raise NoAgentFound(
            "No agent configured and none found on this network. "
            "Run 'camremote discover', or pass --host <address>."
        )
    if len(agents) > 1:
        listing = "\n  ".join(agent.describe() for agent in agents)
        # Guessing between two phones would be a poor way to find out which one took the photo.
        raise NoAgentFound(
            f"Several agents answered; choose one with --host:\n  {listing}"
        )

    found = agents[0]
    return config.AgentConfig(host=found.host, port=found.port, token=resolved.token)


def _http_client(timeout: float) -> Callable[[config.AgentConfig], RemoteClient]:
    """Returns the factory that builds a real HTTP-backed client.

    A factory rather than a direct construction so tests can substitute their own.
    """

    def connect(resolved: config.AgentConfig) -> RemoteClient:
        """Connects to the agent named by the resolved configuration."""
        return RemoteClient(
            HttpTransport(
                host=resolved.host,
                port=resolved.port,
                token=resolved.token,
                timeout=timeout,
            )
        )

    return connect


if __name__ == "__main__":
    sys.exit(main())
