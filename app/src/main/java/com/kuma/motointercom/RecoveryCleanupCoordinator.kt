package com.kuma.motointercom

internal data class RecoveryCleanupRequest(
    val runtimeSessionId: RuntimeSessionId,
    val nextAttempt: ConnectionAttempt?,
    val restartDelayMillis: Long,
    val resetEffect: SessionEffect.ResetWirelessEnvironment? = null
) {
    init {
        require(restartDelayMillis >= 0L) { "Restart delay must not be negative" }
        require(nextAttempt == null || nextAttempt.runtimeSessionId == runtimeSessionId) {
            "Recovery attempt must belong to the cleanup runtime"
        }
        require(
            resetEffect == null ||
                (nextAttempt == null && resetEffect.runtimeSessionId == runtimeSessionId)
        ) {
            "Reset cleanup must belong to the cleanup runtime and have no next attempt"
        }
    }
}

internal class RecoveryCleanupCoordinator(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val restart: (RecoveryCleanupRequest) -> Boolean
) {
    private data class ActiveCleanup(
        val token: Long,
        var request: RecoveryCleanupRequest,
        var cleanupComplete: Boolean = false,
        var restartCallback: Runnable? = null
    )

    private var nextToken = 0L
    private var active: ActiveCleanup? = null

    fun updateIfActive(request: RecoveryCleanupRequest): Boolean {
        val current = active ?: return false
        if (current.request.runtimeSessionId != request.runtimeSessionId) return true
        current.request = request
        if (current.cleanupComplete) scheduleRestart(current)
        return true
    }

    fun start(request: RecoveryCleanupRequest): Long {
        check(active == null) { "Recovery cleanup is already active" }
        val token = ++nextToken
        active = ActiveCleanup(token, request)
        return token
    }

    fun complete(token: Long) {
        val current = active?.takeIf { it.token == token } ?: return
        if (current.cleanupComplete) return
        current.cleanupComplete = true
        scheduleRestart(current)
    }

    fun cancel() {
        active?.restartCallback?.let(removeCallbacks)
        active = null
    }

    private fun scheduleRestart(current: ActiveCleanup) {
        current.restartCallback?.let(removeCallbacks)
        lateinit var callback: Runnable
        callback = Runnable {
            val latest = active
            if (latest !== current || latest.restartCallback !== callback) return@Runnable
            latest.restartCallback = null
            if (restart(latest.request)) active = null
        }
        current.restartCallback = callback
        postDelayed(callback, current.request.restartDelayMillis)
    }
}
