package com.kuma.motointercom.group

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal interface GroupRoomView {
    val key: GroupRoomKey
    val mode: GroupMode get() = GroupMode.LOCAL
    val ownerId: String
    val hostDeviceId: String?
    val ended: Boolean
    val maxMembers: Int get() = GroupRoom.MAX_MEMBERS
    val riderIncludesFormation: Boolean
    val members: List<GroupMember>
    val links: List<GroupLink>
    val privateInvites: List<GroupPrivateInvite>
    fun canEnterRider(role: GroupRole): Boolean = when (role) {
        GroupRole.OWNER, GroupRole.RIDING_OFFICER -> mode != GroupMode.NETWORK
        GroupRole.HEAD, GroupRole.TAIL -> mode != GroupMode.NETWORK && riderIncludesFormation
        GroupRole.MEMBER -> false
    }

    companion object {
        const val NETWORK_MAX_MEMBERS = 50
    }
}

/** Client data cannot become a writable authority room. Lists and confirmation sets are copied. */
internal class GroupRoster(
    override val key: GroupRoomKey,
    val hostId: String,
    val publication: Long,
    override val ended: Boolean,
    members: List<GroupMember>,
    links: List<GroupLink>,
    names: Map<String, String>,
    override val riderIncludesFormation: Boolean = true,
    privateInvites: List<GroupPrivateInvite> = emptyList()
) : GroupRoomView {
    override val mode: GroupMode get() = GroupMode.LOCAL
    override val ownerId: String get() = hostId
    override val hostDeviceId: String get() = hostId
    private val memberValues = members.toList()
    private val linkValues = links.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    private val nameValues = names.toMap()
    private val inviteValues = privateInvites.toList()
    override val members get() = memberValues.toList()
    override val links get() = linkValues.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    val names get() = nameValues.toMap()
    override val privateInvites get() = inviteValues.toList()
    init {
        require(publication > 0)
        listOf(key.instanceId, key.hostRuntimeId, hostId).forEach(::canonical)
        require(memberValues.size <= 16 && memberValues.map { it.lease.deviceId }.distinct().size == memberValues.size)
        require(memberValues.count { it.status != GroupMemberStatus.WAITING } <= 4)
        require(linkValues.size <= 6 && linkValues.map { it.lease.pair }.distinct().size == linkValues.size)
        require(names.keys.all { key -> memberValues.any { it.lease.deviceId == key } })
        names.values.forEach { require(it.length <= 64 && it.toByteArray(Charsets.UTF_8).size <= 64) }
        if (ended) require(memberValues.isEmpty() && linkValues.isEmpty() && inviteValues.isEmpty())
        else require(memberValues.any {
            it.lease.deviceId == hostId && it.lease.runtimeId == key.hostRuntimeId &&
                it.status == GroupMemberStatus.ADMITTED && it.role == GroupRole.OWNER
        })
        val occupied = memberValues.filter { it.status != GroupMemberStatus.WAITING }
        require(occupied.count { it.role == GroupRole.HEAD } <= 1)
        require(occupied.count { it.role == GroupRole.TAIL } <= 1)
        memberValues.forEach { member ->
            canonical(member.lease.deviceId); canonical(member.lease.runtimeId)
            require(member.lease.incarnation > 0 && member.lease.controlGeneration > 0)
            require(member.channelRevision > 0)
            require((member.status == GroupMemberStatus.RESERVED) == (member.reservedUntilMs != null))
            require(member.status == GroupMemberStatus.ADMITTED || !member.audioAvailable)
            require((member.lease.deviceId == hostId) == (member.role == GroupRole.OWNER))
            if (member.status == GroupMemberStatus.WAITING) {
                require(member.role == GroupRole.MEMBER && member.channel == GroupChannel.Public)
            }
            when (val ch = member.channel) {
                GroupChannel.Public -> Unit
                GroupChannel.Rider -> require(canEnterRider(member.role))
                is GroupChannel.Private -> canonical(ch.sessionId)
            }
        }
        occupied.filter { it.channel is GroupChannel.Private }.groupBy { it.channel }.forEach { (_, group) ->
            require(group.size == 2)
        }
        linkValues.forEach { link ->
            val first = memberValues.singleOrNull { it.lease.deviceId == link.lease.pair.first }
            val second = memberValues.singleOrNull { it.lease.deviceId == link.lease.pair.second }
            require(first?.status == GroupMemberStatus.ADMITTED && second?.status == GroupMemberStatus.ADMITTED)
            require(first.channel == second.channel)
            require(first.lease.incarnation == link.lease.firstIncarnation && second.lease.incarnation == link.lease.secondIncarnation && link.lease.generation > 0)
            require(link.confirmedBy.all { it == first.lease.deviceId || it == second.lease.deviceId })
        }
        require(inviteValues.size <= 2 && inviteValues.map { it.inviteId }.distinct().size == inviteValues.size)
        val invitedMembers = mutableSetOf<String>()
        inviteValues.forEach { invite ->
            canonical(invite.inviteId); canonical(invite.callerId); canonical(invite.calleeId)
            require(invitedMembers.add(invite.callerId) && invitedMembers.add(invite.calleeId))
            val caller = memberValues.singleOrNull { it.lease.deviceId == invite.callerId }
            val callee = memberValues.singleOrNull { it.lease.deviceId == invite.calleeId }
            require(caller?.status == GroupMemberStatus.ADMITTED && callee?.status == GroupMemberStatus.ADMITTED)
            require(caller.channel !is GroupChannel.Private && callee.channel !is GroupChannel.Private)
            require(occupied.none { it.channel == GroupChannel.Private(invite.inviteId) })
        }
    }
    companion object { private fun canonical(id: String) { require(UUID.fromString(id).toString() == id) } }
}

