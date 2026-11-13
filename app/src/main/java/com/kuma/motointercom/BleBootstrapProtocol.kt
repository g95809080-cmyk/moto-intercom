package com.kuma.motointercom

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal object MotoComBleUuids {
    val SERVICE: UUID = UUID.fromString("0000c001-0000-1000-8000-00805f9b34fb")
    val RX_CHARACTERISTIC: UUID = UUID.fromString("0000c002-0000-1000-8000-00805f9b34fb")
    val TX_CHARACTERISTIC: UUID = UUID.fromString("0000c003-0000-1000-8000-00805f9b34fb")
}

internal enum class BleBootstrapMessageType {
    BOOTSTRAP_REQUEST,
    BOOTSTRAP_CAPABILITIES,
    HOTSPOT_READY,
    WIFI_JOIN_RESULT,
    ENDPOINT_READY,
    BOOTSTRAP_CANCEL;

    companion object {
        fun parse(raw: String): BleBootstrapMessageType =
            entries.firstOrNull { it.name == raw } ?:
                throw IllegalArgumentException("unsupported BLE bootstrap type: $raw")
    }
}

internal data class BleBootstrapMessage(
    val type: BleBootstrapMessageType,
    val requestId: String,
    val fields: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
    val protocolVersion: Int = SignalingV2Codec.PROTOCOL_VERSION
) {
    init {
        requireCanonicalUuid(requestId, "requestId")
        require(protocolVersion == SignalingV2Codec.PROTOCOL_VERSION) {
            "Unsupported BLE bootstrap protocol version: $protocolVersion"
        }
        require(fields.keys.all { it.isNotBlank() && it.length <= 64 }) {
            "BLE field name is invalid"
        }
        require(fields.values.all { it.length <= BleBootstrapCodec.MAX_FIELD_VALUE_CHARS }) {
            "BLE field value is too long"
        }
    }
}

internal object BleBootstrapCodec {
    const val MAX_MESSAGE_BYTES = 4096
    const val MAX_FIELD_VALUE_CHARS = 1024
    private val ROOT_KEYS = setOf("protocolVersion", "type", "requestId", "fields", "capabilities")

    fun encode(message: BleBootstrapMessage): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocolVersion", message.protocolVersion)
            addProperty("type", message.type.name)
            addProperty("requestId", message.requestId)
            add("fields", JsonObject().apply {
                message.fields.toSortedMap().forEach { (key, value) -> addProperty(key, value) }
            })
            add("capabilities", JsonArray().apply {
                message.capabilities.sorted().forEach(::add)
            })
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_MESSAGE_BYTES) { "BLE bootstrap message is too large" }
        }
    }

    fun decode(raw: ByteArray): BleBootstrapMessage {
        require(raw.size in 1..MAX_MESSAGE_BYTES) { "BLE bootstrap message size is invalid" }
        val root = try {
            JsonParser.parseString(String(raw, StandardCharsets.UTF_8)).asJsonObject
        } catch (t: Throwable) {
            throw IllegalArgumentException("invalid BLE bootstrap JSON", t)
        }
        val actualKeys = root.keySet()
        require(actualKeys == ROOT_KEYS) { "BLE bootstrap keys are not exact: $actualKeys" }
        val protocolVersion = root.get("protocolVersion").asInt
        require(protocolVersion == SignalingV2Codec.PROTOCOL_VERSION) {
            "Unsupported BLE bootstrap protocol version: $protocolVersion"
        }
        val fieldsObject = root.get("fields").asJsonObject
        val fields = linkedMapOf<String, String>()
        fieldsObject.entrySet().forEach { entry ->
            require(entry.key.isNotBlank() && entry.key.length <= 64) {
                "BLE field name is invalid"
            }
            require(entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString) {
                "BLE field must be a string: ${entry.key}"
            }
            val value = entry.value.asString
            require(value.length <= MAX_FIELD_VALUE_CHARS) { "BLE field value is too long" }
            fields[entry.key] = value
        }
        val capabilities = readCapabilities(root.get("capabilities"))
        return BleBootstrapMessage(
            type = BleBootstrapMessageType.parse(root.get("type").asString),
            requestId = root.get("requestId").asString.also {
                requireCanonicalUuid(it, "requestId")
            },
            fields = fields,
            capabilities = capabilities,
            protocolVersion = protocolVersion
        )
    }

    fun encodeAdvertisement(advertisement: CrossPlatformCapabilityAdvertisement): ByteArray {
        val root = JsonObject().apply {
            addProperty("app", advertisement.app)
            addProperty("protocolVersion", advertisement.protocolVersion)
            addProperty("platform", advertisement.platform.name)
            addProperty("deviceId", advertisement.deviceId)
            addProperty("sessionId", advertisement.sessionId)
            add("capabilities", JsonArray().apply {
                advertisement.capabilities.sorted().forEach(::add)
            })
            addProperty("networkRole", advertisement.networkRole.name)
            addProperty("tcpPort", advertisement.tcpPort)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_MESSAGE_BYTES) { "BLE advertisement is too large" }
        }
    }

    fun decodeAdvertisement(raw: ByteArray): CrossPlatformCapabilityAdvertisement {
        require(raw.size in 1..MAX_MESSAGE_BYTES) { "BLE advertisement size is invalid" }
        val root = JsonParser.parseString(String(raw, StandardCharsets.UTF_8)).asJsonObject
        val expected = setOf(
            "app",
            "protocolVersion",
            "platform",
            "deviceId",
            "sessionId",
            "capabilities",
            "networkRole",
            "tcpPort"
        )
        require(root.keySet() == expected) { "BLE advertisement keys are not exact" }
        val app = root.get("app").asString
        val version = root.get("protocolVersion").asInt
        val platform = PeerPlatform.parse(root.get("platform").asString)
        require(platform != PeerPlatform.UNKNOWN) { "Unknown platform is not usable" }
        return CrossPlatformCapabilityAdvertisement(
            app = app,
            protocolVersion = version,
            platform = platform,
            deviceId = root.get("deviceId").asString,
            sessionId = root.get("sessionId").asString,
            capabilities = readCapabilities(root.get("capabilities")),
            networkRole = BootstrapNetworkRole.parse(root.get("networkRole").asString),
            tcpPort = root.get("tcpPort").asInt
        )
    }

    private fun readCapabilities(value: com.google.gson.JsonElement): Set<String> {
        require(value.isJsonArray) { "capabilities must be an array" }
        val result = linkedSetOf<String>()
        value.asJsonArray.forEach { item ->
            require(item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                "capability must be a string"
            }
            require(result.add(item.asString)) { "duplicate capability" }
        }
        require(result.size <= 32) { "too many capabilities" }
        return result
    }
}

