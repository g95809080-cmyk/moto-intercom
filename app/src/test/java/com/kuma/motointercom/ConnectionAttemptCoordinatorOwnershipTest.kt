package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptCoordinatorOwnershipTest {
    private val runtime = RuntimeSessionId("runtime-current")
    private val remoteRuntime = RuntimeSessionId("runtime-remote")

    @Test
    fun coordinatorCreatesCompleteOutboundAttemptFromIntent() {
        val ids = RecordingAttemptIdFactory("attempt-outbound")
        val coordinator = coordinator(ids)

        val decision = coordinator.handle(
            IntercomState.Discovering(runtime),
            outboundIntent()
        )

        assertTrue(requireNotNull(decision).accepted)
        val connecting = decision.state as IntercomState.Connecting
        assertEquals(ConnectionAttemptId("attempt-outbound"), connecting.attempt.id)
        assertEquals(runtime, connecting.attempt.runtimeSessionId)
        assertEquals(TargetLock("peer-a", remoteRuntime), connecting.attempt.targetLock)
        assertEquals(ConnectionTrigger.USER, connecting.attempt.trigger)
        assertEquals(ChannelPlan.single(Transport.LAN), connecting.attempt.channelPlan)
        assertEquals(10_500L, connecting.attempt.deadlineElapsedRealtimeMs)
        assertEquals(connecting.attempt, coordinator.currentAttempt)
        assertEquals(listOf(ConnectionAttemptId("attempt-outbound")), ids.created)
        assertEquals(
            listOf(
                SessionEffect.ScheduleAttemptDeadline(connecting.attempt),
                SessionEffect.OpenTargetedTransport(connecting.attempt)
            ),
            decision.effects
        )
    }

    @Test
    fun duplicateOutboundIntentDoesNotCreateAnotherAttempt() {
        val ids = RecordingAttemptIdFactory("attempt-outbound", "attempt-unexpected")
        val coordinator = coordinator(ids)
        val first = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        )

        val duplicate = requireNotNull(
            coordinator.handle(requireNotNull(first.state), outboundIntent())
        )

        assertFalse(duplicate.accepted)
        assertEquals(listOf(ConnectionAttemptId("attempt-outbound")), ids.created)
        assertEquals(
            (first.state as IntercomState.Connecting).attempt,
            coordinator.currentAttempt
        )
    }

    @Test
    fun coordinatorCreatesRecoveryWithFreshIdentityAndPreservedTargetPlan() {
        val ids = RecordingAttemptIdFactory("attempt-outbound", "attempt-recovery")
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(ids, clock)
        val first = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val connected = IntercomState.Connected(first.attempt, verifiedPeer(), connectedAt = 5L)
        clock.advanceBy(2_000L)

        val decision = requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.WebRtcStateChanged(
                    runtimeSessionId = runtime,
                    attemptId = first.attempt.id,
                    state = WebRtcConnectionState.DISCONNECTED,
                    occurredAt = 6L
                )
            )
        )

        assertTrue(decision.accepted)
        val recovering = decision.state as IntercomState.Recovering
        assertEquals(ConnectionAttemptId("attempt-recovery"), recovering.attempt.id)
        assertNotEquals(first.attempt.id, recovering.attempt.id)
        assertEquals(first.attempt.targetLock, recovering.attempt.targetLock)
        assertEquals(first.attempt.channelPlan, recovering.attempt.channelPlan)
        assertEquals(ConnectionTrigger.RECOVERY, recovering.attempt.trigger)
        assertEquals(12_500L, recovering.attempt.deadlineElapsedRealtimeMs)
        assertEquals(recovering.attempt, coordinator.currentAttempt)
        assertEquals(
            listOf(SessionEffect.ScheduleAttemptDeadline(recovering.attempt)),
            decision.effects
        )
        assertEquals(
            ConnectionAttemptTerminalOutcome.DISCONNECTED,
            coordinator.terminalOutcome(first.attempt.id)
        )
    }

    @Test
    fun signalingRecoveryClearsOldResourcesBeforeSchedulingNewDeadline() {
        val coordinator = coordinator(
            RecordingAttemptIdFactory("attempt-outbound", "attempt-recovery")
        )
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val connected = IntercomState.Connected(
            connecting.attempt,
            verifiedPeer(),
            connectedAt = 5L
        )

        val decision = requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.SignalingDisconnected(runtime, connecting.attempt.id)
            )
        )

        val recovery = (decision.state as IntercomState.Recovering).attempt
        assertEquals(
            listOf(
                SessionEffect.RestartDiscovery(runtime, recovery),
                SessionEffect.ScheduleAttemptDeadline(recovery)
            ),
            decision.effects
        )
    }

    @Test
    fun timeoutWinsOverLateTransportFailure() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-timeout"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        clock.advanceBy(10_000L)

        val timeout = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.AttemptTimedOut(runtime, connecting.attempt.id, 10_500L)
            )
        )
        val lateFailure = requireNotNull(
            coordinator.handle(
                requireNotNull(timeout.state),
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    connecting.attempt.id,
                    Transport.LAN,
                    "late"
                )
            )
        )

        assertTrue(timeout.accepted)
        assertFalse(lateFailure.accepted)
        assertEquals(
            ConnectionAttemptTerminalOutcome.TIMED_OUT,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
        assertNull(coordinator.currentAttempt)
    }

    @Test
    fun cancellationWinsOverQueuedTimeout() {
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-cancel"))
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting

        val canceled = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.DisconnectRequested(runtime, connecting.attempt.id)
            )
        )
        val timeout = requireNotNull(
            coordinator.handle(
                requireNotNull(canceled.state),
                SessionEvent.AttemptTimedOut(runtime, connecting.attempt.id, 10_500L)
            )
        )

        assertTrue(canceled.accepted)
        assertFalse(timeout.accepted)
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
    }

    @Test
    fun successWinsOverQueuedTimeout() {
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-success"))
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val withPeer = connecting.copy(peer = verifiedPeer())

        val connected = requireNotNull(
            coordinator.handle(
                withPeer,
                SessionEvent.WebRtcStateChanged(
                    runtimeSessionId = runtime,
                    attemptId = connecting.attempt.id,
                    state = WebRtcConnectionState.CONNECTED,
                    occurredAt = 5L
                )
            )
        )
        val timeout = requireNotNull(
            coordinator.handle(
                requireNotNull(connected.state),
                SessionEvent.AttemptTimedOut(runtime, connecting.attempt.id, 10_500L)
            )
        )

        assertTrue(connected.accepted)
        assertFalse(timeout.accepted)
        assertEquals(
            ConnectionAttemptTerminalOutcome.SUCCESS,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
        assertEquals(connecting.attempt, coordinator.currentAttempt)
    }

    private fun coordinator(
        ids: RecordingAttemptIdFactory,
        clock: MonotonicClock = FakeMonotonicClock(MonotonicTimestamp(500L))
    ) =
        SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = ids::create
        )

    private fun outboundIntent() =
        SessionEvent.ConnectPresenceRequested(
            runtimeSessionId = runtime,
            targetDeviceId = "peer-a",
            targetSessionId = remoteRuntime,
            availableTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT)
        )

    private fun verifiedPeer() = PeerIdentity(
        deviceId = "peer-a",
        nickname = "Rider A",
        runtimeSessionId = remoteRuntime,
        isDeviceIdVerified = true
    )

    private class RecordingAttemptIdFactory(vararg ids: String) {
        private val remaining = ArrayDeque(ids.map(::ConnectionAttemptId))
        val created = mutableListOf<ConnectionAttemptId>()

        fun create(): ConnectionAttemptId = remaining.removeFirst().also(created::add)
    }
}
