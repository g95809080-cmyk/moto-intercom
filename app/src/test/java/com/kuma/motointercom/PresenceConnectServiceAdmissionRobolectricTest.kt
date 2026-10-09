package com.kuma.motointercom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.SystemClock
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadow.api.Shadow
import org.webrtc.PeerConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingNsdInputShadow::class, RecordingP2pInputShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PresenceConnectServiceAdmissionRobolectricTest {
    @Test fun lateActualLanCacheCannotRetireTheNewerWifiRuntime() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            val ids = AtomicInteger()
            h.setAttemptFactory { ids.incrementAndGet(); RECOVERY }
            h.ensureRegisteredNsd(); h.prepareActualWifiTxt()
            val oldListener = h.nsd.discoveries.last()
            val input = h.found(oldListener)
            val gate = h.pauseLanPublication()
            val resolving = CompletableFuture.runAsync { h.resolve(input) }
            try {
                assertTrue(gate.entered.await(3, TimeUnit.SECONDS))
                h.advanceTo(SystemClock.elapsedRealtime() + 1L); h.txt(RUNTIME_B)
                h.f.awaitMain { h.presence()?.sessionId == RUNTIME_B }
                gate.release(); resolving.get(3, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle(); h.assertWaiting(goal, ids)
                assertEquals(RUNTIME_B, h.presence()?.sessionId)
                h.resolve(h.found(oldListener)); h.assertWaiting(goal, ids)
                assertEquals(RUNTIME_B, h.presence()?.sessionId)
                h.armActualProbe(goal); h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs))
                h.resolve(h.found(h.nsd.discoveries.last(), RUNTIME_B))
                h.f.awaitMain { h.actor.currentAttempt?.id == RECOVERY }; h.recoveryBarrier()
                assertEquals(1, ids.get())
                assertEquals(TargetLock(REMOTE_DEVICE, RUNTIME_B), h.actor.currentAttempt?.targetLock)
            } finally { gate.release(); resolving.get(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun actualWifiRuntimeSupersedingBlockedLanFactoryRejectsOldAdoption() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            h.prepareActualWifiTxt(); h.armActualProbe(goal)
            h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs))
            h.f.awaitMain { h.nsd.discoveries.isNotEmpty() }
            val gate = h.holdRecoveryFactory()
            try {
                h.resolve(h.found(h.nsd.discoveries.last()))
                h.f.awaitMain { gate.entered.count == 0L }
                h.advanceTo(SystemClock.elapsedRealtime() + 1L); h.txt(RUNTIME_B)
                h.f.awaitMain { h.presence()?.sessionId == RUNTIME_B }
                gate.release()
                h.f.awaitMain { h.actor.currentAttempt?.targetLock == TargetLock(REMOTE_DEVICE, RUNTIME_B) }
                h.recoveryBarrier()
                assertNotEquals(RECOVERY, h.actor.currentAttempt?.id)
                assertNull(h.actor.terminalOutcome(RECOVERY)); h.assertNoRecoveryEffects(RECOVERY)
                assertEquals(0, (h.actor.state.value as IntercomState.Recovering).consecutiveFinalFailures)
            } finally { gate.release() }
        }
    }

    @Test fun actualStopDropsScheduledProbeAndOldNsdResolution() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            val ids = AtomicInteger()
            h.setAttemptFactory { ids.incrementAndGet(); RECOVERY }
            h.ensureRegisteredNsd()
            val oldListener = h.nsd.discoveries.last()
            val pending = h.found(oldListener)
            h.armActualProbe(goal)
            val scans = h.nsd.discoveries.size
            val resolutions = h.nsd.resolutions.size
            h.f.service.requestStop()
            h.f.awaitMain { h.actor.state.value == IntercomState.Offline }
            h.resolve(pending)
            oldListener.onServiceFound(nsdInfo())
            h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs) + 60_000L)
            assertEquals(scans, h.nsd.discoveries.size)
            assertEquals(resolutions, h.nsd.resolutions.size)
            assertEquals(0, ids.get())
            assertNull(h.actor.recoveryIntent)
            assertNull(h.actor.currentAttempt)
            assertEquals(IntercomState.Offline, h.actor.state.value)
            h.assertNoRecoveryEffects(RECOVERY)
        }
    }

    @Test fun actualRefreshRetiresOldNsdInputsButKeepsAuthorizedFutureGoal() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            val ids = AtomicInteger()
            h.setAttemptFactory { ids.incrementAndGet(); RECOVERY }
            h.ensureRegisteredNsd()
            val oldLan = h.lan()
            val oldToken = field(h.f.service, "activeSession").get(h.f.service)
            val oldListener = h.nsd.discoveries.last()
            val pending = h.found(oldListener)
            h.armActualProbe(goal)
            h.f.service.requestDiscoveryRefresh()
            h.f.awaitMain { h.fx.snapshot().any { it is SessionEffect.RefreshDiscovery } }
            h.fx.release(h.fx.single { it is SessionEffect.RefreshDiscovery })
            h.f.awaitMain { field(h.f.service, "activeSession").get(h.f.service) != null &&
                field(h.f.service, "activeSession").get(h.f.service) != oldToken &&
                field(h.f.service, "lanDiscovery").get(h.f.service) != null &&
                field(h.f.service, "lanDiscovery").get(h.f.service) !== oldLan }
            val resolutions = h.nsd.resolutions.size
            h.resolve(pending)
            oldListener.onServiceFound(nsdInfo(RUNTIME_B))
            assertEquals(resolutions, h.nsd.resolutions.size)
            h.assertWaiting(goal, ids)
            val scans = h.nsd.discoveries.size
            h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs))
            h.f.awaitMain { h.nsd.discoveries.size == scans + 1 }
            h.assertWaiting(goal, ids)
            h.resolve(h.found(h.nsd.discoveries.last()))
            h.f.awaitMain { h.actor.currentAttempt?.id == RECOVERY }
            h.recoveryBarrier()
            assertEquals(1, ids.get())
            assertEquals(TargetLock(REMOTE_DEVICE, REMOTE_RUNTIME), h.actor.currentAttempt?.targetLock)
            assertEquals(0, (h.actor.state.value as IntercomState.Recovering).consecutiveFinalFailures)
        }
    }

    @Test fun actualRecoveryProbeNeedsNewNsdInputForSameOrNewRemoteRuntime() = runBlocking {
        for (returnedRuntime in listOf(REMOTE_RUNTIME, RUNTIME_B)) {
            Harness().use { h ->
                val goal = h.waitingRecoveryGoal()
                val eligible = requireNotNull(goal.eligibleAfterElapsedMs)
                val ids = AtomicInteger()
                h.setAttemptFactory { ids.incrementAndGet(); RECOVERY }
                h.ensureRegisteredNsd()
                val oldListener = h.nsd.discoveries.last()
                h.resolve(h.found(oldListener))
                h.assertWaiting(goal, ids)
                val pendingBeforeProbe = h.found(oldListener)
                val scans = h.nsd.discoveries.size
                h.armActualProbe(goal)
                h.advanceTo(eligible - 1L)
                assertEquals(scans, h.nsd.discoveries.size)
                h.assertWaiting(goal, ids)
                h.advanceTo(eligible)
                h.f.awaitMain { h.nsd.discoveries.size == scans + 1 }
                val resolutions = h.nsd.resolutions.size
                h.resolve(pendingBeforeProbe)
                oldListener.onServiceFound(nsdInfo(returnedRuntime))
                assertEquals(resolutions, h.nsd.resolutions.size)
                h.assertWaiting(goal, ids)
                h.advanceTo(eligible + 30_000L)
                assertEquals(scans + 2, h.nsd.discoveries.size)
                h.assertWaiting(goal, ids)
                h.resolve(h.found(h.nsd.discoveries.last(), returnedRuntime))
                h.f.awaitMain { h.actor.currentAttempt?.id == RECOVERY }
                h.recoveryBarrier()
                val episode = h.actor.state.value as IntercomState.Recovering
                assertEquals(1, ids.get())
                assertEquals(0, episode.consecutiveFinalFailures)
                assertEquals(TargetLock(REMOTE_DEVICE, returnedRuntime), episode.attempt.targetLock)
                assertEquals(ConnectionTrigger.RECOVERY, episode.attempt.trigger)
                assertEquals(SystemClock.elapsedRealtime() + 10_000L, episode.attempt.deadlineElapsedRealtimeMs)
                assertTrue(h.fx.ever.any { it is SessionEffect.OpenTargetedTransport && it.attempt == episode.attempt })
                assertNull(h.actor.recoveryIntent?.resetAttemptId)
                assertNull(h.actor.recoveryIntent?.eligibleAfterElapsedMs)
                h.resolve(h.found(h.nsd.discoveries.last(), returnedRuntime))
                h.recoveryBarrier()
                assertEquals(1, ids.get())
                assertEquals(episode, h.actor.state.value)
            }
        }
    }

    @Test fun realNsdLostBeforeResolveCannotCreateAFutureRecoveryEpisode() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            val ids = AtomicInteger()
            h.setAttemptFactory { ids.incrementAndGet(); RECOVERY }
            h.armActualProbe(goal)
            h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs))
            h.f.awaitMain { h.nsd.discoveries.isNotEmpty() }
            val listener = h.nsd.discoveries.last()
            val pending = h.found(listener)
            listener.onServiceLost(pending.info)
            h.resolve(pending)
            h.assertWaiting(goal, ids)
            h.resolve(h.found(listener))
            h.f.awaitMain { h.actor.currentAttempt?.id == RECOVERY }
            h.recoveryBarrier()
            assertEquals(1, ids.get())
            assertEquals(TargetLock(REMOTE_DEVICE, REMOTE_RUNTIME), h.actor.currentAttempt?.targetLock)
        }
    }

    @Test fun realNsdLostDuringRecoveryIdFactoryRevokesFinalAdoption() = runBlocking {
        Harness().use { h ->
            val goal = h.waitingRecoveryGoal()
            h.armActualProbe(goal)
            h.advanceTo(requireNotNull(goal.eligibleAfterElapsedMs))
            h.f.awaitMain { h.nsd.discoveries.isNotEmpty() }
            val listener = h.nsd.discoveries.last()
            val gate = h.holdRecoveryFactory()
            try {
                val actual = h.found(listener)
                h.resolve(actual)
                h.f.awaitMain { gate.entered.count == 0L }
                assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), h.actor.state.value)
                listener.onServiceLost(actual.info)
                assertFalse("Lost waited for actor factory under source lock", gate.timedOut.get())
                gate.release()
                h.recoveryBarrier()
                assertEquals(goal, h.actor.recoveryIntent)
                assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), h.actor.state.value)
                assertNull(h.actor.currentAttempt)
                assertNull(h.actor.terminalOutcome(RECOVERY))
                h.assertNoRecoveryEffects(RECOVERY)
                h.resolve(h.found(listener))
                h.f.awaitMain { h.actor.state.value is IntercomState.Recovering }
                h.recoveryBarrier()
                val next = h.actor.state.value as IntercomState.Recovering
                assertNotEquals(RECOVERY, next.attempt.id)
                assertEquals(TargetLock(REMOTE_DEVICE, REMOTE_RUNTIME), next.attempt.targetLock)
                assertEquals(0, next.consecutiveFinalFailures)
            } finally { gate.release() }
        }
    }

    @Test fun readerEofQueuedBeforeOffOnCannotAcquireANewRecoveryTicket() = runBlocking {
        Harness().use { h ->
            h.adopt()
            val first = h.pair(h.parent, Transport.WIFI_DIRECT)
            h.converge(first)
            h.makeConnected(first)
            h.setAttemptFactory { RECOVERY }
            first.remote.close()
            // Reader completion proves production onFailure has queued its original receipt.
            assertTrue(first.readerCompleted.await(3, TimeUnit.SECONDS))
            assertTrue(h.actor.state.value is IntercomState.Connected)
            h.f.service.setAutomaticReconnectEnabled(false)
            h.f.service.setAutomaticReconnectEnabled(true)
            h.f.awaitMain { h.actor.state.value == IntercomState.Discovering(LOCAL_RUNTIME) }
            h.recoveryBarrier()
            assertNull(h.actor.currentAttempt)
            assertNull(h.actor.recoveryIntent)
            h.assertNoRecoveryEffects(RECOVERY)
        }
    }

    @Test fun readerEofObservedAfterReenableRecoversTheExistingConnectedAttempt() = runBlocking {
        Harness().use { h ->
            h.adopt()
            val first = h.pair(h.parent, Transport.WIFI_DIRECT)
            h.converge(first)
            val connected = h.makeConnected(first)
            h.setAttemptFactory { RECOVERY }
            h.f.service.setAutomaticReconnectEnabled(false)
            h.f.service.setAutomaticReconnectEnabled(true)
            h.recoveryBarrier()
            first.remote.close()
            assertTrue(first.readerCompleted.await(3, TimeUnit.SECONDS))
            h.f.awaitMain { h.actor.state.value is IntercomState.Recovering }
            h.recoveryBarrier()
            val recovering = h.actor.state.value as IntercomState.Recovering
            assertEquals(RECOVERY, recovering.attempt.id)
            assertEquals(connected.attempt.targetLock, recovering.attempt.targetLock)
            assertEquals(0, recovering.consecutiveFinalFailures)
            assertEquals(connected.attempt, h.actor.recoveryIntent?.sourceConnectedAttempt)
            assertTrue(h.fx.ever.any { it is SessionEffect.RestartDiscovery && it.attempt == recovering.attempt })
        }
    }

    @Test fun actualRecoveryFactoryCannotOvertakeOffManualOrForgetCommands() = runBlocking {
        for (command in listOf("off", "manual", "forget")) {
            Harness().use { h ->
                h.allowCommandRuntime()
                h.adopt()
                val first = h.pair(h.parent, Transport.WIFI_DIRECT)
                h.converge(first)
                h.makeConnected(first)
                val gate = h.holdRecoveryFactory()
                var lookup: ForgetLookupGate? = null
                try {
                    first.remote.close()
                    h.f.awaitMain { gate.entered.count == 0L }
                    when (command) {
                        "off" -> h.f.service.setAutomaticReconnectEnabled(false)
                        "manual" -> assertTrue(h.f.service.connectToPresence(h.selectableB(), PresenceConnectRequest(
                            LOCAL_RUNTIME, DEVICE_B, RUNTIME_B, "manual-after-real-loss")))
                        "forget" -> {
                            val held = h.holdForgetLookup(); lookup = held
                            h.f.service.forgetPairing(REMOTE_DEVICE)
                            assertTrue("forget must reach its suspended repository call", held.entered.isCompleted)
                        }
                    }
                    assertFalse("Main command waited for factory under policy lock", gate.timedOut.get())
                    gate.release()
                    if (command == "manual") {
                        h.f.awaitMain { h.actor.currentAttempt?.targetDeviceId == DEVICE_B }
                        assertEquals(RUNTIME_B, h.actor.currentAttempt?.targetLock?.expectedRemoteSessionId)
                        assertNotEquals(RECOVERY, h.actor.currentAttempt?.id)
                    } else h.f.awaitMain { h.actor.state.value == IntercomState.Discovering(LOCAL_RUNTIME) }
                    h.recoveryBarrier()
                    assertNull(h.actor.recoveryIntent)
                    assertNull(h.actor.terminalOutcome(RECOVERY))
                    h.assertNoRecoveryEffects(RECOVERY)
                    lookup?.let { held ->
                        held.proceed.complete(Unit)
                        h.f.awaitMain { held.forgotten.isCompleted }
                        assertTrue(held.forgotten.await())
                        h.recoveryBarrier()
                        assertNull(h.actor.recoveryIntent)
                        assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), h.actor.state.value)
                    }
                } finally { gate.release(); lookup?.proceed?.complete(Unit) }
            }
        }
    }

    @Test
    fun realSiblingLeaseFollowsItsOriginalGrantAndUnrelatedTupleDoesNot() = runBlocking {
        Harness().use { h ->
            h.adopt()
            val first = h.pair(h.parent, Transport.WIFI_DIRECT)
            // Both HELLOs finish under A before the Service adopts glare child C.
            val sibling = h.pair(h.parent, Transport.LAN)
            assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, sibling.lease.currentStage)
            h.converge(first)
            val child = requireNotNull(h.actor.currentAttempt)

            assertEquals(CHILD, child.id)
            assertNotEquals(h.parent.id, child.id)
            assertEquals(h.parent.runtimeSessionId, child.runtimeSessionId)
            assertEquals(h.parent.targetLock, child.targetLock)
            assertEquals(h.parent.deadlineElapsedRealtimeMs, child.deadlineElapsedRealtimeMs)
            assertEquals(child, h.grant.adoptedAttempt)
            assertEquals(SignalingAttemptPhase.OPTIMIZING_MEDIA, h.actor.activeControlAttempt?.phase)

            // Live sockets with verified identity must also belong to this grant.
            val unrelated = h.parent.copy(id = UNRELATED)
            val unrelatedPair = h.pair(unrelated, Transport.LAN)
            assertEquals(RequestRole.RESPONDER, unrelatedPair.local.requestRole)
            assertEquals(sibling.local.wireRequestKey, unrelatedPair.local.wireRequestKey)
            assertEquals(sibling.local.targetLock, unrelatedPair.local.targetLock)
            assertEquals(h.parent.deadlineElapsedRealtimeMs, unrelated.deadlineElapsedRealtimeMs)
            h.rejectWithoutChangingOwner(unrelatedPair)

            h.rejectWithoutChangingOwner(h.pair(
                h.parent.copy(deadlineElapsedRealtimeMs = h.parent.deadlineElapsedRealtimeMs + 1L),
                Transport.LAN
            ))
            h.rejectWithoutChangingOwner(h.pair(
                h.parent, Transport.LAN, wire = OTHER_WIRE
            ))
            val wrongRole = h.pair(h.parent, Transport.LAN, remoteRequests = false)
            assertEquals(RequestRole.REQUESTER, wrongRole.local.requestRole)
            h.rejectWithoutChangingOwner(wrongRole)

            assertEquals(h.parent, sibling.local.originatingAttempt)
            assertEquals(h.parent, sibling.lease.originatingAttempt)
            assertEquals(RequestRole.RESPONDER, sibling.local.requestRole)
            assertEquals(CHILD, sibling.local.wireRequestKey.attemptId)

            h.register(sibling)
            assertEquals(PendingSocketLease.Stage.TRANSFERRED, sibling.lease.currentStage)
            assertSame(sibling.local, h.registered()[sibling.local.channel.channelId])
            assertFalse(sibling.local.isClosed)
            assertEquals(h.parent, sibling.local.originatingAttempt)

            sibling.sendRequest(Transport.LAN)
            h.f.awaitMain {
                h.actor.activeControlAttempt?.selectionCohort?.channelIds?.contains(
                    sibling.local.channel.channelId
                ) == true
            }
            val select = h.fx.single {
                it is SessionEffect.SelectMediaChannel && it.attemptId == child.id
            }
            h.fx.release(select)
            h.f.awaitMain {
                h.actor.activeControlAttempt?.mediaOwnerChannelId == sibling.local.channel.channelId
            }
            assertEquals(child, h.actor.currentAttempt)
            assertEquals(child, h.grant.adoptedAttempt)
            assertEquals(
                sibling.local.channel.channelId,
                h.actor.activeControlAttempt?.mediaOwnerChannelId
            )
            // CONNECT_ACCEPT is held, so this control fixture never starts native media.
        }
    }

    @Test
    fun actualQueuedEofAndSendFailureCannotOvertakeServiceRevocation() = runBlocking {
        for (sendFailure in listOf(false, true)) {
            Harness().use { h ->
                h.adopt()
                val first = h.pair(h.parent, Transport.WIFI_DIRECT)
                h.converge(first)
                val accept = if (sendFailure) h.prepareActualAcceptEffect() else null
                val child = requireNotNull(h.actor.currentAttempt)
                val before = h.graph()
                val gate = h.clock.holdNext()

                try {
                    if (sendFailure) {
                        first.localSocket.shutdownOutput()
                        h.fx.release(requireNotNull(accept))
                    } else {
                        first.remote.close()
                    }
                    h.f.awaitMain { gate.entered.count == 0L }

                    // This marker is FIFO between the real failure and exact cancellation.
                    val observedBeforeCancel = h.marker()
                    val cleanup = h.seedCleanupRecord()
                    h.revokeFromService(resourcesAlreadyClosing = true)
                    assertFalse("revoke waited for a clock callback under its lock", gate.timedOut.get())
                    gate.release()

                    assertEquals(before, observedBeforeCancel.get(5, TimeUnit.SECONDS))
                    h.actorBarrier()
                    assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), h.actor.state.value)
                    assertNull(h.actor.currentAttempt)
                    assertNull(h.actor.activeControlAttempt)
                    assertEquals(
                        ConnectionAttemptTerminalOutcome.CANCELED,
                        h.actor.terminalOutcome(child.id)
                    )
                    assertSame(cleanup, h.cleanupRequest())
                    assertFalse(h.fx.ever.any {
                        it is SessionEffect.AbortAttemptAndResumeDiscovery &&
                            it.attemptId == child.id
                    })
                    assertTrue(first.local.isClosed)
                } finally {
                    gate.release()
                }
            }
        }
    }

    @Test
    fun terminalFirstDelayedActualAbortCannotReplaceAnExistingCleanupRequest() = runBlocking {
        Harness().use { h ->
            h.adopt()
            val first = h.pair(h.parent, Transport.WIFI_DIRECT)
            h.converge(first)
            val child = requireNotNull(h.actor.currentAttempt)
            val gate = h.clock.holdNext()
            try {
                first.remote.close()
                h.f.awaitMain { gate.entered.count == 0L }
                val terminalObserved = h.marker()
                gate.release()
                val terminal = terminalObserved.get(5, TimeUnit.SECONDS)
                assertNull(terminal.attempt)
                assertEquals(ConnectionAttemptTerminalOutcome.FAILED, terminal.childTerminal)

                val abort = h.fx.single {
                    it is SessionEffect.AbortAttemptAndResumeDiscovery &&
                        it.attemptId == child.id
                }
                val cleanup = h.seedCleanupRecord()
                h.revokeFromService(resourcesAlreadyClosing = true)
                h.actorBarrier()

                // Release the exact real EOF effect through the original Service collector.
                h.fx.release(abort)
                h.f.awaitMain {
                    h.logs.any { it.contains("连接尝试已中止：${child.id.value}") }
                }
                assertSame(cleanup, h.cleanupRequest())
                assertEquals(17L, h.cleanupRequest()?.discoveryRefreshGeneration)
                assertEquals(
                    ConnectionAttemptTerminalOutcome.FAILED,
                    h.actor.terminalOutcome(child.id)
                )
            } finally {
                gate.release()
            }
        }
    }

    private data class Graph(
        val state: IntercomState,
        val attempt: ConnectionAttempt?,
        val active: AttemptChannelSet?,
        val channels: Map<ControlChannelId, VerifiedControlChannel>,
        val admission: PresenceConnectAdmission?,
        val parentTerminal: ConnectionAttemptTerminalOutcome?,
        val childTerminal: ConnectionAttemptTerminalOutcome?
    )

    private class Harness : AutoCloseable {
        val f = IncomingConfirmationServiceFixture()
        val actor = f.actor
        val logs = CopyOnWriteArrayList<String>()
        val clock = GateClock()
        private val pairs = mutableListOf<OwnedPair>()
        private val coordinator = field(actor, "signalingControl").get(actor) as SignalingControlCoordinator
        private val sessions = field(f.service, "sessions").get(f.service) as SessionGeneration
        private val cleanup = field(f.service, "recoveryCleanupCoordinator")
            .get(f.service) as RecoveryCleanupCoordinator

        @Suppress("UNCHECKED_CAST")
        val fx = HoldingEffects(field(actor, "effectChannel").get(actor) as Channel<SessionEffect>)

        private var tokenStorage: SessionGeneration.Token? = null
        val token get() = requireNotNull(tokenStorage)
        lateinit var parent: ConnectionAttempt
        lateinit var grant: PresenceConnectAdmission
        private var initialEffects = emptyList<SessionEffect>()
        private val idGates = CopyOnWriteArrayList<ReadGate>()
        private var commandOwnership: Any? = null

        init {
            // The existing effects Flow keeps the original channel and Service collector.
            field(actor, "effectChannel").set(actor, fx)
            field(coordinator, "attemptIdFactory").set(coordinator, { PARENT })
            field(coordinator, "clock").set(coordinator, MonotonicClock {
                MonotonicTimestamp(clock.read())
            })
        }

        suspend fun adopt() {
            tokenStorage = f.activateRuntime(LOCAL_RUNTIME)
            field(f.service, "localDeviceId").set(f.service, LOCAL_DEVICE)
            f.service.setListener(object : IntercomService.Listener {
                override fun onStatusChanged(status: String, running: Boolean) = Unit
                override fun onLog(message: String) { logs += message }
                override fun onError(message: String) = Unit
            })
            val request = PresenceConnectRequest(
                LOCAL_RUNTIME, REMOTE_DEVICE, REMOTE_RUNTIME, "service-lease-request"
            )
            grant = PresenceConnectAdmission(sessions, token, request)
            field(f.service, "latestPresenceAdmission").set(f.service, grant)
            field(f.service, "latestPresenceConnectRequest").set(f.service, request)
            assertTrue(withTimeout(5_000L) {
                actor.dispatchAndAwait(SessionEvent.ConnectPresenceRequested(
                    LOCAL_RUNTIME, REMOTE_DEVICE, REMOTE_RUNTIME,
                    setOf(Transport.LAN, Transport.WIFI_DIRECT), admission = grant
                ))
            })
            parent = requireNotNull(actor.currentAttempt)
            initialEffects = fx.snapshot()
        }

        fun pair(
            origin: ConnectionAttempt,
            transport: Transport,
            wire: ConnectionAttemptId = CHILD,
            remoteRequests: Boolean = true
        ): OwnedPair = OwnedPair.establish(origin, transport, wire, remoteRequests)
            .also { pairs += it }

        suspend fun register(pair: OwnedPair) {
            f.register(token, pair.local, pair.lease)
            f.awaitMain {
                (field(pair.local, "readerStarted").get(pair.local) as AtomicBoolean).get()
            }
            assertEquals(PendingSocketLease.Stage.TRANSFERRED, pair.lease.currentStage)
            actorBarrier()
        }

        suspend fun converge(first: OwnedPair) {
            register(first)
            first.sendRequest(Transport.LAN)
            f.awaitMain {
                actor.currentAttempt?.id == CHILD &&
                    actor.activeControlAttempt?.phase == SignalingAttemptPhase.OPTIMIZING_MEDIA
            }
            actorBarrier()
            initialEffects.forEach(fx::release)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(CHILD, actor.currentAttempt?.id)
        }

        suspend fun rejectWithoutChangingOwner(pair: OwnedPair) {
            val before = graph()
            f.register(token, pair.local, pair.lease)
            f.awaitMain { pair.local.isClosed }
            assertEquals(PendingSocketLease.Stage.CLOSED, pair.lease.currentStage)
            assertFalse(registered().containsKey(pair.local.channel.channelId))
            actorBarrier()
            assertEquals(before, graph())
        }

        suspend fun prepareActualAcceptEffect(): SessionEffect {
            val scheduled = fx.single {
                it is SessionEffect.ScheduleAttemptMilestone &&
                    it.milestone is AttemptMilestone.MediaOptimization &&
                    it.milestone.attempt.id == CHILD
            } as SessionEffect.ScheduleAttemptMilestone
            fx.release(scheduled)
            f.awaitMain {
                val scheduler = requireNotNull(field(f.service, "attemptMilestoneScheduler").get(f.service))
                (field(scheduler, "scheduled").get(scheduler) as Map<*, *>).isNotEmpty()
            }
            ShadowSystemClock.advanceBy(Duration.ofMillis(
                (scheduled.milestone.scheduledAt.elapsedRealtimeMs - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
            ))
            f.awaitMain { fx.snapshot().any { it is SessionEffect.SelectMediaChannel } }
            fx.release(fx.single { it is SessionEffect.SelectMediaChannel })
            f.awaitMain { fx.snapshot().any { it is SessionEffect.SendConnectAccept } }
            assertEquals(SignalingAttemptPhase.ACCEPTING, actor.activeControlAttempt?.phase)
            return fx.single { it is SessionEffect.SendConnectAccept }
        }

        fun setAttemptFactory(factory: () -> ConnectionAttemptId) {
            field(coordinator, "attemptIdFactory").set(coordinator, factory)
        }

        suspend fun makeConnected(pair: OwnedPair): IntercomState.Connected {
            val accept = prepareActualAcceptEffect()
            pair.remote.startReader(onMessage = {}, onFailure = {})
            fx.release(accept)
            f.awaitMain { fx.snapshot().any { it is SessionEffect.StartWebRtc } && pair.remote.phase == SignalingPhase.ACCEPTED }
            val start = fx.single { it is SessionEffect.StartWebRtc } as SessionEffect.StartWebRtc
            // Keep native creation held; exercise actual SDP readers and the production SDK callback.
            val candidate = pair.local.toConnectionCandidateContext(start.attempt)
            field(f.service, "activeMediaContext").set(f.service, candidate)
            field(f.service, "activeMediaSession").set(f.service, pair.local)
            pair.sendRemote(SignalingMessageV2.Offer("{\"type\":\"offer\",\"sdp\":\"v=0\"}"))
            f.awaitMain { pair.local.phase == SignalingPhase.READY_TO_SEND_ANSWER }
            pair.sendLocal(SignalingMessageV2.Answer("{\"type\":\"answer\",\"sdp\":\"v=0\"}"))
            f.awaitMain { pair.remote.phase == SignalingPhase.MEDIA_NEGOTIATING }
            IntercomService::class.java.declaredMethods.single { it.name.startsWith("onConnectionStateChanged") &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == 3 }
                .apply { isAccessible = true }.invoke(f.service, token.value, candidate, PeerConnection.PeerConnectionState.CONNECTED)
            f.awaitMain { actor.state.value is IntercomState.Connected && pair.local.phase == SignalingPhase.CONNECTED }
            recoveryBarrier()
            assertNull(graph().admission)
            return actor.state.value as IntercomState.Connected
        }

        suspend fun recoveryBarrier() {
            assertTrue(withTimeout(5_000L) { actor.dispatchAndAwait(SessionEvent.ConfirmationAvailabilityChanged(
                LOCAL_RUNTIME, ConfirmationAvailability(true, false))) })
        }

        suspend fun waitingRecoveryGoal(): AutomaticRecoveryIntent {
            allowCommandRuntime()
            shadowOf(f.service.application).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            adopt()
            val first = pair(parent, Transport.WIFI_DIRECT)
            converge(first)
            makeConnected(first)
            setAttemptFactory { ConnectionAttemptId.create() }
            first.remote.close()
            f.awaitMain { actor.state.value is IntercomState.Recovering }
            repeat(3) { failure ->
                val before = actor.state.value as IntercomState.Recovering
                assertEquals(failure, before.consecutiveFinalFailures)
                fx.release(fx.single { it is SessionEffect.ScheduleAttemptDeadline && it.attempt == before.attempt })
                f.awaitMain {
                    val scheduler = requireNotNull(field(f.service, "attemptDeadlineScheduler").get(f.service))
                    val scheduled = field(scheduler, "scheduled").get(scheduler)
                    scheduled != null && field(scheduled, "attempt").get(scheduled) == before.attempt
                }
                advanceTo(before.attempt.deadlineElapsedRealtimeMs)
                f.awaitMain { actor.currentAttempt != before.attempt }
                recoveryBarrier()
                assertEquals(ConnectionAttemptTerminalOutcome.TIMED_OUT, actor.terminalOutcome(before.attempt.id))
            }
            val reset = actor.state.value as IntercomState.Resetting
            fx.release(fx.single { it is SessionEffect.ResetWirelessEnvironment && it.failedAttemptId == reset.failedAttemptId })
            f.awaitMain { actor.state.value == IntercomState.Discovering(LOCAL_RUNTIME) &&
                actor.recoveryIntent?.eligibleAfterElapsedMs != null &&
                field(f.service, "activeSession").get(f.service) != null &&
                field(f.service, "lanDiscovery").get(f.service) != null }
            tokenStorage = field(f.service, "activeSession").get(f.service) as SessionGeneration.Token
            recoveryBarrier()
            return requireNotNull(actor.recoveryIntent)
        }

        val nsd: RecordingNsdInputShadow get() = Shadow.extract(f.service.getSystemService(Context.NSD_SERVICE) as NsdManager)
        private val p2p: RecordingP2pInputShadow get() = Shadow.extract(f.service.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager)
        suspend fun prepareActualWifiTxt() {
            val tunnel = field(f.service, "wifiTunnel").get(f.service) as WifiDirectTunnel
            if (!(field(tunnel, "serviceDiscoveryReady").get(tunnel) as Boolean)) {
                f.service.applicationContext.sendBroadcast(Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    .putExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_ENABLED))
                f.awaitMain { p2p.groups.isNotEmpty() || field(tunnel, "serviceDiscoveryReady").get(tunnel) as Boolean }
                while (p2p.groups.isNotEmpty()) p2p.groups.removeFirst().onGroupInfoAvailable(null)
                shadowOf(Looper.getMainLooper()).idle(); advanceTo(SystemClock.elapsedRealtime() + 500L)
            }
            f.awaitMain { p2p.dns.isNotEmpty() && field(tunnel, "serviceDiscoveryReady").get(tunnel) as Boolean }
        }
        fun txt(runtime: RuntimeSessionId) {
            val peer = WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:02"; deviceName = "remote"; status = WifiP2pDevice.CONNECTED }
            p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", mapOf("appId" to "MotoCom", "protocolVersion" to "2",
                "deviceId" to REMOTE_DEVICE, "sessionId" to runtime.value, "nickname" to "remote", "deviceName" to "fixture"), peer)
        }
        fun presence(): RiderPresence? = (field(f.service, "presenceAggregator").get(f.service) as PresenceAggregator)
            .snapshot().presences.singleOrNull { it.deviceId == REMOTE_DEVICE && it.isSelectable }
        @Suppress("UNCHECKED_CAST") fun pauseLanPublication(): ReadGate = ReadGate().also { gate ->
            idGates += gate
            val current = lan(); val callback = field(current, "onLog")
            val original = callback.get(current) as (String) -> Unit
            val first = AtomicBoolean(true)
            callback.set(current, { message: String ->
                original(message)
                if (message.startsWith("发现局域网车友") && first.getAndSet(false)) {
                    gate.entered.countDown()
                    if (!gate.released.await(5, TimeUnit.SECONDS)) { gate.timedOut.set(true); error("LAN publication gate timed out") }
                }
            })
        }
        fun lan(): LanDiscoveryCoordinator = field(f.service, "lanDiscovery").get(f.service) as LanDiscoveryCoordinator
        fun ensureRegisteredNsd() {
            val current = lan()
            if (field(current, "nsdDiscoveryListener").get(current) == null)
                current.javaClass.getDeclaredMethod("startNsdDiscovery").apply { isAccessible = true }.invoke(current)
            shadowOf(Looper.getMainLooper()).idle()
        }
        suspend fun armActualProbe(goal: AutomaticRecoveryIntent) {
            fx.release(fx.single { it is SessionEffect.ProbeRecoveryDiscovery && it.intent == goal.ref })
            f.awaitMain { probeIsQueued() }
        }
        private fun probeIsQueued(): Boolean {
            var message = MessageQueue::class.java.getDeclaredField("mMessages").apply { isAccessible = true }
                .get(Looper.getMainLooper().queue) as Message?
            while (message != null) {
                if (message.callback?.javaClass?.name?.contains("scheduleRecoveryDiscoveryProbe") == true) return true
                message = Message::class.java.getDeclaredField("next").apply { isAccessible = true }.get(message) as Message?
            }
            return false
        }
        fun advanceTo(elapsedMs: Long) {
            ShadowSystemClock.advanceBy(Duration.ofMillis((elapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun found(listener: NsdManager.DiscoveryListener, runtime: RuntimeSessionId = REMOTE_RUNTIME): RecordingNsdInputShadow.Resolution {
            val count = nsd.resolutions.size
            listener.onServiceFound(nsdInfo(runtime))
            assertEquals(count + 1, nsd.resolutions.size)
            return nsd.resolutions.last()
        }
        fun resolve(resolution: RecordingNsdInputShadow.Resolution) = resolution.listener.onServiceResolved(resolution.info)
        suspend fun assertWaiting(goal: AutomaticRecoveryIntent, ids: AtomicInteger) {
            // Deliver the original Main callbacks before the non-mutating actor barrier.
            shadowOf(Looper.getMainLooper()).idle()
            recoveryBarrier()
            assertEquals(0, ids.get())
            assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), actor.state.value)
            assertNull(actor.currentAttempt)
            assertEquals(goal, actor.recoveryIntent)
            assertNoRecoveryEffects(RECOVERY)
        }

        fun assertNoRecoveryEffects(id: ConnectionAttemptId) {
            assertFalse(fx.ever.any {
                it is SessionEffect.RestartDiscovery && it.attempt.id == id ||
                    it is SessionEffect.OpenTargetedTransport && it.attempt.id == id ||
                    it is SessionEffect.ScheduleAttemptDeadline && it.attempt.id == id
            })
        }

        fun allowCommandRuntime() {
            val owner = requireNotNull(LegacyRuntimeOwnership.acquire())
            commandOwnership = owner
            field(f.service, "legacyOwnership").set(f.service, owner)
        }

        fun holdRecoveryFactory(): ReadGate = ReadGate().also { gate ->
            idGates += gate
            val first = AtomicBoolean(true)
            setAttemptFactory {
                if (first.getAndSet(false)) {
                    gate.entered.countDown()
                    if (!gate.released.await(5, TimeUnit.SECONDS)) { gate.timedOut.set(true); error("Recovery factory gate timed out") }
                    RECOVERY
                } else ConnectionAttemptId.create()
            }
        }

        fun selectableB(): RiderPresence {
            val aggregator = field(f.service, "presenceAggregator").get(f.service) as PresenceAggregator
            return aggregator.replaceCandidates(Transport.LAN, listOf(DiscoveryCandidate(
                Transport.LAN, "manual-device-b", "127.0.0.1", 1234,
                DiscoveryIdentityClaim(DEVICE_B, RUNTIME_B, "B", "B phone", 2)
            ))).presences.single { it.deviceId == DEVICE_B }
        }

        fun holdForgetLookup(): ForgetLookupGate {
            val delegate = field(f.service, "pairingRepository").get(f.service) as PairingRepository
            return ForgetLookupGate(delegate).also { field(f.service, "pairingRepository").set(f.service, it) }
        }

        fun revokeFromService(resourcesAlreadyClosing: Boolean) {
            IntercomService::class.java.getDeclaredMethod(
                "revokePresenceAdmission", PresenceConnectAdmission::class.java,
                Boolean::class.javaPrimitiveType!!
            ).apply { isAccessible = true }.invoke(f.service, grant, resourcesAlreadyClosing)
        }

        // Completion stays pending; EOF, write failure and Abort use production paths.
        fun seedCleanupRecord(): RecoveryCleanupRequest =
            RecoveryCleanupRequest(LOCAL_RUNTIME, null, 0L, discoveryRefreshGeneration = 17L)
                .also { cleanup.start(it) }

        fun cleanupRequest(): RecoveryCleanupRequest? {
            val active = field(cleanup, "active").get(cleanup) ?: return null
            return field(active, "request").get(active) as RecoveryCleanupRequest
        }

        @Suppress("UNCHECKED_CAST")
        fun registered() = field(f.service, "signalingSessions").get(f.service)
            as Map<ControlChannelId, SignalingSessionV2>

        @Suppress("UNCHECKED_CAST")
        fun graph(): Graph = Graph(
            actor.state.value, actor.currentAttempt, actor.activeControlAttempt,
            (field(coordinator, "channels").get(coordinator)
                as Map<ControlChannelId, VerifiedControlChannel>).toMap(),
            field(coordinator, "ownedPresenceAdmission").get(coordinator) as PresenceConnectAdmission?,
            actor.terminalOutcome(parent.id), actor.terminalOutcome(CHILD)
        )

        fun marker(): CompletableFuture<Graph> {
            val result = CompletableFuture<Graph>()
            assertTrue(actor.dispatch(SessionEvent.AutomaticReconnectChanged(true)) {
                try { result.complete(graph()) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            })
            return result
        }

        suspend fun actorBarrier() {
            assertTrue(withTimeout(5_000L) {
                actor.dispatchAndAwait(SessionEvent.AutomaticReconnectChanged(true))
            })
        }

        override fun close() {
            clock.releaseAll()
            idGates.forEach(ReadGate::release)
            pairs.forEach(OwnedPair::close)
            try { f.close() } finally { commandOwnership?.let(LegacyRuntimeOwnership::release) }
        }
    }

    private class ForgetLookupGate(private val delegate: PairingRepository) : PairingRepository by delegate {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val forgotten = CompletableDeferred<Boolean>()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? {
            if (deviceId == REMOTE_DEVICE) { entered.complete(Unit); proceed.await() }
            return delegate.getByDeviceId(deviceId)
        }
        override suspend fun forget(deviceId: String): Boolean {
            val result = delegate.forget(deviceId)
            if (deviceId == REMOTE_DEVICE) forgotten.complete(result)
            return result
        }
    }

    private class HoldingEffects(
        private val original: Channel<SessionEffect>
    ) : Channel<SessionEffect> by original {
        private val held = CopyOnWriteArrayList<SessionEffect>()
        val ever = CopyOnWriteArrayList<SessionEffect>()

        override suspend fun send(element: SessionEffect) {
            held += element
            ever += element
        }

        fun snapshot(): List<SessionEffect> = held.toList()
        fun single(predicate: (SessionEffect) -> Boolean) = snapshot().single(predicate)

        fun release(effect: SessionEffect) {
            assertTrue("Only release an exact held production effect", held.any { it === effect })
            assertTrue(held.remove(effect))
            assertTrue(original.trySend(effect).isSuccess)
        }
    }

    private class ReadGate {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val timedOut = AtomicBoolean(false)
        fun release() { released.countDown() }
    }

    private class GateClock {
        private val next = AtomicReference<ReadGate?>()
        private val all = CopyOnWriteArrayList<ReadGate>()

        fun holdNext() = ReadGate().also {
            check(next.compareAndSet(null, it))
            all += it
        }

        fun read(): Long {
            val now = SystemClock.elapsedRealtime()
            next.getAndSet(null)?.let {
                it.entered.countDown()
                if (!it.released.await(3, TimeUnit.SECONDS)) {
                    it.timedOut.set(true)
                    error("Actor clock gate timed out")
                }
            }
            return now
        }

        fun releaseAll() { all.forEach(ReadGate::release) }
    }

    private class OwnedPair(
        val local: SignalingSessionV2,
        val remote: SignalingSessionV2,
        val lease: PendingSocketLease,
        val localSocket: Socket,
        private val remoteSocket: Socket
    ) : AutoCloseable {
        val readerCompleted = CountDownLatch(1)
        init {
            val original = field(local, "reader").get(local) as ExecutorService
            field(local, "reader").set(local, object : ExecutorService by original {
                override fun execute(command: Runnable) {
                    original.execute { try { command.run() } finally { readerCompleted.countDown() } }
                }
            })
        }
        fun sendRemote(message: SignalingMessageV2) = send(remote, message)
        fun sendLocal(message: SignalingMessageV2) = send(local, message)
        private fun send(session: SignalingSessionV2, message: SignalingMessageV2) {
            val result = CompletableFuture<Result<Unit>>()
            session.send(message) { result.complete(it) }
            result.get(5, TimeUnit.SECONDS).getOrThrow()
        }
        fun sendRequest(preferred: Transport) {
            val result = CompletableFuture<Result<Unit>>()
            remote.send(SignalingMessageV2.ConnectRequest(RequestTrigger.USER, preferred)) {
                result.complete(it)
            }
            result.get(5, TimeUnit.SECONDS).getOrThrow()
        }

        override fun close() {
            lease.close()
            local.close()
            remote.close()
            runCatching { localSocket.close() }
            runCatching { remoteSocket.close() }
        }

        companion object {
            fun establish(
                origin: ConnectionAttempt,
                transport: Transport,
                wire: ConnectionAttemptId,
                remoteRequests: Boolean
            ): OwnedPair {
                val server = ServerSocket(0)
                val accepting = CompletableFuture.supplyAsync { server.accept() }
                val localSocket = Socket("127.0.0.1", server.localPort)
                val remoteSocket = try {
                    accepting.get(5, TimeUnit.SECONDS)
                } finally {
                    server.close()
                }
                val clock = MonotonicClock { MonotonicTimestamp(SystemClock.elapsedRealtime()) }
                val lease = PendingSocketLease(
                    localSocket, origin, origin.deadlineElapsedRealtimeMs, clock
                ).also { it.armAdmissionDeadline() }
                val remoteAttempt = if (remoteRequests) ConnectionAttempt(
                    wire, REMOTE_RUNTIME, TargetLock(LOCAL_DEVICE, LOCAL_RUNTIME),
                    ConnectionTrigger.USER, ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT),
                    SystemClock.elapsedRealtime() + 60_000L
                ) else null

                val localFuture = CompletableFuture.supplyAsync {
                    SignalingSessionV2.establish(
                        localSocket, transport, PhysicalSocketRole.OPENER,
                        SystemClock.elapsedRealtime(), LOCAL_DEVICE, LOCAL_RUNTIME,
                        "local", "local phone", origin,
                        monotonicClock = clock, pendingSocketLease = lease
                    )
                }
                val remoteFuture = CompletableFuture.supplyAsync {
                    SignalingSessionV2.establish(
                        remoteSocket, transport, PhysicalSocketRole.ACCEPTOR,
                        SystemClock.elapsedRealtime(), REMOTE_DEVICE, REMOTE_RUNTIME,
                        "remote", "remote phone", remoteAttempt,
                        expectedRemoteTargetLock = TargetLock(LOCAL_DEVICE, LOCAL_RUNTIME),
                        monotonicClock = clock
                    )
                }
                return try {
                    val local = localFuture.get(5, TimeUnit.SECONDS)
                    val remote = remoteFuture.get(5, TimeUnit.SECONDS)
                    assertEquals(
                        if (remoteRequests) RequestRole.RESPONDER else RequestRole.REQUESTER,
                        local.requestRole
                    )
                    assertEquals(
                        if (remoteRequests) RequestRole.REQUESTER else RequestRole.RESPONDER,
                        remote.requestRole
                    )
                    assertTrue(lease.prepareAdmission({ true }))
                    OwnedPair(local, remote, lease, localSocket, remoteSocket)
                } catch (failure: Throwable) {
                    lease.close()
                    runCatching { localSocket.close() }
                    runCatching { remoteSocket.close() }
                    localFuture.whenComplete { session, _ -> session?.close() }
                    remoteFuture.whenComplete { session, _ -> session?.close() }
                    throw failure
                }
            }
        }
    }

    companion object {
        @Suppress("DEPRECATION") private fun nsdInfo(runtime: RuntimeSessionId = REMOTE_RUNTIME) = NsdServiceInfo().apply {
            serviceName = "fixture-remote"; serviceType = "_motocom._tcp."
            host = InetAddress.getByName("127.0.0.1"); port = 8890
            setAttribute("id", REMOTE_DEVICE); setAttribute("sessionId", runtime.value)
            setAttribute("name", "remote"); setAttribute("deviceName", "fixture"); setAttribute("protocolVersion", "2")
        }
        private const val LOCAL_DEVICE = "00000000-0000-0000-0000-000000000002"
        private const val REMOTE_DEVICE = "00000000-0000-0000-0000-000000000001"
        private const val DEVICE_B = "00000000-0000-0000-0000-000000000005"
        private val RUNTIME_B = RuntimeSessionId("10000000-0000-0000-0000-000000000005")
        private val LOCAL_RUNTIME = RuntimeSessionId("10000000-0000-0000-0000-000000000002")
        private val REMOTE_RUNTIME = RuntimeSessionId("10000000-0000-0000-0000-000000000001")
        private val CHILD = ConnectionAttemptId("20000000-0000-0000-0000-000000000001")
        private val PARENT = ConnectionAttemptId("20000000-0000-0000-0000-000000000002")
        private val UNRELATED = ConnectionAttemptId("20000000-0000-0000-0000-000000000003")
        private val RECOVERY = ConnectionAttemptId("20000000-0000-0000-0000-000000000004")
        private val OTHER_WIRE = ConnectionAttemptId("20000000-0000-0000-0000-000000000000")
        private fun field(owner: Any, name: String) =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
}
