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
    fun recoveringTextDoesNotPromoteDeviceNameToRiderIdentity() {
        val unnamedPeerState = recoveringState("", "B Phone")

        assertEquals(
            "正在恢复与 原车友 的连接",
            recoveryStatusText(unnamedPeerState.peer)
        )
        assertEquals(
            "正在恢复与 原车友 的连接",
            foregroundNotificationText(unnamedPeerState, "generic")
        )
        assertEquals(
            "正在恢复与 原车友 的连接",
            recoveryStatusText(recoveringState("", "").peer)
        )
    }

    @Test
    fun resettingNotificationUsesVisibleProductStateInsteadOfTransportFallback() {
        val resetting = IntercomState.Resetting(
            runtimeSessionId = RuntimeSessionId("runtime-current"),
            targetDeviceId = "device-b",
            failedAttemptId = ConnectionAttemptId("attempt-recovery-3"),
            consecutiveFinalFailures = 3
        )

        assertEquals(
            intercomStatusDetail(resetting),
            foregroundNotificationText(resetting, "generic transport status")
        )
    }

    @Test
    fun connectedNotificationDoesNotKeepStaleMediaInitializationCopy() {
        val connected = IntercomState.Connected(
            attempt = ConnectionAttemptFixture.create(
                clock = FakeMonotonicClock(MonotonicTimestamp(1L)),
                id = ConnectionAttemptId("attempt-connected"),
                targetDeviceId = "device-b",
                expectedRemoteSessionId = RuntimeSessionId("runtime-b"),
                trigger = ConnectionTrigger.AUTO_PAIRED,
                preferredTransport = Transport.WIFI_DIRECT
            ),
            peer = PeerIdentity(
                deviceId = "device-b",
                nickname = "车友 B",
                runtimeSessionId = RuntimeSessionId("runtime-b"),
                isDeviceIdVerified = true
            ),
            connectedAt = 2L,
            transport = Transport.WIFI_DIRECT
        )

        assertEquals(
            "语音通道已连接",
            foregroundNotificationText(connected, "媒体初始化中")
        )
    }

    @Test
    fun foregroundNotificationShowsLocalAudioInterruptionWithoutChangingIntercomState() {
        val connected = IntercomState.Connected(
            attempt = ConnectionAttemptFixture.create(
                clock = FakeMonotonicClock(MonotonicTimestamp(1L)),
                id = ConnectionAttemptId("attempt-audio"),
                targetDeviceId = "device-b",
                expectedRemoteSessionId = RuntimeSessionId("runtime-b"),
                trigger = ConnectionTrigger.AUTO_PAIRED,
                preferredTransport = Transport.WIFI_DIRECT
            ),
            peer = PeerIdentity(
                deviceId = "device-b",
                nickname = "车友 B",
                runtimeSessionId = RuntimeSessionId("runtime-b"),
                isDeviceIdVerified = true
            ),
            connectedAt = 2L,
            transport = Transport.WIFI_DIRECT
        )

        assertEquals(
            "对讲已暂停",
            foregroundNotificationText(
                connected,
                "generic",
                AudioInterruptionState.PHONE_ACTIVE
            )
        )
        assertEquals("正在恢复对讲音频", foregroundNotificationText(
            connected,
            "generic",
            AudioInterruptionState.RESUMING
        ))
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
