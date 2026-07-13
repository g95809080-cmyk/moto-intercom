package com.kuma.motointercom

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionIdentifiersTest {
    @Test
    fun eachRuntimeSessionGetsANewId() {
        assertNotEquals(RuntimeSessionId.create(), RuntimeSessionId.create())
    }

    @Test
    fun eachConnectionAttemptGetsANewId() {
        assertNotEquals(ConnectionAttemptId.create(), ConnectionAttemptId.create())
    }

    @Test
    fun onlyExplicitLegacyAttemptMayHaveUnknownTarget() {
        val runtime = RuntimeSessionId("runtime")
        val provisional = ConnectionAttempt(
            id = ConnectionAttemptId("attempt-provisional"),
            runtimeSessionId = runtime,
            targetDeviceId = null,
            trigger = ConnectionTrigger.LEGACY_PROVISIONAL,
            preferredTransport = Transport.WIFI_DIRECT,
            deadlineElapsedRealtimeMs = 1L
        )

        assertTrue(provisional.isProvisional)
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionAttempt(
                id = ConnectionAttemptId("attempt-invalid"),
                runtimeSessionId = runtime,
                targetDeviceId = null,
                trigger = ConnectionTrigger.USER,
                preferredTransport = Transport.LAN,
                deadlineElapsedRealtimeMs = 1L
            )
        }
    }
}
