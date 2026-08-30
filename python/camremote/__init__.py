"""Control application for the cam-remote Android agent.

The package mirrors the agent's own layering so the two are easy to read side by side:
``transport`` speaks HTTP, ``client`` turns responses into typed results, and ``commands`` holds one
module per CLI verb. The agent's address is always supplied with ``--host``; there is no discovery
and nothing saved between invocations.
"""

__version__ = "1.0.0"
