package com.kuma.motointercom

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.webrtc.PeerConnection

/** Real HELLO/Service/IM/actor integration; only native media state input is injected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TransientLossServiceRobolectricTest {
    @Test fun actualSelectedEofKeepsProducerBudgetWhenMainDeliveryIsHeld() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val (engine, pair, connected) = connect(f)
            installWaitingLan(f, connected.runtimeSessionId)
            val media = engine.sessions.single()
            val original = field(f.service, "mainHandler") as Handler
            val held = java.util.concurrent.CountDownLatch(1)
            val heldCallback = java.util.concurrent.atomic.AtomicReference<Runnable>()
            val gate = java.util.concurrent.atomic.AtomicBoolean(true)
            setField(f.service, "mainHandler", object : Handler(Looper.getMainLooper()) {
                override fun sendMessageAtTime(msg: android.os.Message, uptimeMillis: Long): Boolean {
                    if (pair.responder.isClosed && msg.callback != null &&
                        Looper.myLooper() != Looper.getMainLooper() && gate.compareAndSet(true, false)) {
                        heldCallback.set(msg.callback); held.countDown(); return true
                    }
                    return super.sendMessageAtTime(msg, uptimeMillis)
                }
            })
            try {
                val firstAt = SystemClock.elapsedRealtime()
                pair.requester.close() // Actual TCP reader failure captures and records before this held Main post.
                assertTrue("Actual EOF producer did not reach held Main delivery", held.await(2, TimeUnit.SECONDS))
                assertEquals(firstAt, media.source.decideOwnerLoss { it?.occurredAtElapsedMs })
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500))
                assertEquals("Closed channel producer is recorded, while its Main event is still held", connected, f.actor.state.value)
                setField(f.service, "mainHandler", original)
                original.post(requireNotNull(heldCallback.get()))
                f.awaitMain { f.actor.state.value is IntercomState.Recovering && media.closed }
                val adopted = f.actor.state.value as IntercomState.Recovering
                assertEquals(firstAt + 10_000L, adopted.attempt.deadlineElapsedRealtimeMs)
                assertEquals(1, engine.sessions.size)
            } finally { setField(f.service, "mainHandler", original) }
        }
    }

    @Test fun actualManagerFailureFreezesOwnerLossBeforePostingMainAndCannotRenewAfterOffOn() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val (engine, _, _) = connect(f)
            val media = engine.sessions.single()
            val at = SystemClock.elapsedRealtime()
            CompletableFuture.runAsync { media.callbacks.onError(java.io.IOException("injected native media failure")) }
                .get(2, TimeUnit.SECONDS)
            val receipt = media.source.decideOwnerLoss { it }
            assertNotNull("IM must record on failure entry before Main delivery", receipt)
            assertEquals(at, receipt!!.occurredAtElapsedMs)
            assertNotNull(receipt.authorization)
            val policy = field(f.service, "automaticRecoveryPolicy") as AutomaticRecoveryPolicy
            policy.setEnabled(false); policy.setEnabled(true)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(500))
            f.awaitMain { f.actor.state.value is IntercomState.Discovering && media.closed }
            assertNull(f.actor.recoveryIntent)
            assertEquals(1, engine.sessions.size)
        }
    }

    @Test fun stopWhileActualOfferIsBufferedBeforeSendCommitCannotCreateMedia() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        IncomingConfirmationServiceFixture().use { f ->
            try {
                val engine = RecordingEngine()
                setField(f.service, "audioSessionController", AudioSessionController(engine, RecordingRoute()))
                val prompt = f.incoming()
                val pair = f.pairs.single()
                val entered = java.util.concurrent.CountDownLatch(1)
                val pause = java.util.concurrent.atomic.AtomicBoolean(true)
                setField(field(f.actor, "signalingControl")!!, "clock", MonotonicClock {
                    if (f.actor.activeControlAttempt?.phase == SignalingAttemptPhase.ACCEPTING && pause.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    }
                    MonotonicTimestamp(SystemClock.elapsedRealtime())
                })
                pair.requester.startReader({ envelope ->
                    if (envelope.message is SignalingMessageV2.ConnectAccept) pair.requester.send(
                        SignalingMessageV2.Offer("{\"type\":\"offer\",\"sdp\":\"v=0\"}"))
                }, {})
                f.service.respondToIncomingConfirmation(prompt, true)
                f.awaitMain { entered.count == 0L && (field(f.service, "pendingMediaMessages") as Map<*, *>).isNotEmpty() }
                assertTrue(engine.sessions.isEmpty())
                assertFalse(pair.responder.isClosed)
                f.stopRuntime()
                release.countDown()
                f.awaitMain { f.actor.state.value == IntercomState.Offline }
                assertTrue(engine.sessions.isEmpty())
                assertTrue(pair.responder.isClosed)
                assertTrue((field(f.service, "pendingMediaMessages") as Map<*, *>).isEmpty())
                assertNull(field(f.service, "intercomManager"))
            } finally { release.countDown() }
        }
    }

    @Test fun delayedNativeDeliveryPreservesActualSelectedSocketManagerAndOriginalConnectedAt() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val (engine, pair, connected) = connect(f)
            val manager = field(f.service, "intercomManager")
            // The original handshake deadline is long gone; accepted media remains its own owner.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            val media = engine.sessions.single()
            media.emit(PeerConnection.PeerConnectionState.DISCONNECTED)
            f.awaitMain { f.actor.connectedLossWindow != null }
            val window = requireNotNull(f.actor.connectedLossWindow)
            assertEquals(connected, f.actor.state.value)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(182))
            val healed = media.emit(PeerConnection.PeerConnectionState.CONNECTED, deliver = false)
            // Actual Service Handler timer runs before the held IM callback.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
            f.awaitMain { f.actor.connectedLossWindow == null }
            assertEquals(connected, f.actor.state.value)
            assertSame(manager, field(f.service, "intercomManager"))
            assertFalse(media.closed)
            assertFalse(pair.responder.isClosed)
            assertEquals(SignalingPhase.CONNECTED, pair.responder.phase)
            media.deliver(healed)
            f.awaitMain { field(f.service, "mediaConnected") == true }
            assertEquals(connected, f.actor.state.value)
            assertFalse(f.actor.dispatchAndAwait(SessionEvent.ConnectedLossObservationDue(window)))
            assertEquals(ConnectionAttemptTerminalOutcome.SUCCESS, f.actor.terminalOutcome(connected.attemptId))
            assertEquals(1, engine.sessions.size)
        }
    }

    @Test fun realSelectedSocketEofEndsObservationAndClosesExactlyThatMediaOwner() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val (engine, pair, connected) = connect(f)
            installWaitingLan(f, connected.runtimeSessionId)
            val media = engine.sessions.single()
            media.emit(PeerConnection.PeerConnectionState.DISCONNECTED)
            f.awaitMain { f.actor.connectedLossWindow != null }
            val firstLoss = requireNotNull(f.actor.connectedLossWindow!!.firstLoss.occurredAtElapsedMs)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(1_800))
            pair.requester.close() // Actual reader EOF, through Service's original effects collector.
            f.awaitMain { f.actor.state.value is IntercomState.Recovering && media.closed }
            val recovery = f.actor.state.value as IntercomState.Recovering
            assertEquals(firstLoss + 10_000, recovery.attempt.deadlineElapsedRealtimeMs)
            assertEquals(connected.attempt.targetLock, recovery.attempt.targetLock)
            assertTrue(pair.responder.isClosed)
            assertNull(f.actor.connectedLossWindow)
            assertNull(field(f.service, "intercomManager"))
            assertNull(media.source.capture(PeerConnection.PeerConnectionState.CONNECTED,
                SystemClock.elapsedRealtime(), 1L) { error("Retired native input") })
        }
    }

    @Test fun actualStopRevokesTimerAndQueuedMediaReceiptWithoutRestartingRuntime() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val (engine, pair, _) = connect(f)
            val media = engine.sessions.single()
            media.emit(PeerConnection.PeerConnectionState.DISCONNECTED)
            f.awaitMain { f.actor.connectedLossWindow != null }
            val late = media.emit(PeerConnection.PeerConnectionState.CONNECTED, deliver = false)
            f.stopRuntime()
            f.awaitMain { f.actor.state.value == IntercomState.Offline }
            media.deliver(late)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertEquals(IntercomState.Offline, f.actor.state.value)
            assertNull(f.actor.connectedLossWindow)
            assertTrue(media.closed)
            assertTrue(pair.responder.isClosed)
            assertNull(field(f.service, "intercomManager"))
        }
    }

    private suspend fun connect(f: IncomingConfirmationServiceFixture): Triple<RecordingEngine, IncomingConfirmationSocketPair, IntercomState.Connected> {
        val engine = RecordingEngine()
        setField(f.service, "audioSessionController", AudioSessionController(engine, RecordingRoute()))
        val prompt = f.incoming()
        val pair = f.pairs.single()
        val received = CopyOnWriteArrayList<SignalingMessageV2>()
        val failures = CopyOnWriteArrayList<Throwable>()
        pair.requester.startReader({ received += it.message }, { failures += it })
        f.service.respondToIncomingConfirmation(prompt, true)
        f.awaitMain { engine.sessions.size == 1 && received.any { it is SignalingMessageV2.ConnectAccept } }
        val sent = CompletableFuture<Throwable?>()
        pair.requester.send(SignalingMessageV2.Offer("{\"type\":\"offer\",\"sdp\":\"v=0\"}")) { sent.complete(it.exceptionOrNull()) }
        assertNull(sent.get(2, TimeUnit.SECONDS))
        f.awaitMain { received.any { it is SignalingMessageV2.Answer } }
        assertTrue(failures.toString(), failures.isEmpty())
        engine.sessions.single().emit(PeerConnection.PeerConnectionState.CONNECTED)
        f.awaitMain { f.actor.state.value is IntercomState.Connected && pair.responder.phase == SignalingPhase.CONNECTED }
        return Triple(engine, pair, f.actor.state.value as IntercomState.Connected)
    }

    private fun installWaitingLan(f: IncomingConfirmationServiceFixture, runtime: RuntimeSessionId) {
        val token = field(f.service, "activeSession") as SessionGeneration.Token
        val generations = field(f.service, "sessions") as SessionGeneration
        setField(f.service, "lanDiscovery", LanDiscoveryCoordinator(f.service, token,
            { generations.isCurrent(it) }, f.localDeviceId, runtime, "fixture", "fixture", 2,
            onDevicesChanged = {}, onControlChannelReady = { session, lease -> f.register(token, session, lease) },
            onLog = {}, onError = {}))
    }

    internal class RecordingRoute : RiderAudioRoute {
        override fun evidence() = AudioRouteEvidence(1, true)
        override fun select(selection: AudioRouteSelection) = Unit
        override fun close() = Unit
    }
    internal class RecordingEngine : RiderMediaEngine {
        val sessions = CopyOnWriteArrayList<RecordingSession>()
        override fun updateAudioControls(controls: VersionedAudioControls) = Unit
        override fun openSession(callbacks: RiderMediaSessionCallbacks, icePolicy: MediaIcePolicy) = RecordingSession(callbacks).also { sessions += it }
        override fun close() = Unit
    }
    internal class RecordingSession(val callbacks: RiderMediaSessionCallbacks) : RiderMediaSession {
        val source = NativeConnectionObservationSource(callbacks.controlIsOpen)
        @Volatile var closed = false
        fun emit(state: PeerConnection.PeerConnectionState, deliver: Boolean = true): NativeConnectionObservation {
            val receipt = CompletableFuture.supplyAsync {
                requireNotNull(source.capture(state, SystemClock.elapsedRealtime(), System.currentTimeMillis()) {
                    callbacks.freezeConnectionLoss?.invoke()
                })
            }.get(2, TimeUnit.SECONDS)
            if (deliver) deliver(receipt)
            return receipt
        }
        fun deliver(receipt: NativeConnectionObservation) {
            Handler(Looper.getMainLooper()).post { callbacks.onConnectionStateObserved!!.invoke(receipt) }
        }
        override fun createOffer() { callbacks.onLocalSdpGenerated("{\"type\":\"offer\",\"sdp\":\"v=0\"}") }
        override fun createAnswer(remoteSdpJson: String) { callbacks.onLocalSdpGenerated("{\"type\":\"answer\",\"sdp\":\"v=0\"}") }
        override fun setRemoteAnswer(remoteSdpJson: String) = Unit
        override fun addRemoteIceCandidate(candidateJson: String) = Unit
        override fun close() { closed = true; source.retire() }
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
}
