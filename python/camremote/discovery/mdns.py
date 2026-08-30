"""A minimal mDNS / DNS-SD browser, built on the standard library alone.

The project deliberately does not use adb, so the control machine has to find the handset by itself.
Rather than ask the operator to hunt for an IP address that DHCP will change next week, the agent
advertises ``_camremote._tcp`` and this module goes looking for it.

Only the browsing half of mDNS is implemented -- enough to ask one question and read the answer. A
full responder is a large piece of software and none of it is needed here.

Two details are worth knowing:

* Queries set the **QU (unicast response) bit**, but the reply is listened for **both** on our
  ephemeral port and on the multicast group. RFC 6762 section 5.4 lets a responder answer a QU
  query by multicast anyway if it has not multicast that record recently, and Android's responder
  does exactly that -- so a client that only watches its own port hears nothing on the very devices
  this project targets. Joining 5353 needs `SO_REUSEADDR`/`SO_REUSEPORT` to coexist with the system
  responder that already owns the port on macOS; if the join fails we fall back to unicast alone
  rather than giving up.
* Responders suppress duplicate answers for a second or two, so a query issued immediately after a
  previous one can legitimately go unanswered. The listen window covers several announcements.
* Every parsing function is total. Anything on the local network can send anything to this socket,
  so a malformed datagram yields no results rather than an exception in the middle of a command.
"""

from __future__ import annotations

import select
import socket
import struct
import time
from dataclasses import dataclass, field
from typing import Mapping

MULTICAST_ADDRESS = "224.0.0.251"
MULTICAST_PORT = 5353

#: The service the Android agent advertises.
SERVICE_TYPE = "_camremote._tcp.local"

TYPE_A = 1
TYPE_PTR = 12
TYPE_TXT = 16
TYPE_SRV = 33

CLASS_IN = 1
UNICAST_RESPONSE_BIT = 0x8000

_HEADER = struct.Struct("!HHHHHH")
_MAX_POINTER_HOPS = 32


@dataclass(frozen=True)
class DiscoveredAgent:
    """An agent found on the local network."""

    instance: str
    host: str
    port: int
    attributes: Mapping[str, str] = field(default_factory=dict)

    def describe(self) -> str:
        """One line naming this agent, preferring the model over the raw service instance."""
        model = self.attributes.get("model", self.instance)
        return f"{model} at {self.host}:{self.port}"


@dataclass(frozen=True)
class Record:
    name: str
    type: int
    rdata: bytes
    rdata_offset: int


@dataclass(frozen=True)
class Message:
    questions: list[str]
    records: list[Record]


def build_query(service_type: str = SERVICE_TYPE) -> bytes:
    """Builds a one-question PTR query for ``service_type``."""
    header = _HEADER.pack(0, 0, 1, 0, 0, 0)
    question = _encode_name(service_type)
    question += struct.pack("!HH", TYPE_PTR, CLASS_IN | UNICAST_RESPONSE_BIT)
    return header + question


def discover(
    timeout: float = 2.0,
    service_type: str = SERVICE_TYPE,
    interface_address: str | None = None,
) -> list[DiscoveredAgent]:
    """Browses the local network for agents, for up to ``timeout`` seconds.

    Returns every distinct agent that answered. An empty list is a perfectly ordinary result: guest
    networks and many corporate access points block multicast outright, which is why every command
    also accepts an explicit ``--host``.
    """
    query = build_query(service_type)
    found: dict[tuple[str, int], DiscoveredAgent] = {}

    # Both sockets have to be pinned to a real interface; see _open_group_listener.
    address = interface_address or _primary_ipv4() or "0.0.0.0"

    querier = _open_querier(address)
    if querier is None:
        return []

    # Most responders answer the multicast group rather than our port, whatever the QU bit asked
    # for, so this is the socket that usually carries the answer. It is optional: without it
    # discovery still works against a responder that honours QU.
    listener = _open_group_listener(address)
    sockets = [s for s in (querier, listener) if s is not None]

    try:
        try:
            querier.sendto(query, (MULTICAST_ADDRESS, MULTICAST_PORT))
        except OSError:
            return []

        deadline = time.monotonic() + timeout
        resent = False
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            # One repeat halfway through, in case the first query was dropped. Asking more often
            # than this is pointless: responders suppress duplicate answers regardless.
            if not resent and remaining < timeout / 2:
                resent = True
                try:
                    querier.sendto(query, (MULTICAST_ADDRESS, MULTICAST_PORT))
                except OSError:
                    pass

            ready, _, _ = select.select(sockets, [], [], min(0.25, max(remaining, 0.0)))
            for sock in ready:
                try:
                    data, sender = sock.recvfrom(9000)
                except OSError:
                    continue
                for agent in parse_response(data, service_type, source_address=sender[0]):
                    found[(agent.host, agent.port)] = agent
    finally:
        for sock in sockets:
            sock.close()

    return sorted(found.values(), key=lambda agent: (agent.instance, agent.host))


