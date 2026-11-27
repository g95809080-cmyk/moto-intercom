package com.kuma.motointercom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kuma.motointercom.group.GroupChannel
import com.kuma.motointercom.group.GroupMemberStatus
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.GroupPhase
import com.kuma.motointercom.group.GroupRole
import com.kuma.motointercom.group.GroupServiceState
import com.kuma.motointercom.group.GroupSessionEvent
import com.kuma.motointercom.group.GroupTransport
import com.kuma.motointercom.group.groupRequiredPermissions
import com.kuma.motointercom.group.hasGroupPermissions
import com.kuma.motointercom.group.hasNetworkGroupPermissions
import com.kuma.motointercom.group.missingGroupPromptPermissions
import com.kuma.motointercom.group.networkGroupPromptPermissions
import com.kuma.motointercom.group.cloud.GroupCloudPreferences
import com.kuma.motointercom.group.cloud.GroupCloudStartRequest
import com.kuma.motointercom.group.cloud.GroupCloudUrlPolicy
import com.kuma.motointercom.group.cloud.GroupActivationCodeValidator
import com.kuma.motointercom.group.cloud.GroupCloudException
import com.kuma.motointercom.group.cloud.GroupEntitlementStore
import com.kuma.motointercom.group.cloud.GroupEntitlementSummary
import com.kuma.motointercom.group.cloud.HttpGroupCloudClient
import com.kuma.motointercom.group.ui.GlassBackdropMood
import com.kuma.motointercom.group.ui.GroupGlassBackdrop
import com.kuma.motointercom.group.ui.GroupGlassPalette
import com.kuma.motointercom.group.ui.GroupGlassTheme
import com.kuma.motointercom.group.ui.GroupInRoomContent
import com.kuma.motointercom.group.ui.GroupInRoomSheet
import com.kuma.motointercom.group.ui.GroupInRoomSheetOverlay
import com.kuma.motointercom.group.ui.GroupLobbyContent
import com.kuma.motointercom.group.ui.GroupLogsContent
import com.kuma.motointercom.group.ui.GroupSettingsContent
import com.kuma.motointercom.group.ui.OwnerLeaveSelectionDialog
import com.kuma.motointercom.group.ui.copyRedactedDiagnosticLogs
import com.kuma.motointercom.group.ui.groupSystemBarStyle
import com.kuma.motointercom.group.ui.redactDiagnosticExportFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class GroupSubpage {
    MAIN,
    SETTINGS,
    LOGS
}

