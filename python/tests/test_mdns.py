"""Tests for the standard-library mDNS client.

The fixture is a real 274-byte response captured from a Realme RMX3563 running the agent, not a
packet written by hand to match the parser. It exercises what actually breaks DNS parsing in the
wild: compression pointers, a SRV record whose target is resolved by a separate A record, TXT
attributes, and IPv6 records the client has no use for.
"""

import unittest
from pathlib import Path

from camremote.discovery import mdns

FIXTURE = bytes.fromhex(
    (Path(__file__).parent / "fixtures" / "mdns_response.hex").read_text().strip()
)


class QueryTest(unittest.TestCase):
    def test_asks_for_the_service_by_pointer_record(self):
        query = mdns.build_query()

        self.assertEqual(b"\x00\x01", query[4:6], "exactly one question")
        self.assertIn(b"\x0a_camremote\x04_tcp\x05local\x00", query)
        self.assertTrue(query.endswith(b"\x00\x0c\x80\x01"))

    def test_sets_the_unicast_response_bit(self):
        # Without this the responder answers to multicast port 5353, which on macOS is already held
        # by mDNSResponder -- so the client would have to fight for it or miss the reply.
        qclass = int.from_bytes(mdns.build_query()[-2:], "big")

        self.assertTrue(qclass & 0x8000, "QU bit must be set")
        self.assertEqual(1, qclass & 0x7FFF, "class IN")


class ParsingTest(unittest.TestCase):
    def test_finds_the_agent_in_a_real_response(self):
        agents = mdns.parse_response(FIXTURE)

        self.assertEqual(1, len(agents))
        self.assertEqual("cam-remote realme RMX3563", agents[0].instance)
        self.assertEqual(8099, agents[0].port)

    def test_resolves_the_address_through_the_srv_target(self):
        # The SRV record names a host (Android_5PNZ241N.local); the address lives in a separate A
        # record. Following that chain is the entire job.
        self.assertEqual("10.0.0.4", mdns.parse_response(FIXTURE)[0].host)

    def test_reads_the_txt_attributes(self):
        attributes = mdns.parse_response(FIXTURE)[0].attributes

        self.assertEqual("v1", attributes["api"])
        self.assertEqual("realme RMX3563", attributes["model"])
        self.assertEqual("14", attributes["android"])

    def test_ignores_a_response_for_a_different_service(self):
        self.assertEqual([], mdns.parse_response(FIXTURE, service_type="_printer._tcp.local"))

    def test_falls_back_to_the_sender_address_when_no_a_record_is_present(self):
        # Some responders answer with SRV and TXT only, expecting a follow-up query. The datagram
        # came from the device, so its source address is a perfectly good answer.
        agents = mdns.parse_response(_response_without_a_record(), source_address="10.0.0.99")

        self.assertEqual("10.0.0.99", agents[0].host)

    def test_returns_nothing_for_an_empty_datagram(self):
        self.assertEqual([], mdns.parse_response(b""))

    def test_never_raises_on_a_truncated_packet(self):
        # Anything on the network can send anything to this socket. A malformed datagram must
        # produce no agents, not a stack trace in the middle of `camremote discover`.
        for length in (4, 12, 30, 100, 200, 273):
            with self.subTest(length=length):
                self.assertEqual([], mdns.parse_response(FIXTURE[:length]))

    def test_survives_a_compression_pointer_loop(self):
        # A pointer to itself would spin forever in a naive reader.
        header = (0).to_bytes(2, "big") + b"\x84\x00" + (0).to_bytes(2, "big")
        header += (1).to_bytes(2, "big") + (0).to_bytes(2, "big") + (0).to_bytes(2, "big")
        malicious = header + b"\xc0\x0c" + b"\x00\x0c\x00\x01\x00\x00\x00\x00\x00\x00"

        self.assertEqual([], mdns.parse_response(malicious))


class NameReadingTest(unittest.TestCase):
    def test_reads_a_plain_name(self):
        data = b"\x0a_camremote\x04_tcp\x05local\x00"

        name, offset = mdns.read_name(data, 0)

        self.assertEqual("_camremote._tcp.local", name)
        self.assertEqual(len(data), offset)

    def test_follows_a_compression_pointer_without_consuming_the_target(self):
        data = b"\x05local\x00" + b"\x04test\xc0\x00"

        name, offset = mdns.read_name(data, 7)

        self.assertEqual("test.local", name)
        self.assertEqual(len(data), offset, "offset advances past the pointer, not the target")


def _encode_name(name: str) -> bytes:
    return b"".join(bytes([len(part)]) + part.encode() for part in name.split(".")) + b"\x00"


def _record(name: str, rtype: int, rdata: bytes) -> bytes:
    return (
        _encode_name(name)
        + rtype.to_bytes(2, "big")
        + (1).to_bytes(2, "big")
        + (120).to_bytes(4, "big")
        + len(rdata).to_bytes(2, "big")
        + rdata
    )


def _response_without_a_record() -> bytes:
    """A responder that answers with SRV and TXT only, expecting a follow-up query for the address.

    Built by hand rather than by editing the captured fixture: the fixture's SRV rdata contains a
    compression pointer into the original packet, so records cannot simply be lifted out of it.
    """
    instance = "cam-remote realme RMX3563._camremote._tcp.local"
    srv = (0).to_bytes(2, "big") + (0).to_bytes(2, "big") + (8099).to_bytes(2, "big")
    srv += _encode_name("Android_5PNZ241N.local")
    txt = b"".join(bytes([len(pair)]) + pair for pair in (b"api=v1", b"model=realme RMX3563"))

    body = _record("_camremote._tcp.local", mdns.TYPE_PTR, _encode_name(instance))
    body += _record(instance, mdns.TYPE_SRV, srv)
    body += _record(instance, mdns.TYPE_TXT, txt)

    header = (0).to_bytes(2, "big") + b"\x84\x00" + (0).to_bytes(2, "big")
    header += (3).to_bytes(2, "big") + (0).to_bytes(2, "big") + (0).to_bytes(2, "big")
    return header + body


if __name__ == "__main__":
    unittest.main()
