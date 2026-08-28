"""The transport contract."""

from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Mapping

from camremote.errors import TransportError


@dataclass(frozen=True)
class Response:
    """One reply from the agent, before anything has been made of it."""

    status: int
    body: bytes
    headers: Mapping[str, str] = field(default_factory=dict)

    def json(self) -> dict:
        """:raises TransportError: when the body is not JSON, which means we are not talking to an agent."""
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise TransportError(
                f"Expected JSON from the agent, got {self.body[:80]!r}"
            ) from error

    def filename(self) -> str | None:
        """The filename the agent suggested, from Content-Disposition."""
        disposition = self.headers.get("Content-Disposition", "")
        marker = "filename="
        if marker not in disposition:
            return None
        return disposition.split(marker, 1)[1].strip().strip('"').split(";")[0]


class Transport(ABC):
    """Carries a request to the agent and brings back a reply."""

    @property
    @abstractmethod
    def base_url(self) -> str:
        """Human-readable description of where this transport points, for error messages."""

    @abstractmethod
    def request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
    ) -> Response:
        """:raises TransportError: when the agent cannot be reached."""
