package com.kuma.motointercom

import android.os.Looper
import android.os.SystemClock
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LanPendingSocketServiceRobolectricTest {
    @Test fun closeSnapshotsPendingLeaseWhileActualHelloWorkerRemovesTheLastEntry() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val runtime = RuntimeSessionId.create()
            val token = f.activateRuntime(runtime)
            val release = CountDownLatch(1)
            val removed = CountDownLatch(1)
            val interleave = AtomicBoolean(false)
            AdapterFixture(f, token, runtime) { lease ->
                try { check(release.await(2, TimeUnit.SECONDS)); lease.close() }
                finally { removed.countDown() }
            }.use { a ->
                val registry = object : ConcurrentHashMap<PendingSocketLease, Long>() {
                    private fun observed(value: Long): Long {
                        if (interleave.compareAndSet(true, false)) {
                            assertEquals(1L, value)
                            release.countDown()
                            assertTrue("Actual HELLO worker did not release its lease", removed.await(2, TimeUnit.SECONDS))
                            assertTrue(isEmpty())
                        }
                        return value
                    }
                    override val size: Int get() = observed(super.size.toLong()).toInt()
                    override fun mappingCount(): Long = observed(super.mappingCount())
                }
                field(a.adapter, "pendingSockets").set(a.adapter, registry)
                a.connectPeer(null)
                assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, a.lease().currentStage)
                assertEquals(1, registry.size)
                @Suppress("UNCHECKED_CAST")
                val listener = (field(a.adapter, "serverSocket").get(a.adapter) as AtomicReference<ServerSocket?>).get()!!
                val executor = field(a.adapter, "executor").get(a.adapter) as ExecutorService
                try {
                    interleave.set(true)
                    a.adapter.close()
                    assertFalse(interleave.get())
                    assertTrue(a.session().isClosed)
                    assertTrue(listener.isClosed)
                    assertTrue(registry.isEmpty())
                    assertTrue(executor.isShutdown)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, registeredCount(f))
                    a.adapter.close()
                } finally {
                    release.countDown()
                    listener.close()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test fun stopClosesActualAcceptedHelloWaitingForServiceMainAdmission() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val runtime = RuntimeSessionId.create()
            val token = f.activateRuntime(runtime)
            AdapterFixture(f, token, runtime).use { a ->
                a.connectPeer(null)
                assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, a.lease().currentStage)
                assertEquals(1, a.pendingCount())
                f.stopRuntime()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, a.pendingCount())
                assertEquals(0, registeredCount(f))
                assertTrue(a.session().isClosed)
                f.awaitMain { f.actor.state.value == IntercomState.Offline }
                assertEquals(IntercomState.Offline, f.actor.state.value)
            }
        }
    }

    @Test fun serviceTransferKeepsActualSessionAliveAfterAdapterClose() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val runtime = RuntimeSessionId.create()
            val token = f.activateRuntime(runtime)
            AdapterFixture(f, token, runtime).use { a ->
                a.connectPeer(null)
                f.awaitMain { a.lease().currentStage == PendingSocketLease.Stage.TRANSFERRED }
                assertEquals(1, registeredCount(f))
                assertEquals(0, a.pendingCount())
                a.adapter.close()
                assertFalse(a.session().isClosed)
                assertEquals(1, registeredCount(f))
            }
        }
    }

    @Test fun recoveryRetryClosesQueuedOldHelloAndCannotInstallItIntoTheReplacementAttempt() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val runtime = RuntimeSessionId.create()
            val token = f.activateRuntime(runtime)
            AdapterFixture(f, token, runtime).use { a ->
                val first = a.outboundAttempt()
                assertTrue(f.actor.dispatchAndAwait(SessionEvent.ConnectRequested(first)))
                assertTrue(a.adapter.connect(first))
                a.connectPeer(first)
                val next = first.copy(id = ConnectionAttemptId.create(), trigger = ConnectionTrigger.RECOVERY)
                assertTrue(f.actor.dispatchAndAwait(SessionEvent.AttemptReplaced(next)))
                assertTrue(a.adapter.prepareRetry(next))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(next, f.actor.currentAttempt)
                assertEquals(0, registeredCount(f))
                assertTrue(a.session().isClosed)
                assertEquals(0, a.pendingCount())
                assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, f.actor.terminalOutcome(first.id))
            }
        }
    }

    @Test fun defaultLanProducerUsesTheServicesElapsedRealtimeDeadlineAtQueuedAdmission() = runBlocking {
        IncomingConfirmationServiceFixture().use { f ->
            val runtime = RuntimeSessionId.create()
            val token = f.activateRuntime(runtime)
            AdapterFixture(f, token, runtime).use { a ->
                val attempt = a.outboundAttempt()
                assertTrue(f.actor.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
                assertTrue(a.adapter.connect(attempt))
                a.connectPeer(attempt)
                assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, a.lease().currentStage)
                ShadowSystemClock.advanceBy(Duration.ofMillis(attempt.deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, registeredCount(f))
                assertTrue(a.session().isClosed)
                assertEquals(0, a.pendingCount())
            }
        }
    }

    private fun registeredCount(f: IncomingConfirmationServiceFixture): Int =
        (field(f.service, "signalingSessions").get(f.service) as Map<*, *>).size

    private class AdapterFixture(
        private val f: IncomingConfirmationServiceFixture, private val token: SessionGeneration.Token,
        private val runtime: RuntimeSessionId,
        private val afterReady: (PendingSocketLease) -> Unit = {}
    ) : AutoCloseable {
        private val remoteDevice = UUID.randomUUID().toString()
        private val remoteRuntime = RuntimeSessionId.create()
        private val ready = CountDownLatch(1)
        private val readySession = AtomicReference<SignalingSessionV2>()
        private val readyLease = AtomicReference<PendingSocketLease>()
        private var client: Socket? = null
        private var peer: SignalingSessionV2? = null
        private val generations = field(f.service, "sessions").get(f.service) as SessionGeneration
        val adapter = LanDiscoveryCoordinator(f.service, token, generations::isCurrent, f.localDeviceId,
            runtime, "local", "local phone", 2, {}, { session, lease ->
                readySession.set(session)
                readyLease.set(lease)
                f.register(token, session, lease)
                ready.countDown()
                afterReady(lease)
            }, {}, {})
        private val serverRun = CompletableFuture.runAsync {
            LanDiscoveryCoordinator::class.java.getDeclaredMethod("runLanTcpServer")
                .apply { isAccessible = true }.invoke(adapter)
        }

        init { field(f.service, "lanDiscovery").set(f.service, adapter) }

        fun outboundAttempt() = ConnectionAttempt(ConnectionAttemptId.create(), runtime,
            TargetLock(remoteDevice, remoteRuntime), ConnectionTrigger.USER,
            ChannelPlan.single(Transport.LAN), SystemClock.elapsedRealtime() + 10_000L)

        fun connectPeer(outbound: ConnectionAttempt?) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while ((field(adapter, "serverSocket").get(adapter) as AtomicReference<*>).get() == null) {
                check(System.nanoTime() < end) { "actual LAN accept server did not start" }
                Thread.sleep(5L)
            }
            val socket = Socket("127.0.0.1", 8890)
            client = socket
            val request = if (outbound == null) ConnectionAttempt(ConnectionAttemptId.create(), remoteRuntime,
                TargetLock(f.localDeviceId, runtime), ConnectionTrigger.USER, ChannelPlan.single(Transport.LAN),
                SystemClock.elapsedRealtime() + 10_000L) else null
            peer = SignalingSessionV2.establish(socket, Transport.LAN, PhysicalSocketRole.OPENER,
                SystemClock.elapsedRealtime(), remoteDevice, remoteRuntime, "remote", "remote phone", request,
                expectedRemoteTargetLock = TargetLock(f.localDeviceId, runtime),
                monotonicClock = MonotonicClock { MonotonicTimestamp(SystemClock.elapsedRealtime()) })
            assertTrue("actual adapter HELLO must queue Service admission", ready.await(1, TimeUnit.SECONDS))
        }

        fun lease(): PendingSocketLease = requireNotNull(readyLease.get())
        fun session(): SignalingSessionV2 = requireNotNull(readySession.get())
        fun pendingCount(): Int = (field(adapter, "pendingSockets").get(adapter) as Map<*, *>).size
        override fun close() {
            try { adapter.close() } finally {
                try { peer?.close() } finally {
                    client?.close()
                    serverRun.get(1, TimeUnit.SECONDS)
                }
            }
        }
    }

    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
}
