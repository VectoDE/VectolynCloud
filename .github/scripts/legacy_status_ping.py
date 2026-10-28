#!/usr/bin/env python3
"""Server List Ping against a Minecraft 1.8.x server (protocol 47).

Sends a handshake with next state "status", requests the status JSON and
checks that the server answers with the expected protocol version. Uses the
standard library only.

Usage: legacy_status_ping.py <host> <port> [expected_protocol]
Exit code 0 on success, 1 on any failure.
"""

import json
import socket
import struct
import sys
import time

PROTOCOL_1_8 = 47


def write_varint(value: int) -> bytes:
    out = bytearray()
    value &= 0xFFFFFFFF
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_exact(sock: socket.socket, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise ConnectionError("connection closed by server")
        data.extend(chunk)
    return bytes(data)


def read_varint(sock: socket.socket) -> int:
    result = 0
    for shift in range(0, 35, 7):
        byte = read_exact(sock, 1)[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
    raise ValueError("VarInt too long")


def packet(packet_id: int, payload: bytes = b"") -> bytes:
    body = write_varint(packet_id) + payload
    return write_varint(len(body)) + body


def ping(host: str, port: int, protocol: int) -> dict:
    with socket.create_connection((host, port), timeout=10) as sock:
        host_bytes = host.encode("utf-8")
        handshake = (
            write_varint(protocol)
            + write_varint(len(host_bytes))
            + host_bytes
            + struct.pack(">H", port)
            + write_varint(1)  # next state: status
        )
        sock.sendall(packet(0x00, handshake))
        sock.sendall(packet(0x00))  # status request

        read_varint(sock)  # packet length
        if read_varint(sock) != 0x00:
            raise ValueError("unexpected packet id in status response")
        json_len = read_varint(sock)
        status = json.loads(read_exact(sock, json_len).decode("utf-8"))

        # Ping/pong round trip to make sure the status state is fully handled.
        token = int(time.time() * 1000)
        sock.sendall(packet(0x01, struct.pack(">q", token)))
        read_varint(sock)
        if read_varint(sock) != 0x01:
            raise ValueError("unexpected packet id in pong")
        if struct.unpack(">q", read_exact(sock, 8))[0] != token:
            raise ValueError("pong payload mismatch")
        return status


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 1
    host, port = sys.argv[1], int(sys.argv[2])
    expected = int(sys.argv[3]) if len(sys.argv) > 3 else PROTOCOL_1_8
    try:
        status = ping(host, port, expected)
    except (OSError, ValueError) as exc:
        print(f"status ping failed: {exc}", file=sys.stderr)
        return 1
    version = status.get("version", {})
    print(json.dumps(status, indent=2))
    if version.get("protocol") != expected:
        print(
            f"protocol mismatch: expected {expected}, got {version.get('protocol')}",
            file=sys.stderr,
        )
        return 1
    print(f"OK: {version.get('name')} (protocol {version.get('protocol')})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
