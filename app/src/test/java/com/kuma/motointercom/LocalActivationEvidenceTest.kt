package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class LocalActivationEvidenceTest {
    private fun evidence(sent: Long,received: Long,gate: Long=1,native: Long=1,stream: String="stream")=
        RiderMediaEvidence(RiderRtpCounters(setOf(stream),sent,received),true,true,true,gate,native)
    @Test fun needsBidirectionalGrowthWithinTheSameNativeGrantAndStreams() {
        val proof=LocalActivationEvidence()
        assertFalse(proof.sample(evidence(100,100)))
        assertFalse(proof.sample(evidence(110,100)))
        assertTrue(proof.sample(evidence(110,110)))
        assertTrue(proof.sample(evidence(120,120)))
        assertFalse(proof.sample(evidence(130,130,gate=2)))
        assertFalse(proof.sample(evidence(140,140,gate=2,native=2)))
        assertFalse(proof.sample(evidence(150,150,gate=2,native=2,stream="replacement")))
        assertTrue(proof.sample(evidence(160,160,gate=2,native=2,stream="replacement")))
    }
    @Test fun missingOrSuspendedEvidenceResetsTheWindow() {
        val proof=LocalActivationEvidence();assertFalse(proof.sample(evidence(1,1)))
        assertFalse(proof.sample(evidence(2,2).copy(audioIoEnabled=false)))
        assertFalse(proof.sample(evidence(3,3)));assertTrue(proof.sample(evidence(4,4)))
    }
    @Test fun aCounterRollbackWithinTheWindowStartsANewWindow() {
        val proof=LocalActivationEvidence()
        assertFalse(proof.sample(evidence(100,100)));assertFalse(proof.sample(evidence(150,100)))
        assertFalse(proof.sample(evidence(130,120)));assertTrue(proof.sample(evidence(140,130)))
    }
}
