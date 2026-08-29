"""Turning agent replies into typed results.

The client owns the protocol; the transport owns the wire. Keeping them apart is what lets the same
client drive a different transport -- the extension guide's worked example -- and lets these two
concerns be tested separately.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, Mapping

from camremote.errors import AuthenticationError, CommandFailed, TransportError
from camremote.models import CommandResponse
from camremote.transport.base import Response, Transport

COMMAND_PATH = "/v1/command"
HEALTH_PATH = "/v1/health"
PAIR_PATH = "/v1/pair"


class RemoteClient:
    """A conversation with one agent."""

    def __init__(self, transport: Transport):
        """Binds this client to one agent, reached through the given transport."""
        self.transport = transport

    @property
    def base_url(self) -> str:
        """Where the agent is, as the transport describes it."""
        return self.transport.base_url

    def invoke(self, command: str, params: Mapping[str, Any] | None = None) -> CommandResponse:
        """Runs a command on the device.

        :raises AuthenticationError: the token was missing or wrong.
        :raises CommandFailed: the agent ran the command and it failed.
        :raises TransportError: the agent could not be reached or did not answer sensibly.
        """
        envelope: dict[str, Any] = {"id": str(uuid.uuid4()), "command": command}
        if params:
            envelope["params"] = dict(params)

        response = self.transport.request(
            "POST", COMMAND_PATH, body=json.dumps(envelope).encode()
        )
        self._raise_for_transport_status(response, context=command)

        parsed = CommandResponse.from_json(response.json())
        if not parsed.ok:
            error = parsed.error
            raise CommandFailed(
                command=command,
                code=error.code if error else "INTERNAL",
                message=error.message if error else "The agent reported a failure",
                remediation=error.remediation if error else None,
            )
        return parsed

    def health(self) -> Mapping[str, Any]:
        """Reads the unauthenticated health endpoint, to tell "wrong address" from "wrong token"."""
        response = self.transport.request("GET", HEALTH_PATH)
        self._raise_for_transport_status(response, context="health")
        return response.json()

    def pair(self) -> str:
        """Claims the token while the user-opened pairing window is live."""
        response = self.transport.request("POST", PAIR_PATH)
        self._raise_for_transport_status(response, context="pair")
        payload = response.json()
        token = payload.get("token")
        if not token:
            raise TransportError("The agent's pairing reply carried no token")
        return token

    def download(self, path: str, destination: Path) -> Path:
        """Fetches a stored photo and writes it to ``destination``.

        A directory is filled in with the filename the agent suggested, which keeps the timestamped
        name the capture was given rather than inventing a new one on this side.
        """
        response = self.transport.request("GET", path)
        self._raise_for_transport_status(response, context="download")

        if destination.is_dir() or destination.suffix == "":
            destination.mkdir(parents=True, exist_ok=True)
            target = destination / (response.filename() or "capture.jpg")
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            target = destination

        target.write_bytes(response.body)
        return target

    @staticmethod
    def _raise_for_transport_status(response: Response, context: str) -> None:
        """Turns an HTTP error status into the matching typed exception.

        Failures arrive in the same envelope whatever their status, so one reader serves them
        all; only 401 is singled out, because a rejected token needs a different remedy from
        everything else.
        """
        if response.status < 400:
            return

        try:
            error = response.json().get("error", {})
        except TransportError:
            error = {}

        message = error.get("message") or f"The agent returned HTTP {response.status}"
        if response.status == 401:
            raise AuthenticationError(
                f"{message}. Run 'camremote pair' after tapping Pair on the device."
            )
        raise CommandFailed(
            command=context,
            code=error.get("code", "INTERNAL"),
            message=message,
            remediation=error.get("remediation"),
        )
