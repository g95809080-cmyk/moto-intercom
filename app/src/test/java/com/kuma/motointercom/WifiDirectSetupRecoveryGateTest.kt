package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSetupRecoveryGateTest {
    @Test
    fun disabledStateStopsRetriesAndEnabledTransitionOwnsOneFreshSetup() {
        val gate = WifiDirectSetupRecoveryGate()
        val initialSetup = checkNotNull(gate.beginSetup())
        assertTrue(gate.scheduleRetry(initialSetup))

        assertFalse(gate.updateP2pEnabled(false))
        assertFalse(gate.isEnabled)
        assertFalse(gate.takeRetry(initialSetup))
        assertNull(gate.beginSetup())

        assertTrue(gate.updateP2pEnabled(true))
        assertFalse(gate.updateP2pEnabled(true))
        val restoredSetup = checkNotNull(gate.beginSetup())
        assertTrue(gate.scheduleRetry(restoredSetup))
        assertFalse(gate.scheduleRetry(restoredSetup))

        val replacementSetup = checkNotNull(gate.beginSetup())
        assertFalse(gate.takeRetry(restoredSetup))
        assertTrue(gate.scheduleRetry(replacementSetup))
        assertTrue(gate.takeRetry(replacementSetup))
    }
}
