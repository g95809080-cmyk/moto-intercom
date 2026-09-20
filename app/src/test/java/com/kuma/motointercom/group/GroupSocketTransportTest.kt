package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GroupSocketTransportTest {
    @Test fun admissionDeadlineClosesSocketEvenWhenNoReadLoopIsRunning() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val socket = listener.accept()
        listener.close()
        val clock = AtomicLong(0)
        val closed = CountDownLatch(1)
        val timers = Executors.newSingleThreadScheduledExecutor()
        val channel = GroupSocketChannel(socket, context(50), SecureGroupChannel(ByteArray(64) { it.toByte() }, ByteArray(32), GroupAuthRole.HOST),
            timers, { true }, { _, _ -> }, { closed.countDown() }, clock::get)
        try {
            channel.startTimers()
            clock.set(10_000)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertTrue(channel.isClosed)
            peer.soTimeout = 1_000
            assertEquals(-1, peer.getInputStream().read())
        } finally { channel.close(); peer.close(); timers.shutdownNow() }
    }
    private fun id(n: Long) = UUID(0, n).toString()
    private val descriptor = GroupDescriptor(GroupRoomKey(id(1), id(2)), GroupAuthEndpoint(id(3), id(2)), "host")
    private val code = GroupJoinCode("000042")
    private fun context(n: Long) = GroupAuthContext(descriptor.room, descriptor.host, GroupAuthEndpoint(id(n), id(n + 100)), UUID.randomUUID().toString())
    @Test fun threeRealLoopbackClientsAuthenticateAndReceiveOrderedEncryptedReplies() {
        val hostChannels = ConcurrentHashMap<String, GroupSocketChannel>()
        val clients = mutableListOf<GroupSocketClient>()
        val ready = CountDownLatch(3)
        val replies = CountDownLatch(9)
        val failures = AtomicInteger()
        val seen = ConcurrentHashMap<String, MutableList<Int>>()
        val host = GroupSocketHost(descriptor, code, InetAddress.getLoopbackAddress(), 0, { true }, {
            hostChannels[it.context.client.deviceId] = it
            it.markAdmitted()
        }, { channel, packet -> channel.send { packet.copyOf() } }, {}, { failures.incrementAndGet() })
        host.start()
        try {
            repeat(3) { index ->
                val ctx = context(index.toLong() + 10)
                val client = GroupSocketClient(ctx, code, InetSocketAddress(InetAddress.getLoopbackAddress(), host.localPort), {}, { true }, { channel ->
                    channel.markAdmitted()
                    repeat(3) { expected -> channel.send { sequence -> byteArrayOf(expected.toByte(), sequence.toByte()) } }
                    ready.countDown()
                }, { _, bytes ->
                    seen.getOrPut(ctx.client.deviceId) { mutableListOf() }.add(bytes[0].toInt())
                    assertEquals(bytes[0].toInt() + 1, bytes[1].toInt())
                    replies.countDown()
                }, {}, { failures.incrementAndGet() })
                clients += client; client.start()
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            assertTrue(replies.await(5, TimeUnit.SECONDS))
            assertEquals(3, hostChannels.size)
            assertEquals(0, failures.get())
            seen.values.forEach { assertEquals(listOf(0, 1, 2), it) }
        } finally { clients.forEach { it.close() }; host.close() }
        assertTrue(hostChannels.values.all { it.isClosed })
    }
    @Test fun wrongCodeNeverPublishesAuthenticatedChannelAndCancellationClosesAttempt() {
        val admitted = AtomicInteger()
        val failed = CountDownLatch(1)
        val host = GroupSocketHost(descriptor, code, InetAddress.getLoopbackAddress(), 0, { true }, { admitted.incrementAndGet() }, { _, _ -> }, {}, {})
        host.start()
        val client = GroupSocketClient(context(20), GroupJoinCode("000043"),
            InetSocketAddress(InetAddress.getLoopbackAddress(), host.localPort), {}, { true },
            { admitted.incrementAndGet() }, { _, _ -> }, {}, { failed.countDown() })
        try {
            client.start()
            assertTrue(failed.await(15, TimeUnit.SECONDS))
            assertEquals(0, admitted.get())
        } finally { client.close(); host.close() }
    }
    @Test fun wrongRoomIsRejectedBeforeCryptography() {
        val failed = CountDownLatch(1)
        val host = GroupSocketHost(descriptor, code, InetAddress.getLoopbackAddress(), 0, { true }, { fail("wrong room") }, { _, _ -> }, {}, {})
        host.start()
        val ctx = context(30).copy(room = GroupRoomKey(id(99), descriptor.host.runtimeId))
        val client = GroupSocketClient(ctx, code, InetSocketAddress(InetAddress.getLoopbackAddress(), host.localPort), {}, { true },
            { fail("wrong room") }, { _, _ -> }, {}, { failed.countDown() })
        try { client.start(); assertTrue(failed.await(5, TimeUnit.SECONDS)) }
        finally { client.close(); host.close() }
    }
    @Test fun frameLengthIsRejectedBeforePayloadAllocationAndHeaderIsStrict() {
        listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(127, -1, -1, -1), byteArrayOf(-1, -1, -1, -1)).forEach {
            assertThrows(Exception::class.java) { GroupSocketPackets.read(DataInputStream(ByteArrayInputStream(it)), 65560) }
        }
        val header = GroupSocketPackets.header(context(40))
        assertThrows(Exception::class.java) { GroupSocketPackets.header(header + 1) }
        assertThrows(Exception::class.java) { GroupSocketPackets.header(header.copyOf(10)) }
        assertEquals(context(40).room, GroupSocketPackets.header(header).room)
    }
}
