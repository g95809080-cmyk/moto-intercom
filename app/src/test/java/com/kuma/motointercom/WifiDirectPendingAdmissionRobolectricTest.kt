package com.kuma.motointercom

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiDirectPendingAdmissionRobolectricTest {
    @Test
    fun passiveHelloExpiresWhileMainIsPausedThenResumesDiscovery() {
        Harness().use { h ->
            val a = h.openGroup(1)
            assertEquals("GROUP_READY", state(h.tunnel))
            assertTrue(field(h.tunnel, "tunnelStarted") as Boolean)
            assertTrue(h.admitted.isEmpty())
            // Main has not consumed production postTransportReady.
            a.awaitExpiredFailureQueued()
            assertEquals(PendingSocketLease.Stage.CLOSED, a.lease.get().currentStage)
            assertTrue(a.lease.get().socket.isClosed)
            assertEquals("GROUP_READY", state(h.tunnel))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("DISCOVERING", state(h.tunnel))
            assertFalse(field(h.tunnel, "tunnelStarted") as Boolean)
            assertEquals(null, field(h.tunnel, "socketTransport"))
            assertTrue(h.admitted.isEmpty())
            assertTrue(h.callbackErrors.toString(), h.callbackErrors.isEmpty())
        }
    }

    @Test
    fun queuedOldExpirationCannotClearReplacementGroupOrTransferredSocket() {
        Harness().use { h ->
            val a = h.openGroup(1)
            a.awaitExpiredFailureQueued()
            // Both A's ready and failure are queued; neither has consumed Main.
            val b = h.openGroup(2)
            assertEquals("GROUP_READY", state(h.tunnel))
            assertSame(b.transport, field(h.tunnel, "socketTransport"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertTrue(field(h.tunnel, "tunnelStarted") as Boolean)
            assertEquals(2, field(h.tunnel, "socketTransportGeneration"))
            assertSame(b.transport, field(h.tunnel, "socketTransport"))
            assertEquals(PendingSocketLease.Stage.TRANSFERRED, b.lease.get().currentStage)
            assertEquals(1, h.admitted.size)
            val session = h.admitted.values.single()
            assertSame(b.lease.get().socket, field(session, "socket"))
            assertFalse(session.isClosed)
            assertFalse(b.lease.get().socket.isClosed)
            assertTrue(h.reportedErrors.toString(), h.reportedErrors.isEmpty())
            assertTrue(h.callbackErrors.toString(), h.callbackErrors.isEmpty())
        }
    }

    private class Harness : AutoCloseable {
        val admitted = linkedMapOf<ControlChannelId, SignalingSessionV2>()
        val reportedErrors = mutableListOf<Throwable>()
        val callbackErrors = ConcurrentLinkedQueue<Throwable>()
        private val rounds = mutableListOf<Round>()
        private val clock = MonotonicClock { MonotonicTimestamp(System.nanoTime() / 1_000_000L) }
        val tunnel = WifiDirectTunnel(
            context = ApplicationProvider.getApplicationContext<Context>(),
            onControlChannelReady = { session, lease ->
                // Install only through the real lease transfer.
                assertTrue(lease.tryTransfer({ true }) { admitted[session.channel.channelId] = session })
            },
            localDeviceId = DEVICE_A,
            localDeviceName = "Phone A",
            sessionId = RuntimeSessionId(SESSION_A),
            monotonicClock = clock,
            onError = reportedErrors::add
        )

        fun openGroup(generation: Int): Round {
            val port = ServerSocket(0, 1, LOOPBACK).use { it.localPort }
            val lease = AtomicReference<PendingSocketLease>()
            val helloQueued = CountDownLatch(1)
            val failureQueued = CountDownLatch(1)
            val transport = WifiDirectSignalingSocket(
                port = port,
                readyTimeoutMillis = 5_000L,
                connectTimeoutMillis = 500,
                retryDelayMillis = 10L,
                clock = clock,
                isSessionCurrent = {
                    field(tunnel, "running") == true &&
                        field(tunnel, "socketTransportGeneration") == generation &&
                        state(tunnel) == "GROUP_READY" && field(tunnel, "targetAttempt") == null
                },
                onReady = { _, role, pending ->
                    lease.set(pending)
                    try {
                        invoke(tunnel, "postTransportReady", generation, null,
                            TargetLock(DEVICE_B, RuntimeSessionId(SESSION_B)), role, pending)
                    } catch (t: Throwable) {
                        callbackErrors.add(t)
                        throw t
                    } finally {
                        helloQueued.countDown()
                    }
                },
                onFailure = { error ->
                    try {
                        invoke(tunnel, "postTransportFailure", generation, null, error)
                    } catch (t: Throwable) {
                        callbackErrors.add(t)
                    } finally {
                        failureQueued.countDown()
                    }
                }
            )
            val round = Round(transport, lease, failureQueued)
            rounds += round
            setField(tunnel, "running", true)
            setField(tunnel, "tunnelStarted", true)
            setField(tunnel, "socketTransportGeneration", generation)
            setField(tunnel, "socketTransport", transport)
            val stateField = tunnel.javaClass.getDeclaredField("state").apply { isAccessible = true }
            stateField.set(tunnel, requireNotNull(stateField.type.enumConstants).single {
                (it as Enum<*>).name == "GROUP_READY"
            })
            transport.startServer(LOOPBACK) { true }
            val socket = connectLoopback(port)
            round.peerSocket = socket
            val attempt = ConnectionAttempt(
                id = ConnectionAttemptId("30000000-0000-4000-8000-00000000000$generation"),
                runtimeSessionId = RuntimeSessionId(SESSION_B),
                targetLock = TargetLock(DEVICE_A, RuntimeSessionId(SESSION_A)),
                trigger = ConnectionTrigger.USER,
                channelPlan = ChannelPlan.single(Transport.WIFI_DIRECT),
                deadlineElapsedRealtimeMs = clock.now().elapsedRealtimeMs + 10_000L
            )
            round.peerSession = SignalingSessionV2.establish(
                socket = socket, transport = Transport.WIFI_DIRECT,
                physicalRole = PhysicalSocketRole.OPENER,
                openedAtElapsedMs = clock.now().elapsedRealtimeMs,
                localDeviceId = DEVICE_B, localRuntimeSessionId = RuntimeSessionId(SESSION_B),
                localNickname = "Rider B", localDeviceName = "Phone B",
                originatingAttempt = attempt, monotonicClock = clock
            )
            assertTrue(callbackErrors.toString(), helloQueued.await(2, TimeUnit.SECONDS))
            assertTrue(callbackErrors.toString(), callbackErrors.isEmpty())
            assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, lease.get().currentStage)
            return round
        }

        override fun close() {
            try { tunnel.close() } finally {
                rounds.forEach { it.close() }
                admitted.values.forEach { it.close() }
            }
        }
    }

    private class Round(
        val transport: WifiDirectSignalingSocket,
        val lease: AtomicReference<PendingSocketLease>,
        private val failureQueued: CountDownLatch
    ) : AutoCloseable {
        var peerSocket: Socket? = null
        var peerSession: SignalingSessionV2? = null
        fun awaitExpiredFailureQueued() {
            assertTrue(failureQueued.await(4, TimeUnit.SECONDS))
        }
        override fun close() {
            transport.close()
            peerSession?.close()
            peerSocket?.close()
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val SESSION_B = "20000000-0000-4000-8000-000000000002"
        fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun setField(owner: Any, name: String, value: Any?) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
        fun state(tunnel: WifiDirectTunnel): String = (field(tunnel, "state") as Enum<*>).name
        fun invoke(owner: Any, name: String, vararg args: Any?) {
            val method = owner.javaClass.declaredMethods.single {
                it.name == name && it.parameterCount == args.size
            }.apply { isAccessible = true }
            try { method.invoke(owner, *args) } catch (t: InvocationTargetException) { throw t.targetException }
        }
        fun connectLoopback(port: Int): Socket {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(LOOPBACK, port), 100)
                    return socket
                } catch (_: IOException) {
                    socket.close()
                    Thread.sleep(10)
                }
            }
            throw AssertionError("actual signaling server did not bind")
        }
    }
}
