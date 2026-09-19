package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class RiderMediaEvidenceTest {
    @Test fun nativeStartAndFreshPcmAreRequiredAndStopRevokesOldRevision() {
        val io = RiderAudioIoEvidence()
        assertFalse(io.ready(io.revision(), 0))
        io.recording(true); io.playing(true)
        val revision = io.revision()
        assertFalse(io.ready(revision, 0))
        io.pcm(10)
        assertTrue(io.ready(revision, 2_010))
        assertFalse(io.ready(revision, 2_011))
        io.recording(false); io.recording(true); io.pcm(2_012)
        assertFalse(io.ready(revision, 2_012))
        assertTrue(io.ready(io.revision(), 2_012))
        io.playing(false)
        assertFalse(io.ready(io.revision(), 2_012))
    }
    @Test fun countersRequireBothAudioDirectionsAndKeepStreamIdentity() {
        val outbound = Triple("o", "outbound-rtp", mapOf<String, Any>("kind" to "audio", "ssrc" to 1L, "bytesSent" to 50L))
        val inbound = Triple("i", "inbound-rtp", mapOf<String, Any>("kind" to "audio", "ssrc" to 2L, "bytesReceived" to 60L))
        assertNull(audioRtpCounters(listOf(outbound)))
        assertNull(audioRtpCounters(listOf(inbound)))
        val result = audioRtpCounters(listOf(outbound, inbound))!!
        assertEquals(50L, result.sent); assertEquals(60L, result.received)
        val different = inbound.copy(third = inbound.third + ("ssrc" to 3L))
        assertNotEquals(result.streamIds, audioRtpCounters(listOf(outbound, different))!!.streamIds)
        assertNull(audioRtpCounters(listOf(outbound, inbound.copy(third = inbound.third + ("bytesReceived" to -1L)))))
        assertNull(audioRtpCounters(listOf(outbound, inbound.copy(third = inbound.third + ("kind" to "video")))))
    }
    @Test fun gateRevisionRejectsPauseResumeDuringStatsCollection() {
        val gate = AudioIoGate(Any(), true, { it() }, {})
        val before = gate.revision()
        gate.request(false); gate.request(true)
        assertFalse(gate.allows(before))
        assertTrue(gate.allows(gate.revision()))
        gate.close(); assertFalse(gate.allows(gate.revision()))
    }
    @Test fun platformOwnershipCannotBeReleasedByAnotherToken() {
        val token = AudioPlatformOwnership.acquire()
        try { AudioPlatformOwnership.release(Any()); assertTrue(AudioPlatformOwnership.hasOwner()) }
        finally { AudioPlatformOwnership.release(token) }
    }
}
