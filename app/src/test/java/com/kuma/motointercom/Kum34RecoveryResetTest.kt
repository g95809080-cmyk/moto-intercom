package com.kuma.motointercom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kum34RecoveryResetTest {
    private val runtime = RuntimeSessionId("runtime-current")
    private val remoteRuntime = RuntimeSessionId("runtime-remote")

    @Test
    fun firstAndSecondFinalFailuresCreateFreshSameTargetRetries() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val ids = AttemptIds("outbound", "recovery-1", "recovery-2", "recovery-3")
        val coordinator = coordinator(clock, ids)
        val recovery1 = beginRecovery(coordinator)

        val first = exhaust(coordinator, clock, recovery1)
        val recovery2 = first.state as IntercomState.Recovering

        assertEquals(1, recovery2.consecutiveFinalFailures)
        assertNotEquals(recovery1.attempt.id, recovery2.attempt.id)
        assertEquals(recovery1.attempt.targetLock, recovery2.attempt.targetLock)
        assertEquals(recovery1.attempt.channelPlan, recovery2.attempt.channelPlan)
        assertEquals(20_500L, recovery2.attempt.deadlineElapsedRealtimeMs)
        assertEquals(
            listOf(
                SessionEffect.RestartDiscovery(
                    runtime,
                    recovery2.attempt,
                    restartDelayMillis = 1_500L
                ),
                SessionEffect.ScheduleAttemptDeadline(recovery2.attempt)
            ),
            first.effects
        )

        val second = exhaust(coordinator, clock, recovery2)
        val recovery3 = second.state as IntercomState.Recovering

        assertEquals(2, recovery3.consecutiveFinalFailures)
        assertNotEquals(recovery2.attempt.id, recovery3.attempt.id)
        assertEquals(recovery2.attempt.targetLock, recovery3.attempt.targetLock)
        assertEquals(recovery2.attempt.channelPlan, recovery3.attempt.channelPlan)
        assertEquals(30_500L, recovery3.attempt.deadlineElapsedRealtimeMs)
        assertEquals(recovery3.attempt, coordinator.currentAttempt)
    }

    @Test
    fun thirdFinalFailureEntersExactResettingStateAndEmitsOneResetEffect() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val ids = AttemptIds("outbound", "recovery-1", "recovery-2", "recovery-3")
        val coordinator = coordinator(clock, ids)
        val recovery1 = beginRecovery(coordinator)
        val recovery2 = exhaust(coordinator, clock, recovery1).state as IntercomState.Recovering
        val recovery3 = exhaust(coordinator, clock, recovery2).state as IntercomState.Recovering

        val third = exhaust(coordinator, clock, recovery3)
        val resetting = third.state as IntercomState.Resetting

        assertEquals(runtime, resetting.runtimeSessionId)
        assertEquals("peer-a", resetting.targetDeviceId)
        assertEquals(recovery3.attempt.id, resetting.failedAttemptId)
        assertEquals(3, resetting.consecutiveFinalFailures)
        assertEquals(
            listOf(
                SessionEffect.ResetWirelessEnvironment(
                    runtimeSessionId = runtime,
                    targetDeviceId = "peer-a",
                    failedAttemptId = recovery3.attempt.id,
                    consecutiveFinalFailures = 3
                )
            ),
            third.effects
        )
        assertNull(coordinator.currentAttempt)
    }

    @Test
    fun transportLocalAndStaleFailuresDoNotDoubleCount() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val ids = AttemptIds("outbound", "recovery-1", "recovery-2")
        val coordinator = coordinator(clock, ids, dualTransport = true)
        val recovery1 = beginRecovery(coordinator, dualTransport = true)

        val localFailure = requireNotNull(
            coordinator.handle(
                recovery1,
                SessionEvent.TargetedTransportOpenFailed(
                    runtime,
                    recovery1.attempt.id,
                    recovery1.attempt.preferredTransport,
                    "preferred failed"
                )
            )
        )

        assertTrue(localFailure.accepted)
        assertEquals(0, (localFailure.state as IntercomState.Recovering).consecutiveFinalFailures)

        val first = exhaust(coordinator, clock, recovery1)
        val recovery2 = first.state as IntercomState.Recovering
        val duplicate = requireNotNull(
            coordinator.handle(
                recovery2,
                SessionEvent.AttemptTimedOut(
                    runtime,
                    recovery1.attempt.id,
                    recovery1.attempt.deadlineElapsedRealtimeMs
                )
            )
        )

        assertFalse(duplicate.accepted)
        assertEquals(1, recovery2.consecutiveFinalFailures)
        assertEquals(recovery2.attempt, coordinator.currentAttempt)
    }

    @Test
    fun canceledRecoveryDoesNotEnterResetting() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val coordinator = coordinator(clock, AttemptIds("outbound", "recovery-1"))
        val recovery = beginRecovery(coordinator)

        val canceled = requireNotNull(
            coordinator.handle(
                recovery,
                SessionEvent.DisconnectRequested(runtime, recovery.attempt.id)
            )
        )

        assertTrue(canceled.accepted)
        assertTrue(canceled.state is IntercomState.Discovering)
        assertTrue(canceled.effects.none { it is SessionEffect.ResetWirelessEnvironment })
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            coordinator.terminalOutcome(recovery.attempt.id)
        )
    }

    @Test
    fun successfulRetryClearsTheFailureEpisode() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(500L))
        val ids = AttemptIds(
            "outbound",
            "recovery-1",
            "recovery-2",
            "next-episode-recovery"
        )
        val coordinator = coordinator(clock, ids)
        val recovery1 = beginRecovery(coordinator)
        val recovery2 = exhaust(coordinator, clock, recovery1).state as IntercomState.Recovering

        val connected = requireNotNull(
            coordinator.handle(
                recovery2,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    recovery2.attempt.id,
                    WebRtcConnectionState.CONNECTED,
                    occurredAt = 3L
                )
            )
        ).state as IntercomState.Connected
        val nextEpisode = requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.SignalingDisconnected(runtime, connected.attempt.id)
            )
        ).state as IntercomState.Recovering

        assertEquals(0, nextEpisode.consecutiveFinalFailures)
        assertEquals(recovery2.attempt.targetLock, nextEpisode.attempt.targetLock)
    }

    @Test
    fun sessionOrchestratorIsTheOnlyWriterForResetAndExactCompletion() = runBlocking {
        var now = 500L
        val ids = ArrayDeque(
            listOf(
                "10000000-0000-4000-8000-000000000001",
                "10000000-0000-4000-8000-000000000002",
                "10000000-0000-4000-8000-000000000003",
                "10000000-0000-4000-8000-000000000004"
            ).map(::ConnectionAttemptId)
        )
        val orchestrator = SessionOrchestrator(
            pairingRepository = NoOpPairingRepository(),
            dispatcher = Dispatchers.Unconfined,
            elapsedRealtime = { now },
            attemptIdFactory = ids::removeFirst
        )
        try {
            assertTrue(orchestrator.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.ConnectPresenceRequested(
                        runtime,
                        "peer-a",
                        remoteRuntime,
                        setOf(Transport.LAN)
                    )
                )
            )
            val outbound = requireNotNull(orchestrator.currentAttempt)
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.RemoteIdentityReceived(runtime, outbound.id, verifiedPeer())
                )
            )
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        outbound.id,
                        WebRtcConnectionState.CONNECTED,
                        occurredAt = 1L
                    )
                )
            )
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.SignalingDisconnected(runtime, outbound.id)
                )
            )

            repeat(3) {
                val recovering = orchestrator.state.value as IntercomState.Recovering
                now = recovering.attempt.deadlineElapsedRealtimeMs
                assertTrue(
                    orchestrator.dispatchAndAwait(
                        SessionEvent.AttemptTimedOut(
                            runtime,
                            recovering.attempt.id,
                            recovering.attempt.deadlineElapsedRealtimeMs
                        )
                    )
                )
            }

            val resetting = orchestrator.state.value as IntercomState.Resetting
            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.WebRtcStateChanged(
                        runtime,
                        ConnectionAttemptId("stale-attempt"),
                        WebRtcConnectionState.CONNECTED,
                        occurredAt = 2L
                    )
                )
            )
            assertFalse(
                orchestrator.dispatchAndAwait(
                    SessionEvent.ResetCompleted(
                        runtime,
                        ConnectionAttemptId("stale-reset")
                    )
                )
            )
            assertEquals(resetting, orchestrator.state.value)
            assertTrue(
                orchestrator.dispatchAndAwait(
                    SessionEvent.ResetCompleted(runtime, resetting.failedAttemptId)
                )
            )
            assertEquals(IntercomState.Discovering(runtime), orchestrator.state.value)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun resetCompletionRequiresExactFailedAttemptIdentity() {
        val failedAttemptId = ConnectionAttemptId("recovery-3")
        val resetting = IntercomState.Resetting(
            runtimeSessionId = runtime,
            targetDeviceId = "peer-a",
            failedAttemptId = failedAttemptId,
            consecutiveFinalFailures = 3
        )

        assertNull(
            reduceIntercomState(
                resetting,
                SessionEvent.ResetCompleted(runtime, ConnectionAttemptId("stale-reset"))
            )
        )
        assertNull(
            reduceIntercomState(
                resetting,
                SessionEvent.ResetCompleted(RuntimeSessionId("old-runtime"), failedAttemptId)
            )
        )
        assertEquals(
            IntercomState.Discovering(runtime),
            requireNotNull(
                reduceIntercomState(
                    resetting,
                    SessionEvent.ResetCompleted(runtime, failedAttemptId)
                )
            ).state
        )
    }

    @Test
    fun fullStopSupersedesLateResetCompletion() {
        val failedAttemptId = ConnectionAttemptId("recovery-3")
        val resetting = IntercomState.Resetting(
            runtime,
            "peer-a",
            failedAttemptId,
            consecutiveFinalFailures = 3
        )
        val stopping = requireNotNull(
            reduceIntercomState(resetting, SessionEvent.StopRequested(runtime))
        ).state

        assertTrue(stopping is IntercomState.Stopping)
        assertNull(
            reduceIntercomState(
                stopping,
                SessionEvent.ResetCompleted(runtime, failedAttemptId)
            )
        )
    }

    @Test
    fun serviceResetEffectGateRequiresExactCurrentResetState() {
        val failedAttemptId = ConnectionAttemptId("recovery-3")
        val effect = SessionEffect.ResetWirelessEnvironment(
            runtime,
            "peer-a",
            failedAttemptId,
            consecutiveFinalFailures = 3
        )
        val resetting = IntercomState.Resetting(
            runtime,
            "peer-a",
            failedAttemptId,
            consecutiveFinalFailures = 3
        )

        assertTrue(
            canExecuteResetWirelessEnvironmentEffect(
                effect,
                resetting,
                currentAttempt = null,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteResetWirelessEnvironmentEffect(
                effect,
                resetting.copy(failedAttemptId = ConnectionAttemptId("other")),
                currentAttempt = null,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteResetWirelessEnvironmentEffect(
                effect,
                IntercomState.Discovering(runtime),
                currentAttempt = null,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteResetWirelessEnvironmentEffect(
                effect,
                resetting,
                currentAttempt = ConnectionAttemptFixture.create(
                    FakeMonotonicClock(MonotonicTimestamp(1L))
                ),
                activeAttempt = null,
                pendingInbound = null
            )
        )
    }

    @Test
    fun recoveryIdentityUpdatesPreserveTheFailureStreak() {
        val attempt = ConnectionAttemptFixture.create(
            FakeMonotonicClock(MonotonicTimestamp(1L)),
            runtimeSessionId = runtime,
            targetDeviceId = "peer-a",
            expectedRemoteSessionId = remoteRuntime,
            trigger = ConnectionTrigger.RECOVERY
        )
        val recovering = IntercomState.Recovering(
            attempt = attempt,
            peer = verifiedPeer(),
            consecutiveFinalFailures = 2
        )
        val tunnelUpdated = requireNotNull(
            reduceIntercomState(
                recovering,
                SessionEvent.TunnelReady(
                    attempt,
                    verifiedPeer().copy(nickname = "Rider A updated"),
                    Transport.LAN
                )
            )
        ).state as IntercomState.Recovering
        val identityUpdated = requireNotNull(
            reduceIntercomState(
                tunnelUpdated,
                SessionEvent.RemoteIdentityReceived(
                    runtime,
                    attempt.id,
                    verifiedPeer().copy(deviceName = "Updated phone")
                )
            )
        ).state as IntercomState.Recovering

        assertEquals(2, tunnelUpdated.consecutiveFinalFailures)
        assertEquals(2, identityUpdated.consecutiveFinalFailures)
    }

    private fun coordinator(
        clock: FakeMonotonicClock,
        ids: AttemptIds,
        dualTransport: Boolean = false
    ) = SignalingControlCoordinator(
        clock = clock,
        attemptTimeoutMs = 10_000L,
        attemptIdFactory = ids::next
    )

    private fun beginRecovery(
        coordinator: SignalingControlCoordinator,
        dualTransport: Boolean = false
    ): IntercomState.Recovering {
        val available = if (dualTransport) {
            setOf(Transport.LAN, Transport.WIFI_DIRECT)
        } else {
            setOf(Transport.LAN)
        }
        val connecting = requireNotNull(
            coordinator.handle(
                IntercomState.Discovering(runtime),
                SessionEvent.ConnectPresenceRequested(
                    runtimeSessionId = runtime,
                    targetDeviceId = "peer-a",
                    targetSessionId = remoteRuntime,
                    availableTransports = available
                )
            )
        ).state as IntercomState.Connecting
        val connected = IntercomState.Connected(
            attempt = connecting.attempt,
            peer = verifiedPeer(),
            connectedAt = 1L,
            transport = Transport.LAN
        )
        return requireNotNull(
            coordinator.handle(
                connected,
                SessionEvent.WebRtcStateChanged(
                    runtime,
                    connecting.attempt.id,
                    WebRtcConnectionState.DISCONNECTED,
                    occurredAt = 2L
                )
            )
        ).state as IntercomState.Recovering
    }

    private fun exhaust(
        coordinator: SignalingControlCoordinator,
        clock: FakeMonotonicClock,
        recovering: IntercomState.Recovering
    ): SignalingControlDecision {
        clock.advanceBy(
            recovering.attempt.deadlineElapsedRealtimeMs - clock.now().elapsedRealtimeMs
        )
        return requireNotNull(
            coordinator.handle(
                recovering,
                SessionEvent.AttemptTimedOut(
                    runtime,
                    recovering.attempt.id,
                    recovering.attempt.deadlineElapsedRealtimeMs
                )
            )
        )
    }

    private fun verifiedPeer() = PeerIdentity(
        deviceId = "peer-a",
        nickname = "Rider A",
        runtimeSessionId = remoteRuntime,
        isDeviceIdVerified = true
    )

    private class AttemptIds(vararg values: String) {
        private val values = ArrayDeque(values.map(::ConnectionAttemptId))

        fun next(): ConnectionAttemptId = values.removeFirst()
    }

    private class NoOpPairingRepository : PairingRepository {
        override fun observeAll(): Flow<List<PairingRecord>> = flowOf(emptyList())
        override suspend fun getAll(): List<PairingRecord> = emptyList()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? = null
        override suspend fun saveConnectedPeer(record: PairingRecord) = Unit
        override suspend fun setPreferred(deviceId: String): Boolean = false
        override suspend fun clearPreferred() = Unit
        override suspend fun updateLastConnectedAt(
            deviceId: String,
            connectedAt: Long,
            transport: String?
        ): Boolean = false
        override suspend fun incrementFailureCount(deviceId: String): Boolean = false
        override suspend fun clearFailureCount(deviceId: String): Boolean = false
        override suspend fun forget(deviceId: String): Boolean = false
    }
}
