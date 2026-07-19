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
    fun fasterThirdPresenceCannotReplaceTheRecoveryTarget() {
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
        val recovering = requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.WebRtcStateChanged(
                    runtimeSessionId = runtime,
                    attemptId = connecting.attempt.id,
                    state = WebRtcConnectionState.DISCONNECTED,
                    occurredAt = 6L
                )
            )
        ).state as IntercomState.Recovering

        val cDecision = requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = runtime,
                    targetDeviceId = "peer-c",
                    targetSessionId = RuntimeSessionId("runtime-peer-c"),
                    availableTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT)
                )
            )
        )

        assertFalse(cDecision.accepted)
        assertEquals("peer-a", recovering.targetDeviceId)
        assertEquals(recovering.attempt, coordinator.currentAttempt)
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
    fun overlapUnavailableRetiresLanThenRetriesP2pForTheSameAttempt() {
        val fixture = sequentialFixture()
        val attempt = fixture.attempt
        val switched = fixture.overlap()

        assertTrue(switched.accepted)
        assertEquals(
            listOf(
                SessionEffect.RetireTargetedTransport(attempt, Transport.LAN),
                SessionEffect.OpenTargetedTransport(attempt, Transport.WIFI_DIRECT)
            ),
            switched.effects
        )
        switched.effects.forEach { effect ->
            val effectAttempt = when (effect) {
                is SessionEffect.RetireTargetedTransport -> effect.attempt
                is SessionEffect.OpenTargetedTransport -> effect.attempt
                else -> error("Unexpected sequential fallback effect: $effect")
            }
            assertEquals(attempt.id, effectAttempt.id)
            assertEquals(attempt.targetLock, effectAttempt.targetLock)
            assertEquals(attempt.channelPlan, effectAttempt.channelPlan)
            assertEquals(attempt.deadlineAt, effectAttempt.deadlineAt)
        }

        val duplicate = fixture.overlap()
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun overlapUnavailableRequiresOpenedFallbackAndNoPreferredCandidate() {
        val fixture = sequentialFixture(openFallback = false)
        val attempt = fixture.attempt

        assertFalse(fixture.overlap().accepted)

        openFallback(fixture.coordinator, fixture.clock, fixture.connecting)
        val preferred = sequentialRequesterChannel(attempt, Transport.LAN)
        assertTrue(
            fixture.handle(
                SessionEvent.ControlChannelVerified(SEQUENTIAL_RUNTIME, preferred)
            ).accepted
        )
        assertFalse(fixture.overlap().accepted)
    }

    @Test
    fun overlapUnavailableRejectsWrongReplacedCanceledAndExpiredAttempts() {
        sequentialFixture().run {
            val wrong = attempt.copy(id = ConnectionAttemptId(ATTEMPT_REPLACEMENT))
            assertFalse(overlap(wrong).accepted)
        }
        sequentialFixture().run {
            val replacement = attempt.copy(id = ConnectionAttemptId(ATTEMPT_REPLACEMENT))
            val replaced = handle(SessionEvent.AttemptReplaced(replacement))
            assertFalse(
                overlap(state = requireNotNull(replaced.state)).accepted
            )
        }
        sequentialFixture().run {
            val canceled = handle(
                SessionEvent.DisconnectRequested(SEQUENTIAL_RUNTIME, attempt.id)
            )
            assertFalse(
                overlap(state = requireNotNull(canceled.state)).accepted
            )
        }
        sequentialFixture().run {
            clock.advanceBy(5_000L)
            assertFalse(overlap().accepted)
        }
    }

    @Test
    fun retiredLanCannotJoinAndP2pFailureTerminatesTheAttempt() {
        val fixture = sequentialFixture()
        val attempt = fixture.attempt
        assertTrue(fixture.overlap().accepted)

        val lateLan = sequentialRequesterChannel(attempt, Transport.LAN)
        assertFalse(
            fixture.handle(
                SessionEvent.ControlChannelVerified(SEQUENTIAL_RUNTIME, lateLan)
            ).accepted
        )
        assertNull(fixture.coordinator.activeAttempt)

        val failed = fixture.handle(
            SessionEvent.TargetedTransportOpenFailed(
                SEQUENTIAL_RUNTIME,
                attempt.id,
                Transport.WIFI_DIRECT,
                "fallback failed after LAN retirement"
            )
        )
        assertTrue(failed.accepted)
        assertTrue(failed.state is IntercomState.Discovering)
        assertEquals(
            ConnectionAttemptTerminalOutcome.FAILED,
            fixture.coordinator.terminalOutcome(attempt.id)
        )
        assertNull(fixture.coordinator.currentAttempt)
        assertNull(fixture.coordinator.activeAttempt)
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

    private fun sequentialFixture(openFallback: Boolean = true): SequentialFixture {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = SignalingControlCoordinator(
            clock = clock,
            attemptTimeoutMs = 10_000L,
            attemptIdFactory = { ConnectionAttemptId(SEQUENTIAL_ATTEMPT) }
        )
        val connecting = requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(SEQUENTIAL_RUNTIME),
                sequentialOutboundIntent()
            )
        ).state as IntercomState.Connecting
        if (openFallback) openFallback(coordinator, clock, connecting)
        return SequentialFixture(clock, coordinator, connecting)
    }

    private data class SequentialFixture(
        val clock: FakeMonotonicClock,
        val coordinator: SignalingControlCoordinator,
        val connecting: IntercomState.Connecting
    ) {
        val attempt: ConnectionAttempt
            get() = connecting.attempt

        fun handle(
            event: SessionEvent,
            state: IntercomState = connecting
        ): SignalingControlDecision = requireNotNull(coordinator.handle(state, event))

        fun overlap(
            eventAttempt: ConnectionAttempt = attempt,
            state: IntercomState = connecting
        ): SignalingControlDecision = handle(
            SessionEvent.TargetedTransportOverlapUnavailable(
                eventAttempt,
                Transport.WIFI_DIRECT
            ),
            state
        )
    }

    private fun openFallback(
        coordinator: SignalingControlCoordinator,
        clock: FakeMonotonicClock,
        connecting: IntercomState.Connecting
    ) {
        clock.advanceBy(5_000L)
        val milestone = AttemptMilestone.FallbackTransport(
            connecting.attempt,
            Transport.WIFI_DIRECT,
            MonotonicTimestamp(5_500L)
        )
        val decision = requireNotNull(
            coordinator.handle(
                connecting,
                SessionEvent.AttemptMilestoneElapsed(milestone)
            )
        )
        assertTrue(decision.accepted)
        assertEquals(
            listOf(
                SessionEffect.OpenTargetedTransport(
                    connecting.attempt,
                    Transport.WIFI_DIRECT
                )
            ),
            decision.effects
        )
    }

    private fun sequentialOutboundIntent() =
        SessionEvent.ConnectPresenceRequested(
            runtimeSessionId = SEQUENTIAL_RUNTIME,
            targetDeviceId = REMOTE_DEVICE,
            targetSessionId = SEQUENTIAL_REMOTE_RUNTIME,
            availableTransports = setOf(Transport.LAN, Transport.WIFI_DIRECT)
        )

    private fun sequentialRequesterChannel(
        attempt: ConnectionAttempt,
        transport: Transport
    ) = VerifiedControlChannel(
        channelId = ControlChannelId.parse(LAN_CHANNEL),
        transport = transport,
        requestRole = RequestRole.REQUESTER,
        wireRequestKey = WireRequestKey(
            requesterDeviceId = DeviceId.parse(LOCAL_DEVICE),
            requesterSessionId = SEQUENTIAL_RUNTIME,
            attemptId = attempt.id,
            responderDeviceId = DeviceId.parse(REMOTE_DEVICE)
        ),
        targetLock = attempt.targetLock,
        peer = PeerIdentity(
            deviceId = REMOTE_DEVICE,
            nickname = "Rider",
            runtimeSessionId = SEQUENTIAL_REMOTE_RUNTIME,
            isDeviceIdVerified = true
        ),
        originatingAttempt = attempt
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

    private companion object {
        val SEQUENTIAL_RUNTIME =
            RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val SEQUENTIAL_REMOTE_RUNTIME =
            RuntimeSessionId("10000000-0000-4000-8000-000000000002")
        const val SEQUENTIAL_ATTEMPT = "20000000-0000-4000-8000-000000000001"
        const val ATTEMPT_REPLACEMENT = "20000000-0000-4000-8000-000000000002"
        const val LOCAL_DEVICE = "30000000-0000-4000-8000-000000000001"
        const val REMOTE_DEVICE = "30000000-0000-4000-8000-000000000002"
        const val LAN_CHANNEL = "40000000-0000-4000-8000-000000000001"
    }
}
