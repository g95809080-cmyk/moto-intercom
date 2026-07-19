package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class IntercomStatusTextTest {
    @Test
    fun recoveringUiAndNotificationNameTheRetainedRider() {
        val state = recoveringState(
            nickname = "车友 B",
            deviceName = "B Phone"
        )

        assertEquals("正在恢复与 车友 B 的连接", recoveryStatusText(state.peer))
        assertEquals("正在恢复与 车友 B 的连接", intercomStatusDetail(state))
        assertEquals(
            "正在恢复与 车友 B 的连接",
            foregroundNotificationText(state, "generic")
        )
    }

    @Test
    fun recoveringTextUsesDeviceNameThenExplicitOriginalRiderFallback() {
        assertEquals(
            "正在恢复与 B Phone 的连接",
            recoveryStatusText(recoveringState("", "B Phone").peer)
        )
        assertEquals(
            "正在恢复与 原车友 的连接",
            recoveryStatusText(recoveringState("", "").peer)
        )
    }

    private fun recoveringState(
        nickname: String,
        deviceName: String
    ) = IntercomState.Recovering(
        attempt = ConnectionAttemptFixture.create(
            clock = FakeMonotonicClock(MonotonicTimestamp(1L)),
            id = ConnectionAttemptId("attempt-recovery"),
            targetDeviceId = "device-b",
            expectedRemoteSessionId = RuntimeSessionId("runtime-b"),
            trigger = ConnectionTrigger.RECOVERY
        ),
        peer = PeerIdentity(
            deviceId = "device-b",
            nickname = nickname,
            deviceName = deviceName,
            runtimeSessionId = RuntimeSessionId("runtime-b"),
            isDeviceIdVerified = true
        )
    )
}
