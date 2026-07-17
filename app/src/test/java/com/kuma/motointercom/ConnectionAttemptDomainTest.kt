package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptDomainTest {
    @Test
    fun attemptCreationRetainsCompleteDomainFields() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(
            clock = clock,
            id = ConnectionAttemptId("attempt-a"),
            runtimeSessionId = RuntimeSessionId("runtime-a"),
            targetDeviceId = "peer-a",
            expectedRemoteSessionId = RuntimeSessionId("runtime-peer-a"),
            trigger = ConnectionTrigger.RECOVERY,
            preferredTransport = Transport.WIFI_DIRECT
        )

        assertEquals(ConnectionAttemptId("attempt-a"), attempt.id)
        assertEquals(RuntimeSessionId("runtime-a"), attempt.runtimeSessionId)
        assertEquals("peer-a", attempt.targetDeviceId)
        assertEquals(RuntimeSessionId("runtime-peer-a"), attempt.targetLock.expectedRemoteSessionId)
        assertEquals(ConnectionTrigger.RECOVERY, attempt.trigger)
        assertEquals(Transport.WIFI_DIRECT, attempt.preferredTransport)
        assertEquals(ChannelPlan.single(Transport.WIFI_DIRECT), attempt.channelPlan)
        assertEquals(MonotonicTimestamp(11_000L), attempt.deadlineAt)
    }

    @Test
    fun deadlineAndIdentityDoNotChangeWhenClockAdvances() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val originalDeadline = attempt.deadlineAt
        val originalId = attempt.id
        val originalTarget = attempt.targetDeviceId

        clock.advanceBy(9_999L)

        assertEquals(MonotonicTimestamp(11_000L), originalDeadline)
        assertEquals(originalDeadline, attempt.deadlineAt)
        assertEquals(originalId, attempt.id)
        assertEquals(originalTarget, attempt.targetDeviceId)
    }

    @Test
    fun fakeClockAdvancesByExactDuration() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(123L))

        clock.advanceBy(877L)

        assertEquals(MonotonicTimestamp(1_000L), clock.now())
    }

    @Test
    fun fakeClockRejectsBackwardMovement() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(123L))

        assertThrows(IllegalArgumentException::class.java) {
            clock.advanceBy(-1L)
        }
    }

    @Test
    fun matchingEventBeforeDeadlineIsCurrent() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val event = eventFor(attempt, MonotonicTimestamp(10_999L))

        assertFalse(attempt.isExpiredAt(event.observedAt))
        assertTrue(attempt.accepts(event))
        assertFalse(attempt.isStale(event))
    }

    @Test
    fun matchingEventAtDeadlineIsStale() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val event = eventFor(attempt, MonotonicTimestamp(11_000L))

        assertTrue(attempt.isExpiredAt(event.observedAt))
        assertTrue(attempt.isStale(event))
    }

    @Test
    fun matchingEventAfterDeadlineIsStale() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val event = eventFor(attempt, MonotonicTimestamp(11_001L))

        assertTrue(attempt.isExpiredAt(event.observedAt))
        assertTrue(attempt.isStale(event))
    }

    @Test
    fun eventFromOldAttemptIsStale() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val event = eventFor(attempt, MonotonicTimestamp(2_000L)).copy(
            attemptId = ConnectionAttemptId("attempt-old")
        )

        assertNotEquals(attempt.id, event.attemptId)
        assertTrue(attempt.isStale(event))
    }

    @Test
    fun eventForDifferentTargetIsStale() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
        val attempt = ConnectionAttemptFixture.create(clock)
        val event = eventFor(attempt, MonotonicTimestamp(2_000L)).copy(
            targetDeviceId = "peer-other"
        )

        assertNotEquals(attempt.targetDeviceId, event.targetDeviceId)
        assertTrue(attempt.isStale(event))
    }

    @Test
    fun channelPlanRejectsEmptyTransportSet() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelPlan(emptySet())
        }
    }

    @Test
    fun channelPlanRejectsMultipleTransports() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelPlan(setOf(Transport.LAN, Transport.WIFI_DIRECT))
        }
    }

    @Test
    fun channelPlanSnapshotsMutableInput() {
        val source = mutableSetOf(Transport.LAN)
        val plan = ChannelPlan(source)

        source.clear()
        source += Transport.WIFI_DIRECT

        assertEquals(Transport.LAN, plan.transport)
        assertEquals(setOf(Transport.LAN), plan.plannedTransports)
        assertEquals(ChannelPlan.single(Transport.LAN), plan)
    }

    @Test
    fun cancelHasDistinctTerminalOutcome() {
        assertEquals(
            ConnectionAttemptTerminalOutcome.CANCELED,
            ConnectionAttemptTerminalOutcome.valueOf("CANCELED")
        )
    }

    @Test
    fun successHasDistinctTerminalOutcome() {
        assertEquals(
            ConnectionAttemptTerminalOutcome.SUCCESS,
            ConnectionAttemptTerminalOutcome.valueOf("SUCCESS")
        )
    }

    @Test
    fun timeoutHasDistinctTerminalOutcome() {
        assertEquals(
            ConnectionAttemptTerminalOutcome.TIMED_OUT,
            ConnectionAttemptTerminalOutcome.valueOf("TIMED_OUT")
        )
    }

    private fun eventFor(
        attempt: ConnectionAttempt,
        observedAt: MonotonicTimestamp
    ) = ConnectionAttemptEventContext(
        attemptId = attempt.id,
        targetDeviceId = attempt.targetDeviceId,
        observedAt = observedAt
    )
}
