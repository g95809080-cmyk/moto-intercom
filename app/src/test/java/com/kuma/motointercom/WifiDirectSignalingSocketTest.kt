package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WifiDirectSignalingSocketTest {
    @Test
    fun closeDuringActualHelloReturnsWithoutWaitingForSocketRead() {
        val entered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val pending = AtomicReference<PendingSocketLease>()
        val transport = WifiDirectSignalingSocket(
            port, 5_000, 500, 10, { true },
            { _, role, lease ->
                pending.set(lease)
                entered.countDown()
                try {
                    SignalingSessionV2.establish(
                        lease.socket, Transport.WIFI_DIRECT, role, 0L, DEVICE_A,
                        RuntimeSessionId(SESSION_A), "A", "Phone A", null,
                        monotonicClock = MonotonicClock { MonotonicTimestamp(System.nanoTime() / 1_000_000L) },
                        pendingSocketLease = lease
                    ).close()
                } catch (_: SignalingV2Exception) {
                } finally {
                    ended.countDown()
                }
            }, { }
        )
        try {
            val loopback = InetAddress.getLoopbackAddress()
            transport.startServer(loopback) { true }
            assertTrue(waitUntil(1_000) { runCatching {
                Socket(loopback, port).also { peer = it }
            }.isSuccess })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val close = CompletableFuture.runAsync { transport.close() }
            close.get(300, TimeUnit.MILLISECONDS)
            assertTrue(ended.await(300, TimeUnit.MILLISECONDS))
            assertTrue(pending.get().socket.isClosed)
        } finally {
            transport.close()
            peer?.close()
            peer = null
        }
    }

    private var peer: Socket? = null

    @Test
    fun rejectedHelloClosesSocketAndRoutesCurrentGroupCleanup() {
        listOf(
            DEVICE_C to SESSION_C,
            DEVICE_B to SESSION_OLD
        ).forEach { (actualDeviceId, actualSessionId) ->
            ServerSocket(0).use { server ->
                val opener = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                val acceptor = server.accept()
                val failures = mutableListOf<Throwable>()
                var cleanupCalls = 0
                val established = CompletableFuture.supplyAsync {
                    establishWifiDirectSignalingSession(
                        socket = acceptor,
                        establish = {
                            SignalingSessionV2.establish(
                                socket = acceptor,
                                transport = Transport.WIFI_DIRECT,
                                physicalRole = PhysicalSocketRole.ACCEPTOR,
                                openedAtElapsedMs = 0L,
                                localDeviceId = DEVICE_A,
                                localRuntimeSessionId = RuntimeSessionId(SESSION_A),
                                localNickname = "Rider A",
                                localDeviceName = "Phone A",
                                originatingAttempt = null,
                                expectedRemoteTargetLock = TargetLock(
                                    DEVICE_B,
                                    RuntimeSessionId(SESSION_B)
                                ),
                                monotonicClock = MonotonicClock {
                                    MonotonicTimestamp(0L)
                                }
                            )
                        },
                        onFailure = {
                            failures += it
                            cleanupCalls += 1
                        }
                    )
                }
                try {
                    SignalingV2Framing.write(
                        DataOutputStream(opener.getOutputStream()),
                        rawHello(actualDeviceId, actualSessionId)
                    )

                    assertNull(established.get(2, TimeUnit.SECONDS))
                    assertTrue(acceptor.isClosed)
                    assertEquals(1, cleanupCalls)
                    assertTrue(failures.single() is SignalingV2Exception)
                } finally {
                    runCatching { opener.close() }
                    runCatching { acceptor.close() }
                }
            }
        }
    }

    @Test
    fun staleSessionClosesSocketWithoutReadyCallback() {
        var active = true
        var ready = false
        val failed = CountDownLatch(1)
        val probe = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val transport = WifiDirectSignalingSocket(
            port = probe.localPort,
            readyTimeoutMillis = 1_000,
            connectTimeoutMillis = 500,
            retryDelayMillis = 10,
            isSessionCurrent = { active },
            onReady = { _, _, socket -> ready = true; socket.close() },
            onFailure = { failed.countDown() }
        )
        probe.close()
        active = false

        transport.startClient(InetAddress.getLoopbackAddress(), InetAddress.getLoopbackAddress())
        failed.await(2, TimeUnit.SECONDS)
        transport.close()

        assertFalse(ready)
    }

    @Test
    fun serverHandsOffOneAllowedLoopbackPeer() {
        val ready = CountDownLatch(2)
        val serverRole = AtomicReference<PhysicalSocketRole>()
        val clientRole = AtomicReference<PhysicalSocketRole>()
        val port = ServerSocket(0).use { it.localPort }
        val server = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { true },
            { _, role, socket ->
                serverRole.set(role)
                ready.countDown()
                socket.close()
            },
            { }
        )
        val client = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { true },
            { _, role, socket ->
                clientRole.set(role)
                ready.countDown()
                socket.close()
            },
            { }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            server.startServer(loopback) { it.isLoopbackAddress }
            client.startClient(loopback, loopback)
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertTrue(serverRole.get() == PhysicalSocketRole.ACCEPTOR)
            assertTrue(clientRole.get() == PhysicalSocketRole.OPENER)
        } finally {
            try { server.close() } finally { client.close() }
        }
    }

    @Test
    fun closeSnapshotsPendingLeaseWhileActualReadyWorkerRemovesTheLastEntry() {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val lease = AtomicReference<PendingSocketLease>()
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(port, 5_000, 500, 10, { true },
            { _, _, pending ->
                lease.set(pending)
                ready.countDown()
                try {
                    check(release.await(2, TimeUnit.SECONDS))
                    pending.close()
                } finally { removed.countDown() }
            }, { })
        val registryField = WifiDirectSignalingSocket::class.java.getDeclaredField("pendingSockets").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val actualRegistry = registryField.get(transport) as MutableSet<PendingSocketLease>
        val interleave = AtomicBoolean(false)
        val registry = object : AbstractMutableSet<PendingSocketLease>() {
            override val size: Int get() {
                val observed = actualRegistry.size
                if (interleave.compareAndSet(true, false)) {
                    assertEquals(1, observed)
                    release.countDown()
                    assertTrue("Actual ready worker did not remove its lease", removed.await(2, TimeUnit.SECONDS))
                    assertTrue(actualRegistry.isEmpty())
                }
                return observed
            }
            override fun iterator() = actualRegistry.iterator()
            override fun add(element: PendingSocketLease) = actualRegistry.add(element)
            override fun remove(element: PendingSocketLease) = actualRegistry.remove(element)
        }
        registryField.set(transport, registry)
        val loopback = InetAddress.getLoopbackAddress()
        val io = WifiDirectSignalingSocket::class.java.getDeclaredField("io").run { isAccessible = true; get(transport) as ExecutorService }
        var listener: ServerSocket? = null
        var remote: Socket? = null
        try {
            transport.startServer(loopback) { true }
            assertTrue(waitUntil(1_000) { runCatching { Socket(loopback, port).also { remote = it } }.isSuccess })
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            @Suppress("UNCHECKED_CAST")
            val listenerRef = WifiDirectSignalingSocket::class.java.getDeclaredField("serverSocket").run {
                isAccessible = true; get(transport) as AtomicReference<ServerSocket?>
            }
            listener = listenerRef.get()!!
            interleave.set(true)
            transport.close()
            assertFalse(interleave.get())
            assertTrue(lease.get().socket.isClosed)
            assertTrue(listener.isClosed)
            assertTrue(actualRegistry.isEmpty())
            assertTrue(io.isShutdown)
            transport.close()
        } finally {
            release.countDown()
            try { transport.close() } finally {
                listener?.close(); remote?.close(); io.shutdownNow()
            }
        }
    }

    @Test
    fun closeUnblocksServerWithoutFailureCallback() {
        val failed = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port, 5_000, 500, 10, { true },
            { _, _, socket -> socket.close() },
            { failed.countDown() }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            transport.startServer(loopback) { false }
            assertTrue(waitUntil(1_000) { canConnect(loopback, port) })
            transport.close()
            assertFalse(failed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            transport.close()
        }
    }

    @Test
    fun sessionInvalidatedDuringAcceptDoesNotHandoff() {
        var active = true
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port, 2_000, 500, 10, { active },
            { _, _, socket -> ready.countDown(); socket.close() },
            { failed.countDown() }
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            transport.startServer(loopback) {
                active = false
                true
            }
            assertTrue(waitUntil(1_000) { canConnect(loopback, port) })
            assertFalse(ready.await(200, TimeUnit.MILLISECONDS))
            assertFalse(failed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            transport.close()
        }
    }

    @Test
    fun exactAttemptDeadlineClosesAcceptedSocketWithoutReadyOrFailure() {
        var now = 0L
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val attempt = ConnectionAttemptFixture.create(
            clock = FakeMonotonicClock(MonotonicTimestamp(0L)),
            preferredTransport = Transport.WIFI_DIRECT,
            timeoutMs = 100L
        )
        val port = ServerSocket(0).use { it.localPort }
        val transport = WifiDirectSignalingSocket(
            port = port,
            readyTimeoutMillis = 5_000L,
            connectTimeoutMillis = 500,
            retryDelayMillis = 10L,
            isSessionCurrent = { true },
            onReady = { _, _, socket -> ready.countDown(); socket.close() },
            onFailure = { failed.countDown() },
            clock = MonotonicClock { MonotonicTimestamp(now) },
            attemptContext = AttemptTaskContext(attempt, generation = 1)
        )
        val loopback = InetAddress.getLoopbackAddress()
        try {
            transport.startServer(loopback) {
                now = 100L
                true
            }
            assertTrue(waitUntil(1_000) { canConnect(loopback, port) })
            assertFalse(ready.await(200, TimeUnit.MILLISECONDS))
            assertFalse(failed.await(200, TimeUnit.MILLISECONDS))
        } finally {
            transport.close()
        }
    }

    private fun canConnect(address: InetAddress, port: Int): Boolean =
        runCatching { Socket(address, port).use { } }.isSuccess

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun rawHello(deviceId: String, sessionId: String): ByteArray = """
        {
          "protocolVersion": 2,
          "type": "HELLO",
          "attemptId": "$ATTEMPT_C",
          "sourceDeviceId": "$deviceId",
          "targetDeviceId": "$DEVICE_A",
          "sourceSessionId": "$sessionId",
          "payload": {
            "requestRole": "REQUESTER",
            "capabilities": []
          }
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private companion object {
        const val DEVICE_A = "11111111-1111-4111-8111-111111111111"
        const val DEVICE_B = "22222222-2222-4222-8222-222222222222"
        const val DEVICE_C = "33333333-3333-4333-8333-333333333333"
        const val SESSION_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val SESSION_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val SESSION_C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val SESSION_OLD = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val ATTEMPT_C = "dddddddd-1111-4111-8111-dddddddddddd"
    }
}
