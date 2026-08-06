package com.kuma.motointercom

internal enum class MainRoute {
    HOME,
    DISCOVER,
    SETTINGS,
    LOGS
}

internal enum class StartPrecondition {
    READY,
    MISSING_CORE_PERMISSION,
    WIFI_UNAVAILABLE
}

internal const val AUDIO_SOURCE_STANDBY_TEXT = "当前音频源：待机"
internal const val BLUETOOTH_AUDIO_CONNECTED_TEXT = "蓝牙音频已连接"

internal fun startPrecondition(
    canStart: Boolean,
    wifiAvailable: Boolean
): StartPrecondition = when {
    !canStart -> StartPrecondition.MISSING_CORE_PERMISSION
    !wifiAvailable -> StartPrecondition.WIFI_UNAVAILABLE
    else -> StartPrecondition.READY
}

internal fun shouldIgnoreToggle(nowElapsed: Long, lastToggleElapsed: Long?): Boolean =
    lastToggleElapsed != null && nowElapsed - lastToggleElapsed < TOGGLE_DEBOUNCE_MS

internal fun shouldRecordToggle(action: PrimaryIntercomAction, startAccepted: Boolean): Boolean =
    action != PrimaryIntercomAction.NONE &&
        (action != PrimaryIntercomAction.START || startAccepted)

internal sealed interface BackNavigation {
    data object CloseNavigation : BackNavigation
    data object DismissPlaceholder : BackNavigation
    data object IgnoreIncomingConfirmation : BackNavigation
    data class NavigateTo(val route: MainRoute) : BackNavigation
    data object SystemDefault : BackNavigation
}

internal data class RouteChrome(
    val route: MainRoute,
    val navigationOpen: Boolean = false,
    val placeholderVisible: Boolean = false,
    val incomingConfirmationVisible: Boolean = false
)

internal fun restoreMainRoute(savedName: String?): MainRoute =
    savedName?.let { runCatching { MainRoute.valueOf(it) }.getOrNull() } ?: MainRoute.HOME

internal fun restoreNicknameDraft(savedDraft: String?, initialName: String): String =
    savedDraft ?: initialName

internal fun resolveBackNavigation(chrome: RouteChrome): BackNavigation = when {
    chrome.incomingConfirmationVisible -> BackNavigation.IgnoreIncomingConfirmation
    chrome.navigationOpen -> BackNavigation.CloseNavigation
    chrome.placeholderVisible -> BackNavigation.DismissPlaceholder
    chrome.route == MainRoute.LOGS -> BackNavigation.NavigateTo(MainRoute.SETTINGS)
    chrome.route == MainRoute.DISCOVER || chrome.route == MainRoute.SETTINGS -> {
        BackNavigation.NavigateTo(MainRoute.HOME)
    }
    else -> BackNavigation.SystemDefault
}

internal data class HomePresentation(
    val primaryText: String,
    val detailText: String,
    val primaryAction: PrimaryIntercomAction,
    val primaryActionLabel: String,
    val primaryActionEnabled: Boolean,
    val disabledReason: String?,
    val showPermissionGrantCta: Boolean,
    val showPermissionSettingsCta: Boolean,
    val showWifiSettingsCta: Boolean,
    val showDiscoverCta: Boolean,
    val discoverCtaLabel: String,
    val peerText: String,
    val plannedTransportText: String,
    val connectedTransportText: String,
    val webRtcText: String,
    val audioSourceText: String,
    val bluetoothActive: Boolean,
    val voxText: String,
    val supplementalText: String?
)

