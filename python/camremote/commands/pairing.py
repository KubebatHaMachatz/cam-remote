"""Finding the agent, and remembering where it is."""

from __future__ import annotations

import argparse

from camremote import config
from camremote.commands.base import CliCommand, Context


def _configure_discover(parser: argparse.ArgumentParser) -> None:
    """Declares how long to listen for mDNS replies."""
    parser.add_argument(
        "--timeout",
        type=float,
        default=3.0,
        help="Seconds to listen for replies (default: 3).",
    )


def _discover(context: Context) -> int:
    """Browses the network for agents, explaining the likely cause when none answer.

    Returns exit code 3 on an empty result: silence usually means blocked multicast rather
    than an absent device, so the message points at `--host`.
    """
    agents = context.discover(context.args.timeout)
    if not agents:
        context.warn(
            "No cam-remote agent answered on this network.\n"
            "Many networks block multicast, and guest networks isolate clients entirely.\n"
            "If you know the device's address, pass it directly: camremote --host <ip> status"
        )
        return 3

    context.emit(
        {"agents": [{"host": a.host, "port": a.port, "instance": a.instance} for a in agents]},
        *(f"{agent.describe()}" for agent in agents),
    )
    return 0


def _pair(context: Context) -> int:
    """Confirms the agent is reachable and remembers its address.

    There is no code and no handshake: the project assumes exactly one agent and one client share
    the LAN, so this is purely a convenience -- it saves a round trip of mDNS discovery on every
    later command, nothing more.
    """
    health = context.agent.health()
    saved = config.save(
        config.AgentConfig(host=context.resolved.host, port=context.resolved.port),
        context.config_path,
    )
    device = health.get("device", {})
    context.emit(
        {"device": device, "savedTo": str(saved)},
        f"Found {device.get('model', context.agent.base_url)} at {context.agent.base_url}",
        f"Address saved to {saved}",
    )
    return 0


DISCOVER = CliCommand(
    name="discover",
    help="Find cam-remote agents on the local network over mDNS.",
    run=_discover,
    add_arguments=_configure_discover,
    needs_agent=False,
)

PAIR = CliCommand(
    name="pair",
    help="Find the agent and remember its address, so later commands do not need --host.",
    run=_pair,
)
