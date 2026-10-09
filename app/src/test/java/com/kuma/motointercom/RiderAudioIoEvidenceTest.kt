package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class RiderAudioIoEvidenceTest {
    @Test fun sharedCaptureAndEachPeerPlayoutRequireTheirOwnFreshRevision() {
        val capture = RiderAudioIoEvidence()
        val first = RiderAudioIoEvidence()
        val second = RiderAudioIoEvidence()
        capture.recording(true); capture.pcm(100)
        first.playing(true); first.rendered(100)
        second.playing(true); second.rendered(100)
        val captureRevision = capture.revision()
        val secondRevision = second.revision()
        first.playing(false)
        assertTrue(capture.captureReady(captureRevision, 110))
        assertFalse(first.playoutReady(first.revision(), 110))
        assertTrue(second.playoutReady(secondRevision, 110))
        first.playing(true)
        assertFalse(first.playoutReady(first.revision(), 110))
        first.rendered(110)
        assertTrue(first.playoutReady(first.revision(), 110))
        assertFalse(second.playoutReady(secondRevision, 2_101))
        capture.recording(false); capture.recording(true); capture.pcm(120)
        assertFalse(capture.captureReady(captureRevision, 120))
    }
    @Test fun requestedOrOneSidedIoCannotProveHealthyAudio() {
        val evidence = RiderAudioIoEvidence()
        evidence.pcm(100)
        assertFalse(evidence.ready(evidence.revision(), 100))
        evidence.recording(true)
        evidence.pcm(100)
        assertFalse(evidence.ready(evidence.revision(), 100))
        evidence.playing(true)
        assertFalse(evidence.ready(evidence.revision(), 100))
        evidence.rendered(100)
        assertTrue(evidence.ready(evidence.revision(), 100))
    }
    @Test fun stopRestartAndStalePcmInvalidateEarlierNativeProof() {
        val evidence = RiderAudioIoEvidence()
        evidence.recording(true); evidence.playing(true); evidence.pcm(100); evidence.rendered(100)
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
