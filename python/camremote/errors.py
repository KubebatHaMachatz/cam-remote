"""The failures a control session can meet.

They are distinct types rather than one exception with a string, because the CLI maps them to
different exit codes: a script that shells out needs to tell "the phone said no" from "the phone was
not there".
"""


class CamRemoteError(Exception):
    """Base class for everything this package raises."""


class TransportError(CamRemoteError):
    """The agent could not be reached, or answered with something unintelligible."""


class AuthenticationError(CamRemoteError):
    """The agent rejected the bearer token."""


class CommandFailed(CamRemoteError):
    """The agent ran the command and it failed.

    Carries the agent's typed error so the CLI can print the remediation the device supplied, which
    is usually the actual answer to the operator's next question.
    """

    def __init__(self, command: str, code: str, message: str, remediation: str | None = None):
        """Keeps the agent's typed error intact so the CLI can print its remediation."""
        super().__init__(f"{command} failed: {message}")
        self.command = command
        self.code = code
        self.message = message
        self.remediation = remediation


class NoAgentFound(CamRemoteError):
    """Discovery ran and turned up nothing."""
