package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal enum class SignalingMessageTypeV2 {
    HELLO,
    CONNECT_REQUEST,
    CONNECT_ACCEPT,
    CONNECT_REJECT,
    BUSY,
    DISCONNECT,
    OFFER,
    ANSWER,
    CANDIDATE
}

internal sealed interface SignalingMessageV2 {
    val type: SignalingMessageTypeV2

    data class Hello(
        val requestRole: RequestRole,
        val nickname: String = "",
        val deviceName: String = ""
    ) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.HELLO
    }

    data class ConnectRequest(
        val preferredTransportHint: Transport? = null
    ) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.CONNECT_REQUEST
    }

    data object ConnectAccept : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.CONNECT_ACCEPT
    }

    data class ConnectReject(val reason: RejectReason) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.CONNECT_REJECT
    }

    data object Busy : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.BUSY
    }

    data object Disconnect : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.DISCONNECT
    }

    data class Offer(val sdpJson: String) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.OFFER
    }

    data class Answer(val sdpJson: String) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.ANSWER
    }

    data class Candidate(val candidateJson: String) : SignalingMessageV2 {
        override val type = SignalingMessageTypeV2.CANDIDATE
    }
}

internal data class SignalingEnvelopeV2(
    val protocolVersion: Int = SignalingV2Codec.PROTOCOL_VERSION,
    val attemptId: ConnectionAttemptId,
    val sourceDeviceId: DeviceId,
    val targetDeviceId: DeviceId,
    val sourceSessionId: RuntimeSessionId,
    val message: SignalingMessageV2
) {
    init {
        require(protocolVersion == SignalingV2Codec.PROTOCOL_VERSION) {
            "Unsupported signaling protocol version: $protocolVersion"
        }
        requireCanonicalUuid(attemptId.value, "attemptId")
        requireCanonicalUuid(sourceSessionId.value, "sourceSessionId")
        require(sourceDeviceId != targetDeviceId) { "Source and target device IDs must differ" }
    }

    fun requesterKey(): WireRequestKey {
        val requesterFrame = message is SignalingMessageV2.ConnectRequest ||
            (message is SignalingMessageV2.Hello && message.requestRole == RequestRole.REQUESTER)
        require(requesterFrame) { "Only requester HELLO or CONNECT_REQUEST defines a request key" }
        return WireRequestKey(
            requesterDeviceId = sourceDeviceId,
            requesterSessionId = sourceSessionId,
            attemptId = attemptId,
            responderDeviceId = targetDeviceId
        )
    }
}

internal fun SignalingMessageV2.responseScopeOrNull(): ResponseScope? = when (this) {
    SignalingMessageV2.ConnectAccept -> ResponseScope.ATTEMPT
    is SignalingMessageV2.ConnectReject -> reason.scope
    SignalingMessageV2.Busy -> ResponseScope.ATTEMPT
    else -> null
}

internal class SignalingV2Codec {
    private var decodedCandidateCount = 0
    private var encodedCandidateCount = 0

