package com.kuma.motointercom

internal class WifiDirectSetupRecoveryGate(
    private val maxBusyRetries: Int = 4
) {
    init {
        require(maxBusyRetries > 0) { "maxBusyRetries must be positive" }
    }

    class Session internal constructor(internal val generation: Int)

    private var p2pEnabled = false
    private var generation = 0
    private var retryGeneration: Int? = null
    private var busyRetryCount = 0

    val isEnabled: Boolean
        get() = p2pEnabled

    fun updateP2pEnabled(enabled: Boolean): Boolean {
        if (p2pEnabled == enabled) return false
        p2pEnabled = enabled
        cancel()
        return enabled
    }

    fun beginSetup(): Session? {
        if (!p2pEnabled) return null
        retryGeneration = null
        return Session(++generation)
    }

    fun scheduleRetry(session: Session): Boolean {
        if (
            !isCurrent(session) ||
                retryGeneration != null ||
                busyRetryCount >= maxBusyRetries
        ) return false
        busyRetryCount++
        retryGeneration = session.generation
        return true
    }

    fun isBusyRetryExhausted(session: Session): Boolean =
        isCurrent(session) && retryGeneration == null && busyRetryCount >= maxBusyRetries

    fun markSetupSucceeded(session: Session) {
        if (!isCurrent(session)) return
        retryGeneration = null
        busyRetryCount = 0
    }

    fun takeRetry(session: Session): Boolean {
        if (!isCurrent(session) || retryGeneration != session.generation) return false
        retryGeneration = null
        return true
    }

    fun cancel() {
        generation++
        retryGeneration = null
        busyRetryCount = 0
    }

    fun isCurrent(session: Session): Boolean =
        p2pEnabled && session.generation == generation
}
