package com.kuma.motointercom

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
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
import java.util.EnumMap

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
    private val onOpenPermissionSettings: () -> Unit
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
    private val homeUiState = mutableStateOf(
        HomeScreenUiState(
            primaryText = "",
            detailText = "",
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
            plannedTransportText = "",
            connectedTransportText = "",
            webRtcText = "",
            bluetoothText = "",
            voxText = "",
            discovering = false,
            connected = false
        )
    )
    private val homeAudioLevel = mutableFloatStateOf(0f)
    private val discoverUiState = mutableStateOf(
        DiscoverScreenUiState(
            presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
            stateText = "",
            supplementalText = null,
            emptyText = "",
            radarRunning = false
        )
    )
    private val settingsUiState = mutableStateOf(
        SettingsScreenUiState("", "", "", "", "", "", "", null, false, "")
    )
    private val logsUiState = mutableStateOf(LogsScreenUiState("", "", false))

    private var currentRoute: MainRoute = restoreMainRoute(savedState?.getString(KEY_ROUTE))
    private var windowWidthClass: MainWindowWidthClass = MainWindowWidthClass.Compact
    private var currentScroll: ScrollView? = null
    private var productState: IntercomState = IntercomState.Offline
    private var canStartIntercom = false
    private var supplementalStatus: String? = null
    private var permissionStatus: String? = null
    private var discoverCtaNeedsReselect = false
    private var audioSourceText = AUDIO_SOURCE_STANDBY_TEXT
    private var bluetoothActive = false
    private var wifiUnavailable = false
    private var bluetoothPermissionMissing = false
    private var notificationPermissionMissing = false
    private var presences = emptyList<RiderPresence>()
    private var lastRenderedDiscoverPresentation: DiscoverPresentation? = null
    private var lastRealPeerName: String? = null
    private var discoverConnectAwaitingState = false
    private var pendingPresenceSelection: PendingPresenceSelection? = null
    private var settingsNicknameDraft = restoreNicknameDraft(
        savedState?.getString(KEY_NICKNAME_DRAFT),
        initialRiderName
    )
    private var placeholderDialog: AlertDialog? = null
    private var navigationFocusReturn: View? = null
    private var incomingConfirmationVisible = false
    private var restoreSettingsAudio = false
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
        return when (val action = resolveBackNavigation(currentChrome())) {
            BackNavigation.CloseNavigation -> {
                closeNavigation()
                true
            }
            BackNavigation.DismissPlaceholder -> {
                dismissPlaceholderDialog()
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

    fun dismissPlaceholderDialog() {
        placeholderDialog?.dismiss()
        placeholderDialog = null
    }

    fun setIncomingConfirmationVisible(visible: Boolean) {
        incomingConfirmationVisible = visible
    }

    fun setIntercomState(state: IntercomState, canStart: Boolean) {
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
            discoverConnectAwaitingState = false
            pendingPresenceSelection = null
        }
        if (navigateAfterDiscoverConnect) {
            discoverConnectAwaitingState = false
            pendingPresenceSelection = null
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

    fun setIntercomError(message: String) {
        discoverConnectAwaitingState = false
        pendingPresenceSelection = null
        permissionStatus = null
        supplementalStatus = message
        discoverCtaNeedsReselect = productState != IntercomState.Offline
        appendLog("错误：$message")
        renderCurrentPage()
    }

    fun setStatus(message: String, appendLog: Boolean = true) {
        permissionStatus = null
        supplementalStatus = message
        if (appendLog) appendLog(message)
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

    fun setAudioSource(status: String, bluetooth: Boolean) {
        audioSourceText = audioSourcePresentation(status, bluetooth)
        bluetoothActive = bluetooth
        renderCurrentPage()
    }

    fun clearServiceOwnedFacts() {
        audioSourceText = AUDIO_SOURCE_STANDBY_TEXT
        bluetoothActive = false
        presences = emptyList()
        lastRealPeerName = null
        discoverConnectAwaitingState = false
        pendingPresenceSelection = null
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
        val pending = pendingPresenceSelection
        if (
            discoverConnectAwaitingState &&
            pending != null &&
            presences.none { it.matchesPendingSelection(pending) }
        ) {
            discoverConnectAwaitingState = false
            pendingPresenceSelection = null
        }
        when (currentRoute) {
            MainRoute.DISCOVER -> renderDiscover()
            MainRoute.SETTINGS -> renderSettings()
            else -> Unit
        }
        updateExpandedDetailPane()
    }

    fun setAudioLevel(level: Float) {
        if (currentRoute != MainRoute.HOME || !animationsEnabled()) return
        homeAudioLevel.floatValue = level.coerceIn(0f, 1f)
    }

    fun appendLog(message: String) {
        logBuffer.append(message)
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
        pageContainer.findViewById<RippleView?>(R.id.discover_radar_ripple)?.stop()
    }

    @SuppressLint("NewApi")
    private fun configureWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
        }
        activity.window.statusBarColor = activity.getColorCompat(R.color.motocom_background)
        activity.window.navigationBarColor = activity.getColorCompat(R.color.motocom_background)
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.decorView.systemUiVisibility = flags
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

    private fun showNavigation() {
        if (windowWidthClass != MainWindowWidthClass.Compact) return
        navigationFocusReturn = root.findFocus()
        constrainNavigationPanelWidth()
        pageContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        navigationScrim.visibility = View.VISIBLE
        navigationScrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        navigationPanel.visibility = View.VISIBLE
        navigationPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        updateNavigationSelection()
        navigationPanel.findViewById<View>(
            when (currentRoute) {
                MainRoute.HOME -> R.id.nav_home_button
                MainRoute.DISCOVER -> R.id.nav_discover_button
                MainRoute.SETTINGS,
                MainRoute.LOGS -> R.id.nav_settings_button
            }
        )?.requestFocus()
    }

    private fun closeNavigation() {
        val focusToRestore = navigationFocusReturn
        navigationFocusReturn = null
        navigationScrim.visibility = View.GONE
        navigationScrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        navigationPanel.visibility = View.GONE
        navigationPanel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        pageContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        focusToRestore
            ?.takeIf { it.visibility == View.VISIBLE }
            ?.requestFocus()
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

        val isCompact = windowWidthClass == MainWindowWidthClass.Compact
        val isExpanded = windowWidthClass == MainWindowWidthClass.Expanded
        navigationRail.visibility = if (isCompact) View.GONE else View.VISIBLE
        // Compact phone layouts follow the design's top-menu/back navigation.
        // Keep the legacy host view for saved IDs and large-screen plumbing, but
        // do not add a second navigation surface below the designed phone page.
        bottomNavigation.visibility = View.GONE
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
                } ?: activity.getString(R.string.discover_one_to_one_note)
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
                    discoverCtaNeedsReselect = discoverCtaNeedsReselect
                )
                title.text = activity.getString(R.string.nav_home)
                body.text = presentation.detailText
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
                    lastStoppingPeerName = lastRealPeerName
                )
                title.text = activity.getString(R.string.nav_settings)
                body.text = activity.getString(
                    R.string.settings_state_summary,
                    presentation.primaryText
                )
                status.text = activity.getString(
                    R.string.settings_audio_summary,
                    presentation.audioSourceText,
                    bluetoothActive.toString()
                )
            }
            MainRoute.LOGS -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPage(route: MainRoute) {
        if (route == currentRoute && pageContainer.childCount > 0) {
            closeNavigation()
            return
        }
        val focusSettingsAudio = route == MainRoute.SETTINGS && restoreSettingsAudio
        saveCurrentPageScrollAndDraft()
        stopAnimations()
        logBottomFollowPending = false
        currentRoute = route
        closeNavigation()
        pageContainer.removeAllViews()
        val page = when (route) {
            MainRoute.HOME -> createHomePage()
            MainRoute.DISCOVER -> createDiscoverPage()
            MainRoute.SETTINGS -> createSettingsPage()
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
        } else {
            renderCurrentPage()
        }
        restoreCurrentScroll(skip = focusSettingsAudio)
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
    }

    private fun createHomePage(): ScrollView = ScrollView(activity).apply {
        id = R.id.home_scroll
        clipToPadding = false
        isFillViewport = false
        addView(
            ComposeView(activity).apply {
                id = R.id.home_content
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setContent {
                    MotoComTheme {
                        MotoComHomeScreen(
                            state = homeUiState.value,
                            audioLevel = homeAudioLevel.floatValue,
                            onMenu = ::showNavigation,
                            onSettings = { showPage(MainRoute.SETTINGS) },
                            onPrimaryAction = onToggleIntercom,
                            onDiscover = { showPage(MainRoute.DISCOVER) },
                            onPermissionGrant = onRequestCorePermissions,
                            onPermissionSettings = onOpenPermissionSettings,
                            onWifiSettings = onOpenWifiSettings,
                            onMute = ::showPlaceholderDialog,
                            onAudioSettings = {
                                restoreSettingsAudio = true
                                showPage(MainRoute.SETTINGS)
                            },
                            onVox = ::showPlaceholderDialog
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
            discoverCtaNeedsReselect = discoverCtaNeedsReselect
        )
        homeUiState.value = HomeScreenUiState(
            primaryText = presentation.primaryText,
            detailText = presentation.detailText,
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
            plannedTransportText = presentation.plannedTransportText,
            connectedTransportText = presentation.connectedTransportText,
            webRtcText = presentation.webRtcText,
            bluetoothText = optionalPermission.bluetoothStatusText,
            voxText = presentation.voxText,
            discovering = animationsEnabled() && productState is IntercomState.Discovering,
            connected = animationsEnabled() && productState is IntercomState.Connected,
            menuVisible = windowWidthClass == MainWindowWidthClass.Compact
        )
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
            emptyText = if (
                presentation.wifiSettingsVisible ||
                presentation.offlineStartVisible ||
                presentation.readOnlyReason != null
            ) stateText else activity.getString(R.string.discover_empty_no_presence),
            radarRunning = animationsEnabled() && productState is IntercomState.Discovering
        )
    }

    private fun createDiscoverPage(): ScrollView = ScrollView(activity).apply {
        id = R.id.discover_scroll
        clipToPadding = false
        isFillViewport = false
        addView(
            ComposeView(activity).apply {
                id = R.id.discover_content
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                )
                setContent {
                    MotoComTheme {
                        MotoComDiscoverScreen(
                            state = discoverUiState.value,
                            onBack = { showPage(MainRoute.HOME) },
                            onHelp = ::showPlaceholderDialog,
                            onStart = onToggleIntercom,
                            onWifiSettings = onOpenWifiSettings,
                            onRescan = ::showPlaceholderDialog,
                            onSelectPresence = { presence ->
                                val deviceId = presence.deviceId
                                val sessionId = presence.sessionId
                                if (deviceId != null && sessionId != null) {
                                    expandedSelectedPresence = PendingPresenceSelection(deviceId, sessionId)
                                    updateExpandedDetailPane()
                                }
                            },
                            onConnect = ::connectFromDiscover
                        )
                    }
                }
            }
        )
    }

    private fun connectFromDiscover(presence: RiderPresence) {
        val currentPresence = presences.firstOrNull {
            it.deviceId == presence.deviceId &&
                it.sessionId == presence.sessionId &&
                it.isSelectableForUi()
        }
        if (
            currentRoute != MainRoute.DISCOVER ||
            productState !is IntercomState.Discovering ||
            discoverConnectAwaitingState ||
            currentPresence == null
        ) {
            if (currentPresence == null) {
                discoverConnectAwaitingState = false
                pendingPresenceSelection = null
                renderDiscover()
            }
            return
        }
        val pendingSelection = PendingPresenceSelection(
            deviceId = requireNotNull(currentPresence.deviceId),
            sessionId = requireNotNull(currentPresence.sessionId)
        )
        discoverConnectAwaitingState = true
        pendingPresenceSelection = pendingSelection
        val dispatched = onConnectPresence(currentPresence)
        if (dispatched) {
            if (currentRoute == MainRoute.DISCOVER) renderDiscover()
        } else {
            discoverConnectAwaitingState = false
            pendingPresenceSelection = null
            feedbackAfterDiscoverConnect(false)?.let(::setStatus)
        }
    }

    private fun createSettingsPage(): ScrollView = ScrollView(activity).apply {
        id = R.id.settings_scroll
        clipToPadding = false
        isFillViewport = false
        addView(ComposeView(activity).apply {
            id = R.id.settings_content
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            setContent {
                MotoComTheme {
                    MotoComSettingsScreen(
                        state = settingsUiState.value,
                        onBack = { showPage(MainRoute.HOME) },
                        onNicknameChanged = { value ->
                            settingsNicknameDraft = value
                            settingsUiState.value = settingsUiState.value.copy(nickname = value)
                        },
                        onSaveNickname = { saveNickname() },
                        onOptionalPermission = onRequestOptionalPermissions,
                        onLogs = { showPage(MainRoute.LOGS) },
                        onAbout = ::showAboutDialog,
                        onPlaceholder = { showPlaceholderDialog() }
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
                MotoComTheme {
                    MotoComLogsScreen(
                        state = logsUiState.value,
                        onBack = { showPage(MainRoute.SETTINGS) },
                        onCopy = ::copyLogs,
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
            lastStoppingPeerName = lastRealPeerName
        )
        settingsUiState.value = SettingsScreenUiState(
            nickname = settingsNicknameDraft,
            nicknameFeedback = settingsUiState.value.nicknameFeedback,
            audioSource = activity.getString(R.string.settings_audio_summary, presentation.audioSourceText, optionalPermission.bluetoothStatusText),
            productState = activity.getString(R.string.settings_state_summary, presentation.primaryText),
            attemptFacts = activity.getString(R.string.settings_attempt_summary, presentation.plannedTransportText, presentation.connectedTransportText, presentation.webRtcText),
            discoveryCandidates = activity.getString(R.string.settings_discovery_candidates_summary, discoveryCandidateSummary(presences)),
            deviceStatus = activity.getString(R.string.settings_device_status_summary, presentation.audioSourceText, optionalPermission.bluetoothStatusText, presentation.primaryText, presentation.connectedTransportText),
            optionalPermissionNotice = optionalPermission.noticeText,
            showOptionalPermissionCta = optionalPermission.showGrantCta,
            version = activity.getString(R.string.settings_version_summary, currentVersionName())
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
            copyEnabled = snapshot.isNotEmpty()
        )
    }

    private fun copyLogs() {
        val snapshot = logBuffer.snapshot()
        if (snapshot.isEmpty()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MotoCom logs", copyableLogText(snapshot)))
        Toast.makeText(activity, LOGS_COPIED_FEEDBACK, Toast.LENGTH_SHORT).show()
    }

    private fun showPlaceholderDialog() {
        if (!shouldShowPlaceholderDialog(placeholderDialog?.isShowing == true)) return
        placeholderDialog = AlertDialog.Builder(activity)
            .setTitle(PLACEHOLDER_DIALOG_TITLE)
            .setMessage(PLACEHOLDER_DIALOG_MESSAGE)
            .setPositiveButton(PLACEHOLDER_DIALOG_BUTTON, null)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { placeholderDialog = null }
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
        placeholderVisible = placeholderDialog?.isShowing == true,
        incomingConfirmationVisible = incomingConfirmationVisible
    )

    private fun saveCurrentPageScrollAndDraft() {
        currentScroll?.let {
            saveRouteScrollPosition(scrollPositions, currentRoute, it.scrollY)
        }
    }

    private fun restoreCurrentScroll(
        skip: Boolean = false,
        finalizeActivityRestore: Boolean = false
    ) {
        if (skip) return
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
            setBackgroundResource(
                if (selected) R.drawable.motocom_pill_green else R.drawable.motocom_secondary_button
            )
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
        val roundedInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            intArrayOf(
                maxOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
                ),
                maxOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0,
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
                ),
                maxOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0,
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
                ),
                maxOf(
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
                )
            )
        } else {
            intArrayOf(0, 0, 0, 0)
        }
        intArrayOf(
            maxOf(safeInsets.left, roundedInsets[0]),
            maxOf(safeInsets.top, roundedInsets[1]),
            maxOf(safeInsets.right, roundedInsets[2]),
            maxOf(safeInsets.bottom, roundedInsets[3])
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
