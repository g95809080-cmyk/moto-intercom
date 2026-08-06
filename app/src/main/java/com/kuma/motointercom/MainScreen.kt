package com.kuma.motointercom

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
        pageContainer.findViewById<VisualizerView?>(R.id.home_visualizer)?.setAmplitude(level)
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
        pageContainer.findViewById<RippleView?>(R.id.home_ripple)?.stop()
        pageContainer.findViewById<RippleView?>(R.id.discover_radar_ripple)?.stop()
        pageContainer.findViewById<VisualizerView?>(R.id.home_visualizer)?.stop()
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
        bottomNavigation.visibility = if (isCompact) View.VISIBLE else View.GONE
        expandedDetailContainer.visibility = if (isExpanded && currentRoute != MainRoute.LOGS) {
            View.VISIBLE
        } else {
            View.GONE
        }
        root.findViewById<ImageButton?>(R.id.home_menu_button)?.visibility =
            if (isCompact) View.VISIBLE else View.INVISIBLE
        updateNavigationSelection()
        updateExpandedDetailPane()
    }

    private fun updateExpandedDetailPane() {
        if (expandedDetailContainer.visibility != View.VISIBLE) return
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
        val layout = when (route) {
            MainRoute.HOME -> R.layout.screen_home
            MainRoute.DISCOVER -> R.layout.screen_discover_riders
            MainRoute.SETTINGS -> R.layout.screen_settings
            MainRoute.LOGS -> R.layout.screen_logs
        }
        val page = inflater.inflate(layout, pageContainer, false)
        pageContainer.addView(page)
        currentScroll = page as? ScrollView
        currentScroll?.apply {
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
            MainRoute.HOME -> bindHome()
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

    private fun bindHome() {
        constrainWidth(R.id.home_content)
        pageContainer.findViewById<ImageButton>(R.id.home_menu_button).setOnClickListener {
            showNavigation()
        }
        pageContainer.findViewById<ImageButton>(R.id.home_settings_button).setOnClickListener {
            showPage(MainRoute.SETTINGS)
        }
        pageContainer.findViewById<Button>(R.id.home_primary_button).setOnClickListener {
            onToggleIntercom()
        }
        pageContainer.findViewById<Button>(R.id.home_discover_cta).setOnClickListener {
            showPage(MainRoute.DISCOVER)
        }
        pageContainer.findViewById<Button>(R.id.home_permission_grant_cta).setOnClickListener {
            onRequestCorePermissions()
        }
        pageContainer.findViewById<Button>(R.id.home_permission_settings_cta).setOnClickListener {
            onOpenPermissionSettings()
        }
        pageContainer.findViewById<Button>(R.id.home_wifi_settings_cta).setOnClickListener {
            onOpenWifiSettings()
        }
        pageContainer.findViewById<View>(R.id.home_mute_button).setOnClickListener {
            showPlaceholderDialog()
        }
        pageContainer.findViewById<View>(R.id.home_audio_settings_button).setOnClickListener {
            restoreSettingsAudio = true
            showPage(MainRoute.SETTINGS)
        }
        pageContainer.findViewById<View>(R.id.home_vox_card).setOnClickListener {
            showPlaceholderDialog()
        }
        pageContainer.findViewById<View>(R.id.home_vox_pill).setOnClickListener {
            showPlaceholderDialog()
        }
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
        pageContainer.findViewById<TextView>(R.id.home_peer_name).text = presentation.peerText
        pageContainer.findViewById<TextView>(R.id.home_status_title).text = presentation.primaryText
        pageContainer.findViewById<TextView>(R.id.home_status_detail).text = presentation.detailText
        pageContainer.findViewById<TextView>(R.id.home_status_supplemental).setOptionalText(
            presentation.supplementalText
        )
        pageContainer.findViewById<Button>(R.id.home_primary_button).apply {
            text = presentation.primaryActionLabel
            isEnabled = presentation.primaryActionEnabled
            contentDescription = presentation.disabledReason
                ?.takeIf { !presentation.primaryActionEnabled }
                ?.let {
                    activity.getString(
                        R.string.home_primary_disabled_description,
                        presentation.primaryActionLabel,
                        it
                    )
                }
        }
        pageContainer.findViewById<TextView>(R.id.home_disabled_reason).setOptionalText(
            presentation.disabledReason
        )
        pageContainer.findViewById<Button>(R.id.home_permission_grant_cta).visibility =
            if (presentation.showPermissionGrantCta) View.VISIBLE else View.GONE
        pageContainer.findViewById<Button>(R.id.home_permission_settings_cta).visibility =
            if (presentation.showPermissionSettingsCta) View.VISIBLE else View.GONE
        pageContainer.findViewById<Button>(R.id.home_wifi_settings_cta).visibility =
            if (presentation.showWifiSettingsCta) View.VISIBLE else View.GONE
        pageContainer.findViewById<Button>(R.id.home_discover_cta).apply {
            text = presentation.discoverCtaLabel
            visibility = if (presentation.showDiscoverCta) View.VISIBLE else View.GONE
        }
        pageContainer.findViewById<TextView>(R.id.home_audio_source).text =
            presentation.audioSourceText
        pageContainer.findViewById<TextView>(R.id.home_transport_pill).text =
            activity.getString(R.string.home_transport_plan, presentation.plannedTransportText)
        pageContainer.findViewById<TextView>(R.id.home_connected_transport).text =
            activity.getString(R.string.home_transport_current, presentation.connectedTransportText)
        pageContainer.findViewById<TextView>(R.id.home_webrtc_pill).text =
            activity.getString(R.string.home_webrtc_state, presentation.webRtcText)
        pageContainer.findViewById<TextView>(R.id.home_bluetooth_pill).text =
            activity.getString(
                R.string.home_bluetooth_state,
                optionalPermission.bluetoothStatusText
            )
        pageContainer.findViewById<TextView>(R.id.home_vox_pill).text =
            activity.getString(R.string.home_vox_pill, presentation.voxText)
        pageContainer.findViewById<TextView>(R.id.home_vox_status).text =
            activity.getString(R.string.home_vox_developing, presentation.voxText)
        pageContainer.findViewById<RippleView>(R.id.home_ripple).setRunning(
            animationsEnabled() && productState is IntercomState.Discovering
        )
        pageContainer.findViewById<VisualizerView>(R.id.home_visualizer).setConnected(
            animationsEnabled() && productState is IntercomState.Connected
        )
    }

    private fun bindDiscover() {
        constrainWidth(R.id.discover_content)
        pageContainer.findViewById<ImageButton>(R.id.discover_back_button).setOnClickListener {
            showPage(MainRoute.HOME)
        }
        pageContainer.findViewById<ImageButton>(R.id.discover_help_button).setOnClickListener {
            showPlaceholderDialog()
        }
        pageContainer.findViewById<Button>(R.id.discover_offline_start_button).setOnClickListener {
            onToggleIntercom()
        }
        pageContainer.findViewById<Button>(R.id.discover_wifi_settings_button).setOnClickListener {
            onOpenWifiSettings()
        }
        pageContainer.findViewById<Button>(R.id.discover_rescan_button).setOnClickListener {
            showPlaceholderDialog()
        }
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
        pageContainer.findViewById<TextView>(R.id.discover_state_text).text = stateText
        pageContainer.findViewById<TextView>(R.id.discover_status_supplemental).setOptionalText(
            supplementalStatus
        )
        pageContainer.findViewById<RippleView>(R.id.discover_radar_ripple).setRunning(
            animationsEnabled() && productState is IntercomState.Discovering
        )
        pageContainer.findViewById<Button>(R.id.discover_offline_start_button).visibility =
            if (presentation.offlineStartVisible) View.VISIBLE else View.GONE
        pageContainer.findViewById<Button>(R.id.discover_wifi_settings_button).visibility =
            if (presentation.wifiSettingsVisible) View.VISIBLE else View.GONE
        val pairedContainer = pageContainer.findViewById<LinearLayout>(R.id.discover_paired_container)
        val nearbyContainer = pageContainer.findViewById<LinearLayout>(R.id.discover_nearby_container)
        val offlinePairedContainer =
            pageContainer.findViewById<LinearLayout>(R.id.discover_offline_paired_container)
        val renderedCardCount = pairedContainer.childCount +
            nearbyContainer.childCount +
            offlinePairedContainer.childCount
        if (
            lastRenderedDiscoverPresentation != presentation ||
            renderedCardCount != presentation.cards.size
        ) {
            pairedContainer.removeAllViews()
            nearbyContainer.removeAllViews()
            offlinePairedContainer.removeAllViews()
            presentation.cards.forEachIndexed { index, card ->
                val target = when {
                    card.offlinePaired -> offlinePairedContainer
                    card.paired || card.preferred -> pairedContainer
                    else -> nearbyContainer
                }
                target.addView(createPresenceCard(card, presentation.orderedPresences[index]))
            }
            lastRenderedDiscoverPresentation = presentation
        }
        val hasCards = presentation.cards.isNotEmpty()
        pageContainer.findViewById<TextView>(R.id.discover_empty_text).apply {
            visibility = if (hasCards) View.GONE else View.VISIBLE
            text = if (
                presentation.wifiSettingsVisible ||
                presentation.offlineStartVisible ||
                presentation.readOnlyReason != null
            ) {
                stateText
            } else {
                activity.getString(R.string.discover_empty_no_presence)
            }
        }
        pageContainer.findViewById<TextView>(R.id.discover_paired_label).visibility =
            if (pairedContainer.childCount > 0) View.VISIBLE else View.GONE
        pageContainer.findViewById<TextView>(R.id.discover_nearby_label).visibility =
            if (nearbyContainer.childCount > 0) View.VISIBLE else View.GONE
        pageContainer.findViewById<TextView>(R.id.discover_offline_paired_label).visibility =
            if (offlinePairedContainer.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun createPresenceCard(
        card: DiscoverCardPresentation,
        presence: RiderPresence
    ): View {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawableCompat(R.drawable.motocom_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = card.title
            setOnClickListener {
                val deviceId = presence.deviceId
                val sessionId = presence.sessionId
                if (deviceId != null && sessionId != null) {
                    expandedSelectedPresence = PendingPresenceSelection(deviceId, sessionId)
                    updateExpandedDetailPane()
                }
            }
        }
        val facts = buildList {
            if (card.paired) add(activity.getString(R.string.discover_fact_paired))
            if (card.preferred) add(activity.getString(R.string.discover_fact_preferred))
            if (!presence.isSelectableForUi()) {
                add(activity.getString(R.string.discover_fact_unavailable))
            }
        }.joinToString(activity.getString(R.string.discover_fact_separator))
            .ifBlank { activity.getString(R.string.discover_fact_current) }
        container.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_rider_24)
                background = activity.getDrawableCompat(R.drawable.motocom_card_soft)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(card.title, 20f, true))
                addView(text(card.deviceText, 14f, false))
                addView(text(card.transportText, 14f, false))
                addView(text(facts, 13f, false))
            }, LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = dp(12)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (card.connectVisible) {
            container.addView(Button(activity).apply {
                minHeight = dp(48)
                text = activity.getString(
                    if (card.connectEnabled) R.string.discover_connect else R.string.discover_connect_pending
                )
                contentDescription = activity.getString(
                    if (card.connectEnabled) {
                        R.string.discover_connect_description
                    } else {
                        R.string.discover_connect_pending_description
                    },
                    card.title
                )
                isAllCaps = false
                isEnabled = card.connectEnabled
                background = activity.getDrawableCompat(R.drawable.motocom_primary_button)
                setTextColor(activity.getColorCompat(R.color.motocom_text_primary))
                setOnClickListener {
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
                        return@setOnClickListener
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
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).withTop(dp(12)))
        }
        return container.withOuterTopMargin(dp(10))
    }

    private fun bindSettings() {
        constrainWidth(R.id.settings_content)
        pageContainer.findViewById<ImageButton>(R.id.settings_back_button).setOnClickListener {
            showPage(MainRoute.HOME)
        }
        pageContainer.findViewById<EditText>(R.id.settings_nickname_input).setText(settingsNicknameDraft)
        pageContainer.findViewById<Button>(R.id.settings_save_nickname_button).setOnClickListener {
            saveNickname()
        }
        listOf(
            R.id.settings_audio_route_button,
            R.id.settings_audio_earpiece_button,
            R.id.settings_audio_speaker_button,
            R.id.settings_vox_button,
            R.id.settings_vox_sensitivity_button,
            R.id.settings_vox_state_button,
            R.id.settings_reconnect_button,
            R.id.settings_help_button
        ).forEach { id ->
            pageContainer.findViewById<Button>(id).setOnClickListener { showPlaceholderDialog() }
        }
        pageContainer.findViewById<Button>(R.id.settings_optional_permission_button).setOnClickListener {
            onRequestOptionalPermissions()
        }
        pageContainer.findViewById<Button>(R.id.settings_logs_button).setOnClickListener {
            showPage(MainRoute.LOGS)
        }
        pageContainer.findViewById<Button>(R.id.settings_about_button).setOnClickListener {
            showAboutDialog()
        }
        if (restoreSettingsAudio) {
            restoreSettingsAudio = false
            val audioSection = pageContainer.findViewById<View>(R.id.settings_audio_section)
            val settingsScroll = currentScroll
            audioSection.post {
                if (currentRoute == MainRoute.SETTINGS && currentScroll === settingsScroll) {
                    settingsScroll?.smoothScrollTo(0, audioSection.top)
                }
            }
        }
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
        pageContainer.findViewById<TextView>(R.id.settings_audio_source).text =
            activity.getString(
                R.string.settings_audio_summary,
                presentation.audioSourceText,
                optionalPermission.bluetoothStatusText
            )
        pageContainer.findViewById<TextView>(R.id.settings_product_state).text =
            activity.getString(R.string.settings_state_summary, presentation.primaryText)
        pageContainer.findViewById<TextView>(R.id.settings_attempt_facts).text =
            activity.getString(
                R.string.settings_attempt_summary,
                presentation.plannedTransportText,
                presentation.connectedTransportText,
                presentation.webRtcText
            )
        pageContainer.findViewById<TextView>(R.id.settings_discovery_candidates).text =
            activity.getString(
                R.string.settings_discovery_candidates_summary,
                discoveryCandidateSummary(presences)
            )
        pageContainer.findViewById<TextView>(R.id.settings_device_status_summary).text =
            activity.getString(
                R.string.settings_device_status_summary,
                presentation.audioSourceText,
                optionalPermission.bluetoothStatusText,
                presentation.primaryText,
                presentation.connectedTransportText
            )
        pageContainer.findViewById<TextView>(R.id.settings_optional_permission_notice).setOptionalText(
            optionalPermission.noticeText
        )
        pageContainer.findViewById<Button>(R.id.settings_optional_permission_button).visibility =
            if (optionalPermission.showGrantCta) View.VISIBLE else View.GONE
        pageContainer.findViewById<TextView>(R.id.settings_version_text).text =
            activity.getString(R.string.settings_version_summary, currentVersionName())
    }

    private fun saveNickname() {
        val input = pageContainer.findViewById<EditText>(R.id.settings_nickname_input).text?.toString().orEmpty()
        settingsNicknameDraft = input
        when (val validation = validateNickname(input)) {
            is NicknameValidation.Invalid -> {
                pageContainer.findViewById<TextView>(R.id.settings_nickname_feedback).text =
                    validation.message
            }
            is NicknameValidation.Valid -> {
                val saved = onSaveRiderName(validation.value)
                pageContainer.findViewById<TextView>(R.id.settings_nickname_feedback).text =
                    nicknameSaveFeedback(saved, productState)
                if (saved) {
                    settingsNicknameDraft = validation.value
                    pageContainer.findViewById<EditText>(R.id.settings_nickname_input).apply {
                        setText(validation.value)
                        setSelection(validation.value.length)
                    }
                }
            }
        }
    }

    private fun bindLogs() {
        constrainWidth(R.id.logs_content)
        pageContainer.findViewById<TextView>(R.id.logs_text).movementMethod =
            ScrollingMovementMethod.getInstance()
        pageContainer.findViewById<Button>(R.id.logs_back_button).setOnClickListener {
            showPage(MainRoute.SETTINGS)
        }
        pageContainer.findViewById<Button>(R.id.logs_close_button).setOnClickListener {
            showPage(MainRoute.SETTINGS)
        }
        pageContainer.findViewById<Button>(R.id.logs_copy_button).setOnClickListener {
            copyLogs()
        }
    }

    private fun renderLogs() {
        val snapshot = logBuffer.snapshot()
        pageContainer.findViewById<TextView>(R.id.logs_scope_text).text = LOGS_SCOPE_TEXT
        pageContainer.findViewById<TextView>(R.id.logs_text).text =
            copyableLogText(snapshot).ifBlank { activity.getString(R.string.logs_empty) }
        pageContainer.findViewById<Button>(R.id.logs_copy_button).isEnabled = snapshot.isNotEmpty()
    }

    private fun copyLogs() {
        val snapshot = logBuffer.snapshot()
        if (snapshot.isEmpty()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MotoCom logs", copyableLogText(snapshot)))
        Toast.makeText(activity, LOGS_COPIED_FEEDBACK, Toast.LENGTH_SHORT).show()
        pageContainer.findViewById<TextView>(R.id.logs_text).announceForAccessibility(LOGS_COPIED_FEEDBACK)
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
        if (currentRoute == MainRoute.SETTINGS) {
            pageContainer.findViewById<EditText?>(R.id.settings_nickname_input)
                ?.text
                ?.toString()
                ?.let { settingsNicknameDraft = it }
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

    private fun text(value: String, sizeSp: Float, bold: Boolean): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(activity.getColorCompat(if (bold) R.color.motocom_text_primary else R.color.motocom_text_secondary))
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }

    private fun animationsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun View.withOuterTopMargin(top: Int): View = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).withTop(top)
    }

    private fun LinearLayout.LayoutParams.withTop(top: Int): LinearLayout.LayoutParams = apply {
        topMargin = top
    }

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
