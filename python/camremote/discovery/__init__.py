"""Finding an agent on the local network without being told where it is."""

from camremote.discovery.mdns import DiscoveredAgent, discover

__all__ = ["DiscoveredAgent", "discover"]
