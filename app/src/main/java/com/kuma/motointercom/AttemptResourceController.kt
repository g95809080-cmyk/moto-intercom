package com.kuma.motointercom

import java.util.concurrent.atomic.AtomicBoolean

internal class AttemptResourceController(
    private val runtimeSessionId: RuntimeSessionId,
    private val closeIntercomAndSocket: () -> Unit,
    private val closeLanDiscovery: () -> Unit,
    private val closeWifiDirect: ((() -> Unit) -> Unit),
    private val closeAudioRoute: () -> Unit,
    private val clearMediaLocator: () -> Unit,
    private val clearConnectionState: () -> Unit,
    private val resumeDiscovery: (RuntimeSessionId) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) {
    fun abortAndResumeDiscovery() {
        closeSafely(clearMediaLocator)
        closeSafely(closeIntercomAndSocket)
        closeSafely(closeLanDiscovery)
        closeSafely(closeAudioRoute)
        closeSafely(clearConnectionState)

        val resumed = AtomicBoolean(false)
        val resumeOnce = {
            if (resumed.compareAndSet(false, true)) {
                closeSafely { resumeDiscovery(runtimeSessionId) }
            }
        }
        try {
            closeWifiDirect(resumeOnce)
        } catch (t: Throwable) {
            onError(t)
            resumeOnce()
        }
    }

    private fun closeSafely(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
