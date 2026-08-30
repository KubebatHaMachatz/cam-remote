"""HTTP transport, on urllib.

Deliberately stdlib rather than `requests`: the whole client is meant to run on a reviewer's machine
with nothing installed, and urllib covers everything this needs.
"""

from __future__ import annotations

import socket
import urllib.error
import urllib.request

from camremote.errors import TransportError
from camremote.transport.base import Response, Transport

DEFAULT_PORT = 8099
DEFAULT_TIMEOUT_SECONDS = 60.0


class HttpTransport(Transport):
    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
    ):
        """Points this transport at one agent.

        :param timeout: generous by default, because a capture legitimately takes seconds.
        """
        self.host = host
        self.port = port
        self.timeout = timeout

    @property
    def base_url(self) -> str:
        """Where this transport points, for error messages the operator has to act on."""
        return f"http://{self.host}:{self.port}"

    def request(self, method: str, path: str, *, body: bytes | None = None) -> Response:
        """Performs one HTTP request against the agent.

        An error *status* is a normal reply here and comes back as a [Response]; only being
        unable to reach the agent at all raises.

        :raises TransportError: the agent could not be reached, or timed out.
        """
        request = urllib.request.Request(
            url=f"{self.base_url}{path}",
            data=body,
            method=method,
        )
        request.add_header("Content-Type", "application/json")

        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return Response(
                    status=response.status,
                    body=response.read(),
                    headers=dict(response.headers.items()),
                )
        except urllib.error.HTTPError as error:
            # A non-2xx status can still carry an error envelope worth showing the operator, so an
            # error status is a normal reply here rather than an exception. HTTPError holds an open
            # socket, hence the explicit close.
            with error:
                return Response(
                    status=error.code,
                    body=error.read(),
                    headers=dict(error.headers.items()) if error.headers else {},
                )
        except (urllib.error.URLError, socket.timeout, TimeoutError, OSError) as error:
            reason = getattr(error, "reason", error)
            raise TransportError(
                f"Could not reach the agent at {self.host}:{self.port} ({reason}). "
                f"Check the device is on the same network with the agent switched on."
            ) from error