internal enum class GroupTermination { HOST_ENDED, REMOVED }
internal enum class GroupNoticeCode {
    CHANNEL_FORBIDDEN,
    PRIVATE_BUSY,
    PRIVATE_DECLINED,
    PRIVATE_CANCELLED,
    PRIVATE_TIMEOUT,
    PRIVATE_ENDED,
    PRIVATE_STALE,
    RIDER_REVOKED
}
internal sealed interface GroupControl {
    class Join(val nickname: String) : GroupControl
    class Welcome(val member: GroupMemberLease, val roster: GroupRoster) : GroupControl
    class Roster(val value: GroupRoster) : GroupControl
    class Signal(val frame: GroupFrame) : GroupControl
    class Refused(val result: GroupResult) : GroupControl
    class Terminated(val reason: GroupTermination) : GroupControl
    class Notice(val code: GroupNoticeCode, val peerId: String? = null) : GroupControl
}

internal object GroupControlCodec {
    private const val VERSION = 2
    fun encode(control: GroupControl, nowMs: Long): ByteArray = with(GroupBinary) {
        encode(65_535) {
            writeInt(0x4d434743); writeByte(VERSION)
            when (control) {
                is GroupControl.Join -> { writeByte(1); text(control.nickname, 64) }
                is GroupControl.Welcome -> { writeByte(2); member(control.member); roster(control.roster, nowMs) }
                is GroupControl.Roster -> { writeByte(3); roster(control.value, nowMs) }
                is GroupControl.Signal -> { writeByte(4); bytes(GroupWire.encode(control.frame), 65_520) }
                is GroupControl.Refused -> {
                    require(control.result in listOf(GroupResult.FULL, GroupResult.REMOVED, GroupResult.BUSY, GroupResult.ENDED, GroupResult.FORBIDDEN))
                    writeByte(5); writeByte(control.result.ordinal)
                }
                is GroupControl.Terminated -> { writeByte(6); writeByte(control.reason.ordinal) }
                is GroupControl.Notice -> {
                    writeByte(7); writeByte(control.code.ordinal)
                    writeBoolean(control.peerId != null)
                    control.peerId?.let { uuid(it) }
                }
            }
        }
    }
    fun decode(bytes: ByteArray, nowMs: Long): GroupControl = with(GroupBinary) {
        decode(bytes, 65_535) {
            require(nowMs >= 0 && nowMs <= Long.MAX_VALUE - GroupRoom.RESERVATION_MS)
            require(readInt() == 0x4d434743 && readUnsignedByte() == VERSION)
            when (readUnsignedByte()) {
                1 -> GroupControl.Join(text(64))
                2 -> GroupControl.Welcome(member(), roster(nowMs))
                3 -> GroupControl.Roster(roster(nowMs))
                4 -> GroupControl.Signal(GroupWire.decode(bytes(65_520)))
                5 -> GroupControl.Refused(GroupResult.entries[readUnsignedByte()].also {
                    require(it in listOf(GroupResult.FULL, GroupResult.REMOVED, GroupResult.BUSY, GroupResult.ENDED, GroupResult.FORBIDDEN))
                })
                6 -> GroupControl.Terminated(GroupTermination.entries[readUnsignedByte()])
                7 -> {
                    val code = GroupNoticeCode.entries[readUnsignedByte()]
                    val peerId = if (boolean()) uuid() else null
                    GroupControl.Notice(code, peerId)
                }
                else -> error("Unknown room control")
            }
        }
    }
    private fun DataOutputStream.member(value: GroupMemberLease) = with(GroupBinary) {
        uuid(value.deviceId); uuid(value.runtimeId)
        require(value.incarnation > 0 && value.controlGeneration > 0)
        writeLong(value.incarnation); writeLong(value.controlGeneration)
    }
    private fun DataInputStream.member(): GroupMemberLease = with(GroupBinary) {
        GroupMemberLease(uuid(), uuid(), readLong().also { require(it > 0) }, readLong().also { require(it > 0) })
    }
    private fun DataOutputStream.roster(value: GroupRoster, nowMs: Long) = with(GroupBinary) {
        room(value.key); uuid(value.hostId); writeLong(value.publication); writeBoolean(value.ended)
        writeBoolean(value.riderIncludesFormation)
        writeByte(value.members.size)
        value.members.forEach { item ->
            member(item.lease); writeByte(item.status.ordinal); writeBoolean(item.audioAvailable)
            if (item.status == GroupMemberStatus.RESERVED) {
                writeInt((checkNotNull(item.reservedUntilMs) - nowMs).coerceIn(0, GroupRoom.RESERVATION_MS).toInt())
            }
            writeByte(item.role.ordinal)
            when (val ch = item.channel) {
                GroupChannel.Public -> writeByte(0)
                GroupChannel.Rider -> writeByte(1)
                is GroupChannel.Private -> { writeByte(2); uuid(ch.sessionId) }
            }
            require(item.channelRevision > 0)
            writeLong(item.channelRevision)
            text(value.names[item.lease.deviceId].orEmpty(), 64)
        }
        writeByte(value.links.size)
        value.links.forEach { item ->
            uuid(item.lease.pair.first); uuid(item.lease.pair.second)
            writeLong(item.lease.firstIncarnation); writeLong(item.lease.secondIncarnation); writeLong(item.lease.generation)
            writeByte((if (item.lease.pair.first in item.confirmedBy) 1 else 0) or (if (item.lease.pair.second in item.confirmedBy) 2 else 0))
        }
        writeByte(value.privateInvites.size)
        value.privateInvites.forEach { invite ->
            uuid(invite.inviteId); uuid(invite.callerId); uuid(invite.calleeId)
            writeInt((invite.expiresAtMs - nowMs).coerceIn(0, GroupRoom.PRIVATE_INVITE_TIMEOUT_MS).toInt())
        }
    }
    private fun DataInputStream.roster(nowMs: Long): GroupRoster = with(GroupBinary) {
        val key = room(); val host = uuid(); val serial = readLong(); val ended = boolean()
        val riderIncludesFormation = boolean()
        val count = readUnsignedByte().also { require(it <= 16) }
        val names = mutableMapOf<String, String>()
        val members = List(count) {
            val lease = member()
            val status = GroupMemberStatus.entries[readUnsignedByte()]
            val audio = boolean()
            val reserved = if (status == GroupMemberStatus.RESERVED) nowMs + readInt().also { require(it in 0..60_000) } else null
            val role = GroupRole.entries[readUnsignedByte()]
            val channel = when (readUnsignedByte()) {
                0 -> GroupChannel.Public
                1 -> GroupChannel.Rider
                2 -> GroupChannel.Private(uuid())
                else -> error("Unknown channel")
            }
            val channelRevision = readLong().also { require(it > 0) }
            names[lease.deviceId] = text(64)
            GroupMember(lease, status, reserved, audio, role, channel, channelRevision)
        }
        val linkCount = readUnsignedByte().also { require(it <= 6) }
        val links = List(linkCount) {
            val pair = GroupPair(uuid(), uuid())
            val link = GroupLinkLease(pair, readLong(), readLong(), readLong())
            val flags = readUnsignedByte().also { require(it in 0..3) }
            GroupLink(link, buildSet { if (flags and 1 != 0) add(pair.first); if (flags and 2 != 0) add(pair.second) })
        }
        val inviteCount = readUnsignedByte().also { require(it <= 2) }
        val invites = List(inviteCount) {
            val inviteId = uuid(); val caller = uuid(); val callee = uuid()
            val remaining = readInt().also { require(it in 0..GroupRoom.PRIVATE_INVITE_TIMEOUT_MS.toInt()) }
            GroupPrivateInvite(inviteId, caller, callee, nowMs + remaining)
        }
        GroupRoster(key, host, serial, ended, members, links, names, riderIncludesFormation, invites)
    }
    private fun DataInputStream.boolean() = readUnsignedByte().also { require(it <= 1) } == 1
}

