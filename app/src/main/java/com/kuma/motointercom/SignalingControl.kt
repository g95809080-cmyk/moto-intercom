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
    val mediaOwnerChannelId: ControlChannelId? = null,
    val selectionCohort: SelectionCohort? = null,
    val terminalOutcome: AttemptOutcome? = null,
    val pendingTerminalChannels: Set<ControlChannelId> = emptySet()
) {
    init {
        require(peer.isVerifiedFor(attempt.targetLock)) {
            "Attempt channel set peer must match the attempt TargetLock"
        }
        require(mediaOwnerChannelId == null || mediaOwnerChannelId in channelIds) {
            "Media owner must belong to the attempt"
        }
        require(selectionCohort == null || selectionCohort.wireRequestKey == wireRequestKey) {
            "Selection cohort must belong to the same wire request"
        }
    }
}

internal enum class PendingInboundPhase {
    WAITING_LOCAL_DECISION,
    TERMINATING
}

internal data class PendingInboundRequest(
    val runtimeSessionId: RuntimeSessionId,
    val wireRequestKey: WireRequestKey,
    val targetLock: TargetLock,
    val peer: PeerIdentity,
    val transport: Transport,
    val channelIds: Set<ControlChannelId>,
    val phase: PendingInboundPhase,
    val confirmationChannelId: ControlChannelId? = null,
    val confirmationSurface: ConfirmationSurface? = null,
    val confirmationActionNonce: String? = null,
    val decisionDeadlineAt: MonotonicTimestamp,
    val terminalOutcome: AttemptOutcome? = null,
    val pendingTerminalChannels: Set<ControlChannelId> = emptySet()
) {
    init {
        require(peer.isVerifiedFor(targetLock)) {
            "Pending inbound peer must match its TargetLock"
        }
        require(channelIds.isNotEmpty()) { "Pending inbound channels must not be empty" }
        require(confirmationChannelId == null || confirmationChannelId in channelIds) {
            "Confirmation channel must belong to the pending request"
        }
        val confirmationFields = listOf(
            confirmationChannelId,
            confirmationSurface,
            confirmationActionNonce
        )
        require(confirmationFields.all { it == null } || confirmationFields.all { it != null }) {
            "Confirmation ownership, surface and nonce must be set together"
        }
    }

    val attemptId: ConnectionAttemptId
        get() = wireRequestKey.attemptId
}

internal data class MediaChannelCandidate(
    val channelId: ControlChannelId,
    val transport: Transport
)

internal data class ConnectionCandidateContext(
    val attempt: ConnectionAttempt,
    val channelId: ControlChannelId,
    val wireRequestKey: WireRequestKey,
    val targetLock: TargetLock,
    val transport: Transport,
    val requestRole: RequestRole,
    val peer: PeerIdentity
) {
    init {
        require(wireRequestKey.attemptId == attempt.id) {
            "Candidate wire request must match the attempt"
        }
        require(targetLock == attempt.targetLock) {
            "Candidate TargetLock must match the attempt"
        }
        require(transport == attempt.channelPlan.transport) {
            "Candidate transport must match the attempt plan"
        }
        require(peer.isVerifiedFor(targetLock)) {
            "Candidate peer must be verified for the attempt TargetLock"
        }
        when (requestRole) {
            RequestRole.REQUESTER -> {
                require(wireRequestKey.requesterSessionId == attempt.runtimeSessionId) {
                    "Requester candidate runtime must match the attempt"
                }
                require(wireRequestKey.responderDeviceId.value == attempt.targetDeviceId) {
                    "Requester candidate target must match the responder"
                }
            }
            RequestRole.RESPONDER -> {
                require(wireRequestKey.requesterDeviceId.value == attempt.targetDeviceId) {
                    "Responder candidate target must match the requester"
                }
                require(
                    wireRequestKey.requesterSessionId ==
                        attempt.targetLock.expectedRemoteSessionId
                ) {
                    "Responder candidate runtime must match the requester"
                }
            }
        }
    }

    val runtimeSessionId: RuntimeSessionId
        get() = attempt.runtimeSessionId

    val attemptId: ConnectionAttemptId
        get() = attempt.id
}

internal fun isCurrentMediaCandidate(
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    candidate: ConnectionCandidateContext
): Boolean {
    val active = activeAttempt ?: return false
    val phaseAllowsMedia = when (active.phase) {
        SignalingAttemptPhase.ACCEPTED,
        SignalingAttemptPhase.MEDIA_NEGOTIATING,
        SignalingAttemptPhase.CONNECTED -> true
        else -> false
    }
    return currentAttempt == candidate.attempt &&
        active.attempt == candidate.attempt &&
        active.wireRequestKey == candidate.wireRequestKey &&
        active.peer == candidate.peer &&
        candidate.channelId in active.channelIds &&
        active.mediaOwnerChannelId == candidate.channelId &&
        active.terminalOutcome == AttemptOutcome.ACCEPTED &&
        phaseAllowsMedia
}

internal fun isCurrentSelectionCandidate(
    currentAttempt: ConnectionAttempt?,
    activeAttempt: AttemptChannelSet?,
    candidate: ConnectionCandidateContext,
    wireRequestKey: WireRequestKey
): Boolean {
    val active = activeAttempt ?: return false
    return currentAttempt == candidate.attempt &&
        active.attempt == candidate.attempt &&
        active.phase == SignalingAttemptPhase.SELECTING_MEDIA &&
        active.mediaOwnerChannelId == null &&
        active.wireRequestKey == wireRequestKey &&
        candidate.wireRequestKey == wireRequestKey &&
        active.peer == candidate.peer &&
        candidate.channelId in active.channelIds
}

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
