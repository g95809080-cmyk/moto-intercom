package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test

class CloudVoxControllerTest {
    private fun controls(revision: Long=1,muted: Boolean=false,enabled: Boolean=true,sensitivity: Int=50) =
        VersionedAudioControls(revision,AudioControlSettings(muted,enabled,sensitivity))
    @Test fun captureKeepsSamplingWithSendingClosedAndUsesAttackAndHangover() {
        val gate=CloudVoxController(controls())
        var now=1_000L
        repeat(30) { gate.sample(20,now); now+=20 }
        assertFalse(gate.current(true).sendEnabled)
        var output=gate.sample(90,now)
        repeat(4) { now+=20; output=gate.sample(90,now) }
        assertTrue(output.sendEnabled); assertEquals(VoxRuntimeState.OPEN,output.state)
        repeat(10) { now+=20; output=gate.sample(0,now) }
        assertEquals(VoxRuntimeState.HANGOVER,output.state); assertTrue(output.sendEnabled)
        repeat(40) { now+=20; output=gate.sample(0,now) }
        assertFalse(output.sendEnabled); assertEquals(VoxRuntimeState.LISTENING,output.state)
    }
    @Test fun muteWinsAcrossSettingsChangesAndResetCannotReopenIt() {
        val gate=CloudVoxController(controls(enabled=false))
        assertTrue(gate.current(true).sendEnabled)
        gate.update(controls(2,muted=true,enabled=false))
        gate.update(controls(3,muted=true,enabled=true,sensitivity=100))
        repeat(60) { assertFalse(gate.sample(100,1_000L+it*20).sendEnabled) }
        gate.reset(); assertEquals(VoxRuntimeState.MUTED,gate.current(true).state)
        assertFalse(gate.update(controls(1,enabled=false)))
        assertFalse(gate.current(true).sendEnabled)
    }
    @Test fun unmuteAndInterruptionResetRequireANewOpeningAttack() {
        val gate=CloudVoxController(controls(muted=true))
        gate.update(controls(2))
        assertFalse(gate.current(true).sendEnabled)
        repeat(40) { gate.sample(90,1_000L+it*20) }
        gate.reset()
        assertFalse(gate.current(true).sendEnabled)
        assertEquals(VoxRuntimeState.IDLE,gate.current(false).state)
        assertFalse(gate.current(false).sendEnabled)
    }
}
