"""Speaking to the agent.

The abstraction earns its place by being the seam the project's extension guide points at: the
agent's command layer is transport-agnostic, so reaching it over something other than HTTP -- a
message broker, a relay -- means writing one class here and nothing else.
"""

from camremote.transport.base import Response, Transport
from camremote.transport.http import HttpTransport

__all__ = ["Response", "Transport", "HttpTransport"]
