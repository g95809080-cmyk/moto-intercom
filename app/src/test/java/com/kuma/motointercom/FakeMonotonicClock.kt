package com.kuma.motointercom

internal class FakeMonotonicClock(
    initialNow: MonotonicTimestamp = MonotonicTimestamp(0L)
) : MonotonicClock {
    private var current = initialNow

    override fun now(): MonotonicTimestamp = current

    fun advanceBy(durationMs: Long) {
        require(durationMs >= 0L) { "Fake monotonic clock cannot move backward" }
        current = MonotonicTimestamp(
            Math.addExact(current.elapsedRealtimeMs, durationMs)
        )
    }
}
