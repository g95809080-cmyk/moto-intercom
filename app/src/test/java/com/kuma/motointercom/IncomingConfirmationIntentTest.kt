package com.kuma.motointercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IncomingConfirmationIntentTest {
    @Test
    fun notificationActionsCarryPinnedIdentityAndUniqueDataUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prompt = prompt("nonce-a")
        val accept = IntercomService.incomingConfirmationActionIntent(
            context,
            prompt,
            accepted = true
        )
        val reject = IntercomService.incomingConfirmationActionIntent(
            context,
            prompt,
            accepted = false
        )
        val nextRequest = IntercomService.incomingConfirmationActionIntent(
            context,
            prompt("nonce-b"),
            accepted = true
        )

        assertEquals(IntercomService.ACTION_ACCEPT_INCOMING, accept.action)
        assertEquals(IntercomService.ACTION_REJECT_INCOMING, reject.action)
        assertEquals(listOf("nonce-a", "accept"), accept.data?.pathSegments)
        assertEquals(listOf("nonce-a", "reject"), reject.data?.pathSegments)
        assertNotEquals(accept.data, reject.data)
        assertNotEquals(accept.data, nextRequest.data)
        assertEquals(prompt.runtimeSessionId.value, accept.extras?.getString(EXTRA_RUNTIME))
        assertEquals(prompt.attemptId.value, accept.extras?.getString(EXTRA_ATTEMPT))
        assertEquals(prompt.channelId.value, accept.extras?.getString(EXTRA_CHANNEL))
        assertEquals(prompt.actionNonce, accept.extras?.getString(EXTRA_NONCE))
    }

    private fun prompt(nonce: String) = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("10000000-0000-4000-8000-000000000001"),
        attemptId = ConnectionAttemptId("20000000-0000-4000-8000-000000000001"),
        channelId = ControlChannelId.parse("30000000-0000-4000-8000-000000000001"),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "40000000-0000-4000-8000-000000000001",
            nickname = "Rider",
            deviceName = "Phone",
            runtimeSessionId = RuntimeSessionId("50000000-0000-4000-8000-000000000001"),
            isDeviceIdVerified = true
        ),
        decisionDeadlineElapsedMs = 15_000L,
        surface = ConfirmationSurface.NOTIFICATION
    )

    private companion object {
        const val EXTRA_RUNTIME = "com.kuma.motointercom.extra.RUNTIME_SESSION_ID"
        const val EXTRA_ATTEMPT = "com.kuma.motointercom.extra.ATTEMPT_ID"
        const val EXTRA_CHANNEL = "com.kuma.motointercom.extra.CHANNEL_ID"
        const val EXTRA_NONCE = "com.kuma.motointercom.extra.ACTION_NONCE"
    }
}
