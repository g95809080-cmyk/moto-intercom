package com.kuma.motointercom

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException

internal class WifiDirectCloseSequence(
    private val cancelConnect: ((() -> Unit) -> Unit),
    private val removeGroup: ((() -> Unit) -> Unit),
    private val clearServiceRequests: ((() -> Unit) -> Unit),
    private val clearLocalServices: ((() -> Unit) -> Unit),
    private val closeChannel: () -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val stepTimeoutMillis: Long,
    private val onError: (Throwable) -> Unit = {}
) {
    private val completed = AtomicBoolean(false)

    init {
        require(stepTimeoutMillis > 0L) { "Close step timeout must be positive" }
    }

    fun start() {
        runStep("cancelConnect", cancelConnect) {
            runStep("removeGroup", removeGroup) {
                runStep("clearServiceRequests", clearServiceRequests) {
                    runStep("clearLocalServices", clearLocalServices, ::complete)
                }
            }
        }
    }

    private fun runStep(
        label: String,
        action: ((() -> Unit) -> Unit),
        next: () -> Unit
    ) {
        val advanced = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val advance: (Throwable?) -> Unit = { failure ->
            if (advanced.compareAndSet(false, true)) {
                removeCallbacks(timeout)
                failure?.let(onError)
                next()
            }
        }
        timeout = Runnable {
            advance(TimeoutException("Wi-Fi Direct close step timed out: $label"))
        }
        try {
            postDelayed(timeout, stepTimeoutMillis)
            action { advance(null) }
        } catch (failure: Throwable) {
            advance(failure)
        }
    }

    private fun complete() {
        if (completed.compareAndSet(false, true)) closeChannel()
    }
}
