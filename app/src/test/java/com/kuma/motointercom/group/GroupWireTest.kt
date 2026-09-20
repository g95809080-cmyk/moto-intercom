package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID

class GroupWireTest {
    private fun id(n: Long) = UUID(0, n).toString()
    private val a = GroupMemberLease(id(1), id(11), 1, 1)
    private val b = GroupMemberLease(id(2), id(12), 2, 1)
    private val key = GroupRoomKey(id(21), id(11))
    private val link = GroupLinkLease(GroupPair.of(a.deviceId, b.deviceId), 1, 2, 1)
    private fun frame(msg: GroupMessage = GroupMessage.Leave) = GroupFrame(key, b, a, 1, msg)
    private fun reject(bytes: ByteArray) { assertThrows(GroupWireException::class.java) { GroupWire.decode(bytes) } }

    @Test fun everyTypeRoundTripsCanonicallyIncludingUnicode() {
        val messages = listOf(GroupMessage.Leave, GroupMessage.End, GroupMessage.Remove(id(3)),
            GroupMessage.Unblock(id(3)), GroupMessage.AudioAvailable(true), GroupMessage.AudioAvailable(false),
            GroupMessage.Offer(link, "v=0\r\n中文"), GroupMessage.Answer(link, "answer"),
            GroupMessage.Candidate(link, "音频", 0, "candidate"))
        for (message in messages) {
            val bytes = GroupWire.encode(frame(message))
            assertArrayEquals(bytes, GroupWire.encode(GroupWire.decode(bytes)))
        }
    }

    @Test fun rejectsEveryTruncationAndTrailingBytes() {
        val bytes = GroupWire.encode(frame(GroupMessage.Candidate(link, "audio", 3, "candidate")))
        for (size in 0 until bytes.size) reject(bytes.copyOf(size))
        reject(bytes + 0)
        reject(ByteArray(GroupWire.MAX_FRAME_BYTES + 1))
    }

    @Test fun rejectsUnknownMagicVersionTypeAndLegacyJson() {
        val bytes = GroupWire.encode(frame())
        for (offset in listOf(0, 4, 6)) reject(bytes.copyOf().also { it[offset] = 99 })
        reject("{\"protocolVersion\":2}".padEnd(200).toByteArray())
    }

    @Test fun rejectsInvalidNumbersFlagsAndSelfTarget() {
        val bytes = GroupWire.encode(frame())
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(135, 0) })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(71, -1) })
        reject(GroupWire.encode(frame(GroupMessage.AudioAvailable(true))).also { it[it.lastIndex] = 2 })
        assertThrows(GroupWireException::class.java) { GroupWire.encode(frame().copy(recipient = b)) }
    }

    @Test fun strictUtf8AndStringLimitsApplyToBothDirections() {
        val bytes = GroupWire.encode(frame(GroupMessage.Offer(link, "x")))
        reject(bytes.copyOf().also { it[it.lastIndex] = 0x80.toByte() })
        reject(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(it.size - 5, Int.MAX_VALUE) })
        assertThrows(GroupWireException::class.java) { GroupWire.encode(frame(GroupMessage.Offer(link, "x".repeat(49153)))) }
        assertThrows(GroupWireException::class.java) { GroupWire.encode(frame(GroupMessage.Offer(link, "\uD800"))) }
        assertThrows(GroupWireException::class.java) { GroupWire.encode(frame(GroupMessage.Offer(link, ""))) }
        assertNotNull(GroupWire.decode(GroupWire.encode(frame(GroupMessage.Offer(link, "x".repeat(49152))))))
    }

    @Test fun rejectsOversizedStreamBeforeReadingBodyAndPreservesNextFrame() {
        for (length in listOf(-1, 0, 142, 65537, Int.MAX_VALUE)) {
            val bytes = ByteBuffer.allocate(4).putInt(length).array()
            assertThrows(GroupWireException::class.java) { GroupWire.read(DataInputStream(ByteArrayInputStream(bytes))) }
        }
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        GroupWire.write(data, frame()); GroupWire.write(data, frame().copy(sequence = 2))
        val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1L, GroupWire.read(input).sequence)
        assertEquals(2L, GroupWire.read(input).sequence)
        assertEquals(0, input.available())
        assertThrows(GroupWireException::class.java) { GroupWire.read(input) }
    }

    @Test fun payloadIsNotIncludedInDiagnosticStrings() {
        val message = GroupMessage.Offer(link, "private-session-description")
        assertFalse(message.toString().contains("private-session-description"))
        assertFalse(frame(message).toString().contains("private-session-description"))
    }
}
