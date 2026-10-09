package com.kuma.motointercom

internal class VoxGate(
    enabled: Boolean,
    sensitivity: Int = DEFAULT_VOX_SENSITIVITY
) {
    enum class State { BYPASS, LISTENING, OPEN, HANGOVER }

    data class Decision(
        val state: State,
        val trackVolume: Double,
        val stateChanged: Boolean,
        val openThreshold: Double,
        val closeThreshold: Double,
        val noiseFloor: Double
    )

    private var enabled = enabled
    private var sensitivity = sensitivity.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
    private var state = if (enabled) State.LISTENING else State.BYPASS
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var calibrationUntil = 0L
    private var attackStartedAt = 0L
    private var aboveCloseStartedAt = 0L
    private var lastVoiceAt = 0L
    private var hangoverStartedAt = 0L
    private val baseOpenThreshold: Double
        get() = DEFAULT_OPEN_THRESHOLD +
            (DEFAULT_VOX_SENSITIVITY - sensitivity) * SENSITIVITY_DB_PER_STEP
    private val noiseMargin: Double
        get() = NOISE_MARGIN + (DEFAULT_VOX_SENSITIVITY - sensitivity) * NOISE_MARGIN_DB_PER_STEP

    @Synchronized
    fun updateSettings(enabled: Boolean, sensitivity: Int) {
        val modeChanged = this.enabled != enabled
        this.enabled = enabled
        this.sensitivity = sensitivity.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
        attackStartedAt = 0L
        aboveCloseStartedAt = 0L
        if (modeChanged) {
            state = if (enabled) State.LISTENING else State.BYPASS
            lastVoiceAt = 0L
            hangoverStartedAt = 0L
        }
        // Keep the learned noise floor and calibration deadline when dragging.
    }

    @Synchronized
    fun currentState(): State = state

    @Synchronized
    fun update(level: Double, nowMs: Long): Decision {
        if (!enabled) return decision(State.BYPASS, changed = false)
        if (calibrationUntil == 0L) calibrationUntil = nowMs + CALIBRATION_MS

        var openThreshold = maxOf(baseOpenThreshold, noiseFloor + noiseMargin)
        var closeThreshold = closingThreshold(openThreshold)
        val previous = state

        when (state) {
            State.BYPASS -> Unit
            State.LISTENING -> {
                val calibrating = nowMs < calibrationUntil
                val alpha = if (calibrating) CALIBRATION_ALPHA else NOISE_ALPHA
                if (calibrating || level < openThreshold) {
                    noiseFloor = (noiseFloor + alpha * (level - noiseFloor))
                        .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                    openThreshold = maxOf(baseOpenThreshold, noiseFloor + noiseMargin)
                    closeThreshold = closingThreshold(openThreshold)
                }

                if (!calibrating && level >= openThreshold) {
                    if (attackStartedAt == 0L) attackStartedAt = nowMs
                    if (nowMs - attackStartedAt >= ATTACK_MS) {
                        attackStartedAt = 0L
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                    }
                } else {
                    attackStartedAt = 0L
                }
            }
            State.OPEN -> {
                if (level >= closeThreshold) {
                    if (aboveCloseStartedAt == 0L) aboveCloseStartedAt = nowMs
                    if (nowMs - aboveCloseStartedAt >= ATTACK_MS) lastVoiceAt = nowMs
                } else {
                    aboveCloseStartedAt = 0L
                }
                if (nowMs - lastVoiceAt >= RELEASE_DEBOUNCE_MS) {
                    aboveCloseStartedAt = 0L
                    hangoverStartedAt = nowMs
                    state = State.HANGOVER
                }
            }
            State.HANGOVER -> {
                if (level >= openThreshold) {
                    if (attackStartedAt == 0L) attackStartedAt = nowMs
                    if (nowMs - attackStartedAt >= ATTACK_MS) {
                        attackStartedAt = 0L
                        hangoverStartedAt = 0L
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                    }
                } else {
                    attackStartedAt = 0L
                }

                if (level >= closeThreshold) {
                    if (aboveCloseStartedAt == 0L) aboveCloseStartedAt = nowMs
                    if (nowMs - aboveCloseStartedAt >= ATTACK_MS) hangoverStartedAt = nowMs
                } else {
                    aboveCloseStartedAt = 0L
                }

                if (state == State.HANGOVER && nowMs - hangoverStartedAt >= HANGOVER_MS) {
                    attackStartedAt = 0L
                    aboveCloseStartedAt = 0L
                    lastVoiceAt = 0L
                    hangoverStartedAt = 0L
                    state = State.LISTENING
                }
            }
        }

        return Decision(
            state = state,
            trackVolume = if (state == State.LISTENING) MUTED_VOLUME else OPEN_VOLUME,
            stateChanged = previous != state,
            openThreshold = openThreshold,
            closeThreshold = closeThreshold,
            noiseFloor = noiseFloor
        )
    }

    private fun decision(value: State, changed: Boolean) = Decision(
        state = value,
        trackVolume = if (value == State.LISTENING) MUTED_VOLUME else OPEN_VOLUME,
        stateChanged = changed,
        openThreshold = maxOf(baseOpenThreshold, noiseFloor + noiseMargin),
        closeThreshold = closingThreshold(maxOf(baseOpenThreshold, noiseFloor + noiseMargin)),
        noiseFloor = noiseFloor
    )

    private fun closingThreshold(openThreshold: Double): Double =
        maxOf(openThreshold - HYSTERESIS, noiseFloor + CLOSE_NOISE_MARGIN)

    private companion object {
        const val OPEN_VOLUME = 1.0
        const val MUTED_VOLUME = 0.0
        const val DEFAULT_OPEN_THRESHOLD = 40.0
        const val SENSITIVITY_DB_PER_STEP = 0.24
        const val NOISE_MARGIN = 8.0
        const val NOISE_MARGIN_DB_PER_STEP = 0.10
        const val CLOSE_NOISE_MARGIN = 1.0
        const val HYSTERESIS = 5.0
        const val ATTACK_MS = 25L
        const val RELEASE_DEBOUNCE_MS = 120L
        const val HANGOVER_MS = 700L
        const val CALIBRATION_MS = 500L
        const val CALIBRATION_ALPHA = 0.10
        const val NOISE_ALPHA = 0.02
        const val INITIAL_NOISE_FLOOR = 32.0
        const val MIN_NOISE_FLOOR = 20.0
        const val MAX_NOISE_FLOOR = 55.0
    }
}
