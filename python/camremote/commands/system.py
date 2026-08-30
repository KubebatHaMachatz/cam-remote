"""Everything a device will tell you about itself, and its own command catalog.

`status` is the command an operator runs first, after any change, and when meeting a new handset.
It absorbed two verbs that used to stand alone -- a liveness ping, and a device-report that gathered
the same four requests into one blob -- because all three answered questions about the device rather
than doing anything to it, and three commands to learn one thing is two too many.
"""

from __future__ import annotations

import argparse
import json
import time
from datetime import datetime
from pathlib import Path

from camremote.commands.base import CliCommand, Context
from camremote.errors import CamRemoteError, CommandFailed


#: Enough to identify a build precisely, and small enough to read. One request, not fifteen.
SURVEY_PROPERTIES = [
    "ro.product.manufacturer",
    "ro.product.brand",
    "ro.product.model",
    "ro.product.device",
    "ro.product.name",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.type",
    "ro.build.tags",
    "ro.build.fingerprint",
    "ro.hardware",
    "ro.board.platform",
    "ro.product.cpu.abi",
]


def _describe_camera_apps(camera_apps: dict) -> list[str]:
    """Describes every camera app found, per strategy, and which one would be launched."""
    lines = ["Camera apps"]
    if "error" in camera_apps:
        return lines + [f"  {_error(camera_apps)}"]

    chosen = camera_apps.get("wouldUseComponent")
    lines.append(
        f"  camera.open would use: {camera_apps.get('wouldUseStrategy')} → {chosen}"
        if chosen
        else "  camera.open would find nothing — no camera app on this device"
    )
    for strategy in camera_apps.get("strategies", []):
        handlers = strategy.get("handlers", [])
        lines.append(f"  {strategy['strategy']} ({strategy.get('action')}): {len(handlers)} handler(s)")
        for handler in handlers:
            marks = []
            if handler.get("preinstalled"):
                marks.append("preinstalled")
            if handler.get("defaultHandler"):
                marks.append("default handler")
            suffix = f"  [{', '.join(marks)}]" if marks else ""
            lines.append(f"      {handler['package']}/{handler['activity']}{suffix}")
    return lines


def _describe_properties(properties: dict) -> list[str]:
    """Prints the surveyed build properties, skipping any the device does not set."""
    lines = ["Build"]
    if "error" in properties:
        return lines + [f"  {_error(properties)}"]

    values = properties.get("properties", {})
    width = max((len(key) for key in values), default=0)
    for key, value in values.items():
        if value is not None:
            lines.append(f"  {key.ljust(width)} = {value}")
    return lines


def _describe_commands(commands: dict) -> list[str]:
    """Names the commands this agent supports, on one line."""
    if "error" in commands:
        return ["Commands", f"  {_error(commands)}"]
    names = [command["name"] for command in commands.get("commands", [])]
    return [f"Commands ({len(names)}): " + ", ".join(names)]


def _error(section: dict) -> str:
    """Formats a failed section so the report shows what went wrong in place."""
    error = section["error"]
    return f"ERROR [{error['code']}] {error['message']}"