    @Synchronized
    fun encode(envelope: SignalingEnvelopeV2): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocolVersion", envelope.protocolVersion)
            addProperty("type", envelope.message.type.name)
            addProperty("attemptId", envelope.attemptId.value)
            addProperty("sourceDeviceId", envelope.sourceDeviceId.value)
            addProperty("targetDeviceId", envelope.targetDeviceId.value)
            addProperty("sourceSessionId", envelope.sourceSessionId.value)
            add("payload", encodePayload(envelope.message))
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8).also {
            requireBytes("frame", it.size, MAX_FRAME_BYTES)
        }
    }

    @Synchronized
    fun decode(frame: ByteArray): SignalingEnvelopeV2 {
        requireBytes("frame", frame.size, MAX_FRAME_BYTES)
        try {
            val root = JsonParser.parseString(
                String(frame, StandardCharsets.UTF_8)
            ).asJsonObject
            root.requireExactKeys(ENVELOPE_KEYS)
            val protocolVersion = root.requiredInt("protocolVersion")
            if (protocolVersion != PROTOCOL_VERSION) {
                throw SignalingV2Exception("unsupported protocolVersion: $protocolVersion")
            }
            val type = root.requiredEnum<SignalingMessageTypeV2>("type")
            val attemptId = ConnectionAttemptId(
                canonicalField(root.requiredString("attemptId"), "attemptId")
            )
            val sourceDeviceId = DeviceId.parse(root.requiredString("sourceDeviceId"))
            val targetDeviceId = DeviceId.parse(root.requiredString("targetDeviceId"))
            val sourceSessionId = RuntimeSessionId(
                canonicalField(root.requiredString("sourceSessionId"), "sourceSessionId")
            )
            val payload = root.requiredObject("payload")
            return SignalingEnvelopeV2(
                protocolVersion = protocolVersion,
                attemptId = attemptId,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDeviceId,
                sourceSessionId = sourceSessionId,
                message = decodePayload(type, payload)
            )
        } catch (t: Throwable) {
            throw when (t) {
                is SignalingV2Exception -> t
                else -> SignalingV2Exception("invalid signaling v2 frame", t)
            }
        }
    }

    private fun encodePayload(message: SignalingMessageV2): JsonObject = JsonObject().apply {
        when (message) {
            is SignalingMessageV2.Hello -> {
                addProperty("requestRole", message.requestRole.name)
                message.nickname.trim().takeIf(String::isNotEmpty)?.let {
                    addProperty("nickname", boundedText("nickname", it, MAX_NICKNAME_CODE_POINTS))
                }
                message.deviceName.trim().takeIf(String::isNotEmpty)?.let {
                    addProperty(
                        "deviceName",
                        boundedText("deviceName", it, MAX_DEVICE_NAME_CODE_POINTS)
                    )
                }
            }
            is SignalingMessageV2.ConnectRequest ->
                message.preferredTransportHint?.let {
                    addProperty("preferredTransportHint", it.name)
                }
            SignalingMessageV2.ConnectAccept,
            SignalingMessageV2.Busy,
            SignalingMessageV2.Disconnect -> Unit
            is SignalingMessageV2.ConnectReject -> addProperty("reason", message.reason.name)
            is SignalingMessageV2.Offer -> addProperty(
                "sdp",
                boundedPayload("sdp", message.sdpJson, MAX_SDP_BYTES)
            )
            is SignalingMessageV2.Answer -> addProperty(
                "sdp",
                boundedPayload("sdp", message.sdpJson, MAX_SDP_BYTES)
            )
            is SignalingMessageV2.Candidate -> {
                val candidate = parseJsonObject(
                    "candidate",
                    boundedPayload("candidate", message.candidateJson, MAX_CANDIDATE_BYTES)
                )
                if (++encodedCandidateCount > MAX_CANDIDATES) {
                    throw SignalingV2Exception("too many encoded candidates")
                }
                add("candidate", candidate)
            }
        }
    }

    private fun decodePayload(
        type: SignalingMessageTypeV2,
        payload: JsonObject
    ): SignalingMessageV2 = when (type) {
        SignalingMessageTypeV2.HELLO -> {
            payload.requireKeys(setOf("requestRole"), setOf("requestRole", "nickname", "deviceName"))
            SignalingMessageV2.Hello(
                requestRole = payload.requiredEnum("requestRole"),
                nickname = payload.optionalBoundedText(
                    "nickname",
                    MAX_NICKNAME_CODE_POINTS
                ),
                deviceName = payload.optionalBoundedText(
                    "deviceName",
                    MAX_DEVICE_NAME_CODE_POINTS
                )
            )
        }
        SignalingMessageTypeV2.CONNECT_REQUEST -> {
            payload.requireKeys(emptySet(), setOf("preferredTransportHint"))
            SignalingMessageV2.ConnectRequest(
                preferredTransportHint = payload.optionalEnum<Transport>("preferredTransportHint")
            )
        }
        SignalingMessageTypeV2.CONNECT_ACCEPT -> {
            payload.requireExactKeys(emptySet())
            SignalingMessageV2.ConnectAccept
        }
        SignalingMessageTypeV2.CONNECT_REJECT -> {
            payload.requireExactKeys(setOf("reason"))
            SignalingMessageV2.ConnectReject(payload.requiredEnum("reason"))
        }
        SignalingMessageTypeV2.BUSY -> {
            payload.requireExactKeys(emptySet())
            SignalingMessageV2.Busy
        }
        SignalingMessageTypeV2.DISCONNECT -> {
            payload.requireExactKeys(emptySet())
            SignalingMessageV2.Disconnect
        }
        SignalingMessageTypeV2.OFFER -> {
            payload.requireExactKeys(setOf("sdp"))
            SignalingMessageV2.Offer(payload.requiredPayload("sdp", MAX_SDP_BYTES))
        }
        SignalingMessageTypeV2.ANSWER -> {
            payload.requireExactKeys(setOf("sdp"))
            SignalingMessageV2.Answer(payload.requiredPayload("sdp", MAX_SDP_BYTES))
        }
        SignalingMessageTypeV2.CANDIDATE -> {
            payload.requireExactKeys(setOf("candidate"))
            if (++decodedCandidateCount > MAX_CANDIDATES) {
                throw SignalingV2Exception("too many decoded candidates")
            }
            val candidate = payload.requiredObject("candidate").toString()
            requireBytes(
                "candidate",
                candidate.toByteArray(StandardCharsets.UTF_8).size,
                MAX_CANDIDATE_BYTES
            )
            SignalingMessageV2.Candidate(candidate)
        }
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key) ?: throw SignalingV2Exception("missing $key")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw SignalingV2Exception("$key must be a string")
        }
        return value.asString
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val value = get(key) ?: throw SignalingV2Exception("missing $key")
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw SignalingV2Exception("$key must be an integer")
        }
        return value.asString.toIntOrNull()
            ?: throw SignalingV2Exception("$key must be an integer")
    }

    private fun JsonObject.requiredObject(key: String): JsonObject {
        val value = get(key) ?: throw SignalingV2Exception("missing $key")
        if (!value.isJsonObject) throw SignalingV2Exception("$key must be an object")
        return value.asJsonObject
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(key: String): T {
        val value = requiredString(key)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw SignalingV2Exception("invalid $key: $value")
    }

    private inline fun <reified T : Enum<T>> JsonObject.optionalEnum(key: String): T? {
        if (!has(key)) return null
        return requiredEnum<T>(key)
    }

    private fun JsonObject.optionalBoundedText(key: String, maximumCodePoints: Int): String {
        if (!has(key)) return ""
        return boundedText(key, requiredString(key), maximumCodePoints)
    }

    private fun JsonObject.requiredPayload(key: String, maximumBytes: Int): String =
        boundedPayload(key, requiredString(key), maximumBytes)

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        requireKeys(expected, expected)
    }

    private fun JsonObject.requireKeys(required: Set<String>, allowed: Set<String>) {
        val actual = keySet()
        val missing = required - actual
        val unknown = actual - allowed
        if (missing.isNotEmpty()) throw SignalingV2Exception("missing fields: $missing")
        if (unknown.isNotEmpty()) throw SignalingV2Exception("unknown fields: $unknown")
    }

    private fun boundedText(name: String, raw: String, maximumCodePoints: Int): String {
        val value = raw.trim()
        if (value.isEmpty()) throw SignalingV2Exception("$name is empty")
        if (value.codePointCount(0, value.length) > maximumCodePoints) {
            throw SignalingV2Exception("$name is too long")
        }
        return value
    }

    private fun boundedPayload(name: String, raw: String, maximumBytes: Int): String {
        requireBytes(name, raw.toByteArray(StandardCharsets.UTF_8).size, maximumBytes)
        return raw
    }

    private fun parseJsonObject(name: String, raw: String): JsonObject = try {
        JsonParser.parseString(raw).asJsonObject
    } catch (t: Throwable) {
        throw SignalingV2Exception("$name must be a JSON object", t)
    }

    private fun canonicalField(raw: String, field: String): String {
        try {
            requireCanonicalUuid(raw, field)
        } catch (t: IllegalArgumentException) {
            throw SignalingV2Exception(t.message ?: "invalid $field", t)
        }
        return raw
    }

    private fun requireBytes(name: String, actual: Int, maximum: Int) {
        if (actual !in 1..maximum) {
            throw SignalingV2Exception("$name bytes=$actual max=$maximum")
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val MAX_FRAME_BYTES = 128 * 1024
        const val MAX_SDP_BYTES = 64 * 1024
        const val MAX_CANDIDATE_BYTES = 4 * 1024
        const val MAX_CANDIDATES = 256
        const val MAX_NICKNAME_CODE_POINTS = 64
        const val MAX_DEVICE_NAME_CODE_POINTS = 128

        private val ENVELOPE_KEYS = setOf(
            "protocolVersion",
            "type",
            "attemptId",
            "sourceDeviceId",
            "targetDeviceId",
            "sourceSessionId",
            "payload"
        )
    }
}

