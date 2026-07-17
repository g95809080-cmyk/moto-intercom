package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingConfirmationDeadlineSchedulerTest {
    @Test
    fun replacementAndNonceScopedCancelInvalidateOldCallbacks() {
        var now = 100L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val removed = mutableListOf<Runnable>()
        val timedOut = mutableListOf<IncomingConfirmationPrompt>()
        val scheduler = IncomingConfirmationDeadlineScheduler(
            elapsedRealtime = { now },
            postDelayed = { callback, delay -> posted += callback to delay },
            removeCallbacks = removed::add,
            onTimedOut = timedOut::add
        )
        val first = prompt("nonce-a", deadline = 1_000L)
        val second = prompt("nonce-b", deadline = 1_500L)

        scheduler.schedule(first)
        assertEquals(900L, posted.single().second)
        scheduler.schedule(second)
        assertTrue(removed.contains(posted.first().first))
        assertEquals(1_400L, posted.last().second)

        posted.first().first.run()
        assertTrue(timedOut.isEmpty())
        scheduler.cancel(
            runtimeSessionId = second.runtimeSessionId,
            attemptId = second.attemptId,
            actionNonce = first.actionNonce
        )
        posted.last().first.run()
        assertEquals(listOf(second), timedOut)

        now = 2_000L
        scheduler.schedule(first)
        assertEquals(0L, posted.last().second)
        scheduler.cancel(first.runtimeSessionId, first.attemptId, first.actionNonce)
        posted.last().first.run()
        assertEquals(listOf(second), timedOut)
    }

    private fun prompt(nonce: String, deadline: Long) = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("10000000-0000-4000-8000-000000000001"),
        attemptId = ConnectionAttemptId("20000000-0000-4000-8000-000000000001"),
        channelId = ControlChannelId.parse("30000000-0000-4000-8000-000000000001"),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "40000000-0000-4000-8000-000000000001",
            nickname = "Rider",
            deviceName = "Phone",
            runtimeSessionId = RuntimeSessionId("50000000-0000-4000-8000-000000000001"),
            isDeviceIdVerified = true
        ),
        decisionDeadlineElapsedMs = deadline,
        surface = ConfirmationSurface.IN_APP
    )
}
