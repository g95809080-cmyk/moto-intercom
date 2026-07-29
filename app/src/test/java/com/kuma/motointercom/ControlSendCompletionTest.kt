package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlSendCompletionTest {
    private val runtimeSessionId =
        RuntimeSessionId("41000000-0000-4000-8000-000000000001")
    private val attemptId =
        ConnectionAttemptId("41000000-0000-4000-8000-000000000002")
    private val channelId =
        ControlChannelId.parse("41000000-0000-4000-8000-000000000003")

    @Test
    fun successfulWriteProducesTerminalEventEvenIfSessionClosesBeforeMainDispatch() {
        assertEquals(
            SessionEvent.SignalingMessageSent(
                runtimeSessionId,
                attemptId,
                channelId,
                SignalingMessageTypeV2.DISCONNECT
            ),
            controlSendCompletionEvent(
                runtimeSessionId,
                attemptId,
                channelId,
                SignalingMessageTypeV2.DISCONNECT,
                Result.success(Unit)
            )
        )
    }

    @Test
    fun failedWriteProducesTerminalEventEvenThoughWriterClosedTheSession() {
        assertEquals(
            SessionEvent.SignalingSendFailed(
                runtimeSessionId,
                attemptId,
                channelId,
                SignalingMessageTypeV2.DISCONNECT,
                "socket closed"
            ),
            controlSendCompletionEvent(
                runtimeSessionId,
                attemptId,
                channelId,
                SignalingMessageTypeV2.DISCONNECT,
                Result.failure(IllegalStateException("socket closed"))
            )
        )
    }

    @Test
    fun decodedFrameRemainsDeliverableWhenSocketClosesBeforeMainDispatch() {
        assertEquals(
            true,
            canDeliverDecodedControlEnvelope(
                sessionCurrent = true,
                sessionClosed = true,
                registeredSessionMatches = true
            )
        )
    }

    @Test
    fun decodedFrameIsRejectedAfterRuntimeOrRegisteredSessionChanges() {
        assertEquals(
            false,
            canDeliverDecodedControlEnvelope(
                sessionCurrent = false,
                sessionClosed = true,
                registeredSessionMatches = true
            )
        )
        assertEquals(
            false,
            canDeliverDecodedControlEnvelope(
                sessionCurrent = true,
                sessionClosed = true,
                registeredSessionMatches = false
            )
        )
    }
}
