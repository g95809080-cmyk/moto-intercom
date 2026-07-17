package com.kuma.motointercom

internal class WifiDirectSetupRecoveryGate {
    class Session internal constructor(internal val generation: Int)

    private var p2pEnabled = true
    private var generation = 0
    private var retryGeneration: Int? = null

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
        if (!isCurrent(session) || retryGeneration != null) return false
        retryGeneration = session.generation
        return true
    }

    fun takeRetry(session: Session): Boolean {
        if (!isCurrent(session) || retryGeneration != session.generation) return false
        retryGeneration = null
        return true
    }

    fun cancel() {
        generation++
        retryGeneration = null
    }

    fun isCurrent(session: Session): Boolean =
        p2pEnabled && session.generation == generation
}
