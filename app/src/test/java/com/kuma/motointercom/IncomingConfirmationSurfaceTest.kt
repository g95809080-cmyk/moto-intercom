package com.kuma.motointercom

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingConfirmationSurfaceTest {

    private class FakeHost : IncomingConfirmationHost {
        var current = true
        var foreground = true
        var canShowInApp = true
        var notificationAvailable = true
        val shown = mutableListOf<IncomingConfirmationPrompt>()
        val canceled = mutableListOf<String>()
        val unavailable = mutableListOf<IncomingConfirmationPrompt>()
        val errors = mutableListOf<Throwable>()

        override fun isPromptCurrent(prompt: IncomingConfirmationPrompt): Boolean = current
        override fun isAppForeground(): Boolean = foreground
        override fun showInApp(prompt: IncomingConfirmationPrompt): Boolean {
            if (!canShowInApp) return false
            shown += prompt
            return true
        }

        override fun cancelInApp(nonce: String) {
            canceled += nonce
        }

        override fun notificationAvailable(): Boolean = notificationAvailable
        override fun notificationManager(): NotificationManager? = null
        override fun buildNotification(prompt: IncomingConfirmationPrompt): Notification =
            throw AssertionError("notification path must not run in this test")
        override fun onSurfaceUnavailable(prompt: IncomingConfirmationPrompt) {
            unavailable += prompt
        }

        override fun onError(error: Throwable) {
            errors += error
        }
    }

    private fun scheduler(onTimedOut: (IncomingConfirmationPrompt) -> Unit = {}): IncomingConfirmationDeadlineScheduler =
        IncomingConfirmationDeadlineScheduler(
            elapsedRealtime = { 1_000L },
            postDelayed = { _, _ -> },
            removeCallbacks = { },
            onTimedOut = onTimedOut
        )

    private fun prompt(
        nonce: String = "nonce-a",
        surface: ConfirmationSurface = ConfirmationSurface.IN_APP,
        deadline: Long = 16_000L
    ) = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("runtime-$nonce"),
        attemptId = ConnectionAttemptId("attempt-$nonce"),
        channelId = ControlChannelId.create(),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "device-$nonce",
            nickname = "Rider",
            deviceName = "Phone",
            runtimeSessionId = RuntimeSessionId("peer-runtime-$nonce"),
            isDeviceIdVerified = false
        ),
        decisionDeadlineElapsedMs = deadline,
        surface = surface
    )

    @Test
    fun stalePromptIsNeitherStoredNorShown() {
        val host = FakeHost().apply { current = false }
        val surface = IncomingConfirmationSurface(scheduler(), host)

        surface.publish(prompt())

        assertNull(surface.activePrompt)
        assertTrue(host.shown.isEmpty())
        assertTrue(host.unavailable.isEmpty())
    }

    @Test
    fun backgroundedAppReportsTheSurfaceUnavailableInsteadOfShowing() {
        val host = FakeHost().apply { foreground = false }
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()

        surface.publish(request)

        assertSame(request, surface.activePrompt)
        assertTrue(host.shown.isEmpty())
        assertEquals(listOf(request), host.unavailable)
    }

    @Test
    fun missingInAppSurfaceReportsUnavailableWithoutAnError() {
        val host = FakeHost().apply { canShowInApp = false }
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()

        surface.publish(request)

        assertEquals(listOf(request), host.unavailable)
        assertTrue(host.errors.isEmpty())
    }

    @Test
    fun cancelIgnoresAnyMismatchedPartOfTheTuple() {
        val host = FakeHost()
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()
        surface.publish(request)

        surface.cancel(request.runtimeSessionId, request.attemptId, "other-nonce")
        surface.cancel(request.runtimeSessionId, ConnectionAttemptId("other-attempt"), request.actionNonce)
        surface.cancel(RuntimeSessionId("other-runtime"), request.attemptId, request.actionNonce)

        assertSame(request, surface.activePrompt)
        assertTrue(host.canceled.isEmpty())
    }

    @Test
    fun matchingCancelClearsThePromptAndNotifiesTheListener() {
        val host = FakeHost()
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()
        surface.publish(request)

        surface.cancel(request.runtimeSessionId, request.attemptId, request.actionNonce)

        assertNull(surface.activePrompt)
        assertEquals(listOf(request.actionNonce), host.canceled)
    }

    @Test
    fun replayOnlyReOffersCurrentInAppPrompts() {
        val host = FakeHost()
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()
        surface.publish(request)
        host.shown.clear()

        val replayed = mutableListOf<IncomingConfirmationPrompt>()
        surface.replay { replayed += it }
        assertEquals(listOf(request), replayed)

        host.current = false
        surface.replay { replayed += it }
        assertEquals("expired prompt must not replay", listOf(request), replayed)
    }

    @Test
    fun notificationPromptIsNeverReplayedIntoTheActivity() {
        val host = FakeHost()
        val surface = IncomingConfirmationSurface(scheduler(), host)
        surface.publish(prompt(surface = ConfirmationSurface.NOTIFICATION))

        val replayed = mutableListOf<IncomingConfirmationPrompt>()
        surface.replay { replayed += it }

        assertTrue(replayed.isEmpty())
    }

    @Test
    fun cancelAllClearsAndNotifiesExactlyOnce() {
        val host = FakeHost()
        val surface = IncomingConfirmationSurface(scheduler(), host)
        val request = prompt()
        surface.publish(request)

        surface.cancelAll()
        surface.cancelAll()

        assertNull(surface.activePrompt)
        assertEquals(listOf(request.actionNonce), host.canceled)
        assertFalse(host.canceled.size > 1)
    }
}