internal fun homePresentation(
    state: IntercomState,
    canStart: Boolean,
    audioSourceText: String,
    bluetoothActive: Boolean,
    wifiUnavailable: Boolean = false,
    supplementalText: String? = null,
    lastStoppingPeerName: String? = null,
    discoverCtaNeedsReselect: Boolean = false
): HomePresentation {
    val primaryAction = primaryIntercomAction(state)
    val primaryEnabled = when (state) {
        IntercomState.Offline -> canStart
        is IntercomState.Stopping -> false
        else -> true
    }
    val showDiscoverCta = state is IntercomState.Discovering
    return HomePresentation(
        primaryText = state.primaryText(),
        detailText = state.detailText(),
        primaryAction = primaryAction,
        primaryActionLabel = state.primaryActionLabel(),
        primaryActionEnabled = primaryEnabled,
        disabledReason = when {
            state == IntercomState.Offline && canStart && wifiUnavailable -> WIFI_UNAVAILABLE_TEXT
            state == IntercomState.Offline && canStart -> null
            state is IntercomState.Stopping -> "正在释放连接和音频资源"
            state == IntercomState.Offline -> "缺少必要权限"
            else -> null
        },
        showPermissionGrantCta = state == IntercomState.Offline && !canStart,
        showPermissionSettingsCta = state == IntercomState.Offline && !canStart,
        showWifiSettingsCta = state == IntercomState.Offline && canStart && wifiUnavailable,
        showDiscoverCta = showDiscoverCta,
        discoverCtaLabel = if (showDiscoverCta && discoverCtaNeedsReselect) {
            HOME_DISCOVER_RESELECT_CTA
        } else {
            HOME_DISCOVER_CTA
        },
        peerText = state.peerText(lastStoppingPeerName),
        plannedTransportText = state.connectionAttemptOrNull()
            ?.channelPlan
            ?.plannedTransports
            ?.joinToString(" + ", transform = Transport::displayName)
            ?: "待建立",
        connectedTransportText = (state as? IntercomState.Connected)
            ?.transport
            ?.displayName()
            ?: "未连接",
        webRtcText = when (state) {
            is IntercomState.Connecting -> "建立中"
            is IntercomState.Connected -> "已连接"
            else -> "未连接"
        },
        audioSourceText = audioSourceText,
        bluetoothActive = bluetoothActive,
        voxText = PLACEHOLDER_VOX_STATUS,
        supplementalText = supplementalText
    )
}

internal data class OptionalPermissionPresentation(
    val bluetoothActive: Boolean,
    val bluetoothStatusText: String,
    val noticeText: String?,
    val showGrantCta: Boolean
)

internal fun optionalPermissionPresentation(
    bluetoothPermissionMissing: Boolean,
    notificationPermissionMissing: Boolean,
    bluetoothActive: Boolean
): OptionalPermissionPresentation {
    val anyMissing = bluetoothPermissionMissing || notificationPermissionMissing
    return OptionalPermissionPresentation(
        bluetoothActive = bluetoothActive && !bluetoothPermissionMissing,
        bluetoothStatusText = when {
            bluetoothPermissionMissing -> BLUETOOTH_PERMISSION_UNAVAILABLE
            bluetoothActive -> BLUETOOTH_CONNECTED_TEXT
            else -> BLUETOOTH_DISCONNECTED_TEXT
        },
        noticeText = when {
            bluetoothPermissionMissing -> OPTIONAL_PERMISSION_BLUETOOTH_NOTICE
            notificationPermissionMissing -> OPTIONAL_PERMISSION_NOTIFICATION_NOTICE
            else -> null
        },
        showGrantCta = anyMissing
    )
}

internal fun audioSourcePresentation(status: String, bluetooth: Boolean): String {
    val normalized = status.trim()
    val compact = normalized.filterNot(Char::isWhitespace)
    if (!bluetooth) {
        val staleBluetoothLabel = compact.contains("蓝牙耳机") || compact.contains("蓝牙音频")
        return if (staleBluetoothLabel) AUDIO_SOURCE_STANDBY_TEXT else normalized.ifBlank {
            AUDIO_SOURCE_STANDBY_TEXT
        }
    }

    val noDeviceName = compact.isBlank() || compact in setOf(
        "当前音频源：蓝牙耳机",
        "当前音频源：蓝牙耳机()",
        "当前音频源：蓝牙耳机（）",
        "当前音频源：蓝牙耳机(头盔蓝牙)",
        "蓝牙耳机(头盔蓝牙)",
        "蓝牙耳机",
        "蓝牙耳机()",
        "蓝牙耳机（）",
        "头盔蓝牙"
    )
    return if (noDeviceName) BLUETOOTH_AUDIO_CONNECTED_TEXT else normalized
}

internal fun visibleAudioSourceText(
    status: String,
    bluetooth: Boolean,
    bluetoothPermissionMissing: Boolean
): String {
    if (bluetooth && bluetoothPermissionMissing) return BLUETOOTH_PERMISSION_UNAVAILABLE
    return audioSourcePresentation(status, bluetooth)
}

