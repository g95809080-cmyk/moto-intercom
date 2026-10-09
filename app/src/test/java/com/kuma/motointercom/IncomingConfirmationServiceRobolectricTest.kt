package com.kuma.motointercom

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Modifier
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class IncomingConfirmationServiceRobolectricTest {
    @Test fun realHelloRequestPublishesAndRebindReplaysCurrentPrompt() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val prompt = f.incoming()
            assertTrue(prompt.peer.isDeviceIdVerified)
            assertNull(f.actor.currentAttempt)
            assertEquals(prompt, f.scheduledPrompt())
            assertEquals(listOf(prompt), f.shown)
            val replay = mutableListOf<IncomingConfirmationPrompt>()
            f.service.setListener(f.listener(replay))
            assertEquals(listOf(prompt), replay)
        }
    }

    @Test fun mismatchedTupleCannotPublishOrReplayOverActualPending() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val prompt = f.incoming()
            val invalid = listOf(
                prompt.copy(runtimeSessionId = RuntimeSessionId.create()),
                prompt.copy(attemptId = ConnectionAttemptId.create()),
                prompt.copy(channelId = ControlChannelId.create()),
                prompt.copy(actionNonce = "old-nonce"),
                prompt.copy(peer = prompt.peer.copy(isDeviceIdVerified = false)),
                prompt.copy(decisionDeadlineElapsedMs = prompt.decisionDeadlineElapsedMs + 1),
                prompt.copy(surface = ConfirmationSurface.NOTIFICATION)
            )
            f.shown.clear()
            for (stale in invalid) {
                f.publish(stale)
                assertEquals(prompt, f.activePrompt())
                assertEquals(prompt, f.scheduledPrompt())
                assertTrue(f.shown.isEmpty())
                f.cachePrompt(stale)
                val replay = mutableListOf<IncomingConfirmationPrompt>()
                f.service.setListener(f.listener(replay))
                assertTrue(replay.isEmpty())
                f.cachePrompt(prompt)
            }
            assertEquals(prompt.attemptId, f.actor.pendingInboundRequest?.attemptId)
        }
    }

    @Test fun closedSocketCannotReplayWhileTheActorIsStillWaiting() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val prompt = f.incoming()
            f.pairs.last().responder.close()
            assertEquals(PendingInboundPhase.WAITING_LOCAL_DECISION, f.actor.pendingInboundRequest?.phase)
            f.shown.clear()
            f.publish(prompt)
            assertTrue(f.shown.isEmpty())
            val replay = mutableListOf<IncomingConfirmationPrompt>()
            f.service.setListener(f.listener(replay))
            assertTrue(replay.isEmpty())
        }
    }

    @Test fun expiredPromptCannotReplayAndItsActualTimerStillTerminatesIt() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val prompt = f.incoming()
            ShadowSystemClock.advanceBy(Duration.ofMillis(
                (prompt.decisionDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            ))
            f.shown.clear()
            f.publish(prompt)
            assertTrue(f.shown.isEmpty())
            val replay = mutableListOf<IncomingConfirmationPrompt>()
            f.service.setListener(f.listener(replay))
            assertTrue(replay.isEmpty())
            f.awaitMain { f.actor.pendingInboundRequest?.phase != PendingInboundPhase.WAITING_LOCAL_DECISION }
            f.awaitMain { f.activePrompt() == null }
            assertTrue(f.canceled.contains(prompt.actionNonce))
        }
    }

    @Test fun queuedOldEffectCannotReplaceTheNewRuntimePromptOrTimer() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val old = f.incoming()
            val release = f.holdEffect(SessionEffect.PublishIncomingConfirmation(old))
            f.stopRuntime()
            val current = f.incoming()
            assertNotEquals(old.runtimeSessionId, current.runtimeSessionId)
            assertNotEquals(old.actionNonce, current.actionNonce)
            f.shown.clear()
            f.canceled.clear()
            release()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(current, f.activePrompt())
            assertEquals(current, f.scheduledPrompt())
            assertEquals(current.attemptId, f.actor.pendingInboundRequest?.attemptId)
            assertTrue(f.shown.isEmpty())
            assertTrue(f.canceled.isEmpty())
        }
    }

    @Test fun actualNotificationIsPublishedAndCannotReplayIntoTheActivity() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val prompt = f.incoming(foreground = false)
            assertEquals(ConfirmationSurface.NOTIFICATION, prompt.surface)
            assertEquals(prompt, f.scheduledPrompt())
            val manager = f.service.getSystemService(NotificationManager::class.java)
            val id = IntercomService::class.java.getDeclaredField("INCOMING_NOTIFICATION_ID")
                .apply { isAccessible = true }.getInt(null)
            assertNotNull(shadowOf(manager).getNotification(id))
            val replay = mutableListOf<IncomingConfirmationPrompt>()
            f.service.setListener(f.listener(replay))
            assertTrue(replay.isEmpty())
        }
    }
}

