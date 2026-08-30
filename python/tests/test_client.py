"""Tests for the client, against a fake transport.

The transport has its own tests over a real socket; here it is a stand-in, so these tests are about
what the client makes of a reply rather than how the reply arrived.
"""

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from camremote.client import RemoteClient
from camremote.errors import CommandFailed, TransportError
from camremote.transport.base import Response, Transport


class FakeTransport(Transport):
    """Replies with whatever a test queued, and records what it was asked."""

    def __init__(self, *replies: Response):
        self.replies = list(replies)
        self.requests: list[tuple[str, str, bytes | None]] = []

    @property
    def base_url(self) -> str:
        return "http://fake:8099"

    def request(self, method, path, *, body=None):
        self.requests.append((method, path, body))
        if not self.replies:
            raise AssertionError(f"unexpected request: {method} {path}")
        return self.replies.pop(0)


def json_response(payload, status=200, headers=None):
    return Response(status=status, body=json.dumps(payload).encode(), headers=headers or {})


def command_ok(command="system.status", data=None):
    return json_response(
        {
            "id": "generated",
            "command": command,
            "status": "OK",
            "data": data or {},
            "durationMs": 7,
        }
    )


class InvokeTest(unittest.TestCase):
    """Sending a command, and what the client makes of each kind of reply."""

    def test_posts_the_command_envelope(self):
        transport = FakeTransport(command_ok())
        RemoteClient(transport).invoke("system.status")

        method, path, body = transport.requests[0]
        self.assertEqual(("POST", "/v1/command"), (method, path))
        sent = json.loads(body)
        self.assertEqual("system.status", sent["command"])
        self.assertTrue(sent["id"], "a correlation id is always sent")

    def test_sends_params_when_given(self):
        transport = FakeTransport(command_ok())

        RemoteClient(transport).invoke("device.getprop", {"key": "ro.product.model"})

        self.assertEqual(
            {"key": "ro.product.model"}, json.loads(transport.requests[0][2])["params"]
        )

    def test_omits_params_entirely_when_there_are_none(self):
        # An empty object would be harmless, but leaving it out keeps the wire honest about what
        # the operator actually asked for.
        transport = FakeTransport(command_ok())

        RemoteClient(transport).invoke("system.status")

        self.assertNotIn("params", json.loads(transport.requests[0][2]))

    def test_gives_each_request_a_distinct_id(self):
        transport = FakeTransport(command_ok(), command_ok())
        client = RemoteClient(transport)

        client.invoke("system.status")
        client.invoke("system.status")

        ids = [json.loads(request[2])["id"] for request in transport.requests]
        self.assertNotEqual(ids[0], ids[1])

    def test_returns_the_parsed_response(self):
        transport = FakeTransport(command_ok(data={"pong": True}))

        response = RemoteClient(transport).invoke("system.status")

        self.assertTrue(response.ok)
        self.assertEqual({"pong": True}, dict(response.data))
        self.assertEqual(7, response.duration_ms)

    def test_raises_when_the_command_failed(self):
        transport = FakeTransport(
            json_response(
                {
                    "id": "1",
                    "command": "camera.open",
                    "status": "ERROR",
                    "error": {
                        "code": "PRECONDITION_FAILED",
                        "message": "overlay permission missing",
                        "remediation": "complete setup on the device",
                    },
                }
            )
        )

        with self.assertRaises(CommandFailed) as caught:
            RemoteClient(transport).invoke("camera.open")

        # The remediation is the device telling the operator what to do next; losing it here would
        # waste the effort the agent went to.
        self.assertEqual("PRECONDITION_FAILED", caught.exception.code)
        self.assertEqual("complete setup on the device", caught.exception.remediation)

    def test_reports_an_unparseable_reply_as_a_transport_problem(self):
        transport = FakeTransport(Response(status=200, body=b"<html>hello</html>"))

        with self.assertRaises(TransportError):
            RemoteClient(transport).invoke("system.status")


class DownloadTest(unittest.TestCase):
    """Saving a photograph, including choosing a filename when only a directory is given."""

    def test_saves_a_photo_using_the_name_the_agent_supplied(self):
        transport = FakeTransport(
            Response(
                status=200,
                body=b"\xff\xd8jpeg\xff\xd9",
                headers={"Content-Disposition": "attachment; filename=capture.jpg"},
            )
        )

        with TemporaryDirectory() as directory:
            saved = RemoteClient(transport).download("/v1/media/abc", Path(directory))

            self.assertEqual("capture.jpg", saved.name)
            self.assertEqual(b"\xff\xd8jpeg\xff\xd9", saved.read_bytes())

    def test_writes_to_an_explicit_filename_when_given_one(self):
        transport = FakeTransport(Response(status=200, body=b"bytes"))

        with TemporaryDirectory() as directory:
            target = Path(directory) / "chosen.jpg"

            saved = RemoteClient(transport).download("/v1/media/abc", target)

            self.assertEqual(target, saved)

    def test_creates_the_destination_directory(self):
        transport = FakeTransport(
            Response(status=200, body=b"bytes", headers={"Content-Disposition": "filename=a.jpg"})
        )

        with TemporaryDirectory() as directory:
            destination = Path(directory) / "shots" / "today"

            saved = RemoteClient(transport).download("/v1/media/abc", destination)

            self.assertTrue(saved.is_file())

    def test_reports_a_missing_photo(self):
        transport = FakeTransport(
            json_response({"error": {"code": "DEVICE_ERROR", "message": "gone"}}, status=404)
        )

        with TemporaryDirectory() as directory:
            with self.assertRaises(CommandFailed):
                RemoteClient(transport).download("/v1/media/abc", Path(directory))


if __name__ == "__main__":
    unittest.main()
