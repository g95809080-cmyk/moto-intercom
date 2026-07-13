package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptResourceControllerTest {
    @Test
    fun failedAttemptReleasesResourcesBeforeSameRuntimeAcceptsNextAttempt() {
        val runtime = RuntimeSessionId("runtime-current")
        val closed = mutableListOf<String>()
        var tunnelClaimed = true
        var physicalLinkReady = true
        var mediaConnected = true
        var wifiCloseCompletion: (() -> Unit)? = null
        var restartedRuntime: RuntimeSessionId? = null

        AttemptResourceController(
            runtimeSessionId = runtime,
            closeIntercomAndSocket = { closed += "intercom-and-socket" },
            closeLanDiscovery = { closed += "lan" },
            closeWifiDirect = { completion ->
                closed += "wifi"
                wifiCloseCompletion = completion
            },
            closeAudioRoute = { closed += "audio" },
            releaseTunnel = { tunnelClaimed = false },
            clearConnectionState = {
                physicalLinkReady = false
                mediaConnected = false
            },
            resumeDiscovery = { restartedRuntime = it }
        ).abortAndResumeDiscovery()

        assertEquals(listOf("intercom-and-socket", "lan", "audio", "wifi"), closed)
        assertFalse(tunnelClaimed)
        assertFalse(physicalLinkReady)
        assertFalse(mediaConnected)
        assertNull(restartedRuntime)

        wifiCloseCompletion?.invoke()

        assertEquals(runtime, restartedRuntime)
        val attemptBClaimed = if (!tunnelClaimed) {
            tunnelClaimed = true
            true
        } else {
            false
        }
        assertTrue(attemptBClaimed)
    }
}
