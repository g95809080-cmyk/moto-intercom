package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupHostIngressTest {
    private fun id(n: Long) = UUID(0, n).toString()
    private var room = GroupRoom.create(id(1), id(11))
    private val participation = GroupParticipation().begin(room.key)
    private val token = participation.token!!
    private var request = 0
    private fun event(e: GroupEvent) { room = room.reduce(room.key, e, 0).room }
    private fun member(n: Long) = room.members.single { it.lease.deviceId == id(n) }.lease
    private fun join(n: Long) { event(GroupEvent.Join(GroupJoinProof(id(n), id(n+10), "${request++}", true, true, true, 10000))) }
    private fun gate() = GroupHostIngress(token, member(1), member(2))
    private fun frame(msg: GroupMessage = GroupMessage.Leave, recipient: Long = 1) =
        GroupFrame(room.key, member(2), member(recipient), 1, msg)
    private fun link(): GroupLinkLease {
        event(GroupEvent.RestartLink(member(2), member(3)))
        return room.links.single().lease
    }

    @Test fun acceptsOwnControlButRejectsReplayWithoutConsumingRejectedSequence() {
        join(2)
        val frame = frame()
        val gate = gate()
        assertNull(gate.accept(frame.copy(sender = member(1), sequence = 100), room, participation))
        val accepted = gate.accept(frame, room, participation)!!
        assertFalse(accepted.needsForwarding)
        assertNull(accepted.next.accept(frame, room, participation))
        assertNotNull(accepted.next.accept(frame.copy(sequence = 2), room, participation))
    }

    @Test fun cannotForgeHostCommandsOrHostRole() {
        join(2)
        for (msg in listOf(GroupMessage.End, GroupMessage.Remove(id(3)), GroupMessage.Unblock(id(3)))) {
            assertNull(gate().accept(frame(msg), room, participation))
        }
        val fakeHost = GroupHostIngress(token, member(2), member(1))
        assertNull(fakeHost.accept(frame().copy(sender = member(1), recipient = member(2)), room, participation))
    }

    @Test fun checksRoomIntentRecipientAndProcessBeforeAccepting() {
        join(2); join(3)
        val gate = gate()
        val frame = frame()
        assertNull(gate.accept(frame.copy(room = room.key.copy(instanceId = id(999))), room, participation))
        assertNull(gate.accept(frame.copy(recipient = member(3)), room, participation))
        assertNull(gate.accept(frame, room, participation.stop(token)))
        assertNull(gate.accept(frame, room, GroupParticipation().begin(room.key)))
        event(GroupEvent.End(member(1)))
        assertNull(gate.accept(frame, room, participation))
    }

    @Test fun replacedControlCannotAcceptEitherOldOrNewFrames() {
        join(2)
        val gate = gate()
        val old = frame()
        event(GroupEvent.Lost(member(2)))
        assertNull(gate.accept(old, room, participation))
        event(GroupEvent.Resume(member(2)))
        assertNull(gate.accept(old, room, participation))
        assertNull(gate.accept(frame(), room, participation))
        assertNotNull(gate().accept(frame(), room, participation))
    }

    @Test fun removedMemberAndReplacementIncarnationCannotReuseOldGate() {
        join(2)
        val gate = gate()
        val old = frame()
        event(GroupEvent.Remove(member(1), id(2)))
        assertNull(gate.accept(old, room, participation))
        event(GroupEvent.Unblock(member(1), id(2))); join(2)
        assertNull(gate.accept(frame(), room, participation))
    }

    @Test fun mediaRequiresCurrentPairAndOfferRoleButDoesNotConfirmVoice() {
        join(2); join(3)
        val link = link()
        val gate = gate()
        val offer = frame(GroupMessage.Offer(link, "sdp"), 3)
        assertTrue(gate.accept(offer, room, participation)!!.needsForwarding)
        assertFalse(room.allVoiceReady)
        assertNull(gate.accept(frame(GroupMessage.Answer(link, "sdp"), 3), room, participation))
        assertNull(gate.accept(frame(GroupMessage.Offer(link.copy(generation = 999), "sdp"), 3), room, participation))
        join(4)
        assertNotNull(gate.accept(offer, room, participation))
        event(GroupEvent.RestartLink(member(2), member(3), link))
        assertNull(gate.accept(offer, room, participation))
    }

    @Test fun recipientReplacementAndStaleChannelIdentityAreRejected() {
        join(2); join(3)
        val link = link()
        val gate = gate()
        val frame = frame(GroupMessage.Candidate(link, "audio", 0, "candidate"), 3)
        assertNotNull(gate.accept(frame, room, participation))
        assertNull(gate.accept(frame.copy(sender = frame.sender.copy(runtimeId = id(99))), room, participation))
        event(GroupEvent.Lost(member(3))); event(GroupEvent.Resume(member(3)))
        assertNull(gate.accept(frame, room, participation))
    }
}