/** UI observes the service. Navigation never leaves a room and saved state never restores a code. */
internal class GroupActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var service: GroupIntercomService? = null
    private var bound = false
    private var resumed = false
    private var pending = false
    private var pendingCode: String? = null
    private var pendingNetwork: GroupCloudStartRequest? = null
    private var permissionsReturned = false
    private val state = mutableStateOf(GroupServiceState())
    private val themeMode = mutableStateOf(MotoComThemeMode.DAYLIGHT)
    private val nickname = mutableStateOf("")
    private val voxSensitivity = mutableIntStateOf(DEFAULT_VOX_SENSITIVITY)
    private var voxSensitivityDirty = false
    private val rnnoiseEnabled = mutableStateOf(false)
    private val diagnosticLogs = mutableStateOf<List<String>>(emptyList())
    private val diagnosticHint = mutableStateOf<String?>(null)
    private val entitlementSummary = mutableStateOf<GroupEntitlementSummary?>(null)
    private val hasEntitlementToken = mutableStateOf(false)
    private val entitlementBusy = mutableStateOf(false)
    private val entitlementError = mutableStateOf<String?>(null)
    private val showActivationDialog = mutableStateOf(false)
    private var entitlementRefreshInFlight = false
    private lateinit var cloudPrefs: GroupCloudPreferences
    private lateinit var entitlementStore: GroupEntitlementStore
    private lateinit var appearancePrefs: AppearancePreferences
    private lateinit var routePrefs: AudioRoutePreferences
    private lateinit var identityStore: LocalIdentityStore
    private val listener: (GroupServiceState) -> Unit = { next ->
        val wasBusy = state.value.busy
        state.value = next
        if (wasBusy && !next.busy && entitlementStore.loadToken() != null) {
            refreshEntitlementAsync(cloudPrefs.loadServerUrl(), silent = true)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!bound) return
            val localBinder = binder as? GroupIntercomService.LocalBinder ?: return
            service = localBinder.service()
            service?.addGroupListener(listener)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            state.value = GroupServiceState(
                message = "连接已结束，请重新创建或加入",
                route = routePrefs.load(),
                vox = GroupAudioControlStore.peek(this@GroupActivity).voxEnabled
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        DiagnosticLog.initialize(this)
        cloudPrefs = GroupCloudPreferences(this)
        entitlementStore = GroupEntitlementStore(this)
        hasEntitlementToken.value = entitlementStore.loadToken() != null
        entitlementSummary.value = entitlementStore.loadSummary()
        appearancePrefs = AppearancePreferences(this)
        routePrefs = AudioRoutePreferences(this)
        identityStore = DataStoreLocalIdentityStore(this)
        themeMode.value = appearancePrefs.load()
        val initialAudio = GroupAudioControlStore.peek(this)
        val loadedRoute = routePrefs.load()
        state.value = state.value.copy(route = loadedRoute, vox = initialAudio.voxEnabled)
        voxSensitivity.intValue = initialAudio.voxSensitivity
        rnnoiseEnabled.value = initialAudio.rnnoiseEnabled
        applySystemBarAppearance(themeMode.value)
        GroupAudioControlStore.hydrateAsync(this) {
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val latestAudio = GroupAudioControlStore.peek(this@GroupActivity)
                if (service == null) {
                    state.value = state.value.copy(vox = latestAudio.voxEnabled)
                }
                if (!voxSensitivityDirty) {
                    voxSensitivity.intValue = latestAudio.voxSensitivity
                }
                rnnoiseEnabled.value = latestAudio.rnnoiseEnabled
            }
        }
        activityScope.launch(Dispatchers.IO) {
            val storedNickname = runCatching { identityStore.getNickname() }.getOrDefault("")
            runOnUiThread { nickname.value = storedNickname }
        }
        setContent {
            GroupGlassTheme(themeMode = themeMode.value) {
                GroupScreen(
                    state = state.value,
                    onStart = ::requestStart,
                    onAction = { service?.groupAction(it) },
                    onRoute = { selection ->
                        val boundService = service
                        if (boundService != null) {
                            boundService.groupRoute(selection)
                        } else {
                            routePrefs.save(selection)
                            state.value = state.value.copy(route = selection)
                        }
                    },
                    onVox = { enabled ->
                        val boundService = service
                        if (boundService != null) {
                            boundService.groupVox(enabled)
                        } else {
                            GroupAudioControlStore.updateVoxEnabled(this@GroupActivity, enabled)
                            state.value = state.value.copy(vox = enabled)
                        }
                    },
                    onBack = ::finish,
                    onSettings = {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
                        )
                    },
                    onCopy = { code -> copyPlainText("房间码", code) },
                    onStartNetwork = ::requestNetworkStart,
                    initialServerUrl = cloudPrefs.loadServerUrl(),
                    allowCustomServer = BuildConfig.DEBUG,
                    onSaveCloudSettings = cloudPrefs::saveServerUrl,
                    onResetCloudSettings = cloudPrefs::resetServerUrl,
                    themeMode = themeMode.value,
                    onThemeChange = { nextTheme ->
                        themeMode.value = nextTheme
                        appearancePrefs.save(nextTheme)
                        applySystemBarAppearance(nextTheme)
                    },
                    nickname = nickname.value,
                    onSaveNickname = { updatedName ->
                        nickname.value = updatedName
                        activityScope.launch(Dispatchers.IO) {
                            runCatching { identityStore.updateNickname(updatedName) }
                        }
                    },
                    voxSensitivity = voxSensitivity.intValue,
                    onVoxSensitivityChange = { value ->
                        val clamped = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
                        voxSensitivity.intValue = clamped
                        voxSensitivityDirty = true
                        service?.previewVoxSensitivity(clamped)
                    },
                    onVoxSensitivityCommit = { value ->
                        val clamped = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
                        voxSensitivity.intValue = clamped
                        persistVoxSensitivityAsync(clamped)
                    },
                    rnnoiseEnabled = rnnoiseEnabled.value,
                    logLines = diagnosticLogs.value,
                    logStatusHint = diagnosticHint.value,
                    onRefreshLogs = ::refreshDiagnostics,
                    onExportLogs = ::exportDiagnostics,
                    onCopyLogs = {
                        val copied = copyRedactedDiagnosticLogs(this@GroupActivity, diagnosticLogs.value)
                        diagnosticHint.value = if (copied) {
                            "已复制脱敏诊断日志"
                        } else {
                            "当前暂无可复制的日志"
                        }
                    },
                    entitlementSummary = entitlementSummary.value,
                    hasEntitlementToken = hasEntitlementToken.value,
                    entitlementBusy = entitlementBusy.value,
                    entitlementError = entitlementError.value,
                    showActivationDialog = showActivationDialog.value,
                    onOpenActivationDialog = {
                        entitlementError.value = null
                        showActivationDialog.value = true
                    },
                    onDismissActivationDialog = {
                        if (!entitlementBusy.value) {
                            entitlementError.value = null
                            showActivationDialog.value = false
                        }
                    },
                    onRedeemActivationCode = { codeInput, targetServerUrl ->
                        redeemActivationCodeAsync(codeInput, targetServerUrl)
                    },
                    onRefreshEntitlement = { targetServerUrl ->
                        refreshEntitlementAsync(targetServerUrl, silent = false)
                    },
                    onClearEntitlement = {
                        entitlementStore.clear()
                        hasEntitlementToken.value = false
                        entitlementSummary.value = null
                        entitlementError.value = null
                        state.value = state.value.copy(message = "已清除本地激活凭证")
                    }
                )
            }
        }
    }

    private fun refreshEntitlementAsync(rawServerUrl: String, silent: Boolean) {
        val token = entitlementStore.loadToken()
        if (token.isNullOrBlank()) {
            hasEntitlementToken.value = false
            entitlementSummary.value = null
            return
        }
        if (entitlementRefreshInFlight) return
        val normalizedUrl = runCatching { GroupCloudUrlPolicy.normalizeConfiguredUrl(rawServerUrl) }.getOrNull() ?: return
        entitlementRefreshInFlight = true
        if (!silent) entitlementBusy.value = true
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val deviceId = identityStore.getOrCreateDeviceId()
                HttpGroupCloudClient(
                    normalizedUrl,
                    entitlementTokenProvider = { token },
                    deviceIdProvider = { deviceId }
                ).use { client ->
                    client.fetchEntitlementStatus()
                }
            }
            runOnUiThread {
                entitlementRefreshInFlight = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                entitlementBusy.value = false
                result.onSuccess { latest ->
                    entitlementStore.saveSummary(latest)
                    hasEntitlementToken.value = true
                    entitlementSummary.value = entitlementStore.loadSummary()
                    entitlementError.value = null
                }.onFailure { error ->
                    if (error is GroupCloudException && error.code in setOf("ENTITLEMENT_INVALID", "ENTITLEMENT_REVOKED", "ENTITLEMENT_DEVICE_MISMATCH")) {
                        entitlementStore.clear()
                        hasEntitlementToken.value = false
                        entitlementSummary.value = null
                        if (!silent) state.value = state.value.copy(message = error.userMessage)
                    } else if (!silent) {
                        state.value = state.value.copy(
                            message = (error as? GroupCloudException)?.userMessage ?: "无法刷新云端额度，请检查网络连接"
                        )
                    }
                }
            }
        }
    }

    private fun redeemActivationCodeAsync(rawCode: String, rawServerUrl: String) {
        val normalizedCode = GroupActivationCodeValidator.normalizeOrNull(rawCode)
        if (normalizedCode == null) {
            entitlementError.value = "激活码格式或校验位无效，请核对后重试"
            return
        }
        val normalizedUrl = try {
            GroupCloudUrlPolicy.normalizeConfiguredUrl(rawServerUrl)
        } catch (error: IllegalArgumentException) {
            entitlementError.value = error.message ?: "云端服务地址无效"
            return
        }
        entitlementBusy.value = true
        entitlementError.value = null
        activityScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val deviceId = identityStore.getOrCreateDeviceId()
                val existingToken = entitlementStore.loadToken()
                HttpGroupCloudClient(
                    normalizedUrl,
                    entitlementTokenProvider = { existingToken },
                    deviceIdProvider = { deviceId }
                ).use { client ->
                    client.redeemActivationCode(normalizedCode, deviceId, BuildConfig.VERSION_NAME)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                entitlementBusy.value = false
                result.onSuccess { redeemed ->
                    val saved = runCatching { entitlementStore.saveRedeemResult(redeemed) }
                    if (saved.isFailure) {
                        entitlementError.value = "本机安全密钥存储不可用，无法加密保存激活凭证"
                        return@onSuccess
                    }
                    hasEntitlementToken.value = true
                    entitlementSummary.value = entitlementStore.loadSummary()
                    entitlementError.value = null
                    showActivationDialog.value = false
                    val summary = redeemed.summary
                    val actionLabel = when (redeemed.action) {
                        "STACKED" -> "已叠加新激活码"
                        "REBOUND" -> "已换绑并恢复激活码权益"
                        else -> "已激活云端对讲权益"
                    }
                    state.value = state.value.copy(
                        message = "$actionLabel，剩余 ${summary.remainingMinutes} 人·分钟（限 ${summary.maxMembers} 人）"
                    )
                }.onFailure { error ->
                    entitlementError.value = (error as? GroupCloudException)?.userMessage ?: "激活失败，请检查网络连接后重试"
                }
            }
        }
    }

    internal fun applySystemBarAppearance(mode: MotoComThemeMode) {
        val style = groupSystemBarStyle(mode)
        window.decorView.setBackgroundColor(style.windowBackgroundColor)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = style.statusBarColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = style.navigationBarColor
        }
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = style.isLightStatusBars
            isAppearanceLightNavigationBars = style.isLightNavigationBars
        }
    }

    private fun copyPlainText(label: String, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun refreshDiagnostics() {
        DiagnosticLog.forContext(this)?.recent { result ->
            val lines = result.getOrDefault(emptyList())
            runOnUiThread { diagnosticLogs.value = lines }
        }
    }

    private fun exportDiagnostics() {
        val access = DiagnosticLog.forContext(this)
        if (access == null) {
            diagnosticHint.value = "诊断日志服务尚未就绪"
            return
        }
        access.export { result ->
            val redactedResult = result.mapCatching(::redactDiagnosticExportFile)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                redactedResult.onSuccess { file ->
                    diagnosticHint.value = "已生成脱敏日志导出文件"
                    runCatching { startActivity(diagnosticExportChooser(this, file)) }
                        .onFailure { diagnosticHint.value = "无法打开系统分享面板" }
                }.onFailure {
                    diagnosticHint.value = "日志导出失败，请稍后重试"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = try {
            bindService(Intent(this, GroupIntercomService::class.java), connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (entitlementStore.loadToken() != null && !state.value.busy) {
            refreshEntitlementAsync(cloudPrefs.loadServerUrl(), silent = true)
        }
        if (pending && permissionsReturned) completeStart()
    }

    override fun onPause() {
        resumed = false
        if (voxSensitivityDirty) {
            persistVoxSensitivityAsync(voxSensitivity.intValue)
        }
        super.onPause()
    }

    private fun persistVoxSensitivityAsync(value: Int) {
        val clamped = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
        voxSensitivityDirty = false
        val boundService = service
        if (boundService != null) {
            boundService.groupVoxSensitivity(clamped)
        } else {
            GroupAudioControlStore.updateVoxSensitivityAsync(this, clamped)
        }
    }

    override fun onStop() {
        service?.removeGroupListener(listener)
        service = null
        if (bound) {
            bound = false
            unbindService(connection)
        }
        super.onStop()
    }

    override fun onDestroy() {
        pending = false
        pendingCode = null
        pendingNetwork = null
        activityScope.cancel()
        super.onDestroy()
    }

    internal fun requestStart(code: String?) {
        if (!resumed || pending || state.value.busy) return
        if (code != null && !code.matches(Regex("[0-9]{6}"))) return
        pending = true
        pendingCode = code
        pendingNetwork = null
        permissionsReturned = false
        val missing = missingGroupPromptPermissions(groupRequiredPermissions())
        if (missing.isEmpty()) {
            permissionsReturned = true
            completeStart()
        } else {
            requestPermissions(missing.toTypedArray(), 8056)
        }
    }

    internal fun requestNetworkStart(request: GroupCloudStartRequest) {
        if (!resumed || pending || state.value.busy) return
        val normalizedUrl = try {
            GroupCloudUrlPolicy.normalizeConfiguredUrl(request.serverUrl)
        } catch (error: IllegalArgumentException) {
            state.value = state.value.copy(message = error.message ?: "云端服务地址无效")
            return
        }
        if (request is GroupCloudStartRequest.Create && entitlementStore.loadToken().isNullOrBlank()) {
            hasEntitlementToken.value = false
            entitlementError.value = null
            showActivationDialog.value = true
            state.value = state.value.copy(message = "创建云端或双模房间前请先输入激活码，队员凭 6 位邀请码可直接加入")
            return
        }
        val normalizedRequest = when (request) {
            is GroupCloudStartRequest.Create -> request.copy(serverUrl = normalizedUrl)
            is GroupCloudStartRequest.Join -> request.copy(serverUrl = normalizedUrl)
        }
        pending = true
        pendingCode = null
        pendingNetwork = normalizedRequest
        permissionsReturned = false
        val basePermissions = if (normalizedRequest.mode == GroupMode.HYBRID) {
            groupRequiredPermissions()
        } else {
            networkGroupPromptPermissions()
        }
        val missing = missingGroupPromptPermissions(basePermissions, hybrid = normalizedRequest.mode == GroupMode.HYBRID)
        if (missing.isEmpty()) {
            permissionsReturned = true
            completeStart()
        } else {
            requestPermissions(missing.toTypedArray(), 8056)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8056 && pending) {
            permissionsReturned = true
            if (resumed) completeStart()
        }
    }

    private fun completeStart() {
        if (!pending || !resumed || isFinishing || isDestroyed) return
        val code = pendingCode
        val networkRequest = pendingNetwork
        pending = false
        pendingCode = null
        pendingNetwork = null
        val intent = if (networkRequest != null) {
            val isHybridRequest = networkRequest.mode == GroupMode.HYBRID
            if (isHybridRequest) {
                if (!hasGroupPermissions()) {
                    state.value = state.value.copy(message = "请授予麦克风与附近设备权限，再重试双模群组")
                    return
                }
            } else if (!hasNetworkGroupPermissions()) {
                state.value = state.value.copy(message = "请授予麦克风权限，再重试网络群组")
                return
            }
            Intent(this, GroupIntercomService::class.java)
                .setAction(
                    if (isHybridRequest) GroupIntercomService.ACTION_START_HYBRID_GROUP
                    else GroupIntercomService.ACTION_START_NETWORK_GROUP
                )
                .putExtra(GroupIntercomService.EXTRA_CLOUD_SERVER_URL, networkRequest.serverUrl)
                .apply {
                    when (networkRequest) {
                        is GroupCloudStartRequest.Create -> putExtra(GroupIntercomService.EXTRA_CLOUD_REQUIRE_APPROVAL, networkRequest.requireApproval)
                        is GroupCloudStartRequest.Join -> putExtra(GroupIntercomService.EXTRA_CLOUD_INVITE_CODE, networkRequest.inviteCode.value)
                    }
                }
        } else {
            if (!hasGroupPermissions()) {
                state.value = state.value.copy(message = "请授予麦克风与附近设备权限，再重试")
                return
            }
            Intent(this, GroupIntercomService::class.java).setAction(GroupIntercomService.ACTION_START_GROUP).apply {
                if (code != null) putExtra(GroupIntercomService.EXTRA_GROUP_CODE, code)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (_: Exception) {
            state.value = state.value.copy(message = "无法启动，请回到页面重试")
        }
    }
}

internal fun showDeveloperCloudSettings(allowCustomServer: Boolean, selectedMode: GroupMode): Boolean =
    allowCustomServer && selectedMode != GroupMode.LOCAL

@Composable
internal fun GroupScreen(
    state: GroupServiceState,
    onStart: (String?) -> Unit,
    onAction: (GroupSessionEvent) -> Unit,
    onRoute: (AudioRouteSelection) -> Unit,
    onVox: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onCopy: (String) -> Unit,
    onStartNetwork: (GroupCloudStartRequest) -> Unit = {},
    initialServerUrl: String = GroupCloudPreferences.DEFAULT_SERVER_URL,
    allowCustomServer: Boolean = BuildConfig.DEBUG,
    onSaveCloudSettings: (String) -> Unit = {},
    onResetCloudSettings: () -> Unit = {},
    themeMode: MotoComThemeMode = MotoComThemeMode.DAYLIGHT,
    onThemeChange: (MotoComThemeMode) -> Unit = {},
    nickname: String = "",
    onSaveNickname: (String) -> Unit = {},
    voxSensitivity: Int = DEFAULT_VOX_SENSITIVITY,
    onVoxSensitivityChange: (Int) -> Unit = {},
    onVoxSensitivityCommit: (Int) -> Unit = {},
    rnnoiseEnabled: Boolean = false,
    logLines: List<String> = emptyList(),
    logStatusHint: String? = null,
    onRefreshLogs: () -> Unit = {},
    onExportLogs: () -> Unit = {},
    onCopyLogs: () -> Unit = {},
    entitlementSummary: GroupEntitlementSummary? = null,
    hasEntitlementToken: Boolean = false,
    entitlementBusy: Boolean = false,
    entitlementError: String? = null,
    showActivationDialog: Boolean = false,
    onOpenActivationDialog: () -> Unit = {},
    onDismissActivationDialog: () -> Unit = {},
    onRedeemActivationCode: (String, String) -> Unit = { _, _ -> },
    onRefreshEntitlement: (String) -> Unit = {},
    onClearEntitlement: () -> Unit = {}
) {
    var selectedMode by remember { mutableStateOf(GroupMode.LOCAL) }
    var activeTheme by remember(themeMode) { mutableStateOf(themeMode) }
    var subpage by remember { mutableStateOf(GroupSubpage.MAIN) }
    var code by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    var developerServerDraft by remember { mutableStateOf(initialServerUrl) }
    var developerServerError by remember { mutableStateOf<String?>(null) }
    var activationCodeDraft by remember { mutableStateOf("") }
    var inviteInput by remember { mutableStateOf("") }
    var requireApproval by remember { mutableStateOf(true) }
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmNetworkOwnerExit by remember { mutableStateOf(false) }
    var roleTargetId by remember { mutableStateOf<String?>(null) }
    var activeInRoomSheet by remember { mutableStateOf<GroupInRoomSheet?>(null) }
    val snapshot = state.snapshot
    val room = snapshot?.view
    val isInSession = state.busy || (snapshot != null && snapshot.phase != GroupPhase.IDLE)
    val isNetwork = snapshot?.mode == GroupMode.NETWORK
    val isHybrid = snapshot?.mode == GroupMode.HYBRID
    val isCloudBacked = isNetwork || isHybrid
    val isCloudVoiceActive = isNetwork || (isHybrid && snapshot?.activeTransport == GroupTransport.CLOUD)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lobbyScrollState = rememberScrollState()
    val inRoomScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    val logsScrollState = rememberScrollState()

    LaunchedEffect(snapshot?.isOwner, room == null, snapshot?.mode) {
        if (snapshot?.isOwner != true || room == null) {
            confirmEnd = false
            confirmNetworkOwnerExit = false
            roleTargetId = null
        }
    }

    LaunchedEffect(isInSession, subpage, room == null) {
        if (!isInSession || subpage != GroupSubpage.MAIN) {
            activeInRoomSheet = null
        } else if (room == null && activeInRoomSheet is GroupInRoomSheet.Governance) {
            activeInRoomSheet = null
        }
    }

    LaunchedEffect(isInSession) {
        if (!isInSession) {
            inRoomScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(showActivationDialog) {
        if (!showActivationDialog) {
            activationCodeDraft = ""
        }
    }

    LaunchedEffect(subpage, lifecycleOwner) {
        if (subpage == GroupSubpage.LOGS) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    onRefreshLogs()
                    delay(2_000L)
                }
            }
        }
    }

    BackHandler(enabled = subpage != GroupSubpage.MAIN) {
        subpage = when (subpage) {
            GroupSubpage.LOGS -> GroupSubpage.SETTINGS
            GroupSubpage.SETTINGS -> GroupSubpage.MAIN
            GroupSubpage.MAIN -> GroupSubpage.MAIN
        }
    }

    val backdropMood = when {
        subpage == GroupSubpage.LOGS -> GlassBackdropMood.NETWORK
        subpage == GroupSubpage.SETTINGS -> GlassBackdropMood.LOBBY
        !isInSession -> GlassBackdropMood.LOBBY
        snapshot?.currentChannel is GroupChannel.Private -> GlassBackdropMood.PRIVATE
        isNetwork -> GlassBackdropMood.NETWORK
        isHybrid && snapshot?.activeTransport == GroupTransport.CLOUD -> GlassBackdropMood.CLOUD
        else -> GlassBackdropMood.LOCAL
    }

    val toggleThemeAction = {
        val next = if (activeTheme == MotoComThemeMode.DAYLIGHT) {
            MotoComThemeMode.NIGHTRIDE
        } else {
            MotoComThemeMode.DAYLIGHT
        }
        activeTheme = next
        onThemeChange(next)
    }

    val selectThemeAction: (MotoComThemeMode) -> Unit = { next ->
        activeTheme = next
        onThemeChange(next)
    }

    val openDeveloperSettingsAction = {
        developerServerDraft = serverUrl
        developerServerError = null
        showDeveloperSettings = true
    }

    GroupGlassTheme(themeMode = activeTheme) {
        val colors = GroupGlassPalette.current
        Box(modifier = Modifier.fillMaxSize()) {
            GroupGlassBackdrop(mood = backdropMood)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                val viewportHeight = maxHeight
                val pageScrollState = when (subpage) {
                    GroupSubpage.MAIN -> if (isInSession) inRoomScrollState else lobbyScrollState
                    GroupSubpage.SETTINGS -> settingsScrollState
                    GroupSubpage.LOGS -> logsScrollState
                }
                val usePinnedFooterLayout = subpage == GroupSubpage.MAIN
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .verticalScroll(pageScrollState)
                        .then(if (usePinnedFooterLayout) Modifier.heightIn(min = viewportHeight) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = if (usePinnedFooterLayout) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.spacedBy(12.dp)
                    }
                ) {
                    when (subpage) {
                        GroupSubpage.SETTINGS -> {
                            GroupSettingsContent(
                                nickname = nickname,
                                onSaveNickname = onSaveNickname,
                                inCall = isInSession,
                                cloudVoiceActive = isCloudVoiceActive,
                                entitlementSummary = entitlementSummary,
                                hasEntitlementToken = hasEntitlementToken,
                                entitlementBusy = entitlementBusy,
                                onOpenActivationDialog = onOpenActivationDialog,
                                onRefreshEntitlement = { onRefreshEntitlement(serverUrl) },
                                onClearEntitlement = onClearEntitlement,
                                themeMode = activeTheme,
                                onSelectTheme = selectThemeAction,
                                route = state.route,
                                onRoute = onRoute,
                                voxEnabled = state.vox,
                                onVox = onVox,
                                voxSensitivity = voxSensitivity,
                                onVoxSensitivityChange = onVoxSensitivityChange,
                                onVoxSensitivityCommit = onVoxSensitivityCommit,
                                rnnoiseEnabled = rnnoiseEnabled,
                                onOpenLogs = {
                                    onRefreshLogs()
                                    subpage = GroupSubpage.LOGS
                                },
                                onOpenSystemPermissions = onSettings,
                                allowCustomServer = allowCustomServer,
                                onOpenDeveloperSettings = openDeveloperSettingsAction,
                                onBack = { subpage = GroupSubpage.MAIN }
                            )
                        }
                        GroupSubpage.LOGS -> {
                            GroupLogsContent(
                                logLines = logLines,
                                statusHint = logStatusHint,
                                onExportLogs = onExportLogs,
                                onCopyLogs = onCopyLogs,
                                onBack = { subpage = GroupSubpage.SETTINGS }
                            )
                        }
                        GroupSubpage.MAIN -> {
                            if (!isInSession) {
                                GroupLobbyContent(
                                    statusMessage = state.message,
                                    selectedMode = selectedMode,
                                    onSelectMode = { selectedMode = it },
                                    themeMode = activeTheme,
                                    onToggleTheme = toggleThemeAction,
                                    onOpenSettings = { subpage = GroupSubpage.SETTINGS },
                                    route = state.route,
                                    voxEnabled = state.vox,
                                    localCode = code,
                                    onLocalCodeChange = { code = it },
                                    inviteInput = inviteInput,
                                    onInviteInputChange = { inviteInput = it },
                                    requireApproval = requireApproval,
                                    onRequireApprovalChange = { requireApproval = it },
                                    serverUrl = serverUrl,
                                    allowCustomServer = allowCustomServer,
                                    onOpenDeveloperSettings = openDeveloperSettingsAction,
                                    onStartLocal = onStart,
                                    onStartNetwork = onStartNetwork,
                                    entitlementSummary = entitlementSummary,
                                    hasEntitlementToken = hasEntitlementToken,
                                    onOpenActivationDialog = onOpenActivationDialog
                                )
                            } else {
                                GroupInRoomContent(
                                    state = state,
                                    snapshot = snapshot,
                                    room = room,
                                    themeMode = activeTheme,
                                    onToggleTheme = toggleThemeAction,
                                    onOpenSettings = { subpage = GroupSubpage.SETTINGS },
                                    onBack = onBack,
                                    onCopy = onCopy,
                                    onAction = onAction,
                                    onOpenSheet = { activeInRoomSheet = it },
                                    onRequestLeaveDialog = {
                                        when {
                                            isCloudBacked && snapshot?.isOwner == true && room != null -> confirmNetworkOwnerExit = true
                                            snapshot?.host == true -> confirmEnd = true
                                            else -> onAction(GroupSessionEvent.Leave)
                                        }
                                    },
                                    onSelectRoleTarget = { roleTargetId = it }
                                )
                            }
                        }
                    }
                }
            }

            val currentSheet = activeInRoomSheet
            if (currentSheet != null && subpage == GroupSubpage.MAIN && isInSession) {
                GroupInRoomSheetOverlay(
                    activeSheet = currentSheet,
                    state = state,
                    snapshot = snapshot,
                    room = room,
                    onUpdateSheet = { activeInRoomSheet = it },
                    onAction = onAction,
                    onRoute = onRoute,
                    onVox = onVox,
                    onOpenSettings = { subpage = GroupSubpage.SETTINGS }
                )
            }
        }

        if (showDeveloperSettings) {
            AlertDialog(
                onDismissRequest = {
                    showDeveloperSettings = false
                    developerServerError = null
                },
                containerColor = colors.cardElevated,
                title = { Text("开发者设置", color = colors.textPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("仅调试构建可用。生产构建固定使用内置云端地址。", color = colors.textSecondary)
                        OutlinedTextField(
                            value = developerServerDraft,
                            onValueChange = {
                                developerServerDraft = it
                                developerServerError = null
                            },
                            label = { Text("调试云端服务地址") },
                            modifier = Modifier.fillMaxWidth().testTag("network_server_input"),
                            singleLine = true
                        )
                        developerServerError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(
                            onClick = {
                                developerServerDraft = GroupCloudPreferences.DEFAULT_SERVER_URL
                                developerServerError = null
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("developer_server_reset")
                        ) { Text("恢复生产地址") }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val normalized = try {
                                GroupCloudUrlPolicy.normalizeConfiguredUrl(developerServerDraft)
                            } catch (error: IllegalArgumentException) {
                                developerServerError = error.message ?: "云端服务地址无效"
                                null
                            }
                            if (normalized != null) {
                                serverUrl = normalized
                                if (normalized == GroupCloudPreferences.DEFAULT_SERVER_URL) onResetCloudSettings()
                                else onSaveCloudSettings(normalized)
                                showDeveloperSettings = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("developer_server_save")
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showDeveloperSettings = false
                            developerServerError = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("取消") }
                }
            )
        }

        val roleTarget = if (snapshot?.mode != GroupMode.NETWORK && snapshot?.isOwner == true && room != null && roleTargetId != null) {
            room.members.singleOrNull { it.lease.deviceId == roleTargetId && it.status != GroupMemberStatus.WAITING }
        } else null
        if (roleTarget != null && snapshot != null) {
            val targetId = roleTarget.lease.deviceId
            AlertDialog(
                onDismissRequest = { roleTargetId = null },
                containerColor = colors.cardElevated,
                title = { Text("修改 ${snapshot.names[targetId] ?: "骑士"} 的角色", color = colors.textPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            GroupRole.MEMBER to "普通成员（仅公共与私聊）",
                            GroupRole.RIDING_OFFICER to "骑行官（可进入领队频道）",
                            GroupRole.HEAD to "头车（全队唯一）",
                            GroupRole.TAIL to "尾车（全队唯一）"
                        ).forEach { (role, label) ->
                            TextButton(
                                onClick = {
                                    roleTargetId = null
                                    onAction(GroupSessionEvent.SetRole(targetId, role))
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("role_option_${role.name}")
                            ) {
                                Text((if (roleTarget.role == role) "✓ " else "") + label)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { roleTargetId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
                }
            )
        }

        if (confirmEnd) {
            AlertDialog(
                onDismissRequest = { confirmEnd = false },
                containerColor = colors.cardElevated,
                title = { Text("结束全队对讲？", color = colors.textPrimary) },
                text = {
                    Text(
                        "本地模式不支持转让群主，离开将直接解散全队，所有成员都会退出且当前房间码立即失效。",
                        color = colors.textSecondary
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmEnd = false; onAction(GroupSessionEvent.Leave) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("结束房间") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { confirmEnd = false },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("继续对讲") }
                }
            )
        }

        if (confirmNetworkOwnerExit && snapshot != null && room != null) {
            OwnerLeaveSelectionDialog(
                snapshot = snapshot,
                room = room,
                onTransferAndLeave = { targetId ->
                    confirmNetworkOwnerExit = false
                    onAction(GroupSessionEvent.TransferOwner(targetId, alsoLeave = true))
                },
                onDisband = {
                    confirmNetworkOwnerExit = false
                    onAction(GroupSessionEvent.EndRoom)
                },
                onDismiss = { confirmNetworkOwnerExit = false }
            )
        }

        if (showActivationDialog) {
            val validDraft = GroupActivationCodeValidator.isValid(activationCodeDraft)
            AlertDialog(
                onDismissRequest = {
                    if (!entitlementBusy) {
                        activationCodeDraft = ""
                        onDismissActivationDialog()
                    }
                },
                containerColor = colors.cardElevated,
                title = {
                    Text(
                        text = if (hasEntitlementToken) "叠加或换绑激活码" else "输入云端对讲激活码",
                        color = colors.textPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "本地直连永久免费。仅建房房主需要激活码，队员输入 6 位邀请码即可直接加入。",
                            color = colors.textSecondary
                        )
                        OutlinedTextField(
                            value = activationCodeDraft,
                            onValueChange = { activationCodeDraft = it.take(24) },
                            label = { Text("激活码（MOTO-XXXX-XXXX-XXXX）") },
                            singleLine = true,
                            enabled = !entitlementBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("activation_code_input")
                        )
                        if (activationCodeDraft.isNotBlank() && !validDraft && entitlementError == null) {
                            Text(
                                text = "请粘贴完整激活码（自动兼容大小写与连字符）",
                                color = colors.textMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        entitlementError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("activation_code_error")
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onRedeemActivationCode(activationCodeDraft, serverUrl) },
                        enabled = !entitlementBusy && activationCodeDraft.isNotBlank(),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("activation_code_submit")
                    ) {
                        Text(if (entitlementBusy) "验证中…" else "确认激活")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            activationCodeDraft = ""
                            onDismissActivationDialog()
                        },
                        enabled = !entitlementBusy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("activation_code_cancel")
                    ) { Text("取消") }
                }
            )
        }
    }
}

@Preview(widthDp = 320, heightDp = 640, fontScale = 1.5f)
@Preview(widthDp = 840, heightDp = 700)
@Composable
private fun GroupScreenPreview() {
    GroupGlassTheme {
        GroupScreen(GroupServiceState(), {}, {}, {}, {}, {}, {}, {})
    }
}
