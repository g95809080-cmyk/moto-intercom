package com.kuma.motointercom.group

import com.kuma.motointercom.*
import org.junit.Assert.*
import org.junit.Test

class GroupMediaControllerTest {
    private val key = GroupRoomKey("room", "host-runtime")
    private val intent = GroupIntentToken(key, "local-runtime", 1)
    private val local = GroupMemberLease("a", "host-runtime", 1, 1)
    private fun lease(id: String, generation: Long = 1) = GroupMediaLease(intent, local,
        GroupMemberLease(id, "$id-runtime", 2, generation), GroupLinkLease(GroupPair.of("a", id), 1, 2, generation))
    private class FakeSession(val callbacks: RiderMediaSessionCallbacks) : RiderMediaSession {
        var closes = 0
        var offers = 0
        var candidates = 0
        var muted = callbacks.initialPlaybackMuted
        var closeThrows = false
        override fun createOffer() { offers++ }
        override fun createAnswer(remoteSdpJson: String) = Unit
        override fun setRemoteAnswer(remoteSdpJson: String) = Unit
        override fun addRemoteIceCandidate(candidateJson: String) { candidates++ }
        override fun setPlaybackMuted(muted: Boolean) { this.muted = muted }
        override fun close() { closes++; if (closeThrows) error("close failure") }
    }
    private class FakeEngine : RiderMediaEngine {
        val sessions = mutableListOf<FakeSession>()
        var failOpen = false
        var duringOpen: (() -> Unit)? = null
        var immediateError = false
        var closed = false
        var suspended = true
        var controls: VersionedAudioControls? = null
        override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession {
            if (failOpen) error("open failure")
            return FakeSession(callbacks).also {
                sessions += it
                if (immediateError) callbacks.onError(IllegalStateException("immediate"))
                duringOpen?.invoke()
            }
        }
        override fun updateAudioControls(controls: VersionedAudioControls) { this.controls = controls }
        override fun suspendAudio() { suspended = true }
        override fun resumeAudio() { suspended = false }
        override fun close() { closed = true }
    }
    private val engine = FakeEngine()
    private var begins = 0
    private var ends = 0
    private val revoked = mutableSetOf<GroupMediaLease>()
    private val controller = GroupMediaController(engine, { it !in revoked }, { begins++ }, { ends++; engine.suspendAudio() })
    private var sdp = 0
    private var errors = 0
    private fun callbacks() = RiderMediaSessionCallbacks({ sdp++ }, {}, onError = { errors++ }, isSessionCurrent = { true })

    @Test fun threePeersShareDemandAndMiddleCloseLeavesHealthyPeers() {
        listOf("b", "c", "d").forEach { assertTrue(controller.open(lease(it), callbacks())) }
        assertEquals(1, begins)
        assertThrows(IllegalStateException::class.java) { controller.open(lease("e"), callbacks()) }
        controller.close(lease("c"))
        assertEquals(listOf(0,1,0), engine.sessions.map { it.closes })
        assertEquals(0, ends)
        controller.offer(lease("b")); controller.candidate(lease("d"), "candidate")
        assertEquals(1, engine.sessions[0].offers)
        assertEquals(1, engine.sessions[2].candidates)
        controller.close(); controller.close()
        assertEquals(listOf(1,1,1), engine.sessions.map { it.closes })
        assertEquals(1, ends)
        assertFalse(engine.closed)
    }

    @Test fun staleCloseErrorAndSdpCannotAffectReplacement() {
        controller.open(lease("b"), callbacks()); controller.open(lease("c"), callbacks())
        val old = engine.sessions[0]
        controller.open(lease("b", 2), callbacks())
        old.callbacks.onLocalSdpGenerated("late")
        old.callbacks.onError(IllegalStateException("late"))
        controller.close(lease("b")); controller.offer(lease("b"))
        assertEquals(0, sdp); assertEquals(0, errors)
        assertEquals(0, engine.sessions.last().closes)
        assertEquals(0, ends)
        engine.sessions.last().callbacks.onLocalSdpGenerated("current")
        assertEquals(1, sdp)
    }

    @Test fun synchronousErrorDoesNotResurrectReturnedSession() {
        engine.immediateError = true
        assertFalse(controller.open(lease("b"), callbacks()))
        assertEquals(1, engine.sessions.single().closes)
        assertEquals(1, ends); assertEquals(1, errors)
        engine.immediateError = false
        assertTrue(controller.open(lease("b",2), callbacks()))
        assertEquals(2, begins)
    }

