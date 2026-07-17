package com.kuma.motointercom

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
    fun connectionAttemptRequiresTargetLockAndOnePlannedTransport() {
        val runtime = RuntimeSessionId("runtime")
        val attempt = ConnectionAttempt(
            id = ConnectionAttemptId("attempt-provisional"),
            runtimeSessionId = runtime,
            targetLock = TargetLock("peer", RuntimeSessionId("remote-runtime")),
            trigger = ConnectionTrigger.USER,
            channelPlan = ChannelPlan.single(Transport.WIFI_DIRECT),
            deadlineElapsedRealtimeMs = 1L
        )

        assertNotEquals("", attempt.targetDeviceId)
        assertThrows(IllegalArgumentException::class.java) {
            TargetLock("", RuntimeSessionId("remote-runtime"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelPlan(setOf(Transport.LAN, Transport.WIFI_DIRECT))
        }
    }
}
