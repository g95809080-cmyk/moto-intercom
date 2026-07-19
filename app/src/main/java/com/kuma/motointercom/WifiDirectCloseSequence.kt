package com.kuma.motointercom

import java.util.concurrent.atomic.AtomicBoolean

internal class WifiDirectCloseSequence(
    private val cancelConnect: ((() -> Unit) -> Unit),
    private val removeGroup: ((() -> Unit) -> Unit),
    private val clearServiceRequests: ((() -> Unit) -> Unit),
    private val clearLocalServices: ((() -> Unit) -> Unit),
    private val closeChannel: () -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    private val completed = AtomicBoolean(false)

    fun start() {
        runStep(cancelConnect) {
            runStep(removeGroup) {
                runStep(clearServiceRequests) {
                    runStep(clearLocalServices, ::complete)
                }
            }
        }
    }

    private fun runStep(
        action: ((() -> Unit) -> Unit),
        next: () -> Unit
    ) {
        val advanced = AtomicBoolean(false)
        val advance = {
            if (advanced.compareAndSet(false, true)) next()
        }
        try {
            action(advance)
        } catch (failure: Throwable) {
            onError(failure)
            advance()
        }
    }

    private fun complete() {
        if (completed.compareAndSet(false, true)) closeChannel()
    }
}
