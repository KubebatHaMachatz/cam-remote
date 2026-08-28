"""Everything a new handset reveals, in one command.

Written for the moment a project meets its second device. Rather than running four commands and
reading four outputs, this gathers the lot into one blob that can be pasted straight into a device
matrix — and, crucially, keeps going when part of the device is broken. A diagnostic that fails on a
misconfigured device is no diagnostic at all.
"""

from __future__ import annotations

import argparse
import json
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


def _configure(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--out",
        type=Path,
        help="Also write the full report as JSON to this file.",
    )


def _collect(context: Context, command: str, params: dict | None = None) -> dict:
    """Runs one command, turning a failure into part of the report rather than the end of it."""
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


def _run(context: Context) -> int:
    report = {
        "agent": {"baseUrl": context.agent.base_url},
        "status": _collect(context, "system.status"),
        "cameraApps": _collect(context, "camera.apps"),
        "properties": _collect(context, "device.getprop", {"keys": SURVEY_PROPERTIES}),
        "commands": _collect(context, "system.commands"),
    }

    if context.args.out:
        context.args.out.parent.mkdir(parents=True, exist_ok=True)
        context.args.out.write_text(json.dumps(report, indent=2) + "\n")

    context.emit(report, *_summarise(report, context.args.out))
    return 0


def _summarise(report: dict, out: Path | None) -> list[str]:
    lines: list[str] = ["Device", f"  agent at {report['agent']['baseUrl']}"]
    lines += _describe_status(report["status"])
    lines += [""] + _describe_camera_apps(report["cameraApps"])
    lines += [""] + _describe_properties(report["properties"])
    lines += [""] + _describe_commands(report["commands"])

    if out:
        lines += ["", f"Full report written to {out}"]
    return lines


def _describe_status(status: dict) -> list[str]:
    if "error" in status:
        return [f"  {_error(status)}"]

    device = status.get("device", {})
    lines = [
        f"  {device.get('model', 'unknown')} — "
        f"Android {device.get('androidRelease')} (API {device.get('apiLevel')})",
        f"  rear camera: {'yes' if status.get('hasRearCamera') else 'no'}",
    ]
    if status.get("setupComplete"):
        lines.append("  setup: complete")
    else:
        lines.append("  setup: incomplete — missing " + ", ".join(status.get("missing", [])))
    return lines


def _describe_camera_apps(camera_apps: dict) -> list[str]:
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
            if handler.get("userDefault"):
                marks.append("user default")
            suffix = f"  [{', '.join(marks)}]" if marks else ""
            lines.append(f"      {handler['package']}/{handler['activity']}{suffix}")
    return lines


def _describe_properties(properties: dict) -> list[str]:
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
    if "error" in commands:
        return ["Commands", f"  {_error(commands)}"]
    names = [command["name"] for command in commands.get("commands", [])]
    return [f"Commands ({len(names)}): " + ", ".join(names)]


def _error(section: dict) -> str:
    error = section["error"]
    return f"ERROR [{error['code']}] {error['message']}"


DEVICE_REPORT = CliCommand(
    name="device-report",
    help="Gather everything about a device into one report, for a compatibility matrix.",
    run=_run,
    add_arguments=_configure,
)