internal class SignalingPhaseMachine(initialRequestRole: RequestRole?) {
    var requestRole: RequestRole? = initialRequestRole
        private set

    var phase: SignalingPhase = if (initialRequestRole == RequestRole.REQUESTER) {
        SignalingPhase.READY_TO_SEND_REQUESTER_HELLO
    } else {
        SignalingPhase.AWAITING_REQUESTER_HELLO
    }
        private set

    @Synchronized
    fun onFrame(direction: FrameDirection, message: SignalingMessageV2) {
        val next = nextPhase(direction, message)
            ?: throw SignalingV2Exception(
                "unexpected ${message.type} direction=$direction phase=$phase role=$requestRole"
            )
        if (
            phase == SignalingPhase.AWAITING_REQUESTER_HELLO &&
            direction == FrameDirection.INBOUND &&
            message is SignalingMessageV2.Hello
        ) {
            requestRole = RequestRole.RESPONDER
        }
        phase = next
    }

    @Synchronized
    fun resolveGlare(localRequestWins: Boolean) {
        if (phase != SignalingPhase.GLARE_PENDING || requestRole != RequestRole.REQUESTER) {
            throw SignalingV2Exception("glare resolution is not pending")
        }
        if (localRequestWins) {
            phase = SignalingPhase.AWAITING_RESPONDER_HELLO
        } else {
            requestRole = RequestRole.RESPONDER
            phase = SignalingPhase.READY_TO_SEND_RESPONDER_HELLO
        }
    }

