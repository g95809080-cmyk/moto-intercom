package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class RiderMediaEvidenceTest {
    @Test fun countersRequireBothAudioDirectionsAndKeepStreamIdentity() {
        val outbound = Triple("o", "outbound-rtp", mapOf<String, Any>("kind" to "audio", "ssrc" to 1L, "bytesSent" to 50L))
        val inbound = Triple("i", "inbound-rtp", mapOf<String, Any>("kind" to "audio", "ssrc" to 2L, "bytesReceived" to 60L))
        assertNull(audioRtpCounters(listOf(outbound))); assertNull(audioRtpCounters(listOf(inbound)))
        val result = audioRtpCounters(listOf(outbound, inbound))!!
        assertEquals(50L, result.sent); assertEquals(60L, result.received)
        assertNotEquals(result.streamIds, audioRtpCounters(listOf(outbound, inbound.copy(third = inbound.third + ("ssrc" to 3L))))!!.streamIds)
        assertNull(audioRtpCounters(listOf(outbound, inbound.copy(third = inbound.third + ("bytesReceived" to -1L)))))
        assertNull(audioRtpCounters(listOf(outbound, inbound.copy(third = inbound.third + ("kind" to "video")))))
    }
    @Test fun gateRevisionRejectsPauseResumeDuringStatsCollection() {
        val gate = AudioIoGate(Any(), true, { it() }, {})
        val before = gate.revision(); gate.request(false); gate.request(true)
        assertFalse(gate.allows(before)); assertTrue(gate.allows(gate.revision()))
        gate.close(); assertFalse(gate.allows(gate.revision()))
    }
}
