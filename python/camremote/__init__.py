"""Control application for the cam-remote Android agent.

The package mirrors the agent's own layering so the two are easy to read side by side:
``transport`` speaks HTTP, ``client`` turns responses into typed results, ``commands`` holds one
module per CLI verb, and ``discovery`` finds an agent on the local network.
"""

__version__ = "1.0.0"