    @Test fun failedOpenDoesNotEndOtherPeersAndCanRetry() {
        controller.open(lease("b"), callbacks())
        engine.failOpen = true
        assertThrows(IllegalStateException::class.java) { controller.open(lease("c"), callbacks()) }
        assertEquals(0, ends)
        engine.failOpen = false
        assertTrue(controller.open(lease("c"), callbacks()))
        assertEquals(1, begins)
    }

    @Test fun lateTransferAfterStopOrRevocationIsClosed() {
        engine.duringOpen = { revoked.add(lease("b")) }
        assertFalse(controller.open(lease("b"), callbacks()))
        assertEquals(1, engine.sessions.single().closes)
        assertEquals(1, ends)
        engine.duringOpen = { controller.close() }
        assertFalse(controller.open(lease("c"), callbacks()))
        assertEquals(1, engine.sessions.last().closes)
        assertEquals(2, ends)
    }

    @Test fun cleanupFailureStillClosesOtherPeersAndEndsDemand() {
        listOf("b", "c", "d").forEach { controller.open(lease(it), callbacks()) }
        engine.sessions[0].closeThrows = true
        assertThrows(IllegalStateException::class.java) { controller.close() }
        assertEquals(listOf(1,1,1), engine.sessions.map { it.closes })
        assertEquals(1, ends)
        controller.close()
    }

    @Test fun blockAppliesBeforeOpenAndSurvivesReplacementAndInterruption() {
        controller.block("b", true)
        controller.open(lease("b"), callbacks()); controller.open(lease("c"), callbacks())
        assertTrue(engine.sessions[0].callbacks.initialPlaybackMuted)
        assertFalse(engine.sessions[1].muted)
        engine.suspendAudio(); engine.resumeAudio()
        controller.open(lease("b",2), callbacks())
        assertTrue(engine.sessions.last().muted)
        assertEquals(0, engine.sessions[1].closes)
        controller.block("b", false)
        assertFalse(engine.sessions.last().muted)
    }

    @Test fun revokedLeaseCannotIssueCommandsOrCallbacks() {
        val lease = lease("b")
        controller.open(lease, callbacks())
        revoked += lease
        controller.offer(lease); controller.candidate(lease,"candidate")
        engine.sessions.single().callbacks.onLocalSdpGenerated("late")
        assertEquals(0, engine.sessions.single().offers)
        assertEquals(0, engine.sessions.single().candidates)
        assertEquals(0, sdp)
        assertFalse(engine.sessions.single().callbacks.isSessionCurrent())
    }

    @Test fun defaultModesPreserveSingleSessionLimit() {
        assertEquals(1, RiderMediaMode.SINGLE.maxSessions)
        assertEquals(3, RiderMediaMode.GROUP.maxSessions)
    }

    @Test fun fullLeaseValidationRejectsReplacementAndStoppedIntent() {
        var room = GroupRoom.create("a", "host-runtime")
        room = room.reduce(room.key, GroupEvent.Join(GroupJoinProof("b", "b-runtime", "request",
            true, true, true, 10000)), 0).room
        val local = room.members.first { it.lease.deviceId == "a" }.lease
        val peer = room.members.first { it.lease.deviceId == "b" }.lease
        room = room.reduce(room.key, GroupEvent.RestartLink(local, peer), 0).room
        val participation = GroupParticipation().begin(room.key)
        val lease = GroupMediaLease(participation.token!!, local, peer, room.links.single().lease)
        assertTrue(lease.matches(room, participation))
        assertFalse(lease.matches(room, participation.stop(participation.token!!)))
        room = room.reduce(room.key, GroupEvent.Lost(peer), 0).room
        assertFalse(lease.matches(room, participation))
        room = room.reduce(room.key, GroupEvent.Resume(peer), 0).room
        assertFalse(lease.matches(room, participation))
    }

    @Test fun demandBeginFailureReleasesReservationAndAllowsRetry() {
        var fail = true
        var ended = 0
        val controller = GroupMediaController(engine, { true }, { if (fail) error("focus failure") }, { ended++ })
        assertThrows(IllegalStateException::class.java) { controller.open(lease("b"), callbacks()) }
        assertEquals(1, ended)
        assertTrue(engine.sessions.isEmpty())
        fail = false
        assertTrue(controller.open(lease("b"), callbacks()))
    }
}
