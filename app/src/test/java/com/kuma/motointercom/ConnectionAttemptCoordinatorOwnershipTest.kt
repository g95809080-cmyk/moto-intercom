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
        assertEquals(
            ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT),
            connecting.attempt.channelPlan
        )
        assertEquals(10_500L, connecting.attempt.deadlineElapsedRealtimeMs)
        assertEquals(connecting.attempt, coordinator.currentAttempt)
        assertEquals(listOf(ConnectionAttemptId("attempt-outbound")), ids.created)
        assertEquals(
            listOf(
                SessionEffect.ScheduleAttemptDeadline(connecting.attempt),
                SessionEffect.OpenTargetedTransport(connecting.attempt, Transport.LAN),
                SessionEffect.ScheduleAttemptMilestone(
                    AttemptMilestone.FallbackTransport(
                        connecting.attempt,
                        Transport.WIFI_DIRECT,
                        MonotonicTimestamp(5_500L)
                    )
                )
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
        val connected = IntercomState.Connected(
            first.attempt,
            verifiedPeer(),
            connectedAt = 5L,
            transport = Transport.LAN
        )
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
            listOf(
                SessionEffect.ScheduleAttemptDeadline(recovering.attempt),
                SessionEffect.OpenTargetedTransport(recovering.attempt, Transport.LAN),
                SessionEffect.ScheduleAttemptMilestone(
                    AttemptMilestone.FallbackTransport(
                        recovering.attempt,
                        Transport.WIFI_DIRECT,
                        MonotonicTimestamp(7_500L)
                    )
                )
            ),
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
            connectedAt = 5L,
            transport = Transport.LAN
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
                SessionEffect.ScheduleAttemptDeadline(recovery),
                SessionEffect.ScheduleAttemptMilestone(
                    AttemptMilestone.FallbackTransport(
                        recovery,
                        Transport.WIFI_DIRECT,
                        MonotonicTimestamp(5_500L)
                    )
                )
            ),
            decision.effects
        )
    }

    @Test
    fun fallbackOpensExactlyAtT5AndDuplicateMilestoneIsIgnored() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-race"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val milestone = AttemptMilestone.FallbackTransport(
            connecting.attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(5_500L)
        )

        clock.advanceBy(4_999L)
        val early = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))
        )
        assertFalse(early.accepted)

        clock.advanceBy(1L)
        val due = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))
        )
        assertTrue(due.accepted)
        assertEquals(
            listOf(SessionEffect.OpenTargetedTransport(connecting.attempt, Transport.WIFI_DIRECT)),
            due.effects
        )

        val duplicate = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))
        )
        assertFalse(duplicate.accepted)

        clock.advanceBy(1L)
        val lateDuplicate = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))
        )
        assertFalse(lateDuplicate.accepted)
    }

    @Test
    fun replacementAttemptRejectsTheOldFallbackMilestone() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-old"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val oldMilestone = AttemptMilestone.FallbackTransport(
            connecting.attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(5_500L)
        )
        val replacement = connecting.attempt.copy(
            id = ConnectionAttemptId("attempt-replacement"),
            deadlineElapsedRealtimeMs = 11_000L
        )
        val replaced = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptReplaced(replacement))
        )

        assertTrue(replaced.accepted)
        assertEquals(replacement, coordinator.currentAttempt)
        clock.advanceBy(5_000L)
        val stale = requireNotNull(
            coordinator.handle(
                requireNotNull(replaced.state),
                SessionEvent.AttemptMilestoneElapsed(oldMilestone)
            )
        )
        assertFalse(stale.accepted)
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
    }

    @Test
    fun preferredFailureWaitsForFixedFallbackMilestone() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-race"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting

        val failed = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    connecting.attempt.id,
                    Transport.LAN,
                    "preferred failed"
                )
            )
        )

        assertTrue(failed.accepted)
        assertEquals(connecting, failed.state)
        assertTrue(failed.effects.isEmpty())
        assertEquals(connecting.attempt, coordinator.currentAttempt)

        clock.advanceBy(5_000L)
        val milestone = AttemptMilestone.FallbackTransport(
            connecting.attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(5_500L)
        )
        val due = requireNotNull(
            coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))
        )
        assertTrue(due.accepted)
    }

    @Test
    fun attemptFailsOnlyAfterEveryOpenedPlannedTransportFails() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-race"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        coordinator.handle(
            connecting,
            SessionEvent.TargetedTransportOpenFailed(
                runtime,
                connecting.attempt.id,
                Transport.LAN,
                "preferred failed"
            )
        )
        clock.advanceBy(5_000L)
        val milestone = AttemptMilestone.FallbackTransport(
            connecting.attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(5_500L)
        )
        coordinator.handle(connecting, SessionEvent.AttemptMilestoneElapsed(milestone))

        val failed = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    connecting.attempt.id,
                    Transport.WIFI_DIRECT,
                    "fallback failed"
                )
            )
        )

        assertTrue(failed.accepted)
        assertTrue(failed.state is IntercomState.Discovering)
        assertEquals(
            ConnectionAttemptTerminalOutcome.FAILED,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
        assertNull(coordinator.currentAttempt)
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
            coordinator.handle(IntercomState.Discovering(runtime), singleTransportOutboundIntent())
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

    @Test
    fun successAtDeadlineCannotBeatQueuedTimeout() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(RecordingAttemptIdFactory("attempt-success"), clock)
        val connecting = requireNotNull(
            coordinator.handle(IntercomState.Discovering(runtime), outboundIntent())
        ).state as IntercomState.Connecting
        val withPeer = connecting.copy(peer = verifiedPeer())
        clock.advanceBy(10_000L)

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
                withPeer,
                SessionEvent.AttemptTimedOut(runtime, connecting.attempt.id, 10_500L)
            )
        )

        assertFalse(connected.accepted)
        assertTrue(timeout.accepted)
        assertEquals(
            ConnectionAttemptTerminalOutcome.TIMED_OUT,
            coordinator.terminalOutcome(connecting.attempt.id)
        )
        assertNull(coordinator.currentAttempt)
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

    private fun singleTransportOutboundIntent() =
        SessionEvent.ConnectPresenceRequested(
            runtimeSessionId = runtime,
            targetDeviceId = "peer-a",
            targetSessionId = remoteRuntime,
            availableTransports = setOf(Transport.LAN)
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
