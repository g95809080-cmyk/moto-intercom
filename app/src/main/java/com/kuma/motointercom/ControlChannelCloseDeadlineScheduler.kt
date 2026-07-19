package com.kuma.motointercom

internal data class ControlChannelCloseDeadline(
    val runtimeSessionId: RuntimeSessionId,
    val attemptId: ConnectionAttemptId,
    val channelId: ControlChannelId,
    val scheduledAtElapsedMs: Long
) {
    init {
        require(scheduledAtElapsedMs >= 0L) {
            "Control channel close deadline must not be negative"
        }
    }
}

internal class ControlChannelCloseDeadlineScheduler(
    private val elapsedRealtime: () -> Long,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val onTimedOut: (ControlChannelCloseDeadline) -> Unit
) {
    private data class Key(
        val runtimeSessionId: RuntimeSessionId,
        val attemptId: ConnectionAttemptId,
        val channelId: ControlChannelId
    )

    private data class ScheduledClose(
        val deadline: ControlChannelCloseDeadline,
        val callback: Runnable
    )

    private val scheduled = linkedMapOf<Key, ScheduledClose>()

    fun schedule(deadline: ControlChannelCloseDeadline) {
        val key = deadline.key()
        val existing = scheduled[key]
        if (existing?.deadline == deadline) return
        if (existing != null) {
            scheduled.remove(key)
            removeCallbacks(existing.callback)
        }
        lateinit var callback: Runnable
        callback = Runnable {
            val current = scheduled[key]
            if (current?.deadline != deadline || current.callback !== callback) return@Runnable
            scheduled.remove(key)
            onTimedOut(deadline)
        }
        scheduled[key] = ScheduledClose(deadline, callback)
        postDelayed(
            callback,
            (deadline.scheduledAtElapsedMs - elapsedRealtime()).coerceAtLeast(0L)
        )
    }

    fun cancel(
        runtimeSessionId: RuntimeSessionId,
        attemptId: ConnectionAttemptId,
        channelId: ControlChannelId
    ) {
        val current = scheduled.remove(Key(runtimeSessionId, attemptId, channelId)) ?: return
        removeCallbacks(current.callback)
    }

    fun cancelRuntime(runtimeSessionId: RuntimeSessionId) {
        val matching = scheduled.filterKeys { it.runtimeSessionId == runtimeSessionId }.keys.toList()
        matching.forEach { key ->
            val current = scheduled.remove(key) ?: return@forEach
            removeCallbacks(current.callback)
        }
    }

    fun cancel() {
        scheduled.values.forEach { removeCallbacks(it.callback) }
        scheduled.clear()
    }

    private fun ControlChannelCloseDeadline.key() =
        Key(runtimeSessionId, attemptId, channelId)
}
