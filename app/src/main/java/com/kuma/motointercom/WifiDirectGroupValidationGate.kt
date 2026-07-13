package com.kuma.motointercom

internal class WifiDirectGroupValidationGate(
    private val nowMillis: () -> Long
) {
    class Session internal constructor(
        internal val generation: Int,
        internal val deadlineMillis: Long
    )

    private var generation = 0

    fun start(timeoutMillis: Long): Session =
        Session(++generation, nowMillis() + timeoutMillis)

    fun cancel() {
        generation++
    }

    fun isCurrent(session: Session): Boolean = session.generation == generation

    fun isExpired(session: Session): Boolean =
        isCurrent(session) && nowMillis() >= session.deadlineMillis

    fun remainingMillis(session: Session): Long =
        (session.deadlineMillis - nowMillis()).coerceAtLeast(0L)
}