internal fun incomingConfirmationNotificationMessage(deviceName: String): String {
    val visibleDeviceName = deviceName.trim().ifBlank { DEVICE_NAME_UNAVAILABLE }
    return "$visibleDeviceName · 请在应用内确认"
}

private fun IntercomState.primaryText(): String = when (this) {
    IntercomState.Offline -> "点击下方启动摩声"
    is IntercomState.Discovering -> "正在寻找附近 MotoCom 车友"
    is IntercomState.IncomingConfirmation -> "收到附近车友的连接请求"
    is IntercomState.Connecting -> "正在建立连接"
    is IntercomState.Optimizing -> "正在优化连接通道"
    is IntercomState.Connected -> "语音通道已连接"
    is IntercomState.Recovering -> "正在恢复原车友连接"
    is IntercomState.Resetting -> "正在重置无线连接"
    is IntercomState.Stopping -> "正在结束对讲"
}

private fun IntercomState.detailText(): String = when (this) {
    IntercomState.Offline -> "一对一对讲 · 无需网络"
    is IntercomState.Discovering -> "保持两台手机的 Wi-Fi 可用"
    is IntercomState.IncomingConfirmation -> "请在确认框中选择接受或拒绝"
    is IntercomState.Connecting -> "正在建立信令与媒体通道"
    is IntercomState.Optimizing -> "保持当前目标不变"
    is IntercomState.Connected -> "对讲已就绪"
    is IntercomState.Recovering -> "不会切换到其他附近车友"
    is IntercomState.Resetting -> "请稍候"
    is IntercomState.Stopping -> "正在释放连接和音频资源"
}

private fun IntercomState.primaryActionLabel(): String = when (this) {
    is IntercomState.Stopping -> "停止中…"
    IntercomState.Offline -> "启动摩声"
    else -> "结束对讲"
}

private fun IntercomState.peerText(lastStoppingPeerName: String?): String = when (this) {
    IntercomState.Offline,
    is IntercomState.Discovering -> "等待车友加入"
    is IntercomState.IncomingConfirmation -> peer.displayName("附近车友")
    is IntercomState.Connecting -> peer?.displayName("正在确认目标车友") ?: "正在确认目标车友"
    is IntercomState.Optimizing -> peer?.displayName("正在确认目标车友") ?: "正在确认目标车友"
    is IntercomState.Connected -> peer.displayName("已连接车友")
    is IntercomState.Recovering -> peer.displayName("正在恢复原车友")
    is IntercomState.Resetting -> "正在恢复原车友"
    is IntercomState.Stopping -> lastStoppingPeerName?.ifBlank { null } ?: "正在结束对讲"
}

private fun PeerIdentity.displayName(fallback: String): String =
    nickname.ifBlank { fallback }

private fun Transport.displayName(): String = when (this) {
    Transport.LAN -> "LAN"
    Transport.WIFI_DIRECT -> "Wi-Fi Direct"
}

internal data class DiscoverCardPresentation(
    val title: String,
    val deviceText: String,
    val transportText: String,
    val paired: Boolean,
    val preferred: Boolean,
    val offlinePaired: Boolean,
    val connectVisible: Boolean,
    val connectEnabled: Boolean
)

internal data class DiscoverPresentation(
    val offlineStartVisible: Boolean,
    val wifiSettingsVisible: Boolean,
    val readOnlyReason: String?,
    val cards: List<DiscoverCardPresentation>,
    val orderedPresences: List<RiderPresence>
)

internal fun RiderPresence.isSelectableForUi(): Boolean =
    isSelectable && !deviceId.isNullOrBlank()

internal fun discoverPresentation(
    state: IntercomState,
    presences: List<RiderPresence>,
    wifiUnavailable: Boolean = false,
    canStart: Boolean = true,
    connectPending: Boolean = false
): DiscoverPresentation {
    val discovering = state is IntercomState.Discovering
    val orderedPresences = presences.sortedWith(
        compareBy<RiderPresence>(::discoverGroupRank)
    )
    return DiscoverPresentation(
        offlineStartVisible = state == IntercomState.Offline,
        wifiSettingsVisible = wifiUnavailable && canStart &&
            (state == IntercomState.Offline || discovering),
        readOnlyReason = discoverReadOnlyReason(state),
        cards = orderedPresences.map { presence ->
            val selectable = presence.isSelectableForUi()
            DiscoverCardPresentation(
                title = presenceTitle(presence, orderedPresences),
                deviceText = presence.deviceName.ifBlank { DEVICE_NAME_UNAVAILABLE },
                transportText = presence.availableTransports
                    .sortedBy(Transport::ordinal)
                    .joinToString(" + ", transform = Transport::displayName)
                    .ifBlank { "暂无可用通道" },
                paired = presence.isPaired,
                preferred = presence.isPreferred,
                offlinePaired = presence.isPaired && presence.availableTransports.isEmpty(),
                connectVisible = discovering && selectable,
                connectEnabled = discovering && selectable && !connectPending
            )
        },
        orderedPresences = orderedPresences
    )
}

