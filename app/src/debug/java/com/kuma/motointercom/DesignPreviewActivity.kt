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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
                        SCREEN_HOME_DISCOVERING -> DiscoveringHomePreview()
                        else -> ConnectedHomePreview()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_HOME_DISCOVERING = "home-discovering"
        const val SCREEN_DISCOVER = "discover"
        const val SCREEN_SETTINGS = "settings"
    }
}

@androidx.compose.runtime.Composable
private fun ConnectedHomePreview() {
    MotoComHomeScreen(
        state = HomeScreenUiState(
            primaryText = "语音通道已连接",
            supplementalText = null,
            peerText = "张一山",
            primaryActionLabel = "结束对讲",
            primaryActionEnabled = true,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "查看附近车友",
            showDiscoverCta = false,
            audioSourceText = "蓝牙耳机 · M200 Pro",
            discovering = false,
            connected = true,
            muteEnabled = true,
            voxState = VoxRuntimeState.OPEN
        ),
        audioLevel = 0.4f,
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
private fun DiscoveringHomePreview() {
    val previewPeers = remember {
        listOf(
            RadarPreviewPeer(
                id = "zhang-yishan",
                label = "张一山"
            ),
            RadarPreviewPeer(
                id = "rider-b",
                label = "车友 B"
            ),
            RadarPreviewPeer(
                id = "rider-c",
                label = "阿杰"
            )
        )
    }
    var selectedPeerId by remember { mutableStateOf<String?>(null) }
    val selectedPeer = previewPeers.firstOrNull { it.id == selectedPeerId }
    MotoComHomeScreen(
        state = HomeScreenUiState(
            primaryText = "正在搜索附近车友",
            supplementalText = null,
            peerText = selectedPeer?.label.orEmpty(),
            primaryActionLabel = "结束对讲",
            primaryActionEnabled = true,
            disabledReason = null,
            showPermissionGrantCta = false,
            showPermissionSettingsCta = false,
            showWifiSettingsCta = false,
            discoverCtaLabel = "查看附近车友",
            showDiscoverCta = true,
            audioSourceText = "蓝牙耳机",
            discovering = true,
            connected = false,
            muteEnabled = true,
            animationsEnabled = true
        ),
        radarPreviewPeers = previewPeers.map { it.copy(selected = it.id == selectedPeerId) },
        onRadarPreviewPeerClick = { selectedPeerId = it.id },
        audioLevel = 0f,
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
            remoteNickname = "车友 B",
            deviceName = "Pixel 9",
            localAlias = "车友 B",
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
            stateText = "正在搜索",
            supplementalText = null,
            radarRunning = true
        ),
        onBack = {},
        onHelp = {},
        onStart = {},
        onWifiSettings = {},
        onRescan = {},
        onSelectPresence = {},
        onConnect = {},
        onManagePairing = {}
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
            audioSource = "蓝牙耳机 · M200 Pro",
            productState = "语音通道已连接",
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
        onHelp = {}
    )
}
