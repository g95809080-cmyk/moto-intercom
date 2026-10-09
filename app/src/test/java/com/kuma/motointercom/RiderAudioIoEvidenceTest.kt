package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class RiderAudioIoEvidenceTest {
    @Test fun requestedOrOneSidedIoCannotProveHealthyAudio() {
        val evidence = RiderAudioIoEvidence()
        evidence.pcm(100)
        assertFalse(evidence.ready(evidence.revision(), 100))
        evidence.recording(true)
        evidence.pcm(100)
        assertFalse(evidence.ready(evidence.revision(), 100))
        evidence.playing(true)
        assertTrue(evidence.ready(evidence.revision(), 100))
    }
    @Test fun stopRestartAndStalePcmInvalidateEarlierNativeProof() {
        val evidence = RiderAudioIoEvidence()
        evidence.recording(true); evidence.playing(true); evidence.pcm(100)
        val old = evidence.revision()
        assertFalse(evidence.ready(old, 2_101))
        evidence.recording(false); evidence.recording(true)
        assertFalse(evidence.ready(old, 110))
        assertFalse(evidence.ready(evidence.revision(), 110))
        evidence.pcm(110)
        assertTrue(evidence.ready(evidence.revision(), 110))
        evidence.playing(false)
        assertFalse(evidence.ready(evidence.revision(), 110))
    }
}