internal fun discoverStateText(presentation: DiscoverPresentation): String = when {
    presentation.wifiSettingsVisible -> WIFI_UNAVAILABLE_TEXT
    presentation.offlineStartVisible -> "启动摩声后开始发现附近车友"
    presentation.readOnlyReason != null -> presentation.readOnlyReason
    presentation.cards.isEmpty() -> "正在搜索附近 MotoCom 车友"
    presentation.cards.none(DiscoverCardPresentation::connectVisible) -> "当前没有可连接的车友"
    else -> "选择一位当前可连接的车友"
}

private fun discoverGroupRank(presence: RiderPresence): Int = when {
    presence.isPaired && presence.isPreferred && presence.availableTransports.isNotEmpty() -> 0
    presence.isPaired && presence.availableTransports.isNotEmpty() -> 1
    !presence.isPaired -> 2
    else -> 3
}

internal fun discoveryCandidateSummary(presences: List<RiderPresence>): String =
    presences
        .filter { it.availableTransports.isNotEmpty() }
        .map { presence ->
            val transports = presence.availableTransports
                .sortedBy(Transport::ordinal)
                .joinToString(" + ", transform = Transport::displayName)
            "${presenceTitle(presence, presences)}：$transports"
        }
        .joinToString("；")
        .ifBlank { "当前没有可用发现候选" }

private fun discoverReadOnlyReason(state: IntercomState): String? = when (state) {
    IntercomState.Offline -> "启动摩声后开始发现附近车友"
    is IntercomState.Discovering -> null
    is IntercomState.IncomingConfirmation -> "请先处理当前连接请求"
    is IntercomState.Connected -> "请先结束当前对讲"
    is IntercomState.Recovering -> "正在恢复原车友连接，暂不能切换目标"
    is IntercomState.Resetting -> "正在重置无线连接"
    is IntercomState.Stopping -> "正在结束对讲"
    is IntercomState.Connecting,
    is IntercomState.Optimizing -> "正在连接当前目标"
}

private fun presenceTitle(
    presence: RiderPresence,
    allPresences: List<RiderPresence>
): String {
    val displayName = presence.realDisplayName()
    val sameName = allPresences.filter { it.realDisplayName() == displayName }
    if (sameName.size <= 1) return displayName
    val deviceName = presence.deviceName.ifBlank { null }
    val sameDeviceName = sameName.count { it.deviceName == presence.deviceName }
    if (deviceName != null && sameDeviceName == 1) return "$displayName · $deviceName"
    val suffix = presence.deviceId?.takeIf(String::isNotBlank)?.takeLast(4)
    return if (suffix == null) displayName else "$displayName · $suffix"
}

private fun RiderPresence.realDisplayName(): String =
    pairing?.localAlias?.takeIf(String::isNotBlank)
        ?: nickname.takeIf(String::isNotBlank)
        ?: pairing?.remoteNickname?.takeIf(String::isNotBlank)
        ?: "附近车友"

internal sealed interface NicknameValidation {
    data class Valid(val value: String) : NicknameValidation
    data class Invalid(val message: String) : NicknameValidation
}

internal fun validateNickname(input: String): NicknameValidation {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return NicknameValidation.Invalid("请输入昵称")
    val codePoints = trimmed.codePointCount(0, trimmed.length)
    if (codePoints > MAX_NICKNAME_CODE_POINTS) {
        return NicknameValidation.Invalid("昵称最多 64 个字符")
    }
    return NicknameValidation.Valid(trimmed)
}

internal fun copyableLogText(lines: List<String>): String = lines.joinToString("\n")

internal fun saveRouteScrollPosition(
    positions: MutableMap<MainRoute, Int>,
    route: MainRoute,
    scrollY: Int
) {
    positions[route] = scrollY.coerceAtLeast(0)
}

