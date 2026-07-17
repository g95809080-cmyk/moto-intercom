package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets

internal class SignalingProtocol(
    private val expectedRemoteSdp: SdpKind,
    identityAlreadySeen: Boolean = false
) {
    enum class SdpKind { OFFER, ANSWER }

    sealed interface Message {
        data class Identity(
            val name: String,
            val deviceId: String? = null,
            val runtimeSessionId: String? = null,
            val deviceName: String = ""
        ) : Message
        data class Offer(val sdpJson: String) : Message
        data class Answer(val sdpJson: String) : Message
        data class Candidate(val candidateJson: String) : Message
    }

    class ProtocolException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private var identitySeen = identityAlreadySeen
    private var sdpSeen = false
    private var candidateCount = 0
    private var encodedCandidateCount = 0

    @Synchronized
    fun decode(frame: ByteArray): Message {
        requireBytes("frame", frame.size, MAX_FRAME_BYTES)
        val root = try {
            JsonParser.parseString(String(frame, StandardCharsets.UTF_8)).asJsonObject
        } catch (t: Throwable) {
            throw ProtocolException("invalid signaling JSON", t)
        }
        val type = root.requiredString("type")
        return when (type) {
            "IDENTITY" -> decodeIdentity(root)
            "OFFER" -> decodeSdp(root, SdpKind.OFFER)
            "ANSWER" -> decodeSdp(root, SdpKind.ANSWER)
            "CANDIDATE" -> decodeCandidate(root)
            else -> throw ProtocolException("unknown signaling type: $type")
        }
    }

    @Synchronized
    fun encode(message: Message): ByteArray {
        val root = JsonObject()
        when (message) {
            is Message.Identity -> {
                val name = message.name.trim()
                if (name.isEmpty()) throw ProtocolException("identity is empty")
                if (name.codePointCount(0, name.length) > MAX_IDENTITY_CODE_POINTS) {
                    throw ProtocolException("identity is too long")
                }
                root.addProperty("type", "IDENTITY")
                root.addProperty("name", name)
                message.deviceId?.let {
                    root.addProperty(
                        "deviceId",
                        requireIdentityField("deviceId", it, MAX_DEVICE_ID_CODE_POINTS)
                    )
                }
                message.runtimeSessionId?.let {
                    root.addProperty(
                        "runtimeSessionId",
                        requireIdentityField(
                            "runtimeSessionId",
                            it,
                            MAX_RUNTIME_SESSION_ID_CODE_POINTS
                        )
                    )
                }
                message.deviceName.trim().takeIf(String::isNotEmpty)?.let {
                    root.addProperty(
                        "deviceName",
                        requireIdentityField(
                            "deviceName",
                            it,
                            MAX_DEVICE_NAME_CODE_POINTS
                        )
                    )
                }
            }
            is Message.Offer -> addSdp(root, "OFFER", message.sdpJson)
            is Message.Answer -> addSdp(root, "ANSWER", message.sdpJson)
            is Message.Candidate -> addCandidate(root, message.candidateJson)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            requireBytes("frame", it.size, MAX_FRAME_BYTES)
        }
    }

    private fun decodeIdentity(root: JsonObject): Message.Identity {
        if (identitySeen || sdpSeen) throw ProtocolException("identity out of order")
        val name = root.requiredString("name").trim()
        if (name.isEmpty()) throw ProtocolException("identity is empty")
        if (name.codePointCount(0, name.length) > MAX_IDENTITY_CODE_POINTS) {
            throw ProtocolException("identity is too long")
        }
        val deviceId = root.optionalIdentityField("deviceId", MAX_DEVICE_ID_CODE_POINTS)
        val runtimeSessionId = root.optionalIdentityField(
            "runtimeSessionId",
            MAX_RUNTIME_SESSION_ID_CODE_POINTS
        )
        val deviceName = root.optionalIdentityField(
            "deviceName",
            MAX_DEVICE_NAME_CODE_POINTS
        ).orEmpty()
        identitySeen = true
        return Message.Identity(name, deviceId, runtimeSessionId, deviceName)
    }

    private fun decodeSdp(root: JsonObject, kind: SdpKind): Message {
        if (!identitySeen || sdpSeen || kind != expectedRemoteSdp) {
            throw ProtocolException("unexpected $kind")
        }
        val raw = root.requiredPayload("sdp")
        requireBytes("sdp", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_SDP_BYTES)
        sdpSeen = true
        return if (kind == SdpKind.OFFER) Message.Offer(raw) else Message.Answer(raw)
    }

    private fun decodeCandidate(root: JsonObject): Message.Candidate {
        if (!identitySeen || !sdpSeen) throw ProtocolException("candidate out of order")
        if (++candidateCount > MAX_CANDIDATES) throw ProtocolException("too many candidates")
        val raw = root.requiredPayload("candidate")
        requireBytes("candidate", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_CANDIDATE_BYTES)
        return Message.Candidate(raw)
    }

    private fun addSdp(root: JsonObject, type: String, raw: String) {
        requireBytes("sdp", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_SDP_BYTES)
        root.addProperty("type", type)
        root.addProperty("sdp", raw)
    }

    private fun addCandidate(root: JsonObject, raw: String) {
        requireBytes("candidate", raw.toByteArray(StandardCharsets.UTF_8).size, MAX_CANDIDATE_BYTES)
        val candidate = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (t: Throwable) {
            throw ProtocolException("invalid candidate JSON", t)
        }
        if (++encodedCandidateCount > MAX_CANDIDATES) {
            throw ProtocolException("too many candidates")
        }
        root.addProperty("type", "CANDIDATE")
        root.add("candidate", candidate)
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString
            ?: throw ProtocolException("missing $key")

    private fun JsonObject.optionalIdentityField(key: String, maximumCodePoints: Int): String? {
        val value = get(key)?.takeUnless { it.isJsonNull } ?: return null
        return requireIdentityField(key, value.asString, maximumCodePoints)
    }

    private fun requireIdentityField(
        name: String,
        rawValue: String,
        maximumCodePoints: Int
    ): String {
        val value = rawValue.trim()
        if (value.isEmpty()) throw ProtocolException("$name is empty")
        if (value.codePointCount(0, value.length) > maximumCodePoints) {
            throw ProtocolException("$name is too long")
        }
        return value
    }

    private fun JsonObject.requiredPayload(key: String): String {
        val value = get(key) ?: throw ProtocolException("missing $key")
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else value.toString()
    }

    private fun requireBytes(name: String, actual: Int, maximum: Int) {
        if (actual !in 1..maximum) throw ProtocolException("$name bytes=$actual max=$maximum")
    }

    companion object {
        const val MAX_FRAME_BYTES = 128 * 1024
        const val MAX_SDP_BYTES = 64 * 1024
        const val MAX_CANDIDATE_BYTES = 4 * 1024
        const val MAX_CANDIDATES = 256
        const val MAX_IDENTITY_CODE_POINTS = 64
        const val MAX_DEVICE_ID_CODE_POINTS = 128
        const val MAX_RUNTIME_SESSION_ID_CODE_POINTS = 128
        const val MAX_DEVICE_NAME_CODE_POINTS = 128
    }
}

internal object LegacyIdentityHandshake {
    private const val READ_TIMEOUT_MS = 1_000

    fun exchange(
        socket: Socket,
        localIdentity: SignalingProtocol.Message.Identity,
        targetLock: TargetLock
    ): PeerIdentity {
        val previousTimeout = socket.soTimeout
        return try {
            requireCompleteLocalIdentity(localIdentity)
            socket.soTimeout = READ_TIMEOUT_MS
            val protocol = SignalingProtocol(SignalingProtocol.SdpKind.OFFER)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            writeFrame(output, protocol.encode(localIdentity))
            val remoteIdentity = protocol.decode(readFrame(input)) as? SignalingProtocol.Message.Identity
                ?: throw SignalingProtocol.ProtocolException("expected remote IDENTITY")
            resolveRemoteIdentity(
                message = remoteIdentity,
                expectedRemoteDeviceId = targetLock.targetDeviceId,
                requireClaimedDeviceId = true,
                expectedRemoteRuntimeSessionId = targetLock.expectedRemoteSessionId
            ).takeIf { it.isVerifiedFor(targetLock) }
                ?: throw SignalingProtocol.ProtocolException("remote IDENTITY is incomplete")
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw when (t) {
                is IOException -> t
                else -> SignalingProtocol.ProtocolException("identity exchange failed", t)
            }
        } finally {
            if (!socket.isClosed) runCatching { socket.soTimeout = previousTimeout }
        }
    }

    private fun requireCompleteLocalIdentity(identity: SignalingProtocol.Message.Identity) {
        if (identity.deviceId.isNullOrBlank() || identity.runtimeSessionId.isNullOrBlank()) {
            throw SignalingProtocol.ProtocolException("local IDENTITY is incomplete")
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: ByteArray) {
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length !in 1..SignalingProtocol.MAX_FRAME_BYTES) {
            throw SignalingProtocol.ProtocolException("invalid identity frame length: $length")
        }
        return ByteArray(length).also(input::readFully)
    }
}