    @Synchronized
    fun markConnected() {
        if (phase != SignalingPhase.MEDIA_NEGOTIATING) {
            throw SignalingV2Exception("media cannot connect from phase=$phase")
        }
        phase = SignalingPhase.CONNECTED
    }

    @Synchronized
    fun close() {
        phase = SignalingPhase.CLOSED
    }

    private fun nextPhase(
        direction: FrameDirection,
        message: SignalingMessageV2
    ): SignalingPhase? = when (phase) {
        SignalingPhase.READY_TO_SEND_REQUESTER_HELLO ->
            SignalingPhase.AWAITING_RESPONDER_HELLO.takeIf {
                direction == FrameDirection.OUTBOUND && message.isHello(RequestRole.REQUESTER)
            }
        SignalingPhase.AWAITING_REQUESTER_HELLO ->
            SignalingPhase.READY_TO_SEND_RESPONDER_HELLO.takeIf {
                direction == FrameDirection.INBOUND && message.isHello(RequestRole.REQUESTER)
            }
        SignalingPhase.AWAITING_RESPONDER_HELLO -> when {
            direction == FrameDirection.INBOUND && message.isHello(RequestRole.RESPONDER) ->
                SignalingPhase.READY_TO_SEND_CONNECT_REQUEST
            direction == FrameDirection.INBOUND && message.isHello(RequestRole.REQUESTER) ->
                SignalingPhase.GLARE_PENDING
            else -> null
        }
        SignalingPhase.READY_TO_SEND_RESPONDER_HELLO ->
            SignalingPhase.AWAITING_CONNECT_REQUEST.takeIf {
                direction == FrameDirection.OUTBOUND && message.isHello(RequestRole.RESPONDER)
            }
        SignalingPhase.READY_TO_SEND_CONNECT_REQUEST ->
            SignalingPhase.AWAITING_REMOTE_DECISION.takeIf {
                direction == FrameDirection.OUTBOUND &&
                    message is SignalingMessageV2.ConnectRequest
            }
        SignalingPhase.AWAITING_CONNECT_REQUEST ->
            SignalingPhase.AWAITING_LOCAL_DECISION.takeIf {
                direction == FrameDirection.INBOUND &&
                    message is SignalingMessageV2.ConnectRequest
            }
        SignalingPhase.AWAITING_REMOTE_DECISION -> when {
            direction == FrameDirection.INBOUND &&
                message == SignalingMessageV2.ConnectAccept -> SignalingPhase.ACCEPTED
            direction == FrameDirection.INBOUND &&
                (message is SignalingMessageV2.ConnectReject ||
                    message == SignalingMessageV2.Busy) -> SignalingPhase.CLOSED
            else -> null
        }
        SignalingPhase.AWAITING_LOCAL_DECISION -> when {
            direction == FrameDirection.OUTBOUND &&
                message == SignalingMessageV2.ConnectAccept -> SignalingPhase.ACCEPTED
            direction == FrameDirection.OUTBOUND &&
                (message is SignalingMessageV2.ConnectReject ||
                    message == SignalingMessageV2.Busy) -> SignalingPhase.CLOSED
            else -> null
        }
        SignalingPhase.GLARE_PENDING -> null
        SignalingPhase.ACCEPTED -> acceptedPhase(direction, message)
        SignalingPhase.AWAITING_ANSWER -> mediaPhase(
            direction,
            message,
            answerDirection = FrameDirection.INBOUND
        )
        SignalingPhase.READY_TO_SEND_ANSWER -> mediaPhase(
            direction,
            message,
            answerDirection = FrameDirection.OUTBOUND
        )
        SignalingPhase.MEDIA_NEGOTIATING,
        SignalingPhase.CONNECTED -> when (message) {
            is SignalingMessageV2.Candidate -> phase
            SignalingMessageV2.Disconnect -> SignalingPhase.CLOSED
            else -> null
        }
        SignalingPhase.CLOSED -> null
    }

