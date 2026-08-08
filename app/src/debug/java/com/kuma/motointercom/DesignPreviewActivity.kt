package com.kuma.motointercom

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource

internal class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.statusBarColor = getColor(R.color.motocom_background)
        window.navigationBarColor = getColor(R.color.motocom_background)
        @Suppress("DEPRECATION")
        var systemUiFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            systemUiFlags = systemUiFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = systemUiFlags
        val screen = intent.getStringExtra(EXTRA_SCREEN).orEmpty()
        setContent {
            MotoComTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colorResource(R.color.motocom_background))
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    when (screen) {
                        SCREEN_DISCOVER -> ConnectedDiscoverPreview()
                        SCREEN_SETTINGS -> ConnectedSettingsPreview()
                        else -> ConnectedHomePreview()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_DISCOVER = "discover"
        const val SCREEN_SETTINGS = "settings"
    }
}

@androidx.compose.runtime.Composable
private fun ConnectedHomePreview() {
    MotoComHomeScreen(
        state = HomeScreenUiState(
            primaryText = "语音通道已连接",
            detailText = "",
            supplementalText = "对方在线",
            peerText = "张一山",
            primaryActionLabel = "结束对讲",
            primaryActionEnabled = true,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "查看附近车友",
            showDiscoverCta = false,
            audioSourceText = "已连接：M200 Pro",
            plannedTransportText = "Wi-Fi Direct",
            connectedTransportText = "Wi-Fi Direct",
            webRtcText = "已连接",
            bluetoothText = "蓝牙耳机已连接",
            voxText = "状态接口待接入",
            discovering = false,
            connected = true
        ),
        audioLevel = 0.4f,
        onMenu = {},
        onSettings = {},
        onPrimaryAction = {},
        onDiscover = {},
        onPermissionGrant = {},
        onPermissionSettings = {},
        onWifiSettings = {},
        onMute = {},
        onAudioSettings = {},
        onVox = {}
    )
}

@androidx.compose.runtime.Composable
private fun ConnectedDiscoverPreview() {
    val preferred = RiderPresence(
        deviceId = "preview-rider-a",
        sessionId = RuntimeSessionId("preview-session-a"),
        nickname = "张一山",
        deviceName = "M200 Pro",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(previewCandidate("preview-a", isAvailable = true)),
        pairing = PairingRecord(
            remoteDeviceId = "preview-rider-a",
            remoteNickname = "张一山",
            deviceName = "M200 Pro",
            localAlias = "张一山",
            shortCode = "1234",
            pairedAt = 1L,
            lastConnectedAt = 1L,
            isPreferred = true,
            lastTransport = "WIFI_DIRECT",
            failureCount = 0
        )
    )
    val nearby = RiderPresence(
        deviceId = "preview-rider-b",
        sessionId = RuntimeSessionId("preview-session-b"),
        nickname = "车友 B",
        deviceName = "Pixel 9",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(previewCandidate("preview-b", isAvailable = false)),
        pairing = PairingRecord(
            remoteDeviceId = "preview-rider-b",
            remoteNickname = "杞﹀弸 B",
            deviceName = "Pixel 9",
            localAlias = "杞﹀弸 B",
            shortCode = "5678",
            pairedAt = 1L,
            lastConnectedAt = 0L,
            isPreferred = false,
            lastTransport = "WIFI_DIRECT",
            failureCount = 0
        )
    )
    val unavailable = RiderPresence(
        deviceId = "preview-rider-c",
        sessionId = RuntimeSessionId("preview-session-c"),
        nickname = "骑行者 C",
        deviceName = "Galaxy S25",
        protocolVersion = 2,
        lastSeenElapsedRealtimeMs = 1L,
        candidates = listOf(previewCandidate("preview-c", isAvailable = false)),
        pairing = null
    )
    val presences = listOf(preferred, nearby, unavailable)
    MotoComDiscoverScreen(
        state = DiscoverScreenUiState(
            presentation = discoverPresentation(
                state = IntercomState.Discovering(RuntimeSessionId("preview-runtime")),
                presences = presences
            ),
            stateText = "正在搜索附近 MotoCom 车友…",
            supplementalText = "请确保双方已打开 MotoCom",
            emptyText = "暂无附近车友",
            radarRunning = true
        ),
        onBack = {},
        onHelp = {},
        onStart = {},
        onWifiSettings = {},
        onRescan = {},
        onSelectPresence = {},
        onConnect = {}
    )
}

private fun previewCandidate(endpointId: String, isAvailable: Boolean) =
    PresenceTransportCandidate(
        transport = Transport.WIFI_DIRECT,
        endpointId = endpointId,
        address = "192.0.2.1",
        port = 4321,
        lastSeenElapsedRealtimeMs = 1L,
        isAvailable = isAvailable
    )

@androidx.compose.runtime.Composable
private fun ConnectedSettingsPreview() {
    MotoComSettingsScreen(
        state = SettingsScreenUiState(
            nickname = "骑行者 A",
            nicknameFeedback = "",
            audioSource = "当前音频源：蓝牙耳机（推荐）",
            productState = "当前状态：语音通道已连接",
            attemptFacts = "规划通道：Wi-Fi Direct；当前通道：Wi-Fi Direct；WebRTC：已连接",
            discoveryCandidates = "可用发现候选：张一山：Wi-Fi Direct",
            deviceStatus = "音频：已连接：M200 Pro\n蓝牙：蓝牙音频已连接\n产品状态：语音通道已连接\n当前通道：Wi-Fi Direct",
            optionalPermissionNotice = null,
            showOptionalPermissionCta = false,
            version = "v1.2.0"
        ),
        onBack = {},
        onNicknameChanged = {},
        onSaveNickname = {},
        onOptionalPermission = {},
        onLogs = {},
        onAbout = {},
        onPlaceholder = {}
    )
}
