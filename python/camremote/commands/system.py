"""Liveness, readiness, and the device's own command catalog."""

from __future__ import annotations

import argparse

from camremote.commands.base import CliCommand, Context


def _ping(context: Context) -> int:
    response = context.agent.invoke("system.ping")
    context.emit(
        response.data,
        f"Agent responded in {response.duration_ms} ms "
        f"(device clock {response.data.get('deviceTimeMillis')})",
    )
    return 0


def _status(context: Context) -> int:
    response = context.agent.invoke("system.status")
    data = response.data
    device = data.get("device", {})
    missing = data.get("missing", [])

    lines = [
        f"{device.get('model', 'unknown device')} "
        f"(Android {device.get('androidRelease')}, API {device.get('apiLevel')})",
        f"Rear camera: {'yes' if data.get('hasRearCamera') else 'no'}",
    ]
    if data.get("setupComplete"):
        lines.append("Setup complete: every command is available.")
    else:
        lines.append("Setup incomplete. Missing on the device: " + ", ".join(missing))
        lines.append("Open cam-remote on the handset and grant the items listed above.")

    context.emit(data, *lines)
    return 0


def _commands(context: Context) -> int:
    response = context.agent.invoke("system.commands")
    lines = []
    for command in response.data.get("commands", []):
        lines.append(f"{command['name']} - {command.get('description', '')}")
        for parameter in command.get("parameters", []):
            required = "required" if parameter.get("required") else "optional"
            default = parameter.get("default")
            suffix = f", default {default}" if default is not None else ""
            lines.append(
                f"    {parameter['name']} ({parameter.get('type', '?').lower()}, "
                f"{required}{suffix}): {parameter.get('description', '')}"
            )
    context.emit(response.data, *lines)
    return 0


PING = CliCommand(
    name="system-ping",
    help="Check the agent is reachable and report its clock.",
    run=_ping,
)

STATUS = CliCommand(
    name="status",
    help="Report the device, its permissions, and whether setup is complete.",
    run=_status,
)

COMMANDS = CliCommand(
    name="commands",
    help="List the commands this device supports, straight from the device.",
    run=_commands,
)
