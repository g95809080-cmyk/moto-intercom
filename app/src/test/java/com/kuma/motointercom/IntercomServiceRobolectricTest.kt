package com.kuma.motointercom

import android.app.Notification
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntercomServiceRobolectricTest {
    @Test
    fun listenerReplaysOnlyTheCurrentInAppConfirmationAfterActivityRebind() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val prompt = incomingPrompt(
            nonce = "rebind-in-app",
            surface = ConfirmationSurface.IN_APP,
            deadline = SystemClock.elapsedRealtime() + 60_000L
        )
        setActiveIncomingPrompt(service, prompt)
        val replayed = mutableListOf<IncomingConfirmationPrompt>()

        service.setListener(recordingListener(replayed))

        assertEquals(listOf(prompt), replayed)
        controller.destroy()
    }

    @Test
    fun listenerDoesNotReplayNotificationOrExpiredConfirmationIntoTheActivity() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val replayed = mutableListOf<IncomingConfirmationPrompt>()
        val listener = recordingListener(replayed)

        setActiveIncomingPrompt(
            service,
            incomingPrompt(
                nonce = "rebind-notification",
                surface = ConfirmationSurface.NOTIFICATION,
                deadline = SystemClock.elapsedRealtime() + 60_000L
            )
        )
        service.setListener(listener)
        assertTrue(replayed.isEmpty())

        setActiveIncomingPrompt(
            service,
            incomingPrompt(
                nonce = "rebind-expired",
                surface = ConfirmationSurface.IN_APP,
                deadline = SystemClock.elapsedRealtime() - 1L
            )
        )
        service.setListener(listener)
        assertTrue(replayed.isEmpty())
        controller.destroy()
    }

    @Test
    fun incomingNotificationDoesNotInventDeviceOrSocketVerificationCopy() {
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        val prompt = incomingPrompt(
            nonce = "notification-copy",
            surface = ConfirmationSurface.NOTIFICATION,
            deadline = SystemClock.elapsedRealtime() + 60_000L,
            deviceName = " "
        )
        val method = IntercomService::class.java.getDeclaredMethod(
            "buildIncomingConfirmationNotification",
            IncomingConfirmationPrompt::class.java
        ).apply { isAccessible = true }

        val notification = method.invoke(service, prompt) as Notification
        assertEquals(
            "设备名称未提供 · 请在应用内确认",
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        )
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?.toString()
                ?.contains("Socket 身份") == false
        )
        controller.destroy()
    }

    private fun recordingListener(
        replayed: MutableList<IncomingConfirmationPrompt>
    ): IntercomService.Listener = object : IntercomService.Listener {
        override fun onStatusChanged(status: String, running: Boolean) = Unit
        override fun onLog(message: String) = Unit
        override fun onError(message: String) = Unit
        override fun onIncomingConfirmation(prompt: IncomingConfirmationPrompt) {
            replayed += prompt
        }
    }

    private fun setActiveIncomingPrompt(
        service: IntercomService,
        prompt: IncomingConfirmationPrompt?
    ) {
        IntercomService::class.java.getDeclaredField("activeIncomingPrompt").apply {
            isAccessible = true
            set(service, prompt)
        }
    }

    private fun incomingPrompt(
        nonce: String,
        surface: ConfirmationSurface,
        deadline: Long,
        deviceName: String = "Test Device"
    ): IncomingConfirmationPrompt = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("runtime-$nonce"),
        attemptId = ConnectionAttemptId("attempt-$nonce"),
        channelId = ControlChannelId.create(),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "device-$nonce",
            nickname = "Incoming Rider",
            deviceName = deviceName,
            runtimeSessionId = RuntimeSessionId("peer-runtime-$nonce"),
            isDeviceIdVerified = false
        ),
        decisionDeadlineElapsedMs = deadline,
        surface = surface
    )
}
