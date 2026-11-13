package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class GmeAudioActivityWitnessTest {
    @Test fun preexistingMusicDoesNotCountAsGmeButNewActivityMustDisappear() {
        var active=AudioActivities(emptySet(),setOf("music-player"))
        val witness=GmeAudioActivityWitness {active}
        active=AudioActivities(setOf("gme-recorder"),setOf("music-player","gme-player"))
        assertFalse(witness.idle())
        active=AudioActivities(emptySet(),setOf("music-player"));assertTrue(witness.idle())
        active=AudioActivities(emptySet(),setOf("music-player","new-unknown-player"));assertFalse(witness.idle())
    }
    @Test fun unavailableOrFailingActivityEvidenceCannotGrantAudio() {
        val unavailable=GmeAudioActivityWitness {null};assertFalse(unavailable.idle())
        var fail=false
        val witness=GmeAudioActivityWitness {if(fail)error("Unavailable") else AudioActivities(emptySet(),emptySet())}
        assertTrue(witness.idle());fail=true;assertFalse(witness.idle())
    }
}
