package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptDeadlineSchedulerTest {
    @Test
    fun replacementAndCancellationInvalidateOldTimeoutCallbacks() {
        var now = 1_000L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val removed = mutableListOf<Runnable>()
        val timedOut = mutableListOf<ConnectionAttempt>()
        val scheduler = AttemptDeadlineScheduler(
            elapsedRealtime = { now },
            postDelayed = { callback, delay -> posted += callback to delay },
            removeCallbacks = removed::add,
            onTimedOut = timedOut::add
        )
        val attemptA = attempt("attempt-a", 2_000L)
        val attemptB = attempt("attempt-b", 3_000L)

        scheduler.schedule(attemptA)
        now = 1_500L
        scheduler.schedule(attemptB)

        assertEquals(listOf(1_000L, 1_500L), posted.map { it.second })
        assertEquals(listOf(posted[0].first), removed)
        posted[0].first.run()
        assertTrue(timedOut.isEmpty())

        scheduler.cancel(attemptA)
        posted[1].first.run()
        assertEquals(listOf(attemptB), timedOut)
    }

    @Test
    fun sameAttemptCanMoveFromTransportToDecisionDeadline() {
        var now = 1_000L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val timedOut = mutableListOf<ConnectionAttempt>()
        val scheduler = AttemptDeadlineScheduler(
            elapsedRealtime = { now },
            postDelayed = { callback, delay -> posted += callback to delay },
            removeCallbacks = {},
            onTimedOut = timedOut::add
        )
        val transportAttempt = attempt("attempt-a", 11_000L)
        val decisionAttempt = transportAttempt.copy(deadlineElapsedRealtimeMs = 26_000L)

        scheduler.schedule(transportAttempt)
        now = 2_000L
        scheduler.schedule(decisionAttempt)

        assertEquals(listOf(10_000L, 24_000L), posted.map { it.second })
        posted[0].first.run()
        assertTrue(timedOut.isEmpty())
        posted[1].first.run()
        assertEquals(listOf(decisionAttempt), timedOut)
    }

    @Test
    fun recoveryOpensOnlyItsSinglePlannedTransport() {
        val recovery = attempt("recovery", 5_000L).copy(trigger = ConnectionTrigger.RECOVERY)
        var lanOpened = false
        var wifiDirectOpened = false

        val opened = openPlannedTransport(
            recovery,
            openLan = {
                lanOpened = true
                true
            },
            openWifiDirect = {
                wifiDirectOpened = true
                true
            }
        )

        assertTrue(opened)
        assertTrue(lanOpened)
        assertFalse(wifiDirectOpened)
        assertEquals(TargetLock("peer-b", RuntimeSessionId("session-b")), recovery.targetLock)
    }

    private fun attempt(id: String, deadline: Long) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = RuntimeSessionId("runtime-current"),
        targetLock = TargetLock("peer-b", RuntimeSessionId("session-b")),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = deadline
    )
}