internal class IncomingConfirmationServiceFixture : AutoCloseable {
    private val controller = Robolectric.buildService(IntercomService::class.java).create()
    val service = controller.get()
    val actor = field("orchestrator").get(service) as SessionOrchestrator
    val shown = mutableListOf<IncomingConfirmationPrompt>()
    val canceled = mutableListOf<String>()
    val pairs = mutableListOf<IncomingConfirmationSocketPair>()
    private val heldEffects = mutableListOf<Runnable>()
    private val main = field("mainHandler").get(service) as Handler
    val localDeviceId = UUID.randomUUID().toString()

    init {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service.setListener(listener(shown))
    }

    fun listener(prompts: MutableList<IncomingConfirmationPrompt>) = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onLog(message: String) = Unit
        override fun onError(message: String) = Unit
        override fun onIncomingConfirmation(prompt: IncomingConfirmationPrompt) { prompts += prompt }
        override fun onIncomingConfirmationCanceled(actionNonce: String) { canceled += actionNonce }
    }

    suspend fun activateRuntime(
        runtime: RuntimeSessionId = RuntimeSessionId.create(), foreground: Boolean = true
    ): SessionGeneration.Token {
        val token = (field("sessions").get(service) as SessionGeneration).start()
        field("activeSession").set(service, token)
        field("activeRuntimeSessionId").set(service, runtime.value)
        field("localDeviceId").set(service, localDeviceId)
        field("running").setBoolean(service, true)
        assertTrue(actor.dispatchAndAwait(SessionEvent.RuntimeStarted(runtime)))
        service.setListener(listener(shown))
        service.setAppForeground(foreground)
        assertTrue(actor.dispatchAndAwait(SessionEvent.ConfirmationAvailabilityChanged(
            runtime, ConfirmationAvailability(foreground, !foreground)
        )))
        return token
    }

    fun register(token: SessionGeneration.Token, session: SignalingSessionV2, lease: PendingSocketLease) {
        IntercomService::class.java.declaredMethods.single {
            it.name.startsWith("registerControlChannel") &&
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 3
        }.apply { isAccessible = true }.invoke(service, token.value, session, lease)
    }

    suspend fun incoming(foreground: Boolean = true): IncomingConfirmationPrompt {
        val runtime = RuntimeSessionId.create()
        val token = activateRuntime(runtime, foreground)
        val pair = IncomingConfirmationSocketPair.establish(localDeviceId, runtime)
        pairs += pair
        register(token, pair.responder, pair.lease)
        awaitMain {
            (SignalingSessionV2::class.java.getDeclaredField("readerStarted")
                .apply { isAccessible = true }.get(pair.responder) as AtomicBoolean).get()
        }
        val sent = CompletableFuture<Throwable?>()
        pair.requester.send(SignalingMessageV2.ConnectRequest(RequestTrigger.USER, Transport.LAN)) {
            sent.complete(it.exceptionOrNull())
        }
        assertNull(sent.get(2, TimeUnit.SECONDS))
        awaitMain { activePrompt()?.runtimeSessionId == runtime && actor.pendingInboundRequest?.runtimeSessionId == runtime }
        val prompt = requireNotNull(activePrompt())
        val pending = requireNotNull(actor.pendingInboundRequest)
        assertEquals(PendingInboundPhase.WAITING_LOCAL_DECISION, pending.phase)
        assertEquals(pending.confirmationChannelId, prompt.channelId)
        assertEquals(pending.confirmationActionNonce, prompt.actionNonce)
        assertEquals(pending.decisionDeadlineAt.elapsedRealtimeMs, prompt.decisionDeadlineElapsedMs)
        assertEquals(pair.responder.peer, prompt.peer)
        assertEquals(SignalingPhase.AWAITING_LOCAL_DECISION, pair.responder.phase)
        assertEquals(IntercomState.IncomingConfirmation(runtime, prompt.attemptId, prompt.peer), actor.state.value)
        return prompt
    }

    fun stopRuntime() { service.onStartCommand(IntercomService.stopIntent(service), 0, 2) }
    fun activePrompt() = field("activeIncomingPrompt").get(service) as IncomingConfirmationPrompt?
    fun cachePrompt(prompt: IncomingConfirmationPrompt) { field("activeIncomingPrompt").set(service, prompt) }
    fun scheduledPrompt(): IncomingConfirmationPrompt? {
        val scheduler = field("incomingConfirmationScheduler").get(service)
        val scheduled = IncomingConfirmationDeadlineScheduler::class.java.getDeclaredField("scheduled")
            .apply { isAccessible = true }.get(scheduler) ?: return null
        return scheduled.javaClass.getDeclaredField("prompt").apply { isAccessible = true }
            .get(scheduled) as IncomingConfirmationPrompt
    }
    fun publish(prompt: IncomingConfirmationPrompt) {
        IntercomService::class.java.getDeclaredMethod("publishIncomingConfirmation", IncomingConfirmationPrompt::class.java)
            .apply { isAccessible = true }.invoke(service, prompt)
    }
    fun holdEffect(effect: SessionEffect): () -> Unit {
        val delivery = Runnable {
            IntercomService::class.java.getDeclaredMethod("handleSessionEffect", SessionEffect::class.java)
                .apply { isAccessible = true }.invoke(service, effect)
        }
        heldEffects += delivery
        main.postDelayed(delivery, 60_000L)
        return {
            main.removeCallbacks(delivery)
            heldEffects.remove(delivery)
            main.post(delivery)
            Unit
        }
    }
    suspend fun awaitMain(predicate: () -> Boolean) {
        withTimeout(3_000L) {
            while (!predicate()) { shadowOf(Looper.getMainLooper()).idle(); delay(5L) }
        }
    }
    private fun field(name: String) = IntercomService::class.java.getDeclaredField(name).apply { isAccessible = true }
    override fun close() {
        heldEffects.forEach(main::removeCallbacks)
        pairs.forEach { it.close() }
        val scope = field("serviceScope").get(service) as CoroutineScope
        val completed = CountDownLatch(1)
        scope.coroutineContext[Job]?.invokeOnCompletion { completed.countDown() } ?: completed.countDown()
        controller.destroy()
        assertTrue("Service scope did not stop", completed.await(5, TimeUnit.SECONDS))
        val instance = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (instance.get(null) as? PairingDatabase)?.close()
        instance.set(null, null)
    }
}

