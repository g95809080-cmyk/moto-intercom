package com.kuma.motointercom

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
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

    @Suppress("UNCHECKED_CAST")
    @Test
    fun manualDiscoveryRefreshWithoutABoundServiceReportsUnavailable() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)
        mainScreen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-unbound-refresh")),
            canStart = true
        )
        invokeShowPage(mainScreen, MainRoute.DISCOVER)
        val refresh = MainScreen::class.java
            .getDeclaredField("onRequestDiscoveryRefresh")
            .apply { isAccessible = true }
            .get(mainScreen) as () -> Unit

        refresh()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            SERVICE_UNAVAILABLE_STATUS,
            (stateValue(mainScreen, "discoverUiState") as DiscoverScreenUiState).supplementalText
        )
        controller.destroy()
    }

    @Test
    fun activityLoadsPersistedVoxSettingsBeforeAnyServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = AudioControlPreferences(application)
        preferences.saveVoxSettings(
            AudioControlSettings(voxEnabled = false, voxSensitivity = 70)
        )
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        val home = stateValue(screen(activity), "homeUiState") as HomeScreenUiState
        assertEquals("DISABLED", home.voxText)
        assertEquals(70, home.voxSensitivity)
        assertFalse(home.voxEnabled)

        controller.destroy()
        preferences.saveVoxSettings(AudioControlSettings())
    }

    @Test
    fun activityLoadsAndPersistsAutomaticReconnectBeforeServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = application.getSharedPreferences("moto_intercom", android.content.Context.MODE_PRIVATE)
        preferences.edit().putBoolean("automatic_reconnect_enabled", false).commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)

        assertFalse(
            MainActivity::class.java.getDeclaredField("automaticReconnectEnabled").apply {
                isAccessible = true
            }.getBoolean(activity)
        )
        invokeShowPage(mainScreen, MainRoute.SETTINGS)
        assertFalse(
            (stateValue(mainScreen, "settingsUiState") as SettingsScreenUiState)
                .automaticReconnectEnabled
        )

        @Suppress("UNCHECKED_CAST")
        val callback = MainScreen::class.java.getDeclaredField("onAutomaticReconnectChanged").apply {
            isAccessible = true
        }.get(mainScreen) as (Boolean) -> Unit
        callback(true)

        assertTrue(preferences.getBoolean("automatic_reconnect_enabled", false))
        controller.destroy()
    }

    @Test
    fun feedbackUsesAnExplicitUserChooserWithoutAttachingSessionLogs() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()

        MainActivity::class.java.getDeclaredMethod("sendFeedback", String::class.java).apply {
            isAccessible = true
        }.invoke(activity, "1.1.0")

        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val payload = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            ?: error("feedback chooser is missing its send Intent")
        assertEquals(Intent.ACTION_SEND, payload.action)
        assertEquals("text/plain", payload.type)
        assertTrue(payload.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().contains("MotoCom"))
        val expectedBody = activity.getString(
            R.string.feedback_body,
            "1.1.0",
            "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        )
        assertEquals(expectedBody, payload.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(null, payload.data)
        payload.clipData?.let { clipData ->
            repeat(clipData.itemCount) { index ->
                val item = clipData.getItemAt(index)
                assertEquals(null, item.uri)
                assertFalse(item.text?.toString().orEmpty().contains("session log", ignoreCase = true))
            }
        }
        assertEquals(
            0,
            payload.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
        )
    }

    @Test
    fun activityOwnsAndPersistsPreferredAudioRouteBeforeServiceReplay() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val preferences = AudioRoutePreferences(application)
        preferences.save(AudioRouteSelection.EARPIECE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        assertEquals(
            AudioRouteSelection.EARPIECE,
            MainScreen::class.java.getDeclaredField("preferredAudioRoute").apply {
                isAccessible = true
            }.get(screen(activity))
        )

        MainActivity::class.java.getDeclaredMethod(
            "savePreferredAudioRoute",
            AudioRouteSelection::class.java
        ).apply { isAccessible = true }.invoke(activity, AudioRouteSelection.SPEAKER)

        assertEquals(AudioRouteSelection.SPEAKER, AudioRoutePreferences(application).load())
        assertEquals(
            AudioRouteSelection.SPEAKER,
            MainScreen::class.java.getDeclaredField("preferredAudioRoute").apply {
                isAccessible = true
            }.get(screen(activity))
        )

        controller.destroy()
        preferences.save(AudioRouteSelection.BLUETOOTH)
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
    fun incomingConfirmationDismissesPairingManagementBeforeTakingPriority() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        val mainScreen = screen(activity)
        val presence = pairedPresence()
        invokeShowPage(mainScreen, MainRoute.DISCOVER)
        mainScreen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-pairing-priority")),
            canStart = true
        )
        mainScreen.setPresences(listOf(presence))
        MainScreen::class.java.getDeclaredMethod(
            "showPairingManagement",
            RiderPresence::class.java
        ).apply { isAccessible = true }.invoke(mainScreen, presence)
        val pairingDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("pairing management dialog was not shown")
        assertTrue(pairingDialog.isShowing)

        showIncomingConfirmation(activity, incomingPrompt("pairing-priority", "Incoming Rider"))
        val incomingDialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming confirmation dialog was not shown")

        assertFalse(pairingDialog.isShowing)
        assertTrue(incomingDialog.isShowing)
        assertEquals(
            activity.getString(R.string.incoming_confirmation_title, "Incoming Rider"),
            shadowOf(incomingDialog).title
        )
        incomingDialog.dismiss()
        controller.destroy()
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
    fun incomingConfirmationSupersedesHelpDialog() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()

        invokePrivate(screen(activity), "showHelpDialog")
        val help = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("help dialog was not shown")
        assertTrue(help.isShowing)

        showIncomingConfirmation(activity, incomingPrompt("incoming-nonce", "Incoming Rider"))
        val incoming = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("incoming dialog was not shown")

        assertFalse(help.isShowing)
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

    private fun pairedPresence(): RiderPresence = RiderPresence(
        deviceId = "paired-device",
        sessionId = RuntimeSessionId("paired-session"),
        nickname = "Paired Rider",
        deviceName = "Paired Phone",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "paired-endpoint",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = PairingRecord(
            remoteDeviceId = "paired-device",
            remoteNickname = "Paired Rider",
            deviceName = "Paired Phone",
            localAlias = "Paired Rider",
            shortCode = "1234",
            pairedAt = 1L,
            lastConnectedAt = 2L,
            isPreferred = false,
            lastTransport = "LAN",
            failureCount = 0
        )
    )
}
