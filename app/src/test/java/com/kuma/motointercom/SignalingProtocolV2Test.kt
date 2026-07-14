package com.kuma.motointercom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class SignalingProtocolV2Test {
    @Test
    fun roundTripsEveryEnabledV2MessageWithRequiredEnvelopeIdentity() {
        val messages = listOf<SignalingMessageV2>(
            SignalingMessageV2.Hello(
                RequestRole.REQUESTER,
                "Rider A",
                "Phone A",
                setOf("AUDIO", "IDENTITY_V2")
            ),
            SignalingMessageV2.ConnectRequest(
                RequestTrigger.AUTO_PAIRED,
                Transport.WIFI_DIRECT
            ),
            SignalingMessageV2.ConnectAccept("Rider B", "Phone B"),
            SignalingMessageV2.ConnectReject(
                RejectReason.UNSUPPORTED_VERSION,
                retryable = false
            ),
            SignalingMessageV2.Busy(BusyReason.parse("ACTIVE_ATTEMPT"), 2_000L),
            SignalingMessageV2.Disconnect(DisconnectReason.parse("USER_REQUESTED")),
            SignalingMessageV2.Offer("{\"type\":\"offer\",\"sdp\":\"v=0\"}"),
            SignalingMessageV2.Answer("{\"type\":\"answer\",\"sdp\":\"v=0\"}"),
            SignalingMessageV2.Candidate("{\"candidate\":\"candidate:1\",\"sdpMid\":\"0\"}")
        )

        messages.forEach { message ->
            val codec = SignalingV2Codec()
            val expected = envelope(message)
            assertEquals(expected, codec.decode(codec.encode(expected)))
        }
    }

    @Test
    fun roundTripsEveryApprovedRequestTrigger() {
        RequestTrigger.entries.forEach { trigger ->
            val message = SignalingMessageV2.ConnectRequest(
                trigger = trigger,
                preferredTransportHint = Transport.LAN
            )
            val expected = envelope(message)

            assertEquals(expected, SignalingV2Codec().decode(SignalingV2Codec().encode(expected)))
        }
    }

    @Test
    fun helloCapabilitiesAreBoundedUniqueStrings() {
        val empty = envelope(
            SignalingMessageV2.Hello(
                requestRole = RequestRole.REQUESTER,
                capabilities = emptySet()
            )
        )
        assertEquals(empty, SignalingV2Codec().decode(SignalingV2Codec().encode(empty)))

        val invalidFrames = listOf(
            rawFrame(
                type = "HELLO",
                payload = "{\"requestRole\":\"REQUESTER\",\"capabilities\":[\"AUDIO\",\"AUDIO\"]}"
            ),
            rawFrame(
                type = "HELLO",
                payload = "{\"requestRole\":\"REQUESTER\",\"capabilities\":[1]}"
            ),
            rawFrame(
                type = "HELLO",
                payload = "{\"requestRole\":\"REQUESTER\",\"capabilities\":[\"${"c".repeat(SignalingV2Codec.MAX_CAPABILITY_CODE_POINTS + 1)}\"]}"
            ),
            rawFrame(
                type = "HELLO",
                payload = "{\"requestRole\":\"REQUESTER\",\"capabilities\":[]}".replace(
                    "[]",
                    (0..SignalingV2Codec.MAX_CAPABILITIES).joinToString(
                        prefix = "[",
                        postfix = "]"
                    ) { "\"C$it\"" }
                )
            )
        )

        invalidFrames.forEach { frame ->
            assertThrows(SignalingV2Exception::class.java) {
                SignalingV2Codec().decode(frame.toByteArray())
            }
        }
        assertThrows(SignalingV2Exception::class.java) {
            SignalingV2Codec().encode(
                envelope(
                    SignalingMessageV2.Hello(
                        requestRole = RequestRole.REQUESTER,
                        capabilities = setOf("AUDIO", " AUDIO ")
                    )
                )
            )
        }
    }

    @Test
    fun approvedControlPayloadsDecodeWithExactKeys() {
        val expected = listOf<SignalingMessageV2>(
            SignalingMessageV2.Hello(
                RequestRole.REQUESTER,
                "Rider A",
                "Phone A",
                setOf("AUDIO")
            ),
            SignalingMessageV2.ConnectRequest(RequestTrigger.USER, Transport.LAN),
            SignalingMessageV2.ConnectAccept("Rider B", "Phone B"),
            SignalingMessageV2.ConnectReject(RejectReason.USER_REJECTED, retryable = false),
            SignalingMessageV2.Busy(BusyReason.parse("ACTIVE_ATTEMPT"), null),
            SignalingMessageV2.Busy(BusyReason.parse("ACTIVE_ATTEMPT"), 1_500L),
            SignalingMessageV2.Disconnect(DisconnectReason.parse("REMOTE_REQUESTED"))
        )

        expected.forEach { message ->
            val envelope = envelope(message)
            assertEquals(envelope, SignalingV2Codec().decode(SignalingV2Codec().encode(envelope)))
        }
    }

    @Test
    fun rejectsMissingMalformedAndOutOfBoundsControlFields() {
        val invalidFrames = listOf(
            rawFrame(payload = "{}"),
            rawFrame(payload = "{\"trigger\":true}"),
            rawFrame(type = "CONNECT_ACCEPT", payload = "{\"nickname\":\"Rider B\"}"),
            rawFrame(
                type = "CONNECT_ACCEPT",
                payload = "{\"nickname\":\"\",\"deviceName\":\"Phone B\"}"
            ),
            rawFrame(
                type = "CONNECT_ACCEPT",
                payload = "{\"nickname\":\"${"n".repeat(SignalingV2Codec.MAX_NICKNAME_CODE_POINTS + 1)}\",\"deviceName\":\"Phone B\"}"
            ),
            rawFrame(
                type = "CONNECT_REJECT",
                payload = "{\"reason\":\"USER_REJECTED\"}"
            ),
            rawFrame(
                type = "CONNECT_REJECT",
                payload = "{\"reason\":\"USER_REJECTED\",\"retryable\":\"false\"}"
            ),
            rawFrame(type = "BUSY", payload = "{}"),
            rawFrame(
                type = "BUSY",
                payload = "{\"reason\":\"ACTIVE_ATTEMPT\",\"retryAfterMs\":-1}"
            ),
            rawFrame(
                type = "BUSY",
                payload = "{\"reason\":\"active_attempt\"}"
            ),
            rawFrame(type = "DISCONNECT", payload = "{}"),
            rawFrame(
                type = "DISCONNECT",
                payload = "{\"reason\":1}"
            )
        )

        invalidFrames.forEach { frame ->
            assertThrows(SignalingV2Exception::class.java) {
                SignalingV2Codec().decode(frame.toByteArray())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalingMessageV2.Busy(BusyReason.parse("ACTIVE_ATTEMPT"), -1L)
        }
    }

    @Test
    fun rejectsUnknownVersionTypeFieldsAndNonCanonicalIds() {
        val valid = rawFrame()
        val invalidFrames = listOf(
            valid.replace("\"protocolVersion\":2", "\"protocolVersion\":1"),
            valid.replace("\"CONNECT_REQUEST\"", "\"PING\""),
            valid.replace(
                "\"payload\":{\"trigger\":\"USER\"}",
                "\"payload\":{\"trigger\":\"USER\"},\"extra\":true"
            ),
            valid.replace(DEVICE_A, DEVICE_A.uppercase()),
            valid.replace(DEVICE_B, DEVICE_A),
            valid.replace("\"targetDeviceId\":\"$DEVICE_B\",", "")
        )

        invalidFrames.forEach { frame ->
            assertThrows(SignalingV2Exception::class.java) {
                SignalingV2Codec().decode(frame.toByteArray())
            }
        }
    }

    @Test
    fun rejectsWrongPayloadShapeAndUnknownPayloadFields() {
        val invalidFrames = listOf(
            rawFrame(payload = "[]"),
            rawFrame(payload = "{\"unexpected\":true}"),
            rawFrame(
                type = "CONNECT_REJECT",
                payload = "{\"reason\":\"NOT_A_REASON\",\"retryable\":false}"
            ),
            rawFrame(
                type = "HELLO",
                payload = "{\"requestRole\":\"REQUESTER\",\"capabilities\":[],\"extra\":1}"
            )
        )

        invalidFrames.forEach { frame ->
            assertThrows(SignalingV2Exception::class.java) {
                SignalingV2Codec().decode(frame.toByteArray())
            }
        }
    }

    @Test
    fun enforcesFrameSdpCandidateAndCandidateCountLimits() {
        assertThrows(SignalingV2Exception::class.java) {
            SignalingV2Codec().decode(ByteArray(SignalingV2Codec.MAX_FRAME_BYTES + 1))
        }
        assertThrows(SignalingV2Exception::class.java) {
            SignalingV2Codec().encode(
                envelope(SignalingMessageV2.Offer("s".repeat(SignalingV2Codec.MAX_SDP_BYTES + 1)))
            )
        }
        assertThrows(SignalingV2Exception::class.java) {
            SignalingV2Codec().encode(
                envelope(
                    SignalingMessageV2.Candidate(
                        "{\"candidate\":\"${"c".repeat(SignalingV2Codec.MAX_CANDIDATE_BYTES)}\"}"
                    )
                )
            )
        }

        val encoder = SignalingV2Codec()
        val candidateEnvelope = envelope(SignalingMessageV2.Candidate("{\"candidate\":\"c\"}"))
        repeat(SignalingV2Codec.MAX_CANDIDATES) { encoder.encode(candidateEnvelope) }
        assertThrows(SignalingV2Exception::class.java) { encoder.encode(candidateEnvelope) }

        val frame = SignalingV2Codec().encode(candidateEnvelope)
        val decoder = SignalingV2Codec()
        repeat(SignalingV2Codec.MAX_CANDIDATES) { decoder.decode(frame) }
        assertThrows(SignalingV2Exception::class.java) { decoder.decode(frame) }
    }

    @Test
    fun requesterAndResponderFollowRequesterFirstHandshakeAndMediaGate() {
        val requester = SignalingPhaseMachine(RequestRole.REQUESTER)
        requester.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        assertEquals(SignalingPhase.AWAITING_RESPONDER_HELLO, requester.phase)
        requester.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.Hello(RequestRole.RESPONDER)
        )
        requester.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.ConnectRequest(RequestTrigger.USER)
        )
        assertThrows(SignalingV2Exception::class.java) {
            requester.onFrame(FrameDirection.OUTBOUND, SignalingMessageV2.Offer("offer"))
        }
        requester.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.ConnectAccept("Rider B", "Phone B")
        )
        requester.onFrame(FrameDirection.OUTBOUND, SignalingMessageV2.Offer("offer"))
        requester.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.Candidate("{\"candidate\":\"c\"}")
        )
        requester.onFrame(FrameDirection.INBOUND, SignalingMessageV2.Answer("answer"))
        requester.markConnected()
        assertEquals(SignalingPhase.CONNECTED, requester.phase)

        val responder = SignalingPhaseMachine(initialRequestRole = null)
        assertThrows(SignalingV2Exception::class.java) {
            responder.onFrame(
                FrameDirection.OUTBOUND,
                SignalingMessageV2.Hello(RequestRole.RESPONDER)
            )
        }
        responder.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        assertEquals(RequestRole.RESPONDER, responder.requestRole)
        responder.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.Hello(RequestRole.RESPONDER)
        )
        responder.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.ConnectRequest(RequestTrigger.USER)
        )
        assertThrows(SignalingV2Exception::class.java) {
            responder.onFrame(FrameDirection.INBOUND, SignalingMessageV2.Offer("offer"))
        }
        responder.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.ConnectAccept("Rider B", "Phone B")
        )
        responder.onFrame(FrameDirection.INBOUND, SignalingMessageV2.Offer("offer"))
        responder.onFrame(FrameDirection.OUTBOUND, SignalingMessageV2.Answer("answer"))
        responder.markConnected()
        assertEquals(SignalingPhase.CONNECTED, responder.phase)
    }

    @Test
    fun simultaneousRequesterHelloRequiresExplicitGlareResolution() {
        val winner = SignalingPhaseMachine(RequestRole.REQUESTER)
        winner.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        winner.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        assertEquals(SignalingPhase.GLARE_PENDING, winner.phase)
        winner.resolveGlare(localRequestWins = true)
        assertEquals(SignalingPhase.AWAITING_RESPONDER_HELLO, winner.phase)
        assertEquals(RequestRole.REQUESTER, winner.requestRole)

        val loser = SignalingPhaseMachine(RequestRole.REQUESTER)
        loser.onFrame(
            FrameDirection.OUTBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        loser.onFrame(
            FrameDirection.INBOUND,
            SignalingMessageV2.Hello(RequestRole.REQUESTER)
        )
        loser.resolveGlare(localRequestWins = false)
        assertEquals(SignalingPhase.READY_TO_SEND_RESPONDER_HELLO, loser.phase)
        assertEquals(RequestRole.RESPONDER, loser.requestRole)
    }

    @Test
    fun framingUsesBoundedLengthPrefixedFrames() {
        val frame = SignalingV2Codec().encode(
            envelope(SignalingMessageV2.ConnectRequest(RequestTrigger.USER))
        )
        val output = ByteArrayOutputStream()
        SignalingV2Framing.write(DataOutputStream(output), frame)

        assertArrayEquals(
            frame,
            SignalingV2Framing.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        )

        val invalid = ByteArrayOutputStream().also {
            DataOutputStream(it).writeInt(SignalingV2Codec.MAX_FRAME_BYTES + 1)
        }
        assertThrows(SignalingV2Exception::class.java) {
            SignalingV2Framing.read(DataInputStream(ByteArrayInputStream(invalid.toByteArray())))
        }
    }

    private fun envelope(message: SignalingMessageV2) = SignalingEnvelopeV2(
        attemptId = ConnectionAttemptId(ATTEMPT_A),
        sourceDeviceId = DeviceId.parse(DEVICE_A),
        targetDeviceId = DeviceId.parse(DEVICE_B),
        sourceSessionId = RuntimeSessionId(SESSION_A),
        message = message
    )

    private fun rawFrame(
        type: String = "CONNECT_REQUEST",
        payload: String = "{\"trigger\":\"USER\"}"
    ): String = """
        {
          "protocolVersion":2,
          "type":"$type",
          "attemptId":"$ATTEMPT_A",
          "sourceDeviceId":"$DEVICE_A",
          "targetDeviceId":"$DEVICE_B",
          "sourceSessionId":"$SESSION_A",
          "payload":$payload
        }
    """.trimIndent()

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
    }
}
