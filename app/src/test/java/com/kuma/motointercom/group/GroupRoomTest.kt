package com.kuma.motointercom.group

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class GroupRoomTest {
    private var now = 0L
    private var room = GroupRoom.create("a", "host-runtime", code = GroupJoinCode("000042"))
    private var request = 0
    private fun lease(id: String) = room.members.single { it.lease.deviceId == id }.lease
    private fun event(event: GroupEvent): GroupTransition = room.reduce(room.key, event, now).also { room = it.room }
    private fun proof(id: String, runtime: String = "$id-runtime") =
        GroupJoinProof(id, runtime, "request-${request++}", true, true, true, now + 10_000)
    private fun join(id: String, runtime: String = "$id-runtime"): GroupTransition = event(GroupEvent.Join(proof(id, runtime)))
    private fun status(id: String) = room.members.single { it.lease.deviceId == id }.status
    private fun link(a: String, b: String): GroupLinkLease {
        assertEquals(GroupResult.OK, event(GroupEvent.RestartLink(lease(a), lease(b),
            room.links.find { it.lease.pair == GroupPair.of(a, b) }?.lease)).result)
        return room.links.single { it.lease.pair == GroupPair.of(a, b) }.lease
    }
    private fun ready(a: String, b: String): GroupLinkLease {
        event(GroupEvent.AudioAvailability(lease(a), true))
        event(GroupEvent.AudioAvailability(lease(b), true))
        return link(a, b).also {
            event(GroupEvent.ConfirmLink(lease(a), it))
            event(GroupEvent.ConfirmLink(lease(b), it))
        }
    }

    @Test fun codePreservesLeadingZerosAndDoesNotLeakInToString() {
        val zeroRandom = object : SecureRandom() { override fun nextInt(bound: Int) = 7 }
        assertEquals("000007", GroupJoinCode.generate(zeroRandom).digits)
        assertFalse(room.joinCode.toString().contains("000042"))
        listOf("12345", "1234567", "１２３４５６", "12a456").forEach {
            assertThrows(IllegalArgumentException::class.java) { GroupJoinCode(it) }
        }
    }

    @Test fun roomIdentityIsIndependentOfCodeAndEndIsTerminal() {
        val rebuilt = GroupRoom.create("a", "host-runtime", code = room.joinCode)
        assertNotEquals(room.key, rebuilt.key)
        assertEquals(GroupResult.WRONG_ROOM, room.reduce(rebuilt.key, GroupEvent.Join(proof("b")), now).result)
        event(GroupEvent.End(lease("a")))
        assertEquals(GroupResult.ENDED, join("b").result)
        assertEquals(0, room.occupiedSeats)
        assertFalse(room.allVoiceReady)
    }

    @Test fun invalidEvidenceNeverAllocatesASeat() {
        val valid = proof("b")
        listOf(valid.copy(identityVerified = false), valid.copy(credentialVerified = false),
            valid.copy(deviceId = ""), valid.copy(expiresAtMs = now), valid.copy(expiresAtMs = 10_001)).forEach {
            assertEquals(GroupResult.UNVERIFIED, event(GroupEvent.Join(it)).result)
        }
        assertEquals(GroupResult.INCOMPATIBLE, event(GroupEvent.Join(valid.copy(versionSupported = false))).result)
        assertEquals(1, room.occupiedSeats)
    }

    @Test fun competingFinalSeatIsSerializedWithoutEvictingExistingMembers() {
        join("b"); join("c")
        val before = room
        val d = event(GroupEvent.Join(proof("d")))
        val e = event(GroupEvent.Join(proof("e")))
        assertEquals(GroupResult.OK, d.result)
        assertEquals(GroupResult.FULL, e.result)
        assertEquals(4, room.occupiedSeats)
        assertEquals(3, before.occupiedSeats) // reducer did not mutate a previous snapshot
        assertEquals(setOf("a", "b", "c", "d"), room.members.map { it.lease.deviceId }.toSet())
    }

    @Test fun duplicateJoinIsIdempotentAndCannotResurrectAfterLeave() {
        val join = GroupEvent.Join(proof("b"))
        event(join)
        val first = lease("b")
        val revision = room.rosterRevision
        assertEquals(first, event(join).member)
        assertEquals(revision, room.rosterRevision)
        event(GroupEvent.Leave(first))
        assertEquals(GroupResult.STALE, event(join).result)
        assertEquals(1, room.occupiedSeats)
        now = 30_001
        assertEquals(GroupResult.UNVERIFIED, event(join).result)
    }

    @Test fun reservationBoundaryAndRepeatedLossDoNotExtendDeadline() {
        join("b"); val b = lease("b")
        now = 100; event(GroupEvent.Lost(b))
        now = 50_000; event(GroupEvent.Lost(b))
        assertEquals(60_100L, room.members.single { it.lease == b }.reservedUntilMs)
        now = 60_099; event(GroupEvent.Tick)
        assertEquals(GroupMemberStatus.RESERVED, status("b")); assertEquals(2, room.occupiedSeats)
        now = 60_100; event(GroupEvent.Tick)
        assertEquals(GroupMemberStatus.WAITING, status("b")); assertEquals(1, room.occupiedSeats)
        now = 60_101; event(GroupEvent.Tick)
        assertEquals(GroupMemberStatus.WAITING, status("b"))
    }

    @Test fun releasedMemberWaitsWhenFullThenResumesWithNewIncarnation() {
        join("b"); join("c"); join("d"); val b = lease("b")
        event(GroupEvent.Lost(b)); now = 60_000; join("e")
        assertEquals(GroupResult.FULL, event(GroupEvent.Resume(b)).result)
        event(GroupEvent.Leave(lease("e")))
        assertEquals(GroupResult.OK, event(GroupEvent.Resume(b)).result)
        assertNotEquals(b.incarnation, lease("b").incarnation)
        assertEquals(GroupResult.STALE, event(GroupEvent.Lost(b)).result)
    }

    @Test fun reservedMemberKeepsSeatButReplacesControlConnection() {
        join("b"); join("c"); join("d"); val old = lease("b")
        event(GroupEvent.Lost(old)); now = 59_999
        assertEquals(GroupResult.FULL, join("e").result)
        assertEquals(GroupResult.OK, event(GroupEvent.Resume(old)).result)
        assertEquals(old.incarnation, lease("b").incarnation)
        assertTrue(lease("b").controlGeneration > old.controlGeneration)
        assertEquals(GroupResult.STALE, event(GroupEvent.Lost(old)).result)
        assertEquals(GroupMemberStatus.ADMITTED, status("b"))
    }

    @Test fun explicitNewRuntimeReplacesReservedSeatAndInvalidatesOldCallbacks() {
        join("b"); join("c"); join("d"); val old = lease("b")
        event(GroupEvent.Lost(old))
        assertEquals(GroupResult.OK, join("b", "restarted-runtime").result)
        assertEquals(4, room.occupiedSeats)
        assertNotEquals(old.incarnation, lease("b").incarnation)
        assertEquals(GroupResult.STALE, event(GroupEvent.Resume(old)).result)
        assertEquals(GroupResult.STALE, event(GroupEvent.Leave(old)).result)
        assertEquals(GroupResult.STALE, event(GroupEvent.AudioAvailability(old, true)).result)
    }

    @Test fun onlyHostCanRemoveUnblockAndEndAndUnblockDoesNotJoin() {
        join("b"); val b = lease("b")
        assertEquals(GroupResult.FORBIDDEN, event(GroupEvent.Remove(b, "a")).result)
        assertEquals(GroupResult.FORBIDDEN, event(GroupEvent.Unblock(b, "c")).result)
        assertEquals(GroupResult.FORBIDDEN, event(GroupEvent.End(b)).result)
        event(GroupEvent.Remove(lease("a"), "b"))
        assertEquals(GroupResult.REMOVED, join("b", "new-runtime").result)
        assertEquals(GroupResult.STALE, event(GroupEvent.Resume(b)).result)
        event(GroupEvent.Unblock(lease("a"), "b"))
        assertEquals(1, room.occupiedSeats)
        assertEquals(GroupResult.OK, join("b", "new-runtime").result)
    }

    @Test fun removedJoinReplayStaysRevokedAfterUnblock() {
        val request = GroupEvent.Join(proof("b")); event(request)
        event(GroupEvent.Remove(lease("a"), "b"))
        event(GroupEvent.Unblock(lease("a"), "b"))
        assertEquals(GroupResult.STALE, event(request).result)
        assertEquals(1, room.occupiedSeats)
    }

    @Test fun hostIsNotOrdinaryLeaveOrRecoveryAndEmptyRoomRetainsCode() {
        assertEquals(GroupResult.FORBIDDEN, event(GroupEvent.Leave(lease("a"))).result)
        assertEquals(GroupResult.FORBIDDEN, event(GroupEvent.Lost(lease("a"))).result)
        join("b"); event(GroupEvent.Leave(lease("b")))
        assertFalse(room.ended); assertEquals("000042", room.joinCode.digits)
        assertTrue(room.activePairs.isEmpty()); assertFalse(room.allVoiceReady)
    }

    @Test fun unrelatedMemberChangesPreserveHealthyPair() {
        join("b"); val ab = ready("a", "b")
        join("c"); val c = lease("c")
        event(GroupEvent.Lost(c)); event(GroupEvent.Resume(c))
        event(GroupEvent.Leave(lease("c")))
        assertEquals(ab, room.links.single().lease)
        assertTrue(room.allVoiceReady)
    }

    @Test fun allSixPairsNeedBothEndpointsNotOnlyHostConnections() {
        join("b"); join("c"); join("d")
        ready("a", "b"); ready("a", "c"); ready("a", "d")
        assertEquals(6, room.activePairs.size); assertEquals(3, room.readyPairs.size)
        assertFalse(room.allVoiceReady)
        ready("b", "c"); ready("b", "d")
        val cd = link("c", "d"); event(GroupEvent.ConfirmLink(lease("c"), cd))
        assertFalse(room.allVoiceReady)
        event(GroupEvent.ConfirmLink(lease("d"), cd)); assertTrue(room.allVoiceReady)
    }

    @Test fun peerFailureDoesNotCloseOtherLinksAndOldEvidenceCannotConfirmReplacement() {
        join("b"); join("c")
        val ab = ready("a", "b"); val ac = ready("a", "c"); ready("b", "c")
        val b = lease("b"); event(GroupEvent.Lost(b)); event(GroupEvent.Resume(b))
        assertEquals(ac, room.links.single().lease)
        val newAb = link("a", "b")
        assertNotEquals(ab.generation, newAb.generation)
        assertEquals(GroupResult.STALE, event(GroupEvent.ConfirmLink(lease("a"), ab)).result)
        assertEquals(setOf(GroupPair.of("a", "c")), room.readyPairs)
    }

    @Test fun audioInterruptionPreservesMembershipAndRequiresFreshPairEvidence() {
        join("b"); val ab = ready("a", "b"); val b = lease("b")
        event(GroupEvent.AudioAvailability(b, false))
        assertEquals(b, lease("b")); assertEquals(GroupMemberStatus.ADMITTED, status("b"))
        event(GroupEvent.AudioAvailability(b, true))
        assertFalse(room.allVoiceReady)
        assertEquals(GroupResult.STALE, event(GroupEvent.ConfirmLink(b, ab)).result)
        ready("a", "b"); assertTrue(room.allVoiceReady)
    }

    @Test fun saturatedReceiptCacheCannotResurrectDepartedOrUnblockedRequests() {
        for (removed in listOf(false, true)) {
            room = GroupRoom.create("a", "host-runtime")
            val original = GroupEvent.Join(proof("b")); event(original)
            if (removed) {
                event(GroupEvent.Remove(lease("a"), "b"))
                event(GroupEvent.Unblock(lease("a"), "b"))
            } else event(GroupEvent.Leave(lease("b")))
            repeat(127) { assertEquals(GroupResult.OK, join("c").result) }
            assertEquals(GroupResult.BUSY, join("d").result)
            assertEquals(GroupResult.STALE, event(original).result)
            assertFalse(room.members.any { it.lease.deviceId == "b" })
        }
        now = 30_001
        assertEquals(GroupResult.OK, join("d").result)
    }

    @Test fun delayedRestartCannotInvalidateReplacementOrNewMember() {
        join("b"); val first = ready("a", "b")
        val delayed = GroupEvent.RestartLink(lease("a"), lease("b"), first)
        event(delayed); val replacement = room.links.single().lease
        event(GroupEvent.ConfirmLink(lease("a"), replacement))
        event(GroupEvent.ConfirmLink(lease("b"), replacement))
        assertEquals(GroupResult.STALE, event(delayed).result)
        assertTrue(room.allVoiceReady)
        join("b", "restarted")
        val latest = ready("a", "b")
        assertEquals(GroupResult.STALE, event(delayed).result)
        assertEquals(latest, room.links.single().lease)
        assertTrue(room.allVoiceReady)
    }
    @Test fun clockCannotMoveBackwards() {
        now = 100; event(GroupEvent.Tick)
        assertThrows(IllegalArgumentException::class.java) { room.reduce(room.key, GroupEvent.Tick, 99) }
    }
}
