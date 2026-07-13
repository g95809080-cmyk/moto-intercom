package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectGroupValidationGateTest {
    @Test
    fun expiresAtHardDeadlineWithoutAnyCallback() {
        var now = 1_000L
        val gate = WifiDirectGroupValidationGate { now }
        val session = gate.start(timeoutMillis = 30_000L)

        now = 30_999L
        assertFalse(gate.isExpired(session))
        now = 31_000L
        assertTrue(gate.isExpired(session))
    }

    @Test
    fun resetInvalidatesOldCallback() {
        val gate = WifiDirectGroupValidationGate { 0L }
        val oldSession = gate.start(timeoutMillis = 30_000L)

        gate.cancel()

        assertFalse(gate.isCurrent(oldSession))
        assertFalse(gate.isExpired(oldSession))
    }

    @Test
    fun closeInvalidatesOldCallback() {
        val gate = WifiDirectGroupValidationGate { 0L }
        val oldSession = gate.start(timeoutMillis = 30_000L)

        gate.cancel()

        assertFalse(gate.isCurrent(oldSession))
    }

    @Test
    fun newValidationInvalidatesOldCallback() {
        val gate = WifiDirectGroupValidationGate { 0L }
        val oldSession = gate.start(timeoutMillis = 30_000L)

        val newSession = gate.start(timeoutMillis = 30_000L)

        assertFalse(gate.isCurrent(oldSession))
        assertTrue(gate.isCurrent(newSession))
    }
}