/** Per-client replay gate for host-originated and host-forwarded media. No room state mutation. */
internal class GroupClientIngress(
    private val intent: GroupIntentToken,
    private val local: GroupMemberLease,
    private val last: Map<GroupMemberLease, Long> = emptyMap()
) {
    fun accept(frame: GroupFrame, view: GroupRoomView, participation: GroupParticipation): GroupClientIngress? {
        if (!participation.isCurrent(intent) || view.key != intent.room || view.ended || frame.room != view.key ||
            frame.recipient != local || frame.sequence <= (last[frame.sender] ?: 0L)) return null
        val admittedMembers = view.members.filter { it.status == GroupMemberStatus.ADMITTED }
        val members = admittedMembers.map { it.lease }
        val localMember = admittedMembers.singleOrNull { it.lease == local } ?: return null
        val senderMember = admittedMembers.singleOrNull { it.lease == frame.sender } ?: return null
        if (frame.sender.deviceId == local.deviceId || localMember.channel != senderMember.channel) return null
        val link = when (val message = frame.message) {
            is GroupMessage.Offer -> if (frame.sender.deviceId < local.deviceId) message.link else return null
            is GroupMessage.Answer -> if (frame.sender.deviceId > local.deviceId) message.link else return null
            is GroupMessage.Candidate -> message.link
            else -> return null
        }
        if (link.pair != GroupPair.of(local.deviceId, frame.sender.deviceId) || view.links.none { it.lease == link }) return null
        if (members.single { it.deviceId == link.pair.first }.incarnation != link.firstIncarnation ||
            members.single { it.deviceId == link.pair.second }.incarnation != link.secondIncarnation) return null
        return GroupClientIngress(intent, local, last.filterKeys { it in members } + (frame.sender to frame.sequence))
    }
}
