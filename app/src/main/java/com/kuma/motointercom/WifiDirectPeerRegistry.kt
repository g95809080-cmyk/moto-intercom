package com.kuma.motointercom

internal class WifiDirectPeerRegistry {
    enum class GroupMatch { MATCHED, PENDING, REJECTED }

    data class Snapshot(
        val pending: Set<String>,
        val accepted: Set<String>,
        val selected: String?
    )

    private val pending = linkedSetOf<String>()
    private val accepted = linkedSetOf<String>()
    private var selected: String? = null

    @Synchronized
    fun reconcile(current: Set<String>): Snapshot {
        pending.retainAll(current)
        accepted.retainAll(current)
        if (selected !in accepted) selected = accepted.firstOrNull()
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
        if (selected == null) selected = address
        return snapshot()
    }

    @Synchronized
    fun isAccepted(address: String): Boolean = address in accepted

    @Synchronized
    fun matchGroup(isGroupOwner: Boolean, owner: String?, clients: List<String>): GroupMatch {
        val remote = (if (isGroupOwner) clients.singleOrNull() else owner)
            ?: return GroupMatch.REJECTED
        return when {
            selected?.equals(remote, ignoreCase = true) == true -> GroupMatch.MATCHED
            pending.any { it.equals(remote, ignoreCase = true) } -> GroupMatch.PENDING
            else -> GroupMatch.REJECTED
        }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        accepted.clear()
        selected = null
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(pending.toSet(), accepted.toSet(), selected)
}