internal class IncomingConfirmationSocketPair private constructor(
    val requester: SignalingSessionV2, val responder: SignalingSessionV2,
    val lease: PendingSocketLease, private val client: Socket, private val accepted: Socket
) : AutoCloseable {
    override fun close() {
        lease.close(); requester.close(); responder.close()
        runCatching { client.close() }; runCatching { accepted.close() }
    }
    companion object {
        fun establish(localDeviceId: String, localRuntime: RuntimeSessionId): IncomingConfirmationSocketPair {
            val server = ServerSocket(0)
            val accepting = CompletableFuture.supplyAsync { server.accept() }
            val client = Socket("127.0.0.1", server.localPort)
            val accepted = try { accepting.get(2, TimeUnit.SECONDS) } finally { server.close() }
            val remoteDeviceId = UUID.randomUUID().toString()
            val remoteRuntime = RuntimeSessionId.create()
            val attempt = ConnectionAttempt(ConnectionAttemptId.create(), remoteRuntime,
                TargetLock(localDeviceId, localRuntime), ConnectionTrigger.USER,
                ChannelPlan.single(Transport.LAN), SystemClock.elapsedRealtime() + 60_000L)
            val clock = MonotonicClock { MonotonicTimestamp(SystemClock.elapsedRealtime()) }
            val lease = PendingSocketLease(accepted, null, SystemClock.elapsedRealtime() + 3_000L, clock)
                .also { it.armAdmissionDeadline() }
            val requester = CompletableFuture.supplyAsync {
                SignalingSessionV2.establish(client, Transport.LAN, PhysicalSocketRole.OPENER,
                    SystemClock.elapsedRealtime(), remoteDeviceId, remoteRuntime, "remote", "remote phone", attempt,
                    monotonicClock = clock)
            }
            val responder = CompletableFuture.supplyAsync {
                SignalingSessionV2.establish(accepted, Transport.LAN, PhysicalSocketRole.ACCEPTOR,
                    SystemClock.elapsedRealtime(), localDeviceId, localRuntime, "local", "local phone", null,
                    expectedRemoteTargetLock = TargetLock(remoteDeviceId, remoteRuntime), monotonicClock = clock,
                    pendingSocketLease = lease)
            }
            return try {
                val response = responder.get(2, TimeUnit.SECONDS)
                assertTrue(lease.prepareAdmission({ true }))
                IncomingConfirmationSocketPair(requester.get(2, TimeUnit.SECONDS), response, lease, client, accepted)
            } catch (failure: Throwable) {
                lease.close(); runCatching { client.close() }; runCatching { accepted.close() }
                requester.whenComplete { value, _ -> value?.close() }
                responder.whenComplete { value, _ -> value?.close() }
                throw failure
            }
        }
    }
}
