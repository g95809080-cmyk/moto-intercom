package com.kuma.motointercom

import java.util.Locale

internal data class DiscoveryIdentityClaim(
    val claimedDeviceId: String?,
    val sourceSessionId: RuntimeSessionId?,
    val nickname: String,
    val deviceName: String,
    val protocolVersion: Int
) {
    init {
        require(protocolVersion >= 0) { "Protocol version must not be negative" }
    }

    val hasStableIdentity: Boolean
        get() = !claimedDeviceId.isNullOrBlank() && sourceSessionId != null
}

internal data class DiscoveryCandidate(
    val transport: Transport,
    val endpointId: String,
    val address: String,
    val port: Int?,
    val identity: DiscoveryIdentityClaim
) {
    init {
        require(endpointId.isNotBlank()) { "Discovery endpoint ID must not be blank" }
        require(address.isNotBlank()) { "Discovery address must not be blank" }
        require(port == null || port in 1..65535) { "Discovery port is invalid" }
    }
}

internal data class PresenceTransportCandidate(
    val transport: Transport,
    val endpointId: String,
    val address: String,
    val port: Int?,
    val lastSeenElapsedRealtimeMs: Long,
    val isAvailable: Boolean
)

internal data class RiderPresence(
    val deviceId: String?,
    val sessionId: RuntimeSessionId?,
    val nickname: String,
    val deviceName: String,
    val protocolVersion: Int,
    val lastSeenElapsedRealtimeMs: Long,
    val candidates: List<PresenceTransportCandidate>,
    val pairing: PairingRecord?
) {
    val isPaired: Boolean
        get() = pairing != null

    val isPreferred: Boolean
        get() = pairing?.isPreferred == true

    val isSelectable: Boolean
        get() = deviceId != null && sessionId != null && candidates.any { it.isAvailable }

    val availableTransports: Set<Transport>
        get() = candidates.asSequence()
            .filter(PresenceTransportCandidate::isAvailable)
            .map(PresenceTransportCandidate::transport)
            .toSet()

    val displayName: String
        get() = pairing?.localAlias?.takeIf(String::isNotBlank)
            ?: nickname.takeIf(String::isNotBlank)
            ?: pairing?.remoteNickname?.takeIf(String::isNotBlank)
            ?: deviceName.takeIf(String::isNotBlank)
            ?: "Rider"

    fun matches(targetLock: TargetLock): Boolean =
        deviceId == targetLock.targetDeviceId &&
            sessionId == targetLock.expectedRemoteSessionId
}

internal data class PresenceSnapshot(
    val presences: List<RiderPresence>,
    val nextExpiryElapsedRealtimeMs: Long?
) {
    fun resolveCurrentSelection(selected: RiderPresence): RiderPresence? {
        val deviceId = selected.deviceId ?: return null
        val sessionId = selected.sessionId ?: return null
        return presences.firstOrNull {
            it.deviceId == deviceId &&
                it.sessionId == sessionId &&
                it.isSelectable
        }
    }
}

internal fun DiscoveryIdentityClaim.matches(targetLock: TargetLock): Boolean =
    claimedDeviceId == targetLock.targetDeviceId &&
        sourceSessionId == targetLock.expectedRemoteSessionId

internal enum class DiscoverySessionRegistration {
    ACTIVE,
    NEW_ACTIVE,
    SUPERSEDED
}

internal class DiscoverySessionTracker {
    private val activeSessionByDeviceId = linkedMapOf<String, RuntimeSessionId>()
    private val supersededSessionsByDeviceId =
        linkedMapOf<String, MutableSet<RuntimeSessionId>>()

    @Synchronized
    fun register(identity: DiscoveryIdentityClaim): DiscoverySessionRegistration {
        val deviceId = identity.claimedDeviceId?.trim()?.takeIf(String::isNotEmpty)
            ?: return DiscoverySessionRegistration.ACTIVE
        val sessionId = identity.sourceSessionId ?: return DiscoverySessionRegistration.ACTIVE
        val active = activeSessionByDeviceId[deviceId]
        return when {
            active == null -> {
                activeSessionByDeviceId[deviceId] = sessionId
                DiscoverySessionRegistration.NEW_ACTIVE
            }
            active == sessionId -> DiscoverySessionRegistration.ACTIVE
            sessionId in supersededSessionsByDeviceId[deviceId].orEmpty() -> {
                DiscoverySessionRegistration.SUPERSEDED
            }
            else -> {
                supersededSessionsByDeviceId.getOrPut(deviceId, ::linkedSetOf).add(active)
                activeSessionByDeviceId[deviceId] = sessionId
                DiscoverySessionRegistration.NEW_ACTIVE
            }
        }
    }

    @Synchronized
    fun isActive(deviceId: String, sessionId: RuntimeSessionId): Boolean =
        activeSessionByDeviceId[deviceId] == sessionId &&
            sessionId !in supersededSessionsByDeviceId[deviceId].orEmpty()

    @Synchronized
    fun clear() {
        activeSessionByDeviceId.clear()
        supersededSessionsByDeviceId.clear()
    }
}

