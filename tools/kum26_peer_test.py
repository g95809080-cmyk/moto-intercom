import json
import socket
import struct
import threading
import time
import unittest

from kum26_peer import (
    ProtocolError,
    encode_frame,
    envelope,
    read_frame,
    validate_responder_frame,
)


ATTEMPT = "10000000-0000-4000-8000-000000000001"
REQUESTER = "20000000-0000-4000-8000-000000000001"
RESPONDER = "30000000-0000-4000-8000-000000000001"
REQUESTER_SESSION = "40000000-0000-4000-8000-000000000001"
RESPONDER_SESSION = "50000000-0000-4000-8000-000000000001"


class Kum26PeerTest(unittest.TestCase):
    def test_framing_round_trip(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        value = envelope(
            "CONNECT_REQUEST",
            ATTEMPT,
            REQUESTER,
            RESPONDER,
            REQUESTER_SESSION,
            {"trigger": "USER", "preferredTransportHint": "LAN"},
        )
        left.sendall(encode_frame(value))
        self.assertEqual(value, read_frame(right))

    def test_rejects_oversized_frame_before_reading_body(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        left.sendall(struct.pack(">I", 128 * 1024 + 1))
        with self.assertRaisesRegex(ProtocolError, "invalid frame length"):
            read_frame(right)

    def test_rejects_malformed_json_frame(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        body = b"{"
        left.sendall(struct.pack(">I", len(body)) + body)
        with self.assertRaisesRegex(ProtocolError, "invalid JSON frame"):
            read_frame(right)

    def test_rejects_malformed_identity(self) -> None:
        with self.assertRaisesRegex(ProtocolError, "sourceDeviceId must be a canonical UUID"):
            envelope(
                "HELLO",
                ATTEMPT,
                "not-a-uuid",
                RESPONDER,
                REQUESTER_SESSION,
                {"requestRole": "REQUESTER", "capabilities": []},
            )

    def test_read_timeout_is_observable(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        right.settimeout(0.01)
        with self.assertRaises(TimeoutError):
            read_frame(right)

    def test_half_close_rejects_partial_frame(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        left.sendall(struct.pack(">I", 8) + b"abc")
        left.shutdown(socket.SHUT_WR)
        with self.assertRaisesRegex(ProtocolError, "peer closed the channel"):
            read_frame(right)

    def test_delayed_response_is_read_before_timeout(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(left.close)
        self.addCleanup(right.close)
        right.settimeout(1.0)
        value = envelope(
            "CONNECT_REJECT",
            ATTEMPT,
            RESPONDER,
            REQUESTER,
            RESPONDER_SESSION,
            {"reason": "USER_REJECTED"},
        )

        def send_delayed() -> None:
            time.sleep(0.02)
            left.sendall(encode_frame(value))

        sender = threading.Thread(target=send_delayed)
        sender.start()
        self.addCleanup(sender.join)
        self.assertEqual(value, read_frame(right))

    def test_duplicate_hello_is_rejected_when_response_is_expected(self) -> None:
        hello = envelope(
            "HELLO",
            ATTEMPT,
            RESPONDER,
            REQUESTER,
            RESPONDER_SESSION,
            {"requestRole": "RESPONDER", "capabilities": []},
        )
        with self.assertRaisesRegex(ProtocolError, "expected CONNECT_ACCEPT, received HELLO"):
            validate_responder_frame(
                hello,
                expected_type="CONNECT_ACCEPT",
                attempt_id=ATTEMPT,
                requester_device_id=REQUESTER,
                responder_device_id=RESPONDER,
                responder_session_id=RESPONDER_SESSION,
            )

    def test_socket_disconnect_is_fail_closed(self) -> None:
        left, right = socket.socketpair()
        self.addCleanup(right.close)
        left.close()
        with self.assertRaisesRegex(ProtocolError, "peer closed the channel"):
            read_frame(right)

    def test_responder_identity_is_pinned_across_frames(self) -> None:
        hello = envelope(
            "HELLO",
            ATTEMPT,
            RESPONDER,
            REQUESTER,
            RESPONDER_SESSION,
            {"requestRole": "RESPONDER", "capabilities": []},
        )
        session = validate_responder_frame(
            hello,
            expected_type="HELLO",
            attempt_id=ATTEMPT,
            requester_device_id=REQUESTER,
            responder_device_id=RESPONDER,
            responder_session_id=None,
        )
        busy = envelope(
            "BUSY",
            ATTEMPT,
            RESPONDER,
            REQUESTER,
            "60000000-0000-4000-8000-000000000001",
            {"reason": "ACTIVE_SESSION"},
        )
        with self.assertRaisesRegex(ProtocolError, "sourceSessionId changed"):
            validate_responder_frame(
                busy,
                expected_type="BUSY",
                attempt_id=ATTEMPT,
                requester_device_id=REQUESTER,
                responder_device_id=RESPONDER,
                responder_session_id=session,
            )

    def test_encoded_json_uses_exact_v2_envelope_keys(self) -> None:
        value = envelope(
            "HELLO",
            ATTEMPT,
            REQUESTER,
            RESPONDER,
            REQUESTER_SESSION,
            {"requestRole": "REQUESTER", "capabilities": []},
        )
        encoded = encode_frame(value)
        decoded = json.loads(encoded[4:].decode("utf-8"))
        self.assertEqual(
            {
                "protocolVersion",
                "type",
                "attemptId",
                "sourceDeviceId",
                "targetDeviceId",
                "sourceSessionId",
                "payload",
            },
            set(decoded),
        )


if __name__ == "__main__":
    unittest.main()
