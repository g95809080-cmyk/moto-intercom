package com.kuma.motointercom.group

/** The single product writer owns one state per authenticated control generation.
 * Never reset a state's sequence on the same physical/control generation.
 * This is not an authentication implementation and does not execute/forward accepted frames.
 */
internal class GroupHostIngress private constructor(
    private val intent: GroupIntentToken,
    private val localHost: GroupMemberLease,
    private val peer: GroupMemberLease,
    private val lastSequence: Long
) {
    constructor(intent: GroupIntentToken, localHost: GroupMemberLease, peer: GroupMemberLease) :
        this(intent, localHost, peer, 0)

    data class Accepted(val next: GroupHostIngress, val frame: GroupFrame, val needsForwarding: Boolean)

    fun accept(frame: GroupFrame, room: GroupRoom, participation: GroupParticipation): Accepted? {
        if (!participation.isCurrent(intent) || room.key != intent.room || room.ended ||
            room.hostId != localHost.deviceId || room.key.hostRuntimeId != localHost.runtimeId ||
            frame.room != room.key || frame.sender != peer || peer.deviceId == localHost.deviceId ||
            frame.sequence <= lastSequence || frame.sequence <= 0) return null
        val admittedMembers = room.members.filter { it.status == GroupMemberStatus.ADMITTED }
        val admitted = admittedMembers.map { it.lease }
        val senderMember = admittedMembers.singleOrNull { it.lease == peer } ?: return null
        val recipientMember = admittedMembers.singleOrNull { it.lease == frame.recipient } ?: return null
        if (localHost !in admitted || frame.recipient.deviceId == peer.deviceId) return null
        val link = when (val msg = frame.message) {
            GroupMessage.Leave,
            is GroupMessage.AudioAvailable,
            is GroupMessage.SwitchChannel,
            is GroupMessage.RequestPrivate,
            is GroupMessage.AcceptPrivate,
            is GroupMessage.DeclinePrivate,
            is GroupMessage.CancelPrivate,
            GroupMessage.EndPrivate -> {
                if (frame.recipient != localHost) return null
                null
            }
            GroupMessage.End, is GroupMessage.Remove, is GroupMessage.Unblock -> return null
            is GroupMessage.Offer -> {
                if (peer.deviceId >= frame.recipient.deviceId) return null
                msg.link
            }
            is GroupMessage.Answer -> {
                if (peer.deviceId <= frame.recipient.deviceId) return null
                msg.link
            }
            is GroupMessage.Candidate -> msg.link
            is GroupMessage.LinkConfirmed -> {
                if (frame.recipient != localHost) return null
                msg.link
            }
            is GroupMessage.RestartLink -> {
                if (frame.recipient != localHost) return null
                msg.link
            }
        }
        if (link != null) {
            val report = frame.message is GroupMessage.LinkConfirmed || frame.message is GroupMessage.RestartLink
            if (!report && senderMember.channel != recipientMember.channel) return null
            val pair = if (report) link.pair else GroupPair.of(peer.deviceId, frame.recipient.deviceId)
            if (peer.deviceId !in listOf(pair.first, pair.second) ||
                admitted.none { it.deviceId == pair.first } || admitted.none { it.deviceId == pair.second }) return null
            if (link.pair != pair || room.links.none { it.lease == link } ||
                admitted.single { it.deviceId == pair.first }.incarnation != link.firstIncarnation ||
                admitted.single { it.deviceId == pair.second }.incarnation != link.secondIncarnation) return null
        }
        return Accepted(GroupHostIngress(intent, localHost, peer, frame.sequence), frame,
            link != null && frame.recipient != localHost)
    }
}
