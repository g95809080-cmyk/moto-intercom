package com.kuma.motointercom

internal class AttemptDeadlineScheduler(
    private val elapsedRealtime: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onTimedOut: (ConnectionAttempt) -> Unit
) {
    private data class ScheduledAttempt(
        val attempt: ConnectionAttempt,
        val callback: Runnable
    )

    private var scheduled: ScheduledAttempt? = null

    fun schedule(attempt: ConnectionAttempt) {
        if (scheduled?.attempt?.id == attempt.id) return
        cancel()
        lateinit var callback: Runnable
        callback = Runnable {
            val current = scheduled
            if (current?.attempt != attempt || current.callback !== callback) return@Runnable
            scheduled = null
            onTimedOut(attempt)
        }
        scheduled = ScheduledAttempt(attempt, callback)
        postDelayed(
            callback,
            (attempt.deadlineElapsedRealtimeMs - elapsedRealtime()).coerceAtLeast(0L)
        )
    }

    fun cancel(attempt: ConnectionAttempt? = null) {
        val current = scheduled ?: return
        if (attempt != null && current.attempt != attempt) return
        scheduled = null
        removeCallbacks(current.callback)
    }

    fun cancelRuntime(runtimeSessionId: RuntimeSessionId) {
        val current = scheduled ?: return
        if (current.attempt.runtimeSessionId == runtimeSessionId) cancel(current.attempt)
    }
}
