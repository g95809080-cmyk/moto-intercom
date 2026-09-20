package com.kuma.motointercom.group

import com.kuma.motointercom.group.network.GroupWifiCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class GroupDescriptor(val room: GroupRoomKey, val host: GroupAuthEndpoint, val nickname: String) {
    init { require(room.hostRuntimeId == host.runtimeId) }
}
internal class GroupNetworkDescriptor(val credentials: GroupWifiCredentials, val hostAddress: String, val port: Int) {
    init {
        val octets = hostAddress.split('.')
        require(octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 && it.toInt().toString() == it })
        require(port in 1..65535)
    }
    override fun toString() = "GroupNetworkDescriptor(REDACTED)"
}

/** Shared bounded binary primitives; no object deserialization or DNS lookup from untrusted fields. */
internal object GroupBinary {
    fun encode(limit: Int, block: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(block)
        return buffer.toByteArray().also { require(it.size <= limit) }
    }
    fun <T> decode(bytes: ByteArray, limit: Int, block: DataInputStream.() -> T): T {
        require(bytes.size in 1..limit)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return input.block().also { require(input.available() == 0) }
    }
    fun DataOutputStream.uuid(id: String) {
        val uuid = UUID.fromString(id); require(uuid.toString() == id)
        writeLong(uuid.mostSignificantBits); writeLong(uuid.leastSignificantBits)
    }
    fun DataInputStream.uuid(): String = UUID(readLong(), readLong()).toString()
    fun DataOutputStream.bytes(value: ByteArray, max: Int) {
        require(value.size in 1..max); writeInt(value.size); write(value)
    }
    fun DataInputStream.bytes(max: Int): ByteArray {
        val length = readInt(); require(length in 1..max && length <= available())
        return ByteArray(length).also(::readFully)
    }
    fun DataOutputStream.text(value: String, max: Int) {
        require(value.length <= max)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        require(encoded.remaining() <= max)
        writeInt(encoded.remaining())
        val bytes = ByteArray(encoded.remaining()); encoded.get(bytes); write(bytes)
    }
    fun DataInputStream.text(max: Int): String {
        val length = readInt(); require(length in 0..max && length <= available())
        val bytes = ByteArray(length); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    fun DataOutputStream.endpoint(endpoint: GroupAuthEndpoint) { uuid(endpoint.deviceId); uuid(endpoint.runtimeId) }
    fun DataInputStream.endpoint() = GroupAuthEndpoint(uuid(), uuid())
    fun DataOutputStream.room(key: GroupRoomKey) { uuid(key.instanceId); uuid(key.hostRuntimeId) }
    fun DataInputStream.room() = GroupRoomKey(uuid(), uuid())
    fun DataOutputStream.context(value: GroupAuthContext) {
        room(value.room); endpoint(value.host); endpoint(value.client); uuid(value.handshakeId)
    }
    fun DataInputStream.context() = GroupAuthContext(room(), endpoint(), endpoint(), uuid())
}

internal sealed interface GroupBootstrapMessage {
    data object Describe : GroupBootstrapMessage
    class Description(val value: GroupDescriptor) : GroupBootstrapMessage
    class Begin(val context: GroupAuthContext, val round: ByteArray) : GroupBootstrapMessage
    class Round(val number: Int, val value: ByteArray) : GroupBootstrapMessage
    class PrivateRequest(val value: ByteArray) : GroupBootstrapMessage
    class PrivateResponse(val value: ByteArray) : GroupBootstrapMessage
}

internal object GroupBootstrapCodec {
    private const val MAGIC = 0x4d434742 // MCGB, no overlap with legacy or authenticated control.
    fun encode(message: GroupBootstrapMessage): ByteArray = with(GroupBinary) {
        encode(8192) {
            writeInt(MAGIC); writeByte(1)
            when (message) {
                GroupBootstrapMessage.Describe -> writeByte(1)
                is GroupBootstrapMessage.Description -> {
                    writeByte(2); room(message.value.room); endpoint(message.value.host); text(message.value.nickname, 64)
                }
                is GroupBootstrapMessage.Begin -> { writeByte(3); context(message.context); bytes(message.round, 4096) }
                is GroupBootstrapMessage.Round -> {
                    require(message.number in 1..3); writeByte(4); writeByte(message.number); bytes(message.value, 4096)
                }
                is GroupBootstrapMessage.PrivateRequest -> { writeByte(5); bytes(message.value, 1024) }
                is GroupBootstrapMessage.PrivateResponse -> { writeByte(6); bytes(message.value, 1024) }
            }
        }
    }
    fun decode(bytes: ByteArray): GroupBootstrapMessage = with(GroupBinary) {
        decode(bytes, 8192) {
            require(readInt() == MAGIC && readUnsignedByte() == 1)
            when (readUnsignedByte()) {
                1 -> GroupBootstrapMessage.Describe
                2 -> GroupBootstrapMessage.Description(GroupDescriptor(room(), endpoint(), text(64)))
                3 -> GroupBootstrapMessage.Begin(context(), bytes(4096))
                4 -> GroupBootstrapMessage.Round(readUnsignedByte().also { require(it in 1..3) }, bytes(4096))
                5 -> GroupBootstrapMessage.PrivateRequest(bytes(1024))
                6 -> GroupBootstrapMessage.PrivateResponse(bytes(1024))
                else -> error("Invalid bootstrap message")
            }
        }
    }
    fun network(value: GroupNetworkDescriptor): ByteArray = with(GroupBinary) {
        encode(256) { text(value.credentials.ssid, 32); text(value.credentials.passphrase, 63); text(value.hostAddress, 15); writeShort(value.port) }
    }
    fun network(bytes: ByteArray): GroupNetworkDescriptor = with(GroupBinary) {
        decode(bytes, 256) { GroupNetworkDescriptor(GroupWifiCredentials(text(32), text(63)), text(15), readUnsignedShort()) }
    }
}

/** One BLE peer. Call on a bounded worker, never on the Android callback looper. */
internal class GroupBootstrapHostSession(
    private val descriptor: GroupDescriptor,
    private val code: GroupJoinCode,
    private val network: () -> GroupNetworkDescriptor,
    private val mayDiscloseTo: (GroupAuthEndpoint) -> Boolean,
    private val nowMs: () -> Long,
    private val isCurrent: () -> Boolean
) : Closeable {
    private val started = nowMs()
    private var phase = 0
    private var auth: GroupAuthentication? = null
    private var channel: SecureGroupChannel? = null
    private var endpoint: GroupAuthEndpoint? = null
    private var nextRound: ByteArray? = null
    @Synchronized fun respond(bytes: ByteArray): ByteArray = try {
        requireCurrent()
        val message = GroupBootstrapCodec.decode(bytes)
        val response = when (phase) {
            0 -> {
                check(message === GroupBootstrapMessage.Describe); phase = 1
                GroupBootstrapMessage.Description(descriptor)
            }
            1 -> {
                check(message is GroupBootstrapMessage.Begin)
                check(message.context.room == descriptor.room && message.context.host == descriptor.host)
                endpoint = message.context.client
                val exchange = GroupAuthentication(message.context, GroupAuthRole.HOST, code, nowMs, isCurrent)
                auth = exchange
                val round1 = exchange.start()
                nextRound = checkNotNull(exchange.receive(message.round))
                phase = 2
                GroupBootstrapMessage.Round(1, round1)
            }
            2, 3 -> {
                check(message is GroupBootstrapMessage.Round && message.number == phase)
                val answer = checkNotNull(nextRound)
                nextRound = auth!!.receive(message.value)
                if (phase == 3) { channel = auth!!.takeChannel(); auth = null }
                val result = GroupBootstrapMessage.Round(phase, answer)
                phase++
                result
            }
            4 -> {
                check(message is GroupBootstrapMessage.PrivateRequest)
                val secure = checkNotNull(channel)
                val request = secure.decrypt(message.value)
                check(request.contentEquals(byteArrayOf(1)))
                check(mayDiscloseTo(checkNotNull(endpoint)))
                val plain = GroupBootstrapCodec.network(network())
                try { GroupBootstrapMessage.PrivateResponse(secure.encrypt(plain)) }
                finally { plain.fill(0); phase = 5; secure.close(); channel = null }
            }
            else -> error("Bootstrap already completed")
        }
        requireCurrent()
        GroupBootstrapCodec.encode(response)
    } catch (_: Exception) { close(); throw IOException("Room authentication failed") }
    private fun requireCurrent() {
        val now = nowMs()
        check(phase >= 0 && isCurrent() && now >= started && now - started < GroupAuthentication.TIMEOUT_MS)
    }
    @Synchronized override fun close() {
        phase = -1; auth?.close(); auth = null; channel?.close(); channel = null
        nextRound?.fill(0); nextRound = null; endpoint = null
    }
}

internal class GroupBootstrapMatch(val descriptor: GroupDescriptor, val network: GroupNetworkDescriptor) {
    override fun toString() = "GroupBootstrapMatch(REDACTED)"
}

/** Suspended transport exchanges do not occupy a thread; PAKE computation is dispatched by the caller. */
internal suspend fun authenticateGroupCandidate(
    endpoint: GroupAuthEndpoint,
    code: GroupJoinCode,
    nowMs: () -> Long,
    isCurrent: () -> Boolean,
    expectedRoom: GroupRoomKey? = null,
    exchange: suspend (ByteArray) -> ByteArray
): GroupBootstrapMatch {
    var auth: GroupAuthentication? = null
    var channel: SecureGroupChannel? = null
    val started = nowMs()
    fun current() { check(isCurrent() && nowMs() >= started && nowMs() - started < GroupAuthentication.TIMEOUT_MS) }
    suspend fun request(message: GroupBootstrapMessage): GroupBootstrapMessage {
        current()
        val bytes = exchange(GroupBootstrapCodec.encode(message))
        current()
        return GroupBootstrapCodec.decode(bytes)
    }
    try {
        val description = request(GroupBootstrapMessage.Describe) as? GroupBootstrapMessage.Description
            ?: error("Missing room descriptor")
        val descriptor = description.value
        check(expectedRoom == null || descriptor.room == expectedRoom)
        val context = GroupAuthContext(descriptor.room, descriptor.host, endpoint, UUID.randomUUID().toString())
        val peer = GroupAuthentication(context, GroupAuthRole.CLIENT, code, nowMs, isCurrent)
        auth = peer
        var response = request(GroupBootstrapMessage.Begin(context, peer.start()))
        for (round in 1..3) {
            check(response is GroupBootstrapMessage.Round && response.number == round)
            val outgoing = peer.receive(response.value)
            if (round < 3) response = request(GroupBootstrapMessage.Round(round + 1, checkNotNull(outgoing)))
            else check(outgoing == null)
        }
        channel = peer.takeChannel()
        val secret = request(GroupBootstrapMessage.PrivateRequest(channel.encrypt(byteArrayOf(1))))
        check(secret is GroupBootstrapMessage.PrivateResponse)
        val plain = channel.decrypt(secret.value)
        val network = try { GroupBootstrapCodec.network(plain) } finally { plain.fill(0) }
        current()
        return GroupBootstrapMatch(descriptor, network)
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (_: Exception) { throw IOException("Room authentication failed") }
    finally { auth?.close(); channel?.close() }
}
