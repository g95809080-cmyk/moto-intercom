package com.kuma.motointercom

/** Command-entry authorization shared by Main and the actor. Claims only mutate actor metadata. */
internal class AutomaticRecoveryPolicy {
    internal class Ticket internal constructor(
        internal val policy: AutomaticRecoveryPolicy,
        val deviceId: String,
        internal val revision: Long,
        internal val targetRevision: Long,
        internal val preferred: Boolean
    ) {
        internal var consumed = false
        internal var observation: FreshDiscoveryObservation? = null
        internal var presenceAdmission: DiscoveryPresenceAdmission? = null
    }

    private val lock = Any()
    private var enabled = true
    private var revision = 0L
    private var preferredSuppressed = false
    private val targetRevisions = mutableMapOf<String, Long>()
    private val canceledTargets = mutableSetOf<String>()
    private var canceledConnectedSource: ConnectionAttempt? = null

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled != value) { enabled = value; revision++ }
    }

    fun beginRuntime() = synchronized(lock) {
        revision++
        preferredSuppressed = false
        targetRevisions.clear()
        canceledTargets.clear()
        canceledConnectedSource = null
    }

    fun cancelAutomaticStarts(connectedSource: ConnectionAttempt? = null) = synchronized(lock) {
        revision++
        preferredSuppressed = true
        if (connectedSource != null) canceledConnectedSource = connectedSource
    }

    fun manualChoice(deviceId: String, connectedSource: ConnectionAttempt? = null) = synchronized(lock) {
        revision++
        preferredSuppressed = true
        canceledTargets.remove(deviceId)
        if (connectedSource != null) canceledConnectedSource = connectedSource
    }

    fun cancelTarget(deviceId: String) = synchronized(lock) {
        targetRevisions[deviceId] = (targetRevisions[deviceId] ?: 0L) + 1L
        canceledTargets.add(deviceId)
    }

    fun capture(deviceId: String, preferred: Boolean = false): Ticket? = synchronized(lock) {
        if (!enabled || deviceId in canceledTargets || preferred && preferredSuppressed) null
        else Ticket(this, deviceId, revision, targetRevisions[deviceId] ?: 0L, preferred)
    }

    fun captureLoss(source: ConnectionAttempt): Ticket? = synchronized(lock) {
        if (source == canceledConnectedSource) null else capture(source.targetDeviceId)
    }

    fun isAuthorized(ticket: Ticket): Boolean = synchronized(lock) { authorized(ticket) }

    fun captureForGoal(ticket: Ticket): Ticket? = synchronized(lock) {
        if (!authorized(ticket)) null
        else Ticket(this, ticket.deviceId, revision, ticket.targetRevision, preferred = false)
    }

    // A consumed ticket may continue authorizing its standing goal, but cannot adopt again.
    fun <T : Any> tryConsume(ticket: Ticket, claim: () -> T?): T? = synchronized(lock) {
        if (ticket.consumed || !authorized(ticket)) return@synchronized null
        val observed = ticket.observation
        val result = if (observed == null) claim() else observed.source.claimIfCurrent(observed) {
            ticket.presenceAdmission?.claimIfCurrent(observed, claim) ?: if (ticket.presenceAdmission == null) claim() else null
        }
        if (result == null) return@synchronized null
        ticket.consumed = true
        result
    }

    private fun authorized(ticket: Ticket): Boolean = ticket.policy === this && enabled &&
        ticket.revision == revision && ticket.targetRevision == (targetRevisions[ticket.deviceId] ?: 0L) &&
        ticket.deviceId !in canceledTargets && (!ticket.preferred || !preferredSuppressed)
}

internal data class RecoveryIntentRef(val runtimeSessionId: RuntimeSessionId, val generation: Long)

internal data class ConnectionLossObservation(
    val sourceAttempt: ConnectionAttempt?,
    val authorization: AutomaticRecoveryPolicy.Ticket?
)

internal data class AutomaticRecoveryIntent(
    val ref: RecoveryIntentRef,
    val sourceConnectedAttempt: ConnectionAttempt,
    val preferredTransport: Transport,
    val authorization: AutomaticRecoveryPolicy.Ticket,
    val resetAttemptId: ConnectionAttemptId? = null,
    val eligibleAfterElapsedMs: Long? = null
) {
    val targetDeviceId get() = sourceConnectedAttempt.targetDeviceId
}

internal data class RecoveryEpisodeRequest(
    val intent: RecoveryIntentRef,
    val resetAttemptId: ConnectionAttemptId,
    val eligibleAfterElapsedMs: Long,
    val observation: FreshDiscoveryObservation,
    val availableTransports: Set<Transport>
) {
    val targetLock get() = TargetLock(
        requireNotNull(observation.candidate.identity.claimedDeviceId),
        requireNotNull(observation.candidate.identity.sourceSessionId)
    )
}

/** A single adoption; subsequent bounded retries never retain a Service admission lock. */
internal class RecoveryEpisodeAdmission(
    private val policy: AutomaticRecoveryPolicy,
    private val authorization: AutomaticRecoveryPolicy.Ticket,
    private val sessions: SessionGeneration,
    private val token: SessionGeneration.Token,
    val request: RecoveryEpisodeRequest,
    private val presenceAdmission: DiscoveryPresenceAdmission? = null
) {
    fun tryConsume(claim: () -> SignalingControlDecision): SignalingControlDecision? =
        policy.tryConsume(authorization) {
            request.observation.source.claimIfCurrent(request.observation) {
                val adopt = {
                    var decision: SignalingControlDecision? = null
                    if (!sessions.claimIfCurrent(token) {
                            decision = claim()
                            decision?.accepted == true
                        }) null else decision
                }
                presenceAdmission?.claimIfCurrent(request.observation, adopt)
                    ?: if (presenceAdmission == null) adopt() else null
            }
        }
}
