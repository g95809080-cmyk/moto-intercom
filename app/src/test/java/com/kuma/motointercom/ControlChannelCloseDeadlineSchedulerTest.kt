package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlChannelCloseDeadlineSchedulerTest {
    @Test
    fun exactDeadlineAndReplacementInvalidateTheOldCallback() {
        var now = 100L
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val removed = mutableListOf<Runnable>()
        val timedOut = mutableListOf<ControlChannelCloseDeadline>()
        val scheduler = scheduler(now = { now }, posted, removed, timedOut)
        val first = deadline(CHANNEL_A, scheduledAt = 1_100L)
        val replacement = first.copy(scheduledAtElapsedMs = 1_500L)

        scheduler.schedule(first)
        scheduler.schedule(first)
        assertEquals(listOf(1_000L), posted.map { it.second })

        now = 200L
        scheduler.schedule(replacement)
        assertEquals(listOf(1_000L, 1_300L), posted.map { it.second })
        assertEquals(listOf(posted.first().first), removed)

        posted.first().first.run()
        assertTrue(timedOut.isEmpty())
        posted.last().first.run()
        assertEquals(listOf(replacement), timedOut)
    }

    @Test
    fun exactChannelAndRuntimeCancellationLeaveOtherDeadlinesActive() {
        val posted = mutableListOf<Pair<Runnable, Long>>()
        val removed = mutableListOf<Runnable>()
        val timedOut = mutableListOf<ControlChannelCloseDeadline>()
        val scheduler = scheduler(now = { 2_000L }, posted, removed, timedOut)
        val current = deadline(CHANNEL_A, scheduledAt = 1_500L)
        val otherChannel = deadline(CHANNEL_B, scheduledAt = 3_000L)
        val otherRuntime = deadline(CHANNEL_C, scheduledAt = 3_000L).copy(
            runtimeSessionId = RuntimeSessionId(RUNTIME_B)
        )

        scheduler.schedule(current)
        scheduler.schedule(otherChannel)
        scheduler.schedule(otherRuntime)
        assertEquals(listOf(0L, 1_000L, 1_000L), posted.map { it.second })

        scheduler.cancel(current.runtimeSessionId, current.attemptId, current.channelId)
        scheduler.cancelRuntime(otherRuntime.runtimeSessionId)
        posted.forEach { it.first.run() }

        assertEquals(listOf(otherChannel), timedOut)
        assertEquals(2, removed.size)
    }

    private fun scheduler(
        now: () -> Long,
        posted: MutableList<Pair<Runnable, Long>>,
        removed: MutableList<Runnable>,
        timedOut: MutableList<ControlChannelCloseDeadline>
    ) = ControlChannelCloseDeadlineScheduler(
        elapsedRealtime = now,
        postDelayed = { callback, delay -> posted += callback to delay },
        removeCallbacks = removed::add,
        onTimedOut = timedOut::add
    )

    private fun deadline(channelId: String, scheduledAt: Long) =
        ControlChannelCloseDeadline(
            runtimeSessionId = RuntimeSessionId(RUNTIME_A),
            attemptId = ConnectionAttemptId(ATTEMPT_A),
            channelId = ControlChannelId.parse(channelId),
            scheduledAtElapsedMs = scheduledAt
        )

    companion object {
        private const val RUNTIME_A = "10000000-0000-4000-8000-000000000001"
        private const val RUNTIME_B = "10000000-0000-4000-8000-000000000002"
        private const val ATTEMPT_A = "20000000-0000-4000-8000-000000000001"
        private const val CHANNEL_A = "30000000-0000-4000-8000-000000000001"
        private const val CHANNEL_B = "30000000-0000-4000-8000-000000000002"
        private const val CHANNEL_C = "30000000-0000-4000-8000-000000000003"
    }
}
