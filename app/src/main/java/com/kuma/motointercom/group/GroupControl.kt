package com.kuma.motointercom.group

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal interface GroupRoomView {
    val key: GroupRoomKey
    val hostId: String
    val ended: Boolean
    val members: List<GroupMember>
    val links: List<GroupLink>
}

/** Client data cannot become a writable authority room. Lists and confirmation sets are copied. */
internal class GroupRoster(
    override val key: GroupRoomKey,
    override val hostId: String,
    val publication: Long,
    override val ended: Boolean,
    members: List<GroupMember>,
    links: List<GroupLink>,
    names: Map<String, String>
) : GroupRoomView {
    private val memberValues = members.toList()
    private val linkValues = links.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    private val nameValues = names.toMap()
    override val members get() = memberValues.toList()
    override val links get() = linkValues.map { it.copy(confirmedBy = it.confirmedBy.toSet()) }
    val names get() = nameValues.toMap()
    init {
        require(publication > 0)
        listOf(key.instanceId, key.hostRuntimeId, hostId).forEach(::canonical)
        require(memberValues.size <= 16 && memberValues.map { it.lease.deviceId }.distinct().size == memberValues.size)
        require(memberValues.count { it.status != GroupMemberStatus.WAITING } <= 4)
        require(linkValues.size <= 6 && linkValues.map { it.lease.pair }.distinct().size == linkValues.size)
        require(names.keys.all { key -> memberValues.any { it.lease.deviceId == key } })
        names.values.forEach { require(it.length <= 64 && it.toByteArray(Charsets.UTF_8).size <= 64) }
        if (ended) require(memberValues.isEmpty() && linkValues.isEmpty())
        else require(memberValues.any { it.lease.deviceId == hostId && it.lease.runtimeId == key.hostRuntimeId && it.status == GroupMemberStatus.ADMITTED })
        memberValues.forEach { member ->
            canonical(member.lease.deviceId); canonical(member.lease.runtimeId)
            require(member.lease.incarnation > 0 && member.lease.controlGeneration > 0)
            require((member.status == GroupMemberStatus.RESERVED) == (member.reservedUntilMs != null))
            require(member.status == GroupMemberStatus.ADMITTED || !member.audioAvailable)
        }
        linkValues.forEach { link ->
            val first = memberValues.singleOrNull { it.lease.deviceId == link.lease.pair.first }
            val second = memberValues.singleOrNull { it.lease.deviceId == link.lease.pair.second }
            require(first?.status == GroupMemberStatus.ADMITTED && second?.status == GroupMemberStatus.ADMITTED)
            require(first.lease.incarnation == link.lease.firstIncarnation && second.lease.incarnation == link.lease.secondIncarnation && link.lease.generation > 0)
            require(link.confirmedBy.all { it == first.lease.deviceId || it == second.lease.deviceId })
        }
    }
    companion object { private fun canonical(id: String) { require(UUID.fromString(id).toString() == id) } }
}

internal enum class GroupTermination { HOST_ENDED, REMOVED }
internal sealed interface GroupControl {
    class Join(val nickname: String) : GroupControl
    class Welcome(val member: GroupMemberLease, val roster: GroupRoster) : GroupControl
    class Roster(val value: GroupRoster) : GroupControl
    class Signal(val frame: GroupFrame) : GroupControl
    class Refused(val result: GroupResult) : GroupControl
    class Terminated(val reason: GroupTermination) : GroupControl
}

internal object GroupControlCodec {
    fun encode(control: GroupControl, nowMs: Long): ByteArray = with(GroupBinary) {
        encode(65_535) {
            writeInt(0x4d434743); writeByte(1)
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
            }
        }
    }
    fun decode(bytes: ByteArray, nowMs: Long): GroupControl = with(GroupBinary) {
        decode(bytes, 65_535) {
            require(nowMs >= 0 && nowMs <= Long.MAX_VALUE - GroupRoom.RESERVATION_MS)
            require(readInt() == 0x4d434743 && readUnsignedByte() == 1)
            when (readUnsignedByte()) {
                1 -> GroupControl.Join(text(64))
                2 -> GroupControl.Welcome(member(), roster(nowMs))
                3 -> GroupControl.Roster(roster(nowMs))
                4 -> GroupControl.Signal(GroupWire.decode(bytes(65_520)))
                5 -> GroupControl.Refused(GroupResult.entries[readUnsignedByte()].also {
                    require(it in listOf(GroupResult.FULL, GroupResult.REMOVED, GroupResult.BUSY, GroupResult.ENDED, GroupResult.FORBIDDEN))
                })
                6 -> GroupControl.Terminated(GroupTermination.entries[readUnsignedByte()])
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
        writeByte(value.members.size)
        value.members.forEach { item ->
            member(item.lease); writeByte(item.status.ordinal); writeBoolean(item.audioAvailable)
            if (item.status == GroupMemberStatus.RESERVED) {
                writeInt((checkNotNull(item.reservedUntilMs) - nowMs).coerceIn(0, GroupRoom.RESERVATION_MS).toInt())
            }
            text(value.names[item.lease.deviceId].orEmpty(), 64)
        }
        writeByte(value.links.size)
        value.links.forEach { item ->
            uuid(item.lease.pair.first); uuid(item.lease.pair.second)
            writeLong(item.lease.firstIncarnation); writeLong(item.lease.secondIncarnation); writeLong(item.lease.generation)
            writeByte((if (item.lease.pair.first in item.confirmedBy) 1 else 0) or (if (item.lease.pair.second in item.confirmedBy) 2 else 0))
        }
    }
    private fun DataInputStream.roster(nowMs: Long): GroupRoster = with(GroupBinary) {
        val key = room(); val host = uuid(); val serial = readLong(); val ended = boolean()
        val count = readUnsignedByte().also { require(it <= 16) }
        val names = mutableMapOf<String, String>()
        val members = List(count) {
            val lease = member()
            val status = GroupMemberStatus.entries[readUnsignedByte()]
            val audio = boolean()
            val reserved = if (status == GroupMemberStatus.RESERVED) nowMs + readInt().also { require(it in 0..60_000) } else null
            names[lease.deviceId] = text(64)
            GroupMember(lease, status, reserved, audio)
        }
        val linkCount = readUnsignedByte().also { require(it <= 6) }
        val links = List(linkCount) {
            val pair = GroupPair(uuid(), uuid())
            val link = GroupLinkLease(pair, readLong(), readLong(), readLong())
            val flags = readUnsignedByte().also { require(it in 0..3) }
            GroupLink(link, buildSet { if (flags and 1 != 0) add(pair.first); if (flags and 2 != 0) add(pair.second) })
        }
        GroupRoster(key, host, serial, ended, members, links, names)
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
        val members = view.members.filter { it.status == GroupMemberStatus.ADMITTED }.map { it.lease }
        if (local !in members || frame.sender !in members || frame.sender.deviceId == local.deviceId) return null
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