internal fun restoredRouteScrollPosition(
    positions: Map<MainRoute, Int>,
    route: MainRoute
): Int = (positions[route] ?: 0).coerceAtLeast(0)

internal fun shouldNavigateHomeAfterDiscoverConnect(state: IntercomState): Boolean = when (state) {
    is IntercomState.Connecting,
    is IntercomState.Optimizing,
    is IntercomState.Connected,
    is IntercomState.Recovering -> true
    else -> false
}

internal fun shouldKeepDiscoverConnectPending(state: IntercomState): Boolean =
    state is IntercomState.Discovering

internal fun feedbackAfterDiscoverConnect(dispatched: Boolean): String? =
    if (dispatched) null else SERVICE_UNAVAILABLE_STATUS

internal fun shouldDismissIncomingConfirmation(
    activeNonce: String?,
    canceledNonce: String
): Boolean = activeNonce == canceledNonce

internal fun shouldShowPlaceholderDialog(alreadyShowing: Boolean): Boolean = !alreadyShowing

internal fun shouldRenderLogAppend(route: MainRoute): Boolean = route == MainRoute.LOGS

internal fun nicknameSaveFeedback(
    saved: Boolean,
    state: IntercomState
): String = when {
    !saved -> NICKNAME_SAVE_FAILED_FEEDBACK
    state == IntercomState.Offline -> NICKNAME_SAVED_FEEDBACK
    else -> NICKNAME_NEXT_START_FEEDBACK
}

internal fun displayVersionName(packageVersion: String?, unavailableText: String): String =
    packageVersion?.takeIf { it.isNotBlank() } ?: unavailableText

internal fun shouldFollowLogBottom(
    scrollY: Int,
    viewportHeight: Int,
    contentBottom: Int,
    thresholdPx: Int
): Boolean = contentBottom <= 0 || scrollY + viewportHeight >= contentBottom - thresholdPx

internal fun constrainedContentWidth(
    parentWidth: Int,
    horizontalPadding: Int,
    maxWidth: Int
): Int = minOf(
    parentWidth.coerceAtLeast(0),
    (maxWidth + horizontalPadding).coerceAtLeast(0)
)

internal fun constrainedPanelWidth(
    availableWidth: Int,
    preferredWidth: Int
): Int = minOf(preferredWidth, availableWidth.coerceAtLeast(0))

internal const val PLACEHOLDER_DIALOG_TITLE = "功能开发中"
internal const val PLACEHOLDER_DIALOG_MESSAGE = "该功能还没做好，暂时无法使用。"
internal const val PLACEHOLDER_DIALOG_BUTTON = "确定"
internal const val PLACEHOLDER_VOX_STATUS = "状态接口待接入"
internal const val LOGS_SCOPE_TEXT = "仅显示本次界面会话日志"
internal const val LOGS_COPIED_FEEDBACK = "日志已复制"
internal const val NICKNAME_SAVE_FAILED_FEEDBACK = "保存失败，请重试"
internal const val NICKNAME_SAVED_FEEDBACK = "已保存"
internal const val NICKNAME_NEXT_START_FEEDBACK = "已保存，下次启动对讲时生效"
internal const val HOME_DISCOVER_CTA = "查看附近车友"
internal const val HOME_DISCOVER_RESELECT_CTA = "重新选择车友"
internal const val SERVICE_UNAVAILABLE_STATUS = "后台服务未就绪"
internal const val DEVICE_NAME_UNAVAILABLE = "设备名称未提供"
internal const val WIFI_UNAVAILABLE_TEXT = "请先打开 Wi-Fi 开关"
internal const val BLUETOOTH_CONNECTED_TEXT = "已连接"
internal const val BLUETOOTH_DISCONNECTED_TEXT = "未连接"
internal const val TOGGLE_DEBOUNCE_MS = 2_000L
internal const val BLUETOOTH_PERMISSION_UNAVAILABLE = "蓝牙状态不可用"
internal const val OPTIONAL_PERMISSION_BLUETOOTH_NOTICE =
    "蓝牙权限未授予；蓝牙状态不可用，对讲仍可启动"
internal const val OPTIONAL_PERMISSION_NOTIFICATION_NOTICE =
    "通知权限未授予；不影响对讲启动"

private const val MAX_NICKNAME_CODE_POINTS = 64
