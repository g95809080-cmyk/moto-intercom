package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Kum35ActiveDisconnectTest {
    private val runtime = RuntimeSessionId("d1000000-0000-4000-8000-000000000035")
    private val attempt = ConnectionAttemptFixture.create(
        clock = FakeMonotonicClock(MonotonicTimestamp(0L)),
        id = ConnectionAttemptId("e1000000-0000-4000-8000-000000000035"),
        runtimeSessionId = runtime,
        targetDeviceId = "b1000000-0000-4000-8000-000000000035",
        expectedRemoteSessionId = RuntimeSessionId("f1000000-0000-4000-8000-000000000035")
    )
    private val peer = PeerIdentity(
        deviceId = attempt.targetDeviceId,
        nickname = "Rider",
        deviceName = "Phone",
        runtimeSessionId = attempt.targetLock.expectedRemoteSessionId,
        isDeviceIdVerified = true
    )

    @Test
    fun exactReleaseGateRejectsReplacementOrResidualCoordinatorOwnership() {
        val effect = SessionEffect.ReleaseActiveSessionAndContinueDiscovery(attempt)
        val channelId = ControlChannelId.parse("c1000000-0000-4000-8000-000000000035")
        val wireKey = WireRequestKey(
            DeviceId.parse("a1000000-0000-4000-8000-000000000035"),
            runtime,
            attempt.id,
            DeviceId.parse(attempt.targetDeviceId)
        )
        val active = AttemptChannelSet(
            wireRequestKey = wireKey,
            attempt = attempt,
            peer = peer,
            channelIds = setOf(channelId),
            phase = SignalingAttemptPhase.CONNECTED,
            mediaOwnerChannelId = channelId
        )
        val pending = PendingInboundRequest(
            runtimeSessionId = runtime,
            wireRequestKey = wireKey,
            targetLock = attempt.targetLock,
            peer = peer,
            transport = Transport.LAN,
            channelIds = setOf(channelId),
            phase = PendingInboundPhase.WAITING_LOCAL_DECISION,
            decisionDeadlineAt = MonotonicTimestamp(1_000L)
        )
        val replacement = attempt.copy(
            id = ConnectionAttemptId("e2000000-0000-4000-8000-000000000035")
        )

        assertTrue(
            canExecuteActiveSessionReleaseEffect(
                effect,
                IntercomState.Discovering(runtime),
                currentAttempt = null,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteActiveSessionReleaseEffect(
                effect,
                IntercomState.Connecting(replacement, peer),
                replacement,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteActiveSessionReleaseEffect(
                effect,
                IntercomState.Discovering(runtime),
                attempt,
                activeAttempt = null,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteActiveSessionReleaseEffect(
                effect,
                IntercomState.Discovering(runtime),
                currentAttempt = null,
                activeAttempt = active,
                pendingInbound = null
            )
        )
        assertFalse(
            canExecuteActiveSessionReleaseEffect(
                effect,
                IntercomState.Discovering(runtime),
                currentAttempt = null,
                activeAttempt = null,
                pendingInbound = pending
            )
        )
    }

    @Test
    fun exactReleasePreservesRuntimeDiscoveryAndAudioOwners() {
        val calls = mutableListOf<String>()
        var runtimeOnline = true
        var discoveryOnline = true
        var audioOwnerOnline = true
        var mediaConnected = true

        ActiveSessionResourceController(
            attempt = attempt,
            cancelAttemptSchedules = { calls += "cancel:${it.id.value}" },
            closeSignalingAndMedia = { calls += "media:${it.id.value}" },
            releaseLanAttempt = { calls += "lan:${it.id.value}" },
            releaseWifiDirectAttempt = { calls += "wifi:${it.id.value}" },
            clearConnectionState = {
                calls += "state"
                mediaConnected = false
            },
            continueDiscovery = { calls += "continue:${it.value}" }
        ).releaseAndContinueDiscovery()

        assertEquals(
            listOf(
                "cancel:${attempt.id.value}",
                "media:${attempt.id.value}",
                "lan:${attempt.id.value}",
                "wifi:${attempt.id.value}",
                "state",
                "continue:${runtime.value}"
            ),
            calls
        )
        assertFalse(mediaConnected)
        assertTrue(runtimeOnline)
        assertTrue(discoveryOnline)
        assertTrue(audioOwnerOnline)
    }

    @Test
    fun primaryActionSeparatesAttemptDisconnectFromFullStop() {
        val statesWithAttempt = listOf<IntercomState>(
            IntercomState.Connecting(attempt, peer),
            IntercomState.Optimizing(attempt, peer),
            IntercomState.Connected(attempt, peer, connectedAt = 1L, transport = Transport.LAN),
            IntercomState.Recovering(attempt, peer)
        )
        statesWithAttempt.forEach {
            assertEquals(PrimaryIntercomAction.DISCONNECT_CURRENT, primaryIntercomAction(it))
            assertEquals("断开当前车友", primaryIntercomActionLabel(it))
        }

        val fullStopStates = listOf<IntercomState>(
            IntercomState.Discovering(runtime),
            IntercomState.IncomingConfirmation(runtime, attempt.id, peer),
            IntercomState.Resetting(
                runtime,
                attempt.targetDeviceId,
                attempt.id,
                RECOVERY_RESET_FAILURE_THRESHOLD
            )
        )
        fullStopStates.forEach {
            assertEquals(PrimaryIntercomAction.STOP_RUNTIME, primaryIntercomAction(it))
            assertEquals("停止摩声", primaryIntercomActionLabel(it))
        }

        assertEquals(PrimaryIntercomAction.START, primaryIntercomAction(IntercomState.Offline))
        assertEquals("启动摩声", primaryIntercomActionLabel(IntercomState.Offline))
        assertEquals(
            PrimaryIntercomAction.NONE,
            primaryIntercomAction(IntercomState.Stopping(runtime))
        )
        assertEquals("停止中...", primaryIntercomActionLabel(IntercomState.Stopping(runtime)))
    }
}
