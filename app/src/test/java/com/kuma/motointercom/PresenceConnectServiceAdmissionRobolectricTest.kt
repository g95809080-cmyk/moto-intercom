package com.kuma.motointercom

import android.os.Looper
import android.os.SystemClock
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PresenceConnectServiceAdmissionRobolectricTest {
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
            pairs.forEach(OwnedPair::close)
            f.close()
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
        private const val LOCAL_DEVICE = "00000000-0000-0000-0000-000000000002"
        private const val REMOTE_DEVICE = "00000000-0000-0000-0000-000000000001"
        private val LOCAL_RUNTIME = RuntimeSessionId("10000000-0000-0000-0000-000000000002")
        private val REMOTE_RUNTIME = RuntimeSessionId("10000000-0000-0000-0000-000000000001")
        private val CHILD = ConnectionAttemptId("20000000-0000-0000-0000-000000000001")
        private val PARENT = ConnectionAttemptId("20000000-0000-0000-0000-000000000002")
        private val UNRELATED = ConnectionAttemptId("20000000-0000-0000-0000-000000000003")
        private val OTHER_WIRE = ConnectionAttemptId("20000000-0000-0000-0000-000000000000")
        private fun field(owner: Any, name: String) =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
}
