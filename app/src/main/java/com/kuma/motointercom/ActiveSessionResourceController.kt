package com.kuma.motointercom

internal class ActiveSessionResourceController(
    private val attempt: ConnectionAttempt,
    private val cancelAttemptSchedules: (ConnectionAttempt) -> Unit,
    private val closeSignalingAndMedia: (ConnectionAttempt) -> Unit,
    private val releaseLanAttempt: (ConnectionAttempt) -> Unit,
    private val releaseWifiDirectAttempt: (ConnectionAttempt) -> Unit,
    private val clearConnectionState: () -> Unit,
    private val continueDiscovery: (RuntimeSessionId) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    fun releaseAndContinueDiscovery(finalizeDiscovery: Boolean = true) {
        runSafely { cancelAttemptSchedules(attempt) }
        runSafely { closeSignalingAndMedia(attempt) }
        runSafely { releaseLanAttempt(attempt) }
        runSafely { releaseWifiDirectAttempt(attempt) }
        if (!finalizeDiscovery) return
        runSafely(clearConnectionState)
        runSafely { continueDiscovery(attempt.runtimeSessionId) }
    }

    private fun runSafely(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
