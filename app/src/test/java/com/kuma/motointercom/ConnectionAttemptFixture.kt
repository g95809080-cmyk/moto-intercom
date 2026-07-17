package com.kuma.motointercom

internal object ConnectionAttemptFixture {
    fun create(
        clock: MonotonicClock,
        id: ConnectionAttemptId = ConnectionAttemptId("attempt-current"),
        runtimeSessionId: RuntimeSessionId = RuntimeSessionId("runtime-current"),
        targetDeviceId: String = "peer-current",
        expectedRemoteSessionId: RuntimeSessionId = RuntimeSessionId("runtime-remote"),
        trigger: ConnectionTrigger = ConnectionTrigger.USER,
        preferredTransport: Transport = Transport.LAN,
        timeoutMs: Long = 10_000L
    ): ConnectionAttempt {
        require(timeoutMs > 0L) { "Attempt timeout must be positive" }
        return ConnectionAttempt(
            id = id,
            runtimeSessionId = runtimeSessionId,
            targetLock = TargetLock(targetDeviceId, expectedRemoteSessionId),
            trigger = trigger,
            channelPlan = ChannelPlan.single(preferredTransport),
            deadlineElapsedRealtimeMs = Math.addExact(
                clock.now().elapsedRealtimeMs,
                timeoutMs
            )
        )
    }
}
