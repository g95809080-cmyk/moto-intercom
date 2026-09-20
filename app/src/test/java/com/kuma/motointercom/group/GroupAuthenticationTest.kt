package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class GroupAuthenticationTest {
    private fun id(n: Long) = UUID(0, n).toString()
    private val context = GroupAuthContext(GroupRoomKey(id(1), id(2)),
        GroupAuthEndpoint(id(3), id(2)), GroupAuthEndpoint(id(4), id(5)), id(6))
    private var time = 0L
    private var current = true
    private fun auth(role: GroupAuthRole, code: String = "000042", ctx: GroupAuthContext = context) =
        GroupAuthentication(ctx, role, GroupJoinCode(code), { time }, { current })

    private fun confirm(host: GroupAuthentication, client: GroupAuthentication) {
        val h1 = host.start(); val c1 = client.start()
        val h2 = host.receive(c1)!!; val c2 = client.receive(h1)!!
        val h3 = host.receive(c2)!!; val c3 = client.receive(h2)!!
        assertNull(host.receive(c3)); assertNull(client.receive(h3))
    }
    private fun channels(): Pair<SecureGroupChannel, SecureGroupChannel> {
        val host = auth(GroupAuthRole.HOST); val client = auth(GroupAuthRole.CLIENT)
        confirm(host, client)
        return host.takeChannel() to client.takeChannel()
    }
    private fun rejected(action: () -> Unit) { assertThrows(GroupAuthException::class.java, action) }

    @Test fun sameCodeConfirmsAndEncryptsBothDirectionsWithDistinctKeys() {
        val (host, client) = channels()
        val plain = "四人离线对讲".toByteArray()
        val fromHost = host.encrypt(plain)
        val fromClient = client.encrypt(plain)
        assertFalse(fromHost.contentEquals(fromClient))
        assertArrayEquals(plain, client.decrypt(fromHost))
        assertArrayEquals(plain, host.decrypt(fromClient))
    }

    @Test fun wrongCodeFailsKeyConfirmationAndCannotExport() {
        val host = auth(GroupAuthRole.HOST); val client = auth(GroupAuthRole.CLIENT, "000043")
        val h1 = host.start(); val c1 = client.start()
        val h2 = host.receive(c1)!!; val c2 = client.receive(h1)!!
        val h3 = host.receive(c2)!!; val c3 = client.receive(h2)!!
        rejected { host.receive(c3) }; rejected { client.receive(h3) }
        rejected { host.takeChannel() }; rejected { client.takeChannel() }
    }

    @Test fun contextChangesAndReflectionAreRejectedInFirstProof() {
        val changes = listOf(context.copy(handshakeId = id(99)),
            context.copy(room = context.room.copy(instanceId = id(98))),
            context.copy(client = context.client.copy(runtimeId = id(97))))
        for (ctx in changes) {
            val host = auth(GroupAuthRole.HOST); val client = auth(GroupAuthRole.CLIENT, ctx = ctx)
            host.start()
            rejected { host.receive(client.start()) }
        }
        val host = auth(GroupAuthRole.HOST)
        val own = host.start()
        rejected { host.receive(own) }
    }

    @Test fun prematureExportWrongOrderAndRepeatedStartAreTerminal() {
        val premature = auth(GroupAuthRole.HOST)
        rejected { premature.takeChannel() }; rejected { premature.start() }
        val host = auth(GroupAuthRole.HOST)
        host.start(); rejected { host.start() }
        val notStarted = auth(GroupAuthRole.HOST)
        rejected { notStarted.receive(auth(GroupAuthRole.CLIENT).start()) }
    }

    @Test fun deadlinesCancellationAndClockRollbackCloseExchange() {
        val expired = auth(GroupAuthRole.HOST)
        time = GroupAuthentication.TIMEOUT_MS
        rejected { expired.start() }
        time = 0
        val cancelled = auth(GroupAuthRole.HOST)
        current = false
        rejected { cancelled.start() }
        current = true
        val rollback = auth(GroupAuthRole.HOST)
        time = -1
        rejected { rollback.start() }
    }

    @Test fun malformedBoundedPacketsNeverAdvanceExchange() {
        val client = auth(GroupAuthRole.CLIENT)
        val valid = client.start()
        val invalid = listOf(valid.copyOf(3), valid + 0,
            valid.copyOf().also { it[0] = 0 }, valid.copyOf().also { it[5] = 2 },
            valid.copyOf().also { it[6] = 0x7f }, ByteArray(4097))
        for (bytes in invalid) {
            val host = auth(GroupAuthRole.HOST); host.start()
            rejected { host.receive(bytes) }
            rejected { host.receive(valid) }
        }
    }

    @Test fun exportIsAtomicAndOneShot() {
        val host = auth(GroupAuthRole.HOST); val client = auth(GroupAuthRole.CLIENT)
        confirm(host, client)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(List(2) { Callable { runCatching { host.takeChannel() }.getOrNull() } }).map { it.get() }
            assertEquals(1, results.count { it != null })
            val channel = results.filterNotNull().single()
            assertArrayEquals(byteArrayOf(1), client.takeChannel().decrypt(channel.encrypt(byteArrayOf(1))))
        } finally { pool.shutdownNow() }
    }

    @Test fun maximumPayloadAndEmptyPayloadAreAcceptedWithoutTruncation() {
        val (host, client) = channels()
        val maximum = ByteArray(SecureGroupChannel.MAX_PLAIN_BYTES) { (it % 127).toByte() }
        val packet = host.encrypt(maximum)
        assertEquals(SecureGroupChannel.MAX_CIPHER_BYTES, packet.size)
        assertArrayEquals(maximum, client.decrypt(packet))
        assertArrayEquals(byteArrayOf(), client.decrypt(host.encrypt(byteArrayOf())))
        rejected { host.encrypt(ByteArray(SecureGroupChannel.MAX_PLAIN_BYTES + 1)) }
        rejected { host.encrypt(byteArrayOf(1)) }
    }

    @Test fun replayTamperingReflectionAndCrossSessionFailClosed() {
        val (host, client) = channels()
        val packet = host.encrypt(byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), client.decrypt(packet))
        rejected { client.decrypt(packet) }
        rejected { client.decrypt(host.encrypt(byteArrayOf(2))) }
        val (h2, c2) = channels()
        rejected { h2.decrypt(h2.encrypt(byteArrayOf(1))) }
        rejected { c2.decrypt(packet) }
        val (h3, c3) = channels()
        val tampered = h3.encrypt(byteArrayOf(1)).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejected { c3.decrypt(tampered) }
    }

    @Test fun concurrentEncryptionUsesDistinctIncreasingSequences() {
        val (host, client) = channels()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val packets = pool.invokeAll(List(16) { Callable { host.encrypt(byteArrayOf(9)) } }).map { it.get() }
                .sortedBy { java.nio.ByteBuffer.wrap(it).long }
            assertEquals((1L..16L).toList(), packets.map { java.nio.ByteBuffer.wrap(it).long })
            packets.forEach { assertArrayEquals(byteArrayOf(9), client.decrypt(it)) }
        } finally { pool.shutdownNow() }
        host.close(); rejected { host.encrypt(byteArrayOf(1)) }
    }
}
