"""The shape of a CLI command.

Mirrors the agent's own `Command` interface on purpose: on both sides, adding a capability means
writing one small unit and adding one line to a registry. Reading the two next to each other should
make the symmetry obvious.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence, TextIO

from camremote.client import RemoteClient
from camremote.config import AgentConfig
from camremote.discovery.mdns import DiscoveredAgent


@dataclass
class Context:
    """Everything a CLI command is allowed to touch."""

    args: argparse.Namespace
    client: RemoteClient | None
    out: TextIO
    err: TextIO
    as_json: bool
    resolved: AgentConfig
    config_path: Path
    discover: Callable[[float], Sequence[DiscoveredAgent]]

    @property
    def agent(self) -> RemoteClient:
        """The connected client. Only commands with `needs_agent` may reach for this."""
        if self.client is None:
            raise RuntimeError("This command does not have an agent connection")
        return self.client

    def emit(self, payload: Mapping[str, Any] | None, *lines: str) -> None:
        """Prints either the raw payload or the human-readable lines, never both.

        `--json` exists so the CLI can be driven from a script without parsing prose; mixing the two
        would defeat that.
        """
        if self.as_json:
            print(json.dumps(payload if payload is not None else {}, indent=2), file=self.out)
        else:
            for line in lines:
                print(line, file=self.out)

    def warn(self, message: str) -> None:
        """Writes a diagnostic to stderr, keeping stdout clean for machine-read output."""
        print(message, file=self.err)


@dataclass(frozen=True)
class CliCommand:
    """One subcommand.

    :param needs_agent: false for commands that find an agent rather than talk to one, so they are
        not blocked by the very configuration they exist to establish.
    """

    name: str
    help: str
    run: Callable[[Context], int]
    add_arguments: Callable[[argparse.ArgumentParser], None] = lambda parser: None
    needs_agent: bool = True
