package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryTimingInstrumentationTest {
    @Test
    fun preferredTransportRecoversBeforeT3AndLateFallbackIsInert() {
        val fixture = fixture(Transport.WIFI_DIRECT)
        fixture.clock.elapsedMs = 2_999L

        val connected = fixture.connectOn(Transport.WIFI_DIRECT)

        assertEquals(Transport.WIFI_DIRECT, connected.transport)
        fixture.clock.elapsedMs = 3_000L
        assertFalse(
            requireNotNull(
                fixture.coordinator.handle(
                    connected,
                    SessionEvent.AttemptMilestoneElapsed(fixture.milestone)
                )
            ).accepted
        )
        record(
            "preferred",
            fixture.recovering.attempt,
            elapsedMs = 2_999L,
            finalTransport = connected.transport
        )
    }

    @Test
    fun alternateTransportOpensAtT3AndCanBecomeTheOnlyWinner() {
        val fixture = fixture(Transport.WIFI_DIRECT)
        fixture.clock.elapsedMs = 3_000L

        val fallback = requireNotNull(
            fixture.coordinator.handle(
                fixture.recovering,
                SessionEvent.AttemptMilestoneElapsed(fixture.milestone)
            )
        )
        assertTrue(fallback.accepted)
        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    fixture.recovering.attempt,
                    Transport.LAN
                )
            ),
            fallback.effects
        )

        val connected = fixture.connectOn(Transport.LAN)

        assertEquals(Transport.LAN, connected.transport)
        assertEquals(10_000L, connected.attempt.deadlineElapsedRealtimeMs)
        record(
            "fallback",
            fixture.recovering.attempt,
            elapsedMs = 3_000L,
            finalTransport = connected.transport
        )
    }

    private fun fixture(lastTransport: Transport): Fixture {
        val clock = MutableClock()
        val ids = ArrayDeque(
            listOf(
                ConnectionAttemptId(OUTBOUND_ATTEMPT),
                ConnectionAttemptId(RECOVERY_ATTEMPT)
            )
        )
        val coordinator = SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::removeFirst
        )
        val connecting = requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(LOCAL_RUNTIME),
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = LOCAL_RUNTIME,
                    targetDeviceId = REMOTE_DEVICE,
                    targetSessionId = REMOTE_RUNTIME,
                    availableTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        ).state as IntercomState.Connecting
        val recoveryDecision = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    attempt = connecting.attempt,
                    peer = verifiedPeer(),
                    connectedAt = 1L,
                    transport = lastTransport
                ),
                SessionEvent.WebRtcStateChanged(
                    LOCAL_RUNTIME,
                    connecting.attempt.id,
                    WebRtcConnectionState.DISCONNECTED,
                    occurredAt = 2L
                )
            )
        )
        val recovering = recoveryDecision.state as IntercomState.Recovering
        val milestone = (
            recoveryDecision.effects.single { it is SessionEffect.ScheduleAttemptMilestone }
                as SessionEffect.ScheduleAttemptMilestone
            ).milestone as AttemptMilestone.FallbackTransport
        assertEquals(MonotonicTimestamp(3_000L), milestone.scheduledAt)
        return Fixture(clock, coordinator, recovering, milestone)
    }

    private fun record(
        scenario: String,
        attempt: ConnectionAttempt,
        elapsedMs: Long,
        finalTransport: Transport
    ) {
        val evidence =
            "KUM33_RECOVERY scenario=$scenario attempt=${attempt.id.value} " +
                "target=${attempt.targetDeviceId} preferred=${attempt.preferredTransport} " +
                "fallback=${attempt.channelPlan.fallbackTransport} fastWindowMs=3000 " +
                "elapsedMs=$elapsedMs finalTransport=$finalTransport " +
                "deadlineMs=${attempt.deadlineElapsedRealtimeMs}"
        println(evidence)
        android.util.Log.i("MotoComKum33", evidence)
    }

    private fun verifiedPeer() = PeerIdentity(
        deviceId = REMOTE_DEVICE,
        nickname = "Rider B",
        runtimeSessionId = REMOTE_RUNTIME,
        isDeviceIdVerified = true
    )

    private class MutableClock(var elapsedMs: Long = 0L) : MonotonicClock {
        override fun now() = MonotonicTimestamp(elapsedMs)
    }

    private data class Fixture(
        val clock: MutableClock,
        val coordinator: SignalingControlCoordinator,
        val recovering: IntercomState.Recovering,
        val milestone: AttemptMilestone.FallbackTransport
    ) {
        fun connectOn(transport: Transport): IntercomState.Connected {
            val attempt = recovering.attempt
            val channel = requesterChannel(attempt, transport)
            val verified = requireNotNull(
                coordinator.handle(
                    recovering,
                    SessionEvent.ControlChannelVerified(LOCAL_RUNTIME, channel)
                )
            )
            assertTrue(verified.accepted)
            val awaitingRemote = verified.state ?: recovering
            val accepted = requireNotNull(
                coordinator.handle(
                    awaitingRemote,
                    SessionEvent.RemoteConnectAccepted(
                        LOCAL_RUNTIME,
                        attempt.id,
                        channel.channelId,
                        channel.wireRequestKey
                    )
                )
            )
            assertTrue(accepted.accepted)
            assertTrue(accepted.effects.single() is SessionEffect.StartWebRtc)
            val startingMedia = accepted.state ?: awaitingRemote
            val connected = requireNotNull(
                coordinator.handle(
                    startingMedia,
                    SessionEvent.WebRtcStateChanged(
                        LOCAL_RUNTIME,
                        attempt.id,
                        WebRtcConnectionState.CONNECTED,
                        clock.elapsedMs
                    )
                )
            )
            assertTrue(connected.accepted)
            return connected.state as IntercomState.Connected
        }

        private fun requesterChannel(
            attempt: ConnectionAttempt,
            transport: Transport
        ): VerifiedControlChannel {
            val wireKey = WireRequestKey(
                requesterDeviceId = DeviceId.parse(LOCAL_DEVICE),
                requesterSessionId = LOCAL_RUNTIME,
                attemptId = attempt.id,
                responderDeviceId = DeviceId.parse(REMOTE_DEVICE)
            )
            return VerifiedControlChannel(
                channelId = ControlChannelId.parse(
                    if (transport == Transport.LAN) LAN_CHANNEL else WIFI_CHANNEL
                ),
                transport = transport,
                requestRole = RequestRole.REQUESTER,
                wireRequestKey = wireKey,
                targetLock = attempt.targetLock,
                peer = PeerIdentity(
                    deviceId = REMOTE_DEVICE,
                    nickname = "Rider B",
                    runtimeSessionId = REMOTE_RUNTIME,
                    isDeviceIdVerified = true
                ),
                originatingAttempt = attempt
            )
        }
    }

    private companion object {
        val LOCAL_RUNTIME = RuntimeSessionId("40000000-0000-4000-8000-000000000001")
        val REMOTE_RUNTIME = RuntimeSessionId("40000000-0000-4000-8000-000000000002")
        const val LOCAL_DEVICE = "10000000-0000-4000-8000-000000000001"
        const val REMOTE_DEVICE = "10000000-0000-4000-8000-000000000002"
        const val OUTBOUND_ATTEMPT = "20000000-0000-4000-8000-000000000001"
        const val RECOVERY_ATTEMPT = "20000000-0000-4000-8000-000000000002"
        const val LAN_CHANNEL = "30000000-0000-4000-8000-000000000001"
        const val WIFI_CHANNEL = "30000000-0000-4000-8000-000000000002"
    }
}