internal class PresenceAggregator(
    private val nowElapsedRealtimeMs: () -> Long,
    private val retentionMs: Long = DEFAULT_RETENTION_MS
) {
    private data class CandidateKey(
        val transport: Transport,
        val endpointId: String
    )

    private data class TrackedCandidate(
        val candidate: DiscoveryCandidate,
        val lastSeenElapsedRealtimeMs: Long,
        val expiresAtElapsedRealtimeMs: Long?
    )

    private val candidates = linkedMapOf<CandidateKey, TrackedCandidate>()
    private val pairings = linkedMapOf<String, PairingRecord>()
    private val sessionTracker = DiscoverySessionTracker()

    init {
        require(retentionMs >= 0L) { "Presence retention must not be negative" }
    }

    @Synchronized
    fun replaceCandidates(
        transport: Transport,
        observedCandidates: List<DiscoveryCandidate>
    ): PresenceSnapshot {
        require(observedCandidates.all { it.transport == transport }) {
            "Candidate transport does not match replacement transport"
        }
        val now = nowElapsedRealtimeMs()
        val observed = observedCandidates.associateBy {
            CandidateKey(it.transport, it.endpointId)
        }

        candidates.entries
            .filter { it.key.transport == transport && it.key !in observed }
            .forEach { entry ->
                if (entry.value.expiresAtElapsedRealtimeMs == null) {
                    entry.setValue(
                        entry.value.copy(expiresAtElapsedRealtimeMs = now + retentionMs)
                    )
                }
            }

        observed.forEach { (key, candidate) ->
            if (
                sessionTracker.register(candidate.identity) ==
                DiscoverySessionRegistration.SUPERSEDED
            ) {
                return@forEach
            }
            candidates[key] = TrackedCandidate(
                candidate = candidate,
                lastSeenElapsedRealtimeMs = now,
                expiresAtElapsedRealtimeMs = null
            )
        }
        return buildSnapshot(now)
    }

    @Synchronized
    fun updatePairings(records: List<PairingRecord>): PresenceSnapshot {
        pairings.clear()
        records.forEach { record ->
            val deviceId = record.remoteDeviceId.trim()
            if (deviceId.isNotEmpty()) pairings[deviceId] = record
        }
        return buildSnapshot(nowElapsedRealtimeMs())
    }

    @Synchronized
    fun expire(): PresenceSnapshot = buildSnapshot(nowElapsedRealtimeMs())

    @Synchronized
    fun snapshot(): PresenceSnapshot = buildSnapshot(nowElapsedRealtimeMs())

    @Synchronized
    fun clear(): PresenceSnapshot {
        candidates.clear()
        sessionTracker.clear()
        return PresenceSnapshot(emptyList(), null)
    }

    private fun buildSnapshot(now: Long): PresenceSnapshot {
        candidates.entries.removeAll { (_, tracked) ->
            tracked.expiresAtElapsedRealtimeMs?.let { it <= now } == true
        }

        val groups = linkedMapOf<String, MutableList<TrackedCandidate>>()
        candidates.values.forEach { tracked ->
            val identity = tracked.candidate.identity
            val deviceId = identity.claimedDeviceId?.trim()?.takeIf(String::isNotEmpty)
            val sessionId = identity.sourceSessionId
            val groupKey = if (
                deviceId != null &&
                sessionId != null &&
                sessionTracker.isActive(deviceId, sessionId)
            ) {
                "stable:$deviceId"
            } else if (identity.hasStableIdentity) {
                return@forEach
            } else {
                "provisional:${tracked.candidate.transport}:${tracked.candidate.endpointId}"
            }
            groups.getOrPut(groupKey, ::mutableListOf).add(tracked)
        }

        val presences = groups.values.map { group ->
            val latest = group.maxBy(TrackedCandidate::lastSeenElapsedRealtimeMs)
            val identity = latest.candidate.identity
            val stableDeviceId = identity.claimedDeviceId
                ?.trim()
                ?.takeIf { identity.hasStableIdentity && it.isNotEmpty() }
            val pairing = stableDeviceId?.let(pairings::get)
            RiderPresence(
                deviceId = stableDeviceId,
                sessionId = identity.sourceSessionId.takeIf { stableDeviceId != null },
                nickname = identity.nickname.ifBlank { pairing?.remoteNickname.orEmpty() },
                deviceName = identity.deviceName.ifBlank { pairing?.deviceName.orEmpty() },
                protocolVersion = group.maxOf { it.candidate.identity.protocolVersion },
                lastSeenElapsedRealtimeMs = group.maxOf(TrackedCandidate::lastSeenElapsedRealtimeMs),
                candidates = group.map { tracked ->
                    PresenceTransportCandidate(
                        transport = tracked.candidate.transport,
                        endpointId = tracked.candidate.endpointId,
                        address = tracked.candidate.address,
                        port = tracked.candidate.port,
                        lastSeenElapsedRealtimeMs = tracked.lastSeenElapsedRealtimeMs,
                        isAvailable = tracked.expiresAtElapsedRealtimeMs == null
                    )
                }.sortedWith(
                    compareBy<PresenceTransportCandidate>({ it.transport.ordinal }, { it.endpointId })
                ),
                pairing = pairing
            )
        }.sortedWith(
            compareByDescending<RiderPresence>(RiderPresence::isPreferred)
                .thenByDescending(RiderPresence::isPaired)
                .thenBy { it.displayName.lowercase(Locale.ROOT) }
                .thenBy { it.deviceId.orEmpty() }
        )

        return PresenceSnapshot(
            presences = presences,
            nextExpiryElapsedRealtimeMs = candidates.values
                .mapNotNull(TrackedCandidate::expiresAtElapsedRealtimeMs)
                .minOrNull()
        )
    }

    companion object {
        const val DEFAULT_RETENTION_MS = 10_000L
    }
}
