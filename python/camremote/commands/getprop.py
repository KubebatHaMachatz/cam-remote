"""Reading Android system properties."""

from __future__ import annotations

import argparse

from camremote.commands.base import CliCommand, Context


def _configure(parser: argparse.ArgumentParser) -> None:
    """Declares the positional property names."""
    parser.add_argument(
        "keys",
        nargs="+",
        metavar="KEY",
        help="One or more property names, e.g. ro.product.model",
    )


def _run(context: Context) -> int:
    """Reads the requested properties and prints them aligned in a column."""
    keys = context.args.keys
    # One request whatever the number of keys: the round trip is the expensive part, not the read.
    params = {"key": keys[0]} if len(keys) == 1 else {"keys": keys}

    response = context.agent.invoke("device.getprop", params)
    properties = response.data.get("properties", {})

    width = max((len(key) for key in properties), default=0)
    lines = [
        f"{key.ljust(width)} = {'(not set)' if value is None else value}"
        for key, value in properties.items()
    ]
    context.emit(response.data, *lines)
    return 0


GETPROP = CliCommand(
    name="getprop",
    help="Read one or more Android system properties.",
    run=_run,
    add_arguments=_configure,
)
