"""Opening the camera app, and taking a photograph."""

from __future__ import annotations

import argparse
from pathlib import Path

from camremote.commands.base import CliCommand, Context


def _configure_open(parser: argparse.ArgumentParser) -> None:
    """Declares the optional lens hint and target package."""
    parser.add_argument(
        "--lens",
        choices=("front", "rear"),
        help="Ask the camera app for a particular lens. A hint only; apps may ignore it.",
    )
    parser.add_argument(
        "--package",
        help="Open a specific camera app rather than the device default.",
    )


def _open(context: Context) -> int:
    """Opens the device's camera app and reports which component the agent launched."""
    params = {}
    if context.args.lens:
        params["lens"] = context.args.lens
    if context.args.package:
        params["package"] = context.args.package

    response = context.agent.invoke("camera.open", params or None)
    context.emit(response.data, f"Opened {response.data.get('component', 'the camera app')}")
    return 0


def _configure_capture(parser: argparse.ArgumentParser) -> None:
    """Declares where the photo goes, on the device and on this machine."""
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("shots"),
        help="Where to save the photo on this machine (default: ./shots).",
    )
    parser.add_argument("--filename", help="Name for the file on the device.")
    parser.add_argument("--path", help="Destination directory on the device.")
    parser.add_argument(
        "--quality",
        type=int,
        metavar="1-100",
        help="JPEG quality (default: the agent's, 95).",
    )
    parser.add_argument(
        "--gallery",
        action="store_true",
        help="Also index the photo in the device gallery.",
    )
    parser.add_argument(
        "--no-download",
        action="store_true",
        help="Leave the photo on the device instead of fetching it.",
    )


def _capture(context: Context) -> int:
    """Takes a rear-camera photograph and, unless told otherwise, downloads it here.

    The download is the default because a path on the handset is of no use to an operator who
    is not holding it.
    """
    params = {}
    if context.args.path:
        params["path"] = context.args.path
    if context.args.filename:
        params["filename"] = context.args.filename
    if context.args.quality is not None:
        params["jpegQuality"] = context.args.quality
    if context.args.gallery:
        params["publishToGallery"] = True

    response = context.agent.invoke("camera.capture", params or None)
    data = dict(response.data)

    lines = [
        f"Captured {data.get('widthPx')}x{data.get('heightPx')}, "
        f"{_megabytes(data.get('sizeBytes'))} in {response.duration_ms} ms",
        f"On the device: {data.get('path')}",
    ]

    if not context.args.no_download and data.get("downloadPath"):
        saved = context.agent.download(data["downloadPath"], context.args.out)
        # The path on the handset is of no use here; the point of the download route is that the
        # operator ends up holding the photograph.
        data["savedTo"] = str(saved)
        lines.append(f"Saved to: {saved}")

    context.emit(data, *lines)
    return 0


def _megabytes(size: int | None) -> str:
    """Formats a byte count for humans, tolerating a missing value."""
    if not size:
        return "unknown size"
    return f"{size / 1_000_000:.2f} MB"


def _apps(context: Context) -> int:
    """Lists every camera app the device offers and which one `open-camera` would choose.

    Purely diagnostic, and the fastest way to explain why a new handset behaves differently.
    """
    response = context.agent.invoke("camera.apps")
    data = response.data

    chosen = data.get("wouldUseComponent")
    lines = [
        f"camera.open would use: {data.get('wouldUseStrategy')} -> {chosen}"
        if chosen
        else "camera.open would find nothing - this device has no camera app"
    ]
    for strategy in data.get("strategies", []):
        handlers = strategy.get("handlers", [])
        lines.append(f"{strategy['strategy']} ({strategy.get('action')}): {len(handlers)} handler(s)")
        for handler in handlers:
            marks = []
            if handler.get("preinstalled"):
                marks.append("preinstalled")
            if handler.get("defaultHandler"):
                marks.append("default handler")
            suffix = f"  [{', '.join(marks)}]" if marks else ""
            lines.append(f"    {handler['package']}/{handler['activity']}{suffix}")

    context.emit(data, *lines)
    return 0


CAMERA_APPS = CliCommand(
    name="camera-apps",
    help="List the camera apps this device offers and which one camera.open would use.",
    run=_apps,
)

OPEN_CAMERA = CliCommand(
    name="open-camera",
    help="Open the camera app on the device.",
    run=_open,
    add_arguments=_configure_open,
)

TAKE_PICTURE = CliCommand(
    name="take-picture",
    help="Take a still with the rear camera and download it.",
    run=_capture,
    add_arguments=_configure_capture,
)
