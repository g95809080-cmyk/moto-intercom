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
    private var foregroundPeak = 0.0
    private var attackMinimum = 0.0
    private var peakMinimum = 0.0
    private var peakStartedAt = 0L
    private var backgroundMinimum = 0.0
    private var backgroundMaximum = 0.0
    private var backgroundStartedAt = 0L
    private var lastSampleAt: Long? = null
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
            clearActiveBackground()
            lastSampleAt = null
        }
        // Keep the learned noise floor and calibration deadline when dragging.
    }

    @Synchronized
    fun currentState(): State = state

    @Synchronized
    fun update(level: Double, nowMs: Long): Decision {
        if (!enabled) return decision(State.BYPASS, changed = false)
        if (calibrationUntil == 0L) calibrationUntil = nowMs + CALIBRATION_MS
        if (lastSampleAt?.let { nowMs < it || nowMs - it > MAX_FRAME_GAP_MS } == true) {
            backgroundStartedAt = 0L
            peakStartedAt = 0L
            attackStartedAt = 0L
            aboveCloseStartedAt = 0L
        }
        lastSampleAt = nowMs

        val background = if (state == State.OPEN || state == State.HANGOVER) {
            observeForegroundPeak(level, nowMs)
            learnActiveBackground(level, nowMs)
        } else false

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
                        .coerceIn(MIN_NOISE_FLOOR, if (calibrating) MAX_CALIBRATION_NOISE else MAX_NOISE_FLOOR)
                    openThreshold = maxOf(baseOpenThreshold, noiseFloor + noiseMargin)
                    closeThreshold = closingThreshold(openThreshold)
                }

                if (!calibrating && level >= openThreshold) {
                    if (attackStartedAt == 0L) {
                        attackStartedAt = nowMs
                        attackMinimum = level
                    } else attackMinimum = minOf(attackMinimum, level)
                    if (nowMs - attackStartedAt >= ATTACK_MS) {
                        attackStartedAt = 0L
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                        foregroundPeak = attackMinimum
                    }
                } else {
                    attackStartedAt = 0L
                }
            }
            State.OPEN -> {
                if (!background && level >= closeThreshold) {
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
                if (!background && level >= openThreshold) {
                    if (attackStartedAt == 0L) {
                        attackStartedAt = nowMs
                        attackMinimum = level
                    } else attackMinimum = minOf(attackMinimum, level)
                    if (nowMs - attackStartedAt >= ATTACK_MS) {
                        attackStartedAt = 0L
                        hangoverStartedAt = 0L
                        state = State.OPEN
                        aboveCloseStartedAt = nowMs
                        lastVoiceAt = nowMs
                        clearActiveBackground()
                        foregroundPeak = attackMinimum
                    }
                } else {
                    attackStartedAt = 0L
                }

                // Only a new opening attack may restart speech. Close-band energy is tail,
                // not evidence to renew this deadline indefinitely.
                if (state == State.HANGOVER && nowMs - hangoverStartedAt >= HANGOVER_MS) {
                    attackStartedAt = 0L
                    aboveCloseStartedAt = 0L
                    lastVoiceAt = 0L
                    hangoverStartedAt = 0L
                    state = State.LISTENING
                    clearActiveBackground()
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

    private fun learnActiveBackground(level: Double, nowMs: Long): Boolean {
        // Conservative energy evidence, not semantic VAD: a lower, stationary plateau near
        // the learned background. Constant foreground and ordinary syllable dips stay open.
        if (foregroundPeak - level < BACKGROUND_DROP_DB || level > noiseFloor + BACKGROUND_BAND_DB) {
            backgroundStartedAt = 0L
            return false
        }
        val minimum = minOf(backgroundMinimum, level)
        val maximum = maxOf(backgroundMaximum, level)
        if (backgroundStartedAt == 0L || maximum - minimum > BACKGROUND_VARIATION_DB) {
            backgroundMinimum = level
            backgroundMaximum = level
            backgroundStartedAt = nowMs
            return false
        }
        backgroundMinimum = minimum
        backgroundMaximum = maximum
        if (nowMs - backgroundStartedAt < BACKGROUND_SETTLE_MS) return false
        noiseFloor = (noiseFloor + ACTIVE_NOISE_ALPHA * (level - noiseFloor))
            .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
        return true
    }

    private fun observeForegroundPeak(level: Double, nowMs: Long) {
        if (level <= foregroundPeak) {
            peakStartedAt = 0L
        } else {
            if (peakStartedAt == 0L) {
                peakStartedAt = nowMs
                peakMinimum = level
            } else peakMinimum = minOf(peakMinimum, level)
            if (nowMs - peakStartedAt >= ATTACK_MS) {
                foregroundPeak = peakMinimum
                peakStartedAt = 0L
            }
        }
    }

    private fun clearActiveBackground() {
        foregroundPeak = 0.0
        peakStartedAt = 0L
        backgroundStartedAt = 0L
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
        const val ACTIVE_NOISE_ALPHA = 0.15
        const val BACKGROUND_DROP_DB = 18.0
        const val BACKGROUND_BAND_DB = 20.0
        const val BACKGROUND_VARIATION_DB = 1.5
        const val BACKGROUND_SETTLE_MS = 600L
        const val MAX_FRAME_GAP_MS = 60L
        const val INITIAL_NOISE_FLOOR = 32.0
        const val MIN_NOISE_FLOOR = 20.0
        const val MAX_CALIBRATION_NOISE = 55.0
        const val MAX_NOISE_FLOOR = 85.0
    }
}
