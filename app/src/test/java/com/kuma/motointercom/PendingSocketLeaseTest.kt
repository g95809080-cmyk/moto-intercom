package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PendingSocketLeaseTest {
    @Test
    fun splitLengthPrefixAndTricklingPayloadShareOneAbsoluteFrameBudget() {
        listOf(true, false).forEach { splitPrefix ->
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { peer ->
                    server.accept().use { socket ->
                        val clock = MonotonicClock { MonotonicTimestamp(System.nanoTime() / 1_000_000L) }
                        val lease = PendingSocketLease(socket, null, clock.now().elapsedRealtimeMs + 3_000L, clock)
                        lease.armAdmissionDeadline()
                        lease.beginHello()
                        val guard = lease.newHelloFrameReadGuard()
                        val read = CompletableFuture.supplyAsync {
                            SignalingV2Framing.read(DataInputStream(socket.getInputStream()), guard)
                        }
                        val writer = CompletableFuture.runAsync {
                            runCatching {
                                val output = DataOutputStream(peer.getOutputStream())
                                if (splitPrefix) {
                                    output.writeByte(0); output.flush()
                                    Thread.sleep(400L)
                                    output.write(byteArrayOf(0, 0, 3)); output.flush()
                                    Thread.sleep(400L)
                                } else {
                                    output.writeInt(3); output.flush()
                                }
                                repeat(3) {
                                    output.writeByte(1); output.flush()
                                    Thread.sleep(if (splitPrefix) 200L else 550L)
                                }
                            }
                        }
                        try {
                            val failure = assertThrows(ExecutionException::class.java) { read.get(1_500L, TimeUnit.MILLISECONDS) }
                            assertTrue(failure.cause is IOException || failure.cause is SignalingV2Exception)
                        } finally {
                            lease.close()
                            peer.close()
                            writer.get(2, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun verifiedSocketRemainsOwnedUntilServiceAdmissionAndCloseCancelsIt() {
        verifiedPending().use { pair ->
            assertEquals(PendingSocketLease.Stage.ADMISSION_PENDING, pair.lease.currentStage)
            assertTrue(pair.lease.prepareAdmission({ true }))
            pair.lease.close()
            var installed = false
            assertFalse(pair.lease.tryTransfer({ true }) { installed = true })
            assertFalse(installed)
            assertTrue(pair.responder.isClosed)
        }
    }

    @Test
    fun transferredSocketSurvivesLateAdapterCloseAndExpiredWatchdog() {
        verifiedPending().use { pair ->
            var installed = 0
            var acknowledged = 0
            assertTrue(pair.lease.prepareAdmission({ true }, { acknowledged++ }))
            assertTrue(pair.lease.tryTransfer({ true }) { installed++ })
            pair.now.set(10_000L)
            pair.lease.close()
            assertFalse(pair.lease.tryTransfer({ true }) { installed++ })
            assertEquals(1, installed)
            assertEquals(1, acknowledged)
            assertEquals(PendingSocketLease.Stage.TRANSFERRED, pair.lease.currentStage)
            assertFalse(pair.responder.isClosed)
        }
    }

    @Test
    fun expiredAdmissionOrReplacedAdapterCannotInstallVerifiedSocket() {
        listOf(true, false).forEach { expired ->
            verifiedPending().use { pair ->
                var adapterCurrent = true
                assertTrue(pair.lease.prepareAdmission({ adapterCurrent }))
                if (expired) pair.now.set(3_000L) else adapterCurrent = false
                var installed = false
                assertFalse(pair.lease.tryTransfer({ true }) { installed = true })
                pair.lease.close()
                assertFalse(installed)
                assertTrue(pair.responder.isClosed)
            }
        }
    }

    @Test
    fun wholeHelloWatchdogClosesBlockedWriteWithoutAnySocketRead() {
        val socket = BlockingWriteSocket()
        val started = System.nanoTime() / 1_000_000L
        val clock = MonotonicClock { MonotonicTimestamp(System.nanoTime() / 1_000_000L) }
        val attempt = attempt().copy(deadlineElapsedRealtimeMs = started + 10_000L)
        val future = CompletableFuture.supplyAsync {
            establish(socket, DEVICE_A, SESSION_A, attempt, clock)
        }
        assertTrue(socket.entered.await(1, TimeUnit.SECONDS))
        val failure = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
        assertTrue(failure.cause is SignalingV2Exception)
        assertTrue(socket.isClosed)
        assertTrue(System.nanoTime() / 1_000_000L - started < 2_900L)
    }

    private fun verifiedPending(): PairFixture {
        val now = AtomicLong(0L)
        val clock = MonotonicClock { MonotonicTimestamp(now.get()) }
        val sockets = ServerSocket(0).use { server ->
            val opener = Socket("127.0.0.1", server.localPort)
            opener to server.accept()
        }
        val lease = PendingSocketLease(sockets.second, null, 3_000L, clock).also { it.armAdmissionDeadline() }
        val requesterFuture = CompletableFuture.supplyAsync {
            establish(sockets.first, DEVICE_A, SESSION_A, attempt(), clock)
        }
        return try {
            val responder = establish(sockets.second, DEVICE_B, SESSION_B, null, clock, lease)
            PairFixture(requesterFuture.get(2, TimeUnit.SECONDS), responder, lease, now)
        } catch (t: Throwable) {
            lease.close()
            sockets.first.close()
            sockets.second.close()
            throw t
        }
    }

    private fun establish(
        socket: Socket, device: String, runtime: String, attempt: ConnectionAttempt?,
        clock: MonotonicClock, lease: PendingSocketLease? = null
    ) = SignalingSessionV2.establish(
        socket, Transport.LAN, if (attempt == null) PhysicalSocketRole.ACCEPTOR else PhysicalSocketRole.OPENER,
        clock.now().elapsedRealtimeMs, device, RuntimeSessionId(runtime), "Rider", "Phone", attempt,
        monotonicClock = clock, pendingSocketLease = lease
    )

    private fun attempt() = ConnectionAttempt(
        ConnectionAttemptId("30000000-0000-4000-8000-000000000001"), RuntimeSessionId(SESSION_A),
        TargetLock(DEVICE_B, RuntimeSessionId(SESSION_B)), ConnectionTrigger.USER,
        ChannelPlan.single(Transport.LAN), 10_000L
    )

    private data class PairFixture(
        val requester: SignalingSessionV2, val responder: SignalingSessionV2,
        val lease: PendingSocketLease, val now: AtomicLong
    ) : AutoCloseable {
        override fun close() { lease.close(); requester.close(); responder.close() }
    }

    private class BlockingWriteSocket : Socket() {
        val entered = CountDownLatch(1)
        private val canceled = CountDownLatch(1)
        override fun isConnected() = true
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                entered.countDown()
                if (!canceled.await(4, TimeUnit.SECONDS)) throw IOException("write was not canceled")
                throw IOException("socket closed while writing")
            }
        }
        override fun close() { super.close(); canceled.countDown() }
    }

    private companion object {
        const val DEVICE_A = "a0000000-0000-4000-8000-000000000001"
        const val DEVICE_B = "b0000000-0000-4000-8000-000000000002"
        const val SESSION_A = "10000000-0000-4000-8000-000000000001"
        const val SESSION_B = "10000000-0000-4000-8000-000000000002"
    }
}
