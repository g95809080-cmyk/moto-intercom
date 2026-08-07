package com.kuma.motointercom

internal class WifiDirectPendingDiscoveryRecovery(
    private val maxAttempts: Int = 4
) {
    enum class Action {
        DISCOVER,
        REPRIME_REQUEST,
        REREGISTER_LOCAL_SERVICE
    }

    data class Retry(
        val generation: Int,
        val attempt: Int,
        val action: Action
    )

    private var generation = 0
    private var attempts = 0

    val currentGeneration: Int
        get() = generation

    fun invalidate() {
        generation++
        attempts = 0
    }

    fun next(
        hasPendingPeers: Boolean,
        hasAcceptedPeers: Boolean,
        blocked: Boolean
    ): Retry? {
        if (!hasPendingPeers || hasAcceptedPeers || blocked || attempts >= maxAttempts) {
            return null
        }
        val attempt = ++attempts
        return Retry(
            generation = generation,
            attempt = attempt,
            action = when {
                attempt == 1 -> Action.DISCOVER
                attempt == maxAttempts -> Action.REREGISTER_LOCAL_SERVICE
                else -> Action.REPRIME_REQUEST
            }
        )
    }

    fun isCurrent(retry: Retry): Boolean = retry.generation == generation
}
