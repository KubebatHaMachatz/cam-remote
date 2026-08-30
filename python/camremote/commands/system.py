"""Readiness, the device clock, and the device's own command catalog."""

from __future__ import annotations

import time
from datetime import datetime

from camremote.commands.base import CliCommand, Context


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


#: Below this, the difference is indistinguishable from the round trip that measured it.
CLOCK_TOLERANCE_SECONDS = 5


def _clock_line(device_time_millis: object, duration_ms: object) -> str:
    """Round-trip time, the device clock, and whether that clock can be trusted.

    A handset whose time is wrong writes capture timestamps that make no sense a week later, and
    the moment someone asks the device how it is doing is the cheapest place to notice. The
    comparison is against this machine, which is an assumption worth stating rather than a fact:
    it says the two disagree, not which of them is wrong.
    """
    answered = f"Answered in {duration_ms} ms"
    if not isinstance(device_time_millis, int):
        return answered

    device_time = datetime.fromtimestamp(device_time_millis / 1000)
    shown = device_time.strftime("%Y-%m-%d %H:%M:%S")
    drift = (device_time_millis / 1000) - time.time()

    if abs(drift) <= CLOCK_TOLERANCE_SECONDS:
        return f"{answered}; device clock {shown} (in step with this machine)"

    direction = "ahead of" if drift > 0 else "behind"
    return f"{answered}; device clock {shown} ({_describe(abs(drift))} {direction} this machine)"


def _describe(seconds: float) -> str:
    """A drift a person can read: seconds, minutes, hours or days, never all four."""
    for size, unit in ((86_400, "day"), (3_600, "hour"), (60, "minute"), (1, "second")):
        if seconds >= size:
            count = round(seconds / size)
            return f"{count} {unit}{'s' if count != 1 else ''}"
    return "under a second"


def _status(context: Context) -> int:
    """Summarises the device, its clock, and every permission it holds, granted or not.

    This is the command an operator runs first and after any change. It absorbed the old
    system-ping verb, whose entire output -- round-trip time and the device clock -- is two lines
    that belong in a summary rather than behind a separate command.
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
        _clock_line(data.get("deviceTimeMillis"), response.duration_ms),
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
