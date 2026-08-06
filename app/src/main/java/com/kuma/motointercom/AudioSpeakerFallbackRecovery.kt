package com.kuma.motointercom

internal class AudioSpeakerFallbackRecovery(
    private val maxAttempts: Int = 3
) {
    data class Attempt(
        val generation: Int,
        val number: Int
    )

    private var generation = 0
    private var attempts = 0

    val currentGeneration: Int
        get() = generation

    fun reset() {
        generation++
        attempts = 0
    }

    fun next(): Attempt? {
        if (attempts >= maxAttempts) return null
        return Attempt(generation = generation, number = ++attempts)
    }

    fun isCurrent(attempt: Attempt): Boolean = attempt.generation == generation
}
