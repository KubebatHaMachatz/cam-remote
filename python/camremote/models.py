"""Client-side mirrors of the agent's wire types.

Deliberately a hand-written mirror rather than a generated one: it is a dozen fields, and writing
them out means the client can be read on its own without the Kotlin next to it. The agent's
protocol tests pin the format both sides depend on.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping


@dataclass(frozen=True)
class CommandError:
    code: str
    message: str
    remediation: str | None = None

    @classmethod
    def from_json(cls, payload: Mapping[str, Any]) -> "CommandError":
        """Reads an error object, defaulting anything the agent chose to omit."""
        return cls(
            code=payload.get("code", "INTERNAL"),
            message=payload.get("message", ""),
            remediation=payload.get("remediation"),
        )


@dataclass(frozen=True)
class CommandResponse:
    id: str
    command: str
    status: str
    data: Mapping[str, Any] = field(default_factory=dict)
    error: CommandError | None = None
    duration_ms: int = 0

    @property
    def ok(self) -> bool:
        """True when the agent ran the command successfully."""
        return self.status == "OK"

    @classmethod
    def from_json(cls, payload: Mapping[str, Any]) -> "CommandResponse":
        """Reads a response envelope.

        Tolerant by design: fields the agent omits fall back to defaults, so a client stays
        usable against an agent older or newer than itself.
        """
        error = payload.get("error")
        return cls(
            id=payload.get("id", ""),
            command=payload.get("command", ""),
            status=payload.get("status", "ERROR"),
            data=payload.get("data") or {},
            error=CommandError.from_json(error) if error else None,
            duration_ms=payload.get("durationMs", 0),
        )
