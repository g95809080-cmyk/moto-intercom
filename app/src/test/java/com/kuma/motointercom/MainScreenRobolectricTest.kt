package com.kuma.motointercom

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.RoundedCorner
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
class MainScreenRobolectricTest {
    @Test
    fun topLevelRoutesAndNavigationPanelStayUiOnly() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)

        assertNotNull(pageContainer.findViewById<View>(R.id.home_scroll))
        assertFalse(root.findViewById<View>(R.id.home_primary_button).isEnabled)
        assertEquals(View.GONE, root.findViewById<View>(R.id.navigation_panel).visibility)

        root.findViewById<View>(R.id.home_menu_button).performClick()
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigation_panel).visibility)
        assertFlexibleButton(root.findViewById(R.id.nav_home_button))
        assertFlexibleButton(root.findViewById(R.id.nav_discover_button))
        assertFlexibleButton(root.findViewById(R.id.nav_settings_button))
        assertEquals("当前页面", root.findViewById<Button>(R.id.nav_home_button).stateDescription)
        assertTrue(fixture.screen.handleBack())
        assertEquals(View.GONE, root.findViewById<View>(R.id.navigation_panel).visibility)

        root.findViewById<View>(R.id.home_settings_button).performClick()
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_scroll))
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_discovery_candidates))
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
                    "width=${widthDp}dp expected compact bottom navigation",
                    View.VISIBLE,
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
            assertEquals(View.VISIBLE, bottomNavigation.visibility)
            assertTrue(bottomNavigation.bottom <= root.height)
            listOf(
                R.id.bottom_nav_home_button,
                R.id.bottom_nav_discover_button,
                R.id.bottom_nav_settings_button
            ).forEach { id ->
                val button = root.findViewById<Button>(id)
                assertTrue(button.height >= (48 * button.resources.displayMetrics.density).toInt())
                assertTrue(button.bottom <= bottomNavigation.bottom)
            }
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
        val menuButton = root.findViewById<View>(R.id.home_menu_button)
        menuButton.requestFocus()

        menuButton.performClick()

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
        assertTrue(menuButton.hasFocus())
    }

    @Test
    fun homeAudioSettingsCtaOpensSettingsAudioSection() {
        val fixture = fixture()
        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)

        pageContainer.findViewById<View>(R.id.home_audio_settings_button).performClick()

        assertNotNull(pageContainer.findViewById<View>(R.id.settings_scroll))
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_audio_section))
        assertNotNull(pageContainer.findViewById<View>(R.id.settings_audio_route_button))
    }

    @Test
    fun deferredAudioFocusDoesNotTouchNewRouteAfterImmediateBack() {
        val fixture = fixture()
        val root = fixture.screen.root

        root.findViewById<View>(R.id.home_audio_settings_button).performClick()
        assertNotNull(root.findViewById<View>(R.id.settings_audio_section))
        root.findViewById<View>(R.id.settings_back_button).performClick()

        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(root.findViewById<View>(R.id.home_scroll))
        assertEquals(0, root.findViewById<ScrollView>(R.id.home_scroll).scrollY)
    }

    @Test
    fun audioSourceCallbackUsesGenericBluetoothFallbackWithoutDeviceName() {
        val fixture = fixture()
        val audioSource = fixture.screen.root.findViewById<TextView>(R.id.home_audio_source)

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 ( )", bluetooth = true)
        assertEquals(BLUETOOTH_AUDIO_CONNECTED_TEXT, audioSource.text.toString())

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 (头盔蓝牙)", bluetooth = true)
        assertEquals(BLUETOOTH_AUDIO_CONNECTED_TEXT, audioSource.text.toString())

        fixture.screen.setAudioSource("", bluetooth = false)
        assertEquals(AUDIO_SOURCE_STANDBY_TEXT, audioSource.text.toString())

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机 (Helmet)", bluetooth = false)
        assertEquals(AUDIO_SOURCE_STANDBY_TEXT, audioSource.text.toString())
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
            fixture.screen.root.findViewById<TextView>(R.id.home_audio_source).text.toString()
        )

        fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
        val settingsAudio = fixture.screen.root
            .findViewById<TextView>(R.id.settings_audio_source)
            .text
            .toString()
        assertTrue(settingsAudio.contains(BLUETOOTH_PERMISSION_UNAVAILABLE))
        assertFalse(settingsAudio.contains("Helmet"))
    }

    @Test
    fun discoverAndLogsRoutesUseDedicatedScrollHosts() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)

        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()
        assertNotNull(pageContainer.findViewById<View>(R.id.discover_scroll))

        root.findViewById<View>(R.id.discover_help_button).performClick()
        dismissPlaceholder()
        root.findViewById<View>(R.id.discover_back_button).performClick()

        root.findViewById<View>(R.id.home_settings_button).performClick()
        root.findViewById<View>(R.id.settings_logs_button).performClick()
        assertNotNull(pageContainer.findViewById<View>(R.id.logs_scroll))
        root.findViewById<View>(R.id.logs_back_button).performClick()
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
        val cards = fixture.screen.root.findViewById<LinearLayout>(R.id.discover_nearby_container)
        val firstCard = cards.getChildAt(0)
        val maxScroll = (scroll.getChildAt(0).measuredHeight - scroll.height).coerceAtLeast(0)
        val preservedOffset = minOf(120, maxScroll)
        scroll.scrollTo(0, preservedOffset)

        fixture.screen.setStatus("仅更新发现状态补充文案", appendLog = false)
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(firstCard, cards.getChildAt(0))
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
            fixture.screen.root.findViewById<TextView>(R.id.discover_state_text).text.toString()
        )
        val card = fixture.screen.root
            .findViewById<LinearLayout>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        assertTrue(
            (0 until card.childCount).none { card.getChildAt(it) is Button }
        )
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

        val pairedCard = fixture.screen.root
            .findViewById<LinearLayout>(R.id.discover_paired_container)
            .getChildAt(0) as ViewGroup
        val pairedFacts = ((pairedCard.getChildAt(0) as ViewGroup)
            .getChildAt(1) as ViewGroup)
            .getChildAt(3) as TextView
        assertEquals(
            fixture.activity.getString(R.string.discover_fact_paired) +
                fixture.activity.getString(R.string.discover_fact_separator) +
                fixture.activity.getString(R.string.discover_fact_preferred),
            pairedFacts.text.toString()
        )

        val offlineCard = fixture.screen.root
            .findViewById<LinearLayout>(R.id.discover_offline_paired_container)
            .getChildAt(0) as ViewGroup
        val offlineFacts = ((offlineCard.getChildAt(0) as ViewGroup)
            .getChildAt(1) as ViewGroup)
            .getChildAt(3) as TextView
        assertEquals(
            fixture.activity.getString(R.string.discover_fact_paired) +
                fixture.activity.getString(R.string.discover_fact_separator) +
                fixture.activity.getString(R.string.discover_fact_unavailable),
            offlineFacts.text.toString()
        )

        val nearbyCard = fixture.screen.root
            .findViewById<LinearLayout>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        val nearbyFacts = ((nearbyCard.getChildAt(0) as ViewGroup)
            .getChildAt(1) as ViewGroup)
            .getChildAt(3) as TextView
        assertEquals(
            fixture.activity.getString(R.string.discover_fact_current),
            nearbyFacts.text.toString()
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

        val firstInput = first.screen.root.findViewById<android.widget.EditText>(R.id.settings_nickname_input)
        firstInput.setText("Unsaved Draft")
        val firstScroll = first.screen.root.findViewById<ScrollView>(R.id.settings_scroll)
        val maxScroll = firstScroll.getChildAt(0).measuredHeight - firstScroll.height
        assertTrue("settings content must be scrollable before saving state", maxScroll > 0)
        val savedScrollY = minOf(120, maxScroll)
        firstScroll.scrollTo(0, savedScrollY)
        val discoveringSummary = first.screen.root
            .findViewById<android.widget.TextView>(R.id.settings_product_state)
            .text
            .toString()

        val savedState = Bundle()
        first.screen.saveState(savedState)

        val recreated = fixture(
            savedState = savedState,
            initialRiderName = "Persisted Rider"
        )
        measureAtWidth(recreated, widthDp = 360)
        shadowOf(Looper.getMainLooper()).idle()

        val recreatedInput = recreated.screen.root
            .findViewById<android.widget.EditText>(R.id.settings_nickname_input)
        val recreatedScroll = recreated.screen.root.findViewById<ScrollView>(R.id.settings_scroll)
        val recreatedSummary = recreated.screen.root
            .findViewById<android.widget.TextView>(R.id.settings_product_state)
            .text
            .toString()

        assertEquals("Unsaved Draft", recreatedInput.text.toString())
        assertEquals(savedScrollY, recreatedScroll.scrollY)
        assertTrue("product state must come from the new runtime, not saved UI state", recreatedSummary != discoveringSummary)

        recreated.screen.root.findViewById<View>(R.id.settings_back_button).performClick()
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

        val card = first.screen.root
            .findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0)
        card.performClick()
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
        assertEquals(View.VISIBLE, recreated.screen.root.findViewById<View>(R.id.bottom_navigation).visibility)

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
        first.screen.root.findViewById<android.widget.EditText>(R.id.settings_nickname_input)
            .setText("Unsaved Draft")
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

        root.findViewById<View>(R.id.home_settings_button).performClick()
        root.findViewById<View>(R.id.settings_back_button).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val restoredHomeScroll = pageContainer.findViewById<ScrollView>(R.id.home_scroll)
        assertEquals(140, restoredHomeScroll.scrollY)
    }

    @Test
    fun longLabelControlsCanGrowWhileKeepingMinimumTouchTargets() {
        val fixture = fixture()
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.home_permission_settings_cta))
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.home_discover_cta))

        fixture.screen.root.findViewById<View>(R.id.home_menu_button).performClick()
        fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.discover_wifi_settings_button))
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.discover_rescan_button))

        fixture.screen.root.findViewById<View>(R.id.discover_back_button).performClick()
        fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.settings_save_nickname_button))
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.settings_audio_route_button))
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.settings_about_button))

        fixture.screen.root.findViewById<View>(R.id.settings_logs_button).performClick()
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.logs_copy_button))
        assertFlexibleButton(fixture.screen.root.findViewById(R.id.logs_close_button))
    }

    @Test
    fun nicknameInputCanGrowWhileKeepingMinimumTouchTarget() {
        val fixture = fixture()
        fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()

        val input = fixture.screen.root.findViewById<android.widget.EditText>(R.id.settings_nickname_input)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, input.layoutParams.height)
        val minimumTouchTarget = (48 * input.resources.displayMetrics.density).toInt()
        assertTrue(input.minimumHeight >= minimumTouchTarget)
    }

    @Test
    fun aboutDialogUsesTheRealVersionAndHasNoInventedServiceClaims() {
        val fixture = fixture()
        fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
        fixture.screen.root.findViewById<View>(R.id.settings_about_button).performClick()

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

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val parent = pageContainer.findViewById<View>(R.id.home_main_control_section)
        val row = pageContainer.findViewById<View>(R.id.home_main_control_row)

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, row.layoutParams.width)
        assertEquals(parent.width / 2, row.left + row.width / 2)
    }

    @Test
    fun homeCoreControlRemainsReachableInStandardFirstViewport() {
        val fixture = fixture()
        measureAtWidth(fixture, widthDp = 360, heightDp = 800)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = fixture.screen.root.findViewById<View>(R.id.home_main_control_row)

        assertTrue("Home core control should be reachable on a standard phone", row.bottom <= scroll.height)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-420dpi")
    fun homeCoreControlFitsTheRealisticPhoneViewportAtDesignDensity() {
        val fixture = fixture()
        measureAtWidth(fixture, widthDp = 360, heightDp = 800)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = fixture.screen.root.findViewById<View>(R.id.home_main_control_row)

        assertTrue(
            "Home core control should fit inside the first 800dp viewport at 420dpi",
            row.bottom <= scroll.height
        )
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-420dpi")
    fun homeCoreControlFitsTheStandardCompactViewport() {
        val fixture = fixture()
        fixture.screen.onWindowSizeChanged(widthDp = 360, heightDp = 640)
        measureAtWidth(fixture, widthDp = 360, heightDp = 640)

        val scroll = fixture.screen.root.findViewById<ScrollView>(R.id.home_scroll)
        val row = fixture.screen.root.findViewById<View>(R.id.home_main_control_row)
        val scrollLocation = IntArray(2)
        val rowLocation = IntArray(2)
        scroll.getLocationOnScreen(scrollLocation)
        row.getLocationOnScreen(rowLocation)
        val scrollRect = android.graphics.Rect(
            scrollLocation[0],
            scrollLocation[1],
            scrollLocation[0] + scroll.width,
            scrollLocation[1] + scroll.height
        )
        val rowRect = android.graphics.Rect(
            rowLocation[0],
            rowLocation[1],
            rowLocation[0] + row.width,
            rowLocation[1] + row.height
        )
        val bottomClearance = (48 * scroll.resources.displayMetrics.density).toInt()
        assertTrue(
            "Home core control should keep a 48dp bottom clearance in the standard viewport " +
                "(row=$rowRect, scroll=$scrollRect)",
            rowRect.bottom + bottomClearance <= scrollRect.bottom
        )
    }

    @Test
    fun discoverAndSettingsHeadersKeepTitlesCentered() {
        val fixture = fixture()
        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)

        openRoute(fixture, MainRoute.DISCOVER)
        measureAtWidth(fixture, widthDp = 360)
        assertTitleCentered(pageContainer, R.id.discover_title, R.id.discover_scroll)

        pageContainer.findViewById<View>(R.id.discover_back_button).performClick()
        pageContainer.findViewById<View>(R.id.home_settings_button).performClick()
        measureAtWidth(fixture, widthDp = 360)
        assertTitleCentered(pageContainer, R.id.settings_title, R.id.settings_scroll)
    }

    @Test
    fun logsPlaceCopyActionAfterTheReadableLogRegion() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val logText = pageContainer.findViewById<View>(R.id.logs_text)
        val copyButton = pageContainer.findViewById<View>(R.id.logs_copy_button)
        val closeButton = pageContainer.findViewById<View>(R.id.logs_close_button)

        assertTrue(logText.bottom <= copyButton.top)
        assertTrue(copyButton.bottom <= closeButton.top)
        assertEquals(Typeface.MONOSPACE, pageContainer.findViewById<TextView>(R.id.logs_text).typeface)
    }

    @Test
    fun logsUseAnIndependentBoundedVerticalScrollRegion() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val logText = pageContainer.findViewById<TextView>(R.id.logs_text)

        assertEquals(
            fixture.activity.resources.getDimensionPixelSize(R.dimen.motocom_logs_viewport_height),
            logText.height
        )
        assertTrue(logText.isVerticalScrollBarEnabled)
        assertEquals(Typeface.MONOSPACE, logText.typeface)
        assertTrue(logText.movementMethod is ScrollingMovementMethod)
    }

    @Test
    fun logsAppendFollowsInnerViewportOnlyWhenAlreadyAtBottom() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        val logText = fixture.screen.root.findViewById<TextView>(R.id.logs_text)
        assertTrue("long logs should overflow the bounded viewport", logText.layout.height > logText.height)
        assertTrue("a visible log append should follow the bottom", logText.scrollY > 0)

        logText.scrollTo(0, 0)
        fixture.screen.appendLog("new-log-while-reading-history")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("history browsing must not be forced back to the bottom", 0, logText.scrollY)
    }

    @Test
    fun logsAppendPreservesAnIntermediateHistoryOffset() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        val logText = fixture.screen.root.findViewById<TextView>(R.id.logs_text)
        val maximumScrollY = (
            (logText.layout?.height ?: 0) + logText.paddingTop + logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
        val middleOffset = (maximumScrollY / 2).coerceAtLeast(1)
        logText.scrollTo(0, middleOffset)
        assertEquals(middleOffset, logText.scrollY)

        fixture.screen.appendLog("new-log-while-reading-middle-history")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "history browsing must preserve its current offset",
            middleOffset,
            logText.scrollY
        )
    }

    @Test
    fun nonLogServiceRefreshDoesNotStealLogHistoryOffset() {
        val fixture = fixture()
        openRoute(fixture, MainRoute.LOGS)
        measureAtWidth(fixture, widthDp = 360)

        repeat(120) { index -> fixture.screen.appendLog("log-$index") }
        shadowOf(Looper.getMainLooper()).idle()

        val logText = fixture.screen.root.findViewById<TextView>(R.id.logs_text)
        val maximumScrollY = (
            (logText.layout?.height ?: 0) + logText.paddingTop + logText.paddingBottom - logText.height
        ).coerceAtLeast(0)
        val middleOffset = (maximumScrollY / 2).coerceAtLeast(1)
        logText.scrollTo(0, middleOffset)
        assertEquals(middleOffset, logText.scrollY)
        val displayedTextBeforeRefresh = logText.text

        fixture.screen.setAudioSource("当前音频源：蓝牙耳机", bluetooth = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "non-log Service refreshes must not redraw the visible Logs viewport",
            middleOffset,
            logText.scrollY
        )
        assertSame(
            "non-log Service refreshes must not replace the visible Logs snapshot",
            displayedTextBeforeRefresh,
            logText.text
        )
    }

    @Test
    fun logsCopyUsesCurrentSessionOrderAndEnablesOnlyWithLogs() {
        val fixture = fixture()
        fixture.screen.setStatus("第一条")
        fixture.screen.setStatus("第二条")
        openRoute(fixture, MainRoute.LOGS)

        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val copyButton = pageContainer.findViewById<Button>(R.id.logs_copy_button)
        assertTrue(copyButton.isEnabled)
        assertEquals(
            "第一条\n第二条",
            pageContainer.findViewById<android.widget.TextView>(R.id.logs_text).text.toString()
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
            fixture.screen.root.findViewById<Button>(R.id.logs_copy_button).isEnabled
        )
    }

    @Test
    fun nicknameSaveUiTrimsOnSuccessAndPreservesEditingOnFailure() {
        var savedName: String? = null
        val success = fixture(onSaveRiderName = { value -> savedName = value; true })
        openRoute(success, MainRoute.SETTINGS)
        val successInput = success.screen.root.findViewById<android.widget.EditText>(R.id.settings_nickname_input)
        assertEquals(
            success.activity.getString(R.string.edit_text_hint),
            successInput.hint.toString()
        )
        successInput.setText("  Road Captain  ")
        success.screen.root.findViewById<Button>(R.id.settings_save_nickname_button).performClick()
        assertEquals("Road Captain", savedName)
        assertEquals("Road Captain", successInput.text.toString())
        assertEquals(
            NICKNAME_SAVED_FEEDBACK,
            success.screen.root.findViewById<android.widget.TextView>(R.id.settings_nickname_feedback)
                .text
                .toString()
        )

        val failure = fixture(onSaveRiderName = { false })
        openRoute(failure, MainRoute.SETTINGS)
        val failureInput = failure.screen.root.findViewById<android.widget.EditText>(R.id.settings_nickname_input)
        failureInput.setText("  Keep This Draft  ")
        failure.screen.root.findViewById<Button>(R.id.settings_save_nickname_button).performClick()
        assertEquals("  Keep This Draft  ", failureInput.text.toString())
        assertEquals(
            NICKNAME_SAVE_FAILED_FEEDBACK,
            failure.screen.root.findViewById<android.widget.TextView>(R.id.settings_nickname_feedback)
                .text
                .toString()
        )
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
        fixture.screen.root.findViewById<View>(R.id.home_menu_button).performClick()
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

            assertTrue(
                "${state.kind} must render a primary status title",
                fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_status_title)
                    .text
                    .isNotBlank()
            )
            assertTrue(
                "${state.kind} must render a primary action label",
                fixture.screen.root.findViewById<Button>(R.id.home_primary_button)
                    .text
                    .isNotBlank()
            )
            assertEquals(
                "${state.kind} must keep Home route active",
                View.VISIBLE,
                fixture.screen.root.findViewById<View>(R.id.home_scroll).visibility
            )
            assertEquals(
                "${state.kind} Discover CTA visibility",
                if (state is IntercomState.Discovering) View.VISIBLE else View.GONE,
                fixture.screen.root.findViewById<View>(R.id.home_discover_cta).visibility
            )
            assertEquals(
                "${state.kind} Stopping enabled state",
                state !is IntercomState.Stopping,
                fixture.screen.root.findViewById<Button>(R.id.home_primary_button).isEnabled
            )
            assertEquals(
                "VOX：状态接口待接入",
                fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_vox_pill)
                    .text
                    .toString()
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
        }

        val panelFixture = fixture()
        panelFixture.screen.root.findViewById<View>(R.id.home_menu_button).performClick()
        measureAtWidth(panelFixture, widthDp = 360)
        assertVisibleActionsAreAccessible(panelFixture.screen.root)
    }

    @Test
    fun placeholderEntriesExposeStaticIconsWithoutChangingTheirTextContract() {
        val fixture = fixture()
        val homeMute = fixture.screen.root.findViewById<android.widget.ImageButton>(R.id.home_mute_button)
        assertNotNull("Home mute placeholder should expose a static icon", homeMute.drawable)
        assertTrue(homeMute.contentDescription.toString().contains("开发中"))

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
            val button = fixture.screen.root.findViewById<Button>(id)
            assertNotNull("placeholder $id should expose a static icon", button.compoundDrawablesRelative[0])
            assertTrue(button.text.toString().contains("开发中"))
        }

        fixture.screen.root.findViewById<View>(R.id.settings_back_button).performClick()
        fixture.screen.root.findViewById<View>(R.id.home_menu_button).performClick()
        fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
        val discoverHelp = fixture.screen.root.findViewById<android.widget.ImageButton>(R.id.discover_help_button)
        assertNotNull("Discover help placeholder should expose a static icon", discoverHelp.drawable)
        assertTrue(discoverHelp.contentDescription.toString().contains("开发中"))
        val rescan = fixture.screen.root.findViewById<Button>(R.id.discover_rescan_button)
        assertNotNull(rescan.compoundDrawablesRelative[0])
        assertTrue(rescan.text.toString().contains("开发中"))

        fixture.screen.root.findViewById<View>(R.id.discover_back_button).performClick()
        val voxPill = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_vox_pill)
        assertNotNull(voxPill.compoundDrawablesRelative[0])
        val voxCardTitle = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_vox_title)
        assertNotNull(voxCardTitle.compoundDrawablesRelative[0])
    }

    @Test
    fun voxVisualStateChipsRemainDecorativeForAccessibility() {
        val fixture = fixture()
        val pageContainer = fixture.screen.root.findViewById<FrameLayout>(R.id.page_container)
        val stateRow = pageContainer.findViewById<View>(R.id.home_vox_state_row)

        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, stateRow.importantForAccessibility)
        assertEquals(3, (stateRow as ViewGroup).childCount)
    }

    @Test
    fun placeholderButtonShowsOneDialogAndPositiveActionDismissesIt() {
        val fixture = fixture()
        fixture.screen.root.findViewById<View>(R.id.home_mute_button).performClick()

        val dialog = ShadowAlertDialog.getLatestAlertDialog() ?: error("placeholder dialog was not shown")
        val shadow = shadowOf(dialog)
        assertEquals(PLACEHOLDER_DIALOG_TITLE, shadow.title)
        assertEquals(PLACEHOLDER_DIALOG_MESSAGE, shadow.message)
        assertEquals(PLACEHOLDER_DIALOG_BUTTON, dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)

        fixture.screen.root.findViewById<View>(R.id.home_mute_button).performClick()
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
    }

    @Test
    fun homeVoxFactPillOpensThePlaceholderDialog() {
        val fixture = fixture()

        fixture.screen.root.findViewById<View>(R.id.home_vox_pill).performClick()

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

            pageContainer.findViewById<View>(placeholderIds.first()).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
                ?: error("placeholder dialog was not shown for $route")
            assertTrue(dialog.isShowing)

            placeholderIds.drop(1).forEach { id ->
                pageContainer.findViewById<View>(id).performClick()
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
                fixture.screen.root.findViewById<View>(id).performClick()

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
        val supplemental = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_status_supplemental)

        fixture.screen.setPermissionStatus("缺少必要权限，请先授权")
        assertEquals("缺少必要权限，请先授权", supplemental.text.toString())

        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        assertEquals("请点击下方启动对讲", supplemental.text.toString())

        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        assertEquals(SERVICE_UNAVAILABLE_STATUS, supplemental.text.toString())
    }

    @Test
    fun permissionRefreshClearsOfflineDisabledReasonWhenStartBecomesAvailable() {
        val fixture = fixture()
        val primary = fixture.screen.root.findViewById<Button>(R.id.home_primary_button)
        val disabledReason = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_disabled_reason)

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        assertFalse(primary.isEnabled)
        assertEquals("缺少必要权限", disabledReason.text.toString())
        assertEquals(View.VISIBLE, disabledReason.visibility)

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        assertTrue(primary.isEnabled)
        assertEquals("", disabledReason.text.toString())
        assertEquals(View.GONE, disabledReason.visibility)
    }

    @Test
    fun discoveringClearsOfflinePermissionCopyButKeepsServiceCopy() {
        val fixture = fixture()
        val supplemental = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_status_supplemental)
        val runtime = RuntimeSessionId("runtime-permission-transition")

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("请点击下方启动对讲")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        assertEquals("", supplemental.text.toString())

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        assertEquals(SERVICE_UNAVAILABLE_STATUS, supplemental.text.toString())
    }

    @Test
    fun wifiStatusRefreshClearsOnlyItsOwnSupplementalCopy() {
        val fixture = fixture()
        val supplemental = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_status_supplemental)

        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setStatus(WIFI_UNAVAILABLE_TEXT)
        assertEquals(WIFI_UNAVAILABLE_TEXT, supplemental.text.toString())

        fixture.screen.setWifiUnavailable(false)
        assertEquals("", supplemental.text.toString())

        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setStatus(SERVICE_UNAVAILABLE_STATUS)
        fixture.screen.setWifiUnavailable(false)
        assertEquals(SERVICE_UNAVAILABLE_STATUS, supplemental.text.toString())
    }

    @Test
    fun wifiRemediationYieldsToMissingPermissionAndReturnsWhenReady() {
        val fixture = fixture()
        val supplemental = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_status_supplemental)

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("ready")
        fixture.screen.setWifiUnavailable(true)
        assertEquals(WIFI_UNAVAILABLE_TEXT, supplemental.text.toString())

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        fixture.screen.setPermissionStatus("permission required")
        assertEquals("permission required", supplemental.text.toString())

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = true)
        fixture.screen.setPermissionStatus("ready")
        fixture.screen.setWifiUnavailable(true)
        assertEquals(WIFI_UNAVAILABLE_TEXT, supplemental.text.toString())
    }

    @Test
    fun settingsCandidateSummaryRefreshesWhenPresenceSnapshotChanges() {
        val fixture = fixture()
        fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
        val summary = fixture.screen.root.findViewById<android.widget.TextView>(R.id.settings_discovery_candidates)

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
        assertTrue(summary.text.toString().contains("Road Captain：LAN"))

        fixture.screen.setPresences(emptyList())
        assertTrue(summary.text.toString().contains("当前没有可用发现候选"))
    }

    @Test
    fun discoveryResetsTheLastPeerNameBeforeALaterStop() {
        val fixture = fixture()
        val peerName = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_peer_name)
        val runtime = RuntimeSessionId("runtime-a")

        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = true)
        fixture.screen.setRemoteRider("Road Captain")
        assertEquals("Road Captain", peerName.text.toString())

        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = false)
        assertEquals("正在结束对讲", peerName.text.toString())
    }

    @Test
    fun offlineErrorDoesNotPoisonTheNextDiscoveryCta() {
        val fixture = fixture()
        val cta = fixture.screen.root.findViewById<android.widget.Button>(R.id.home_discover_cta)
        val runtime = RuntimeSessionId("runtime-a")

        fixture.screen.setIntercomError("旧服务错误")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)

        assertEquals(HOME_DISCOVER_CTA, cta.text.toString())
    }

    @Test
    fun serviceErrorClearsPendingPresenceSelectionBeforeTheNextSnapshot() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-a")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))
        var card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        (card.getChildAt(card.childCount - 1) as Button).performClick()

        fixture.screen.setIntercomError("服务拒绝了连接")
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)

        card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        val connectButton = card.getChildAt(card.childCount - 1) as Button
        assertTrue(connectButton.isEnabled)
        assertEquals("连接", connectButton.text.toString())
    }

    @Test
    fun expiredPresenceReleasesThePendingConnectLock() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-a")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))

        var card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        var connectButton = card.getChildAt(card.childCount - 1) as Button
        connectButton.performClick()
        card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        connectButton = card.getChildAt(card.childCount - 1) as Button
        assertFalse(connectButton.isEnabled)

        fixture.screen.setPresences(emptyList())
        fixture.screen.setPresences(listOf(presence))

        card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        connectButton = card.getChildAt(card.childCount - 1) as Button
        assertTrue(connectButton.isEnabled)
        assertEquals("连接", connectButton.text.toString())
    }

    @Test
    fun serviceFactClearReleasesThePendingConnectLock() {
        val fixture = fixture(onConnectPresence = { true })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        val runtime = RuntimeSessionId("runtime-service-clear")
        val presence = selectablePresence()
        fixture.screen.setIntercomState(IntercomState.Discovering(runtime), canStart = true)
        fixture.screen.setPresences(listOf(presence))

        var card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        var connectButton = card.getChildAt(card.childCount - 1) as Button
        connectButton.performClick()
        card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        connectButton = card.getChildAt(card.childCount - 1) as Button
        assertFalse(connectButton.isEnabled)

        fixture.screen.clearServiceOwnedFacts()
        fixture.screen.setPresences(listOf(presence))

        card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        connectButton = card.getChildAt(card.childCount - 1) as Button
        assertTrue(connectButton.isEnabled)
        assertEquals("连接", connectButton.text.toString())
    }

    @Test
    fun serviceFactClearDropsTheLastPeerNameBeforeOfflineStateRefresh() {
        val fixture = fixture()
        val peerName = fixture.screen.root.findViewById<android.widget.TextView>(R.id.home_peer_name)
        val runtime = RuntimeSessionId("runtime-service-facts")

        fixture.screen.setIntercomState(IntercomState.Stopping(runtime), canStart = true)
        fixture.screen.setRemoteRider("Road Captain")
        assertEquals("Road Captain", peerName.text.toString())

        fixture.screen.clearServiceOwnedFacts()

        assertEquals("正在结束对讲", peerName.text.toString())
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
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-stale-card")),
            canStart = true
        )
        val presence = selectablePresence()
        fixture.screen.setPresences(listOf(presence))
        val staleCard = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        val staleConnectButton = staleCard.getChildAt(staleCard.childCount - 1) as Button

        fixture.screen.setPresences(emptyList())
        staleConnectButton.performClick()

        assertEquals(0, dispatchCount)
        assertEquals(
            0,
            pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container).childCount
        )
    }

    @Test
    fun failedPresenceDispatchKeepsDiscoverRouteAndShowsServiceUnavailable() {
        val fixture = fixture(onConnectPresence = { false })
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()
        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-dispatch-failure")),
            canStart = true
        )
        fixture.screen.setPresences(listOf(selectablePresence()))

        val card = pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container)
            .getChildAt(0) as ViewGroup
        (card.getChildAt(card.childCount - 1) as Button).performClick()

        assertNotNull(pageContainer.findViewById<View>(R.id.discover_scroll))
        assertEquals(
            SERVICE_UNAVAILABLE_STATUS,
            pageContainer.findViewById<android.widget.TextView>(R.id.discover_status_supplemental)
                .text
                .toString()
        )
    }

    @Test
    fun discoverRendersOfflinePairedSectionAfterNearbySection() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            canStart = true
        )
        fixture.screen.setPresences(listOf(offlinePairedPresence(), selectablePresence()))

        assertEquals(
            View.GONE,
            pageContainer.findViewById<View>(R.id.discover_paired_label).visibility
        )
        assertEquals(
            1,
            pageContainer.findViewById<ViewGroup>(R.id.discover_nearby_container).childCount
        )
        assertEquals(
            View.VISIBLE,
            pageContainer.findViewById<View>(R.id.discover_offline_paired_label).visibility
        )
        assertEquals(
            1,
            pageContainer.findViewById<ViewGroup>(R.id.discover_offline_paired_container).childCount
        )
    }

    @Test
    fun discoverShowsWifiSettingsWhenWifiDropsDuringDiscovery() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            canStart = true
        )
        fixture.screen.setWifiUnavailable(true)

        assertEquals(
            WIFI_UNAVAILABLE_TEXT,
            pageContainer.findViewById<android.widget.TextView>(R.id.discover_state_text).text
                .toString()
        )
        assertEquals(
            View.VISIBLE,
            pageContainer.findViewById<View>(R.id.discover_wifi_settings_button).visibility
        )
    }

    @Test
    fun discoverPermissionBlockTakesPriorityOverWifiRepair() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(IntercomState.Offline, canStart = false)
        fixture.screen.setWifiUnavailable(true)

        assertEquals(
            "启动摩声后开始发现附近车友",
            pageContainer.findViewById<android.widget.TextView>(R.id.discover_state_text).text
                .toString()
        )
        assertEquals(
            View.VISIBLE,
            pageContainer.findViewById<View>(R.id.discover_offline_start_button).visibility
        )
        assertEquals(
            View.GONE,
            pageContainer.findViewById<View>(R.id.discover_wifi_settings_button).visibility
        )
    }

    @Test
    fun discoverWifiCopySurvivesPermissionRefreshAndClearsWhenConnecting() {
        val fixture = fixture()
        val root = fixture.screen.root
        val pageContainer = root.findViewById<FrameLayout>(R.id.page_container)
        root.findViewById<View>(R.id.home_menu_button).performClick()
        root.findViewById<View>(R.id.nav_discover_button).performClick()

        fixture.screen.setIntercomState(
            IntercomState.Discovering(RuntimeSessionId("runtime-wifi-refresh")),
            canStart = true
        )
        fixture.screen.setWifiUnavailable(true)
        fixture.screen.setPermissionStatus(null)
        assertEquals(
            WIFI_UNAVAILABLE_TEXT,
            pageContainer.findViewById<android.widget.TextView>(R.id.discover_status_supplemental)
                .text
                .toString()
        )

        fixture.screen.setIntercomState(
            IntercomState.Connecting(uiAttempt(RuntimeSessionId("runtime-wifi-refresh"))),
            canStart = true
        )
        assertEquals(
            "",
            pageContainer.findViewById<android.widget.TextView>(R.id.discover_status_supplemental)
                .text
                .toString()
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

    @Suppress("DEPRECATION")
    private fun fixture(
        fontScale: Float = 1f,
        savedState: Bundle = Bundle(),
        initialRiderName: String = "",
        onSaveRiderName: (String) -> Boolean = { true },
        onConnectPresence: (RiderPresence) -> Boolean = { false }
    ): Fixture {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
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
                fixture.screen.root.findViewById<View>(R.id.home_menu_button).performClick()
                fixture.screen.root.findViewById<View>(R.id.nav_discover_button).performClick()
            }
            MainRoute.SETTINGS -> {
                fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
            }
            MainRoute.LOGS -> {
                fixture.screen.root.findViewById<View>(R.id.home_settings_button).performClick()
                fixture.screen.root.findViewById<View>(R.id.settings_logs_button).performClick()
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