def _collect(context: Context, command: str, params: dict | None = None) -> dict:
    """Runs one command, turning a failure into part of the answer rather than the end of it."""
    try:
        return dict(context.agent.invoke(command, params).data)
    except CommandFailed as failure:
        return {
            "error": {
                "code": failure.code,
                "message": failure.message,
                "remediation": failure.remediation,
            }
        }
    except CamRemoteError as failure:
        return {"error": {"code": "CLIENT", "message": str(failure)}}


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
    """Summarises the device, its clock, and every permission it holds, granted or not.

    This is the command an operator runs first and after any change. It absorbed the old
    system-ping verb, whose entire output -- round-trip time and the device clock -- is two lines
    that belong in a summary rather than behind a separate command.
    """
    response = context.agent.invoke("system.status")
    data = response.data
    device = data.get("device", {})

    # Every remaining section is gathered the way device-report gathered them: a section that
    # fails becomes part of the answer rather than the end of it. A survey that dies on a broken
    # device is no survey, and a broken device is exactly when this gets run.
    report = {
        "status": data,
        "cameraApps": _collect(context, "camera.apps"),
        "properties": _collect(context, "device.getprop", {"keys": SURVEY_PROPERTIES}),
        "commands": _collect(context, "system.commands"),
    }
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
    lines += _describe_camera_apps(report["cameraApps"])
    lines.append("")
    lines += _describe_properties(report["properties"])
    lines.append("")
    lines += _describe_commands(report["commands"])
    lines.append("")

    if data.get("setupComplete"):
        lines.append("Setup complete: every command is available.")
    else:
        lines.append("Setup incomplete. Missing on the device: " + ", ".join(missing))
        lines.append("Open cam-remote on the handset and grant the items listed above.")

    if context.args.out:
        context.args.out.parent.mkdir(parents=True, exist_ok=True)
        context.args.out.write_text(json.dumps(report, indent=2) + "\n")
        lines += ["", f"Full report written to {context.args.out}"]

    context.emit(report, *lines)
    return 0


def _commands(context: Context) -> int:
    """Prints the catalog the device reports, split into what the agent is for and how to inspect it.

    Read from the agent rather than from a list held here, so a client can describe a command it was
    never compiled against -- including which group that command belongs in. An agent too old to say
    is listed under diagnostics rather than dropped.
    """
    response = context.agent.invoke("system.commands")
    catalog = response.data.get("commands", [])

    # Built from the registry, so a verb added later maps itself without editing anything here.
    # An agent command with no verb is still listed: the device is the authority on what it can
    # do, and this client not having caught up is the client's problem to state, not to hide.
    from camremote.commands import COMMANDS

    verbs = {
        command.agent_command: command.name
        for command in COMMANDS
        if command.agent_command is not None
    }

    groups = [
        ("Primary — what the agent is for", "PRIMARY"),
        ("Diagnostics — how to inspect it", "DIAGNOSTIC"),
    ]

    lines = []
    for heading, category in groups:
        # Anything the agent did not categorise, including from an agent that has never heard of
        # categories, falls in with the diagnostics.
        members = [
            command
            for command in catalog
            if (command.get("category") or "DIAGNOSTIC").upper() == category
        ]
        if not members:
            continue
        if lines:
            lines.append("")
        lines.append(f"{heading}:")
        for command in members:
            agent_name = command["name"]
            verb = verbs.get(agent_name)
            # The verb first, because it is the only one of the two an operator can type. The
            # agent's name stays because it is what the device log, --json and the wire all use.
            headline = f"{verb}  ({agent_name})" if verb else f"{agent_name}  (no CLI verb)"
            lines.append(f"  {headline} - {command.get('description', '')}")
            for parameter in command.get("parameters", []):
                required = "required" if parameter.get("required") else "optional"
                default = parameter.get("default")
                suffix = f", default {default}" if default is not None else ""
                lines.append(
                    f"      {parameter['name']} ({parameter.get('type', '?').lower()}, "
                    f"{required}{suffix}): {parameter.get('description', '')}"
                )

    lines.append("")
    lines.append(
        "The first name is the verb to type; the second is what the agent calls it, which is what"
    )
    lines.append(
        "appears in --json and in the device log. Parameters are the agent's own — a verb may"
    )
    lines.append("expose them under a different flag, so see `camremote <verb> --help`.")

    context.emit(response.data, *lines)
    return 0


def _configure_status(parser: argparse.ArgumentParser) -> None:
    """Declares the optional file to write the full survey to, for a device matrix."""
    parser.add_argument(
        "--out",
        type=Path,
        help="Also write the full survey as JSON to this file.",
    )


STATUS = CliCommand(
    name="status",
    agent_command="system.status",
    help="Report the device, its permissions, its camera apps, its build and its catalog.",
    run=_status,
    add_arguments=_configure_status,
)

COMMANDS = CliCommand(
    name="commands",
    agent_command="system.commands",
    help="List the commands this device supports, straight from the device.",
    run=_commands,
)
