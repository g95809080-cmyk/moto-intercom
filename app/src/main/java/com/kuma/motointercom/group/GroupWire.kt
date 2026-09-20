package com.kuma.motointercom.group

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Untrusted decoded data. No message constitutes an admission or authentication proof. */
internal sealed interface GroupMessage {
    data object Leave : GroupMessage
    data object End : GroupMessage
    data class Remove(val deviceId: String) : GroupMessage
    data class Unblock(val deviceId: String) : GroupMessage
    data class AudioAvailable(val available: Boolean) : GroupMessage
    class Offer(val link: GroupLinkLease, val sdp: String) : GroupMessage
    class Answer(val link: GroupLinkLease, val sdp: String) : GroupMessage
    class Candidate(val link: GroupLinkLease, val mid: String, val line: Int, val candidate: String) : GroupMessage
    class LinkConfirmed(val link: GroupLinkLease) : GroupMessage
    class RestartLink(val link: GroupLinkLease) : GroupMessage
}

internal data class GroupFrame(
    val room: GroupRoomKey,
    val sender: GroupMemberLease,
    val recipient: GroupMemberLease,
    val sequence: Long,
    val message: GroupMessage
) {
    override fun toString() = "GroupFrame(sequence=$sequence, payload=REDACTED)"
}

internal class GroupWireException : IOException("Invalid group control frame")

/** Big endian, fixed-layout v1. Stream ownership and read deadlines belong to the transport. */
internal object GroupWire {
    const val MAX_FRAME_BYTES = 65_536
    const val MIN_FRAME_BYTES = 143
    private const val MAGIC = 0x4d434750 // MCGP, separate from legacy signaling.
    private const val VERSION = 1
    private const val MAX_SDP = 49_152

    fun encode(frame: GroupFrame): ByteArray = checked {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(VERSION)
            out.writeByte(when (frame.message) {
                GroupMessage.Leave -> 1
                GroupMessage.End -> 2
                is GroupMessage.Remove -> 3
                is GroupMessage.Unblock -> 4
                is GroupMessage.AudioAvailable -> 5
                is GroupMessage.Offer -> 6
                is GroupMessage.Answer -> 7
                is GroupMessage.Candidate -> 8
                is GroupMessage.LinkConfirmed -> 9
                is GroupMessage.RestartLink -> 10
            })
            out.uuid(frame.room.instanceId)
            out.uuid(frame.room.hostRuntimeId)
            out.member(frame.sender)
            out.member(frame.recipient)
            require(frame.sender.deviceId != frame.recipient.deviceId)
            out.positive(frame.sequence)
            when (val msg = frame.message) {
                GroupMessage.Leave, GroupMessage.End -> Unit
                is GroupMessage.Remove -> out.uuid(msg.deviceId)
                is GroupMessage.Unblock -> out.uuid(msg.deviceId)
                is GroupMessage.AudioAvailable -> out.writeByte(if (msg.available) 1 else 0)
                is GroupMessage.Offer -> { out.link(msg.link); out.text(msg.sdp, MAX_SDP) }
                is GroupMessage.Answer -> { out.link(msg.link); out.text(msg.sdp, MAX_SDP) }
                is GroupMessage.Candidate -> {
                    out.link(msg.link)
                    out.text(msg.mid, 256, allowEmpty = true)
                    require(msg.line in 0..65535)
                    out.writeInt(msg.line)
                    out.text(msg.candidate, 4096)
                }
                is GroupMessage.LinkConfirmed -> out.link(msg.link)
                is GroupMessage.RestartLink -> out.link(msg.link)
            }
        }
        bytes.toByteArray().also { require(it.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES) }
    }

    fun decode(bytes: ByteArray): GroupFrame = checked {
        require(bytes.size in MIN_FRAME_BYTES..MAX_FRAME_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC && input.readUnsignedShort() == VERSION)
        val type = input.readUnsignedByte()
        require(type in 1..10)
        val room = GroupRoomKey(input.uuid(), input.uuid())
        val sender = input.member()
        val recipient = input.member()
        require(sender.deviceId != recipient.deviceId)
        val sequence = input.positive()
        val message = when (type) {
            1 -> GroupMessage.Leave
            2 -> GroupMessage.End
            3 -> GroupMessage.Remove(input.uuid())
            4 -> GroupMessage.Unblock(input.uuid())
            5 -> GroupMessage.AudioAvailable(input.readUnsignedByte().also { require(it <= 1) } == 1)
            6 -> GroupMessage.Offer(input.link(), input.text(MAX_SDP))
            7 -> GroupMessage.Answer(input.link(), input.text(MAX_SDP))
            8 -> GroupMessage.Candidate(input.link(), input.text(256, true),
                input.readInt().also { require(it in 0..65535) }, input.text(4096))
            9 -> GroupMessage.LinkConfirmed(input.link())
            else -> GroupMessage.RestartLink(input.link())
        }
        require(input.available() == 0)
        GroupFrame(room, sender, recipient, sequence, message)
    }

    fun read(input: DataInputStream): GroupFrame = checked {
        val length = input.readInt()
        require(length in MIN_FRAME_BYTES..MAX_FRAME_BYTES)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        decode(bytes)
    }

    fun write(output: DataOutputStream, frame: GroupFrame) {
        val bytes = encode(frame)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun DataOutputStream.uuid(value: String) {
        val uuid = UUID.fromString(value)
        require(uuid.toString() == value)
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong()).toString()
    private fun DataOutputStream.positive(value: Long) { require(value > 0); writeLong(value) }
    private fun DataInputStream.positive() = readLong().also { require(it > 0) }
    private fun DataOutputStream.member(value: GroupMemberLease) {
        uuid(value.deviceId); uuid(value.runtimeId); positive(value.incarnation); positive(value.controlGeneration)
    }
    private fun DataInputStream.member() = GroupMemberLease(uuid(), uuid(), positive(), positive())
    private fun DataOutputStream.link(value: GroupLinkLease) {
        uuid(value.pair.first); uuid(value.pair.second)
        positive(value.firstIncarnation); positive(value.secondIncarnation); positive(value.generation)
    }
    private fun DataInputStream.link() = GroupLinkLease(GroupPair(uuid(), uuid()), positive(), positive(), positive())
    private fun DataOutputStream.text(value: String, max: Int, allowEmpty: Boolean = false) {
        // Bound before encoding as well, so local callers cannot allocate an unbounded buffer.
        require(value.length <= max)
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(java.nio.CharBuffer.wrap(value))
        require(encoded.remaining() in (if (allowEmpty) 0 else 1)..max)
        writeInt(encoded.remaining())
        val bytes = ByteArray(encoded.remaining()); encoded.get(bytes); write(bytes)
    }
    private fun DataInputStream.text(max: Int, allowEmpty: Boolean = false): String {
        val length = readInt()
        require(length in (if (allowEmpty) 0 else 1)..max && length <= available())
        val bytes = ByteArray(length); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (_: IOException) {
        throw GroupWireException()
    } catch (_: IllegalArgumentException) { throw GroupWireException() }
}
