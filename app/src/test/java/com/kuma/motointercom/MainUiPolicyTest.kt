package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiPolicyTest {
    @Test
    fun startPreconditionPrioritizesCorePermissionBeforeWifiRepair() {
        assertEquals(
            StartPrecondition.MISSING_CORE_PERMISSION,
            startPrecondition(canStart = false, wifiAvailable = false)
        )
        assertEquals(
            StartPrecondition.WIFI_UNAVAILABLE,
            startPrecondition(canStart = true, wifiAvailable = false)
        )
        assertEquals(
            StartPrecondition.READY,
            startPrecondition(canStart = true, wifiAvailable = true)
        )
    }

    @Test
    fun firstToggleIsAcceptedAndOnlyRapidRepeatsAreIgnored() {
        assertFalse(shouldIgnoreToggle(nowElapsed = 0L, lastToggleElapsed = null))
        assertTrue(shouldIgnoreToggle(nowElapsed = 1_000L, lastToggleElapsed = 0L))
        assertFalse(shouldIgnoreToggle(nowElapsed = TOGGLE_DEBOUNCE_MS, lastToggleElapsed = 0L))
        assertFalse(
            shouldIgnoreToggle(
                nowElapsed = TOGGLE_DEBOUNCE_MS,
                lastToggleElapsed = -TOGGLE_DEBOUNCE_MS
            )
            )
    }

    @Test
    fun failedStartDoesNotArmToggleDebounceButRuntimeActionsDo() {
        assertFalse(shouldRecordToggle(PrimaryIntercomAction.START, startAccepted = false))
        assertTrue(shouldRecordToggle(PrimaryIntercomAction.START, startAccepted = true))
        assertTrue(shouldRecordToggle(PrimaryIntercomAction.DISCONNECT_CURRENT, startAccepted = false))
        assertTrue(shouldRecordToggle(PrimaryIntercomAction.STOP_RUNTIME, startAccepted = false))
        assertFalse(shouldRecordToggle(PrimaryIntercomAction.NONE, startAccepted = true))
    }

    @Test
    fun backNavigationRespectsNaturalPrecedence() {
        assertEquals(MainRoute.HOME, restoreMainRoute(null))
        assertEquals(MainRoute.HOME, restoreMainRoute("UNKNOWN"))
        assertEquals(MainRoute.SETTINGS, restoreMainRoute("SETTINGS"))
        assertEquals(
            BackNavigation.CloseNavigation,
            resolveBackNavigation(RouteChrome(MainRoute.LOGS, navigationOpen = true))
        )
        assertEquals(
            BackNavigation.IgnoreIncomingConfirmation,
            resolveBackNavigation(
                RouteChrome(
                    MainRoute.LOGS,
                    navigationOpen = true,
                    placeholderVisible = true,
                    incomingConfirmationVisible = true
                )
            )
        )
        assertEquals(
            BackNavigation.IgnoreIncomingConfirmation,
            resolveBackNavigation(RouteChrome(MainRoute.LOGS, incomingConfirmationVisible = true))
        )
        assertEquals(
            BackNavigation.DismissPlaceholder,
            resolveBackNavigation(RouteChrome(MainRoute.LOGS, placeholderVisible = true))
        )
        assertEquals(
            BackNavigation.NavigateTo(MainRoute.SETTINGS),
            resolveBackNavigation(RouteChrome(MainRoute.LOGS))
        )
        assertEquals(
            BackNavigation.NavigateTo(MainRoute.HOME),
            resolveBackNavigation(RouteChrome(MainRoute.DISCOVER))
        )
        assertEquals(
            BackNavigation.NavigateTo(MainRoute.HOME),
            resolveBackNavigation(RouteChrome(MainRoute.SETTINGS))
        )
        assertEquals(
            BackNavigation.SystemDefault,
            resolveBackNavigation(RouteChrome(MainRoute.HOME))
        )
    }

    @Test
    fun restorationPolicyKeepsDraftAndPerRouteScrollPositions() {
        assertEquals("Saved Rider", restoreNicknameDraft(null, "Saved Rider"))
        assertEquals("Unsaved Draft", restoreNicknameDraft("Unsaved Draft", "Saved Rider"))

        val positions = mutableMapOf(
            MainRoute.HOME to 20,
            MainRoute.DISCOVER to 40
        )

        saveRouteScrollPosition(positions, MainRoute.SETTINGS, 720)
        saveRouteScrollPosition(positions, MainRoute.LOGS, -10)

        assertEquals(20, restoredRouteScrollPosition(positions, MainRoute.HOME))
        assertEquals(40, restoredRouteScrollPosition(positions, MainRoute.DISCOVER))
        assertEquals(720, restoredRouteScrollPosition(positions, MainRoute.SETTINGS))
        assertEquals(0, restoredRouteScrollPosition(positions, MainRoute.LOGS))
        assertEquals(0, restoredRouteScrollPosition(emptyMap(), MainRoute.SETTINGS))
    }

    @Test
    fun responsiveWidthPolicyNeverExceedsSafeAvailableSpace() {
        assertEquals(320, constrainedContentWidth(parentWidth = 320, horizontalPadding = 40, maxWidth = 520))
        assertEquals(272, constrainedContentWidth(parentWidth = 272, horizontalPadding = 40, maxWidth = 520))
        assertEquals(584, constrainedContentWidth(parentWidth = 720, horizontalPadding = 64, maxWidth = 520))
        assertEquals(24, constrainedContentWidth(parentWidth = 24, horizontalPadding = 40, maxWidth = 520))

        assertEquals(292, constrainedPanelWidth(availableWidth = 320, preferredWidth = 292))
        assertEquals(272, constrainedPanelWidth(availableWidth = 272, preferredWidth = 292))
        assertEquals(0, constrainedPanelWidth(availableWidth = -1, preferredWidth = 292))
    }

    @Test
    fun discoverConnectWaitsForAcceptedProductStateBeforeNavigation() {
        val runtime = RuntimeSessionId("runtime-a")
        val attempt = attempt(runtime)
        val peer = PeerIdentity(
            deviceId = "peer-device",
            nickname = "Peer",
            deviceName = "Peer Phone",
            runtimeSessionId = RuntimeSessionId("peer-runtime"),
            isDeviceIdVerified = true
        )
        assertFalse(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Discovering(runtime)))
        assertTrue(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Connecting(attempt)))
        assertTrue(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Optimizing(attempt)))
        assertTrue(
            shouldNavigateHomeAfterDiscoverConnect(
                IntercomState.Connected(attempt, peer, connectedAt = 1L, transport = Transport.LAN)
            )
        )
        assertTrue(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Recovering(attempt, peer)))
        assertFalse(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Stopping(runtime)))
        assertFalse(
            shouldNavigateHomeAfterDiscoverConnect(
                IntercomState.Resetting(
                    runtimeSessionId = runtime,
                    targetDeviceId = "device-1",
                    failedAttemptId = ConnectionAttemptId("attempt-failed"),
                    consecutiveFinalFailures = RECOVERY_RESET_FAILURE_THRESHOLD
                )
            )
        )
        assertFalse(shouldNavigateHomeAfterDiscoverConnect(IntercomState.Offline))
        assertTrue(shouldKeepDiscoverConnectPending(IntercomState.Discovering(runtime)))
        assertFalse(shouldKeepDiscoverConnectPending(IntercomState.Stopping(runtime)))
        assertNull(feedbackAfterDiscoverConnect(dispatched = true))
        assertEquals(SERVICE_UNAVAILABLE_STATUS, feedbackAfterDiscoverConnect(dispatched = false))
    }

    @Test
    fun incomingConfirmationCancellationRequiresCurrentNonce() {
        assertTrue(shouldDismissIncomingConfirmation("nonce-new", "nonce-new"))
        assertFalse(shouldDismissIncomingConfirmation("nonce-new", "nonce-old"))
        assertFalse(shouldDismissIncomingConfirmation(null, "nonce-old"))
    }

    @Test
    fun placeholderAndHiddenLogPoliciesAvoidDuplicateOrHiddenUiWork() {
        assertTrue(shouldShowPlaceholderDialog(alreadyShowing = false))
        assertFalse(shouldShowPlaceholderDialog(alreadyShowing = true))
        assertTrue(shouldRenderLogAppend(MainRoute.LOGS))
        assertFalse(shouldRenderLogAppend(MainRoute.HOME))
        assertFalse(shouldRenderLogAppend(MainRoute.DISCOVER))
        assertFalse(shouldRenderLogAppend(MainRoute.SETTINGS))
    }

    @Test
    fun nicknameSaveFeedbackSeparatesFailureOfflineAndNextStartSemantics() {
        assertEquals(
            NICKNAME_SAVE_FAILED_FEEDBACK,
            nicknameSaveFeedback(saved = false, state = IntercomState.Offline)
        )
        assertEquals(
            NICKNAME_SAVED_FEEDBACK,
            nicknameSaveFeedback(saved = true, state = IntercomState.Offline)
        )
        assertEquals(
            NICKNAME_NEXT_START_FEEDBACK,
            nicknameSaveFeedback(
                saved = true,
                state = IntercomState.Discovering(RuntimeSessionId("runtime-a"))
            )
        )
    }

    @Test
    fun versionPresentationNeverInventsUnavailablePackageVersion() {
        assertEquals("2.4.1", displayVersionName("2.4.1", "版本信息不可用"))
        assertEquals("版本信息不可用", displayVersionName(null, "版本信息不可用"))
        assertEquals("版本信息不可用", displayVersionName("   ", "版本信息不可用"))
    }

    @Test
    fun discoveringCtaSeparatesInitialDiscoveryFromFailureRecovery() {
        val runtime = RuntimeSessionId("runtime-a")
        val initial = homePresentation(
            state = IntercomState.Discovering(runtime),
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false
        )
        val afterError = homePresentation(
            state = IntercomState.Discovering(runtime),
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false,
            supplementalText = "连接失败",
            discoverCtaNeedsReselect = true
        )
        val offline = homePresentation(
            state = IntercomState.Offline,
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false,
            discoverCtaNeedsReselect = true
        )

        assertTrue(initial.showDiscoverCta)
        assertEquals(HOME_DISCOVER_CTA, initial.discoverCtaLabel)
        assertEquals("连接失败", afterError.supplementalText)
        assertEquals(HOME_DISCOVER_RESELECT_CTA, afterError.discoverCtaLabel)
        assertFalse(offline.showDiscoverCta)
        assertEquals(HOME_DISCOVER_CTA, offline.discoverCtaLabel)
    }

    @Test
    fun homePresentationCoversNineProductStatesTruthfully() {
        val runtime = RuntimeSessionId("runtime-a")
        val attempt = attempt(runtime = runtime, transports = ChannelPlan.race(Transport.LAN, Transport.WIFI_DIRECT))
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
            IntercomState.IncomingConfirmation(runtime, ConnectionAttemptId("incoming"), peer),
            IntercomState.Connecting(attempt, peer),
            IntercomState.Optimizing(attempt, peer),
            IntercomState.Connected(attempt, peer, connectedAt = 123L, transport = Transport.LAN),
            IntercomState.Recovering(attempt, peer),
            IntercomState.Resetting(runtime, "peer-a", ConnectionAttemptId("failed"), RECOVERY_RESET_FAILURE_THRESHOLD),
            IntercomState.Stopping(runtime)
        )

        val presentations = states.map {
            homePresentation(
                state = it,
                canStart = true,
                audioSourceText = "扬声器",
                bluetoothActive = true,
                supplementalText = "supplement",
                lastStoppingPeerName = "Road Captain"
            )
        }

        assertEquals("点击下方启动摩声", presentations[0].primaryText)
        assertEquals("正在寻找附近 MotoCom 车友", presentations[1].primaryText)
        assertEquals("收到附近车友的连接请求", presentations[2].primaryText)
        assertEquals("正在建立连接", presentations[3].primaryText)
        assertEquals("正在优化连接通道", presentations[4].primaryText)
        assertEquals("语音通道已连接", presentations[5].primaryText)
        assertEquals("正在恢复原车友连接", presentations[6].primaryText)
        assertEquals("正在重置无线连接", presentations[7].primaryText)
        assertEquals("正在结束对讲", presentations[8].primaryText)
        assertEquals("启动摩声", presentations[0].primaryActionLabel)
        assertEquals("结束对讲", presentations[1].primaryActionLabel)
        assertEquals("停止中…", presentations[8].primaryActionLabel)
        assertEquals(
            listOf(
                PrimaryIntercomAction.START,
                PrimaryIntercomAction.STOP_RUNTIME,
                PrimaryIntercomAction.STOP_RUNTIME,
                PrimaryIntercomAction.DISCONNECT_CURRENT,
                PrimaryIntercomAction.DISCONNECT_CURRENT,
                PrimaryIntercomAction.DISCONNECT_CURRENT,
                PrimaryIntercomAction.DISCONNECT_CURRENT,
                PrimaryIntercomAction.STOP_RUNTIME,
                PrimaryIntercomAction.NONE
            ),
            presentations.map(HomePresentation::primaryAction)
        )
        assertFalse(presentations[8].primaryActionEnabled)
        assertFalse(presentations[0].showPermissionGrantCta)
        assertFalse(presentations[8].showPermissionGrantCta)
        assertFalse(presentations[0].showPermissionSettingsCta)
        assertFalse(presentations[8].showPermissionSettingsCta)
        assertFalse(presentations[0].showWifiSettingsCta)
        assertFalse(presentations[8].showWifiSettingsCta)
        assertTrue(presentations[1].showDiscoverCta)
        assertEquals(HOME_DISCOVER_CTA, presentations[1].discoverCtaLabel)
        assertFalse(presentations[3].showDiscoverCta)
        assertEquals("等待车友加入", presentations[0].peerText)
        assertEquals("Road Captain", presentations[5].peerText)
        assertEquals("Road Captain", presentations[6].peerText)
        assertEquals("正在恢复原车友", presentations[7].peerText)
        assertEquals("LAN + Wi-Fi Direct", presentations[3].plannedTransportText)
        assertEquals("LAN", presentations[5].connectedTransportText)
        assertEquals("建立中", presentations[3].webRtcText)
        assertEquals("已连接", presentations[5].webRtcText)
        assertEquals("未连接", presentations[6].webRtcText)
        assertTrue(presentations[0].bluetoothActive)
        assertEquals(PLACEHOLDER_VOX_STATUS, presentations[5].voxText)
        assertEquals("supplement", presentations[0].supplementalText)
        assertEquals("点击下方启动摩声", presentations[0].primaryText)
    }

    @Test
    fun offlineHomeDisablesStartWhenPermissionsAreMissing() {
        val presentation = homePresentation(
            state = IntercomState.Offline,
            canStart = false,
            audioSourceText = "待机",
            bluetoothActive = false
        )

        assertEquals(PrimaryIntercomAction.START, presentation.primaryAction)
        assertFalse(presentation.primaryActionEnabled)
        assertEquals("缺少必要权限", presentation.disabledReason)
        assertTrue(presentation.showPermissionGrantCta)
        assertTrue(presentation.showPermissionSettingsCta)
    }

    @Test
    fun wifiUnavailableShowsRealSettingsEntryWithoutPretendingDiscoveryFailed() {
        val home = homePresentation(
            state = IntercomState.Offline,
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false,
            wifiUnavailable = true
        )
        val discover = discoverPresentation(
            state = IntercomState.Offline,
            presences = emptyList(),
            wifiUnavailable = true
        )
        val discovering = discoverPresentation(
            state = IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            presences = emptyList(),
            wifiUnavailable = true
        )

        assertEquals(PrimaryIntercomAction.START, home.primaryAction)
        assertTrue(home.primaryActionEnabled)
        assertEquals(WIFI_UNAVAILABLE_TEXT, home.disabledReason)
        assertTrue(home.showWifiSettingsCta)
        assertFalse(home.showPermissionGrantCta)
        assertTrue(discover.offlineStartVisible)
        assertTrue(discover.wifiSettingsVisible)
        assertEquals("启动摩声后开始发现附近车友", discover.readOnlyReason)
        assertTrue(discovering.wifiSettingsVisible)
    }

    @Test
    fun discoverPermissionBlockTakesPriorityOverWifiRepair() {
        val offline = discoverPresentation(
            state = IntercomState.Offline,
            presences = emptyList(),
            wifiUnavailable = true,
            canStart = false
        )

        assertTrue(offline.offlineStartVisible)
        assertFalse(offline.wifiSettingsVisible)
        assertEquals("启动摩声后开始发现附近车友", offline.readOnlyReason)
    }

    @Test
    fun optionalPermissionPresentationDegradesBluetoothWithoutBlockingIntercom() {
        val bluetoothMissing = optionalPermissionPresentation(
            bluetoothPermissionMissing = true,
            notificationPermissionMissing = false,
            bluetoothActive = true
        )

        assertFalse(bluetoothMissing.bluetoothActive)
        assertEquals(BLUETOOTH_PERMISSION_UNAVAILABLE, bluetoothMissing.bluetoothStatusText)
        assertEquals(OPTIONAL_PERMISSION_BLUETOOTH_NOTICE, bluetoothMissing.noticeText)
        assertTrue(bluetoothMissing.showGrantCta)

        val notificationOnly = optionalPermissionPresentation(
            bluetoothPermissionMissing = false,
            notificationPermissionMissing = true,
            bluetoothActive = true
        )

        assertTrue(notificationOnly.bluetoothActive)
        assertEquals(BLUETOOTH_CONNECTED_TEXT, notificationOnly.bluetoothStatusText)
        assertEquals(OPTIONAL_PERMISSION_NOTIFICATION_NOTICE, notificationOnly.noticeText)
        assertTrue(notificationOnly.showGrantCta)
    }

    @Test
    fun audioSourcePresentationUsesTruthfulFallbackWhenBluetoothDeviceNameIsMissing() {
        assertEquals(
            BLUETOOTH_AUDIO_CONNECTED_TEXT,
            audioSourcePresentation("", bluetooth = true)
        )
        assertEquals(
            BLUETOOTH_AUDIO_CONNECTED_TEXT,
            audioSourcePresentation("当前音频源：蓝牙耳机 ( )", bluetooth = true)
        )
        assertEquals(
            BLUETOOTH_AUDIO_CONNECTED_TEXT,
            audioSourcePresentation("当前音频源：蓝牙耳机 (头盔蓝牙)", bluetooth = true)
        )
        assertEquals(
            BLUETOOTH_AUDIO_CONNECTED_TEXT,
            audioSourcePresentation("头盔蓝牙", bluetooth = true)
        )
        assertEquals(
            AUDIO_SOURCE_STANDBY_TEXT,
            audioSourcePresentation("", bluetooth = false)
        )
        assertEquals(
            AUDIO_SOURCE_STANDBY_TEXT,
            audioSourcePresentation("当前音频源：蓝牙耳机 (Helmet)", bluetooth = false)
        )
        assertEquals(
            AUDIO_SOURCE_STANDBY_TEXT,
            audioSourcePresentation("蓝牙音频已连接", bluetooth = false)
        )
        assertEquals(
            "当前音频源：蓝牙耳机 (Helmet)",
            audioSourcePresentation(" 当前音频源：蓝牙耳机 (Helmet) ", bluetooth = true)
        )
    }

    @Test
    fun missingBluetoothPermissionCannotExposeConnectedAudioSource() {
        assertEquals(
            BLUETOOTH_PERMISSION_UNAVAILABLE,
            visibleAudioSourceText(
                status = "当前音频源：蓝牙耳机 (Helmet)",
                bluetooth = true,
                bluetoothPermissionMissing = true
            )
        )
        assertEquals(
            "当前音频源：蓝牙耳机 (Helmet)",
            visibleAudioSourceText(
                status = "当前音频源：蓝牙耳机 (Helmet)",
                bluetooth = true,
                bluetoothPermissionMissing = false
            )
        )
    }

    @Test
    fun incomingConfirmationNotificationUsesTruthfulDeviceCopy() {
        assertEquals(
            "Helmet · 请在应用内确认",
            incomingConfirmationNotificationMessage("  Helmet  ")
        )
        assertEquals(
            "设备名称未提供 · 请在应用内确认",
            incomingConfirmationNotificationMessage(" ")
        )
    }

    @Test
    fun connectingWithoutPeerDoesNotInventRiderIdentity() {
        val presentation = homePresentation(
            state = IntercomState.Connecting(attempt()),
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false
        )

        assertEquals("正在确认目标车友", presentation.peerText)
    }

    @Test
    fun homeDoesNotPromoteDeviceNameToRiderIdentity() {
        val peer = PeerIdentity(
            deviceId = "peer-a",
            nickname = "",
            deviceName = "Pixel 8",
            runtimeSessionId = RuntimeSessionId("peer-runtime"),
            isDeviceIdVerified = true
        )
        val connected = homePresentation(
            state = IntercomState.Connected(
                attempt(),
                peer,
                connectedAt = 1L,
                transport = Transport.LAN
            ),
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false
        )
        val recovering = homePresentation(
            state = IntercomState.Recovering(attempt(), peer),
            canStart = true,
            audioSourceText = "待机",
            bluetoothActive = false
        )

        assertEquals("已连接车友", connected.peerText)
        assertEquals("正在恢复原车友", recovering.peerText)
    }

    @Test
    fun discoverConnectIsVisibleOnlyForSelectablePresenceDuringDiscovering() {
        val runtime = RuntimeSessionId("runtime-a")
        val selectable = presence(
            deviceId = "device-1111",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "Same",
            deviceName = "",
            transport = Transport.LAN
        )
        val missingIdentity = presence(
            deviceId = null,
            sessionId = RuntimeSessionId("session-b"),
            nickname = "Same",
            deviceName = "",
            transport = Transport.WIFI_DIRECT
        )

        val discovering = discoverPresentation(
            IntercomState.Discovering(runtime),
            listOf(selectable, missingIdentity)
        )
        val pending = discoverPresentation(
            IntercomState.Discovering(runtime),
            listOf(selectable),
            connectPending = true
        )
        val offline = discoverPresentation(IntercomState.Offline, listOf(selectable))

        assertTrue(discovering.cards[0].connectVisible)
        assertTrue(discovering.cards[0].connectEnabled)
        assertTrue(pending.cards[0].connectVisible)
        assertFalse(pending.cards[0].connectEnabled)
        assertFalse(discovering.cards[1].connectVisible)
        assertEquals("Same · 1111", discovering.cards[0].title)
        assertEquals("LAN", discovering.cards[0].transportText)
        assertFalse(offline.cards[0].connectVisible)
        assertTrue(offline.offlineStartVisible)
        assertEquals("启动摩声后开始发现附近车友", offline.readOnlyReason)
    }

    @Test
    fun discoverDoesNotExposeConnectForBlankStableDeviceId() {
        val blankDeviceId = presence(
            deviceId = "",
            sessionId = RuntimeSessionId("session-blank"),
            nickname = "Unidentified",
            deviceName = "Unknown phone",
            transport = Transport.LAN
        )

        val presentation = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(blankDeviceId)
        )

        assertFalse(presentation.cards.single().connectVisible)
        assertFalse(presentation.cards.single().connectEnabled)
    }

    @Test
    fun discoverPresentationUsesOnlyCurrentSnapshotAndReadOnlyReasons() {
        val runtime = RuntimeSessionId("runtime-a")
        val peer = PeerIdentity(
            deviceId = "peer-a",
            nickname = "Current Rider",
            deviceName = "Pixel",
            runtimeSessionId = RuntimeSessionId("peer-runtime"),
            isDeviceIdVerified = true
        )
        val pairedPreferred = presence(
            deviceId = "device-a",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "Twin",
            deviceName = "Pixel 8",
            transport = Transport.LAN,
            pairing = PairingRecord(
                remoteDeviceId = "device-a",
                remoteNickname = "Twin",
                deviceName = "Pixel 8",
                localAlias = "Paired Twin",
                shortCode = "123456",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = true,
                lastTransport = null,
                failureCount = 0
            )
        )
        val sameName = presence(
            deviceId = "device-b",
            sessionId = RuntimeSessionId("session-b"),
            nickname = "Twin",
            deviceName = "Pixel 9",
            transport = Transport.WIFI_DIRECT
        )

        val discovering = discoverPresentation(
            IntercomState.Discovering(runtime),
            listOf(pairedPreferred, sameName)
        )
        val connected = discoverPresentation(
            IntercomState.Connected(attempt(runtime), peer, connectedAt = 1L, transport = Transport.LAN),
            listOf(pairedPreferred)
        )
        val recovering = discoverPresentation(
            IntercomState.Recovering(attempt(runtime), peer),
            listOf(pairedPreferred)
        )
        val staleRemoved = discoverPresentation(IntercomState.Discovering(runtime), emptyList())

        assertEquals(listOf("Paired Twin", "Twin"), discovering.cards.map { it.title.substringBefore(" ·") })
        assertTrue(discovering.cards[0].paired)
        assertTrue(discovering.cards[0].preferred)
        assertEquals("Pixel 9", discovering.cards[1].deviceText)
        assertEquals("Wi-Fi Direct", discovering.cards[1].transportText)
        assertEquals("请先结束当前对讲", connected.readOnlyReason)
        assertFalse(connected.cards[0].connectVisible)
        assertEquals("正在恢复原车友连接，暂不能切换目标", recovering.readOnlyReason)
        assertTrue(staleRemoved.cards.isEmpty())
    }

    @Test
    fun discoverKeepsOfflinePairedRidersAfterOnlineNearbyRiders() {
        val offlinePaired = presence(
            deviceId = "device-offline",
            sessionId = RuntimeSessionId("session-offline"),
            nickname = "Paired Offline",
            deviceName = "Old Phone",
            transport = Transport.LAN,
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
        ).copy(
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
        val nearby = presence(
            deviceId = "device-nearby",
            sessionId = RuntimeSessionId("session-nearby"),
            nickname = "Nearby",
            deviceName = "Phone",
            transport = Transport.WIFI_DIRECT
        )

        val cards = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(offlinePaired, nearby)
        ).cards

        assertTrue(cards.first { it.paired }.offlinePaired)
        assertFalse(cards.first { !it.paired }.offlinePaired)
    }

    @Test
    fun discoverStateExplainsWhenSnapshotHasNoConnectablePresence() {
        val unavailable = presence(
            deviceId = "device-unavailable",
            sessionId = RuntimeSessionId("session-unavailable"),
            nickname = "Unavailable",
            deviceName = "Old Phone",
            transport = Transport.LAN
        ).copy(
            candidates = listOf(presenceCandidate(Transport.LAN, "expired").copy(isAvailable = false))
        )

        val presentation = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(unavailable)
        )

        assertFalse(presentation.cards.single().connectVisible)
        assertEquals("当前没有可连接的车友", discoverStateText(presentation))
    }

    @Test
    fun discoverOrdersPreferredPairedNearbyAndOfflineGroupsIndependentlyOfSnapshotOrder() {
        val preferredOnline = presence(
            deviceId = "device-preferred",
            sessionId = RuntimeSessionId("session-preferred"),
            nickname = "Preferred",
            deviceName = "Preferred Phone",
            transport = Transport.LAN,
            pairing = PairingRecord(
                remoteDeviceId = "device-preferred",
                remoteNickname = "Preferred",
                deviceName = "Preferred Phone",
                localAlias = "Preferred",
                shortCode = "111111",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = true,
                lastTransport = "LAN",
                failureCount = 0
            )
        )
        val pairedOnline = presence(
            deviceId = "device-paired",
            sessionId = RuntimeSessionId("session-paired"),
            nickname = "Paired",
            deviceName = "Paired Phone",
            transport = Transport.WIFI_DIRECT,
            pairing = PairingRecord(
                remoteDeviceId = "device-paired",
                remoteNickname = "Paired",
                deviceName = "Paired Phone",
                localAlias = "Paired",
                shortCode = "222222",
                pairedAt = 1L,
                lastConnectedAt = 2L,
                isPreferred = false,
                lastTransport = "Wi-Fi Direct",
                failureCount = 0
            )
        )
        val nearby = presence(
            deviceId = "device-nearby",
            sessionId = RuntimeSessionId("session-nearby"),
            nickname = "Nearby",
            deviceName = "Nearby Phone",
            transport = Transport.LAN
        )
        val offlinePaired = pairedOnline.copy(
            deviceId = "device-offline",
            sessionId = RuntimeSessionId("session-offline"),
            nickname = "Offline",
            deviceName = "Offline Phone",
            candidates = pairedOnline.candidates.map { it.copy(isAvailable = false) },
            pairing = pairedOnline.pairing?.copy(
                remoteDeviceId = "device-offline",
                remoteNickname = "Offline",
                deviceName = "Offline Phone",
                localAlias = "Offline",
                isPreferred = false
            )
        )

        val presentation = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(offlinePaired, nearby, pairedOnline, preferredOnline)
        )

        assertEquals(
            listOf("Preferred", "Paired", "Nearby", "Offline"),
            presentation.cards.map { it.title }
        )
        assertEquals(
            listOf("Preferred", "Paired", "Nearby", "Offline"),
            presentation.orderedPresences.map { it.nickname }
        )
        assertTrue(presentation.cards.last().offlinePaired)
    }

    @Test
    fun discoverTransportFactsUseStableChannelOrder() {
        val presence = presence(
            deviceId = "device-multi",
            sessionId = RuntimeSessionId("session-multi"),
            nickname = "Multi",
            deviceName = "Multi Phone",
            transport = Transport.WIFI_DIRECT
        ).copy(
            candidates = listOf(
                presenceCandidate(Transport.WIFI_DIRECT, "p2p"),
                presenceCandidate(Transport.LAN, "lan")
            )
        )

        val card = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(presence)
        ).cards.single()

        assertEquals("LAN + Wi-Fi Direct", card.transportText)
    }

    @Test
    fun discoverDoesNotRenderDomainRiderFallbackAsAnIdentity() {
        val presence = presence(
            deviceId = "device-a",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "",
            deviceName = "",
            transport = Transport.LAN
        )

        val card = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(presence)
        ).cards.single()
        assertEquals("附近车友", card.title)
        assertEquals(DEVICE_NAME_UNAVAILABLE, card.deviceText)
    }

    @Test
    fun discoverKeepsDeviceNameSeparateFromMissingRiderNickname() {
        val presence = presence(
            deviceId = "device-a",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "",
            deviceName = "Pixel 8",
            transport = Transport.LAN
        )

        val card = discoverPresentation(
            IntercomState.Discovering(RuntimeSessionId("runtime-a")),
            listOf(presence)
        ).cards.single()

        assertEquals("附近车友", card.title)
        assertEquals("Pixel 8", card.deviceText)
    }

    @Test
    fun settingsDiscoveryCandidateSummaryUsesOnlyAvailableSnapshotTransports() {
        val available = presence(
            deviceId = "device-a",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "Road Captain",
            deviceName = "Pixel",
            transport = Transport.LAN
        ).copy(
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.LAN,
                    endpointId = "lan",
                    address = "192.0.2.10",
                    port = 1234,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = true
                ),
                PresenceTransportCandidate(
                    transport = Transport.WIFI_DIRECT,
                    endpointId = "p2p",
                    address = "192.0.2.11",
                    port = 5678,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = false
                )
            )
        )
        val expired = presence(
            deviceId = "device-b",
            sessionId = RuntimeSessionId("session-b"),
            nickname = "Expired",
            deviceName = "Old Phone",
            transport = Transport.WIFI_DIRECT
        ).copy(
            candidates = listOf(
                PresenceTransportCandidate(
                    transport = Transport.WIFI_DIRECT,
                    endpointId = "expired",
                    address = "192.0.2.12",
                    port = 5678,
                    lastSeenElapsedRealtimeMs = 1L,
                    isAvailable = false
                )
            )
        )

        assertEquals(
            "Road Captain：LAN",
            discoveryCandidateSummary(listOf(available, expired))
        )
        assertEquals(
            "当前没有可用发现候选",
            discoveryCandidateSummary(listOf(expired))
        )
    }

    @Test
    fun discoverIgnoresBlankStableIdWhenDisambiguatingNames() {
        val first = presence(
            deviceId = "",
            sessionId = RuntimeSessionId("session-a"),
            nickname = "Twin",
            deviceName = "",
            transport = Transport.LAN
        )
        val second = presence(
            deviceId = "device-b",
            sessionId = RuntimeSessionId("session-b"),
            nickname = "Twin",
            deviceName = "",
            transport = Transport.WIFI_DIRECT
        )

        assertEquals(
            "Twin",
            discoverPresentation(
                IntercomState.Discovering(RuntimeSessionId("runtime-a")),
                listOf(first, second)
            ).cards.first().title
        )
    }

    @Test
    fun nicknameValidationTrimsAndCountsUnicodeCodePoints() {
        assertEquals(NicknameValidation.Valid("车友"), validateNickname("  车友  "))
        assertTrue(validateNickname("   ") is NicknameValidation.Invalid)
        assertEquals(
            NicknameValidation.Valid("😀".repeat(64)),
            validateNickname("😀".repeat(64))
        )
        assertTrue(validateNickname("😀".repeat(65)) is NicknameValidation.Invalid)
    }

    @Test
    fun placeholderDialogCopyIsExactAndLogsCopyPreservesOrder() {
        assertEquals("功能开发中", PLACEHOLDER_DIALOG_TITLE)
        assertEquals("该功能还没做好，暂时无法使用。", PLACEHOLDER_DIALOG_MESSAGE)
        assertEquals("确定", PLACEHOLDER_DIALOG_BUTTON)
        assertEquals("仅显示本次界面会话日志", LOGS_SCOPE_TEXT)
        assertEquals("日志已复制", LOGS_COPIED_FEEDBACK)
        assertEquals("one\ntwo\nthree", copyableLogText(listOf("one", "two", "three")))
        assertEquals("", copyableLogText(emptyList()))
    }

    @Test
    fun logFollowBottomPolicyOnlyFollowsWhenAlreadyNearBottom() {
        assertTrue(shouldFollowLogBottom(scrollY = 0, viewportHeight = 100, contentBottom = 0, thresholdPx = 16))
        assertTrue(shouldFollowLogBottom(scrollY = 884, viewportHeight = 100, contentBottom = 1_000, thresholdPx = 16))
        assertFalse(shouldFollowLogBottom(scrollY = 700, viewportHeight = 100, contentBottom = 1_000, thresholdPx = 16))
    }

    private fun attempt(
        runtime: RuntimeSessionId = RuntimeSessionId("runtime-a"),
        transports: ChannelPlan = ChannelPlan.single(Transport.LAN)
    ) = ConnectionAttempt(
        id = ConnectionAttemptId("attempt-a"),
        runtimeSessionId = runtime,
        targetLock = TargetLock("peer-a", RuntimeSessionId("peer-runtime")),
        trigger = ConnectionTrigger.USER,
        channelPlan = transports,
        deadlineElapsedRealtimeMs = 1_000L
    )

    private fun presence(
        deviceId: String?,
        sessionId: RuntimeSessionId?,
        nickname: String,
        deviceName: String,
        transport: Transport,
        pairing: PairingRecord? = null
    ) = RiderPresence(
        deviceId = deviceId,
        sessionId = sessionId,
        nickname = nickname,
        deviceName = deviceName,
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(
            PresenceTransportCandidate(
                transport = transport,
                endpointId = "endpoint",
                address = "127.0.0.1",
                port = 1234,
                lastSeenElapsedRealtimeMs = 1L,
                isAvailable = true
            )
        ),
        pairing = pairing
    )

    private fun presenceCandidate(
        transport: Transport,
        endpointId: String
    ) = PresenceTransportCandidate(
        transport = transport,
        endpointId = endpointId,
        address = "127.0.0.1",
        port = 1234,
        lastSeenElapsedRealtimeMs = 1L,
        isAvailable = true
    )
}
