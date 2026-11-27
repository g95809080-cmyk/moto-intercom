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
internal enum class GroupRole { OWNER, RIDING_OFFICER, HEAD, TAIL, MEMBER }
internal enum class GroupMode { LOCAL, NETWORK, HYBRID }
internal enum class GroupTransport { LOCAL, CLOUD }
internal data class GroupJoinRequest(
    val requestId: String,
    val deviceId: String,
    val nickname: String,
    val expiresAtMs: Long
) {
    init { require(requestId.isNotBlank() && deviceId.isNotBlank()) }
}
internal sealed interface GroupChannel {
    data object Public : GroupChannel
    data object Rider : GroupChannel
    data class Private(val sessionId: String) : GroupChannel {
        init { require(sessionId.isNotBlank()) }
    }
}
internal data class GroupPrivateInvite(
    val inviteId: String,
    val callerId: String,
    val calleeId: String,
    val expiresAtMs: Long
) {
    init {
        require(inviteId.isNotBlank() && callerId.isNotBlank() && calleeId.isNotBlank() && callerId != calleeId)
    }
    fun involves(deviceId: String) = callerId == deviceId || calleeId == deviceId
}
internal data class GroupMember(
    val lease: GroupMemberLease,
    val status: GroupMemberStatus = GroupMemberStatus.ADMITTED,
    val reservedUntilMs: Long? = null,
    val audioAvailable: Boolean = false,
    val role: GroupRole = GroupRole.MEMBER,
    val channel: GroupChannel = GroupChannel.Public,
    val channelRevision: Long = 1,
    val rtcUserId: String = lease.deviceId,
    val supportsFastP2p: Boolean = false
)
internal fun List<GroupMember>.allowedRtcUsers(blockedDeviceIds: Set<String>): Set<String> =
    filter { it.status == GroupMemberStatus.ADMITTED && it.lease.deviceId !in blockedDeviceIds }
        .mapTo(mutableSetOf()) { it.rtcUserId }

internal fun List<GroupMember>.speakingDevices(rtcUserIds: Set<String>): Set<String> =
    filter { it.status == GroupMemberStatus.ADMITTED && it.rtcUserId in rtcUserIds }.mapTo(mutableSetOf()) { it.lease.deviceId }

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
    data class SetRole(val actor: GroupMemberLease, val deviceId: String, val role: GroupRole) : GroupEvent
    data class SetRiderIncludesFormation(val actor: GroupMemberLease, val included: Boolean) : GroupEvent
    data class SwitchChannel(val actor: GroupMemberLease, val channel: GroupChannel) : GroupEvent
    data class RequestPrivate(val actor: GroupMemberLease, val targetDeviceId: String, val inviteId: String) : GroupEvent
    data class AcceptPrivate(val actor: GroupMemberLease, val inviteId: String) : GroupEvent
    data class DeclinePrivate(val actor: GroupMemberLease, val inviteId: String) : GroupEvent
    data class CancelPrivate(val actor: GroupMemberLease, val inviteId: String) : GroupEvent
    data class EndPrivate(val actor: GroupMemberLease) : GroupEvent
    data object Tick : GroupEvent
}

