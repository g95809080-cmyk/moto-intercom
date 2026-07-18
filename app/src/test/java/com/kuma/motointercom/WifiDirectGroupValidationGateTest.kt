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

    @Test
    fun targetedValidationUsesAttemptDeadlineInsteadOfLocalThirtySeconds() {
        var now = 1_000L
        val gate = WifiDirectGroupValidationGate { now }
        val attempt = ConnectionAttemptFixture.create(
            clock = FakeMonotonicClock(MonotonicTimestamp(now)),
            preferredTransport = Transport.WIFI_DIRECT,
            timeoutMs = 250L
        )
        val session = gate.start(
            timeoutMillis = 30_000L,
            taskContext = AttemptTaskContext(attempt, generation = 7)
        )

        assertTrue(gate.isCurrent(session))
        assertTrue(gate.remainingMillis(session) == 250L)
        now = 1_249L
        assertFalse(gate.isExpired(session))
        now = 1_250L
        assertTrue(gate.isExpired(session))
    }
}
