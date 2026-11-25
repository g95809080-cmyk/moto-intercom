package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupControlTest {
    private fun id(n: Long) = UUID(0, n).toString()
    private val key = GroupRoomKey(id(1), id(2))
    private val host = GroupMemberLease(id(3), id(2), 1, 1)
    private val peer = GroupMemberLease(id(4), id(5), 2, 1)
    private val link = GroupLinkLease(GroupPair.of(host.deviceId, peer.deviceId), 1, 2, 1)
    private val hostMember = GroupMember(host, role = GroupRole.OWNER)
    private fun roster(
        members: List<GroupMember> = listOf(hostMember, GroupMember(peer)),
        links: List<GroupLink> = listOf(GroupLink(link)),
        riderIncludesFormation: Boolean = true,
        privateInvites: List<GroupPrivateInvite> = emptyList()
    ) = GroupRoster(key, host.deviceId, 1, false, members, links, emptyMap(), riderIncludesFormation, privateInvites)
    @Test fun reservationDurationIsRebasedToReceiverMonotonicClock() {
        val snapshot = roster(listOf(hostMember, GroupMember(peer, GroupMemberStatus.RESERVED, 61_000)), emptyList())
        val decoded = GroupControlCodec.decode(GroupControlCodec.encode(GroupControl.Roster(snapshot), 1_000), 900_000) as GroupControl.Roster
        assertEquals(960_000L, decoded.value.members.single { it.lease == peer }.reservedUntilMs)
        assertEquals(key, decoded.value.key)
    }
    @Test fun rosterRejectsDuplicateIdentityOverCapacityWrongHostAndForeignLink() {
        assertThrows(Exception::class.java) { roster(listOf(hostMember, hostMember)) }
        assertThrows(Exception::class.java) { roster(listOf(hostMember) + (4L..7).map { GroupMember(GroupMemberLease(id(it + 10), id(it + 100), it, 1)) }, emptyList()) }
        assertThrows(Exception::class.java) { roster(listOf(GroupMember(host.copy(runtimeId = id(99)), role = GroupRole.OWNER), GroupMember(peer))) }
        assertThrows(Exception::class.java) { roster(links = listOf(GroupLink(link.copy(secondIncarnation = 99)))) }
        assertThrows(Exception::class.java) { roster(links = listOf(GroupLink(link, setOf(id(99))))) }
    }
    @Test fun rosterDefensivelyCopiesCallerCollections() {
        val members = mutableListOf(hostMember, GroupMember(peer))
        val confirmed = mutableSetOf(host.deviceId)
        val links = mutableListOf(GroupLink(link, confirmed))
        val value = roster(members, links)
        members.clear(); links.clear(); confirmed.add(peer.deviceId)
        assertEquals(2, value.members.size)
        assertEquals(setOf(host.deviceId), value.links.single().confirmedBy)
    }
    @Test fun frameCodecRejectsTrailingBadTypesAndMalformedUtf8() {
        val join = GroupControlCodec.encode(GroupControl.Join("a"), 0)
        listOf(join + 1, join.copyOf(5), join.copyOf().also { it[5] = 99 }, join.copyOf().also { it[it.lastIndex] = -1 }).forEach {
            assertThrows(Exception::class.java) { GroupControlCodec.decode(it, 0) }
        }
        assertThrows(Exception::class.java) { GroupControlCodec.encode(GroupControl.Join("a".repeat(65)), 0) }
    }
    @Test fun clientReplayGateRejectsUnknownPairOrOldGenerationAndAcceptsCurrentForwardedOffer() {
        val participation = GroupParticipation().begin(key)
        val gate = GroupClientIngress(participation.token!!, peer)
        val frame = GroupFrame(key, host, peer, 1, GroupMessage.Offer(link, "sdp"))
        val next = gate.accept(frame, roster(), participation)!!
        assertNull(next.accept(frame, roster(), participation))
        assertNull(next.accept(frame.copy(sequence = 2, sender = host.copy(controlGeneration = 2)), roster(), participation))
        assertNull(next.accept(frame.copy(sequence = 2, message = GroupMessage.Offer(link.copy(generation = 2), "sdp")), roster(), participation))
        assertNull(next.accept(frame.copy(sequence = 2), roster(), participation.stop(participation.token!!)))
        assertNotNull(next.accept(frame.copy(sequence = 2), roster(), participation))
    }
    @Test fun newLinkReportsAreStrictlyDecoded() {
        listOf(GroupMessage.LinkConfirmed(link), GroupMessage.RestartLink(link)).forEach { payload ->
            val frame = GroupFrame(key, peer, host, 1, payload)
            val decoded = GroupWire.decode(GroupWire.encode(frame))
            val decodedLink = when (val message = decoded.message) {
                is GroupMessage.LinkConfirmed -> message.link
                is GroupMessage.RestartLink -> message.link
                else -> error("unexpected type")
            }
            assertEquals(link, decodedLink)
        }
    }

    @Test fun r2RosterAndNoticeRoundTripWithRebasedInviteDeadlinesAndStrictValidation() {
        val third = GroupMemberLease(id(6), id(7), 3, 1)
        val fourth = GroupMemberLease(id(8), id(9), 4, 1)
        val sessionId = id(50)
        val inviteId = id(51)
        val privatePair = GroupLinkLease(GroupPair.of(third.deviceId, fourth.deviceId), 3, 4, 2)
        val snapshot = roster(
            members = listOf(
                hostMember.copy(channel = GroupChannel.Rider, channelRevision = 2),
                GroupMember(peer, role = GroupRole.HEAD, channel = GroupChannel.Public, channelRevision = 3),
                GroupMember(third, role = GroupRole.RIDING_OFFICER, channel = GroupChannel.Private(sessionId), channelRevision = 4),
                GroupMember(fourth, role = GroupRole.TAIL, channel = GroupChannel.Private(sessionId), channelRevision = 4)
            ),
            links = listOf(GroupLink(privatePair, setOf(third.deviceId))),
            riderIncludesFormation = false,
            privateInvites = listOf(GroupPrivateInvite(inviteId, host.deviceId, peer.deviceId, 31_000))
        )
        val decoded = GroupControlCodec.decode(GroupControlCodec.encode(GroupControl.Roster(snapshot), 1_000), 500_000) as GroupControl.Roster
        assertFalse(decoded.value.riderIncludesFormation)
        assertEquals(GroupChannel.Rider, decoded.value.members.single { it.lease == host }.channel)
        assertEquals(GroupRole.HEAD, decoded.value.members.single { it.lease == peer }.role)
        assertEquals(GroupChannel.Private(sessionId), decoded.value.members.single { it.lease == third }.channel)
        assertEquals(530_000L, decoded.value.privateInvites.single().expiresAtMs)

        val notice = GroupControl.Notice(GroupNoticeCode.PRIVATE_DECLINED, peer.deviceId)
        val decodedNotice = GroupControlCodec.decode(GroupControlCodec.encode(notice, 0), 0) as GroupControl.Notice
        assertEquals(GroupNoticeCode.PRIVATE_DECLINED, decodedNotice.code)
        assertEquals(peer.deviceId, decodedNotice.peerId)
        val noticeNoPeer = GroupControlCodec.decode(GroupControlCodec.encode(GroupControl.Notice(GroupNoticeCode.RIDER_REVOKED), 0), 0) as GroupControl.Notice
        assertEquals(GroupNoticeCode.RIDER_REVOKED, noticeNoPeer.code)
        assertNull(noticeNoPeer.peerId)

        assertThrows(Exception::class.java) { roster(listOf(GroupMember(host, role = GroupRole.MEMBER), GroupMember(peer)), emptyList()) }
        assertThrows(Exception::class.java) { roster(listOf(hostMember, GroupMember(peer, role = GroupRole.OWNER)), emptyList()) }
        assertThrows(Exception::class.java) {
            roster(listOf(hostMember, GroupMember(peer, role = GroupRole.HEAD), GroupMember(third, role = GroupRole.HEAD)), emptyList())
        }
        assertThrows(Exception::class.java) {
            roster(listOf(hostMember, GroupMember(peer, role = GroupRole.MEMBER, channel = GroupChannel.Rider)), emptyList())
        }
        assertThrows(Exception::class.java) {
            roster(listOf(hostMember, GroupMember(peer, role = GroupRole.HEAD, channel = GroupChannel.Rider)), emptyList(), riderIncludesFormation = false)
        }
        assertThrows(Exception::class.java) {
            roster(listOf(hostMember, GroupMember(peer, channel = GroupChannel.Private(sessionId))), emptyList())
        }
        assertThrows(Exception::class.java) {
            roster(listOf(hostMember.copy(channel = GroupChannel.Rider), GroupMember(peer, channel = GroupChannel.Public)), listOf(GroupLink(link)))
        }
    }

    @Test fun clientIngressRejectsCrossChannelMediaFrame() {
        val participation = GroupParticipation().begin(key)
        val gate = GroupClientIngress(participation.token!!, peer)
        val crossChannelRoster = roster(
            members = listOf(hostMember.copy(channel = GroupChannel.Rider), GroupMember(peer, channel = GroupChannel.Public)),
            links = emptyList()
        )
        val frame = GroupFrame(key, host, peer, 1, GroupMessage.Offer(link, "sdp"))
        assertNull(gate.accept(frame, crossChannelRoster, participation))
    }
}
