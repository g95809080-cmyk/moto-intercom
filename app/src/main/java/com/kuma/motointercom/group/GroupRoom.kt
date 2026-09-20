package com.kuma.motointercom.group

import java.security.SecureRandom
import java.util.UUID

/** No wire protocol or authentication implementation. Only the trusted adapter may supply proofs. */
internal data class GroupJoinProof(
    val deviceId: String,
    val runtimeId: String,
    val requestId: String,
    val identityVerified: Boolean,
    val credentialVerified: Boolean,
    val versionSupported: Boolean,
    val expiresAtMs: Long
)

internal class GroupJoinCode(val digits: String) {
    init { require(digits.length == 6 && digits.all { it in '0'..'9' }) }
    override fun toString() = "GroupJoinCode(REDACTED)"
    companion object {
        fun generate(random: SecureRandom = SecureRandom()) =
            GroupJoinCode(random.nextInt(1_000_000).toString().padStart(6, '0'))
    }
}

internal data class GroupRoomKey(val instanceId: String, val hostRuntimeId: String)
internal data class GroupMemberLease(
    val deviceId: String,
    val runtimeId: String,
    val incarnation: Long,
    val controlGeneration: Long
)
internal enum class GroupMemberStatus { ADMITTED, RESERVED, WAITING }
internal data class GroupMember(
    val lease: GroupMemberLease,
    val status: GroupMemberStatus = GroupMemberStatus.ADMITTED,
    val reservedUntilMs: Long? = null,
    val audioAvailable: Boolean = false
)
internal data class GroupPair(val first: String, val second: String) {
    init { require(first < second) }
    companion object {
        fun of(a: String, b: String) = if (a < b) GroupPair(a, b) else GroupPair(b, a)
    }
}
internal data class GroupLinkLease(
    val pair: GroupPair,
    val firstIncarnation: Long,
    val secondIncarnation: Long,
    val generation: Long
)
internal data class GroupLink(val lease: GroupLinkLease, val confirmedBy: Set<String> = emptySet())
internal enum class GroupResult { OK, ENDED, WRONG_ROOM, UNVERIFIED, INCOMPATIBLE, REMOVED, FULL, BUSY, STALE, FORBIDDEN }
internal data class GroupTransition(val room: GroupRoom, val result: GroupResult, val member: GroupMemberLease? = null)

internal sealed interface GroupEvent {
    data class Join(val proof: GroupJoinProof) : GroupEvent
    data class Resume(val lease: GroupMemberLease) : GroupEvent
    data class Lost(val lease: GroupMemberLease) : GroupEvent
    data class Leave(val lease: GroupMemberLease) : GroupEvent
    data class Remove(val actor: GroupMemberLease, val deviceId: String) : GroupEvent
    data class Unblock(val actor: GroupMemberLease, val deviceId: String) : GroupEvent
    data class End(val actor: GroupMemberLease) : GroupEvent
    data class AudioAvailability(val lease: GroupMemberLease, val available: Boolean) : GroupEvent
    data class RestartLink(val actor: GroupMemberLease, val peer: GroupMemberLease,
        val expected: GroupLinkLease? = null) : GroupEvent
    data class ConfirmLink(val actor: GroupMemberLease, val lease: GroupLinkLease) : GroupEvent
    data object Tick : GroupEvent
}

