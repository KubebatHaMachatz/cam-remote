"""Finding an agent, and getting its token."""

from __future__ import annotations

import argparse

from camremote import config
from camremote.commands.base import CliCommand, Context


def _configure_discover(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--timeout",
        type=float,
        default=3.0,
        help="Seconds to listen for replies (default: 3).",
    )


def _discover(context: Context) -> int:
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
    token = context.agent.pair()
    saved = config.save(
        config.AgentConfig(
            host=context.resolved.host,
            port=context.resolved.port,
            token=token,
        ),
        context.config_path,
    )
    context.emit(
        {"token": token, "savedTo": str(saved)},
        f"Paired with {context.agent.base_url}",
        f"Token saved to {saved}",
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
    help="Claim the agent's token. Tap Pair on the device first.",
    run=_pair,
)
