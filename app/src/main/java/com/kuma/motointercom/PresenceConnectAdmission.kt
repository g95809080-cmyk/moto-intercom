package com.kuma.motointercom

/** Service authorization stays revocable until the actor commits the established call. */
internal class PresenceConnectAdmission(
    private val sessions: SessionGeneration,
    private val token: SessionGeneration.Token,
    val request: PresenceConnectRequest
) {
    private val lock = Any()
    private var revoked = false
    private var completed = false
    private var adopted: ConnectionAttempt? = null
    private var original: ConnectionAttempt? = null

    fun revoke(): ConnectionAttempt? = synchronized(lock) {
        if (completed) return@synchronized null
        revoked = true
        adopted
    }

    val adoptedAttempt: ConnectionAttempt? get() = synchronized(lock) { adopted }
    val isRevoked: Boolean get() = synchronized(lock) { revoked }

    fun isAuthorizedFor(attempt: ConnectionAttempt): Boolean = synchronized(lock) {
        !revoked && adopted == attempt && sessions.isCurrent(token)
    }

    fun isAuthorizedOrigin(origin: ConnectionAttempt, current: ConnectionAttempt): Boolean = synchronized(lock) {
        !revoked && adopted == current && (origin == current || origin == original) &&
            origin.runtimeSessionId == current.runtimeSessionId && origin.targetLock == current.targetLock &&
            origin.deadlineElapsedRealtimeMs == current.deadlineElapsedRealtimeMs && sessions.isCurrent(token)
    }

    fun isAvailableFor(event: SessionEvent.ConnectPresenceRequested): Boolean = synchronized(lock) {
        !revoked && adopted == null && matches(event) && sessions.isCurrent(token)
    }

    /** Only pure actor state writes belong in claim; factories, I/O and callbacks stay outside. */
    fun tryAdopt(
        event: SessionEvent.ConnectPresenceRequested,
        attempt: ConnectionAttempt,
        claim: () -> SignalingControlDecision
    ): SignalingControlDecision? = synchronized(lock) {
        if (revoked || adopted != null || !matches(event) ||
            attempt.runtimeSessionId != request.runtimeSessionId ||
            attempt.targetLock != TargetLock(request.targetDeviceId, request.targetSessionId)
        ) return@synchronized null
        var result: SignalingControlDecision? = null
        val accepted = sessions.claimIfCurrent(token) {
            result = claim()
            result?.accepted == true
        }
        if (!accepted) return@synchronized null
        adopted = attempt
        original = attempt
        result
    }

    /** Glare keeps the same authorization and deadline while changing the wire attempt ID. */
    fun tryTransfer(
        previous: ConnectionAttempt,
        replacement: ConnectionAttempt?,
        claim: () -> SignalingControlDecision
    ): SignalingControlDecision? = synchronized(lock) {
        if (revoked || adopted != previous || replacement?.let {
                it.runtimeSessionId != previous.runtimeSessionId || it.targetLock != previous.targetLock ||
                    it.deadlineElapsedRealtimeMs != previous.deadlineElapsedRealtimeMs
            } == true
        ) return@synchronized null
        var result: SignalingControlDecision? = null
        val accepted = sessions.claimIfCurrent(token) {
            result = claim()
            result?.accepted == true
        }
        if (!accepted) return@synchronized null
        if (replacement != null) adopted = replacement
        result
    }

    /** Ordinary actor graph decisions cannot overtake a revocation and its exact cancellation. */
    fun tryDecide(
        attempt: ConnectionAttempt,
        claim: () -> SignalingControlDecision
    ): SignalingControlDecision? = synchronized(lock) {
        if (revoked || completed || adopted != attempt) return@synchronized null
        var result: SignalingControlDecision? = null
        if (!sessions.claimIfCurrent(token) { result = claim(); true }) return@synchronized null
        result
    }

    /** Commit SUCCESS under the same lock as revocation; established calls outlive the request. */
    fun tryComplete(
        attempt: ConnectionAttempt,
        claim: () -> SignalingControlDecision
    ): SignalingControlDecision? = synchronized(lock) {
        if (revoked || completed || adopted != attempt) return@synchronized null
        var result: SignalingControlDecision? = null
        val accepted = sessions.claimIfCurrent(token) {
            result = claim()
            result?.accepted == true
        }
        if (!accepted) return@synchronized null
        completed = true
        result
    }

    private fun matches(event: SessionEvent.ConnectPresenceRequested): Boolean =
        event.admission === this && event.runtimeSessionId == request.runtimeSessionId &&
            event.targetDeviceId == request.targetDeviceId && event.targetSessionId == request.targetSessionId
}
