package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCandidateContextTest {
    private val runtime = RuntimeSessionId(RUNTIME_A)
    private val remoteRuntime = RuntimeSessionId(RUNTIME_B)
    private val attempt = ConnectionAttempt(
        id = ConnectionAttemptId(ATTEMPT_A),
        runtimeSessionId = runtime,
        targetLock = TargetLock(DEVICE_B, remoteRuntime),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 10_000L
    )
    private val wireRequestKey = WireRequestKey(
        requesterDeviceId = DeviceId.parse(DEVICE_A),
        requesterSessionId = runtime,
        attemptId = attempt.id,
        responderDeviceId = DeviceId.parse(DEVICE_B)
    )
    private val channelId = ControlChannelId.parse(CHANNEL_A)
    private val peer = PeerIdentity(
        deviceId = DEVICE_B,
        nickname = "Rider B",
        deviceName = "Phone B",
        runtimeSessionId = remoteRuntime,
        isDeviceIdVerified = true
    )
    private val candidate = ConnectionCandidateContext(
        attempt = attempt,
        channelId = channelId,
        wireRequestKey = wireRequestKey,
        targetLock = attempt.targetLock,
        transport = Transport.LAN,
        requestRole = RequestRole.REQUESTER,
        peer = peer
    )

    @Test
    fun currentWinnerRequiresTheExactCandidateContext() {
        val active = activeAttempt()

        assertTrue(isCurrentMediaCandidate(attempt, active, candidate))
        assertFalse(isCurrentMediaCandidate(null, active, candidate))
        assertFalse(
            isCurrentMediaCandidate(
                attempt,
                active,
                candidate.copy(channelId = ControlChannelId.parse(CHANNEL_B))
            )
        )
        assertFalse(
            isCurrentMediaCandidate(
                attempt,
                active.copy(phase = SignalingAttemptPhase.TERMINATING),
                candidate
            )
        )
    }

    @Test
    fun selectionRequiresTheCurrentAttemptWireKeyAndCandidateSet() {
        val selecting = activeAttempt().copy(
            phase = SignalingAttemptPhase.SELECTING_MEDIA,
            mediaOwnerChannelId = null,
            terminalOutcome = null
        )

        assertTrue(
            isCurrentSelectionCandidate(attempt, selecting, candidate, wireRequestKey)
        )
        assertFalse(
            isCurrentSelectionCandidate(
                attempt,
                selecting,
                candidate.copy(channelId = ControlChannelId.parse(CHANNEL_B)),
                wireRequestKey
            )
        )
        assertFalse(
            isCurrentSelectionCandidate(
                attempt,
                selecting.copy(phase = SignalingAttemptPhase.ACCEPTED),
                candidate,
                wireRequestKey
            )
        )
    }

    @Test
    fun contextRejectsWrongAttemptTargetTransportRoleAndPeer() {
        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(
                wireRequestKey = wireRequestKey.copy(
                    attemptId = ConnectionAttemptId(ATTEMPT_B)
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(targetLock = TargetLock(DEVICE_C, RuntimeSessionId(RUNTIME_C)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(transport = Transport.WIFI_DIRECT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(requestRole = RequestRole.RESPONDER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            candidate.copy(peer = peer.copy(deviceId = DEVICE_C))
        }
    }

    private fun activeAttempt() = AttemptChannelSet(
        wireRequestKey = wireRequestKey,
        attempt = attempt,
        peer = peer,
        channelIds = setOf(channelId),
        phase = SignalingAttemptPhase.ACCEPTED,
        mediaOwnerChannelId = channelId,
        terminalOutcome = AttemptOutcome.ACCEPTED
    )

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val DEVICE_C = "c0000000-0000-4000-8000-000000000003"
        const val RUNTIME_A = "10000000-0000-4000-8000-000000000001"
        const val RUNTIME_B = "10000000-0000-4000-8000-000000000002"
        const val RUNTIME_C = "10000000-0000-4000-8000-000000000003"
        const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
        const val ATTEMPT_B = "20000000-0000-4000-8000-000000000002"
        const val CHANNEL_A = "30000000-0000-4000-8000-000000000001"
        const val CHANNEL_B = "30000000-0000-4000-8000-000000000002"
    }
}
