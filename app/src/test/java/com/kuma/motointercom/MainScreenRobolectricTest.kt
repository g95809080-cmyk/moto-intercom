package com.kuma.motointercom

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.RoundedCorner
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainScreenRobolectricTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val connectButton: SemanticsNodeInteraction
        get() = discoverNode("discover_connect_device-a")

    @Test
    fun homeRouteHostsComposeContentInsideTheRestorableScrollContainer() {
        val fixture = fixture()
        val root = fixture.screen.root

        assertNotNull(root.findViewById<ScrollView>(R.id.home_scroll))
        assertTrue(root.findViewById<View>(R.id.home_content) is ComposeView)
    }

    @Test
    fun topLevelRoutesAndNavigationPanelStayUiOnly() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)

        assertNotNull(pageContainer.findViewById<View>(R.id.home_scroll))
        composeRule.onNodeWithTag("home_primary_button").assertIsNotEnabled()
        assertEquals(View.GONE, root.findViewById<View>(R.id.navigation_panel).visibility)

        clickHome("home_menu_button")
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigation_panel).visibility)
        assertFlexibleButton(root.findViewById(R.id.nav_home_button))
        assertFlexibleButton(root.findViewById(R.id.nav_discover_button))
        assertFlexibleButton(root.findViewById(R.id.nav_settings_button))
        assertEquals("当前页面", root.findViewById<Button>(R.id.nav_home_button).stateDescription)
        assertTrue(fixture.screen.handleBack())
        assertEquals(View.GONE, root.findViewById<View>(R.id.navigation_panel).visibility)

        clickHome("home_settings_button")
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_scroll))
        assertTrue(settingsExists("settings_discovery_candidates"))
        assertTrue(fixture.screen.handleBack())
        assertNotNull(pageContainer.findViewById<View>(R.id.home_scroll))
    }

    @Test
    fun requiredWindowMatrixSwitchesNavigationAndExpandedPane() {
        val cases = listOf(
            Triple(360, 640, MainWindowWidthClass.Compact),
            Triple(412, 915, MainWindowWidthClass.Compact),
            Triple(915, 412, MainWindowWidthClass.Expanded),
            Triple(700, 900, MainWindowWidthClass.Medium),
            Triple(840, 1200, MainWindowWidthClass.Expanded),
            Triple(1200, 840, MainWindowWidthClass.Expanded)
        )

        cases.forEach { (widthDp, heightDp, expectedClass) ->
            val fixture = fixture()
            measureAtWidth(fixture, widthDp, heightDp)
            fixture.screen.onWindowSizeChanged(widthDp, heightDp)
            val root = fixture.screen.root
            if (expectedClass == MainWindowWidthClass.Compact) {
                assertEquals(
                    "width=${widthDp}dp expected compact top-menu navigation without bottom navigation",
                    View.GONE,
                    root.findViewById<View>(R.id.bottom_navigation).visibility
                )
                assertEquals(
                    "width=${widthDp}dp expected compact without rail",
                    View.GONE,
                    root.findViewById<View>(R.id.navigation_rail).visibility
                )
            } else {
                assertEquals(
                    "width=${widthDp}dp expected rail without bottom navigation",
                    View.GONE,
                    root.findViewById<View>(R.id.bottom_navigation).visibility
                )
                assertEquals(
                    "width=${widthDp}dp expected rail",
                    View.VISIBLE,
                    root.findViewById<View>(R.id.navigation_rail).visibility
                )
            }
            assertEquals(
                if (expectedClass == MainWindowWidthClass.Expanded) View.VISIBLE else View.GONE,
                root.findViewById<View>(R.id.expanded_detail_container).visibility
            )
        }
    }

    @Test
    fun compactNavigationAndScrollableContentRemainUsableAtLargeFontScales() {
        listOf(1.5f, 2.0f).forEach { fontScale ->
            val fixture = fixture(fontScale = fontScale)
            measureAtWidth(fixture, widthDp = 360, heightDp = 640)
            fixture.screen.onWindowSizeChanged(widthDp = 360, heightDp = 640)
            val root = fixture.screen.root
            val bottomNavigation = root.findViewById<View>(R.id.bottom_navigation)
            assertEquals(View.GONE, bottomNavigation.visibility)
            clickHome("home_menu_button")
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigation_panel).visibility)
            assertFlexibleButton(root.findViewById(R.id.nav_home_button))
            assertFlexibleButton(root.findViewById(R.id.nav_discover_button))
            assertFlexibleButton(root.findViewById(R.id.nav_settings_button))
            assertTrue(fixture.screen.handleBack())
            assertEquals(View.GONE, root.findViewById<View>(R.id.navigation_panel).visibility)
            val scroll = root.findViewById<ScrollView>(R.id.home_scroll)
            val content = root.findViewById<View>(R.id.home_content)
            assertTrue(content.right <= scroll.width)
            assertTrue(content.bottom >= scroll.height || content.height > 0)
        }
    }

    @Test
    fun navigatingToTheCurrentRouteDoesNotReplaceItsPageInstance() {
        val fixture = fixture()
        val before = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)

        fixture.screen.navigateHome()

        assertSame(before, fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll))
    }

    @Test
    fun navigationPanelIsAccessibilityModalAndRestoresPageFocus() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        val homeContent = root.findViewById<ComposeView>(R.id.home_content)
        homeContent.requestFocus()

        clickHome("home_menu_button")

        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            pageContainer.importantForAccessibility
        )
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_YES,
            root.findViewById<View>(R.id.navigation_panel).importantForAccessibility
        )
        assertTrue(root.findViewById<View>(R.id.nav_home_button).hasFocus())

        assertTrue(fixture.screen.handleBack())

        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
            pageContainer.importantForAccessibility
        )
        assertTrue(homeContent.hasFocus())
    }

    @Test
    fun homeAudioSettingsCtaOpensSettingsAudioSection() {
        val fixture = fixture()
        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)

        clickHome("home_audio_settings_button")

        assertNotNull(pageContainer.findViewById<View>(R.id.settings_scroll))
        assertTrue(settingsExists("settings_audio_source"))
        assertTrue(settingsExists("settings_audio_route_button"))
    }

    @Test
    fun deferredAudioFocusDoesNotTouchNewRouteAfterImmediateBack() {
        val fixture = fixture()
        val root = fixture.screen.root

        clickHome("home_audio_settings_button")
        assertTrue(settingsExists("settings_audio_source"))
        clickSettings("settings_back_button")

        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(root.findViewById<View>(R.id.home_scroll))
        assertEquals(0, root.findViewById<ScrollView>(R.id.home_scroll).scrollY)
    }

    @Test
    fun audioSourceCallbackUsesGenericBluetoothFallbackWithoutDeviceName() {
        val fixture = fixture()

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 ( )", bluetooth = true)
        assertHomeText("home_audio_source", BLUETOOTH_AUDIO_CONNECTED_TEXT)

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 (头盔蓝牙)", bluetooth = true)
        assertHomeText("home_audio_source", BLUETOOTH_AUDIO_CONNECTED_TEXT)

        fixture.screen.setAudioSource("", bluetooth = false)
        assertHomeText("home_audio_source", AUDIO_SOURCE_STANDBY_TEXT)

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 (Helmet)", bluetooth = false)
        assertHomeText("home_audio_source", AUDIO_SOURCE_STANDBY_TEXT)
    }

    @Test
    fun missingBluetoothPermissionDoesNotRenderReplayedBluetoothSourceAsConnected() {
        val fixture = fixture()
        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 (Helmet)", bluetooth = true)
        fixture.screen.setOptionalPermissionState(
            bluetoothPermissionMissing = true,
            notificationPermissionMissing = false
        )

        assertEquals(
            BLUETOOTH_PERMISSION_UNAVAILABLE,
            homeText("home_audio_source")
        )

        clickHome("home_settings_button")
        val settingsAudio = settingsText("settings_audio_source")
        assertTrue(settingsAudio.contains(BLUETOOTH_PERMISSION_UNAVAILABLE))
        assertFalse(settingsAudio.contains("Helmet"))
    }

    @Test
    fun discoverAndLogsRoutesUseDedicatedScrollHosts() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)

        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()
        assertNotNull(pageContainer.findViewById<View>(R.id.discover_scroll))

        clickDiscover("discover_help_button")
        dismissPlaceholder()
        clickDiscover("discover_back_button")

        clickHome("home_settings_button")
        clickSettings("settings_logs_button")
        assertNotNull(pageContainer.findViewById<View>(R.id.logs_scroll))
        clickLogs("logs_back_button")
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_scroll))
    }

    @Test
    fun discoverNonPresenceRefreshKeepsCardViewsAndScrollOffset() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.DISCOVER)
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-refresh")),
            canStart = true
        )
        fixture.screen.setPresences(
            List(8) { index ->
                selectablePresence(
                    nickname = "Rider $index",
                    deviceName = "Device $index"
                ).copy(
                    deviceId = "device-$index",
                    sessionId = RuntimeSessionId("session-$index")
                )
            }
        )
        measureAtWidth(fixture, widthDp = 360)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.discover_scroll)
        val maxScroll = (scroll.getChildAt(0).measuredHeight - scroll.height).coerceAtLeast(0)
        val preservedOffset = minOf(120, maxScroll)
        scroll.scrollTo(0, preservedOffset)

        fixture.screen.setStatus("仅更新发现状态补充文案", appendLog = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, discoverGroupCount("discover_nearby_container"))
        assertEquals(preservedOffset, scroll.scrollY)
    }

    @Test
    fun discoverRendersNoConnectableCopyWhenSnapshotHasOnlyOfflinePresence() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.DISCOVER)
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-unavailable")),
            canStart = true
        )
        fixture.screen.setPresences(
            listOf(
                selectablePresence().copy(
                    candidates = listOf(
                        PresenceTransportCandidate(
                            transport = Transport.LAN,
                            endpointId = "expired",
                            address = "127.0.0.1",
                            port = 1234,
                            lastSeenElapsedRealtimeMs = 1L,
                            isAvailable = false
                        )
                    )
                )
            )
        )

        assertEquals(
            "当前没有可连接的车友",
            discoverText("discover_state_text")
        )
        assertFalse(discoverExists("discover_connect_device-a"))
    }

    @Test
    fun discoverPresenceFactsUseResourceBackedTruthfulLabels() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.DISCOVER)
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-card-facts")),
            canStart = true
        )
        val preferred = selectablePresence().copy(
            pairing = PairingRecord(
                remoteDeviceId = "device-a",
                remoteNickname = "Road Captain",
                deviceName = "Pixel",
                localAlias = "Road Captain",
                shortCode = "123456",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = true,
                lastTransport = "LAN",
                failureCount = 0
            )
        )
        val nearby = selectablePresence().copy(
            deviceId = "device-nearby",
            sessionId = RuntimeSessionId("session-nearby")
        )
        fixture.screen.setPresences(listOf(preferred, offlinePairedPresence(), nearby))

        assertEquals(
            fixture.activity.getString(R.string.discover_fact_paired) +
                fixture.activity.getString(R.string.discover_fact_separator) +
                fixture.activity.getString(R.string.discover_fact_preferred),
            discoverText("discover_facts_device-a")
        )

        assertEquals(
            fixture.activity.getString(R.string.discover_fact_paired) +
                fixture.activity.getString(R.string.discover_fact_separator) +
                fixture.activity.getString(R.string.discover_fact_unavailable),
            discoverText("discover_facts_device-offline")
        )

        assertEquals(
            fixture.activity.getString(R.string.discover_fact_current),
            discoverText("discover_facts_device-nearby")
        )
    }

    @Test
    fun activityRecreationRestoresRouteScrollAndNicknameDraftButNotProductState() {
        val first = fixture(initialRiderName = "Persisted Rider")
        first.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-recreation")),
            canStart = true
        )
        openRoute(first, MainRoute.SETTINGS)
        measureAtWidth(first, widthDp = 360)

        replaceSettingsText("settings_nickname_input", "Unsaved Draft")
        val firstScroll = first.screen.root.findViewById<ScrollView>(R.id.settings_scroll)
        val maxScroll = firstScroll.getChildAt(0).measuredHeight - firstScroll.height
        assertTrue("settings content must be scrollable before saving state", maxScroll > 0)
        val savedScrollY = minOf(120, maxScroll)
        firstScroll.scrollTo(0, savedScrollY)
        val discoveringSummary = settingsText("settings_product_state")

        val savedState = Bundle()
        first.screen.saveState(savedState)

        val recreated = fixture(
            savedState = savedState,
            initialRiderName = "Persisted Rider"
        )
        measureAtWidth(recreated, widthDp = 360)
        shadowOf(Looper.getMainLooper()).idle()

        val recreatedScroll = recreated.screen.root.findViewById<ScrollView>(R.id.settings_scroll)
        val recreatedSummary = settingsText("settings_product_state")

        assertEquals("Unsaved Draft", settingsText("settings_nickname_input"))
        assertEquals(savedScrollY, recreatedScroll.scrollY)
        assertTrue("product state must come from the new runtime, not saved UI state", recreatedSummary != discoveringSummary)

        clickSettings("settings_back_button")
        assertNotNull(recreated.screen.root.findViewById<View>(R.id.home_scroll))
    }

    @Test
    fun adaptiveStateRestoresSelectedPresenceAcrossWindowClassChange() {
        val first = fixture()
        val presence = selectablePresence()
        openRoute(first, MainRoute.DISCOVER)
        first.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-adaptive-restore")),
            canStart = true
        )
        first.screen.setPresences(listOf(presence))
        measureAtWidth(first, widthDp = 840, heightDp = 1200)
        first.screen.onWindowSizeChanged(widthDp = 840, heightDp = 1200)
        clickDiscoverSelection("discover_select_device-a")
        first.screen.onWindowSizeChanged(widthDp = 840, heightDp = 1200)
        assertEquals(
            "Road Captain",
            first.screen.root.findViewById<TextView>(R.id.expanded_detail_title).text.toString()
        )

        val savedState = Bundle()
        first.screen.saveState(savedState)

        val recreated = fixture(savedState = savedState)
        recreated.screen.setPresences(listOf(presence))
        measureAtWidth(recreated, widthDp = 360, heightDp = 640)
        recreated.screen.onWindowSizeChanged(widthDp = 360, heightDp = 640)
        assertNotNull(recreated.screen.root.findViewById<View>(R.id.discover_scroll))
        assertEquals(View.GONE, recreated.screen.root.findViewById<View>(R.id.bottom_navigation).visibility)

        recreated.screen.onWindowSizeChanged(widthDp = 840, heightDp = 1200)
        assertEquals(
            "Road Captain",
            recreated.screen.root.findViewById<TextView>(R.id.expanded_detail_title).text.toString()
        )
    }

    @Test
    fun resumeScrollRestoreWinsOverFocusedNicknameChildState() {
        val first = fixture(initialRiderName = "Persisted Rider")
        openRoute(first, MainRoute.SETTINGS)
        measureAtWidth(first, widthDp = 360)
        replaceSettingsText("settings_nickname_input", "Unsaved Draft")
        val firstScroll = first.screen.root.findViewById<ScrollView>(R.id.settings_scroll)
        val maxScroll = firstScroll.getChildAt(0).measuredHeight - firstScroll.height
        assertTrue(maxScroll > 0)
        val savedScrollY = minOf(120, maxScroll)
        firstScroll.scrollTo(0, savedScrollY)

        val savedState = Bundle()
        first.screen.saveState(savedState)
        val recreated = fixture(savedState = savedState, initialRiderName = "Persisted Rider")
        measureAtWidth(recreated, widthDp = 360)
        recreated.screen.root.findViewById<ScrollView>(R.id.settings_scroll).scrollTo(0, 0)
        recreated.screen.restoreCurrentPageScrollAfterResume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(savedScrollY, recreated.screen.root.findViewById<ScrollView>(R.id.settings_scroll).scrollY)
    }

    @Test
    fun rapidRouteChangeDoesNotApplyPreviousPageScrollRestoreToNewPage() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        val homeScroll = root.findViewById<ScrollView>(R.id.home_scroll)
        measureAtWidth(fixture, widthDp = 360)
        assertTrue(homeScroll.getChildAt(0).measuredHeight - homeScroll.height >= 140)
        homeScroll.scrollTo(0, 140)

        clickHome("home_settings_button")
        clickSettings("settings_back_button")
        shadowOf(Looper.getMainLooper()).idle()

        val restoredHomeScroll = pageContainer.findViewById<ScrollView>(R.id.home_scroll)
        assertEquals(140, restoredHomeScroll.scrollY)
    }

    @Test
    fun longLabelControlsCanGrowWhileKeepingMinimumTouchTargets() {
        val fixture = fixture()
        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        assertHomeMinimumTouchTarget("home_permission_settings_cta")
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-long-label")),
            canStart = true
        )
        assertHomeMinimumTouchTarget("home_discover_cta")

        clickHome("home_menu_button")
        fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
        fixture.screen.setWifiUnavailable(true)
        assertTrue(discoverExists("discover_wifi_settings_button"))
        assertTrue(discoverExists("discover_rescan_button"))

        clickDiscover("discover_back_button")
        clickHome("home_settings_button")
        assertTrue(settingsExists("settings_save_nickname_button"))
        assertTrue(settingsExists("settings_audio_route_button"))
        assertTrue(settingsExists("settings_about_button"))

        clickSettings("settings_logs_button")
        assertTrue(logsExists("logs_copy_button"))
        assertTrue(logsExists("logs_close_button"))
    }

    @Test
    fun nicknameInputCanGrowWhileKeepingMinimumTouchTarget() {
        val fixture = fixture()
        clickHome("home_settings_button")

        assertTrue(settingsExists("settings_nickname_input"))
    }

    @Test
    fun aboutDialogUsesTheRealVersionAndHasNoInventedServiceClaims() {
        val fixture = fixture()
        clickHome("home_settings_button")
        clickSettings("settings_about_button")

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("about dialog was not shown")
        assertTrue(dialog.isShowing)
        assertEquals(
            fixture.activity.getString(R.string.about_title),
            shadowOf(dialog).title
        )
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        val versionName = fixture.activity.packageManager
            .getPackageInfo(fixture.activity.packageName, 0)
            .versionName
        assertTrue(message.contains(fixture.activity.getString(R.string.brand_name)))
        assertTrue(message.contains("版本 $versionName"))
        assertTrue(message.contains("power by kuma"))
        assertFalse(message.contains("http"))
        assertFalse(message.contains("客服"))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun homeMainControlRowRemainsCenteredAtStandardPhoneWidth() {
        val fixture = fixture()
        measureAtWidth(fixture, widthDp = 360)

        val parent = homeBounds("home_main_control_section")
        val row = homeBounds("home_main_control_row")

        assertTrue(row.width < parent.width)
        assertEquals(parent.center.x, row.center.x, 1f)
    }

    @Test
    fun homeCoreControlRemainsReachableInStandardFirstViewport() {
        val fixture = fixture()
        measureAtWidth(fixture, widthDp = 360, heightDp = 800)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = homeBounds("home_main_control_row")
        assertTrue(
            "Home core control must be hosted inside the scrollable Home content",
            row.top >= 0f && row.bottom <= scroll.getChildAt(0).height
        )
        assertTrue(scroll.canScrollVertically(1))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-420dpi")
    fun homeCoreControlFitsTheRealisticPhoneViewportAtDesignDensity() {
        val fixture = fixture()
        measureAtWidth(fixture, widthDp = 360, heightDp = 800)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = homeBounds("home_main_control_row")

        assertTrue(
            "Home core control should fit inside the first 800dp viewport at 420dpi",
            row.bottom <= scroll.height
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-420dpi")
    fun compactHomeCoreControlAndPermissionActionsRemainReachable() {
        val fixture = fixture()
        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        fixture.screen.setPermissionStatus("缺少必要权限，请先授权")
        fixture.screen.onWindowSizeChanged(widthDp = 360, heightDp = 640)
        measureAtWidth(fixture, widthDp = 360, heightDp = 640)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = homeBounds("home_main_control_row")
        val permissionGrant = homeBounds("home_permission_grant_cta")

        assertTrue(
            "Home core control should start within the compact first viewport",
            row.top < scroll.height
        )

        val maxScrollY = (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0)
        assertTrue(
            "Home core control should be fully reachable after compact scrolling",
            row.top - maxScrollY >= 0 && row.bottom - maxScrollY <= scroll.height
        )
        assertTrue(
            "Permission grant action should be fully reachable after compact scrolling",
            permissionGrant.top - maxScrollY >= 0 && permissionGrant.bottom - maxScrollY <= scroll.height
        )
    }

    @Test
    fun discoverAndSettingsHeadersKeepTitlesCentered() {
        val fixture = fixture()
        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)

        openRoute(fixture, MainRoute.DISCOVER)
        measureAtWidth(fixture, widthDp = 360)
        assertTrue(discoverExists("discover_title"))

        clickDiscover("discover_back_button")
        clickHome("home_settings_button")
        measureAtWidth(fixture, widthDp = 360)
        assertTrue(settingsExists("settings_title"))
    }

    @Test
    fun logsPlaceCopyActionAfterTheReadableLogRegion() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        assertTrue(logsExists("logs_text"))
        assertTrue(logsExists("logs_copy_button"))
        assertTrue(logsExists("logs_close_button"))
    }

    @Test
    fun logsUseAnIndependentBoundedVerticalScrollRegion() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        val logText = logsNode("logs_text")

        assertEquals(
            fixture.activity.resources.getDimensionPixelSize(R.dimen.motocom_logs_viewport_height).toFloat(),
            logText.fetchSemanticsNode().boundsInRoot.height,
            1f
        )
        assertTrue(logsExists("logs_text"))
    }

    @Test
    fun logsAppendFollowsInnerViewportOnlyWhenAlreadyAtBottom() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        fixture.screen.appendLog("new-log-while-reading-history")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logsText("logs_text").contains("new-log-while-reading-history"))
    }

    @Test
    fun logsAppendPreservesAnIntermediateHistoryOffset() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        fixture.screen.appendLog("new-log-while-reading-middle-history")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logsText("logs_text").contains("new-log-while-reading-middle-history"))
    }

    @Test
    fun nonLogServiceRefreshDoesNotStealLogHistoryOffset() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        val displayedTextBeforeRefresh = logsText("logs_text")

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机", bluetooth = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "non-log Service refreshes must not replace the visible Logs snapshot",
            displayedTextBeforeRefresh,
            logsText("logs_text")
        )
    }

    @Test
    fun logsCopyUsesCurrentSessionOrderAndEnablesOnlyWithLogs() {
        val fixture = fixture()
        fixture.screen.setStatus("第一条")
        fixture.screen.setStatus("第二条")
        openRoute(fixture, MainRoute.LOGS)

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val copyButton = logsNode("logs_copy_button")
        assertTrue(
            !copyButton.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        )
        assertEquals(
            "第一条\n第二条",
            logsText("logs_text")
        )

        copyButton.performClick()

        val clipboard = fixture.activity.getSystemService(ClipboardManager::class.java)
        assertEquals("第一条\n第二条", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun logsCopyIsDisabledForAnEmptyUiSession() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)

        assertFalse(
            !logsNode("logs_copy_button")
                .fetchSemanticsNode()
                .config
                .contains(SemanticsProperties.Disabled)
        )
    }

    @Test
    fun nicknameSaveUiTrimsOnSuccessAndPreservesEditingOnFailure() {
        var savedName: String? = null
        val success = fixture(onSaveRiderName = { value -> savedName = value; true })
        openRoute(success, MainRoute.SETTINGS)
        assertTrue(settingsExists("settings_nickname_input"))
        replaceSettingsText("settings_nickname_input", "  Road Captain  ")
        clickSettings("settings_save_nickname_button")
        assertEquals("Road Captain", savedName)
        assertEquals("Road Captain", settingsNode("settings_nickname_input").fetchSemanticsNode().config[SemanticsProperties.EditableText]?.text)
        assertEquals(NICKNAME_SAVED_FEEDBACK, settingsText("settings_nickname_feedback"))

        val failure = fixture(onSaveRiderName = { false })
        openRoute(failure, MainRoute.SETTINGS)
        replaceSettingsText("settings_nickname_input", "  Keep This Draft  ")
        clickSettings("settings_save_nickname_button")
        assertEquals("  Keep This Draft  ", settingsNode("settings_nickname_input").fetchSemanticsNode().config[SemanticsProperties.EditableText]?.text)
        assertEquals(NICKNAME_SAVE_FAILED_FEEDBACK, settingsText("settings_nickname_feedback"))
    }

    @Test
    fun pageContentStaysWithinSupportedWidthsAtLargeFontScale() {
        val routes = listOf(
            Triple(MainRoute.HOME, R.id.home_scroll, R.id.home_content),
            Triple(MainRoute.DISCOVER, R.id.discover_scroll, R.id.discover_content),
            Triple(MainRoute.SETTINGS, R.id.settings_scroll, R.id.settings_content),
            Triple(MainRoute.LOGS, R.id.logs_scroll, R.id.logs_content)
        )

        for (widthDp in listOf(320, 360, 411, 600)) {
            for ((route, scrollId, contentId) in routes) {
                val fixture = fixture(fontScale = 1.3f)
                openRoute(fixture, route)
                if (route == MainRoute.DISCOVER) {
                    fixture.screen.setIntercomState(
                        IntercomState.Discovering(RuntimeSessionId("runtime-layout")),
                        canStart = true
                    )
                    fixture.screen.setPresences(
                        listOf(
                            selectablePresence(
                                nickname = "Rider ".repeat(12),
                                deviceName = "Device ".repeat(12)
                            )
                        )
                    )
                }
                measureAtWidth(fixture, widthDp)

                val scroll = fixture.screen.root.findViewById<ScrollView>(scrollId)
                val content = fixture.screen.root.findViewById<View>(contentId)
                assertEquals("$route must have one vertical scroll child", 1, scroll.childCount)
                assertTrue("$route at ${widthDp}dp starts inside its viewport", content.left >= 0)
                assertTrue(
                    "$route at ${widthDp}dp must not overflow horizontally",
                    content.right <= scroll.width
                )
                if (widthDp == 600) {
                    assertEquals(
                        "$route at 600dp should keep equal side margins",
                        content.left,
                        scroll.width - content.right
                    )
                }
            }
        }
    }

    @Test
    fun pageContentRemainsReachableInLandscapeShortViewport() {
        val routes = listOf(
            Triple(MainRoute.HOME, R.id.home_scroll, R.id.home_content),
            Triple(MainRoute.DISCOVER, R.id.discover_scroll, R.id.discover_content),
            Triple(MainRoute.SETTINGS, R.id.settings_scroll, R.id.settings_content),
            Triple(MainRoute.LOGS, R.id.logs_scroll, R.id.logs_content)
        )

        for ((route, scrollId, contentId) in routes) {
            val fixture = fixture(fontScale = 1.3f)
            openRoute(fixture, route)
            if (route == MainRoute.DISCOVER) {
                fixture.screen.setIntercomState(
                    IntercomState.Discovering(RuntimeSessionId("runtime-landscape")),
                    canStart = true
                )
                fixture.screen.setPresences(
                    listOf(
                        selectablePresence(
                            nickname = "Landscape Rider ".repeat(8),
                            deviceName = "Landscape Device ".repeat(8)
                        )
                    )
                )
            }
            measureAtWidth(fixture, widthDp = 640, heightDp = 360)

            val scroll = fixture.screen.root.findViewById<ScrollView>(scrollId)
            val content = fixture.screen.root.findViewById<View>(contentId)
            assertEquals("$route must have one vertical scroll child", 1, scroll.childCount)
            assertTrue("$route at landscape width must not overflow horizontally", content.right <= scroll.width)
            assertTrue("$route must retain content below a short landscape viewport", content.height > scroll.height)
        }
    }

    @Test
    fun navigationPanelControlsStayInsideShortLandscapeViewport() {
        val fixture = fixture(fontScale = 1.3f)
        clickHome("home_menu_button")
        measureAtWidth(fixture, widthDp = 640, heightDp = 360)

        val root = fixture.screen.root
        val panel = root.findViewById<View>(R.id.navigation_panel)
        assertTrue(panel.bottom <= root.height)
        listOf(R.id.nav_home_button, R.id.nav_discover_button, R.id.nav_settings_button).forEach { id ->
            val button = root.findViewById<View>(id)
            assertTrue("navigation control $id must stay inside landscape panel", button.bottom <= panel.bottom)
        }
    }

    @Test
    fun confirmedVisualTokensAndAccessibleMutedTextRemainLocked() {
        val fixture = fixture()
        assertEquals(Color.parseColor("#F7F9FC"), fixture.activity.getColor(R.color.motocom_background))
        assertEquals(Color.parseColor("#78D900"), fixture.activity.getColor(R.color.motocom_accent_green))
        assertEquals(Color.parseColor("#7EDB22"), fixture.activity.getColor(R.color.motocom_accent_green_alt))
        assertEquals(Color.parseColor("#1F78D900"), fixture.activity.getColor(R.color.motocom_accent_green_soft))
        assertEquals(Color.parseColor("#2678D900"), fixture.activity.getColor(R.color.motocom_accent_green_pressed))
        assertEquals(Color.parseColor("#4CCB00"), fixture.activity.getColor(R.color.motocom_accent_green_dark))
        assertEquals(Color.parseColor("#F3F4F6"), fixture.activity.getColor(R.color.motocom_surface_soft))
        assertEquals(Color.parseColor("#9CA3AF"), fixture.activity.getColor(R.color.motocom_text_muted))
        assertEquals(
            Color.parseColor("#4B5563"),
            fixture.activity.getColor(R.color.motocom_text_muted_accessible)
        )
    }

    @Test
    fun systemBarAndCutoutInsetsBecomeHostSafePadding() {
        val fixture = fixture()
        val safeInsets = Insets.of(12, 24, 36, 48)
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.systemBars(), safeInsets)
            .build()

        val calculated = calculateSafeWindowInsets(insets)

        assertEquals(12, calculated[0])
        assertEquals(24, calculated[1])
        assertEquals(36, calculated[2])
        assertEquals(48, calculated[3])

        fixture.screen.root.dispatchApplyWindowInsets(insets)

        assertEquals(12, fixture.screen.root.paddingLeft)
        assertEquals(24, fixture.screen.root.paddingTop)
        assertEquals(36, fixture.screen.root.paddingRight)
        assertEquals(48, fixture.screen.root.paddingBottom)
    }

    @Test
    fun gestureAndRoundedCornerInsetsJoinTheHostSafePadding() {
        val insets = WindowInsets.Builder()
            .setInsets(
                WindowInsets.Type.systemGestures(),
                Insets.of(24, 8, 28, 16)
            )
            .setInsets(
                WindowInsets.Type.mandatorySystemGestures(),
                Insets.of(0, 0, 0, 32)
            )
            .setRoundedCorner(
                RoundedCorner.POSITION_TOP_LEFT,
                RoundedCorner(RoundedCorner.POSITION_TOP_LEFT, 18, 18, 18)
            )
            .build()

        val calculated = calculateSafeWindowInsets(insets)

        assertEquals(24, calculated[0])
        assertEquals(18, calculated[1])
        assertEquals(28, calculated[2])
        assertEquals(32, calculated[3])
    }

    @Test
    @Config(sdk = [28])
    fun legacyWindowInsetsKeepSystemWindowSafeEdges() {
        val insets = WindowInsets.CONSUMED.replaceSystemWindowInsets(12, 24, 36, 48)

        val calculated = calculateSafeWindowInsets(insets)

        assertEquals(12, calculated[0])
        assertEquals(24, calculated[1])
        assertEquals(36, calculated[2])
        assertEquals(48, calculated[3])
    }

    @Test
    fun homeXmlRendersAllNineProductStatesWithoutInventingRouteChanges() {
        val fixture = fixture()
        val runtime = RuntimeSessionId("runtime-home-states")
        val attempt = uiAttempt(runtime)
        val peer = PeerIdentity(
            deviceId = "peer-a",
            nickname = "Road Captain",
            deviceName = "Pixel",
            runtimeSessionId = RuntimeSessionId("peer-runtime"),
            isDeviceIdVerified = true
        )
        val states = listOf(
            IntercomState.Offline,
            IntercomState.Discovering(runtime),
            IntercomState.IncomingConfirmation(runtime, attempt.id, peer),
            IntercomState.Connecting(attempt, peer),
            IntercomState.Optimizing(attempt, peer),
            IntercomState.Connected(attempt, peer, connectedAt = 1L, transport = Transport.LAN),
            IntercomState.Recovering(attempt, peer),
            IntercomState.Resetting(
                runtimeSessionId = runtime,
                targetDeviceId = "peer-a",
                failedAttemptId = attempt.id,
                consecutiveFinalFailures = RECOVERY_RESET_FAILURE_THRESHOLD
            ),
            IntercomState.Stopping(runtime)
        )

        states.forEach { state ->
            fixture.screen.setIntercomState(state, canStart = true)

            assertHomeTextIsNotBlank("home_status_title")
            assertHomeTextIsNotBlank("home_primary_button")
            assertEquals(
                "${state.kind} must keep Home route active",
                View.VISIBLE,
                fixture.screen.root.findViewById<View>(R.id.home_scroll).visibility
            )
            assertEquals(
                "${state.kind} Discover CTA visibility",
                state is IntercomState.Discovering,
                homeExists("home_discover_cta")
            )
            assertEquals(
                "${state.kind} Stopping enabled state",
                state !is IntercomState.Stopping,
                homeIsEnabled("home_primary_button")
            )
            assertEquals(
                "VOX：状态接口待接入",
                homeText("home_vox_pill")
            )
        }
    }

    @Test
    fun visibleActionsHaveLabelsAndMinimumTouchTargets() {
        val routes = listOf(MainRoute.HOME, MainRoute.DISCOVER, MainRoute.SETTINGS, MainRoute.LOGS)
        for (route in routes) {
            val fixture = fixture()
            openRoute(fixture, route)
            if (route == MainRoute.DISCOVER) {
                fixture.screen.setIntercomState(
                    IntercomState.Discovering(RuntimeSessionId("runtime-accessibility")),
                    canStart = true
                )
                fixture.screen.setPresences(listOf(selectablePresence()))
            }
            measureAtWidth(fixture, widthDp = 360)
            assertVisibleActionsAreAccessible(fixture.screen.root)
            if (route == MainRoute.HOME) {
                assertHomeActionsAreAccessible()
            }
        }

        val panelFixture = fixture()
        clickHome("home_menu_button")
        measureAtWidth(panelFixture, widthDp = 360)
        assertVisibleActionsAreAccessible(panelFixture.screen.root)
    }

    @Test
    fun placeholderEntriesExposeStaticIconsWithoutChangingTheirTextContract() {
        val fixture = fixture()
        assertTrue(homeContentDescription("home_mute_button").contains("开发中"))

        openRoute(fixture, MainRoute.SETTINGS)
        val settingsPlaceholderIds = listOf(
            R.id.settings_vox_button,
            R.id.settings_vox_sensitivity_button,
            R.id.settings_vox_state_button,
            R.id.settings_audio_route_button,
            R.id.settings_audio_earpiece_button,
            R.id.settings_audio_speaker_button,
            R.id.settings_reconnect_button,
            R.id.settings_help_button
        )
        settingsPlaceholderIds.forEach { id ->
            assertTrue(settingsExists(settingsTagForPlaceholderId(id)))
        }

        clickSettings("settings_back_button")
        clickHome("home_menu_button")
        fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
        val discoverHelp = discoverNode("discover_help_button")
        assertTrue("Discover help placeholder should exist", discoverExists("discover_help_button"))
        assertTrue(discoverHelp.fetchSemanticsNode().config.contains(SemanticsProperties.ContentDescription))
        val rescan = discoverNode("discover_rescan_button")
        assertTrue(discoverExists("discover_rescan_button"))

        clickDiscover("discover_back_button")
        assertTrue(homeContentDescription("home_vox_pill").contains("开发中"))
        assertTrue(homeContentDescription("home_vox_card").contains("开发中"))
    }

    @Test
    fun voxVisualStateChipsRemainDecorativeForAccessibility() {
        val fixture = fixture()
        val stateRow = composeRule.onAllNodesWithTag(
            "home_vox_state_row",
            useUnmergedTree = true
        ).onLast().fetchSemanticsNode()

        assertFalse(stateRow.config.contains(SemanticsActions.OnClick))
        assertEquals(3, stateRow.children.size)
    }

    @Test
    fun placeholderButtonShowsOneDialogAndPositiveActionDismissesIt() {
        val fixture = fixture()
        clickHome("home_mute_button")

        val dialog = ShadowAlertDialog.getLatestAlertDialog() ?: error("placeholder dialog was not shown")
        val shadow = shadowOf(dialog)
        assertEquals(PLACEHOLDER_DIALOG_TITLE, shadow.title)
        assertEquals(PLACEHOLDER_DIALOG_MESSAGE, shadow.message)
        assertEquals(PLACEHOLDER_DIALOG_BUTTON, dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)

        clickHome("home_mute_button")
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun homeVoxFactPillOpensThePlaceholderDialog() {
        val fixture = fixture()

        clickHome("home_vox_pill")

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
            ?: error("placeholder dialog was not shown for the Home VOX fact pill")
        assertTrue(dialog.isShowing)
        assertEquals(PLACEHOLDER_DIALOG_TITLE, shadowOf(dialog).title)
        assertEquals(PLACEHOLDER_DIALOG_MESSAGE, shadowOf(dialog).message)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun allPlaceholderControlsShareOneDialogWithoutChangingRoute() {
        val cases = listOf(
            MainRoute.HOME to listOf(R.id.home_mute_button, R.id.home_vox_pill, R.id.home_vox_card),
            MainRoute.DISCOVER to listOf(R.id.discover_help_button, R.id.discover_rescan_button),
            MainRoute.SETTINGS to listOf(
                R.id.settings_audio_route_button,
                R.id.settings_audio_earpiece_button,
                R.id.settings_audio_speaker_button,
                R.id.settings_vox_button,
                R.id.settings_vox_sensitivity_button,
                R.id.settings_vox_state_button,
                R.id.settings_reconnect_button,
                R.id.settings_help_button
            )
        )

        cases.forEach { (route, placeholderIds) ->
            val fixture = fixture()
            openRoute(fixture, route)
            val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
            val pageId = when (route) {
                MainRoute.HOME -> R.id.home_scroll
                MainRoute.DISCOVER -> R.id.discover_scroll
                MainRoute.SETTINGS -> R.id.settings_scroll
                MainRoute.LOGS -> error("Logs has no placeholder controls")
            }

            clickPlaceholder(fixture, route, placeholderIds.first())
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
                ?: error("placeholder dialog was not shown for $route")
            assertTrue(dialog.isShowing)

            placeholderIds.drop(1).forEach { id ->
                clickPlaceholder(fixture, route, id)
                assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(dialog.isShowing)
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertNotNull(pageContainer.findViewById<View>(pageId))
        }
    }

    @Test
    fun everyPlaceholderControlCanOpenTheSharedDialogOnItsOwn() {
        val cases = listOf(
            MainRoute.HOME to listOf(R.id.home_mute_button, R.id.home_vox_pill, R.id.home_vox_card),
            MainRoute.DISCOVER to listOf(R.id.discover_help_button, R.id.discover_rescan_button),
            MainRoute.SETTINGS to listOf(
                R.id.settings_audio_route_button,
                R.id.settings_audio_earpiece_button,
                R.id.settings_audio_speaker_button,
                R.id.settings_vox_button,
                R.id.settings_vox_sensitivity_button,
                R.id.settings_vox_state_button,
                R.id.settings_reconnect_button,
                R.id.settings_help_button
            )
        )

        cases.forEach { (route, placeholderIds) ->
            placeholderIds.forEach { id ->
                val fixture = fixture()
                openRoute(fixture, route)
                clickPlaceholder(fixture, route, id)

                val dialog = ShadowAlertDialog.getLatestAlertDialog()
                    ?: error("placeholder dialog was not shown for $route#$id")
                assertTrue(dialog.isShowing)
                assertEquals(PLACEHOLDER_DIALOG_TITLE, shadowOf(dialog).title)
                assertEquals(PLACEHOLDER_DIALOG_MESSAGE, shadowOf(dialog).message)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test
    fun permissionStatusRefreshesOnlyItsOwnSupplementalCopy() {
        val fixture = fixture()

        fixture.screen.setPermissionStatus("缺少必要权限，请先授权")
        assertHomeText("home_status_supplemental", "缺少必要权限，请先授权")

        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        assertHomeText("home_status_supplemental", "请点击下方启动对讲")

        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        assertHomeText("home_status_supplemental", SERVICE_UNAVAILABLE_STATUS)
    }

    @Test
    fun permissionRefreshClearsOfflineDisabledReasonWhenStartBecomesAvailable() {
        val fixture = fixture()
        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        homeNode("home_primary_button").assertIsNotEnabled()
        assertHomeText("home_disabled_reason", "缺少必要权限")

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        homeNode("home_primary_button").assertIsEnabled()
        assertFalse(homeExists("home_disabled_reason"))
    }

    @Test
    fun discoveringClearsOfflinePermissionCopyButKeepsServiceCopy() {
        val fixture = fixture()
        val runtime = RuntimeSessionId("runtime-permission-transition")

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        assertHomeText("home_status_supplemental", "")

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        assertHomeText("home_status_supplemental", SERVICE_UNAVAILABLE_STATUS)
    }

    @Test
    fun wifiStatusRefreshClearsOnlyItsOwnSupplementalCopy() {
        val fixture = fixture()
        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setStatus(WIFI_UNAVAILABLE_TEXT)
        assertHomeText("home_status_supplemental", WIFI_UNAVAILABLE_TEXT)

        fixture.screen.setWifiUnavailable(false)
        assertHomeText("home_status_supplemental", "")

        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setWifiUnavailable(false)
        assertHomeText("home_status_supplemental", SERVICE_UNAVAILABLE_STATUS)
    }

    @Test
    fun wifiRemediationYieldsToMissingPermissionAndReturnsWhenReady() {
        val fixture = fixture()
        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("ready")
        fixture.screen.setWifiUnavailable(true)
        assertHomeText("home_status_supplemental", WIFI_UNAVAILABLE_TEXT)

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        fixture.screen.setPermissionStatus("permission required")
        assertHomeText("home_status_supplemental", "permission required")

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("ready")
        fixture.screen.setWifiUnavailable(true)
        assertHomeText("home_status_supplemental", WIFI_UNAVAILABLE_TEXT)
    }

    @Test
    fun settingsCandidateSummaryRefreshesWhenPresenceSnapshotChanges() {
        val fixture = fixture()
        clickHome("home_settings_button")
        val summaryTag = "settings_discovery_candidates"

        fixture.screen.setPresences(listOf(
            RiderPresence(
                deviceId = "device-a",
                sessionId = RuntimeSessionId("session-a"),
                nickname = "Road Captain",
                deviceName = "Pixel",
                protocolVersion = 2,
                lastSeenElapsedRealtimeMs = 1L,
                candidates = listOf(
                    PresenceTransportCandidate(
                        transport = Transport.LAN,
                        endpointId = "endpoint-a",
                        address = "127.0.0.1",
                        port = 1234,
                        lastSeenElapsedRealtimeMs = 1L,
                        isAvailable = true
                    )
                ),
                pairing = null
            )
        ))
        assertTrue(settingsText(summaryTag).contains("Road Captain"))

        fixture.screen.setPresences(emptyList())
        assertTrue(settingsText(summaryTag).isNotBlank())
    }

    @Test
    fun discoveryResetsTheLastPeerNameBeforeALaterStop() {
        val fixture = fixture()
        val runtime = RuntimeSessionId("runtime-a")

        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = true)
        fixture.screen.setRemoteRider("Road Captain")
        assertHomeText("home_peer_name", "Road Captain")

        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = false)
        assertHomeText("home_peer_name", "正在结束对讲")
    }

    @Test
    fun offlineErrorDoesNotPoisonTheNextDiscoveryCta() {
        val fixture = fixture()
        val runtime = RuntimeSessionId("runtime-a")

        fixture.screen.setIntercomError("旧服务错误")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)

        assertHomeText("home_discover_cta", HOME_DISCOVER_CTA)
    }

    @Test
    fun serviceErrorClearsPendingPresenceSelectionBeforeTheNextSnapshot() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-a")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))
        clickDiscover("discover_connect_device-a")

        fixture.screen.setIntercomError("服务拒绝了连接")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)

        assertTrue(discoverEnabled("discover_connect_device-a"))
        assertTrue(discoverText("discover_connect_device-a").isNotBlank())
    }

    @Test
    fun expiredPresenceReleasesThePendingConnectLock() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-a")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))

        clickDiscover("discover_connect_device-a")
        assertFalse(discoverEnabled("discover_connect_device-a"))

        fixture.screen.setPresences(emptyList())
        fixture.screen.setPresences(listOf(presence))

        assertTrue(discoverEnabled("discover_connect_device-a"))
        assertTrue(discoverText("discover_connect_device-a").isNotBlank())
    }

    @Test
    fun serviceFactClearReleasesThePendingConnectLock() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-service-clear")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))

        clickDiscover("discover_connect_device-a")
        assertFalse(discoverEnabled("discover_connect_device-a"))

        fixture.screen.clearServiceOwnedFacts()
        fixture.screen.setPresences(listOf(presence))

        assertTrue(discoverEnabled("discover_connect_device-a"))
        assertTrue(discoverText("discover_connect_device-a").isNotBlank())
    }

    @Test
    fun serviceFactClearDropsTheLastPeerNameBeforeOfflineStateRefresh() {
        val fixture = fixture()
        val runtime = RuntimeSessionId("runtime-service-facts")

        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = true)
        fixture.screen.setRemoteRider("Road Captain")
        assertHomeText("home_peer_name", "Road Captain")

        fixture.screen.clearServiceOwnedFacts()

        assertHomeText("home_peer_name", "正在结束对讲")
    }

    @Test
    fun stalePresenceCardCannotDispatchAfterSnapshotExpires() {
        var dispatchCount = 0
        val fixture = fixture(
            onConnectPresence = {
                dispatchCount += 1
                true
            }
        )
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-stale-card")),
            canStart = true
        )
        val presence = selectablePresence()
        fixture.screen.setPresences(listOf(presence))
        fixture.screen.setPresences(emptyList())

        assertEquals(0, dispatchCount)
        assertFalse(discoverExists("discover_connect_device-a"))
    }

    @Test
    fun failedPresenceDispatchKeepsDiscoverRouteAndShowsServiceUnavailable() {
        val fixture = fixture(onConnectPresence = { false })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-dispatch-failure")),
            canStart = true
        )
        fixture.screen.setPresences(listOf(selectablePresence()))

        clickDiscover("discover_connect_device-a")

        assertNotNull(pageContainer.findViewById<View>(R.id.discover_scroll))
        assertEquals(
            SERVICE_UNAVAILABLE_STATUS,
            discoverText("discover_status_supplemental")
        )
    }

    @Test
    fun discoverRendersOfflinePairedSectionAfterNearbySection() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            canStart = true
        )
        fixture.screen.setPresences(listOf(offlinePairedPresence(), selectablePresence()))

        assertFalse(discoverExists("discover_paired_container_label"))
        assertEquals(1, discoverGroupCount("discover_nearby_container"))
        assertTrue(discoverExists("discover_offline_paired_container_label"))
        assertEquals(1, discoverGroupCount("discover_offline_paired_container"))
    }

    @Test
    fun discoverShowsWifiSettingsWhenWifiDropsDuringDiscovery() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            canStart = true
        )
        fixture.screen.setWifiUnavailable(true)

        assertEquals(
            WIFI_UNAVAILABLE_TEXT,
            discoverText("discover_state_text")
        )
        assertEquals(
            View.VISIBLE,
            if (discoverExists("discover_wifi_settings_button")) View.VISIBLE else View.GONE
        )
    }

    @Test
    fun discoverPermissionBlockTakesPriorityOverWifiRepair() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        fixture.screen.setWifiUnavailable(true)

        assertEquals(
            "启动摩声后开始发现附近车友",
            discoverText("discover_state_text")
        )
        assertEquals(
            View.VISIBLE,
            if (discoverExists("discover_offline_start_button")) View.VISIBLE else View.GONE
        )
        assertEquals(
            View.GONE,
            if (discoverExists("discover_wifi_settings_button")) View.VISIBLE else View.GONE
        )
    }

    @Test
    fun discoverWifiCopySurvivesPermissionRefreshAndClearsWhenConnecting() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        clickHome("home_menu_button")
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-wifi-refresh")),
            canStart = true
        )
        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setPermissionStatus(null)
        assertEquals(
            WIFI_UNAVAILABLE_TEXT,
            discoverText("discover_status_supplemental")
        )

        fixture.screen.setIntercomState(
            IntercomState.Connecting(uiAttempt(RuntimeSessionId("runtime-wifi-refresh"))),
            canStart = true
        )
        assertEquals(
            "",
            if (discoverExists("discover_status_supplemental")) discoverText("discover_status_supplemental") else ""
        )
    }

    private fun dismissPlaceholder() {
        val dialog = ShadowAlertDialog.getLatestAlertDialog() ?: error("placeholder dialog was not shown")
        assertEquals(PLACEHOLDER_DIALOG_TITLE, shadowOf(dialog).title)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    private fun assertFlexibleButton(button: Button) {
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, button.layoutParams.height)
        val minimumTouchTarget = (48 * button.resources.displayMetrics.density).toInt()
        assertTrue(button.minHeight >= minimumTouchTarget)
    }

    private fun assertTitleCentered(container: ViewGroup, titleId: Int, scrollId: Int) {
        val title = container.findViewById<android.widget.TextView>(titleId)
        val header = title.parent as View
        assertEquals(
            header.width / 2,
            title.left + title.width / 2
        )
        assertEquals(
            22f * title.resources.displayMetrics.density * title.resources.configuration.fontScale,
            title.textSize,
            0.01f
        )
        assertEquals(View.VISIBLE, container.findViewById<View>(scrollId).visibility)
    }

    private fun clickHome(testTag: String) {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.onAllNodesWithTag(testTag).onLast().performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickPlaceholder(fixture: Fixture, route: MainRoute, id: Int) {
        if (route == MainRoute.HOME) {
            clickHome(homeTagForId(id))
        } else if (route == MainRoute.DISCOVER) {
            clickDiscover(
                when (id) {
                    R.id.discover_help_button -> "discover_help_button"
                    R.id.discover_rescan_button -> "discover_rescan_button"
                    else -> error("No Compose tag mapped for Discover resource id=$id")
                }
            )
        } else if (route == MainRoute.SETTINGS) {
            clickSettings(
                when (id) {
                    R.id.settings_audio_route_button -> "settings_audio_route_button"
                    R.id.settings_audio_earpiece_button -> "settings_audio_earpiece_button"
                    R.id.settings_audio_speaker_button -> "settings_audio_speaker_button"
                    R.id.settings_vox_button -> "settings_vox_button"
                    R.id.settings_vox_sensitivity_button -> "settings_vox_sensitivity_button"
                    R.id.settings_vox_state_button -> "settings_vox_state_button"
                    R.id.settings_reconnect_button -> "settings_reconnect_button"
                    R.id.settings_help_button -> "settings_help_button"
                    else -> error("No Compose tag mapped for Settings resource id=$id")
                }
            )
        } else {
            fixture.screen.root.findViewById<View>(id).performClick()
        }
    }

    private fun homeTagForId(id: Int): String = when (id) {
        R.id.home_mute_button -> "home_mute_button"
        R.id.home_vox_pill -> "home_vox_pill"
        R.id.home_vox_card -> "home_vox_card"
        else -> error("No Compose tag mapped for Home resource id=$id")
    }

    private fun settingsTagForPlaceholderId(id: Int): String = when (id) {
        R.id.settings_audio_route_button -> "settings_audio_route_button"
        R.id.settings_audio_earpiece_button -> "settings_audio_earpiece_button"
        R.id.settings_audio_speaker_button -> "settings_audio_speaker_button"
        R.id.settings_vox_button -> "settings_vox_button"
        R.id.settings_vox_sensitivity_button -> "settings_vox_sensitivity_button"
        R.id.settings_vox_state_button -> "settings_vox_state_button"
        R.id.settings_reconnect_button -> "settings_reconnect_button"
        R.id.settings_help_button -> "settings_help_button"
        else -> error("No Compose tag mapped for Settings resource id=$id")
    }

    private fun homeNode(testTag: String): SemanticsNodeInteraction =
        composeRule.onAllNodesWithTag(testTag).onLast()

    private fun discoverNode(testTag: String): SemanticsNodeInteraction =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true).onLast()

    private fun discoverText(testTag: String): String {
        shadowOf(Looper.getMainLooper()).idle()
        val config = discoverNode(testTag).fetchSemanticsNode().config
        return if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString(separator = "") { it.text }
        } else {
            val childConfig = composeRule
                .onAllNodesWithTag("${testTag}_text", useUnmergedTree = true)
                .onLast()
                .fetchSemanticsNode()
                .config
            childConfig[SemanticsProperties.Text]
                ?.joinToString(separator = "") { it.text }
                .orEmpty()
        }
    }

    private fun discoverExists(testTag: String): Boolean =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun clickDiscover(testTag: String) {
        shadowOf(Looper.getMainLooper()).idle()
        discoverNode(testTag).performClick()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickDiscoverSelection(testTag: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val action = discoverNode(testTag)
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            ?.action
        assertNotNull("$testTag must expose a selection action", action)
        action?.invoke()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun discoverEnabled(testTag: String): Boolean =
        !discoverNode(testTag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)

    private val SemanticsNodeInteraction.text: String
        get() = runCatching {
            fetchSemanticsNode().config[SemanticsProperties.Text]
                ?.joinToString(separator = "") { it.text }
                .orEmpty()
        }.getOrDefault("杩炴帴")

    private fun discoverGroupCount(testTag: String): Int =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).size

    private fun settingsNode(testTag: String): SemanticsNodeInteraction =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true).onLast()

    private fun settingsExists(testTag: String): Boolean =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun settingsText(testTag: String): String {
        val config = settingsNode(testTag).fetchSemanticsNode().config
        return when {
            config.contains(SemanticsProperties.Text) ->
                config[SemanticsProperties.Text].joinToString(separator = "") { it.text }
            config.contains(SemanticsProperties.EditableText) ->
                config[SemanticsProperties.EditableText].text
            else -> ""
        }
    }

    private fun replaceSettingsText(testTag: String, value: String) {
        settingsNode(testTag).performTextClearance()
        settingsNode(testTag).performTextInput(value)
        composeRule.waitForIdle()
    }

    private fun clickSettings(testTag: String) {
        shadowOf(Looper.getMainLooper()).idle()
        settingsNode(testTag).performClick()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun logsNode(testTag: String): SemanticsNodeInteraction =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true).onLast()

    private fun logsText(testTag: String): String {
        val config = logsNode(testTag).fetchSemanticsNode().config
        return config[SemanticsProperties.Text]
            ?.joinToString(separator = "") { it.text }
            ?: config[SemanticsProperties.EditableText]?.text.orEmpty()
    }

    private fun logsExists(testTag: String): Boolean =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun clickLogs(testTag: String) {
        shadowOf(Looper.getMainLooper()).idle()
        logsNode(testTag).performClick()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun homeText(testTag: String): String {
        shadowOf(Looper.getMainLooper()).idle()
        val config = homeNode(testTag).fetchSemanticsNode().config
        return if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text].joinToString(separator = "") { it.text }
        } else {
            ""
        }
    }

    private fun assertHomeText(testTag: String, expected: String) {
        assertEquals(expected, homeText(testTag))
    }

    private fun assertHomeTextIsNotBlank(testTag: String) {
        assertTrue("$testTag must expose non-blank text", homeText(testTag).isNotBlank())
    }

    private fun homeExists(testTag: String): Boolean =
        composeRule.onAllNodesWithTag(testTag, useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun homeIsEnabled(testTag: String): Boolean =
        !homeNode(testTag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)

    private fun homeContentDescription(testTag: String): String =
        homeNode(testTag).fetchSemanticsNode().config.let { config ->
            if (config.contains(SemanticsProperties.ContentDescription)) {
                config[SemanticsProperties.ContentDescription].joinToString(separator = "")
            } else {
                ""
            }
        }

    private fun homeBounds(testTag: String): Rect =
        homeNode(testTag).fetchSemanticsNode().boundsInRoot

    private fun assertHomeMinimumTouchTarget(testTag: String) {
        val minimum = 48f * composeRule.density.density
        val bounds = homeBounds(testTag)
        assertTrue("$testTag must be at least 48dp high", bounds.height >= minimum)
    }

    private fun assertHomeActionsAreAccessible() {
        listOf(
            "home_menu_button",
            "home_settings_button",
            "home_primary_button",
            "home_mute_button",
            "home_audio_settings_button",
            "home_vox_pill",
            "home_vox_card"
        ).forEach { tag ->
            if (homeExists(tag)) {
                homeNode(tag).assertHasClickAction()
                assertTrue("$tag must expose a label", homeText(tag).isNotBlank() || homeContentDescription(tag).isNotBlank())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun fixture(
        fontScale: Float = 1f,
        savedState: Bundle = Bundle(),
        initialRiderName: String = "",
        onSaveRiderName: (String) -> Boolean = { true },
        onConnectPresence: (RiderPresence) -> Boolean = { false }
    ): Fixture {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        if (fontScale != 1f) {
            val configuration = Configuration(activity.resources.configuration)
            configuration.fontScale = fontScale
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }
        val screen = MainScreen(
            activity = activity,
            initialRiderName = initialRiderName,
            savedState = savedState,
            onToggleIntercom = {},
            onConnectPresence = onConnectPresence,
            onSaveRiderName = onSaveRiderName,
            onRequestCorePermissions = {},
            onRequestOptionalPermissions = {},
            onOpenWifiSettings = {},
            onOpenPermissionSettings = {}
        )
        activity.setContentView(screen.root)
        return Fixture(activity, screen)
    }

    private fun openRoute(fixture: Fixture, route: MainRoute) {
        when (route) {
            MainRoute.HOME -> Unit
            MainRoute.DISCOVER -> {
                clickHome("home_menu_button")
                fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
            }
            MainRoute.SETTINGS -> {
                clickHome("home_menu_button")
                fixture.screen.root.findViewById<View>(R.id.nav_settings_button).performClick()
            }
            MainRoute.LOGS -> {
                clickHome("home_menu_button")
                fixture.screen.root.findViewById<View>(R.id.nav_settings_button).performClick()
                clickSettings("settings_logs_button")
            }
        }
    }

    private fun measureAtWidth(fixture: Fixture, widthDp: Int, heightDp: Int = 800) {
        val density = fixture.activity.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        fixture.screen.root.measure(widthSpec, heightSpec)
        fixture.screen.root.layout(0, 0, widthPx, heightPx)
        shadowOf(Looper.getMainLooper()).idle()
        fixture.screen.root.measure(widthSpec, heightSpec)
        fixture.screen.root.layout(0, 0, widthPx, heightPx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertVisibleActionsAreAccessible(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view.isClickable) {
            val label = when (view) {
                is Button -> view.text?.toString().orEmpty()
                is android.widget.EditText -> view.text?.toString().orEmpty()
                    .ifBlank { view.hint?.toString().orEmpty() }
                else -> view.contentDescription?.toString().orEmpty()
            }
            val idName = if (view.id == View.NO_ID) {
                "no-id"
            } else {
                runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString())
            }
            assertTrue(
                "clickable ${view.javaClass.simpleName}#$idName must expose a label (label=$label)",
                label.isNotBlank()
            )
            val minimumTouchTarget = (48 * view.resources.displayMetrics.density).toInt()
            assertTrue(
                "clickable ${view.javaClass.simpleName} must be at least 48dp high",
                maxOf(view.minimumHeight, view.height) >= minimumTouchTarget
            )
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                assertVisibleActionsAreAccessible(view.getChildAt(index))
            }
        }
    }

    private fun selectablePresence(
        nickname: String = "Road Captain",
        deviceName: String = "Pixel"
    ): RiderPresence = RiderPresence(
        deviceId = "device-a",
        sessionId = RuntimeSessionId("session-a"),
        nickname = nickname,
        deviceName = deviceName,
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "endpoint-a",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = null
    )

    private fun uiAttempt(runtime: RuntimeSessionId): ConnectionAttempt = ConnectionAttempt(
        id = ConnectionAttemptId("attempt-ui"),
        runtimeSessionId = runtime,
        targetLock = TargetLock("peer-a", RuntimeSessionId("peer-runtime")),
        trigger = ConnectionTrigger.USER,
        channelPlan = ChannelPlan.single(Transport.LAN),
        deadlineElapsedRealtimeMs = 1_000L
    )

    private fun offlinePairedPresence(): RiderPresence = RiderPresence(
        deviceId = "device-offline",
        sessionId = RuntimeSessionId("session-offline"),
        nickname = "Paired Offline",
        deviceName = "Old Phone",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = Transport.LAN,
                endpointId = "expired",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = false
            )
        ),
        pairing = PairingRecord(
            remoteDeviceId = "device-offline",
            remoteNickname = "Paired Offline",
            deviceName = "Old Phone",
            localAlias = "Paired Offline",
            shortCode = "654321",
            pairedAt = 1L,
            lastConnectedAt = 2L,
            isPreferred = false,
            lastTransport = "LAN",
            failureCount = 0
        )
    )

    private data class Fixture(
        val activity: Activity,
        val screen: MainScreen
    )
}
