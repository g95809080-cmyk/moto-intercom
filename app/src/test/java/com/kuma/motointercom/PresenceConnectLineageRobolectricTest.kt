package com.kuma.motointercom

import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresenceConnectLineageRobolectricTest {
    @Test
    fun transferredGlareGrantCancelsTheFreshChildAndLateCancelCannotClearRequestC() = runBlocking {
        Fixture().use { f ->
            val parent = f.adopt()
            val pair = f.prepare(parent)
            assertTrue(f.await(f.request(pair)))
            val child = requireNotNull(f.actor.currentAttempt)
            assertEquals(CHILD_ID, child.id)
            assertNotEquals(parent.id, child.id)
            assertEquals(parent.targetLock, child.targetLock)
            assertEquals(parent.deadlineElapsedRealtimeMs, child.deadlineElapsedRealtimeMs)
            assertEquals(child, f.grant.adoptedAttempt)
            assertTrue(f.actor.isAttemptAuthorized(child))
            assertEquals(ConnectionAttemptTerminalOutcome.GLARE_LOST, f.actor.terminalOutcome(parent.id))

            val revoked = requireNotNull(f.grant.revoke())
            assertEquals(child, revoked)
            assertFalse(f.actor.isAttemptAuthorized(child))
            val cancel = SessionEvent.PresenceConnectCanceled(revoked, f.grant)
            assertTrue(f.await(cancel))
            assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), f.actor.state.value)
            assertNull(f.actor.currentAttempt)
            assertNull(f.actor.activeControlAttempt)
            assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, f.actor.terminalOutcome(child.id))

            val grantC = f.newGrant()
            val currentC = f.adopt(grantC)
            assertNotEquals(parent.id, currentC.id)
            assertNotEquals(child.id, currentC.id)
            assertTrue(f.actor.isAttemptAuthorized(currentC))
            assertFalse(f.await(cancel))
            assertEquals(currentC, f.actor.currentAttempt)
            assertTrue(f.actor.isAttemptAuthorized(currentC))
            assertNull(f.actor.terminalOutcome(currentC.id))
        }
    }

    @Test
    fun revokedQueuedGlareCannotChangeTheTerminalOrChannelGraphBeforeCancel() = runBlocking {
        Fixture().use { f ->
            val parent = f.adopt()
            val pair = f.prepare(parent)
            val event = f.request(pair)
            val before = f.graph(pair)
            f.drainEffects()
            val gate = f.clock.holdNext()
            val observed = CompletableFuture<Observation>()
            val incoming = f.queue(event) { accepted ->
                observed.complete(Observation(accepted, f.graph(pair), f.drainEffects()))
            }
            gate.awaitEntered()
            val revoked = requireNotNull(f.grant.revoke())
            assertEquals(parent, revoked)
            val cancel = f.queue(SessionEvent.PresenceConnectCanceled(revoked, f.grant))
            gate.release()

            assertFalse(incoming.get(5, TimeUnit.SECONDS))
            val afterGlareBeforeCancel = observed.get(5, TimeUnit.SECONDS)
            assertFalse(afterGlareBeforeCancel.accepted)
            assertEquals(before, afterGlareBeforeCancel.graph)
            assertTrue(afterGlareBeforeCancel.effects.isEmpty())
            assertTrue(cancel.get(5, TimeUnit.SECONDS))
            assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, f.actor.terminalOutcome(parent.id))
            assertNull(f.actor.terminalOutcome(CHILD_ID))
            assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), f.actor.state.value)
            assertNull(f.actor.currentAttempt)
        }
    }

    @Test
    fun revokedQueuedConnectedWithAnActualMediaOwnerCannotRecordSuccessOrPairing() = runBlocking {
        Fixture().use { f ->
            val pair = f.prepare(f.adopt())
            assertTrue(f.await(f.request(pair)))
            val child = f.selectOwner(pair)
            val before = f.graph(pair)
            val gate = f.clock.holdNext()
            val observed = CompletableFuture<Observation>()
            val connected = f.queue(f.connected(child)) { accepted ->
                observed.complete(Observation(accepted, f.graph(pair), f.drainEffects()))
            }
            gate.awaitEntered()
            val revoked = requireNotNull(f.grant.revoke())
            assertEquals(child, revoked)
            val cancel = f.queue(SessionEvent.PresenceConnectCanceled(revoked, f.grant))
            gate.release()

            assertFalse(connected.get(5, TimeUnit.SECONDS))
            val afterConnectedBeforeCancel = observed.get(5, TimeUnit.SECONDS)
            assertFalse(afterConnectedBeforeCancel.accepted)
            assertEquals(before, afterConnectedBeforeCancel.graph)
            assertTrue(afterConnectedBeforeCancel.effects.isEmpty())
            assertTrue(f.repository.saved.isEmpty())
            assertTrue(cancel.get(5, TimeUnit.SECONDS))
            assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, f.actor.terminalOutcome(child.id))
            assertFalse(f.actor.state.value is IntercomState.Connected)
            assertNull(f.actor.currentAttempt)
            assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), f.actor.state.value)
            assertTrue(f.repository.saved.isEmpty())
        }
    }

    @Test
    fun connectedFirstCompletesTheGrantAndLateRevokeKeepsTheEstablishedOwner() = runBlocking {
        Fixture().use { f ->
            val pair = f.prepare(f.adopt())
            assertTrue(f.await(f.request(pair)))
            val child = f.selectOwner(pair)
            assertTrue(f.await(f.connected(child)))
            val connected = f.actor.state.value as IntercomState.Connected
            assertEquals(child, connected.attempt)
            assertEquals(ConnectionAttemptTerminalOutcome.SUCCESS, f.actor.terminalOutcome(child.id))
            assertEquals(pair.local.channel.channelId, f.actor.activeControlAttempt?.mediaOwnerChannelId)
            assertEquals(SignalingAttemptPhase.CONNECTED, f.actor.activeControlAttempt?.phase)
            assertEquals(REMOTE_DEVICE, f.repository.saved.single().remoteDeviceId)
            assertNull(f.grant.revoke())
            assertFalse(f.await(SessionEvent.PresenceConnectCanceled(child, f.grant)))
            assertEquals(connected, f.actor.state.value)
            assertEquals(child, f.actor.currentAttempt)
            assertTrue(f.actor.isAttemptAuthorized(child))
            assertEquals(1, f.repository.saved.size)
        }
    }

    @Test
    fun queuedGlareAtTheOriginalDeadlineDoesNotAdoptAChildAndFreshInboundStillConnects() = runBlocking {
        Fixture().use { f ->
            val parent = f.adopt()
            val pair = f.prepare(parent)
            val event = f.request(pair)
            val before = f.graph(pair)
            f.drainEffects()
            // The decision captures time once before entering the authorization lock.
            val gate = f.clock.holdNext()
            val observed = CompletableFuture<Observation>()
            val incoming = f.queue(event) { accepted ->
                observed.complete(Observation(accepted, f.graph(pair), f.drainEffects()))
            }
            gate.awaitEntered()
            f.clock.value.set(parent.deadlineElapsedRealtimeMs)
            gate.release()
            assertFalse(incoming.get(5, TimeUnit.SECONDS))
            val rejected = observed.get(5, TimeUnit.SECONDS)
            assertEquals(before, rejected.graph)
            assertTrue(rejected.effects.isEmpty())
            assertTrue(f.await(SessionEvent.AttemptTimedOut(
                LOCAL_RUNTIME, parent.id, parent.deadlineElapsedRealtimeMs
            )))
            assertEquals(ConnectionAttemptTerminalOutcome.TIMED_OUT, f.actor.terminalOutcome(parent.id))
            assertNull(f.actor.terminalOutcome(CHILD_ID))
            assertNull(f.actor.currentAttempt)
            assertEquals(IntercomState.Discovering(LOCAL_RUNTIME), f.actor.state.value)
            pair.close()
            assertTrue(f.await(SessionEvent.ChannelClosed(
                LOCAL_RUNTIME, pair.local.channel.channelId, pair.local.wireRequestKey, "expired socket closed"
            )))
            f.drainEffects()
            assertTrue(f.await(SessionEvent.ConfirmationAvailabilityChanged(
                LOCAL_RUNTIME, ConfirmationAvailability(true, false)
            )))
            val freshId = ConnectionAttemptId.create()
            val fresh = f.prepare(null, freshId)
            assertTrue(f.await(f.request(fresh)))
            val pending = requireNotNull(f.actor.pendingInboundRequest)
            assertEquals(freshId, pending.attemptId)
            assertTrue(f.await(SessionEvent.IncomingAccepted(
                pending.runtimeSessionId, pending.attemptId,
                requireNotNull(pending.confirmationChannelId),
                requireNotNull(pending.confirmationActionNonce), f.clock.value.get()
            )))
            val freshOwner = f.selectOwner(fresh)
            assertEquals(freshId, freshOwner.id)
            assertTrue(freshOwner.deadlineElapsedRealtimeMs > parent.deadlineElapsedRealtimeMs)
            assertTrue(f.await(f.connected(freshOwner)))
            assertEquals(freshOwner, (f.actor.state.value as IntercomState.Connected).attempt)
            assertEquals(ConnectionAttemptTerminalOutcome.SUCCESS, f.actor.terminalOutcome(freshId))
            assertEquals(1, f.repository.saved.size)
        }
    }

    private data class Graph(
        val state: IntercomState,
        val attempt: ConnectionAttempt?,
        val active: AttemptChannelSet?,
        val channels: Map<ControlChannelId, VerifiedControlChannel>,
        val parentTerminal: ConnectionAttemptTerminalOutcome?,
        val childTerminal: ConnectionAttemptTerminalOutcome?
    )

    private data class Observation(
        val accepted: Boolean, val graph: Graph, val effects: List<SessionEffect>
    )

    private class Fixture : AutoCloseable {
        val clock = GateClock()
        val repository = RecordingRepository()
        private val sessions = SessionGeneration()
        private val token = sessions.start()
        val grant = newGrant()
        private val errors = CopyOnWriteArrayList<Throwable>()
        private val ids = AtomicInteger()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val effects = Channel<SessionEffect>(Channel.UNLIMITED)
        private val pairs = mutableListOf<HelloPair>()
        val actor = SessionOrchestrator(
            repository, dispatcher = Dispatchers.Default, onError = errors::add,
            elapsedRealtime = clock::read,
            attemptIdFactory = { if (ids.getAndIncrement() == 0) PARENT_ID else ConnectionAttemptId.create() }
        )
        private val coordinator = SessionOrchestrator::class.java.getDeclaredField("signalingControl")
            .apply { isAccessible = true }.get(actor) as SignalingControlCoordinator

        init { scope.launch { actor.effects.collect(effects::send) } }

        fun newGrant() = PresenceConnectAdmission(sessions, token, PresenceConnectRequest(
            LOCAL_RUNTIME, REMOTE_DEVICE, REMOTE_RUNTIME, UUID.randomUUID().toString()
        ))

        suspend fun await(event: SessionEvent) = withTimeout(5_000L) { actor.dispatchAndAwait(event) }

        suspend fun adopt(admission: PresenceConnectAdmission = grant): ConnectionAttempt {
            if (actor.state.value == IntercomState.Offline) {
                assertTrue(await(SessionEvent.RuntimeStarted(LOCAL_RUNTIME)))
            }
            assertTrue(await(SessionEvent.ConnectPresenceRequested(
                LOCAL_RUNTIME, REMOTE_DEVICE, REMOTE_RUNTIME, setOf(Transport.LAN), admission = admission
            )))
            drainEffects()
            return requireNotNull(actor.currentAttempt)
        }

        suspend fun prepare(parent: ConnectionAttempt?, wireId: ConnectionAttemptId = CHILD_ID): HelloPair {
            val pair = HelloPair.establish(parent, wireId, clock.value.get())
            pairs += pair
            assertTrue(pair.local.peer.isVerifiedFor(TargetLock(REMOTE_DEVICE, REMOTE_RUNTIME)))
            assertEquals(RequestRole.RESPONDER, pair.local.requestRole)
            assertEquals(parent, pair.local.originatingAttempt)
            assertTrue(await(SessionEvent.ControlChannelVerified(LOCAL_RUNTIME, VerifiedControlChannel(
                pair.local.channel.channelId, Transport.LAN, pair.local.requestRole,
                pair.local.wireRequestKey, pair.local.targetLock, pair.local.peer, parent
            ))))
            return pair
        }

        fun request(pair: HelloPair): SessionEvent.IncomingConnectRequest {
            val envelope = pair.request()
            val message = envelope.message as SignalingMessageV2.ConnectRequest
            assertEquals(pair.local.wireRequestKey.attemptId, envelope.attemptId)
            return SessionEvent.IncomingConnectRequest(
                LOCAL_RUNTIME, pair.local.channel.channelId, pair.local.wireRequestKey,
                message.trigger, message.preferredTransportHint, clock.value.get()
            )
        }

        suspend fun selectOwner(pair: HelloPair): ConnectionAttempt {
            val select = drainEffects().filterIsInstance<SessionEffect.SelectMediaChannel>().single()
            assertTrue(await(SessionEvent.MediaChannelSelected(
                select.runtimeSessionId, select.attemptId, select.wireRequestKey,
                pair.local.channel.channelId, select.cohort
            )))
            val accept = drainEffects().filterIsInstance<SessionEffect.SendConnectAccept>().single()
            pair.accept()
            assertTrue(await(SessionEvent.SignalingMessageSent(
                accept.runtimeSessionId, accept.attemptId, accept.channelId, SignalingMessageTypeV2.CONNECT_ACCEPT
            )))
            val start = drainEffects().filterIsInstance<SessionEffect.StartWebRtc>().single()
            val owner = requireNotNull(actor.currentAttempt)
            val active = requireNotNull(actor.activeControlAttempt)
            assertEquals(owner, start.attempt)
            assertEquals(pair.local.channel.channelId, start.channelId)
            assertEquals(owner, active.attempt)
            assertEquals(pair.local.channel.channelId, active.mediaOwnerChannelId)
            assertEquals(SignalingAttemptPhase.ACCEPTED, active.phase)
            assertEquals(SignalingPhase.ACCEPTED, pair.local.phase)
            assertTrue(isCurrentMediaCandidate(owner, active, ConnectionCandidateContext(
                owner, pair.local.channel.channelId, pair.local.wireRequestKey, pair.local.targetLock,
                Transport.LAN, pair.local.requestRole, pair.local.peer
            )))
            return owner
        }

        fun connected(attempt: ConnectionAttempt) = SessionEvent.WebRtcStateChanged(
            LOCAL_RUNTIME, attempt.id, WebRtcConnectionState.CONNECTED, 1_000_000L
        )

        fun queue(event: SessionEvent, inspect: (Boolean) -> Unit = {}): CompletableFuture<Boolean> {
            val result = CompletableFuture<Boolean>()
            assertTrue(actor.dispatch(event) { accepted ->
                try { inspect(accepted); result.complete(accepted) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
            })
            return result
        }

        @Suppress("UNCHECKED_CAST")
        fun graph(pair: HelloPair): Graph {
            val channels = SignalingControlCoordinator::class.java.getDeclaredField("channels")
                .apply { isAccessible = true }.get(coordinator) as Map<ControlChannelId, VerifiedControlChannel>
            return Graph(actor.state.value, actor.currentAttempt, actor.activeControlAttempt, channels.toMap(),
                actor.terminalOutcome(PARENT_ID), actor.terminalOutcome(pair.local.wireRequestKey.attemptId))
        }

        fun drainEffects(): List<SessionEffect> = buildList {
            while (true) add(effects.tryReceive().getOrNull() ?: break)
        }

        override fun close() {
            clock.releaseAll()
            pairs.forEach(HelloPair::close)
            actor.close()
            scope.cancel()
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }

    private class GateClock {
        val value = AtomicLong(100L)
        private val next = AtomicReference<ReadGate?>()
        private val all = CopyOnWriteArrayList<ReadGate>()
        fun holdNext(readsUntilHold: Int = 1): ReadGate = ReadGate(readsUntilHold).also {
            check(next.compareAndSet(null, it))
            all += it
        }
        fun read(): Long {
            val gate = next.get()
            if (gate != null && gate.remaining.decrementAndGet() == 0 && next.compareAndSet(gate, null)) {
                gate.entered.countDown()
                check(gate.released.await(5, TimeUnit.SECONDS)) { "Actor clock gate timed out" }
            }
            return value.get()
        }
        fun releaseAll() { all.forEach(ReadGate::release) }
    }

    private class ReadGate(reads: Int) {
        val remaining = AtomicInteger(reads)
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        fun awaitEntered() { assertTrue("Actor did not reach the clock gate", entered.await(5, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }

    private class RecordingRepository : PairingRepository {
        val saved = CopyOnWriteArrayList<PairingRecord>()
        override fun observeAll(): Flow<List<PairingRecord>> = flowOf(emptyList())
        override suspend fun getAll(): List<PairingRecord> = saved.toList()
        override suspend fun getByDeviceId(deviceId: String): PairingRecord? =
            saved.lastOrNull { it.remoteDeviceId == deviceId }
        override suspend fun saveConnectedPeer(record: PairingRecord) { saved += record }
        override suspend fun setPreferred(deviceId: String) = false
        override suspend fun clearPreferred(deviceId: String) = false
        override suspend fun updateLastConnectedAt(deviceId: String, connectedAt: Long, transport: String?) = false
        override suspend fun incrementFailureCount(deviceId: String) = false
        override suspend fun clearFailureCount(deviceId: String) = false
        override suspend fun forget(deviceId: String) = false
    }

    private class HelloPair(
        val local: SignalingSessionV2, val remote: SignalingSessionV2,
        private val client: Socket, private val accepted: Socket
    ) : AutoCloseable {
        private val localFrames = LinkedBlockingQueue<SignalingEnvelopeV2>()
        private val remoteFrames = LinkedBlockingQueue<SignalingEnvelopeV2>()
        private val failures = LinkedBlockingQueue<Throwable>()
        init {
            local.startReader(localFrames::add, failures::add)
            remote.startReader(remoteFrames::add, failures::add)
        }
        fun request(): SignalingEnvelopeV2 {
            send(remote, SignalingMessageV2.ConnectRequest(RequestTrigger.USER, Transport.LAN))
            return requireNotNull(localFrames.poll(5, TimeUnit.SECONDS)) { failures.toString() }
        }
        fun accept() {
            send(local, SignalingMessageV2.ConnectAccept("local", "local phone"))
            assertTrue(requireNotNull(remoteFrames.poll(5, TimeUnit.SECONDS)).message is SignalingMessageV2.ConnectAccept)
            assertTrue(failures.toString(), failures.isEmpty())
        }
        private fun send(session: SignalingSessionV2, message: SignalingMessageV2) {
            val result = CompletableFuture<Result<Unit>>()
            session.send(message) { result.complete(it) }
            result.get(5, TimeUnit.SECONDS).getOrThrow()
        }
        override fun close() { local.close(); remote.close(); client.close(); accepted.close() }

        companion object {
            fun establish(parent: ConnectionAttempt?, wireId: ConnectionAttemptId, now: Long): HelloPair {
                val server = ServerSocket(0)
                val accepting = CompletableFuture.supplyAsync { server.accept() }
                val client = Socket("127.0.0.1", server.localPort)
                val accepted = try { accepting.get(5, TimeUnit.SECONDS) } finally { server.close() }
                val remoteAttempt = ConnectionAttempt(
                    wireId, REMOTE_RUNTIME, TargetLock(LOCAL_DEVICE, LOCAL_RUNTIME), ConnectionTrigger.USER,
                    ChannelPlan.single(Transport.LAN), now + 60_000L
                )
                val socketClock = MonotonicClock { MonotonicTimestamp(now) }
                val localFuture = CompletableFuture.supplyAsync {
                    SignalingSessionV2.establish(client, Transport.LAN, PhysicalSocketRole.OPENER,
                        now, LOCAL_DEVICE, LOCAL_RUNTIME, "local", "local phone", parent,
                        expectedRemoteTargetLock = TargetLock(REMOTE_DEVICE, REMOTE_RUNTIME),
                        monotonicClock = socketClock)
                }
                val remoteFuture = CompletableFuture.supplyAsync {
                    SignalingSessionV2.establish(accepted, Transport.LAN, PhysicalSocketRole.ACCEPTOR,
                        now, REMOTE_DEVICE, REMOTE_RUNTIME, "remote", "remote phone", remoteAttempt,
                        monotonicClock = socketClock)
                }
                return try {
                    HelloPair(localFuture.get(5, TimeUnit.SECONDS), remoteFuture.get(5, TimeUnit.SECONDS), client, accepted)
                } catch (failure: Throwable) {
                    client.close(); accepted.close()
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
        private val PARENT_ID = ConnectionAttemptId("20000000-0000-0000-0000-000000000002")
        private val CHILD_ID = ConnectionAttemptId("20000000-0000-0000-0000-000000000001")
    }
}
