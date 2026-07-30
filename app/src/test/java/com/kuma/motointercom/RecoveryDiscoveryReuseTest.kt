package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryDiscoveryReuseTest {
    private val clock = FakeMonotonicClock(MonotonicTimestamp(1_000L))
    private val runtime = RuntimeSessionId("runtime-current")
    private val remoteRuntime = RuntimeSessionId("runtime-remote")

    @Test
    fun onlyDelayedRecoveryRestartReusesDiscoveryAdapters() {
        val attempt = attempt("recovery", Transport.WIFI_DIRECT)

        assertTrue(
            shouldReuseRecoveryDiscovery(
                SessionEffect.RestartDiscovery(runtime, attempt, restartDelayMillis = 1_500L)
            )
        )
        assertFalse(
            shouldReuseRecoveryDiscovery(
                SessionEffect.RestartDiscovery(runtime, attempt, restartDelayMillis = 0L)
            )
        )
        assertFalse(
            shouldReuseRecoveryDiscovery(
                SessionEffect.RestartDiscovery(
                    runtime,
                    attempt.copy(trigger = ConnectionTrigger.USER),
                    restartDelayMillis = 1_500L
                )
            )
        )
    }

    @Test
    fun reuseRequiresSameRuntimeTargetTransportAndLiveDeadline() {
        val previous = attempt("previous", Transport.WIFI_DIRECT)
        val retry = attempt("retry", Transport.WIFI_DIRECT)

        assertTrue(
            retry.canReuseDiscoveryAdapterFrom(previous, Transport.WIFI_DIRECT, clock)
        )
        assertFalse(
            retry.copy(runtimeSessionId = RuntimeSessionId("other-runtime"))
                .canReuseDiscoveryAdapterFrom(previous, Transport.WIFI_DIRECT, clock)
        )
        assertFalse(
            retry.copy(targetLock = retry.targetLock.copy(targetDeviceId = "other-peer"))
                .canReuseDiscoveryAdapterFrom(previous, Transport.WIFI_DIRECT, clock)
        )
        assertFalse(retry.canReuseDiscoveryAdapterFrom(previous, Transport.LAN, clock))
        assertFalse(
            retry.copy(trigger = ConnectionTrigger.USER)
                .canReuseDiscoveryAdapterFrom(previous, Transport.WIFI_DIRECT, clock)
        )

        clock.advanceBy(10_000L)
        assertFalse(
            retry.canReuseDiscoveryAdapterFrom(previous, Transport.WIFI_DIRECT, clock)
        )
    }

    private fun attempt(id: String, transport: Transport) = ConnectionAttempt(
        id = ConnectionAttemptId(id),
        runtimeSessionId = runtime,
        targetLock = TargetLock("peer-a", remoteRuntime),
        trigger = ConnectionTrigger.RECOVERY,
        channelPlan = ChannelPlan.single(transport),
        deadlineElapsedRealtimeMs = 11_000L
    )
}
