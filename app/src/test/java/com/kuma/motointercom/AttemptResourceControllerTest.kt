package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptResourceControllerTest {
    @Test
    fun failedAttemptReleasesAttemptResourcesWithoutClosingOnlineAudioOwner() {
        val runtime = RuntimeSessionId("runtime-current")
        val closed = mutableListOf<String>()
        var mediaLocated = true
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
            clearMediaLocator = { mediaLocated = false },
            clearConnectionState = {
                physicalLinkReady = false
                mediaConnected = false
            },
            resumeDiscovery = { restartedRuntime = it }
        ).abortAndResumeDiscovery()

        assertEquals(listOf("intercom-and-socket", "lan", "wifi"), closed)
        assertFalse(mediaLocated)
        assertFalse(physicalLinkReady)
        assertFalse(mediaConnected)
        assertNull(restartedRuntime)

        wifiCloseCompletion?.invoke()

        assertEquals(runtime, restartedRuntime)
        val attemptBClaimed = if (!mediaLocated) {
            mediaLocated = true
            true
        } else {
            false
        }
        assertTrue(attemptBClaimed)
    }

    @Test
    fun recoveryRestartAddsNoBackoffButOrdinaryDiscoveryKeepsItsDelay() {
        val clock = FakeMonotonicClock(MonotonicTimestamp(100L))
        val recovery = ConnectionAttemptFixture.create(
            clock = clock,
            trigger = ConnectionTrigger.RECOVERY
        )
        val ordinary = ConnectionAttemptFixture.create(
            clock = clock,
            id = ConnectionAttemptId("attempt-ordinary"),
            trigger = ConnectionTrigger.USER
        )

        assertEquals(0L, restartDiscoveryDelayMillis(recovery))
        assertEquals(1_500L, restartDiscoveryDelayMillis(ordinary))
        assertEquals(1_500L, restartDiscoveryDelayMillis(null))
    }
}
