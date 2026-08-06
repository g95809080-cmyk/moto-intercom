package com.kuma.motointercom

import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityRobolectricTest {
    @Test
    fun activityOwnsXmlRoutesAndRestoresUiStateBeforeServiceStart() {
        val firstController = Robolectric.buildActivity(MainActivity::class.java).create()
        val first = firstController.get()

        first.findViewById<View>(R.id.home_settings_button).performClick()
        val draft = first.findViewById<EditText>(R.id.settings_nickname_input)
        draft.setText("Activity Draft")

        val savedState = Bundle()
        firstController.saveInstanceState(savedState)
        firstController.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
            .get()

        assertNotNull(recreated.findViewById<View>(R.id.settings_scroll))
        assertEquals(
            "Activity Draft",
            recreated.findViewById<EditText>(R.id.settings_nickname_input).text.toString()
        )
        assertTrue(recreated.findViewById<View>(R.id.home_scroll) == null)
    }

    @Test
    fun processRestartDoesNotRestoreSavedRouteFromAnotherProcess() {
        val savedState = Bundle().apply {
            putString("main_route", MainRoute.SETTINGS.name)
            putString("process_session_token", "previous-process")
        }

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
            .get()

        assertNotNull(recreated.findViewById<View>(R.id.home_scroll))
        assertTrue(recreated.findViewById<View>(R.id.settings_scroll) == null)
    }

    @Test
    fun recreatedActivityCanRenderServiceReplayedIncomingConfirmation() {
        val savedState = Bundle()
        val firstController = Robolectric.buildActivity(MainActivity::class.java).create()
        firstController.saveInstanceState(savedState)
        firstController.destroy()

        val recreatedController = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
        val recreated = recreatedController.get()
        setPrivateBoolean(recreated, "serviceConnected", true)

        recreated.onIncomingConfirmation(incomingPrompt("recreated-nonce", "Recreated Rider"))
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("replayed incoming dialog was not shown")
        assertTrue(dialog.isShowing)
        assertEquals(
            recreated.getString(R.string.incoming_confirmation_title, "Recreated Rider"),
            shadowOf(dialog).title
        )
        dialog.dismiss()
        recreatedController.destroy()
    }

    @Test
    fun replacedIncomingDialogCannotActOnTheCurrentRequest() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        showIncomingConfirmation(activity, incomingPrompt("old-nonce", "Old Rider"))
        val oldDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("first incoming dialog was not shown")

        showIncomingConfirmation(activity, incomingPrompt("new-nonce", "New Rider"))
        val currentDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("replacement incoming dialog was not shown")

        assertFalse(oldDialog.isShowing)
        oldDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(currentDialog.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "New Rider"),
            shadowOf(currentDialog).title
        )

        currentDialog.dismiss()
        controller.destroy()
    }

    @Test
    fun incomingConfirmationSupersedesPlaceholderDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        activity.findViewById<View>(R.id.home_mute_button).performClick()
        val placeholder = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("placeholder dialog was not shown")
        assertTrue(placeholder.isShowing)

        showIncomingConfirmation(activity, incomingPrompt("incoming-nonce", "Incoming Rider"))
        val incoming = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming dialog was not shown")

        assertFalse(placeholder.isShowing)
        assertTrue(incoming.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "Incoming Rider"),
            shadowOf(incoming).title
        )

        incoming.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(incoming.isShowing)
        assertNotNull(activity.findViewById<View>(R.id.home_scroll))
        controller.destroy()
    }

    @Test
    fun incomingConfirmationClosesWhenServiceStateLeavesConfirmation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "serviceConnected", true)

        showIncomingConfirmation(activity, incomingPrompt("state-close-nonce", "Incoming Rider"))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming dialog was not shown")
        assertTrue(dialog.isShowing)

        activity.onIntercomStateChanged(IntercomState.Offline)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(dialog.isShowing)
        controller.destroy()
    }

    @Test
    fun serviceReplayedStatusAndPeerDoNotBecomeCurrentSessionLogs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "serviceConnected", true)
        setPrivateBoolean(activity, "replayingServiceSnapshot", true)

        activity.onStatusChanged("回放的服务状态", running = true)
        activity.onRemoteRiderIdentified("回放的远端骑士")
        shadowOf(Looper.getMainLooper()).idle()

        activity.findViewById<View>(R.id.home_settings_button).performClick()
        activity.findViewById<View>(R.id.settings_logs_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            activity.getString(R.string.logs_empty),
            activity.findViewById<android.widget.TextView>(R.id.logs_text).text.toString()
        )

        setPrivateBoolean(activity, "replayingServiceSnapshot", false)
        activity.onStatusChanged("实时服务状态", running = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            activity.findViewById<android.widget.TextView>(R.id.logs_text).text
                .toString()
                .contains("实时服务状态")
        )
        controller.destroy()
    }

    @Test
    fun serviceDisconnectClearsStaleAudioBluetoothAndPresenceFacts() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "bindingRegistered", true)
        setPrivateBoolean(activity, "serviceConnected", true)

        activity.onAudioSourceChanged("当前音频源：蓝牙耳机 (Helmet)", bluetooth = true)
        activity.onPresencesChanged(stalePresence())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            BLUETOOTH_PERMISSION_UNAVAILABLE,
            activity.findViewById<TextView>(R.id.home_audio_source).text.toString()
        )

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "当前音频源：待机",
            activity.findViewById<TextView>(R.id.home_audio_source).text.toString()
        )
        assertFalse(
            activity.findViewById<TextView>(R.id.home_bluetooth_pill).text
                .toString()
                .contains(BLUETOOTH_CONNECTED_TEXT)
        )

        activity.findViewById<View>(R.id.home_menu_button).performClick()
        activity.findViewById<View>(R.id.nav_discover_button).performClick()
        assertEquals(
            0,
            activity.findViewById<ViewGroup>(R.id.discover_nearby_container).childCount
        )
        controller.destroy()
    }

    @Test
    fun serviceDisconnectBeforeRebindConnectionDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "bindingRegistered", true)
        setPrivateBoolean(activity, "serviceConnected", false)
        activity.findViewById<TextView>(R.id.home_status_supplemental).text = "保留当前状态"

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            "保留当前状态",
            activity.findViewById<TextView>(R.id.home_status_supplemental).text.toString()
        )
        controller.destroy()
    }

    @Test
    fun lateServiceDisconnectAfterActivityStopDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        controller.stop()
        val before = activity.findViewById<TextView>(R.id.home_status_supplemental).text.toString()

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            before,
            activity.findViewById<TextView>(R.id.home_status_supplemental).text.toString()
        )
        controller.destroy()
    }

    private fun showIncomingConfirmation(
        activity: MainActivity,
        prompt: IncomingConfirmationPrompt
    ) {
        val method = MainActivity::class.java.getDeclaredMethod(
            "showIncomingConfirmation",
            IncomingConfirmationPrompt::class.java
        )
        method.isAccessible = true
        method.invoke(activity, prompt)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun setPrivateBoolean(activity: MainActivity, fieldName: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun incomingPrompt(
        nonce: String,
        riderName: String
    ): IncomingConfirmationPrompt = IncomingConfirmationPrompt(
        runtimeSessionId = RuntimeSessionId("incoming-runtime-$nonce"),
        attemptId = ConnectionAttemptId("incoming-attempt-$nonce"),
        channelId = ControlChannelId.create(),
        actionNonce = nonce,
        peer = PeerIdentity(
            deviceId = "device-$nonce",
            nickname = riderName,
            deviceName = "Test Device",
            runtimeSessionId = RuntimeSessionId("peer-runtime-$nonce"),
            isDeviceIdVerified = false
        ),
        decisionDeadlineElapsedMs = 60_000L,
        surface = ConfirmationSurface.IN_APP
    )

    private fun stalePresence(): List<RiderPresence> = listOf(
        RiderPresence(
            deviceId = "stale-device",
            sessionId = RuntimeSessionId("stale-session"),
            nickname = "Stale Rider",
            deviceName = "Stale Phone",
            protocolVersion = 2,
            lastSeenElapsedRealtimeMs = 1L,
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.LAN,
                    endpointId = "stale-endpoint",
                    address = "127.0.0.1",
                    port = 1234,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = true
                )
            ),
            pairing = null
        )
    )
}
