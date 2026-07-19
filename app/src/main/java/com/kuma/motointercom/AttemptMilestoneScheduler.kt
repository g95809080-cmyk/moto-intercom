package com.kuma.motointercom

internal class AttemptMilestoneScheduler(
    private val elapsedRealtime: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onElapsed: (AttemptMilestone) -> Unit
) {
    private data class ScheduledMilestone(
        val milestone: AttemptMilestone,
        val callback: Runnable
    )

    private val scheduled = linkedMapOf<AttemptMilestoneKind, ScheduledMilestone>()

    fun schedule(milestone: AttemptMilestone) {
        val existing = scheduled[milestone.kind]
        if (existing?.milestone == milestone) return
        if (existing != null) {
            scheduled.remove(milestone.kind)
            removeCallbacks(existing.callback)
        }
        lateinit var callback: Runnable
        callback = Runnable {
            val current = scheduled[milestone.kind]
            if (current?.milestone != milestone || current.callback !== callback) return@Runnable
            scheduled.remove(milestone.kind)
            onElapsed(milestone)
        }
        scheduled[milestone.kind] = ScheduledMilestone(milestone, callback)
        postDelayed(
            callback,
            (milestone.scheduledAt.elapsedRealtimeMs - elapsedRealtime()).coerceAtLeast(0L)
        )
    }

    fun cancel(attempt: ConnectionAttempt? = null) {
        val matching = scheduled.values.filter {
            attempt == null || it.milestone.attempt == attempt
        }
        matching.forEach {
            scheduled.remove(it.milestone.kind)
            removeCallbacks(it.callback)
        }
    }

    fun cancelRuntime(runtimeSessionId: RuntimeSessionId) {
        val matching = scheduled.values.filter {
            it.milestone.attempt.runtimeSessionId == runtimeSessionId
        }
        matching.forEach {
            scheduled.remove(it.milestone.kind)
            removeCallbacks(it.callback)
        }
    }
}
