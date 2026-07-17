package com.kuma.motointercom

internal class WifiDirectPeerRegistry {
    enum class GroupMatch { MATCHED, PENDING, REJECTED }

    data class Snapshot(
        val pending: Set<String>,
        val accepted: Set<String>
    )

    private val pending = linkedSetOf<String>()
    private val accepted = linkedSetOf<String>()

    @Synchronized
    fun reconcile(current: Set<String>): Snapshot {
        pending.retainAll(current)
        accepted.retainAll(current)
        return snapshot()
    }

    @Synchronized
    fun markPending(address: String): Snapshot {
        if (address !in accepted) pending += address
        return snapshot()
    }

    @Synchronized
    fun accept(address: String): Snapshot {
        pending -= address
        accepted += address
        return snapshot()
    }

    @Synchronized
    fun isAccepted(address: String): Boolean = address in accepted

    @Synchronized
    fun findAcceptedAddress(
        claims: Map<String, DiscoveryIdentityClaim>,
        targetLock: TargetLock
    ): String? = accepted.asSequence()
        .filter { claims[it]?.matches(targetLock) == true }
        .minOrNull()

    @Synchronized
    fun matchGroup(
        expectedRemoteAddress: String?,
        isGroupOwner: Boolean,
        owner: String?,
        clients: List<String>
    ): GroupMatch {
        val expected = expectedRemoteAddress ?: return GroupMatch.REJECTED
        val remote = (if (isGroupOwner) clients.singleOrNull() else owner)
            ?: return GroupMatch.REJECTED
        if (!expected.equals(remote, ignoreCase = true)) return GroupMatch.REJECTED
        return when {
            accepted.any { it.equals(remote, ignoreCase = true) } -> GroupMatch.MATCHED
            pending.any { it.equals(remote, ignoreCase = true) } -> GroupMatch.PENDING
            else -> GroupMatch.REJECTED
        }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        accepted.clear()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(pending.toSet(), accepted.toSet())
}
