"""Where the agent is, and the secret for talking to it.

Three sources can supply that, in a fixed order of precedence: an explicit flag, then the
environment, then the file written by ``camremote pair``. Anything still missing afterwards is left
as None, and the CLI falls back to mDNS discovery.
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
ENV_TOKEN = "CAMREMOTE_TOKEN"


@dataclass(frozen=True)
class AgentConfig:
    host: str | None = None
    port: int = DEFAULT_PORT
    token: str | None = None


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
        port=int(parsed.get("port", DEFAULT_PORT)),
        token=parsed.get("token"),
    )


def save(agent: AgentConfig, path: Path | None = None) -> Path:
    """Writes the agent to disk, readable only by this user.

    Hand-rolled TOML because the standard library reads the format but does not write it, and three
    scalar keys do not justify a dependency in a client that otherwise needs none.
    """
    path = path or DEFAULT_CONFIG_PATH
    path.parent.mkdir(parents=True, exist_ok=True)

    lines = ["# Written by 'camremote pair'.", ""]
    if agent.host:
        lines.append(f'host = "{_escape(agent.host)}"')
    lines.append(f"port = {agent.port}")
    if agent.token:
        lines.append(f'token = "{_escape(agent.token)}"')

    path.write_text("\n".join(lines) + "\n")
    # The token authorises taking photographs with someone's phone; other users on a shared machine
    # have no business reading it.
    path.chmod(0o600)
    return path


def resolve(
    host: str | None,
    port: int | None,
    token: str | None,
    path: Path | None = None,
) -> AgentConfig:
    """Combines flags, environment and file, in that order of precedence."""
    resolved = load(path)

    env_host = os.environ.get(ENV_HOST)
    env_token = os.environ.get(ENV_TOKEN)
    env_port = os.environ.get(ENV_PORT)

    if env_host:
        resolved = replace(resolved, host=env_host)
    if env_token:
        resolved = replace(resolved, token=env_token)
    if env_port:
        try:
            resolved = replace(resolved, port=int(env_port))
        except ValueError as error:
            # Silently ignoring it would send the request to the wrong port and produce a baffling
            # connection error several steps later.
            raise CamRemoteError(f"{ENV_PORT} must be a number, got {env_port!r}") from error

    if host:
        resolved = replace(resolved, host=host)
    if port:
        resolved = replace(resolved, port=port)
    if token:
        resolved = replace(resolved, token=token)

    return resolved


def _escape(value: str) -> str:
    """Escapes a value for a TOML basic string."""
    return value.replace("\\", "\\\\").replace('"', '\\"')
