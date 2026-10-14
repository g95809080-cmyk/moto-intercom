package com.kuma.motointercom

/** Cache delivery cannot introduce a runtime; only current, physically received input can. */
internal class DiscoveryPresenceAdmission {
    private val lock = Any()
    private val candidates = mutableMapOf<Transport, List<DiscoveryCandidate>>()
    private val latest = mutableMapOf<String, FreshDiscoveryObservation>()
    private val sessions = DiscoverySessionTracker()

    fun replaceCached(transport: Transport, values: List<DiscoveryCandidate>) = synchronized(lock) {
        require(values.all { it.transport == transport })
        candidates[transport] = values.toList()
    }

    fun eligibleCandidates(transport: Transport): List<DiscoveryCandidate> = synchronized(lock) {
        candidates[transport].orEmpty().filter { candidate ->
            val device = candidate.identity.claimedDeviceId
            val admitted = latest[device]?.candidate?.identity
            admitted != null && candidate.identity.sourceSessionId == admitted.sourceSessionId &&
                candidate.hasStableV2ObservationIdentity()
        }
    }

    fun admit(observation: FreshDiscoveryObservation): Boolean =
        observation.source.claimIfCurrent(observation) {
            synchronized(lock) {
                val candidate = observation.candidate
                if (candidate !in candidates[candidate.transport].orEmpty()) return@synchronized false
                val device = requireNotNull(candidate.identity.claimedDeviceId)
                val previous = latest[device]
                if (previous != null) {
                    val otherRuntime = candidate.identity.sourceSessionId != previous.candidate.identity.sourceSessionId
                    if (observation.receivedAtElapsedRealtimeMs < previous.receivedAtElapsedRealtimeMs) {
                        if (otherRuntime) sessions.retire(candidate.identity)
                        return@synchronized false
                    }
                    if (otherRuntime && observation.receivedAtElapsedRealtimeMs == previous.receivedAtElapsedRealtimeMs)
                        return@synchronized false
                }
                if (sessions.register(candidate.identity) == DiscoverySessionRegistration.SUPERSEDED) return@synchronized false
                latest[device] = observation
                true
            }
        } == true

    fun <T : Any> claimIfCurrent(observation: FreshDiscoveryObservation, claim: () -> T?): T? = synchronized(lock) {
        val candidate = observation.candidate
        if (latest[candidate.identity.claimedDeviceId]?.receipt != observation.receipt ||
            candidate !in candidates[candidate.transport].orEmpty()
        ) null else claim()
    }

    fun clear() = synchronized(lock) {
        candidates.clear()
        latest.clear()
        sessions.clear()
    }
}