/** Immutable reducer. The single product writer must serialize events and commit each returned room. */
internal class GroupRoom private constructor(
    override val key: GroupRoomKey,
    val joinCode: GroupJoinCode,
    override val hostId: String,
    val rosterRevision: Long,
    override val ended: Boolean,
    private val membersById: Map<String, GroupMember>,
    private val removed: Set<String>,
    private val linksByPair: Map<GroupPair, GroupLink>,
    private val requests: Map<RequestKey, Receipt>,
    private val nextIncarnation: Long,
    private val nextLinkGeneration: Long,
    private val lastTimeMs: Long
) : GroupRoomView {
    private data class RequestKey(val deviceId: String, val runtimeId: String, val requestId: String)
    private data class Receipt(val lease: GroupMemberLease, val expiresAtMs: Long)

    override val members: List<GroupMember> get() = membersById.values.toList()
    val removedDeviceIds: Set<String> get() = removed.toSet()
    val occupiedSeats: Int get() = members.count { it.status != GroupMemberStatus.WAITING }
    override val links: List<GroupLink> get() = linksByPair.values.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    val activePairs: Set<GroupPair> get() {
        val ids = members.filter { it.status == GroupMemberStatus.ADMITTED }.map { it.lease.deviceId }.sorted()
        return ids.flatMapIndexed { index, a -> ids.drop(index + 1).map { b -> GroupPair(a, b) } }.toSet()
    }
    val readyPairs: Set<GroupPair> get() = activePairs.filter { pair ->
        val link = linksByPair[pair]
        link != null && link.confirmedBy.containsAll(listOf(pair.first, pair.second)) &&
            membersById.getValue(pair.first).audioAvailable && membersById.getValue(pair.second).audioAvailable
    }.toSet()
    val allVoiceReady: Boolean get() = !ended && activePairs.isNotEmpty() &&
        members.none { it.status == GroupMemberStatus.RESERVED } && readyPairs == activePairs

    fun reduce(roomKey: GroupRoomKey, event: GroupEvent, nowMs: Long): GroupTransition {
        if (roomKey != key) return GroupTransition(this, GroupResult.WRONG_ROOM)
        if (ended) return GroupTransition(this, GroupResult.ENDED)
        require(nowMs >= lastTimeMs && nowMs <= Long.MAX_VALUE - RESERVATION_MS) { "monotonic time required" }
        val current = expire(nowMs)
        return current.handle(event, nowMs)
    }

    private fun handle(event: GroupEvent, now: Long): GroupTransition {
        fun result(value: GroupResult, lease: GroupMemberLease? = null) = GroupTransition(this, value, lease)
        fun valid(lease: GroupMemberLease) = membersById[lease.deviceId]?.lease == lease
        fun host(lease: GroupMemberLease) = lease.deviceId == hostId && valid(lease)
        fun changeMember(member: GroupMember, clearLinks: Boolean = true): GroupTransition =
            GroupTransition(copy(
                members = membersById + (member.lease.deviceId to member),
                links = if (clearLinks) withoutLinks(member.lease.deviceId) else linksByPair,
                revision = rosterRevision + 1
            ), GroupResult.OK, member.lease)

        return when (event) {
            is GroupEvent.Join -> join(event.proof, now)
            is GroupEvent.Resume -> {
                val member = membersById[event.lease.deviceId]
                when {
                    event.lease.deviceId == hostId -> result(GroupResult.FORBIDDEN)
                    !valid(event.lease) || member == null -> result(GroupResult.STALE)
                    member.status == GroupMemberStatus.ADMITTED -> result(GroupResult.OK, member.lease)
                    member.status == GroupMemberStatus.WAITING && occupiedSeats >= MAX_MEMBERS -> result(GroupResult.FULL)
                    else -> {
                        val released = member.status == GroupMemberStatus.WAITING
                        val lease = member.lease.copy(
                            incarnation = if (released) nextIncarnation else member.lease.incarnation,
                            controlGeneration = member.lease.controlGeneration + 1
                        )
                        val changed = changeMember(GroupMember(lease))
                        changed.copy(room = changed.room.copy(next = nextIncarnation + if (released) 1 else 0))
                    }
                }
            }
            is GroupEvent.Lost -> {
                val member = membersById[event.lease.deviceId]
                when {
                    !valid(event.lease) || member == null -> result(GroupResult.STALE)
                    event.lease.deviceId == hostId -> result(GroupResult.FORBIDDEN)
                    member.status != GroupMemberStatus.ADMITTED -> result(GroupResult.OK)
                    else -> changeMember(member.copy(status = GroupMemberStatus.RESERVED,
                        reservedUntilMs = now + RESERVATION_MS, audioAvailable = false))
                }
            }
            is GroupEvent.Leave -> when {
                !valid(event.lease) -> result(GroupResult.STALE)
                event.lease.deviceId == hostId -> result(GroupResult.FORBIDDEN)
                else -> evict(event.lease.deviceId, block = false)
            }
            is GroupEvent.Remove -> when {
                !host(event.actor) || event.deviceId == hostId -> result(GroupResult.FORBIDDEN)
                else -> evict(event.deviceId, block = true)
            }
            is GroupEvent.Unblock -> if (!host(event.actor)) result(GroupResult.FORBIDDEN) else
                GroupTransition(copy(blocked = removed - event.deviceId), GroupResult.OK)
            is GroupEvent.End -> if (!host(event.actor)) result(GroupResult.FORBIDDEN) else
                GroupTransition(copy(isEnded = true, members = emptyMap(), links = emptyMap(),
                    receipts = emptyMap(), blocked = emptySet(), revision = rosterRevision + 1), GroupResult.OK)
            is GroupEvent.AudioAvailability -> {
                val member = membersById[event.lease.deviceId]
                if (!valid(event.lease) || member?.status != GroupMemberStatus.ADMITTED) result(GroupResult.STALE)
                else changeMember(member.copy(audioAvailable = event.available), clearLinks = !event.available)
            }
            is GroupEvent.RestartLink -> {
                val local = membersById[event.actor.deviceId]
                val remote = membersById[event.peer.deviceId]
                if (!valid(event.actor) || local?.status != GroupMemberStatus.ADMITTED ||
                    remote?.status != GroupMemberStatus.ADMITTED || !valid(event.peer) || event.peer.deviceId == event.actor.deviceId) {
                    result(GroupResult.STALE)
                } else {
                    val pair = GroupPair.of(event.actor.deviceId, event.peer.deviceId)
                    if (linksByPair[pair]?.lease != event.expected) return result(GroupResult.STALE)
                    val lease = GroupLinkLease(pair, membersById.getValue(pair.first).lease.incarnation,
                        membersById.getValue(pair.second).lease.incarnation,
                        nextLinkGeneration)
                    GroupTransition(copy(links = linksByPair + (pair to GroupLink(lease)), linkNext = nextLinkGeneration + 1), GroupResult.OK)
                }
            }
            is GroupEvent.ConfirmLink -> {
                val pair = event.lease.pair
                val link = linksByPair[pair]
                if (!valid(event.actor) || event.actor.deviceId !in listOf(pair.first, pair.second) ||
                    link?.lease != event.lease || pair !in activePairs) result(GroupResult.STALE)
                else GroupTransition(copy(links = linksByPair + (pair to link.copy(
                    confirmedBy = link.confirmedBy + event.actor.deviceId))), GroupResult.OK)
            }
            GroupEvent.Tick -> result(GroupResult.OK)
        }
    }

    private fun join(proof: GroupJoinProof, now: Long): GroupTransition {
        fun deny(result: GroupResult) = GroupTransition(this, result)
        if (!proof.versionSupported) return deny(GroupResult.INCOMPATIBLE)
        if (!proof.identityVerified || !proof.credentialVerified || proof.expiresAtMs <= now ||
            proof.expiresAtMs > now + JOIN_PROOF_MS ||
            listOf(proof.deviceId, proof.runtimeId, proof.requestId).any { it.isBlank() }) return deny(GroupResult.UNVERIFIED)
        if (proof.deviceId == hostId) return deny(GroupResult.FORBIDDEN)
        if (proof.deviceId in removed) return deny(GroupResult.REMOVED)
        val request = RequestKey(proof.deviceId, proof.runtimeId, proof.requestId)
        val old = membersById[proof.deviceId]
        requests[request]?.let { receipt ->
            return if (old?.lease == receipt.lease) GroupTransition(this, GroupResult.OK, old.lease)
            else deny(GroupResult.STALE)
        }
        val hasSeat = old != null && old.status != GroupMemberStatus.WAITING
        if (!hasSeat && occupiedSeats >= MAX_MEMBERS) return deny(GroupResult.FULL)
        val freshMembership = old == null || old.lease.runtimeId != proof.runtimeId || old.status == GroupMemberStatus.WAITING
        val lease = GroupMemberLease(proof.deviceId, proof.runtimeId,
            if (freshMembership) nextIncarnation else old!!.lease.incarnation,
            (old?.lease?.controlGeneration ?: 0) + 1)
        val receipts = requests.toMutableMap()
        if (receipts.size >= MAX_RECEIPTS) return deny(GroupResult.BUSY)
        receipts[request] = Receipt(lease, now + RECEIPT_MS)
        return GroupTransition(copy(members = membersById + (proof.deviceId to GroupMember(lease)),
            links = withoutLinks(proof.deviceId), receipts = receipts.toMap(),
            revision = rosterRevision + 1, next = nextIncarnation + if (freshMembership) 1 else 0), GroupResult.OK, lease)
    }

    private fun expire(now: Long): GroupRoom {
        var changed = false
        val expired = membersById.mapValues { (_, member) ->
            if (member.status == GroupMemberStatus.RESERVED && now >= requireNotNull(member.reservedUntilMs)) {
                changed = true
                member.copy(status = GroupMemberStatus.WAITING, reservedUntilMs = null)
            } else member
        }
        return copy(members = expired, revision = rosterRevision + if (changed) 1 else 0,
            receipts = requests.filterValues { it.expiresAtMs > now }, time = now)
    }

    private fun evict(deviceId: String, block: Boolean) = GroupTransition(copy(
        members = membersById - deviceId, blocked = if (block) removed + deviceId else removed,
        links = withoutLinks(deviceId),
        revision = rosterRevision + 1
    ), GroupResult.OK)

    private fun withoutLinks(deviceId: String) = linksByPair.filterKeys { it.first != deviceId && it.second != deviceId }

    private fun copy(
        members: Map<String, GroupMember> = membersById,
        blocked: Set<String> = removed,
        links: Map<GroupPair, GroupLink> = linksByPair,
        receipts: Map<RequestKey, Receipt> = requests,
        revision: Long = rosterRevision,
        next: Long = nextIncarnation,
        time: Long = lastTimeMs,
        linkNext: Long = nextLinkGeneration,
        isEnded: Boolean = ended
    ) = GroupRoom(key, joinCode, hostId, revision, isEnded, members, blocked, links, receipts, next, linkNext, time)

    companion object {
        const val MAX_MEMBERS = 4
        const val RESERVATION_MS = 60_000L
        private const val JOIN_PROOF_MS = 10_000L
        private const val RECEIPT_MS = 30_000L
        private const val MAX_RECEIPTS = 128
        fun create(hostId: String, hostRuntimeId: String, nowMs: Long = 0,
            code: GroupJoinCode = GroupJoinCode.generate(), instanceId: String = UUID.randomUUID().toString()): GroupRoom {
            require(hostId.isNotBlank() && hostRuntimeId.isNotBlank() && instanceId.isNotBlank())
            require(nowMs >= 0 && nowMs <= Long.MAX_VALUE - RESERVATION_MS)
            val host = GroupMember(GroupMemberLease(hostId, hostRuntimeId, 1, 1))
            return GroupRoom(GroupRoomKey(instanceId, hostRuntimeId), code, hostId, 1, false,
                mapOf(hostId to host), emptySet(), emptyMap(), emptyMap(), 2, 1, nowMs)
        }
    }
}
