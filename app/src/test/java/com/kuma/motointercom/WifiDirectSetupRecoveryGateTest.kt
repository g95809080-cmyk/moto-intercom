package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSetupRecoveryGateTest {
    @Test
    fun disabledStateStopsRetriesAndEnabledTransitionOwnsOneFreshSetup() {
        val gate = WifiDirectSetupRecoveryGate()
        assertFalse(gate.isEnabled)
        assertNull(gate.beginSetup())

        assertTrue(gate.updateP2pEnabled(true))
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

    @Test
    fun busyRetriesAreBoundedAndSuccessfulSetupResetsTheBudget() {
        val gate = WifiDirectSetupRecoveryGate(maxBusyRetries = 2)
        assertTrue(gate.updateP2pEnabled(true))

        val first = checkNotNull(gate.beginSetup())
        assertTrue(gate.scheduleRetry(first))
        assertTrue(gate.takeRetry(first))

        val second = checkNotNull(gate.beginSetup())
        assertTrue(gate.scheduleRetry(second))
        assertTrue(gate.takeRetry(second))

        val exhausted = checkNotNull(gate.beginSetup())
        assertFalse(gate.scheduleRetry(exhausted))
        assertTrue(gate.isBusyRetryExhausted(exhausted))

        gate.markSetupSucceeded(exhausted)
        val afterSuccess = checkNotNull(gate.beginSetup())
        assertTrue(gate.scheduleRetry(afterSuccess))
    }
}