/** Immutable reducer. The single product writer must serialize events and commit each returned room. */
internal class GroupRoom private constructor(
    override val key: GroupRoomKey,
    val joinCode: GroupJoinCode,
    val hostId: String,
    val rosterRevision: Long,
    override val ended: Boolean,
    override val riderIncludesFormation: Boolean,
    private val membersById: Map<String, GroupMember>,
    private val removed: Set<String>,
    private val linksByPair: Map<GroupPair, GroupLink>,
    private val invitesById: Map<String, GroupPrivateInvite>,
    private val requests: Map<RequestKey, Receipt>,
    private val nextIncarnation: Long,
    private val nextLinkGeneration: Long,
    private val lastTimeMs: Long
) : GroupRoomView {
    override val mode: GroupMode get() = GroupMode.LOCAL
    override val ownerId: String get() = hostId
    override val hostDeviceId: String get() = hostId
    private data class RequestKey(val deviceId: String, val runtimeId: String, val requestId: String)
    private data class Receipt(val lease: GroupMemberLease, val expiresAtMs: Long)

    override val members: List<GroupMember> get() = membersById.values.toList()
    val removedDeviceIds: Set<String> get() = removed.toSet()
    val occupiedSeats: Int get() = members.count { it.status != GroupMemberStatus.WAITING }
    override val links: List<GroupLink> get() = linksByPair.values.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    override val privateInvites: List<GroupPrivateInvite> get() = invitesById.values.toList()
    val activePairs: Set<GroupPair> get() = members
        .filter { it.status == GroupMemberStatus.ADMITTED }
        .groupBy { it.channel }
        .values
        .flatMap { group ->
            val ids = group.map { it.lease.deviceId }.sorted()
            ids.flatMapIndexed { index, a -> ids.drop(index + 1).map { b -> GroupPair(a, b) } }
        }
        .toSet()
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
        fun changeMember(
            member: GroupMember,
            clearLinks: Boolean = true,
            invites: Map<String, GroupPrivateInvite> = invitesById
        ): GroupTransition =
            GroupTransition(copy(
                members = membersById + (member.lease.deviceId to member),
                links = if (clearLinks) withoutLinks(member.lease.deviceId) else linksByPair,
                invites = invites,
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
                        val nextRole = if (released) GroupRole.MEMBER else member.role
                        val nextChannel = if (released) GroupChannel.Public else preservedChannel(member, nextRole)
                        val nextChannelRev = if (released || nextChannel != member.channel) member.channelRevision + 1 else member.channelRevision
                        val changed = changeMember(
                            GroupMember(lease, role = nextRole, channel = nextChannel, channelRevision = nextChannelRev)
                        )
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
                    else -> changeMember(
                        member.copy(status = GroupMemberStatus.RESERVED, reservedUntilMs = now + RESERVATION_MS, audioAvailable = false),
                        invites = invitesById.filterValues { !it.involves(member.lease.deviceId) }
                    )
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
                    invites = emptyMap(), receipts = emptyMap(), blocked = emptySet(), revision = rosterRevision + 1), GroupResult.OK)
            is GroupEvent.AudioAvailability -> {
                val member = membersById[event.lease.deviceId]
                if (!valid(event.lease) || member?.status != GroupMemberStatus.ADMITTED) result(GroupResult.STALE)
                else changeMember(member.copy(audioAvailable = event.available), clearLinks = !event.available)
            }
            is GroupEvent.RestartLink -> {
                val local = membersById[event.actor.deviceId]
                val remote = membersById[event.peer.deviceId]
                if (!valid(event.actor) || local?.status != GroupMemberStatus.ADMITTED ||
                    remote?.status != GroupMemberStatus.ADMITTED || !valid(event.peer) ||
                    event.peer.deviceId == event.actor.deviceId || local.channel != remote.channel) {
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
            is GroupEvent.SetRole -> {
                if (!host(event.actor) || event.deviceId == hostId || event.role == GroupRole.OWNER) result(GroupResult.FORBIDDEN)
                else {
                    val target = membersById[event.deviceId]
                    if (target == null || target.status == GroupMemberStatus.WAITING) result(GroupResult.STALE)
                    else if (target.role == event.role) result(GroupResult.OK, target.lease)
                    else {
                        val updated = membersById.toMutableMap()
                        var nextLinks = linksByPair
                        if (event.role == GroupRole.HEAD || event.role == GroupRole.TAIL) {
                            updated.values.filter { it.lease.deviceId != event.deviceId && it.role == event.role }.forEach { other ->
                                val kickedFromRider = other.channel == GroupChannel.Rider && !canEnterRider(GroupRole.MEMBER)
                                updated[other.lease.deviceId] = other.copy(
                                    role = GroupRole.MEMBER,
                                    channel = if (kickedFromRider) GroupChannel.Public else other.channel,
                                    channelRevision = other.channelRevision + if (kickedFromRider) 1 else 0
                                )
                                if (kickedFromRider) nextLinks = withoutLinks(nextLinks, other.lease.deviceId)
                            }
                        }
                        val demotedFromRider = target.channel == GroupChannel.Rider && !canEnterRider(event.role)
                        updated[event.deviceId] = target.copy(
                            role = event.role,
                            channel = if (demotedFromRider) GroupChannel.Public else target.channel,
                            channelRevision = target.channelRevision + if (demotedFromRider) 1 else 0
                        )
                        if (demotedFromRider) nextLinks = withoutLinks(nextLinks, event.deviceId)
                        GroupTransition(copy(members = updated.toMap(), links = nextLinks, revision = rosterRevision + 1), GroupResult.OK, target.lease)
                    }
                }
            }
            is GroupEvent.SetRiderIncludesFormation -> {
                if (!host(event.actor)) result(GroupResult.FORBIDDEN)
                else if (riderIncludesFormation == event.included) result(GroupResult.OK)
                else {
                    val updated = membersById.toMutableMap()
                    var nextLinks = linksByPair
                    if (!event.included) {
                        updated.values.filter { (it.role == GroupRole.HEAD || it.role == GroupRole.TAIL) && it.channel == GroupChannel.Rider }.forEach { member ->
                            updated[member.lease.deviceId] = member.copy(channel = GroupChannel.Public, channelRevision = member.channelRevision + 1)
                            nextLinks = withoutLinks(nextLinks, member.lease.deviceId)
                        }
                    }
                    GroupTransition(copy(members = updated.toMap(), links = nextLinks, includesFormation = event.included,
                        revision = rosterRevision + 1), GroupResult.OK)
                }
            }
            is GroupEvent.SwitchChannel -> {
                val member = membersById[event.actor.deviceId]
                when {
                    !valid(event.actor) || member == null || member.status != GroupMemberStatus.ADMITTED -> result(GroupResult.STALE)
                    event.channel is GroupChannel.Private -> result(GroupResult.FORBIDDEN)
                    event.channel == GroupChannel.Rider && !canEnterRider(member.role) -> result(GroupResult.FORBIDDEN)
                    member.channel == event.channel -> result(GroupResult.OK, member.lease)
                    else -> {
                        val updated = membersById.toMutableMap()
                        var nextLinks = linksByPair
                        val oldChannel = member.channel
                        if (oldChannel is GroupChannel.Private) {
                            nextLinks = closePrivateSession(updated, nextLinks, oldChannel.sessionId)
                            val moved = updated.getValue(member.lease.deviceId)
                            if (event.channel != GroupChannel.Public) {
                                updated[member.lease.deviceId] = moved.copy(channel = event.channel)
                            }
                        } else {
                            updated[member.lease.deviceId] = member.copy(channel = event.channel, channelRevision = member.channelRevision + 1)
                            nextLinks = withoutLinks(nextLinks, member.lease.deviceId)
                        }
                        GroupTransition(copy(members = updated.toMap(), links = nextLinks, revision = rosterRevision + 1), GroupResult.OK, member.lease)
                    }
                }
            }
            is GroupEvent.RequestPrivate -> {
                val caller = membersById[event.actor.deviceId]
                val callee = membersById[event.targetDeviceId]
                when {
                    event.inviteId.isBlank() || event.targetDeviceId.isBlank() || event.targetDeviceId == event.actor.deviceId -> result(GroupResult.FORBIDDEN)
                    !valid(event.actor) || caller?.status != GroupMemberStatus.ADMITTED || callee?.status != GroupMemberStatus.ADMITTED -> result(GroupResult.STALE)
                    invitesById[event.inviteId]?.let { it.callerId == event.actor.deviceId && it.calleeId == event.targetDeviceId } == true -> result(GroupResult.OK, caller.lease)
                    invitesById.containsKey(event.inviteId) || membersById.values.any { it.channel == GroupChannel.Private(event.inviteId) } -> result(GroupResult.STALE)
                    caller.channel is GroupChannel.Private || callee.channel is GroupChannel.Private -> result(GroupResult.BUSY)
                    invitesById.values.any { it.involves(caller.lease.deviceId) || it.involves(callee.lease.deviceId) } -> result(GroupResult.BUSY)
                    else -> {
                        val invite = GroupPrivateInvite(event.inviteId, caller.lease.deviceId, callee.lease.deviceId, now + PRIVATE_INVITE_TIMEOUT_MS)
                        GroupTransition(copy(invites = invitesById + (invite.inviteId to invite), revision = rosterRevision + 1), GroupResult.OK, caller.lease)
                    }
                }
            }
            is GroupEvent.AcceptPrivate -> {
                val callee = membersById[event.actor.deviceId]
                val invite = invitesById[event.inviteId]
                when {
                    !valid(event.actor) || callee?.status != GroupMemberStatus.ADMITTED || invite == null -> result(GroupResult.STALE)
                    invite.calleeId != event.actor.deviceId -> result(GroupResult.FORBIDDEN)
                    else -> {
                        val caller = membersById[invite.callerId]
                        if (caller?.status != GroupMemberStatus.ADMITTED) {
                            GroupTransition(copy(invites = invitesById - invite.inviteId, revision = rosterRevision + 1), GroupResult.STALE)
                        } else if (caller.channel is GroupChannel.Private || callee.channel is GroupChannel.Private) {
                            GroupTransition(copy(invites = invitesById - invite.inviteId, revision = rosterRevision + 1), GroupResult.BUSY)
                        } else {
                            val privateChannel = GroupChannel.Private(invite.inviteId)
                            val updated = membersById +
                                (caller.lease.deviceId to caller.copy(channel = privateChannel, channelRevision = caller.channelRevision + 1)) +
                                (callee.lease.deviceId to callee.copy(channel = privateChannel, channelRevision = callee.channelRevision + 1))
                            val nextLinks = withoutLinks(withoutLinks(linksByPair, caller.lease.deviceId), callee.lease.deviceId)
                            GroupTransition(copy(members = updated, links = nextLinks, invites = invitesById - invite.inviteId,
                                revision = rosterRevision + 1), GroupResult.OK, callee.lease)
                        }
                    }
                }
            }
            is GroupEvent.DeclinePrivate -> {
                val callee = membersById[event.actor.deviceId]
                val invite = invitesById[event.inviteId]
                when {
                    !valid(event.actor) || callee?.status != GroupMemberStatus.ADMITTED || invite == null -> result(GroupResult.STALE)
                    invite.calleeId != event.actor.deviceId -> result(GroupResult.FORBIDDEN)
                    else -> GroupTransition(copy(invites = invitesById - invite.inviteId, revision = rosterRevision + 1), GroupResult.OK, callee.lease)
                }
            }
            is GroupEvent.CancelPrivate -> {
                val caller = membersById[event.actor.deviceId]
                val invite = invitesById[event.inviteId]
                when {
                    !valid(event.actor) || caller?.status != GroupMemberStatus.ADMITTED || invite == null -> result(GroupResult.STALE)
                    invite.callerId != event.actor.deviceId -> result(GroupResult.FORBIDDEN)
                    else -> GroupTransition(copy(invites = invitesById - invite.inviteId, revision = rosterRevision + 1), GroupResult.OK, caller.lease)
                }
            }
            is GroupEvent.EndPrivate -> {
                val member = membersById[event.actor.deviceId]
                val currentChannel = member?.channel
                if (!valid(event.actor) || member?.status != GroupMemberStatus.ADMITTED || currentChannel !is GroupChannel.Private) {
                    result(GroupResult.STALE)
                } else {
                    val updated = membersById.toMutableMap()
                    val nextLinks = closePrivateSession(updated, linksByPair, currentChannel.sessionId)
                    GroupTransition(copy(members = updated.toMap(), links = nextLinks, revision = rosterRevision + 1), GroupResult.OK, member.lease)
                }
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
        val updated = membersById.toMutableMap()
        var nextLinks = linksByPair
        val nextInvites = invitesById.filterValues { !it.involves(proof.deviceId) }
        val joinedMember = if (freshMembership) {
            val oldChannel = old?.channel
            if (oldChannel is GroupChannel.Private) nextLinks = closePrivateSession(updated, nextLinks, oldChannel.sessionId)
            GroupMember(lease, role = GroupRole.MEMBER, channel = GroupChannel.Public, channelRevision = (old?.channelRevision ?: 0) + 1)
        } else {
            val preservedRole = old!!.role
            val nextChannel = preservedChannel(old, preservedRole)
            val nextChannelRev = if (nextChannel == old.channel) old.channelRevision else old.channelRevision + 1
            GroupMember(lease, role = preservedRole, channel = nextChannel, channelRevision = nextChannelRev)
        }
        updated[proof.deviceId] = joinedMember
        nextLinks = withoutLinks(nextLinks, proof.deviceId)
        return GroupTransition(copy(members = updated.toMap(),
            links = nextLinks, invites = nextInvites, receipts = receipts.toMap(),
            revision = rosterRevision + 1, next = nextIncarnation + if (freshMembership) 1 else 0), GroupResult.OK, lease)
    }

    private fun expire(now: Long): GroupRoom {
        var changed = false
        val updated = membersById.toMutableMap()
        var nextLinks = linksByPair
        var nextInvites = invitesById
        val expiredIds = updated.values
            .filter { it.status == GroupMemberStatus.RESERVED && now >= requireNotNull(it.reservedUntilMs) }
            .map { it.lease.deviceId }
        expiredIds.forEach { deviceId ->
            changed = true
            val member = updated.getValue(deviceId)
            val channel = member.channel
            if (channel is GroupChannel.Private) nextLinks = closePrivateSession(updated, nextLinks, channel.sessionId)
            val current = updated.getValue(deviceId)
            updated[deviceId] = current.copy(
                status = GroupMemberStatus.WAITING,
                reservedUntilMs = null,
                audioAvailable = false,
                role = GroupRole.MEMBER,
                channel = GroupChannel.Public,
                channelRevision = current.channelRevision + if (current.channel == GroupChannel.Public) 0 else 1
            )
            nextLinks = withoutLinks(nextLinks, deviceId)
            nextInvites = nextInvites.filterValues { !it.involves(deviceId) }
        }
        val activeInvites = nextInvites.filterValues { now < it.expiresAtMs }
        if (activeInvites.size != nextInvites.size) changed = true
        return copy(members = updated.toMap(), links = nextLinks, invites = activeInvites,
            revision = rosterRevision + if (changed) 1 else 0,
            receipts = requests.filterValues { it.expiresAtMs > now }, time = now)
    }

    private fun preservedChannel(member: GroupMember, role: GroupRole): GroupChannel = when (val ch = member.channel) {
        GroupChannel.Public -> GroupChannel.Public
        GroupChannel.Rider -> if (canEnterRider(role)) GroupChannel.Rider else GroupChannel.Public
        is GroupChannel.Private -> if (membersById.values.any {
            it.lease.deviceId != member.lease.deviceId && it.status != GroupMemberStatus.WAITING && it.channel == ch
        }) ch else GroupChannel.Public
    }

    private fun closePrivateSession(
        members: MutableMap<String, GroupMember>,
        links: Map<GroupPair, GroupLink>,
        sessionId: String
    ): Map<GroupPair, GroupLink> {
        var nextLinks = links
        val target = GroupChannel.Private(sessionId)
        members.values.filter { it.channel == target }.forEach { participant ->
            val id = participant.lease.deviceId
            members[id] = participant.copy(channel = GroupChannel.Public, channelRevision = participant.channelRevision + 1)
            nextLinks = withoutLinks(nextLinks, id)
        }
        return nextLinks
    }

    private fun evict(deviceId: String, block: Boolean): GroupTransition {
        val updated = membersById.toMutableMap()
        var nextLinks = linksByPair
        val oldChannel = updated[deviceId]?.channel
        if (oldChannel is GroupChannel.Private) nextLinks = closePrivateSession(updated, nextLinks, oldChannel.sessionId)
        updated.remove(deviceId)
        nextLinks = withoutLinks(nextLinks, deviceId)
        val nextInvites = invitesById.filterValues { !it.involves(deviceId) }
        return GroupTransition(copy(
            members = updated.toMap(), blocked = if (block) removed + deviceId else removed,
            links = nextLinks, invites = nextInvites, revision = rosterRevision + 1
        ), GroupResult.OK)
    }

    private fun withoutLinks(deviceId: String) = withoutLinks(linksByPair, deviceId)
    private fun withoutLinks(links: Map<GroupPair, GroupLink>, deviceId: String) =
        links.filterKeys { it.first != deviceId && it.second != deviceId }

    private fun copy(
        members: Map<String, GroupMember> = membersById,
        blocked: Set<String> = removed,
        links: Map<GroupPair, GroupLink> = linksByPair,
        invites: Map<String, GroupPrivateInvite> = invitesById,
        receipts: Map<RequestKey, Receipt> = requests,
        revision: Long = rosterRevision,
        next: Long = nextIncarnation,
        time: Long = lastTimeMs,
        linkNext: Long = nextLinkGeneration,
        isEnded: Boolean = ended,
        includesFormation: Boolean = riderIncludesFormation
    ) = GroupRoom(key, joinCode, hostId, revision, isEnded, includesFormation, members, blocked, links, invites, receipts, next, linkNext, time)

    companion object {
        const val MAX_MEMBERS = 4
        const val RESERVATION_MS = 60_000L
        const val PRIVATE_INVITE_TIMEOUT_MS = 30_000L
        private const val JOIN_PROOF_MS = 10_000L
        private const val RECEIPT_MS = 30_000L
        private const val MAX_RECEIPTS = 128
        fun create(hostId: String, hostRuntimeId: String, nowMs: Long = 0,
            code: GroupJoinCode = GroupJoinCode.generate(), instanceId: String = UUID.randomUUID().toString()): GroupRoom {
            require(hostId.isNotBlank() && hostRuntimeId.isNotBlank() && instanceId.isNotBlank())
            require(nowMs >= 0 && nowMs <= Long.MAX_VALUE - RESERVATION_MS)
            val host = GroupMember(GroupMemberLease(hostId, hostRuntimeId, 1, 1), role = GroupRole.OWNER)
            return GroupRoom(GroupRoomKey(instanceId, hostRuntimeId), code, hostId, 1, false, true,
                mapOf(hostId to host), emptySet(), emptyMap(), emptyMap(), emptyMap(), 2, 1, nowMs)
        }
    }
}
