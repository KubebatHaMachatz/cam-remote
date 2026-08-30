"""The CLI's command catalog.

One line per verb, in the order they appear in `--help`. Adding a command to the client is the
mirror image of adding one to the agent: write the module, add it here, change nothing else.
"""

from camremote.commands.base import CliCommand, Context
from camremote.commands.camera import CAMERA_APPS, OPEN_CAMERA, TAKE_PICTURE
from camremote.commands.getprop import GETPROP
from camremote.commands.report import DEVICE_REPORT
from camremote.commands.system import COMMANDS as LIST_COMMANDS
from camremote.commands.system import PING, STATUS

COMMANDS: tuple[CliCommand, ...] = (
    STATUS,
    PING,
    LIST_COMMANDS,
    GETPROP,
    OPEN_CAMERA,
    CAMERA_APPS,
    TAKE_PICTURE,
    DEVICE_REPORT,
)

__all__ = ["COMMANDS", "CliCommand", "Context"]
