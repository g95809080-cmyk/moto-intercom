package com.kuma.motointercom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.kuma.motointercom.group.*
import com.kuma.motointercom.group.cloud.*
import com.kuma.motointercom.group.hybrid.*
import com.kuma.motointercom.group.network.GroupGoOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object GroupAudioControlStore {
    private val stateLock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "motocom-group-audio-prefs").apply { isDaemon = true }
    }
    private var initializedAppContext: Context? = null
    private var cachedSettings: AudioControlSettings? = null
    private var pendingAppContext: Context? = null
    private var pendingVoxEnabled: Boolean? = null
    private var pendingVoxSensitivity: Int? = null

    @Volatile
    internal var diskLoader: (Context) -> AudioControlSettings = { context ->
        AudioControlPreferences(context).load()
    }

    @Volatile
    internal var diskWriter: (Context, AudioControlSettings) -> Boolean = { context, settings ->
        AudioControlPreferences(context).saveVoxSettings(settings)
    }

    private fun resetPendingIfContextChangedLocked(appContext: Context) {
        if (pendingAppContext !== appContext) {
            pendingAppContext = appContext
            pendingVoxEnabled = null
            pendingVoxSensitivity = null
        }
    }

    private fun loadOrHydrate(appContext: Context): AudioControlSettings {
        synchronized(stateLock) {
            val current = cachedSettings
            if (current != null && initializedAppContext === appContext) {
                return current
            }
        }
        val loadedFromDisk = diskLoader(appContext)
        return synchronized(stateLock) {
            val existing = cachedSettings
            if (existing != null && initializedAppContext === appContext) {
                existing
            } else {
                val pendingEnabled = if (pendingAppContext === appContext) pendingVoxEnabled else null
                val pendingSensitivity = if (pendingAppContext === appContext) pendingVoxSensitivity else null
                val merged = loadedFromDisk.copy(
                    voxEnabled = pendingEnabled ?: loadedFromDisk.voxEnabled,
                    voxSensitivity = pendingSensitivity ?: loadedFromDisk.voxSensitivity
                ).normalized()
                initializedAppContext = appContext
                cachedSettings = merged
                pendingAppContext = appContext
                pendingVoxEnabled = null
                pendingVoxSensitivity = null
                merged
            }
        }
    }

    fun load(context: Context): AudioControlSettings =
        loadOrHydrate(context.applicationContext)

    fun isHydrated(context: Context): Boolean {
        val appContext = context.applicationContext
        return synchronized(stateLock) {
            cachedSettings != null && initializedAppContext === appContext
        }
    }

    fun peek(context: Context): AudioControlSettings {
        val appContext = context.applicationContext
        return synchronized(stateLock) {
            cachedSettings?.takeIf { initializedAppContext === appContext } ?: AudioControlSettings(
                voxEnabled = if (pendingAppContext === appContext) pendingVoxEnabled ?: true else true,
                voxSensitivity = if (pendingAppContext === appContext) {
                    pendingVoxSensitivity ?: DEFAULT_VOX_SENSITIVITY
                } else {
                    DEFAULT_VOX_SENSITIVITY
                }
            ).normalized()
        }
    }

    fun hydrateAsync(
        context: Context,
        onLoaded: ((AudioControlSettings) -> Unit)? = null
    ): Future<AudioControlSettings> {
        val appContext = context.applicationContext
        return ioExecutor.submit<AudioControlSettings> {
            val hydrated = loadOrHydrate(appContext)
            onLoaded?.invoke(hydrated)
            hydrated
        }
    }

    fun updateVoxEnabled(context: Context, enabled: Boolean): AudioControlSettings {
        val appContext = context.applicationContext
        val snapshot = synchronized(stateLock) {
            val existing = cachedSettings?.takeIf { initializedAppContext === appContext }
            if (existing != null) {
                val next = existing.copy(voxEnabled = enabled).normalized()
                cachedSettings = next
                next
            } else {
                resetPendingIfContextChangedLocked(appContext)
                pendingVoxEnabled = enabled
                AudioControlSettings(
                    voxEnabled = enabled,
                    voxSensitivity = pendingVoxSensitivity ?: DEFAULT_VOX_SENSITIVITY
                ).normalized()
            }
        }
        enqueueDiskWrite(appContext)
        return snapshot
    }

    fun updateVoxSensitivity(context: Context, sensitivity: Int): AudioControlSettings {
        val appContext = context.applicationContext
        return updateVoxSensitivityInternal(appContext, sensitivity).first
    }

    fun updateVoxSensitivityAsync(context: Context, sensitivity: Int): Future<AudioControlSettings> {
        val appContext = context.applicationContext
        return updateVoxSensitivityInternal(appContext, sensitivity).second
    }

    private fun updateVoxSensitivityInternal(
        appContext: Context,
        sensitivity: Int
    ): Pair<AudioControlSettings, Future<AudioControlSettings>> {
        val clamped = sensitivity.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
        val snapshot = synchronized(stateLock) {
            val existing = cachedSettings?.takeIf { initializedAppContext === appContext }
            if (existing != null) {
                val next = existing.copy(voxSensitivity = clamped).normalized()
                cachedSettings = next
                next
            } else {
                resetPendingIfContextChangedLocked(appContext)
                pendingVoxSensitivity = clamped
                AudioControlSettings(
                    voxEnabled = pendingVoxEnabled ?: true,
                    voxSensitivity = clamped
                ).normalized()
            }
        }
        return snapshot to enqueueDiskWrite(appContext)
    }

    private fun enqueueDiskWrite(appContext: Context): Future<AudioControlSettings> =
        ioExecutor.submit<AudioControlSettings> {
            val latest = loadOrHydrate(appContext)
            diskWriter(appContext, latest)
            latest
        }

    fun awaitPendingWrites() {
        ioExecutor.submit {}.get()
    }
}

