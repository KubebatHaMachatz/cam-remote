"""Where the agent is.

Three sources can supply that, in a fixed order of precedence: an explicit flag, then the
environment, then a file written by ``camremote pair``. Anything still missing afterwards is left
as None, and the CLI falls back to mDNS discovery.

There is no secret to manage here: the project assumes exactly one agent and one client share the
LAN, so ``pair`` is purely a convenience -- it finds the agent and remembers its address, with no
code or handshake involved.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, replace
from pathlib import Path

from camremote.errors import CamRemoteError

DEFAULT_PORT = 8099
DEFAULT_CONFIG_PATH = Path.home() / ".camremote.toml"

ENV_HOST = "CAMREMOTE_HOST"
ENV_PORT = "CAMREMOTE_PORT"


@dataclass(frozen=True)
class AgentConfig:
    host: str | None = None
    port: int = DEFAULT_PORT


def load(path: Path | None = None) -> AgentConfig:
    """Reads the saved agent, or returns empty defaults if there is nothing saved."""
    path = path or DEFAULT_CONFIG_PATH
    if not path.is_file():
        return AgentConfig()

    try:
        parsed = tomllib.loads(path.read_text())
    except (tomllib.TOMLDecodeError, OSError, UnicodeDecodeError) as error:
        raise CamRemoteError(f"Could not read {path}: {error}") from error

    return AgentConfig(
        host=parsed.get("host"),
        port=_port(parsed.get("port", DEFAULT_PORT), f"port in {path}"),
    )


def save(agent: AgentConfig, path: Path | None = None) -> Path:
    """Writes the agent to disk, so later commands do not need --host every time."""
    path = path or DEFAULT_CONFIG_PATH
    path.parent.mkdir(parents=True, exist_ok=True)

    lines = ["# Written by 'camremote pair'.", ""]
    if agent.host:
        lines.append(f'host = "{_escape(agent.host)}"')
    lines.append(f"port = {agent.port}")

    path.write_text("\n".join(lines) + "\n")
    return path


def resolve(
    host: str | None,
    port: int | None,
    path: Path | None = None,
) -> AgentConfig:
    """Combines flags, environment and file, in that order of precedence."""
    resolved = load(path)

    env_host = os.environ.get(ENV_HOST)
    env_port = os.environ.get(ENV_PORT)

    if env_host:
        resolved = replace(resolved, host=env_host)
    if env_port:
        resolved = replace(resolved, port=_port(env_port, ENV_PORT))

    if host:
        resolved = replace(resolved, host=host)
    if port:
        resolved = replace(resolved, port=_port(port, "--port"))

    return resolved


def _port(value: object, source: str) -> int:
    """Validates a port from any of the three places one can come from.

    Silently accepting a bad value would send the request somewhere else and surface as a
    baffling connection error several steps later, so each is rejected where it is read and
    named by its source. Booleans are checked first because bool subclasses int in Python,
    so int(True) is 1 and a naive conversion would quietly connect to port 1; floats are
    refused for the same reason, since int(8099.5) is 8099 with no complaint.
    """
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise CamRemoteError(f"{source} must be a whole number, got {value!r}")

    try:
        port = int(value)
    except ValueError:
        raise CamRemoteError(f"{source} must be a whole number, got {value!r}") from None

    if not 1 <= port <= 65535:
        raise CamRemoteError(f"{source} must be between 1 and 65535, got {port}")
    return port


def _escape(value: str) -> str:
    """Escapes a value for a TOML basic string."""
    return value.replace("\\", "\\\\").replace('"', '\\"')
