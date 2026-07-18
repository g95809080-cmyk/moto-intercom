package com.kuma.motointercom

internal class WifiDirectGroupValidationGate(
    private val nowMillis: () -> Long
) {
    class Session internal constructor(
        internal val generation: Int,
        internal val deadlineMillis: Long,
        internal val taskContext: AttemptTaskContext?
    )

    private var generation = 0

    fun start(
        timeoutMillis: Long,
        taskContext: AttemptTaskContext? = null
    ): Session {
        require(timeoutMillis >= 0L) { "Validation timeout must not be negative" }
        val localDeadline = Math.addExact(nowMillis(), timeoutMillis)
        val deadline = minOf(
            localDeadline,
            taskContext?.attempt?.deadlineElapsedRealtimeMs ?: localDeadline
        )
        return Session(++generation, deadline, taskContext)
    }

    fun cancel() {
        generation++
    }

    fun isCurrent(session: Session): Boolean = session.generation == generation

    fun isExpired(session: Session): Boolean =
        isCurrent(session) && nowMillis() >= session.deadlineMillis

    fun remainingMillis(session: Session): Long =
        (session.deadlineMillis - nowMillis()).coerceAtLeast(0L)
}
