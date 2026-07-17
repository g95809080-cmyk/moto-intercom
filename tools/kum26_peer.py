#!/usr/bin/env python3
"""Controlled Signaling v2 requester used for KUM-26 device acceptance."""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any


PROTOCOL_VERSION = 2
MAX_FRAME_BYTES = 128 * 1024


class ProtocolError(RuntimeError):
    pass


def canonical_uuid(raw: str, field: str) -> str:
    try:
        parsed = uuid.UUID(raw)
    except ValueError as exc:
        raise ProtocolError(f"{field} must be a canonical UUID") from exc
    canonical = str(parsed)
    if canonical != raw:
        raise ProtocolError(f"{field} must be a lowercase canonical UUID")
    return canonical


def new_uuid() -> str:
    return str(uuid.uuid4())


def envelope(
    message_type: str,
    attempt_id: str,
    source_device_id: str,
    target_device_id: str,
    source_session_id: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    for field, value in (
        ("attemptId", attempt_id),
        ("sourceDeviceId", source_device_id),
        ("targetDeviceId", target_device_id),
        ("sourceSessionId", source_session_id),
    ):
        canonical_uuid(value, field)
    if source_device_id == target_device_id:
        raise ProtocolError("sourceDeviceId and targetDeviceId must differ")
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "type": message_type,
        "attemptId": attempt_id,
        "sourceDeviceId": source_device_id,
        "targetDeviceId": target_device_id,
        "sourceSessionId": source_session_id,
        "payload": payload,
    }


def encode_frame(value: dict[str, Any]) -> bytes:
    body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if not 1 <= len(body) <= MAX_FRAME_BYTES:
        raise ProtocolError(f"invalid frame length: {len(body)}")
    return struct.pack(">I", len(body)) + body