def _primary_ipv4() -> str | None:
    """The address of the interface this machine would actually use to reach the LAN.

    Connecting a UDP socket sends nothing; it only asks the routing table which local address
    would be used. That is more dependable than `gethostbyname(gethostname())`, which on macOS
    frequently answers 127.0.0.1 or an address belonging to a VPN.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect((MULTICAST_ADDRESS, MULTICAST_PORT))
        return probe.getsockname()[0]
    except OSError:
        return None
    finally:
        probe.close()


def _open_querier(interface_address: str) -> socket.socket | None:
    """The socket the query goes out of, listening on its own ephemeral port for a QU reply."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        if interface_address not in ("", "0.0.0.0"):
            # Pin the outgoing interface for the same reason the join below is pinned.
            try:
                sock.setsockopt(
                    socket.IPPROTO_IP,
                    socket.IP_MULTICAST_IF,
                    socket.inet_aton(interface_address),
                )
            except OSError:
                pass
        try:
            sock.bind((interface_address, 0))
        except OSError:
            pass
        sock.setblocking(False)
        return sock
    except OSError:
        sock.close()
        return None


def _open_group_listener(interface_address: str | None) -> socket.socket | None:
    """A socket joined to the mDNS group, or None where the platform will not share port 5353.

    Two details decide whether this receives anything at all.

    Sharing the port: on macOS `mDNSResponder` already holds 5353, and on Linux a responder such
    as Avahi usually does. `SO_REUSEADDR` and `SO_REUSEPORT` together are what let this socket
    receive alongside them, and `SO_REUSEPORT` does not exist on every platform.

    Joining on a **named interface**: `INADDR_ANY` leaves the choice to the routing table, which on
    macOS picks an interface that is frequently not the one carrying mDNS — a VPN tunnel, or simply
    the wrong one of several. Measured on a Mac with one Wi-Fi interface, an `INADDR_ANY` join
    received nothing at all over twelve seconds while a join pinned to that same interface received
    the network's ordinary mDNS traffic. The pinned join is therefore tried first, with
    `INADDR_ANY` kept only as a fallback.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        sock.bind(("", MULTICAST_PORT))
    except OSError:
        sock.close()
        return None

    joined = False
    for address in (interface_address, None):
        if address in ("", "0.0.0.0"):
            continue
        membership = (
            struct.pack("=4sl", socket.inet_aton(MULTICAST_ADDRESS), socket.INADDR_ANY)
            if address is None
            else struct.pack("=4s4s", socket.inet_aton(MULTICAST_ADDRESS), socket.inet_aton(address))
        )
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
            joined = True
            break
        except OSError:
            continue

    if not joined:
        sock.close()
        return None

    sock.setblocking(False)
    return sock


def parse_response(
    data: bytes,
    service_type: str = SERVICE_TYPE,
    source_address: str | None = None,
) -> list[DiscoveredAgent]:
    """Extracts agents from a response datagram.

    ``source_address`` is used when the responder sent SRV and TXT but no address record, which is
    legal: the datagram came from the device, so where it came from is the answer.
    """
    message = parse_message(data)
    if message is None:
        return []

    suffix = "." + service_type.rstrip(".")
    services: dict[str, dict] = {}
    addresses: dict[str, str] = {}

    for record in message.records:
        if record.type == TYPE_A and len(record.rdata) == 4:
            addresses.setdefault(record.name.lower(), socket.inet_ntoa(record.rdata))
            continue
        if not record.name.endswith(suffix):
            continue
        entry = services.setdefault(record.name, {"target": None, "port": None, "attributes": {}})
        if record.type == TYPE_SRV:
            parsed = _parse_srv(data, record)
            if parsed is not None:
                entry["target"], entry["port"] = parsed
        elif record.type == TYPE_TXT:
            entry["attributes"] = _parse_txt(record.rdata)

    agents = []
    for name, entry in services.items():
        if entry["port"] is None:
            continue
        host = addresses.get((entry["target"] or "").lower()) or source_address
        if host is None:
            continue
        agents.append(
            DiscoveredAgent(
                instance=name[: -len(suffix)],
                host=host,
                port=entry["port"],
                attributes=entry["attributes"],
            )
        )
    return agents


def parse_message(data: bytes) -> Message | None:
    """Parses a DNS message, or returns None if it is not one."""
    if len(data) < _HEADER.size:
        return None

    _, _, questions, answers, authorities, additionals = _HEADER.unpack_from(data, 0)
    offset = _HEADER.size
    asked = []

    try:
        for _ in range(questions):
            name, offset = read_name(data, offset)
            asked.append(name)
            offset += 4  # type and class

        records = []
        for _ in range(answers + authorities + additionals):
            name, offset = read_name(data, offset)
            if offset + 10 > len(data):
                return None
            rtype, _rclass, _ttl, rdlength = struct.unpack_from("!HHIH", data, offset)
            offset += 10
            if offset + rdlength > len(data):
                return None
            records.append(Record(name, rtype, data[offset : offset + rdlength], offset))
            offset += rdlength
    except (ValueError, struct.error, IndexError):
        return None

    return Message(questions=asked, records=records)


def read_name(data: bytes, offset: int) -> tuple[str, int]:
    """Reads a possibly compressed domain name.

    Returns the name and the offset just past it -- past the *pointer*, when one was followed,
    rather than past whatever it pointed at.

    :raises ValueError: on a truncated name or a pointer loop.
    """
    labels: list[str] = []
    hops = 0
    cursor = offset
    offset_after: int | None = None

    while True:
        if cursor >= len(data):
            raise ValueError("truncated name")
        length = data[cursor]

        if length & 0xC0 == 0xC0:
            if cursor + 1 >= len(data):
                raise ValueError("truncated compression pointer")
            pointer = ((length & 0x3F) << 8) | data[cursor + 1]
            if offset_after is None:
                offset_after = cursor + 2
            hops += 1
            # A name that points at itself, or round in a circle, would otherwise loop forever.
            if hops > _MAX_POINTER_HOPS or pointer >= len(data) or pointer == cursor:
                raise ValueError("compression pointer loop")
            cursor = pointer
            continue

        if length == 0:
            return ".".join(labels), offset_after if offset_after is not None else cursor + 1

        cursor += 1
        if cursor + length > len(data):
            raise ValueError("truncated label")
        labels.append(data[cursor : cursor + length].decode("utf-8", "replace"))
        cursor += length


def _parse_srv(data: bytes, record: Record) -> tuple[str, int] | None:
    """Reads a SRV record's target host and port, or None if it is malformed."""
    if len(record.rdata) < 7:
        return None
    _priority, _weight, port = struct.unpack_from("!HHH", record.rdata, 0)
    try:
        # The target is read from the whole message, not from the rdata alone: it is usually a
        # compression pointer into an earlier record.
        target, _ = read_name(data, record.rdata_offset + 6)
    except ValueError:
        return None
    return target, port


def _parse_txt(rdata: bytes) -> dict[str, str]:
    """Reads TXT key=value pairs, skipping any entry that is not one."""
    attributes: dict[str, str] = {}
    cursor = 0
    while cursor < len(rdata):
        length = rdata[cursor]
        cursor += 1
        entry = rdata[cursor : cursor + length]
        cursor += length
        if b"=" not in entry:
            continue
        key, _, value = entry.partition(b"=")
        attributes[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    return attributes


def _encode_name(name: str) -> bytes:
    """Encodes a domain name as DNS length-prefixed labels."""
    encoded = b""
    for label in name.rstrip(".").split("."):
        raw = label.encode("utf-8")
        encoded += bytes([len(raw)]) + raw
    return encoded + b"\x00"
