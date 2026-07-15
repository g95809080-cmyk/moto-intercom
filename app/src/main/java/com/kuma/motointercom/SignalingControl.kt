package com.kuma.motointercom

internal data class VerifiedControlChannel(
    val channelId: ControlChannelId,
    val transport: Transport,
    val requestRole: RequestRole,
    val wireRequestKey: WireRequestKey,
    val targetLock: TargetLock,
    val peer: PeerIdentity,
    val originatingAttempt: ConnectionAttempt?
) {
    init {
        require(peer.isVerifiedFor(targetLock)) {
            "Verified control channel peer must match its TargetLock"
        }
        if (requestRole == RequestRole.REQUESTER) {
            requireNotNull(originatingAttempt) {
                "Requester control channel must belong to an outbound attempt"
            }
            require(originatingAttempt.id == wireRequestKey.attemptId) {
                "Requester channel attempt does not match its wire request key"
            }
        }
    }
}

internal data class SelectionCohort(
    val wireRequestKey: WireRequestKey,
    val channelIds: Set<ControlChannelId>,
    val frozenAtElapsedMs: Long
) {
    init {
        require(channelIds.isNotEmpty()) { "Selection cohort must not be empty" }
        require(frozenAtElapsedMs >= 0L) { "Selection cohort time must not be negative" }
    }
}

internal enum class SignalingAttemptPhase {
    WAITING_REMOTE_DECISION,
    WAITING_LOCAL_DECISION,
    SELECTING_MEDIA,
    ACCEPTING,
    ACCEPTED,
    MEDIA_NEGOTIATING,
    CONNECTED,
    TERMINATING
}

internal enum class AttemptOutcome {
    ACCEPTED,
    REJECTED,
    TIMED_OUT,
    BUSY,
    CANCELED,
    GLARE_LOST,
    DISCONNECTED
}

internal data class CompletedWireAttempt(
    val key: WireRequestKey,
    val outcome: AttemptOutcome,
    val response: SignalingMessageV2?,
    val expiresAtElapsedMs: Long
)

internal data class AttemptChannelSet(
    val wireRequestKey: WireRequestKey,
    val attempt: ConnectionAttempt,
    val peer: PeerIdentity,
    val channelIds: Set<ControlChannelId>,
    val phase: SignalingAttemptPhase,
    val confirmationChannelId: ControlChannelId? = null,
    val mediaOwnerChannelId: ControlChannelId? = null,
    val selectionCohort: SelectionCohort? = null,
    val terminalOutcome: AttemptOutcome? = null,
    val pendingTerminalChannels: Set<ControlChannelId> = emptySet()
) {
    init {
        require(peer.isVerifiedFor(attempt.targetLock)) {
            "Attempt channel set peer must match the attempt TargetLock"
        }
        require(confirmationChannelId == null || confirmationChannelId in channelIds) {
            "Confirmation channel must belong to the attempt"
        }
        require(mediaOwnerChannelId == null || mediaOwnerChannelId in channelIds) {
            "Media owner must belong to the attempt"
        }
        require(selectionCohort == null || selectionCohort.wireRequestKey == wireRequestKey) {
            "Selection cohort must belong to the same wire request"
        }
    }
}

internal data class MediaChannelCandidate(
    val channelId: ControlChannelId,
    val transport: Transport
)

internal fun selectMediaChannel(
    candidates: Collection<MediaChannelCandidate>,
    preferredTransport: Transport?
): ControlChannelId? {
    if (candidates.isEmpty()) return null
    val preferred = preferredTransport?.let { preferredTransportValue ->
        candidates.filter { it.transport == preferredTransportValue }
    }.orEmpty()
    val eligible = preferred.ifEmpty { candidates.toList() }
    return eligible.minWithOrNull(
        compareBy<MediaChannelCandidate>({ it.transport.selectionPriority }, { it.channelId })
    )?.channelId
}

private val Transport.selectionPriority: Int
    get() = when (this) {
        Transport.LAN -> 0
        Transport.WIFI_DIRECT -> 1
    }

internal fun ConnectionTrigger.toRequestTrigger(): RequestTrigger = when (this) {
    ConnectionTrigger.USER -> RequestTrigger.USER
    ConnectionTrigger.AUTO_PAIRED -> RequestTrigger.AUTO_PAIRED
    ConnectionTrigger.INBOUND -> RequestTrigger.INBOUND
    ConnectionTrigger.RECOVERY -> RequestTrigger.RECOVERY
}
