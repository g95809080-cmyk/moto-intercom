package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalingV2TypesTest {
    @Test
    fun protocolIdsRequireCanonicalLowercaseUuids() {
        assertEquals(DEVICE_A, DeviceId.parse(DEVICE_A).value)
        assertEquals(CHANNEL_A, ControlChannelId.parse(CHANNEL_A).value)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceId.parse(DEVICE_A.uppercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlChannelId.parse("not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WireRequestKey(
                requesterDeviceId = DeviceId.parse(DEVICE_A),
                requesterSessionId = RuntimeSessionId("opaque-session"),
                attemptId = ConnectionAttemptId(ATTEMPT_A),
                responderDeviceId = DeviceId.parse(DEVICE_B)
            )
        }
    }

    @Test
    fun glareComparatorUsesCanonicalUuidBytesWithoutCaseFolding() {
        val lower = requestKey(ATTEMPT_A)
        val higher = requestKey(ATTEMPT_B)

        assertTrue(lower < higher)
        assertTrue(higher > lower)
        assertEquals(0, lower.compareTo(lower.copy()))
    }

    @Test
    fun pinnedIdentityValidatesBothEnvelopeDirections() {
        val key = requestKey(ATTEMPT_A)
        val pinned = PinnedChannelIdentity(
            localDeviceId = DeviceId.parse(DEVICE_A),
            localSessionId = RuntimeSessionId(SESSION_A),
            remoteDeviceId = DeviceId.parse(DEVICE_B),
            remoteSessionId = RuntimeSessionId(SESSION_B),
            wireRequestKey = key
        )
        val incoming = envelope(
            sourceDeviceId = DEVICE_B,
            targetDeviceId = DEVICE_A,
            sourceSessionId = SESSION_B,
            message = SignalingMessageV2.Hello(RequestRole.RESPONDER)
        )
        val outgoing = envelope(
            sourceDeviceId = DEVICE_A,
            targetDeviceId = DEVICE_B,
            sourceSessionId = SESSION_A,
            message = SignalingMessageV2.ConnectRequest(RequestTrigger.USER)
        )

        pinned.requireIncoming(incoming)
        pinned.requireOutgoing(outgoing)
        assertEquals(key, outgoing.requesterKey())

        assertThrows(SignalingV2Exception::class.java) {
            pinned.requireIncoming(incoming.copy(sourceSessionId = RuntimeSessionId(SESSION_C)))
        }
        assertThrows(SignalingV2Exception::class.java) {
            pinned.requireIncoming(incoming.copy(targetDeviceId = DeviceId.parse(DEVICE_C)))
        }
        assertThrows(SignalingV2Exception::class.java) {
            pinned.requireIncoming(incoming.copy(attemptId = ConnectionAttemptId(ATTEMPT_B)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            incoming.requesterKey()
        }
    }

    @Test
    fun responderPinnedContextRetainsRequesterOrientedWireKey() {
        val key = requestKey(ATTEMPT_A)
        val responderContext = PinnedChannelIdentity(
            localDeviceId = DeviceId.parse(DEVICE_B),
            localSessionId = RuntimeSessionId(SESSION_B),
            remoteDeviceId = DeviceId.parse(DEVICE_A),
            remoteSessionId = RuntimeSessionId(SESSION_A),
            wireRequestKey = key
        )

        responderContext.requireIncoming(
            envelope(
                sourceDeviceId = DEVICE_A,
                targetDeviceId = DEVICE_B,
                sourceSessionId = SESSION_A,
                message = SignalingMessageV2.ConnectRequest(RequestTrigger.USER)
            )
        )
        responderContext.requireOutgoing(
            envelope(
                sourceDeviceId = DEVICE_B,
                targetDeviceId = DEVICE_A,
                sourceSessionId = SESSION_B,
                message = SignalingMessageV2.ConnectAccept("Rider B", "Phone B")
            )
        )
    }

    @Test
    fun physicalSocketRoleDoesNotChooseRequestOrWebRtcRole() {
        val inbound = PendingControlChannel(
            channelId = ControlChannelId.parse(CHANNEL_A),
            transport = Transport.WIFI_DIRECT,
            physicalRole = PhysicalSocketRole.OPENER,
            requestRole = null,
            openedAtElapsedMs = 10L
        )

        assertNull(inbound.requestRole)
        assertEquals(WebRtcRole.OFFERER, RequestRole.REQUESTER.webRtcRole)
        assertEquals(WebRtcRole.ANSWERER, RequestRole.RESPONDER.webRtcRole)
    }

    @Test
    fun responseScopeSeparatesChannelAndAttemptOutcomes() {
        assertEquals(
            ResponseScope.CHANNEL,
            SignalingMessageV2.ConnectReject(RejectReason.SUPERSEDED_CHANNEL, retryable = false)
                .responseScopeOrNull()
        )
        assertEquals(
            ResponseScope.ATTEMPT,
            SignalingMessageV2.ConnectReject(RejectReason.USER_REJECTED, retryable = false)
                .responseScopeOrNull()
        )
        assertEquals(
            ResponseScope.ATTEMPT,
            SignalingMessageV2.ConnectReject(
                RejectReason.UNSUPPORTED_VERSION,
                retryable = false
            ).responseScopeOrNull()
        )
        assertEquals(
            ResponseScope.ATTEMPT,
            SignalingMessageV2.ConnectAccept("Rider B", "Phone B").responseScopeOrNull()
        )
        assertEquals(
            ResponseScope.ATTEMPT,
            SignalingMessageV2.Busy(BusyReason.parse("ACTIVE_ATTEMPT"), null)
                .responseScopeOrNull()
        )
        assertNull(
            SignalingMessageV2.Disconnect(DisconnectReason.parse("USER_REQUESTED"))
                .responseScopeOrNull()
        )
    }

    @Test
    fun wireReasonTokensAreStrictAndBounded() {
        assertEquals("ACTIVE_ATTEMPT", BusyReason.parse("ACTIVE_ATTEMPT").value)
        assertEquals("USER_REQUESTED", DisconnectReason.parse("USER_REQUESTED").value)
        assertThrows(IllegalArgumentException::class.java) {
            BusyReason.parse("active_attempt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DisconnectReason.parse("A".repeat(65))
        }
    }

    private fun requestKey(attemptId: String) = WireRequestKey(
        requesterDeviceId = DeviceId.parse(DEVICE_A),
        requesterSessionId = RuntimeSessionId(SESSION_A),
        attemptId = ConnectionAttemptId(attemptId),
        responderDeviceId = DeviceId.parse(DEVICE_B)
    )

    private fun envelope(
        sourceDeviceId: String,
        targetDeviceId: String,
        sourceSessionId: String,
        message: SignalingMessageV2
    ) = SignalingEnvelopeV2(
        attemptId = ConnectionAttemptId(ATTEMPT_A),
        sourceDeviceId = DeviceId.parse(sourceDeviceId),
        targetDeviceId = DeviceId.parse(targetDeviceId),
        sourceSessionId = RuntimeSessionId(sourceSessionId),
        message = message
    )

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val DEVICE_C = "c0000000-0000-4000-8000-000000000003"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val SESSION_B = "10000000-0000-4000-8000-000000000002"
        const val SESSION_C = "10000000-0000-4000-8000-000000000003"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
        const val ATTEMPT_B = "20000000-0000-4000-8000-000000000002"
        const val CHANNEL_A = "30000000-0000-4000-8000-000000000001"
    }
}