    private fun acceptedPhase(
        direction: FrameDirection,
        message: SignalingMessageV2
    ): SignalingPhase? = when {
        message == SignalingMessageV2.Disconnect -> SignalingPhase.CLOSED
        requestRole == RequestRole.REQUESTER &&
            direction == FrameDirection.OUTBOUND &&
            message is SignalingMessageV2.Offer -> SignalingPhase.AWAITING_ANSWER
        requestRole == RequestRole.RESPONDER &&
            direction == FrameDirection.INBOUND &&
            message is SignalingMessageV2.Offer -> SignalingPhase.READY_TO_SEND_ANSWER
        else -> null
    }

    private fun mediaPhase(
        direction: FrameDirection,
        message: SignalingMessageV2,
        answerDirection: FrameDirection
    ): SignalingPhase? = when {
        message == SignalingMessageV2.Disconnect -> SignalingPhase.CLOSED
        message is SignalingMessageV2.Candidate -> phase
        direction == answerDirection && message is SignalingMessageV2.Answer ->
            SignalingPhase.MEDIA_NEGOTIATING
        else -> null
    }

    private fun SignalingMessageV2.isHello(role: RequestRole): Boolean =
        this is SignalingMessageV2.Hello && requestRole == role
}

internal object SignalingV2Framing {
    fun write(output: DataOutputStream, frame: ByteArray) {
        if (frame.size !in 1..SignalingV2Codec.MAX_FRAME_BYTES) {
            throw SignalingV2Exception("invalid signaling frame length: ${frame.size}")
        }
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length !in 1..SignalingV2Codec.MAX_FRAME_BYTES) {
            throw SignalingV2Exception("invalid signaling frame length: $length")
        }
        return ByteArray(length).also(input::readFully)
    }
}
