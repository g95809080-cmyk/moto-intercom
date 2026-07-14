package com.kuma.motointercom

import java.io.IOException
import java.util.UUID

internal class SignalingV2Exception(message: String, cause: Throwable? = null) :
    IOException(message, cause)

@JvmInline
internal value class DeviceId private constructor(val value: String) {
    companion object {
        fun create(): DeviceId = DeviceId(UUID.randomUUID().toString())
        fun parse(value: String): DeviceId = DeviceId(canonicalUuid(value, "deviceId"))
    }
}

@JvmInline
internal value class ControlChannelId private constructor(val value: String) {
    companion object {
        fun create(): ControlChannelId = ControlChannelId(UUID.randomUUID().toString())
        fun parse(value: String): ControlChannelId =
            ControlChannelId(canonicalUuid(value, "channelId"))
    }
}

internal data class WireRequestKey(
    val requesterDeviceId: DeviceId,
    val requesterSessionId: RuntimeSessionId,
    val attemptId: ConnectionAttemptId,
    val responderDeviceId: DeviceId
) : Comparable<WireRequestKey> {
    init {
        requireCanonicalUuid(requesterSessionId.value, "requesterSessionId")
        requireCanonicalUuid(attemptId.value, "attemptId")
        require(requesterDeviceId != responderDeviceId) {
            "Requester and responder device IDs must differ"
        }
    }

    override fun compareTo(other: WireRequestKey): Int = compareValues(
        compareUuid(attemptId.value, other.attemptId.value),
        compareUuid(requesterDeviceId.value, other.requesterDeviceId.value),
        compareUuid(requesterSessionId.value, other.requesterSessionId.value),
        compareUuid(responderDeviceId.value, other.responderDeviceId.value)
    )
}

internal data class PinnedChannelIdentity(
    val localDeviceId: DeviceId,
    val localSessionId: RuntimeSessionId,
    val remoteDeviceId: DeviceId,
    val remoteSessionId: RuntimeSessionId,
    val wireRequestKey: WireRequestKey
) {
    init {
        requireCanonicalUuid(localSessionId.value, "localSessionId")
        requireCanonicalUuid(remoteSessionId.value, "remoteSessionId")
        require(localDeviceId != remoteDeviceId) { "Pinned endpoints must differ" }
        require(matchesRequesterDirection() || matchesResponderDirection()) {
            "Pinned endpoints do not match the wire request key"
        }
    }

    fun requireIncoming(envelope: SignalingEnvelopeV2) {
        if (
            envelope.targetDeviceId != localDeviceId ||
            envelope.sourceDeviceId != remoteDeviceId ||
            envelope.sourceSessionId != remoteSessionId ||
            envelope.attemptId != wireRequestKey.attemptId
        ) {
            throw SignalingV2Exception("incoming envelope does not match pinned channel identity")
        }
    }

    fun requireOutgoing(envelope: SignalingEnvelopeV2) {
        if (
            envelope.sourceDeviceId != localDeviceId ||
            envelope.sourceSessionId != localSessionId ||
            envelope.targetDeviceId != remoteDeviceId ||
            envelope.attemptId != wireRequestKey.attemptId
        ) {
            throw SignalingV2Exception("outgoing envelope does not match pinned channel identity")
        }
    }

    private fun matchesRequesterDirection(): Boolean =
        wireRequestKey.requesterDeviceId == localDeviceId &&
            wireRequestKey.requesterSessionId == localSessionId &&
            wireRequestKey.responderDeviceId == remoteDeviceId

    private fun matchesResponderDirection(): Boolean =
        wireRequestKey.requesterDeviceId == remoteDeviceId &&
            wireRequestKey.requesterSessionId == remoteSessionId &&
            wireRequestKey.responderDeviceId == localDeviceId
}

internal data class PendingControlChannel(
    val channelId: ControlChannelId,
    val transport: Transport,
    val physicalRole: PhysicalSocketRole,
    val requestRole: RequestRole?,
    val openedAtElapsedMs: Long
) {
    init {
        require(openedAtElapsedMs >= 0L) { "Channel open time must not be negative" }
    }
}

internal enum class PhysicalSocketRole {
    OPENER,
    ACCEPTOR
}

internal enum class RequestRole(val webRtcRole: WebRtcRole) {
    REQUESTER(WebRtcRole.OFFERER),
    RESPONDER(WebRtcRole.ANSWERER)
}

internal enum class WebRtcRole {
    OFFERER,
    ANSWERER
}

internal enum class ResponseScope {
    CHANNEL,
    ATTEMPT
}

internal enum class RejectReason(val scope: ResponseScope) {
    SUPERSEDED_CHANNEL(ResponseScope.CHANNEL),
    IDENTITY_MISMATCH(ResponseScope.CHANNEL),
    PROTOCOL_ERROR(ResponseScope.CHANNEL),
    USER_REJECTED(ResponseScope.ATTEMPT),
    TIMEOUT(ResponseScope.ATTEMPT),
    CONFIRMATION_UNAVAILABLE(ResponseScope.ATTEMPT),
    CANCELED(ResponseScope.ATTEMPT),
    GLARE_LOST(ResponseScope.ATTEMPT)
}

internal enum class SignalingPhase {
    READY_TO_SEND_REQUESTER_HELLO,
    AWAITING_REQUESTER_HELLO,
    AWAITING_RESPONDER_HELLO,
    READY_TO_SEND_RESPONDER_HELLO,
    READY_TO_SEND_CONNECT_REQUEST,
    AWAITING_CONNECT_REQUEST,
    AWAITING_REMOTE_DECISION,
    AWAITING_LOCAL_DECISION,
    GLARE_PENDING,
    ACCEPTED,
    AWAITING_ANSWER,
    READY_TO_SEND_ANSWER,
    MEDIA_NEGOTIATING,
    CONNECTED,
    CLOSED
}

internal enum class FrameDirection {
    INBOUND,
    OUTBOUND
}

private fun canonicalUuid(raw: String, field: String): String {
    requireCanonicalUuid(raw, field)
    return raw
}

internal fun requireCanonicalUuid(raw: String, field: String) {
    val parsed = try {
        UUID.fromString(raw)
    } catch (t: IllegalArgumentException) {
        throw IllegalArgumentException("$field must be a canonical RFC 4122 UUID", t)
    }
    require(parsed.toString() == raw) { "$field must be a canonical lowercase RFC 4122 UUID" }
}

private fun compareUuid(left: String, right: String): Int {
    val leftUuid = UUID.fromString(left)
    val rightUuid = UUID.fromString(right)
    val high = java.lang.Long.compareUnsigned(
        leftUuid.mostSignificantBits,
        rightUuid.mostSignificantBits
    )
    return if (high != 0) high else java.lang.Long.compareUnsigned(
        leftUuid.leastSignificantBits,
        rightUuid.leastSignificantBits
    )
}

private fun compareValues(vararg comparisons: Int): Int =
    comparisons.firstOrNull { it != 0 } ?: 0
