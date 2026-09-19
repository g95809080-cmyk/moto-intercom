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
    private fun roster(members: List<GroupMember> = listOf(GroupMember(host), GroupMember(peer)),
        links: List<GroupLink> = listOf(GroupLink(link))) = GroupRoster(key, host.deviceId, 1, false, members, links, emptyMap())
    @Test fun reservationDurationIsRebasedToReceiverMonotonicClock() {
        val snapshot = roster(listOf(GroupMember(host), GroupMember(peer, GroupMemberStatus.RESERVED, 61_000)), emptyList())
        val decoded = GroupControlCodec.decode(GroupControlCodec.encode(GroupControl.Roster(snapshot), 1_000), 900_000) as GroupControl.Roster
        assertEquals(960_000L, decoded.value.members.single { it.lease == peer }.reservedUntilMs)
        assertEquals(key, decoded.value.key)
    }
    @Test fun rosterRejectsDuplicateIdentityOverCapacityWrongHostAndForeignLink() {
        assertThrows(Exception::class.java) { roster(listOf(GroupMember(host), GroupMember(host))) }
        assertThrows(Exception::class.java) { roster(listOf(GroupMember(host)) + (4L..7).map { GroupMember(GroupMemberLease(id(it + 10), id(it + 100), it, 1)) }, emptyList()) }
        assertThrows(Exception::class.java) { roster(listOf(GroupMember(host.copy(runtimeId = id(99))), GroupMember(peer))) }
        assertThrows(Exception::class.java) { roster(links = listOf(GroupLink(link.copy(secondIncarnation = 99)))) }
        assertThrows(Exception::class.java) { roster(links = listOf(GroupLink(link, setOf(id(99))))) }
    }
    @Test fun rosterDefensivelyCopiesCallerCollections() {
        val members = mutableListOf(GroupMember(host), GroupMember(peer))
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
}
