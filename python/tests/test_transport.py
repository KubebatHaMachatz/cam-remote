"""Tests for the HTTP transport, run against a real server on a real socket.

A mocked ``urlopen`` would test the mock. These spin up ``http.server`` on a loopback port instead,
so timeouts, headers, error statuses and connection refusals are the genuine article.
"""

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from camremote.errors import TransportError
from camremote.transport.http import HttpTransport


class _Handler(BaseHTTPRequestHandler):
    """Echoes back what it was sent, so tests can assert on the request the transport built."""

    def do_GET(self):  # noqa: N802 - name fixed by BaseHTTPRequestHandler
        if self.path == "/v1/media/known":
            body = b"\xff\xd8jpeg-bytes\xff\xd9"
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Disposition", 'attachment; filename=a-photo.jpg')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/v1/slow":
            import time

            time.sleep(2)
        self._respond(200, {"path": self.path, "content_type": self.headers.get("Content-Type")})

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        payload = self.rfile.read(length).decode()
        if self.path == "/v1/failing":
            self._respond(500, {"error": {"code": "DEVICE_ERROR", "message": "nope"}})
            return
        if self.path == "/v1/not-json":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"this is not json")
            return
        self._respond(
            200,
            {"path": self.path, "body": payload, "content_type": self.headers.get("Content-Type")},
        )

    def _respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class HttpTransportTest(unittest.TestCase):
    """The transport, exercised against a real server on a loopback socket.

    Deliberately carries no credential of any kind: the project assumes exactly one agent and one
    client share the LAN, so there is nothing here to authenticate.
    """

    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.port = cls.server.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def transport(self, timeout=10.0):
        return HttpTransport(host="127.0.0.1", port=self.port, timeout=timeout)

    def test_sends_json_content_type(self):
        response = self.transport().request("POST", "/v1/command", body=b"{}")

        self.assertEqual("application/json", response.json()["content_type"])

    def test_returns_the_body_of_an_error_status_rather_than_raising(self):
        # An error envelope is worth showing the operator, so the transport hands it back and lets
        # the client decide what it means rather than raising itself.
        response = self.transport().request("POST", "/v1/failing", body=b"{}")

        self.assertEqual(500, response.status)
        self.assertEqual("DEVICE_ERROR", response.json()["error"]["code"])

    def test_reports_a_non_json_body_as_a_transport_error(self):
        response = self.transport().request("POST", "/v1/not-json", body=b"{}")

        with self.assertRaises(TransportError):
            response.json()

    def test_downloads_binary_content_with_its_filename(self):
        response = self.transport().request("GET", "/v1/media/known")

        self.assertEqual(b"\xff\xd8jpeg-bytes\xff\xd9", response.body)
        self.assertEqual("a-photo.jpg", response.filename())

    def test_reports_an_unreachable_agent_clearly(self):
        unreachable = HttpTransport(host="127.0.0.1", port=1, timeout=2.0)

        with self.assertRaises(TransportError) as caught:
            unreachable.request("GET", "/v1/health")

        self.assertIn("127.0.0.1:1", str(caught.exception))

    def test_gives_up_on_a_hung_agent(self):
        with self.assertRaises(TransportError):
            self.transport(timeout=0.4).request("GET", "/v1/slow")

    def test_describes_where_it_is_pointing(self):
        self.assertEqual(f"http://127.0.0.1:{self.port}", self.transport().base_url)


if __name__ == "__main__":
    unittest.main()