def recv_exact(sock: socket.socket, length: int) -> bytes:
    chunks: list[bytes] = []
    remaining = length
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ProtocolError("peer closed the channel")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def read_frame(sock: socket.socket) -> dict[str, Any]:
    length = struct.unpack(">I", recv_exact(sock, 4))[0]
    if not 1 <= length <= MAX_FRAME_BYTES:
        raise ProtocolError(f"invalid frame length: {length}")
    try:
        value = json.loads(recv_exact(sock, length).decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("invalid JSON frame") from exc
    if not isinstance(value, dict):
        raise ProtocolError("frame root must be an object")
    return value


def validate_responder_frame(
    value: dict[str, Any],
    *,
    expected_type: str | None,
    attempt_id: str,
    requester_device_id: str,
    responder_device_id: str,
    responder_session_id: str | None,
) -> str:
    required = {
        "protocolVersion",
        "type",
        "attemptId",
        "sourceDeviceId",
        "targetDeviceId",
        "sourceSessionId",
        "payload",
    }
    if set(value) != required:
        raise ProtocolError(f"unexpected envelope keys: {sorted(value)}")
    if value["protocolVersion"] != PROTOCOL_VERSION:
        raise ProtocolError(f"unsupported protocolVersion: {value['protocolVersion']}")
    if expected_type is not None and value["type"] != expected_type:
        raise ProtocolError(f"expected {expected_type}, received {value['type']}")
    if value["attemptId"] != attempt_id:
        raise ProtocolError("response attemptId mismatch")
    if value["sourceDeviceId"] != responder_device_id:
        raise ProtocolError("response sourceDeviceId mismatch")
    if value["targetDeviceId"] != requester_device_id:
        raise ProtocolError("response targetDeviceId mismatch")
    session_id = canonical_uuid(value["sourceSessionId"], "sourceSessionId")
    if responder_session_id is not None and session_id != responder_session_id:
        raise ProtocolError("response sourceSessionId changed")
    if not isinstance(value["payload"], dict):
        raise ProtocolError("payload must be an object")
    return session_id


@dataclass(frozen=True)
class RequestIdentity:
    device_id: str
    session_id: str
    attempt_id: str
    target_device_id: str

    def __post_init__(self) -> None:
        canonical_uuid(self.device_id, "sourceDeviceId")
        canonical_uuid(self.session_id, "sourceSessionId")
        canonical_uuid(self.attempt_id, "attemptId")
        canonical_uuid(self.target_device_id, "targetDeviceId")
        if self.device_id == self.target_device_id:
            raise ProtocolError("requester and target must differ")


def emit(event: str, **fields: Any) -> None:
    print(
        json.dumps(
            {"monotonicMs": round(time.monotonic() * 1000), "event": event, **fields},
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        flush=True,
    )


def run_request(args: argparse.Namespace) -> int:
    identity = RequestIdentity(
        device_id=args.source_device_id or new_uuid(),
        session_id=args.source_session_id or new_uuid(),
        attempt_id=args.attempt_id or new_uuid(),
        target_device_id=args.target_device_id,
    )
    emit("request_identity", **identity.__dict__)
    with socket.create_connection((args.host, args.port), timeout=args.timeout) as sock:
        sock.settimeout(args.timeout)
        hello = envelope(
            "HELLO",
            identity.attempt_id,
            identity.device_id,
            identity.target_device_id,
            identity.session_id,
            {
                "requestRole": "REQUESTER",
                "nickname": args.nickname,
                "deviceName": args.device_name,
                "capabilities": [],
            },
        )
        sock.sendall(encode_frame(hello))
        emit("sent", type="HELLO")

        response_hello = read_frame(sock)
        responder_session_id = validate_responder_frame(
            response_hello,
            expected_type="HELLO",
            attempt_id=identity.attempt_id,
            requester_device_id=identity.device_id,
            responder_device_id=identity.target_device_id,
            responder_session_id=None,
        )
        if response_hello["payload"].get("requestRole") != "RESPONDER":
            raise ProtocolError("HELLO response is not RESPONDER")
        emit("received", type="HELLO", responderSessionId=responder_session_id)

        if args.delay_before_request:
            emit("delay_before_request", milliseconds=round(args.delay_before_request * 1000))
            time.sleep(args.delay_before_request)

        request = envelope(
            "CONNECT_REQUEST",
            identity.attempt_id,
            identity.device_id,
            identity.target_device_id,
            identity.session_id,
            {"trigger": "USER", "preferredTransportHint": "LAN"},
        )
        sock.sendall(encode_frame(request))
        emit("sent", type="CONNECT_REQUEST")

        response = read_frame(sock)
        validate_responder_frame(
            response,
            expected_type=args.expect,
            attempt_id=identity.attempt_id,
            requester_device_id=identity.device_id,
            responder_device_id=identity.target_device_id,
            responder_session_id=responder_session_id,
        )
        emit("received", type=response["type"], payload=response["payload"])
        if args.hold_after_response:
            time.sleep(args.hold_after_response)
        return 0


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)
    request = subcommands.add_parser("request", help="send HELLO and CONNECT_REQUEST")
    request.add_argument("--host", required=True)
    request.add_argument("--port", type=int, default=8890)
    request.add_argument("--target-device-id", required=True)
    request.add_argument("--source-device-id")
    request.add_argument("--source-session-id")
    request.add_argument("--attempt-id")
    request.add_argument("--nickname", default="PC 验收端 C")
    request.add_argument("--device-name", default="Windows PC")
    request.add_argument("--delay-before-request", type=float, default=0.0)
    request.add_argument("--hold-after-response", type=float, default=0.0)
    request.add_argument("--timeout", type=float, default=5.0)
    request.add_argument(
        "--expect",
        choices=("CONNECT_ACCEPT", "CONNECT_REJECT", "BUSY"),
    )
    request.set_defaults(handler=run_request)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        return args.handler(args)
    except (OSError, ProtocolError) as exc:
        emit("failure", error=str(exc))
        return 1


if __name__ == "__main__":
    sys.exit(main())