internal data class BleChunk(
    val messageId: Int,
    val index: Int,
    val total: Int,
    val messageBytes: Int,
    val payload: ByteArray
)

internal object BleChunkCodec {
    private const val HEADER_BYTES = 12
    private const val MAX_CHUNKS = 512

    fun encode(messageId: Int, message: ByteArray, maxPacketBytes: Int = 20): List<ByteArray> {
        require(message.isNotEmpty()) { "BLE message must not be empty" }
        require(maxPacketBytes > HEADER_BYTES) { "BLE packet size is too small" }
        val payloadBytes = maxPacketBytes - HEADER_BYTES
        val total = (message.size + payloadBytes - 1) / payloadBytes
        require(total in 1..MAX_CHUNKS) { "BLE message has too many chunks" }
        return (0 until total).map { index ->
            val start = index * payloadBytes
            val end = minOf(start + payloadBytes, message.size)
            ByteBuffer.allocate(HEADER_BYTES + end - start)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(messageId)
                .putShort(index.toShort())
                .putShort(total.toShort())
                .putInt(message.size)
                .put(message, start, end - start)
                .array()
        }
    }

    fun decode(packet: ByteArray): BleChunk {
        require(packet.size >= HEADER_BYTES) { "BLE chunk is too small" }
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val messageId = buffer.int
        val index = buffer.short.toInt()
        val total = buffer.short.toInt()
        val messageBytes = buffer.int
        require(index in 0 until total && total in 1..MAX_CHUNKS) {
            "BLE chunk index/total is invalid"
        }
        require(messageBytes in 1..BleBootstrapCodec.MAX_MESSAGE_BYTES) {
            "BLE message size is invalid"
        }
        return BleChunk(messageId, index, total, messageBytes, buffer.remainingBytes())
    }

    private fun ByteBuffer.remainingBytes(): ByteArray = ByteArray(remaining()).also(::get)
}

internal class BleMessageAssembler(
    private val nowElapsedRealtimeMs: () -> Long = {
        System.nanoTime() / 1_000_000L
    },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    init {
        require(timeoutMs > 0L) { "BLE assembler timeout must be positive" }
    }

    private data class Pending(
        val total: Int,
        val messageBytes: Int,
        val chunks: Array<ByteArray?>,
        var lastUpdatedElapsedRealtimeMs: Long
    )

    private val pending = linkedMapOf<Int, Pending>()

    @Synchronized
    fun add(packet: ByteArray): ByteArray? {
        expire(nowElapsedRealtimeMs())
        val chunk = BleChunkCodec.decode(packet)
        val now = nowElapsedRealtimeMs()
        val entry = pending.getOrPut(chunk.messageId) {
            Pending(
                total = chunk.total,
                messageBytes = chunk.messageBytes,
                chunks = arrayOfNulls(chunk.total),
                lastUpdatedElapsedRealtimeMs = now
            )
        }
        require(entry.total == chunk.total && entry.messageBytes == chunk.messageBytes) {
            "BLE chunk metadata changed within one message"
        }
        entry.lastUpdatedElapsedRealtimeMs = now
        entry.chunks[chunk.index] = chunk.payload
        if (entry.chunks.any { it == null }) return null
        pending.remove(chunk.messageId)
        val result = entry.chunks.filterNotNull().fold(ByteArray(0)) { acc, value -> acc + value }
        require(result.size == entry.messageBytes) { "BLE message length does not match chunks" }
        return result
    }

    @Synchronized
    fun expire(nowElapsedRealtimeMs: Long = this.nowElapsedRealtimeMs()): Boolean =
        pending.entries.removeAll { (_, entry) ->
            nowElapsedRealtimeMs - entry.lastUpdatedElapsedRealtimeMs >= timeoutMs
        }

    @Synchronized
    fun clear() = pending.clear()

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