internal open class GroupIntercomService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listeners = mutableSetOf<(GroupServiceState) -> Unit>()
    private var runtime: GroupRuntimeHandle? = null
    private var starting: UUID? = null
    private var destroyed = false
    private var audioHydrationInFlight = false
    private var pendingPreviewVoxSensitivity: Int? = null
    private var appliedRuntimeControls: AudioControlSettings? = null
    private var state = GroupServiceState()
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service() = this@GroupIntercomService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        hydrateIdleAudioPreferences()
    }

    internal fun addGroupListener(listener: (GroupServiceState) -> Unit) {
        if (runtime == null && starting == null) {
            hydrateIdleAudioPreferences()
        }
        listeners += listener
        listener(state)
    }

    internal fun removeGroupListener(listener: (GroupServiceState) -> Unit) {
        listeners -= listener
    }

    internal fun groupAction(event: GroupSessionEvent) {
        if (event == GroupSessionEvent.Leave && starting != null) {
            starting = null
            publish(idleState("已取消"))
            stopForegroundCompat()
            stopSelf()
        } else runtime?.dispatch(event)
    }

    internal fun groupRoute(selection: AudioRouteSelection) {
        AudioRoutePreferences(this).save(selection)
        runtime?.route(selection)
        publish(state.copy(route = selection))
    }

    internal fun groupVox(value: Boolean) {
        GroupAudioControlStore.updateVoxEnabled(this, value)
        appliedRuntimeControls = appliedRuntimeControls?.copy(voxEnabled = value)
        runtime?.vox(value)
        publish(state.copy(vox = value))
    }

    internal fun previewVoxSensitivity(value: Int) {
        val clamped = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
        pendingPreviewVoxSensitivity = clamped
        appliedRuntimeControls = appliedRuntimeControls?.copy(voxSensitivity = clamped)
        runtime?.voxSensitivity(clamped)
    }

    internal fun groupVoxSensitivity(value: Int) {
        val clamped = value.coerceIn(MIN_VOX_SENSITIVITY, MAX_VOX_SENSITIVITY)
        pendingPreviewVoxSensitivity = null
        GroupAudioControlStore.updateVoxSensitivity(this, clamped)
        appliedRuntimeControls = appliedRuntimeControls?.copy(voxSensitivity = clamped)
        runtime?.voxSensitivity(clamped)
    }

    private fun ensureAudioPreferencesHydrated() {
        if (destroyed || audioHydrationInFlight || GroupAudioControlStore.isHydrated(this)) return
        audioHydrationInFlight = true
        GroupAudioControlStore.hydrateAsync(this) {
            scope.launch {
                audioHydrationInFlight = false
                if (destroyed) return@launch
                val latest = GroupAudioControlStore.peek(this@GroupIntercomService)
                val activeRuntime = runtime
                if (activeRuntime != null) {
                    val applied = appliedRuntimeControls
                    val targetSensitivity = pendingPreviewVoxSensitivity ?: latest.voxSensitivity
                    if (applied == null || applied.voxEnabled != latest.voxEnabled) {
                        activeRuntime.vox(latest.voxEnabled)
                    }
                    if (applied == null || applied.voxSensitivity != targetSensitivity) {
                        activeRuntime.voxSensitivity(targetSensitivity)
                    }
                    appliedRuntimeControls = AudioControlSettings(
                        voxEnabled = latest.voxEnabled,
                        voxSensitivity = targetSensitivity,
                        rnnoiseEnabled = latest.rnnoiseEnabled
                    ).normalized()
                }
                if (state.vox != latest.voxEnabled) {
                    publish(state.copy(vox = latest.voxEnabled))
                }
            }
        }
    }

    private fun effectiveRuntimeAudioControls(): AudioControlSettings {
        val base = GroupAudioControlStore.peek(this)
        val preview = pendingPreviewVoxSensitivity ?: return base
        return base.copy(voxSensitivity = preview).normalized()
    }

    private fun hydrateIdleAudioPreferences() {
        state = state.copy(
            route = AudioRoutePreferences(this).load(),
            vox = GroupAudioControlStore.peek(this).voxEnabled
        )
        ensureAudioPreferencesHydrated()
    }

    private fun idleState(message: String): GroupServiceState = GroupServiceState(
        message = message,
        route = AudioRoutePreferences(this).load(),
        vox = GroupAudioControlStore.peek(this).voxEnabled
    )

    internal open fun identityStore(): LocalIdentityStore = DataStoreLocalIdentityStore(this)

    internal open fun createRuntime(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = createLocalRuntimeInstance(
        endpoint = endpoint,
        nickname = nickname,
        initialRoute = state.route,
        initialControls = effectiveRuntimeAudioControls(),
        onSnapshot = onSnapshot,
        onLabel = onLabel,
        onReleased = onReleased
    )

    internal open fun createLocalRuntimeInstance(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        initialRoute: AudioRouteSelection,
        initialControls: AudioControlSettings,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = GroupRuntime(
        this, endpoint, nickname, initialRoute, initialControls, onSnapshot, onLabel, onReleased
    )

    internal open fun createCloudRuntime(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = GroupCloudRuntime(
        context = this,
        endpoint = endpoint,
        nickname = nickname,
        request = request,
        initialRoute = state.route,
        onSnapshot = onSnapshot,
        onAudioLabel = onLabel,
        onReleased = onReleased
    )

    internal open fun createHybridRuntime(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = createHybridRuntimeInstance(
        endpoint = endpoint,
        nickname = nickname,
        request = request,
        initialRoute = state.route,
        initialControls = effectiveRuntimeAudioControls(),
        onSnapshot = onSnapshot,
        onLabel = onLabel,
        onReleased = onReleased
    )

    internal open fun createHybridRuntimeInstance(
        endpoint: GroupAuthEndpoint,
        nickname: String,
        request: GroupCloudStartRequest,
        initialRoute: AudioRouteSelection,
        initialControls: AudioControlSettings,
        onSnapshot: (GroupSessionSnapshot) -> Unit,
        onLabel: (String) -> Unit,
        onReleased: () -> Unit
    ): GroupRuntimeHandle = GroupHybridRuntime(
        context = this,
        endpoint = endpoint,
        nickname = nickname,
        request = request,
        initialRoute = initialRoute,
        initialControls = initialControls,
        onSnapshot = onSnapshot,
        onAudioLabel = onLabel,
        onReleased = onReleased
    )

    internal open fun available(): Boolean = hasGroupPermissions() &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    internal open fun networkAvailable(): Boolean = hasNetworkGroupPermissions()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GROUP -> {
                val raw = intent.getStringExtra(EXTRA_GROUP_CODE)
                if (raw != null && !raw.matches(Regex("[0-9]{6}"))) {
                    publish(state.copy(message = "请输入六位数字房间码"))
                    if (runtime == null && starting == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                startGroup(raw?.let(::GroupJoinCode))
            }
            ACTION_START_NETWORK_GROUP,
            ACTION_START_HYBRID_GROUP -> {
                val targetMode = if (intent.action == ACTION_START_HYBRID_GROUP) GroupMode.HYBRID else GroupMode.NETWORK
                val rawUrl = intent.getStringExtra(EXTRA_CLOUD_SERVER_URL).orEmpty()
                val normalizedUrl = try {
                    GroupCloudUrlPolicy.normalizeConfiguredUrl(rawUrl)
                } catch (error: IllegalArgumentException) {
                    publish(state.copy(message = error.message ?: "云端服务地址无效"))
                    if (runtime == null && starting == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                val rawInvite = intent.getStringExtra(EXTRA_CLOUD_INVITE_CODE)
                val request: GroupCloudStartRequest = if (rawInvite != null) {
                    val invite = try {
                        GroupCloudInviteCode(rawInvite)
                    } catch (_: IllegalArgumentException) {
                        publish(state.copy(message = "请输入有效的 6 位数字邀请码"))
                        if (runtime == null && starting == null) stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    GroupCloudStartRequest.Join(normalizedUrl, invite, targetMode)
                } else {
                    val requireApproval = intent.getBooleanExtra(EXTRA_CLOUD_REQUIRE_APPROVAL, true)
                    GroupCloudStartRequest.Create(normalizedUrl, requireApproval, targetMode)
                }
                if (targetMode == GroupMode.HYBRID) startHybridGroup(request) else startNetworkGroup(request)
            }
            else -> {
                if (runtime == null && starting == null) stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (runtime == null && starting == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun startNetworkGroup(request: GroupCloudStartRequest) {
        if (destroyed) return
        if (starting != null || runtime != null || GroupRuntimeOwnership.hasOwner() ||
            GroupGoOwnership.process.hasOwner() || LegacyRuntimeOwnership.hasOwner()) {
            publish(state.copy(message = "正在对讲或释放网络，请稍后重试"))
            return
        }
        if (!networkAvailable()) {
            publish(idleState("请授予麦克风权限后再使用网络群组"))
            return
        }
        val token = UUID.randomUUID()
        starting = token
        state = GroupServiceState(
            message = "正在连接云端网络群组",
            busy = true,
            route = AudioRoutePreferences(this).load(),
            vox = GroupAudioControlStore.peek(this).voxEnabled
        )
        try {
            getSystemService(NotificationManager::class.java).cancel(TERMINAL_NOTIFICATION_ID)
            startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            starting = null
            publish(idleState("无法启动前台对讲，请返回页面重试"))
            return
        }
        publish(state)
        ensureAudioPreferencesHydrated()
        scope.launch {
            try {
                val identity = identityStore()
                val device = identity.getOrCreateDeviceId()
                val nickname = identity.getNickname().trim().ifBlank {
                    "骑士 ${device.replace("-", "").takeLast(6).uppercase(java.util.Locale.ROOT)}"
                }
                if (starting != token || destroyed) return@launch
                val initialControls = effectiveRuntimeAudioControls()
                lateinit var created: GroupRuntimeHandle
                created = createCloudRuntime(
                    endpoint = GroupAuthEndpoint(device, UUID.randomUUID().toString()),
                    nickname = nickname,
                    request = request,
                    onSnapshot = { snapshot ->
                        if (runtime === created && !destroyed) {
                            publish(state.copy(snapshot = snapshot, message = snapshot.message, busy = true))
                        }
                    },
                    onLabel = { label ->
                        if (runtime === created && !destroyed) publish(state.copy(audioLabel = label))
                    },
                    onReleased = {
                        if (runtime === created) {
                            runtime = null
                            appliedRuntimeControls = null
                            pendingPreviewVoxSensitivity = null
                            if (!destroyed) {
                                publish(state.copy(snapshot = created.snapshot,
                                    message = created.snapshot.message, busy = false))
                                postTerminalNotificationIfNeeded(created.snapshot)
                            }
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                )
                runtime = created
                appliedRuntimeControls = initialControls
                starting = null
                try {
                    created.start(null)
                } catch (error: Exception) {
                    created.close()
                    val msg = error.message?.takeIf { it.isNotBlank() } ?: "网络群组启动失败，请检查网络或服务地址"
                    if (runtime === created) publish(state.copy(message = msg))
                    else if (runtime == null && !destroyed) publish(idleState(msg))
                }
            } catch (_: Exception) {
                if (starting == token && !destroyed) {
                    starting = null
                    publish(idleState("无法准备设备身份，请重试"))
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun startHybridGroup(request: GroupCloudStartRequest) {
        if (destroyed) return
        if (starting != null || runtime != null || GroupRuntimeOwnership.hasOwner() ||
            GroupGoOwnership.process.hasOwner() || LegacyRuntimeOwnership.hasOwner()) {
            publish(state.copy(message = "正在对讲或释放网络，请稍后重试"))
            return
        }
        if (!available()) {
            publish(idleState("请检查麦克风和附近设备权限；双模群组需要 Wi-Fi Direct 与 BLE"))
            return
        }
        val token = UUID.randomUUID()
        starting = token
        state = GroupServiceState(
            message = "正在连接双模群组（本地优先）",
            busy = true,
            route = AudioRoutePreferences(this).load(),
            vox = GroupAudioControlStore.peek(this).voxEnabled
        )
        try {
            getSystemService(NotificationManager::class.java).cancel(TERMINAL_NOTIFICATION_ID)
            startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            starting = null
            publish(idleState("无法启动前台对讲，请返回页面重试"))
            return
        }
        publish(state)
        ensureAudioPreferencesHydrated()
        scope.launch {
            try {
                val identity = identityStore()
                val device = identity.getOrCreateDeviceId()
                val nickname = identity.getNickname().trim().ifBlank {
                    "骑士 ${device.replace("-", "").takeLast(6).uppercase(java.util.Locale.ROOT)}"
                }
                if (starting != token || destroyed) return@launch
                val initialControls = effectiveRuntimeAudioControls()
                lateinit var created: GroupRuntimeHandle
                created = createHybridRuntime(
                    endpoint = GroupAuthEndpoint(device, UUID.randomUUID().toString()),
                    nickname = nickname,
                    request = request,
                    onSnapshot = { snapshot ->
                        if (runtime === created && !destroyed) {
                            publish(state.copy(snapshot = snapshot, message = snapshot.message, busy = true))
                        }
                    },
                    onLabel = { label ->
                        if (runtime === created && !destroyed) publish(state.copy(audioLabel = label))
                    },
                    onReleased = {
                        if (runtime === created) {
                            runtime = null
                            appliedRuntimeControls = null
                            pendingPreviewVoxSensitivity = null
                            if (!destroyed) {
                                publish(state.copy(snapshot = created.snapshot,
                                    message = created.snapshot.message, busy = false))
                                postTerminalNotificationIfNeeded(created.snapshot)
                            }
                            stopForegroundCompat()
                            stopSelf()
                        }
                    }
                )
                runtime = created
                appliedRuntimeControls = initialControls
                starting = null
                try {
                    created.start(null)
                } catch (error: Exception) {
                    created.close()
                    val msg = error.message?.takeIf { it.isNotBlank() } ?: "双模群组启动失败，请检查网络或服务地址"
                    if (runtime === created) publish(state.copy(message = msg))
                    else if (runtime == null && !destroyed) publish(idleState(msg))
                }
            } catch (_: Exception) {
                if (starting == token && !destroyed) {
                    starting = null
                    publish(idleState("无法准备设备身份，请重试"))
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun startGroup(code: GroupJoinCode?) {
        if (destroyed) return
        if (starting != null || runtime != null || GroupRuntimeOwnership.hasOwner() ||
            GroupGoOwnership.process.hasOwner() || LegacyRuntimeOwnership.hasOwner()) {
            publish(state.copy(message = "正在对讲或释放网络，请稍后重试"))
            return
        }
        if (!available()) {
            publish(idleState("请检查麦克风和附近设备权限；本地群组需要 Wi-Fi Direct 与 BLE"))
            return
        }
        val token = UUID.randomUUID()
        starting = token
        state = GroupServiceState(message = "正在准备本地群组", busy = true,
            route = AudioRoutePreferences(this).load(), vox = GroupAudioControlStore.peek(this).voxEnabled)
        try {
            getSystemService(NotificationManager::class.java).cancel(TERMINAL_NOTIFICATION_ID)
            startForeground(NOTIFICATION_ID, notification())
        } catch (_: Exception) {
            starting = null
            publish(idleState("无法启动前台对讲，请返回页面重试"))
            return
        }
        publish(state)
        ensureAudioPreferencesHydrated()
        scope.launch {
            try {
                val identity = identityStore()
                val device = identity.getOrCreateDeviceId()
                val nickname = identity.getNickname().trim().ifBlank {
                    "骑士 ${device.replace("-", "").takeLast(6).uppercase(java.util.Locale.ROOT)}"
                }
                if (starting != token || destroyed) return@launch
                val initialControls = effectiveRuntimeAudioControls()
                lateinit var created: GroupRuntimeHandle
                created = createRuntime(GroupAuthEndpoint(device, UUID.randomUUID().toString()), nickname,
                    { snapshot -> if (runtime === created && !destroyed) {
                        publish(state.copy(snapshot = snapshot, message = snapshot.message, busy = true))
                    } },
                    { label -> if (runtime === created && !destroyed) publish(state.copy(audioLabel = label)) },
                    { if (runtime === created) {
                        runtime = null
                        appliedRuntimeControls = null
                        pendingPreviewVoxSensitivity = null
                        if (!destroyed) {
                            publish(state.copy(snapshot = created.snapshot,
                                message = created.snapshot.message, busy = false))
                            postTerminalNotificationIfNeeded(created.snapshot)
                        }
                        stopForegroundCompat()
                        stopSelf()
                    } })
                runtime = created
                appliedRuntimeControls = initialControls
                starting = null
                try {
                    created.start(code)
                } catch (_: Exception) {
                    created.close()
                    if (runtime === created) publish(state.copy(message = "群组启动失败，正在释放网络"))
                    else if (runtime == null && !destroyed) publish(idleState("群组启动失败，请检查无线网络后重试"))
                }
            } catch (_: Exception) {
                if (starting == token && !destroyed) {
                    starting = null
                    publish(idleState("无法准备设备身份，请重试"))
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun publish(value: GroupServiceState) {
        if (destroyed) return
        state = value
        listeners.toList().forEach { listener -> runCatching { listener(value) } }
        if (runtime != null || starting != null) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "群组对讲", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_rider_24)
            .setContentTitle(notificationTitle())
            .setContentText(state.message)
            .setContentIntent(contentIntent(0))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun notificationTitle(): String {
        val snapshot = state.snapshot ?: return "摩声群组 · 准备中"
        if (snapshot.mode == GroupMode.NETWORK) {
            return when {
                snapshot.phase == GroupPhase.WAITING -> "摩声网络群组 · 等待审批"
                snapshot.phase == GroupPhase.RECONNECTING -> "摩声网络群组 · 公共频道 · 恢复中"
                snapshot.view != null -> "摩声网络群组 · 公共频道"
                else -> "摩声网络群组 · 连接中"
            }
        }
        if (snapshot.mode == GroupMode.HYBRID) {
            if (snapshot.phase == GroupPhase.WAITING) return "摩声双模群组 · 等待审批"
            if (snapshot.phase == GroupPhase.RECONNECTING) return "摩声双模群组 · 恢复中"
            if (snapshot.view == null) return "摩声双模群组 · 连接中"
            if (snapshot.transportSwitching) return "摩声双模群组 · 传输切换中"
            val transportLabel = if (snapshot.activeTransport == GroupTransport.LOCAL) "本地直连" else "云端兜底"
            val channel = when (snapshot.currentChannel) {
                GroupChannel.Public -> "公共频道"
                GroupChannel.Rider -> "领队频道"
                is GroupChannel.Private -> {
                    val partner = snapshot.privatePartner?.lease?.deviceId?.let { snapshot.names[it] } ?: "队友"
                    "私聊（$partner）"
                }
            }
            return "摩声双模群组 · $transportLabel · $channel"
        }
        if (snapshot.view == null) {
            return if (snapshot.phase == GroupPhase.RECONNECTING) "摩声群组 · 重连恢复中" else "摩声群组 · 准备中"
        }
        val channel = when (snapshot.currentChannel) {
            GroupChannel.Public -> "公共频道"
            GroupChannel.Rider -> "领队频道"
            is GroupChannel.Private -> {
                val partner = snapshot.privatePartner?.lease?.deviceId?.let { snapshot.names[it] } ?: "队友"
                "私聊（$partner）"
            }
        }
        return if (snapshot.phase == GroupPhase.RECONNECTING) "摩声群组 · $channel · 恢复中"
        else "摩声群组 · $channel"
    }

    private fun postTerminalNotificationIfNeeded(snapshot: GroupSessionSnapshot) {
        val reason = snapshot.terminalReason ?: return
        val prefix = when (snapshot.mode) {
            GroupMode.NETWORK -> "摩声网络群组"
            GroupMode.HYBRID -> "摩声双模群组"
            GroupMode.LOCAL -> "摩声群组"
        }
        val title = when (reason) {
            GroupTerminalReason.HOST_ENDED -> "$prefix · 房间已结束"
            GroupTerminalReason.REMOVED -> "$prefix · 你已被移出房间"
            GroupTerminalReason.RECOVERY_EXPIRED -> "$prefix · 重连已超时"
            GroupTerminalReason.REFUSED -> if (snapshot.mode != GroupMode.LOCAL) "$prefix · 加入申请未通过" else return
            else -> return
        }
        val text = snapshot.message.ifBlank {
            when (reason) {
                GroupTerminalReason.HOST_ENDED -> "房主已结束全队对讲"
                GroupTerminalReason.REMOVED -> "你已被移出该房间"
                GroupTerminalReason.RECOVERY_EXPIRED -> "恢复已超过 60 秒，房间已结束"
                GroupTerminalReason.REFUSED -> "加入申请未通过或房间已满"
                else -> ""
            }
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(TERMINAL_CHANNEL_ID, "群组状态提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, TERMINAL_CHANNEL_ID)
            else Notification.Builder(this)
        manager.notify(
            TERMINAL_NOTIFICATION_ID,
            builder.setSmallIcon(R.drawable.ic_rider_24)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent(1))
                .setOngoing(false)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        )
    }

    private fun contentIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, GroupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        destroyed = true
        starting = null
        scope.cancel()
        listeners.clear()
        runtime?.close()
        stopForegroundCompat()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START_GROUP = "com.kuma.motointercom.action.START_GROUP"
        internal const val EXTRA_GROUP_CODE = "com.kuma.motointercom.extra.GROUP_CODE"
        internal const val ACTION_START_NETWORK_GROUP = "com.kuma.motointercom.action.START_NETWORK_GROUP"
        internal const val ACTION_START_HYBRID_GROUP = "com.kuma.motointercom.action.START_HYBRID_GROUP"
        internal const val EXTRA_CLOUD_SERVER_URL = "com.kuma.motointercom.extra.CLOUD_SERVER_URL"
        internal const val EXTRA_CLOUD_INVITE_CODE = "com.kuma.motointercom.extra.CLOUD_INVITE_CODE"
        internal const val EXTRA_CLOUD_REQUIRE_APPROVAL = "com.kuma.motointercom.extra.CLOUD_REQUIRE_APPROVAL"
        internal const val NOTIFICATION_ID = 2056
        internal const val TERMINAL_NOTIFICATION_ID = 2057
        private const val CHANNEL_ID = "motocom_group"
        private const val TERMINAL_CHANNEL_ID = "motocom_group_events"
    }
}
