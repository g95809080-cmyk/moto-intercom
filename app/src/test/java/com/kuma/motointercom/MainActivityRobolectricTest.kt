package com.kuma.motointercom

import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Looper
import android.view.View
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
    private fun clickHome(activity: MainActivity, tag: String) {
        val screen = screen(activity)
        when (tag) {
            "home_settings_button" -> invokeShowPage(screen, MainRoute.SETTINGS)
            "home_menu_button" -> invokePrivate(screen, "showNavigation")
            "home_mute_button" -> invokePrivate(screen, "showPlaceholderDialog")
            else -> error("Activity test has no direct Home action mapping for $tag")
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun homeText(activity: MainActivity, tag: String): String {
        val state = screen(activity)::class.java.getDeclaredField("homeUiState").apply {
            isAccessible = true
        }.get(screen(activity)) as androidx.compose.runtime.MutableState<*>
        val value = state.value as HomeScreenUiState
        return when (tag) {
            "home_audio_source" -> value.audioSourceText
            "home_bluetooth_pill" -> value.bluetoothText
            "home_status_supplemental" -> value.supplementalText.orEmpty()
            else -> error("Activity test has no Home text mapping for $tag")
        }
    }

    private fun screen(activity: MainActivity): MainScreen =
        MainActivity::class.java.getDeclaredField("screen").apply {
            isAccessible = true
        }.get(activity) as MainScreen

    private fun invokeShowPage(screen: MainScreen, route: MainRoute) =
        MainScreen::class.java.getDeclaredMethod("showPage", MainRoute::class.java).apply {
            isAccessible = true
        }.invoke(screen, route)

    private fun invokePrivate(screen: MainScreen, name: String) =
        MainScreen::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(screen)

    private fun stateValue(screen: MainScreen, fieldName: String): Any? =
        (MainScreen::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
        }.get(screen) as androidx.compose.runtime.MutableState<*>).value

    private fun setPrivateString(screen: MainScreen, fieldName: String, value: String) {
        MainScreen::class.java.getDeclaredField(fieldName).apply {
            isAccessible = true
            set(screen, value)
        }
    }

    @Test
    fun activityOwnsXmlRoutesAndRestoresUiStateBeforeServiceStart() {
        val firstController = Robolectric.buildActivity(MainActivity::class.java).create()
        val first = firstController.get()

        clickHome(first, "home_settings_button")
        setPrivateString(screen(first), "settingsNicknameDraft", "Activity Draft")
        invokePrivate(screen(first), "renderSettings")

        val savedState = Bundle()
        firstController.saveInstanceState(savedState)
        firstController.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedState)
            .get()

        assertNotNull(recreated.findViewById<View>(R.id.settings_scroll))
        assertEquals(
            "Activity Draft",
            (stateValue(screen(recreated), "settingsUiState") as SettingsScreenUiState).nickname
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

        clickHome(activity, "home_mute_button")
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

        clickHome(activity, "home_settings_button")
        invokeShowPage(screen(activity), MainRoute.LOGS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            activity.getString(R.string.logs_empty),
            (stateValue(screen(activity), "logsUiState") as LogsScreenUiState).logText
        )

        setPrivateBoolean(activity, "replayingServiceSnapshot", false)
        activity.onStatusChanged("实时服务状态", running = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            (stateValue(screen(activity), "logsUiState") as LogsScreenUiState).logText
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
            homeText(activity, "home_audio_source")
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
            homeText(activity, "home_audio_source")
        )
        assertFalse(
            homeText(activity, "home_bluetooth_pill")
                .contains(BLUETOOTH_CONNECTED_TEXT)
        )

        clickHome(activity, "home_menu_button")
        invokeShowPage(screen(activity), MainRoute.DISCOVER)
        assertEquals(
            0,
            (stateValue(screen(activity), "discoverUiState") as DiscoverScreenUiState)
                .presentation
                .cards
                .size
        )
        controller.destroy()
    }

    @Test
    fun serviceDisconnectBeforeRebindConnectionDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        setPrivateBoolean(activity, "bindingRegistered", true)
        setPrivateBoolean(activity, "serviceConnected", false)
        setPrivateBoolean(activity, "serviceConnected", true)
        activity.onStatusChanged("保留当前状态", running = false)
        setPrivateBoolean(activity, "serviceConnected", false)
        shadowOf(Looper.getMainLooper()).idle()

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            "保留当前状态",
            homeText(activity, "home_status_supplemental")
        )
        controller.destroy()
    }

    @Test
    fun lateServiceDisconnectAfterActivityStopDoesNotOverwriteUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        controller.stop()
        setPrivateBoolean(activity, "serviceConnected", true)
        activity.onStatusChanged("保留当前状态", running = false)
        setPrivateBoolean(activity, "serviceConnected", false)
        shadowOf(Looper.getMainLooper()).idle()
        val before = homeText(activity, "home_status_supplemental")

        val connection = MainActivity::class.java.getDeclaredField("serviceConnection").apply {
            isAccessible = true
        }.get(activity) as ServiceConnection
        connection.onServiceDisconnected(
            ComponentName(activity, IntercomService::class.java)
        )

        assertEquals(
            before,
            homeText(activity, "home_status_supplemental")
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
