package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Sprint4FinalAcceptanceInstrumentationTest {
    @Test
    fun thirdNodeCannotReplaceTargetAndBAloneWinsAtFallback() {
        val clock = MutableClock()
        val coordinator = coordinator(clock, OUTBOUND_ATTEMPT, RECOVERY_ATTEMPT_1)
        val connecting = connect(coordinator)
        val recoveryDecision = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.WIFI_DIRECT
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

        val thirdNode = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.ConnectPresenceRequested(
                    LOCAL_RUNTIME,
                    REMOTE_DEVICE_C,
                    REMOTE_RUNTIME_C,
                    setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        )
        assertFalse(thirdNode.accepted)
        assertEquals(connecting.attempt.targetLock, recovering.attempt.targetLock)
        assertEquals(REMOTE_DEVICE_B, coordinator.currentAttempt?.targetDeviceId)

        clock.elapsedMs = 3_000L
        val fallback = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.AttemptMilestoneElapsed(milestone)
            )
        )
        assertTrue(fallback.accepted)
        assertEquals(
            listOf(SessionEffect.OpenTargetedTransport(recovering.attempt, Transport.LAN)),
            fallback.effects
        )

        val channel = requesterChannel(recovering.attempt, Transport.LAN)
        val verified = requireNotNull(
            coordinator.handle(
                fallback.state ?: recovering,
                SessionEvent.ControlChannelVerified(LOCAL_RUNTIME, channel)
            )
        )
        assertTrue(verified.accepted)
        val accepted = requireNotNull(
            coordinator.handle(
                verified.state ?: recovering,
                SessionEvent.RemoteConnectAccepted(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    channel.channelId,
                    channel.wireRequestKey
                )
            )
        )
        assertTrue(accepted.accepted)
        assertTrue(accepted.effects.single() is SessionEffect.StartWebRtc)
        val connected = requireNotNull(
            coordinator.handle(
                accepted.state ?: recovering,
                SessionEvent.WebRtcStateChanged(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    occurredAt = clock.elapsedMs
                )
            )
        )

        assertEquals(REMOTE_DEVICE_B, (connected.state as IntercomState.Connected).peer.deviceId)
        assertEquals(channel.channelId, coordinator.activeAttempt?.mediaOwnerChannelId)
        assertEquals(10_000L, recovering.attempt.deadlineElapsedRealtimeMs)
        record(
            "third-node",
            "target=${recovering.attempt.targetDeviceId} rejected=$REMOTE_DEVICE_C " +
                "fallbackAt=${milestone.scheduledAt.elapsedRealtimeMs} " +
                "owner=${channel.channelId.value}"
        )
    }

    @Test
    fun threeFinalFailuresResetExactlyAndRejectStaleCompletion() {
        val clock = MutableClock()
        val coordinator = coordinator(
            clock,
            OUTBOUND_ATTEMPT,
            RECOVERY_ATTEMPT_1,
            RECOVERY_ATTEMPT_2,
            RECOVERY_ATTEMPT_3
        )
        val connecting = connect(coordinator)
        var state: IntercomState = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.LAN
                ),
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        ).state as IntercomState.Recovering
        val attempts = mutableListOf<ConnectionAttempt>()

        repeat(3) { index ->
            val recovering = state as IntercomState.Recovering
            attempts += recovering.attempt
            clock.elapsedMs = recovering.attempt.deadlineElapsedRealtimeMs
            val decision = requireNotNull(
                coordinator.handle(
                    recovering,
                    SessionEvent.AttemptTimedOut(
                        LOCAL_RUNTIME,
                        recovering.attempt.id,
                        recovering.attempt.deadlineElapsedRealtimeMs
                    )
                )
            )
            assertTrue(decision.accepted)
            state = requireNotNull(decision.state)
            if (index < 2) {
                val retry = state as IntercomState.Recovering
                assertEquals(index + 1, retry.consecutiveFinalFailures)
                assertEquals(connecting.attempt.targetLock, retry.attempt.targetLock)
            } else {
                assertEquals(
                    1,
                    decision.effects.count { it is SessionEffect.ResetWirelessEnvironment }
                )
            }
        }

        val resetting = state as IntercomState.Resetting
        assertNull(coordinator.currentAttempt)
        assertNull(coordinator.activeAttempt)
        assertFalse(
            requireNotNull(
                coordinator.handle(
                    resetting,
                    SessionEvent.TargetedTransportOpenFailed(
                        LOCAL_RUNTIME,
                        attempts[1].id,
                        Transport.LAN,
                        "stale"
                    )
                )
            ).accepted
        )
        assertNull(
            reduceIntercomState(
                resetting,
                SessionEvent.ResetCompleted(LOCAL_RUNTIME, attempts[1].id)
            )
        )
        assertEquals(
            IntercomState.Discovering(LOCAL_RUNTIME),
            requireNotNull(
                reduceIntercomState(
                    resetting,
                    SessionEvent.ResetCompleted(LOCAL_RUNTIME, resetting.failedAttemptId)
                )
            ).state
        )
        record(
            "reset",
            "target=${resetting.targetDeviceId} failures=${resetting.consecutiveFinalFailures} " +
                "failedAttempt=${resetting.failedAttemptId.value}"
        )
    }

    @Test
    fun userCancellationWinsTheQueuedRecoveryTimeout() {
        val clock = MutableClock()
        val coordinator = coordinator(clock, OUTBOUND_ATTEMPT, RECOVERY_ATTEMPT_1)
        val connecting = connect(coordinator)
        val recovering = requireNotNull(
            coordinator.handle(
                IntercomState.Connected(
                    connecting.attempt,
                    peerB(),
                    connectedAt = 1L,
                    transport = Transport.LAN
                ),
                SessionEvent.SignalingDisconnected(LOCAL_RUNTIME, connecting.attempt.id)
            )
        ).state as IntercomState.Recovering
        val canceled = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.DisconnectRequested(LOCAL_RUNTIME, recovering.attempt.id)
            )
        )
        assertTrue(canceled.accepted)
        assertTrue(canceled.state is IntercomState.Discovering)
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            coordinator.terminalOutcome(recovering.attempt.id)
        )

        clock.elapsedMs = recovering.attempt.deadlineElapsedRealtimeMs
        val staleTimeout = requireNotNull(
            coordinator.handle(
                requireNotNull(canceled.state),
                SessionEvent.AttemptTimedOut(
                    LOCAL_RUNTIME,
                    recovering.attempt.id,
                    recovering.attempt.deadlineElapsedRealtimeMs
                )
            )
        )
        assertFalse(staleTimeout.accepted)
        assertNull(coordinator.currentAttempt)
        assertNull(coordinator.activeAttempt)
        record("cancel", "attempt=${recovering.attempt.id.value} timeout=stale")
    }

    private fun coordinator(
        clock: MonotonicClock,
        vararg attemptIds: String
    ): SignalingControlCoordinator {
        val ids = attemptIds.map(::ConnectionAttemptId).toCollection(ArrayDeque())
        return SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::removeFirst
        )
    }

    private fun connect(coordinator: SignalingControlCoordinator): IntercomState.Connecting =
        requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(LOCAL_RUNTIME),
                SessionEvent.ConnectPresenceRequested(
                    LOCAL_RUNTIME,
                    REMOTE_DEVICE_B,
                    REMOTE_RUNTIME_B,
                    setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        ).state as IntercomState.Connecting

    private fun requesterChannel(
        attempt: ConnectionAttempt,
        transport: Transport
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(CHANNEL_B),
        transport = transport,
        requestRole = RequestRole.REQUESTER,
        wireRequestKey = WireRequestKey(
            DeviceId.parse(LOCAL_DEVICE_A),
            LOCAL_RUNTIME,
            attempt.id,
            DeviceId.parse(REMOTE_DEVICE_B)
        ),
        targetLock = attempt.targetLock,
        peer = peerB(),
        originatingAttempt = attempt
    )

    private fun peerB() = PeerIdentity(
        deviceId = REMOTE_DEVICE_B,
        nickname = "Rider B",
        runtimeSessionId = REMOTE_RUNTIME_B,
        isDeviceIdVerified = true
    )

    private fun record(scenario: String, details: String) {
        val evidence = "KUM36_FINAL scenario=$scenario $details"
        println(evidence)
        android.util.Log.i("MotoComKum36", evidence)
    }

    private class MutableClock(var elapsedMs: Long = 0L) : MonotonicClock {
        override fun now() = MonotonicTimestamp(elapsedMs)
    }

    private companion object {
        val LOCAL_RUNTIME = RuntimeSessionId("51000000-0000-4000-8000-000000000036")
        val REMOTE_RUNTIME_B = RuntimeSessionId("52000000-0000-4000-8000-000000000036")
        val REMOTE_RUNTIME_C = RuntimeSessionId("53000000-0000-4000-8000-000000000036")
        const val LOCAL_DEVICE_A = "a2000000-0000-4000-8000-000000000036"
        const val REMOTE_DEVICE_B = "b2000000-0000-4000-8000-000000000036"
        const val REMOTE_DEVICE_C = "c2000000-0000-4000-8000-000000000036"
        const val OUTBOUND_ATTEMPT = "d6000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_1 = "d7000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_2 = "d8000000-0000-4000-8000-000000000036"
        const val RECOVERY_ATTEMPT_3 = "d9000000-0000-4000-8000-000000000036"
        const val CHANNEL_B = "e3000000-0000-4000-8000-000000000036"
    }
}
