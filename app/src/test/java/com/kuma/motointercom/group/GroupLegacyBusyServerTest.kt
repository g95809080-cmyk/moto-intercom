package com.kuma.motointercom.group

import com.kuma.motointercom.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.lang.management.ManagementFactory

class GroupLegacyBusyServerTest {
    @Test fun realLegacyHelloRequestGetsBusyWithoutGroupOrMediaAdmission() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val server = GroupLegacyBusyServer(endpoint, "房主", emptyList())
        val listener = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val done = worker.submit { listener.accept().use(server::respond) }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 3_000
                val codec = SignalingV2Codec()
                val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                val hello = SignalingEnvelopeV2(attemptId = ConnectionAttemptId.create(), sourceDeviceId = DeviceId.parse(UUID.randomUUID().toString()),
                    targetDeviceId = DeviceId.parse(endpoint.deviceId), sourceSessionId = RuntimeSessionId.create(),
                    message = SignalingMessageV2.Hello(RequestRole.REQUESTER, "车友"))
                SignalingV2Framing.write(output, codec.encode(hello))
                val reply = codec.decode(SignalingV2Framing.read(input))
                assertEquals(RequestRole.RESPONDER, (reply.message as SignalingMessageV2.Hello).requestRole)
                SignalingV2Framing.write(output, codec.encode(hello.copy(message = SignalingMessageV2.ConnectRequest(RequestTrigger.USER))))
                val busy = codec.decode(SignalingV2Framing.read(input))
                assertTrue(busy.message is SignalingMessageV2.Busy)
                assertEquals(hello.attemptId, busy.attemptId)
                assertEquals(hello.sourceDeviceId, busy.targetDeviceId)
            }
            done.get(3, TimeUnit.SECONDS)
        } finally { server.close(); listener.close(); worker.shutdownNow() }
    }
    @Test fun groupOwnerRemainsUntilExactCleanupOwnerReleasesIt() {
        val token = GroupRuntimeOwnership.acquire()!!
        try {
            GroupRuntimeOwnership.release(Any())
            assertTrue(GroupRuntimeOwnership.hasOwner()); assertNull(GroupRuntimeOwnership.acquire())
        } finally { GroupRuntimeOwnership.release(token) }
        assertFalse(GroupRuntimeOwnership.hasOwner())
    }

    @Test fun realDualAcceptThreadsShareBudgetWithoutLosingEitherListener() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val time = AtomicLong(200)
        val calls = AtomicInteger()
        val firstClock = CountDownLatch(1)
        val secondClock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val secondThread = AtomicReference<Thread>()
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val server = GroupLegacyBusyServer(endpoint, "房主", listOf(0, 0)) {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.add(failure) }
            when (calls.incrementAndGet()) {
                1 -> {
                    firstThread.set(thread)
                    firstClock.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    100L
                }
                2 -> { secondThread.set(thread); secondClock.countDown(); 200L }
                else -> time.get()
            }
        }
        val clients = Executors.newFixedThreadPool(2)
        try {
            server.start()
            @Suppress("UNCHECKED_CAST")
            val listeners = server.javaClass.getDeclaredField("servers").apply { isAccessible = true }
                .get(server) as List<ServerSocket>
            val ports = listeners.map { it.localPort }
            val budget = server.javaClass.getDeclaredField("budget").apply { isAccessible = true }.get(server)
            val first = clients.submit(Callable { legacyExchange(ports[0], endpoint) })
            assertTrue(firstClock.await(5, TimeUnit.SECONDS))
            val second = clients.submit(Callable { legacyExchange(ports[1], endpoint) })
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var clockSerialized = false
            while (System.nanoTime() < deadline && secondClock.count != 0L) {
                val blocked = threads.getThreadInfo(threads.allThreadIds).filterNotNull().firstOrNull {
                    it.threadState == Thread.State.BLOCKED &&
                        it.lockInfo?.identityHashCode == System.identityHashCode(budget) &&
                        it.lockOwnerId == firstThread.get().id
                }
                if (blocked != null) { clockSerialized = true; break }
                Thread.yield()
            }
            // Old code must finish B's real response before A's older timestamp resumes.
            if (!clockSerialized) assertTrue(second.get(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS))
            assertTrue(second.get(5, TimeUnit.SECONDS))
            assertTrue(clockSerialized)
            assertNotSame(firstThread.get(), secondThread.get())
            assertTrue(firstThread.get().isAlive)
            assertTrue(secondThread.get().isAlive)
            assertTrue(uncaught.isEmpty())

            // Both ports count the same unauthenticated address: only one permit remains.
            assertTrue(legacyExchange(ports[0], endpoint))
            Socket("127.0.0.1", ports[1]).use { socket ->
                socket.soTimeout = 3_000
                assertEquals(-1, socket.getInputStream().read())
            }
            time.set(60_200)
            assertTrue(legacyExchange(ports[0], endpoint))
            assertTrue(legacyExchange(ports[1], endpoint))
            assertTrue(firstThread.get().isAlive)
            assertTrue(secondThread.get().isAlive)
            assertTrue(uncaught.isEmpty())
        } finally {
            releaseFirst.countDown()
            server.close()
            clients.shutdownNow()
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS))
            listOfNotNull(firstThread.get(), secondThread.get()).forEach { thread ->
                thread.join(3_000)
                assertFalse("accept thread must exit after server close", thread.isAlive)
            }
        }
    }

    private fun legacyExchange(port: Int, endpoint: GroupAuthEndpoint): Boolean =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val codec = SignalingV2Codec()
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val hello = SignalingEnvelopeV2(attemptId = ConnectionAttemptId.create(),
                sourceDeviceId = DeviceId.parse(UUID.randomUUID().toString()),
                targetDeviceId = DeviceId.parse(endpoint.deviceId), sourceSessionId = RuntimeSessionId.create(),
                message = SignalingMessageV2.Hello(RequestRole.REQUESTER, "车友"))
            SignalingV2Framing.write(output, codec.encode(hello))
            val reply = codec.decode(SignalingV2Framing.read(input))
            assertEquals(RequestRole.RESPONDER, (reply.message as SignalingMessageV2.Hello).requestRole)
            assertEquals(hello.attemptId, reply.attemptId)
            assertEquals(hello.sourceDeviceId, reply.targetDeviceId)
            SignalingV2Framing.write(output, codec.encode(hello.copy(
                message = SignalingMessageV2.ConnectRequest(RequestTrigger.USER))))
            val busy = codec.decode(SignalingV2Framing.read(input))
            assertTrue(busy.message is SignalingMessageV2.Busy)
            assertEquals(hello.attemptId, busy.attemptId)
            assertEquals(hello.sourceDeviceId, busy.targetDeviceId)
            assertEquals(-1, input.read())
            true
        }
}
