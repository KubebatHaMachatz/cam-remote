"""Liveness, readiness, and the device's own command catalog."""

from __future__ import annotations


from camremote.commands.base import CliCommand, Context


def _ping(context: Context) -> int:
    """Reports round-trip time and the device clock.

    The clock is included because a handset whose time is wrong produces capture timestamps
    that make no sense later, and this is the cheapest place to notice.
    """
    response = context.agent.invoke("system.ping")
    context.emit(
        response.data,
        f"Agent responded in {response.duration_ms} ms "
        f"(device clock {response.data.get('deviceTimeMillis')})",
    )
    return 0


#: What each grant actually enables, in terms of the commands an operator would try.
#:
#: Only used to annotate a permission that is missing, where "what is now impossible" is the useful
#: half. Anything the agent reports that is absent from this table is still listed, by name alone --
#: the agent is the authority on its own permissions, exactly as it is for its own commands, and a
#: newer agent must not have a grant silently dropped by an older client.
PERMISSION_EFFECTS = {
    "camera": "blocks take-picture",
    "notifications": "hides the notification that shows the agent's address",
    "canDrawOverlays": "blocks open-camera, which starts an app from the background",
    "ignoringBatteryOptimizations": "the agent may stop answering with the screen off",
}


def _status(context: Context) -> int:
    """Summarises the device and reports every permission it holds, granted or not.

    The first questions after any failure are which grants are absent and what they cost, and
    answering both from here saves walking over to wherever the phone is.
    """
    response = context.agent.invoke("system.status")
    data = response.data
    device = data.get("device", {})
    permissions = data.get("permissions", {})
    missing = data.get("missing", [])

    lines = [
        f"{device.get('model', 'unknown device')} "
        f"(Android {device.get('androidRelease')}, API {device.get('apiLevel')})",
        f"Rear camera: {'yes' if data.get('hasRearCamera') else 'no'}",
    ]

    if permissions:
        lines.append("")
        lines.append("Permissions:")
        width = max(len(name) for name in permissions)
        for name, granted in permissions.items():
            note = "" if granted else PERMISSION_EFFECTS.get(name, "")
            state = "granted" if granted else "MISSING"
            line = f"  {name:<{width}}  {state}"
            lines.append(f"{line}  - {note}" if note else line)
        lines.append("")

    if data.get("setupComplete"):
        lines.append("Setup complete: every command is available.")
    else:
        lines.append("Setup incomplete. Missing on the device: " + ", ".join(missing))
        lines.append("Open cam-remote on the handset and grant the items listed above.")

    context.emit(data, *lines)
    return 0


def _commands(context: Context) -> int:
    """Prints the catalog the device reports, including each command's parameters.

    Read from the agent rather than from a list held here, so a client can describe a command
    it was never compiled against.
    """
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
