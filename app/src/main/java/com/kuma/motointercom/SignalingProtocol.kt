package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class SignalingProtocol(private val expectedRemoteSdp: SdpKind) {
    enum class SdpKind { OFFER, ANSWER }

    sealed interface Message {
        data class Identity(val name: String) : Message
        data class Offer(val sdpJson: String) : Message
        data class Answer(val sdpJson: String) : Message
        data class Candidate(val candidateJson: String) : Message
    }

    class ProtocolException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private var identitySeen = false
    private var sdpSeen = false
    private var candidateCount = 0

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

    fun encode(message: Message): ByteArray {
        val root = JsonObject()
        when (message) {
            is Message.Identity -> {
                root.addProperty("type", "IDENTITY")
                root.addProperty("name", message.name)
            }
            is Message.Offer -> addPayload(root, "OFFER", "sdp", message.sdpJson)
            is Message.Answer -> addPayload(root, "ANSWER", "sdp", message.sdpJson)
            is Message.Candidate -> addPayload(root, "CANDIDATE", "candidate", message.candidateJson)
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
        identitySeen = true
        return Message.Identity(name)
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

    private fun addPayload(root: JsonObject, type: String, key: String, raw: String) {
        root.addProperty("type", type)
        root.add(key, JsonParser.parseString(raw))
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.takeUnless { it.isJsonNull }?.asString
            ?: throw ProtocolException("missing $key")

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
    }
}
