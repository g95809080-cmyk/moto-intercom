package com.kuma.motointercom

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import java.util.EnumMap
import java.util.UUID
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

internal class MainScreen(
    private val activity: Activity,
    initialRiderName: String,
    savedState: Bundle?,
    private val onToggleIntercom: () -> Unit,
    private val onConnectPresence: (RiderPresence) -> Boolean,
    private val onSaveRiderName: (String) -> Boolean,
    private val onRequestCorePermissions: () -> Unit,
    private val onRequestOptionalPermissions: () -> Unit,
    private val onOpenWifiSettings: () -> Unit,
    private val onOpenPermissionSettings: () -> Unit,
    initialAudioControls: AudioControlSnapshot = idleAudioControlSnapshot(AudioControlSettings()),
    initialPreferredAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    initialAutomaticReconnectEnabled: Boolean = true,
    initialTheme: MotoComThemeMode = MotoComThemeMode.DAYLIGHT,
    private val onThemeSelected: (MotoComThemeMode) -> Boolean = { true },
    private val onSetMuted: (Boolean) -> Unit = {},
    private val onSetVoxEnabled: (Boolean) -> Unit = {},
    private val onSetVoxSensitivity: (Int) -> Unit = {},
    private val onSelectAudioRoute: (AudioRouteSelection) -> Unit = {},
    private val onSetRnnoiseEnabled: (Boolean) -> Unit = {},
    private val onAutomaticReconnectChanged: (Boolean) -> Unit = {},
    private val onRequestDiscoveryRefresh: () -> Unit = {},
    private val onSetPairingPreferred: (String, Boolean) -> Boolean = { _, _ -> false },
    private val onForgetPairing: (String) -> Boolean = { false },
    private val onSendFeedback: (String) -> Unit = {},
    private val onboardingPreferences: OnboardingPreferences? = null,
    private val diagnostics: DiagnosticLogAccess? = DiagnosticLog.forContext(activity),
    private val onConnectPresenceRequest: (RiderPresence, PresenceConnectRequest) -> Boolean =
        { presence, _ -> onConnectPresence(presence) }
) {
    val root: View

    private val inflater: LayoutInflater = LayoutInflater.from(activity)
    private val pageContainer: FrameLayout
    private val navigationRail: View
    private val bottomNavigation: View
    private val expandedDetailContainer: View
    private val navigationScrim: View
    private val navigationPanel: View
    private val scrollPositions = EnumMap<MainRoute, Int>(MainRoute::class.java)
    private val pendingRestoredScrollPositions = EnumMap<MainRoute, Int>(MainRoute::class.java)
    private val logBuffer = BoundedLogBuffer(300)
    private var diagnosticsActive = true
    private var diagnosticsClosed = false
    private var diagnosticsGeneration = 0L
    private var historyReadPending = false
    private var exportPreparing = false
    private var diagnosticError: String? = null
    private val logRefresh = object : Runnable {
        override fun run() {
            if (!diagnosticsActive || diagnosticsClosed || currentRoute != MainRoute.LOGS) return
            refreshPersistedLogs()
            root.postDelayed(this, 2_000)
        }
    }
    private var onboarding: OnboardingGuide? = null
    private val guideAnchors = EnumMap<GuideTarget, GuideAnchor>(GuideTarget::class.java)
    private val homeUiState = mutableStateOf(
        HomeScreenUiState(
            primaryText = "",
            supplementalText = null,
            peerText = "",
            primaryActionLabel = "",
            primaryActionEnabled = false,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "",
            showDiscoverCta = false,
            audioSourceText = "",
            discovering = false,
            connected = false
        )
    )
    private val homeAudioLevel = mutableFloatStateOf(0f)
    private val homeRadarPeers = mutableStateOf(emptyList<RadarPreviewPeer>())
    private var audioControlSnapshot = initialAudioControls
    private val appTheme = mutableStateOf(initialTheme)
    private val discoverUiState = mutableStateOf(
        DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "",
            supplementalText = null,
            radarRunning = false,
            rescanEnabled = false
        )
    )
    private val settingsUiState = mutableStateOf(
        SettingsScreenUiState("", "", "", "", null, false, "")
    )
    private val logsUiState = mutableStateOf(LogsScreenUiState("", "", false))

    private var currentRoute: MainRoute = restoreMainRoute(savedState?.getString(KEY_ROUTE))
    private var windowWidthClass: MainWindowWidthClass = MainWindowWidthClass.Compact
    private var currentScroll: ScrollView? = null
    private var productState: IntercomState = IntercomState.Offline
    private var canStartIntercom = false
    private var permissionRequestAttempted = onboardingPreferences?.permissionRequested ?: false
    private var supplementalStatus: String? = null
    private var permissionStatus: String? = null
    private var discoverCtaNeedsReselect = false
    private var audioSourceText = AUDIO_SOURCE_STANDBY_TEXT
    private var bluetoothActive = false
    private var audioReady = false
    private var preferredAudioRoute = initialPreferredAudioRoute
    private var automaticReconnectEnabled = initialAutomaticReconnectEnabled
    private var wifiUnavailable = false
    private var bluetoothPermissionMissing = false
    private var notificationPermissionMissing = false
    private var presences = emptyList<RiderPresence>()
    private var lastRenderedDiscoverPresentation: DiscoverPresentation? = null
    private var lastRealPeerName: String? = null
    private var discoverConnectAwaitingState = false
    private var pendingPresenceSelection: PendingPresenceSelection? = null
    private var pendingPresenceExpiry: Runnable? = null
    private var pendingPresenceMissingDeadlineMs: Long? = null
    private var pendingConnectRequest: PresenceConnectRequest? = null
    private var pendingConnectAdmitted = false
    private var settingsNicknameDraft = restoreNicknameDraft(
        savedState?.getString(KEY_NICKNAME_DRAFT),
        initialRiderName
    )
    private var helpDialog: AlertDialog? = null
    private var presenceDetailsDialog: AlertDialog? = null
    private var pairingManagementDialog: AlertDialog? = null
    private var forgetPairingDialog: AlertDialog? = null
    private var activePairingDeviceId: String? = null
    private var incomingConfirmationVisible = false
    private var logBottomFollowPending = false
    private var userScrollView: ScrollView? = null
    private var expandedSelectedPresence: PendingPresenceSelection? = savedState?.let { state ->
        val deviceId = state.getString(KEY_EXPANDED_SELECTED_DEVICE_ID)
        val sessionId = state.getString(KEY_EXPANDED_SELECTED_SESSION_ID)
        if (deviceId != null && sessionId != null) {
            PendingPresenceSelection(deviceId, RuntimeSessionId(sessionId))
        } else {
            null
        }
    }

    init {
        root = inflater.inflate(R.layout.activity_main, FrameLayout(activity), false)
        pageContainer = root.findViewById(R.id.page_container)
        navigationRail = root.findViewById(R.id.navigation_rail)
        bottomNavigation = root.findViewById(R.id.bottom_navigation)
        expandedDetailContainer = root.findViewById(R.id.expanded_detail_container)
        navigationScrim = root.findViewById(R.id.navigation_scrim)
        navigationPanel = root.findViewById(R.id.navigation_panel)
        windowWidthClass = runCatching { mainWindowInfo(activity).widthClass }
            .getOrDefault(MainWindowWidthClass.Compact)
        restoreScrollPositions(savedState)
        configureWindow()
        bindNavigation()
        showPage(currentRoute)
        updateAdaptiveLayout()
        onboardingPreferences?.let { preferences ->
            onboarding = OnboardingGuide(
                root = root as FrameLayout,
                preferences = preferences,
                anchor = { target ->
                    if (target == GuideTarget.DISCOVER) {
                        GuideAnchor(root.findViewById(
                            if (bottomNavigation.visibility == View.VISIBLE) R.id.bottom_nav_discover_button
                            else R.id.adaptive_nav_discover_button
                        ))
                    } else guideAnchors[if (target == GuideTarget.PERMISSION) GuideTarget.START else target]
                },
                scroll = { currentScroll },
                onAction = ::performGuideAction,
                onTourStarted = { showPage(MainRoute.HOME) }
            )
            refreshGuide()
        }
    }

    fun saveState(outState: Bundle) {
        saveCurrentPageScrollAndDraft()
        outState.putString(KEY_ROUTE, currentRoute.name)
        outState.putString(KEY_NICKNAME_DRAFT, settingsNicknameDraft)
        expandedSelectedPresence?.let { selected ->
            outState.putString(KEY_EXPANDED_SELECTED_DEVICE_ID, selected.deviceId)
            outState.putString(KEY_EXPANDED_SELECTED_SESSION_ID, selected.sessionId.value)
        }
        MainRoute.entries.forEach { route ->
            outState.putInt(scrollKey(route), scrollPositions[route] ?: 0)
        }
    }

    /** Re-apply the saved page scroll after Android restores focused child state. */
    fun restoreCurrentPageScrollAfterResume() {
        restoreCurrentScroll(finalizeActivityRestore = true)
    }

    fun onWindowSizeChanged(widthDp: Int? = null, heightDp: Int? = null) {
        val simulatedWindowClass = if (widthDp != null && heightDp != null) {
            mainWindowWidthClass(widthDp, heightDp)
        } else {
            null
        }
        updateAdaptiveLayout(simulatedWindowClass)
        constrainCurrentPageWidth()
        constrainNavigationPanelWidth()
    }

    fun handleBack(): Boolean {
        if (!incomingConfirmationVisible && onboarding?.handleBack() == true) return true
        return when (val action = resolveBackNavigation(currentChrome())) {
            BackNavigation.CloseNavigation -> {
                closeNavigation()
                true
            }
            BackNavigation.DismissTransientDialog -> {
                dismissTransientDialogs()
                true
            }
            BackNavigation.IgnoreIncomingConfirmation -> true
            is BackNavigation.NavigateTo -> {
                showPage(action.route)
                true
            }
            BackNavigation.SystemDefault -> false
        }
    }

    fun navigateHome() {
        showPage(MainRoute.HOME)
    }

    fun dismissHelpDialog() {
        helpDialog?.dismiss()
        helpDialog = null
    }

    fun dismissTransientDialogs() {
        dismissHelpDialog()
        presenceDetailsDialog?.dismiss()
        dismissPairingDialogs()
    }

    fun setIncomingConfirmationVisible(visible: Boolean) {
        incomingConfirmationVisible = visible
        refreshGuide()
    }

    fun setIntercomState(state: IntercomState, canStart: Boolean) {
        if (pendingConnectRequest?.let { it.runtimeSessionId != state.runtimeSessionId } == true) {
            clearPendingDiscoverConnect()
        }
        if (state !is IntercomState.Connected) audioReady = false
        if (state !is IntercomState.Offline && supplementalStatus == permissionStatus) {
            supplementalStatus = null
        }
        if (
            state !is IntercomState.Offline &&
                state !is IntercomState.Discovering &&
                supplementalStatus == WIFI_UNAVAILABLE_TEXT
        ) {
            supplementalStatus = null
        }
        val navigateAfterDiscoverConnect =
            discoverConnectAwaitingState && shouldNavigateHomeAfterDiscoverConnect(state)
        if (
            discoverConnectAwaitingState &&
            !navigateAfterDiscoverConnect &&
            !shouldKeepDiscoverConnectPending(state)
        ) {
            clearPendingDiscoverConnect()
        }
        if (navigateAfterDiscoverConnect) {
            clearPendingDiscoverConnect()
        }
        productState = state
        canStartIntercom = canStart
        if (state !is IntercomState.Discovering) discoverCtaNeedsReselect = false
        when (state) {
            IntercomState.Offline -> lastRealPeerName = null
            is IntercomState.Discovering -> lastRealPeerName = null
            is IntercomState.IncomingConfirmation -> rememberPeer(state.peer)
            is IntercomState.Connecting -> state.peer?.let(::rememberPeer)
            is IntercomState.Optimizing -> state.peer?.let(::rememberPeer)
            is IntercomState.Connected -> rememberPeer(state.peer)
            is IntercomState.Recovering -> rememberPeer(state.peer)
            else -> Unit
        }
        if (navigateAfterDiscoverConnect && currentRoute == MainRoute.DISCOVER) {
            showPage(MainRoute.HOME)
        } else {
            renderCurrentPage()
        }
        updateExpandedDetailPane()
    }

    fun setIntercomError(message: String, persistLog: Boolean = true) {
        clearPendingDiscoverConnect()
        permissionStatus = null
        supplementalStatus = message
        discoverCtaNeedsReselect = productState != IntercomState.Offline
        appendLog("错误：$message", persist = persistLog)
        renderCurrentPage()
    }

    fun setStatus(message: String, appendLog: Boolean = true, persistLog: Boolean = true) {
        permissionStatus = null
        supplementalStatus = message
        if (appendLog) appendLog(message, persist = persistLog)
        renderCurrentPage()
    }

    fun setPermissionStatus(message: String?) {
        if (
            supplementalStatus == permissionStatus ||
            supplementalStatus == WIFI_UNAVAILABLE_TEXT &&
                productState == IntercomState.Offline &&
                wifiUnavailable
        ) {
            supplementalStatus = message
        }
        permissionStatus = message
        renderCurrentPage()
    }

    fun markPermissionRequestAttempted() {
        permissionRequestAttempted = true
        onboardingPreferences?.permissionRequested = true
        renderCurrentPage()
    }

    fun permissionRequestWasAttempted(): Boolean = permissionRequestAttempted

    fun setAudioSource(status: String, bluetooth: Boolean) {
        audioSourceText = audioSourcePresentation(status, bluetooth)
        bluetoothActive = bluetooth
        renderCurrentPage()
    }

    fun setAudioReady(ready: Boolean) {
        audioReady = ready && productState is IntercomState.Connected
        renderCurrentPage()
        updateExpandedDetailPane()
    }

    fun setAutomaticReconnectEnabled(enabled: Boolean) {
        automaticReconnectEnabled = enabled
        renderSettings()
    }

    fun setAudioControls(snapshot: AudioControlSnapshot) {
        audioControlSnapshot = snapshot
        renderCurrentPage()
    }

    fun setPreferredAudioRoute(selection: AudioRouteSelection) {
        preferredAudioRoute = selection
        renderCurrentPage()
    }

    fun clearServiceOwnedFacts() {
        clearPendingDiscoverConnect()
        audioSourceText = AUDIO_SOURCE_STANDBY_TEXT
        bluetoothActive = false
        audioReady = false
        presences = emptyList()
        lastRealPeerName = null
        audioControlSnapshot = idleAudioControlSnapshot(audioControlSnapshot.controls)
        renderCurrentPage()
    }

    fun setOptionalPermissionState(
        bluetoothPermissionMissing: Boolean,
        notificationPermissionMissing: Boolean
    ) {
        this.bluetoothPermissionMissing = bluetoothPermissionMissing
        this.notificationPermissionMissing = notificationPermissionMissing
        renderCurrentPage()
    }

    fun setWifiUnavailable(unavailable: Boolean) {
        if (
            unavailable &&
            (productState == IntercomState.Offline || productState is IntercomState.Discovering) &&
            canStartIntercom &&
            (
                supplementalStatus == null ||
                    supplementalStatus == permissionStatus ||
                    supplementalStatus == WIFI_UNAVAILABLE_TEXT
                )
        ) {
            supplementalStatus = WIFI_UNAVAILABLE_TEXT
            permissionStatus = null
        }
        if (!unavailable && wifiUnavailable && supplementalStatus == WIFI_UNAVAILABLE_TEXT) {
            supplementalStatus = null
        }
        wifiUnavailable = unavailable
        renderCurrentPage()
    }

    fun setRemoteRider(name: String?) {
        when (productState) {
            is IntercomState.IncomingConfirmation,
            is IntercomState.Connecting,
            is IntercomState.Optimizing,
            is IntercomState.Connected,
            is IntercomState.Recovering,
            is IntercomState.Stopping -> {
                name?.takeIf(String::isNotBlank)?.let { lastRealPeerName = it }
            }
            IntercomState.Offline,
            is IntercomState.Discovering,
            is IntercomState.Resetting -> Unit
        }
        renderCurrentPage()
    }

    fun setPresences(value: List<RiderPresence>) {
        presences = value.toList()
        activePairingDeviceId?.let { deviceId ->
            if (currentPairedPresence(deviceId) == null) dismissPairingDialogs()
        }
        val pending = pendingPresenceSelection
        if (
            discoverConnectAwaitingState &&
            !pendingConnectAdmitted &&
            pending != null &&
            presences.none { it.matchesPendingSelection(pending) }
        ) {
            schedulePendingPresenceExpiry(pending)
        } else if (pending != null && presences.any { it.matchesPendingSelection(pending) }) {
            cancelPendingPresenceExpiry()
        }
        when (currentRoute) {
            MainRoute.HOME -> renderHome()
            MainRoute.DISCOVER -> renderDiscover()
            MainRoute.SETTINGS -> renderSettings()
            else -> Unit
        }
        updateExpandedDetailPane()
    }

    private fun schedulePendingPresenceExpiry(pending: PendingPresenceSelection) {
        if (pendingPresenceExpiry != null || pendingConnectAdmitted) return
        val request = pendingConnectRequest ?: return
        val deadline = pendingPresenceMissingDeadlineMs ?: (
            SystemClock.uptimeMillis() + DISCOVER_CONNECT_PENDING_GRACE_MS
        ).also { pendingPresenceMissingDeadlineMs = it }
        lateinit var expiry: Runnable
        expiry = Runnable {
            if (pendingPresenceExpiry !== expiry) return@Runnable
            pendingPresenceExpiry = null
            if (
                pendingConnectRequest != request ||
                !discoverConnectAwaitingState ||
                pendingConnectAdmitted ||
                pendingPresenceSelection != pending ||
                productState !is IntercomState.Discovering ||
                productState.runtimeSessionId != request.runtimeSessionId ||
                presences.any { it.matchesPendingSelection(pending) }
            ) {
                return@Runnable
            }
            clearPendingDiscoverConnect()
            renderCurrentPage()
        }
        pendingPresenceExpiry = expiry
        root.postDelayed(expiry, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelPendingPresenceExpiry() {
        pendingPresenceExpiry?.let(root::removeCallbacks)
        pendingPresenceExpiry = null
        pendingPresenceMissingDeadlineMs = null
    }

    private fun clearPendingDiscoverConnect() {
        cancelPendingPresenceExpiry()
        discoverConnectAwaitingState = false
        pendingPresenceSelection = null
        pendingConnectRequest = null
        pendingConnectAdmitted = false
    }

    fun abandonPresenceConnectRequest() {
        clearPendingDiscoverConnect()
    }

    fun onPresenceConnectAdmission(request: PresenceConnectRequest, accepted: Boolean) {
        if (
            pendingConnectRequest != request || !discoverConnectAwaitingState ||
            productState !is IntercomState.Discovering ||
            productState.runtimeSessionId != request.runtimeSessionId
        ) return
        if (accepted) {
            pendingConnectAdmitted = true
            cancelPendingPresenceExpiry()
        } else {
            clearPendingDiscoverConnect()
            permissionStatus = null
            supplementalStatus = "连接未启动，请重新选择车友"
        }
        renderCurrentPage()
    }

    fun setAudioLevel(level: Float) {
        if (currentRoute != MainRoute.HOME || !animationsEnabled()) return
        homeAudioLevel.floatValue = level.coerceIn(0f, 1f)
    }

    fun appendLog(message: String, persist: Boolean = true) {
        if (persist) diagnostics?.record("I", "MotoComUI", message)
        logBuffer.append(PersistentLogStore.clean(message))
        if (shouldRenderLogAppend(currentRoute)) {
            val logText = pageContainer.findViewById<TextView?>(R.id.logs_text)
            val followBottom = logText?.let { it.isAtBottom() || logBottomFollowPending }
                ?: currentScroll?.isAtBottom()
                ?: true
            val preservedScrollY = logText?.scrollY ?: 0
            renderLogs()
            logText?.post {
                if (followBottom && logText.scrollY == preservedScrollY) {
                    logText.scrollToBottom()
                } else if (!followBottom) {
                    logText.scrollTo(0, preservedScrollY.coerceIn(0, logText.maximumScrollY()))
                }
                logBottomFollowPending = false
            }
            if (followBottom) logBottomFollowPending = true
        }
    }

    fun stopAnimations() {
        homeAudioLevel.floatValue = 0f
    }

    @SuppressLint("NewApi")
    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
        }
        applyThemeToWindow(appTheme.value)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safeInsets = calculateSafeWindowInsets(insets)
            view.setPadding(safeInsets[0], safeInsets[1], safeInsets[2], safeInsets[3])
            view.post {
                constrainCurrentPageWidth()
                constrainNavigationPanelWidth()
            }
            insets
        }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val hadLaidOutWidth = oldRight - oldLeft > 0
                updateAdaptiveLayout()
                constrainCurrentPageWidth()
                constrainNavigationPanelWidth()
                if (hadLaidOutWidth) restoreCurrentScroll()
            }
        }
    }

    private fun applyThemeToWindow(theme: MotoComThemeMode) {
        val colors = theme.colors
        activity.window.statusBarColor = colors.background.toArgb()
        activity.window.navigationBarColor = colors.background.toArgb()
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (colors.isLight) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            flags
        } else {
            0
        }
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = colors.isLight
            isAppearanceLightNavigationBars = colors.isLight
        }
        root.setBackgroundColor(colors.background.toArgb())
        navigationRail.setBackgroundColor(colors.panel.toArgb())
        navigationPanel.setBackgroundColor(colors.panel.toArgb())
        expandedDetailContainer.setBackgroundColor(colors.panelRaised.toArgb())
        bottomNavigation.background = roundedDrawable(
            fill = colors.panel.copy(alpha = 0.82f).toArgb(),
            stroke = colors.outline.copy(alpha = 0.72f).toArgb(),
            radiusDp = 18,
            strokeWidthDp = 1
        )
        bottomNavigation.elevation = 0f
        updateNavigationSelection()
    }

    private fun roundedDrawable(
        fill: Int,
        stroke: Int,
        radiusDp: Int,
        strokeWidthDp: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (strokeWidthDp > 0) {
            setStroke(dp(strokeWidthDp), stroke)
        }
    }

    private fun styleNavItem(button: Button, selected: Boolean, isBottom: Boolean) {
        val colors = appTheme.value.colors
        val contentColor = if (selected) colors.text else colors.muted
        val iconTint = if (selected) colors.accent else colors.muted
        button.background = if (isBottom) {
            roundedDrawable(
                fill = if (selected) {
                    colors.accent.copy(alpha = 0.1f).toArgb()
                } else {
                    AndroidColor.TRANSPARENT
                },
                stroke = AndroidColor.TRANSPARENT,
                radiusDp = 14
            )
        } else {
            roundedDrawable(
                fill = if (selected) {
                    colors.accent.copy(alpha = 0.12f).toArgb()
                } else {
                    colors.panelRaised.toArgb()
                },
                stroke = if (selected) {
                    colors.accent.copy(alpha = 0.28f).toArgb()
                } else {
                    colors.outline.toArgb()
                },
                radiusDp = 18,
                strokeWidthDp = 1
            )
        }
        button.setTextColor(contentColor.toArgb())
        button.compoundDrawableTintList = ColorStateList.valueOf(iconTint.toArgb())
        button.elevation = 0f
        button.isAllCaps = false
        button.textSize = if (isBottom) 11f else 14f
    }

    private fun bindNavigation() {
        navigationScrim.setOnClickListener { closeNavigation() }
        listOf(
            R.id.nav_home_button,
            R.id.adaptive_nav_home_button,
            R.id.bottom_nav_home_button
        ).forEach { id ->
            root.findViewById<Button>(id).setOnClickListener { navigateFromPanel(MainRoute.HOME) }
        }
        listOf(
            R.id.nav_discover_button,
            R.id.adaptive_nav_discover_button,
            R.id.bottom_nav_discover_button
        ).forEach { id ->
            root.findViewById<Button>(id).setOnClickListener { navigateFromPanel(MainRoute.DISCOVER) }
        }
        listOf(
            R.id.nav_settings_button,
            R.id.adaptive_nav_settings_button,
            R.id.bottom_nav_settings_button
        ).forEach { id ->
            root.findViewById<Button>(id).setOnClickListener { navigateFromPanel(MainRoute.SETTINGS) }
        }
    }

    private fun navigateFromPanel(route: MainRoute) {
        if (route == currentRoute) {
            closeNavigation()
        } else {
            showPage(route)
        }
    }

    private fun closeNavigation() {
        navigationScrim.visibility = View.GONE
        navigationScrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        navigationPanel.visibility = View.GONE
        navigationPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        pageContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun updateAdaptiveLayout(forcedWidthClass: MainWindowWidthClass? = null) {
        val measuredWidth = maxOf(root.width, root.measuredWidth)
        val measuredHeight = maxOf(root.height, root.measuredHeight)
        val detectedWidthClass = forcedWidthClass ?: if (measuredWidth > 0 && measuredHeight > 0) {
            val density = root.resources.displayMetrics.density
            mainWindowWidthClass(
                widthDp = (measuredWidth / density).toInt(),
                heightDp = (measuredHeight / density).toInt()
            )
        } else {
            runCatching { mainWindowInfo(activity).widthClass }
                .getOrDefault(windowWidthClass)
        }
        if (detectedWidthClass != windowWidthClass) {
            saveCurrentPageScrollAndDraft()
            windowWidthClass = detectedWidthClass
            if (windowWidthClass != MainWindowWidthClass.Compact) closeNavigation()
        }

        val isExpanded = windowWidthClass == MainWindowWidthClass.Expanded
        navigationRail.visibility = View.GONE
        bottomNavigation.visibility = View.VISIBLE
        expandedDetailContainer.visibility = if (isExpanded && currentRoute != MainRoute.LOGS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (currentRoute == MainRoute.HOME) renderHome()
        updateNavigationSelection()
        updateExpandedDetailPane()
    }

    private fun updateExpandedDetailPane() {
        val title = expandedDetailContainer.findViewById<TextView>(R.id.expanded_detail_title)
        val body = expandedDetailContainer.findViewById<TextView>(R.id.expanded_detail_body)
        val status = expandedDetailContainer.findViewById<TextView>(R.id.expanded_detail_status)
        when (currentRoute) {
            MainRoute.DISCOVER -> {
                val selected = expandedSelectedPresence?.let { pending ->
                    presences.firstOrNull {
                        it.deviceId == pending.deviceId && it.sessionId == pending.sessionId
                    }
                }
                title.text = selected?.displayName
                    ?: activity.getString(R.string.nav_discover)
                body.text = selected?.let {
                    "${it.deviceName}\n${it.availableTransports.joinToString()}"
                }.orEmpty()
                status.text = selected?.let {
                    if (it.isSelectable) {
                        activity.getString(R.string.discover_connect)
                    } else {
                        activity.getString(R.string.discover_fact_unavailable)
                    }
                }.orEmpty()
            }
            MainRoute.HOME -> {
                val presentation = homePresentation(
                    state = productState,
                    canStart = canStartIntercom,
                    audioSourceText = audioSourceText,
                    bluetoothActive = bluetoothActive,
                    wifiUnavailable = wifiUnavailable,
                    supplementalText = supplementalStatus,
                    lastStoppingPeerName = lastRealPeerName,
                    discoverCtaNeedsReselect = discoverCtaNeedsReselect,
                    permissionRequestAttempted = permissionRequestAttempted,
                    audioReady = audioReady
                )
                title.text = activity.getString(R.string.nav_home)
                body.text = presentation.peerText
                status.text = presentation.primaryText
            }
            MainRoute.SETTINGS -> {
                val presentation = homePresentation(
                    state = productState,
                    canStart = canStartIntercom,
                    audioSourceText = audioSourceText,
                    bluetoothActive = bluetoothActive,
                    wifiUnavailable = wifiUnavailable,
                    supplementalText = supplementalStatus,
                    lastStoppingPeerName = lastRealPeerName,
                    permissionRequestAttempted = permissionRequestAttempted,
                    audioReady = audioReady
                )
                title.text = activity.getString(R.string.nav_settings)
                body.text = presentation.primaryText
                status.text = audioSourceLabel(presentation.audioSourceText)
            }
            MainRoute.LOGS -> Unit
        }
    }

    private fun showPage(route: MainRoute) {
        showPage(route, focusAudio = false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPage(route: MainRoute, focusAudio: Boolean) {
        if (route == currentRoute && pageContainer.childCount > 0) {
            closeNavigation()
            return
        }
        presenceDetailsDialog?.dismiss()
        saveCurrentPageScrollAndDraft()
        stopAnimations()
        logBottomFollowPending = false
        currentRoute = route
        root.removeCallbacks(logRefresh)
        diagnosticsGeneration++
        historyReadPending = false
        exportPreparing = false
        closeNavigation()
        pageContainer.removeAllViews()
        guideAnchors.clear()
        val page = when (route) {
            MainRoute.HOME -> createHomePage()
            MainRoute.DISCOVER -> createDiscoverPage()
            MainRoute.SETTINGS -> createSettingsPage(focusAudio)
            MainRoute.LOGS -> createLogsPage()
        }
        pageContainer.addView(page)
        currentScroll = page as? ScrollView
        currentScroll?.apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> userScrollView = view as ScrollView
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        if (userScrollView === view) {
                            post {
                                if (currentScroll === view) saveCurrentPageScrollAndDraft()
                            }
                            userScrollView = null
                        }
                    }
                }
                false
            }
            setOnScrollChangeListener { view, _, scrollY, _, _ ->
                if (userScrollView === view && currentScroll === view) {
                    saveRouteScrollPosition(scrollPositions, currentRoute, scrollY)
                }
            }
        }
        bindCurrentPage()
        if (currentRoute == MainRoute.LOGS) {
            renderLogs()
            refreshPersistedLogs()
            if (diagnostics != null && diagnosticsActive) root.postDelayed(logRefresh, 2_000)
        } else {
            renderCurrentPage()
        }
        if (!focusAudio) restoreCurrentScroll()
        updateNavigationSelection()
        updateAdaptiveLayout()
    }

    private fun bindCurrentPage() {
        when (currentRoute) {
            MainRoute.HOME -> Unit
            MainRoute.DISCOVER -> bindDiscover()
            MainRoute.SETTINGS -> bindSettings()
            MainRoute.LOGS -> bindLogs()
        }
    }

    private fun renderCurrentPage() {
        when (currentRoute) {
            MainRoute.HOME -> renderHome()
            MainRoute.DISCOVER -> renderDiscover()
            MainRoute.SETTINGS -> renderSettings()
            MainRoute.LOGS -> Unit
        }
        refreshGuide()
    }

    private fun refreshGuide() {
        onboarding?.update(GuideFacts(productState, currentRoute, canStartIntercom,
            permissionRequestAttempted, wifiUnavailable, incomingConfirmationVisible, audioReady))
    }

    private fun performGuideAction(target: GuideTarget) {
        when (target) {
            GuideTarget.PERMISSION -> onRequestCorePermissions()
            GuideTarget.PERMISSION_SETTINGS -> onOpenPermissionSettings()
            GuideTarget.WIFI -> onOpenWifiSettings()
            GuideTarget.START -> if (productState is IntercomState.Offline && canStartIntercom && !wifiUnavailable) onToggleIntercom()
            GuideTarget.DISCOVER -> if (productState is IntercomState.Discovering) showPage(MainRoute.DISCOVER)
            GuideTarget.SCAN -> if (productState is IntercomState.Discovering && !wifiUnavailable && !discoverConnectAwaitingState) onRequestDiscoveryRefresh()
        }
        refreshGuide()
    }

    private fun recordGuideAnchor(host: View, target: GuideTarget, bounds: androidx.compose.ui.geometry.Rect) {
        guideAnchors[target] = GuideAnchor(host, android.graphics.RectF(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    private fun selectTheme(theme: MotoComThemeMode) {
        if (theme == appTheme.value) return
        if (!onThemeSelected(theme)) return
        appTheme.value = theme
        applyThemeToWindow(theme)
    }

    private fun createHomePage(): ScrollView = ScrollView(activity).apply {
        id = R.id.home_scroll
        clipToPadding = false
        isFillViewport = false
        addView(
            ComposeView(activity).apply homeHost@ {
                id = R.id.home_content
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setContent {
                    MotoComTheme(appTheme.value) {
                        MotoComHomeScreen(
                            state = homeUiState.value,
                            audioLevel = homeAudioLevel.floatValue,
                            onPrimaryAction = onToggleIntercom,
                            onDiscover = { showPage(MainRoute.DISCOVER) },
                            onPermissionGrant = onRequestCorePermissions,
                            onPermissionSettings = onOpenPermissionSettings,
                            onWifiSettings = onOpenWifiSettings,
                            onMute = onSetMuted,
                            onAudioSettings = { showPage(MainRoute.SETTINGS, focusAudio = true) },
                            onVox = { showPage(MainRoute.SETTINGS) },
                            radarPreviewPeers = homeRadarPeers.value,
                            onRadarPreviewPeerClick = ::connectFromHomeRadar,
                            onGuideAnchor = { target, bounds -> recordGuideAnchor(this@homeHost, target, bounds) }
                        )
                    }
                }
            }
        )
    }

    private fun renderHome() {
        val optionalPermission = optionalPermissionPresentation(
            bluetoothPermissionMissing = bluetoothPermissionMissing,
            notificationPermissionMissing = notificationPermissionMissing,
            bluetoothActive = bluetoothActive
        )
        val presentation = homePresentation(
            state = productState,
            canStart = canStartIntercom,
            audioSourceText = visibleAudioSourceText(
                status = audioSourceText,
                bluetooth = bluetoothActive,
                bluetoothPermissionMissing = bluetoothPermissionMissing
            ),
            bluetoothActive = optionalPermission.bluetoothActive,
            wifiUnavailable = wifiUnavailable,
            supplementalText = supplementalStatus,
            lastStoppingPeerName = lastRealPeerName,
            discoverCtaNeedsReselect = discoverCtaNeedsReselect,
            permissionRequestAttempted = permissionRequestAttempted,
            audioReady = audioReady
        )
        homeUiState.value = HomeScreenUiState(
            primaryText = presentation.primaryText,
            supplementalText = presentation.supplementalText,
            peerText = presentation.peerText,
            primaryActionLabel = presentation.primaryActionLabel,
            primaryActionEnabled = presentation.primaryActionEnabled,
            disabledReason = presentation.disabledReason,
            showPermissionGrantCta = presentation.showPermissionGrantCta,
            showPermissionSettingsCta = presentation.showPermissionSettingsCta,
            showWifiSettingsCta = presentation.showWifiSettingsCta,
            discoverCtaLabel = presentation.discoverCtaLabel,
            showDiscoverCta = presentation.showDiscoverCta,
            audioSourceText = presentation.audioSourceText,
            discovering = productState is IntercomState.Discovering,
            connected = productState is IntercomState.Connected && audioReady,
            statusLabel = productStatusLabel(productState),
            animationsEnabled = animationsEnabled(),
            muted = audioControlSnapshot.controls.muted,
            muteEnabled = productState !is IntercomState.Offline &&
                productState !is IntercomState.Stopping,
            voxEnabled = audioControlSnapshot.controls.voxEnabled,
            voxSensitivity = audioControlSnapshot.controls.voxSensitivity,
            voxState = audioControlSnapshot.voxState
        )
        homeRadarPeers.value = if (productState is IntercomState.Discovering) {
            val presentation = discoverPresentation(
                state = productState,
                presences = presences,
                connectPending = discoverConnectAwaitingState
            )
            presentation.orderedPresences.mapIndexedNotNull { index, presence ->
                val card = presentation.cards[index]
                if (!card.connectVisible) {
                    null
                } else {
                    RadarPreviewPeer(
                        id = radarPeerId(presence),
                        label = card.title,
                        enabled = card.connectEnabled
                    )
                }
            }
        } else {
            emptyList()
        }
    }

    private fun bindDiscover() {
        // Discover is rendered by Compose; event boundaries remain owned by MainScreen.
    }

    private fun renderDiscover() {
        val presentation = discoverPresentation(
            state = productState,
            presences = presences,
            wifiUnavailable = wifiUnavailable,
            canStart = canStartIntercom,
            connectPending = discoverConnectAwaitingState
        )
        val stateText = discoverStateText(presentation)
        discoverUiState.value = DiscoverScreenUiState(
            presentation = presentation,
            stateText = stateText,
            supplementalText = supplementalStatus,
            radarRunning = productState is IntercomState.Discovering,
            animationsEnabled = animationsEnabled(),
            statusLabel = productStatusLabel(productState),
            rescanEnabled = productState is IntercomState.Discovering &&
                !wifiUnavailable &&
                !discoverConnectAwaitingState
        )
    }

    private fun createDiscoverPage(): ScrollView = ScrollView(activity).apply {
        id = R.id.discover_scroll
        clipToPadding = false
        isFillViewport = false
        addView(
            ComposeView(activity).apply discoverHost@ {
                id = R.id.discover_content
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setContent {
                    MotoComTheme(appTheme.value) {
                        MotoComDiscoverScreen(
                            state = discoverUiState.value,
                            onBack = { showPage(MainRoute.HOME) },
                            onHelp = ::showHelpDialog,
                            onStart = onToggleIntercom,
                            onWifiSettings = onOpenWifiSettings,
                            onRescan = onRequestDiscoveryRefresh,
                            onSelectPresence = ::showPresenceDetails,
                            onConnect = ::connectFromDiscover,
                            onGuideAnchor = { target, bounds -> recordGuideAnchor(this@discoverHost, target, bounds) },
                            onManagePairing = ::showPairingManagement
                        )
                    }
                }
            }
        )
    }

    private fun showPresenceDetails(presence: RiderPresence) {
        if (windowWidthClass == MainWindowWidthClass.Expanded) {
            val deviceId = presence.deviceId
            val sessionId = presence.sessionId
            if (deviceId != null && sessionId != null) {
                expandedSelectedPresence = PendingPresenceSelection(deviceId, sessionId)
                updateExpandedDetailPane()
                return
            }
        }
        if (presenceDetailsDialog?.isShowing == true) return
        val presentation = discoverPresentation(productState, presences)
        val index = presentation.orderedPresences.indexOf(presence)
        if (index < 0) return
        val card = presentation.cards[index]
        presenceDetailsDialog = AlertDialog.Builder(activity)
            .setTitle(card.title)
            .setMessage(activity.getString(
                R.string.discover_details_message,
                card.deviceText,
                card.transportText,
                activity.getString(if (card.paired) R.string.discover_fact_paired else R.string.discover_not_paired)
            ))
            .setPositiveButton(R.string.help_close, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { presenceDetailsDialog = null }
                dialog.show()
            }
    }

    private fun radarPeerId(presence: RiderPresence): String =
        "${presence.deviceId.orEmpty()}|${presence.sessionId?.value.orEmpty()}"

    private fun connectFromHomeRadar(peer: RadarPreviewPeer) {
        val presence = presences.firstOrNull {
            it.isSelectableForUi() && radarPeerId(it) == peer.id
        } ?: run {
            renderHome()
            return
        }
        requestPresenceConnection(presence, MainRoute.HOME)
    }

    private fun connectFromDiscover(presence: RiderPresence) {
        requestPresenceConnection(presence, MainRoute.DISCOVER)
    }

    private fun requestPresenceConnection(presence: RiderPresence, route: MainRoute) {
        val currentPresence = presences.firstOrNull {
            it.deviceId == presence.deviceId &&
                it.sessionId == presence.sessionId &&
                it.isSelectableForUi()
        }
        if (
            currentRoute != route ||
            productState !is IntercomState.Discovering ||
            discoverConnectAwaitingState ||
            currentPresence == null
        ) {
            if (currentPresence == null && currentRoute == route) {
                when (route) {
                    MainRoute.HOME -> renderHome()
                    MainRoute.DISCOVER -> renderDiscover()
                    else -> Unit
                }
            }
            return
        }
        val pendingSelection = PendingPresenceSelection(
            deviceId = requireNotNull(currentPresence.deviceId),
            sessionId = requireNotNull(currentPresence.sessionId)
        )
        val request = PresenceConnectRequest(
            runtimeSessionId = requireNotNull(productState.runtimeSessionId),
            targetDeviceId = pendingSelection.deviceId,
            targetSessionId = pendingSelection.sessionId,
            requestId = UUID.randomUUID().toString()
        )
        cancelPendingPresenceExpiry()
        discoverConnectAwaitingState = true
        pendingPresenceSelection = pendingSelection
        pendingConnectRequest = request
        pendingConnectAdmitted = false
        val dispatched = onConnectPresenceRequest(currentPresence, request)
        if (dispatched) {
            when (route) {
                MainRoute.HOME -> renderHome()
                MainRoute.DISCOVER -> renderDiscover()
                else -> Unit
            }
        } else if (pendingConnectRequest == request && productState is IntercomState.Discovering &&
            productState.runtimeSessionId == request.runtimeSessionId) {
            clearPendingDiscoverConnect()
            feedbackAfterDiscoverConnect(false)?.let(::setStatus)
        }
    }

    private fun showPairingManagement(presence: RiderPresence) {
        if (incomingConfirmationVisible || currentRoute != MainRoute.DISCOVER) return
        val deviceId = presence.deviceId ?: return
        val currentPresence = currentPairedPresence(deviceId) ?: return
        val pairing = requireNotNull(currentPresence.pairing)
        val riderName = currentPresence.displayName.ifBlank {
            activity.getString(R.string.pairing_rider_fallback)
        }
        val deviceName = currentPresence.deviceName.ifBlank {
            activity.getString(R.string.version_unavailable)
        }
        val requestedPreferred = !pairing.isPreferred

        dismissTransientDialogs()
        activePairingDeviceId = deviceId
        pairingManagementDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pairing_manage_title)
            .setMessage(
                activity.getString(
                    R.string.pairing_manage_message,
                    riderName,
                    deviceName
                )
            )
            .setPositiveButton(
                if (requestedPreferred) {
                    R.string.pairing_set_preferred
                } else {
                    R.string.pairing_clear_preferred
                }
            ) { _, _ ->
                val current = currentPairedPresence(deviceId) ?: return@setPositiveButton
                if (current.pairing?.isPreferred == requestedPreferred) return@setPositiveButton
                if (!onSetPairingPreferred(deviceId, requestedPreferred)) {
                    setStatus(SERVICE_UNAVAILABLE_STATUS)
                }
            }
            .setNeutralButton(R.string.pairing_forget) { _, _ ->
                currentPairedPresence(deviceId)?.let(::showForgetPairingConfirmation)
            }
            .setNegativeButton(R.string.pairing_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (pairingManagementDialog === dialog) {
                        pairingManagementDialog = null
                        if (forgetPairingDialog == null) activePairingDeviceId = null
                    }
                }
                dialog.show()
            }
    }

    private fun showForgetPairingConfirmation(presence: RiderPresence) {
        val deviceId = presence.deviceId ?: return
        val currentPresence = currentPairedPresence(deviceId) ?: return
        val riderName = currentPresence.displayName.ifBlank {
            activity.getString(R.string.pairing_rider_fallback)
        }

        dismissPairingDialogs()
        activePairingDeviceId = deviceId
        forgetPairingDialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.pairing_forget_title, riderName))
            .setMessage(
                activity.getString(
                    R.string.pairing_forget_message,
                    activity.getString(R.string.pairing_forget_connection_note)
                )
            )
            .setPositiveButton(R.string.pairing_forget) { _, _ ->
                if (currentPairedPresence(deviceId) == null) return@setPositiveButton
                if (!onForgetPairing(deviceId)) setStatus(SERVICE_UNAVAILABLE_STATUS)
            }
            .setNegativeButton(R.string.pairing_cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener {
                    if (forgetPairingDialog === dialog) {
                        forgetPairingDialog = null
                        if (pairingManagementDialog == null) activePairingDeviceId = null
                    }
                }
                dialog.show()
            }
    }

    private fun currentPairedPresence(deviceId: String): RiderPresence? =
        presences.firstOrNull {
            it.deviceId == deviceId && it.pairing?.remoteDeviceId == deviceId
        }

    private fun dismissPairingDialogs() {
        pairingManagementDialog?.dismiss()
        pairingManagementDialog = null
        forgetPairingDialog?.dismiss()
        forgetPairingDialog = null
        activePairingDeviceId = null
    }

    private fun createSettingsPage(focusAudio: Boolean): ScrollView = ScrollView(activity).apply {
        val scrollHost = this
        var audioFocusPending = focusAudio
        id = R.id.settings_scroll
        clipToPadding = false
        isFillViewport = false
        addView(ComposeView(activity).apply {
            id = R.id.settings_content
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MotoComTheme(appTheme.value) {
                    MotoComSettingsScreen(
                        state = settingsUiState.value,
                        onBack = { showPage(MainRoute.HOME) },
                        theme = appTheme.value,
                        onThemeSelected = ::selectTheme,
                        onNicknameChanged = { value ->
                            settingsNicknameDraft = value
                            settingsUiState.value = settingsUiState.value.copy(nickname = value)
                        },
                        onSaveNickname = { saveNickname() },
                        onOptionalPermission = onRequestOptionalPermissions,
                        onLogs = { showPage(MainRoute.LOGS) },
                        onAbout = ::showAboutDialog,
                        onHelp = ::showHelpDialog,
                        onVoxEnabledChanged = onSetVoxEnabled,
                        onVoxSensitivityChanged = onSetVoxSensitivity,
                        onAudioRouteSelected = onSelectAudioRoute,
                        onRnnoiseEnabledChanged = onSetRnnoiseEnabled,
                        onAutomaticReconnectChanged = onAutomaticReconnectChanged,
                        onGuide = {
                            showPage(MainRoute.HOME)
                            onboarding?.replay()
                        },
                        onAudioSectionPositioned = { offset ->
                            if (audioFocusPending) {
                                audioFocusPending = false
                                saveRouteScrollPosition(scrollPositions, MainRoute.SETTINGS, offset)
                                scrollHost.post {
                                    if (currentScroll === scrollHost) scrollHost.scrollTo(0, offset)
                                }
                            }
                        }
                    )
                }
            }
        })
    }

    private fun createLogsPage(): ScrollView = ScrollView(activity).apply {
        id = R.id.logs_scroll
        clipToPadding = false
        isFillViewport = false
        addView(ComposeView(activity).apply {
            id = R.id.logs_content
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MotoComTheme(appTheme.value) {
                    MotoComLogsScreen(
                        state = logsUiState.value,
                        onBack = { showPage(MainRoute.SETTINGS) },
                        onCopy = ::copyLogs,
                        onExport = ::exportLogs,
                        onClose = { showPage(MainRoute.SETTINGS) }
                    )
                }
            }
        })
    }

    private fun bindSettings() {
        // Settings is rendered by Compose; callbacks are passed by createSettingsPage().
    }

    private fun renderSettings() {
        val optionalPermission = optionalPermissionPresentation(
            bluetoothPermissionMissing = bluetoothPermissionMissing,
            notificationPermissionMissing = notificationPermissionMissing,
            bluetoothActive = bluetoothActive
        )
        val presentation = homePresentation(
            state = productState,
            canStart = canStartIntercom,
            audioSourceText = visibleAudioSourceText(
                status = audioSourceText,
                bluetooth = bluetoothActive,
                bluetoothPermissionMissing = bluetoothPermissionMissing
            ),
            bluetoothActive = optionalPermission.bluetoothActive,
            supplementalText = supplementalStatus,
            lastStoppingPeerName = lastRealPeerName,
            permissionRequestAttempted = permissionRequestAttempted,
            audioReady = audioReady
        )
        settingsUiState.value = SettingsScreenUiState(
            nickname = settingsNicknameDraft,
            nicknameFeedback = settingsUiState.value.nicknameFeedback,
            audioSource = presentation.audioSourceText,
            productState = settingsProductStateText(productState, audioReady),
            optionalPermissionNotice = optionalPermission.noticeText,
            showOptionalPermissionCta = optionalPermission.showGrantCta,
            version = activity.getString(R.string.settings_version_summary, currentVersionName()),
            voxEnabled = audioControlSnapshot.controls.voxEnabled,
            voxSensitivity = audioControlSnapshot.controls.voxSensitivity,
            preferredAudioRoute = preferredAudioRoute,
            automaticReconnectEnabled = automaticReconnectEnabled,
            rnnoiseEnabled = audioControlSnapshot.controls.rnnoiseEnabled,
            canChangeNoiseReduction = productState == IntercomState.Offline
        )
    }

    private fun saveNickname() {
        saveNicknameValue(settingsNicknameDraft)
    }

    private fun saveNicknameValue(input: String) {
        settingsNicknameDraft = input
        when (val validation = validateNickname(input)) {
            is NicknameValidation.Invalid -> {
                settingsUiState.value = settingsUiState.value.copy(nickname = input, nicknameFeedback = validation.message)
            }
            is NicknameValidation.Valid -> {
                val saved = onSaveRiderName(validation.value)
                settingsUiState.value = settingsUiState.value.copy(
                    nickname = if (saved) validation.value else input,
                    nicknameFeedback = nicknameSaveFeedback(saved, productState)
                )
                if (saved) {
                    settingsNicknameDraft = validation.value
                }
            }
        }
    }

    private fun bindLogs() {
        // Logs is rendered by Compose; clipboard ownership remains in MainScreen.
    }

    private fun renderLogs() {
        val snapshot = logBuffer.snapshot()
        logsUiState.value = LogsScreenUiState(
            scopeText = LOGS_SCOPE_TEXT,
            logText = copyableLogText(snapshot).ifBlank { activity.getString(R.string.logs_empty) },
            copyEnabled = snapshot.isNotEmpty(),
            exportEnabled = diagnostics != null && snapshot.isNotEmpty(),
            exporting = exportPreparing,
            errorText = diagnosticError
        )
    }

    fun resumeDiagnostics() {
        diagnosticsActive = true
        if (currentRoute == MainRoute.LOGS && diagnostics != null) {
            refreshPersistedLogs()
            root.removeCallbacks(logRefresh)
            root.postDelayed(logRefresh, 2_000)
        }
    }

    fun pauseDiagnostics() {
        diagnosticsActive = false
        diagnosticsGeneration++
        historyReadPending = false
        exportPreparing = false
        root.removeCallbacks(logRefresh)
    }

    fun closeDiagnostics() {
        abandonPresenceConnectRequest()
        pauseDiagnostics()
        diagnosticsClosed = true
    }

    private fun refreshPersistedLogs() {
        val source = diagnostics ?: return
        if (historyReadPending || !diagnosticsActive || diagnosticsClosed || currentRoute != MainRoute.LOGS) return
        historyReadPending = true
        val generation = diagnosticsGeneration
        val weakScreen = WeakReference(this)
        source.recent { result ->
            weakScreen.get()?.root?.post {
                val screen = weakScreen.get() ?: return@post
                if (!screen.acceptDiagnosticResult(generation)) return@post
                screen.historyReadPending = false
                result.fold(
                    onSuccess = { screen.logBuffer.replace(it); screen.diagnosticError = null },
                    onFailure = { screen.diagnosticError = screen.activity.getString(R.string.logs_read_failed) }
                )
                screen.renderLogs()
            }
        }
    }

    private fun acceptDiagnosticResult(generation: Long): Boolean =
        !diagnosticsClosed && diagnosticsActive && currentRoute == MainRoute.LOGS && generation == diagnosticsGeneration

    private fun exportLogs() {
        val source = diagnostics ?: return
        if (exportPreparing || !diagnosticsActive || diagnosticsClosed) return
        exportPreparing = true
        diagnosticError = null
        renderLogs()
        val generation = diagnosticsGeneration
        val weakScreen = WeakReference(this)
        source.export { result ->
            weakScreen.get()?.root?.post {
                val screen = weakScreen.get() ?: return@post
                if (!screen.acceptDiagnosticResult(generation)) return@post
                screen.exportPreparing = false
                val launched = result.mapCatching { screen.activity.startActivity(diagnosticExportChooser(screen.activity, it)) }
                if (launched.isFailure) {
                    screen.diagnosticError = screen.activity.getString(R.string.logs_export_failed)
                    Toast.makeText(screen.activity, screen.diagnosticError, Toast.LENGTH_SHORT).show()
                }
                screen.renderLogs()
            }
        }
    }

    private fun copyLogs() {
        val snapshot = logBuffer.snapshot()
        if (snapshot.isEmpty()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MotoCom logs", copyableLogText(snapshot)))
        Toast.makeText(activity, LOGS_COPIED_FEEDBACK, Toast.LENGTH_SHORT).show()
    }

    private fun showHelpDialog() {
        if (!shouldShowTransientDialog(helpDialog?.isShowing == true)) return
        helpDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.help_send_feedback) { _, _ ->
                onSendFeedback(currentVersionName())
            }
            .setNegativeButton(R.string.help_close, null)
            .setNeutralButton(R.string.guide_replay) { _, _ ->
                dismissHelpDialog()
                showPage(MainRoute.HOME)
                onboarding?.replay()
            }
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { helpDialog = null }
                dialog.show()
            }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setMessage(activity.getString(R.string.about_message, currentVersionName()))
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun currentVersionName(): String =
        displayVersionName(
            packageVersion = runCatching {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            }.getOrNull(),
            unavailableText = activity.getString(R.string.version_unavailable)
        )

    private fun currentChrome(): RouteChrome = RouteChrome(
        route = currentRoute,
        navigationOpen = navigationPanel.visibility == View.VISIBLE,
        transientDialogVisible = helpDialog?.isShowing == true ||
            presenceDetailsDialog?.isShowing == true ||
            pairingManagementDialog?.isShowing == true ||
            forgetPairingDialog?.isShowing == true,
        incomingConfirmationVisible = incomingConfirmationVisible
    )

    private fun saveCurrentPageScrollAndDraft() {
        currentScroll?.let {
            saveRouteScrollPosition(scrollPositions, currentRoute, it.scrollY)
        }
    }

    private fun restoreCurrentScroll(
        finalizeActivityRestore: Boolean = false
    ) {
        val scroll = currentScroll ?: return
        val scrollY = pendingRestoredScrollPositions[currentRoute]
            ?: restoredRouteScrollPosition(scrollPositions, currentRoute)
        val needsSecondPass = finalizeActivityRestore || pendingRestoredScrollPositions.isNotEmpty()
        scroll.post {
            if (currentScroll !== scroll) {
                return@post
            }
            scroll.scrollTo(0, scrollY)
            if (!needsSecondPass) return@post
            // Android may restore a focused EditText after the first posted
            // pass and move its parent ScrollView. Re-apply once more after
            // that state restoration.
            scroll.post {
                if (currentScroll === scroll) scroll.scrollTo(0, scrollY)
                if (finalizeActivityRestore) pendingRestoredScrollPositions.clear()
            }
        }
    }

    private fun restoreScrollPositions(savedState: Bundle?) {
        MainRoute.entries.forEach { route ->
            val restored = savedState?.getInt(scrollKey(route), 0)?.coerceAtLeast(0) ?: 0
            saveRouteScrollPosition(
                scrollPositions,
                route,
                restored
            )
            if (savedState?.containsKey(scrollKey(route)) == true) {
                pendingRestoredScrollPositions[route] = restored
            }
        }
    }

    private fun updateNavigationSelection() {
        val homeSelected = currentRoute == MainRoute.HOME
        val discoverSelected = currentRoute == MainRoute.DISCOVER
        val settingsSelected = currentRoute == MainRoute.SETTINGS || currentRoute == MainRoute.LOGS
        listOf(R.id.nav_home_button, R.id.adaptive_nav_home_button, R.id.bottom_nav_home_button)
            .forEach { setNavSelected(it, homeSelected) }
        listOf(
            R.id.nav_discover_button,
            R.id.adaptive_nav_discover_button,
            R.id.bottom_nav_discover_button
        ).forEach { setNavSelected(it, discoverSelected) }
        listOf(
            R.id.nav_settings_button,
            R.id.adaptive_nav_settings_button,
            R.id.bottom_nav_settings_button
        ).forEach { setNavSelected(it, settingsSelected) }
    }

    private fun setNavSelected(id: Int, selected: Boolean) {
        root.findViewById<Button?>(id)?.apply {
            isSelected = selected
            val isBottomItem = id == R.id.bottom_nav_home_button ||
                id == R.id.bottom_nav_discover_button || id == R.id.bottom_nav_settings_button
            styleNavItem(this, selected, isBottomItem)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                contentDescription = null
                stateDescription = if (selected) {
                    activity.getString(R.string.nav_selected_description)
                } else {
                    null
                }
            } else {
                contentDescription = if (selected) {
                    activity.getString(
                        R.string.nav_selected_content_description,
                        text.toString()
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun rememberPeer(peer: PeerIdentity) {
        peer.displayNameForUi()?.let { lastRealPeerName = it }
    }

    private fun PeerIdentity.displayNameForUi(): String? =
        nickname.takeIf(String::isNotBlank)

    private fun constrainWidth(contentId: Int) {
        val content = pageContainer.findViewById<View>(contentId)
        content.post {
            val parentWidth = (content.parent as? View)?.width ?: return@post
            val horizontalPadding = activity.resources.getDimensionPixelSize(R.dimen.motocom_page_horizontal_padding) * 2
            val target = constrainedContentWidth(
                parentWidth,
                horizontalPadding,
                activity.resources.getDimensionPixelSize(R.dimen.motocom_content_max_width)
            )
            val params = content.layoutParams as ViewGroup.MarginLayoutParams
            params.width = target
            params.leftMargin = ((parentWidth - target) / 2).coerceAtLeast(0)
            params.rightMargin = params.leftMargin
            content.layoutParams = params
        }
    }

    private fun constrainCurrentPageWidth() {
        val contentId = when (currentRoute) {
            MainRoute.HOME -> R.id.home_content
            MainRoute.DISCOVER -> R.id.discover_content
            MainRoute.SETTINGS -> R.id.settings_content
            MainRoute.LOGS -> R.id.logs_content
        }
        if (pageContainer.findViewById<View>(contentId) != null) {
            constrainWidth(contentId)
        }
    }

    private fun constrainNavigationPanelWidth() {
        val availableWidth = root.width - root.paddingLeft - root.paddingRight
        val params = navigationPanel.layoutParams
        params.width = constrainedPanelWidth(
            availableWidth,
            activity.resources.getDimensionPixelSize(R.dimen.motocom_panel_width)
        )
        navigationPanel.layoutParams = params
    }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun TextView.setOptionalText(value: String?) {
        text = value.orEmpty()
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun ScrollView.isAtBottom(): Boolean {
        val child = getChildAt(0) ?: return true
        return shouldFollowLogBottom(scrollY, height, child.bottom, dp(16))
    }

    private fun TextView.isAtBottom(): Boolean =
        shouldFollowLogBottom(scrollY, height, logContentBottom(), dp(16))

    private fun TextView.scrollToBottom() {
        scrollTo(0, (logContentBottom() - height).coerceAtLeast(0))
    }

    private fun TextView.logContentBottom(): Int =
        (layout?.height ?: 0) + paddingTop + paddingBottom

    private fun TextView.maximumScrollY(): Int =
        (logContentBottom() - height).coerceAtLeast(0)

    private fun Activity.getColorCompat(id: Int): Int =
        getColor(id)

    private fun Activity.getDrawableCompat(id: Int) =
        getDrawable(id)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun scrollKey(route: MainRoute): String = "$KEY_SCROLL_PREFIX${route.name}"

    private data class PendingPresenceSelection(
        val deviceId: String,
        val sessionId: RuntimeSessionId
    )

    private fun RiderPresence.matchesPendingSelection(
        pending: PendingPresenceSelection
    ): Boolean =
        deviceId == pending.deviceId &&
            sessionId == pending.sessionId &&
            isSelectableForUi()

    private companion object {
        const val KEY_ROUTE = "main_route"
        const val KEY_NICKNAME_DRAFT = "nickname_draft"
        const val KEY_EXPANDED_SELECTED_DEVICE_ID = "expanded_selected_device_id"
        const val KEY_EXPANDED_SELECTED_SESSION_ID = "expanded_selected_session_id"
        const val KEY_SCROLL_PREFIX = "scroll_"
        const val DISCOVER_CONNECT_PENDING_GRACE_MS = 2_000L
    }
}

@SuppressLint("NewApi")
internal fun calculateSafeWindowInsets(insets: WindowInsets): IntArray =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val safeInsets = insets.getInsets(
            WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout() or
                WindowInsets.Type.systemGestures() or
                WindowInsets.Type.mandatorySystemGestures()
        )
        intArrayOf(
            safeInsets.left,
            safeInsets.top,
            safeInsets.right,
            safeInsets.bottom
        )
    } else {
        @Suppress("DEPRECATION")
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            insets.displayCutout
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val systemGestures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insets.systemGestureInsets
        } else {
            null
        }
        @Suppress("DEPRECATION")
        val mandatoryGestures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insets.mandatorySystemGestureInsets
        } else {
            null
        }
        @Suppress("DEPRECATION")
        intArrayOf(
            maxOf(
                insets.systemWindowInsetLeft,
                cutout?.safeInsetLeft ?: 0,
                systemGestures?.left ?: 0,
                mandatoryGestures?.left ?: 0
            ),
            maxOf(
                insets.systemWindowInsetTop,
                cutout?.safeInsetTop ?: 0,
                systemGestures?.top ?: 0,
                mandatoryGestures?.top ?: 0
            ),
            maxOf(
                insets.systemWindowInsetRight,
                cutout?.safeInsetRight ?: 0,
                systemGestures?.right ?: 0,
                mandatoryGestures?.right ?: 0
            ),
            maxOf(
                insets.systemWindowInsetBottom,
                cutout?.safeInsetBottom ?: 0,
                systemGestures?.bottom ?: 0,
                mandatoryGestures?.bottom ?: 0
            )
        )
    }
