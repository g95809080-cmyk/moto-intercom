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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.webrtc.PeerConnection
import java.io.IOException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台免死对讲服务。
 *
 * Activity 只做遥控器；蓝牙 SCO、Wi-Fi Direct、WebRTC 信令全部由前台服务托管，
 * 锁屏和退到后台时不跟着 Activity 一起释放。
 */
class IntercomService : Service() {

    internal interface Listener {
        fun onStatusChanged(status: String, running: Boolean)
        fun onAudioSourceChanged(status: String, bluetooth: Boolean) = Unit
        fun onLanDevicesChanged(devices: List<LanRiderDevice>) = Unit
        fun onAudioLevelChanged(level: Float) = Unit
        fun onLog(message: String)
        fun onToast(message: String) = Unit
        fun onRemoteRiderIdentified(name: String) = Unit
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): IntercomService = this@IntercomService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = SessionGeneration()

    private var listener: Listener? = null
    private var audioRouteController: AudioRouteController? = null
    private var wifiTunnel: WifiDirectTunnel? = null
    private var intercomManager: IntercomManager? = null
    private var lanDiscovery: LanDiscoveryCoordinator? = null

    private var bluetoothReady = false
    private var physicalLinkReady = false
    private var mediaConnected = false
    private var running = false
    private var lastStatus = READY_STATUS
    private var audioSourceStatus = AUDIO_STANDBY_STATUS
    private var audioSourceBluetooth = false
    private var requestedRiderName = ""
    private var localRiderName = ""
    private var remoteRiderName: String? = null
    private var activeSession: SessionGeneration.Token? = null
    private val tunnelChosen = AtomicLong(NO_SESSION_TOKEN)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_INTERCOM -> {
                requestedRiderName = intent.getStringExtra(EXTRA_RIDER_NAME).orEmpty().trim()
                if (!hasRequiredRuntimePermissions()) {
                    publishStatus("缺少必要权限，无法启动摩声")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startIntercom()
                return START_NOT_STICKY
            }
            ACTION_STOP_INTERCOM -> {
                stopIntercom()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopIntercom()
        super.onDestroy()
    }

    internal fun setListener(listener: Listener?) {
        this.listener = listener
        listener?.onStatusChanged(lastStatus, running)
        listener?.onAudioSourceChanged(audioSourceStatus, audioSourceBluetooth)
        listener?.onLanDevicesChanged(lanDiscovery?.devicesSnapshot().orEmpty())
        remoteRiderName?.let { listener?.onRemoteRiderIdentified(it) }
    }

    fun requestStart(riderName: String = "") {
        mainHandler.post {
            requestedRiderName = riderName.trim()
            if (hasRequiredRuntimePermissions()) startIntercom() else publishStatus("缺少必要权限，无法启动摩声")
        }
    }

    fun requestStop() {
        mainHandler.post {
            stopIntercom()
            stopSelf()
        }
    }

    internal fun connectToLanDevice(device: LanRiderDevice) {
        mainHandler.post {
            if (activeSession == null || tunnelChosen.get() != NO_SESSION_TOKEN) return@post
            publishStatus(PEER_FOUND_STATUS)
            lanDiscovery?.connect(device)
        }
    }

    private fun startIntercom() {
        if (running) {
            publishStatus(lastStatus)
            return
        }

        val token = sessions.start()
        activeSession = token
        running = true
        bluetoothReady = false
        physicalLinkReady = false
        remoteRiderName = null
        localRiderName = ""
        tunnelChosen.set(NO_SESSION_TOKEN)
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(SEARCHING_STATUS)

        audioRouteController = AudioRouteController(
            context = this,
            onScoConnected = { deviceName ->
                postForSession(token) {
                    bluetoothReady = true
                    publishAudioSource("当前音频源：蓝牙耳机 ($deviceName)", bluetooth = true)
                    publishToast("头盔蓝牙已连线，对讲音频已就绪")
                    updateStageStatus()
                }
            },
            onScoDisconnected = {
                postForSession(token) {
                    bluetoothReady = false
                    publishToast(BLUETOOTH_RETRY_STATUS)
                    publishLog(BLUETOOTH_RETRY_STATUS)
                    updateStageStatus()
                }
            },
            onSpeakerFallback = { noBluetooth ->
                postForSession(token) {
                    bluetoothReady = false
                    publishAudioSource(AUDIO_SPEAKER_STATUS, bluetooth = false)
                    if (noBluetooth) publishToast("未检测到头盔蓝牙，已切换至手机外放")
                    updateStageStatus()
                }
            },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.switchToBluetoothSco() }

        publishStatus(SEARCHING_STATUS)
        wifiTunnel = WifiDirectTunnel(
            context = this,
            onTunnelReady = { targetIp, isServer, socket ->
                onTunnelReady(token, targetIp, isServer, socket)
            },
            localNickname = requestedRiderName.ifBlank { "骑士" },
            onPeersChanged = {
                postForSession(token) {
                    publishLog("发现附近设备：${it.size}")
                    if (it.isNotEmpty() && !physicalLinkReady) publishStatus(PEER_FOUND_STATUS)
                }
            },
            onDiscoveryStatus = {
                postForSession(token) {
                    publishStatus(it)
                    publishLog(it)
                }
            },
            onDisconnected = {
                postForSession(token) { publishStatus(SIGNAL_LOST_STATUS) }
            },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.start() }
        lanDiscovery = LanDiscoveryCoordinator(
            context = this,
            token = token,
            isSessionCurrent = ::isSessionCurrent,
            nodeId = UUID.randomUUID().toString(),
            riderName = requestedRiderName.ifBlank { "骑士" },
            onDevicesChanged = { devices ->
                postForSession(token) {
                    if (!physicalLinkReady && devices.isNotEmpty()) publishStatus(PEER_FOUND_STATUS)
                    listener?.onLanDevicesChanged(devices)
                }
            },
            onTunnelReady = { ip, server, socket ->
                acceptTunnel(token, ip, server, socket, closeWifiDirect = true)
            },
            onLog = { message -> postForSession(token) { publishLog(message) } },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also { it.start() }
    }

    private fun onTunnelReady(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket
    ) {
        acceptTunnel(token, targetIp, isServer, signalingSocket, closeWifiDirect = false)
    }

    private fun acceptTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ): Boolean {
        if (!sessions.claimIfCurrent(token) {
                tunnelChosen.compareAndSet(NO_SESSION_TOKEN, token.value)
            }
        ) {
            return closeStaleSocket(signalingSocket)
        }
        mainHandler.post {
            if (!isSessionCurrent(token) || tunnelChosen.get() != token.value) {
                tunnelChosen.compareAndSet(token.value, NO_SESSION_TOKEN)
                closeStaleSocket(signalingSocket)
                return@post
            }
            activateTunnel(token, targetIp, isServer, signalingSocket, closeWifiDirect)
        }
        return true
    }

    private fun activateTunnel(
        token: SessionGeneration.Token,
        targetIp: String,
        isServer: Boolean,
        signalingSocket: Socket,
        closeWifiDirect: Boolean
    ) {
        lanDiscovery?.close()
        lanDiscovery = null
        if (closeWifiDirect) {
            try {
                wifiTunnel?.close()
            } catch (t: Throwable) {
                handleError(t)
            }
            wifiTunnel = null
        }

        physicalLinkReady = true
        mediaConnected = false
        publishStatus(SIGNALING_CONNECTED_STATUS)
        localRiderName = requestedRiderName.ifBlank { if (isServer) "骑士A" else "骑士B" }
        publishLog("本机骑士昵称：$localRiderName")

        intercomManager = IntercomManager(
            context = this,
            signalingSocket = signalingSocket,
            isServer = isServer,
            localRiderName = localRiderName,
            onIntercomDisconnected = { onIntercomDisconnected(token, it) },
            onConnectionStateChanged = { onConnectionStateChanged(token, it) },
            onRemoteRiderIdentified = { onRemoteRiderIdentified(token, it) },
            onAudioLevelChanged = { onAudioLevelChanged(token, it) },
            onError = { error -> postForSession(token) { handleError(error) } }
        ).also {
            publishStatus(MEDIA_INITIALIZING_STATUS)
            it.start()
        }

        updateStageStatus()
    }

    private fun onConnectionStateChanged(
        token: SessionGeneration.Token,
        state: PeerConnection.PeerConnectionState
    ) {
        postForSession(token) {
            publishLog("WebRTC 状态：$state")
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mediaConnected = true
                    publishStatus(VOICE_CONNECTED_STATUS)
                }
                PeerConnection.PeerConnectionState.DISCONNECTED,
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.CLOSED -> {
                    mediaConnected = false
                    publishStatus(SIGNAL_LOST_STATUS)
                }
                else -> updateStageStatus()
            }
        }
    }

    private fun onIntercomDisconnected(token: SessionGeneration.Token, error: IOException) {
        postForSession(token) {
            publishLog("信令通道断开：${error.message}")
            publishStatus(SIGNAL_LOST_STATUS)
            stopIntercom()
            stopSelf()
        }
    }

    private fun onRemoteRiderIdentified(token: SessionGeneration.Token, name: String) {
        postForSession(token) {
            remoteRiderName = name
            publishLog("已识别远端骑士：$name")
            listener?.onRemoteRiderIdentified(name)
            updateStageStatus()
        }
    }

    private fun onAudioLevelChanged(token: SessionGeneration.Token, level: Float) {
        postForSession(token) { listener?.onAudioLevelChanged(level) }
    }

    private fun stopIntercom() {
        sessions.invalidate()
        activeSession = null
        running = false
        tunnelChosen.set(NO_SESSION_TOKEN)
        lanDiscovery?.close()
        lanDiscovery = null

        try {
            intercomManager?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        try {
            wifiTunnel?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        try {
            audioRouteController?.close()
        } catch (t: Throwable) {
            handleError(t)
        }
        intercomManager = null
        wifiTunnel = null
        audioRouteController = null
        bluetoothReady = false
        physicalLinkReady = false
        mediaConnected = false
        localRiderName = ""
        remoteRiderName = null
        publishAudioSource(AUDIO_STANDBY_STATUS, bluetooth = false)
        publishStatus(ENDED_STATUS)
        stopForegroundCompat()
    }

    private fun isSessionCurrent(token: SessionGeneration.Token): Boolean =
        running && sessions.isCurrent(token) && activeSession == token

    private fun dispatchOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun postForSession(token: SessionGeneration.Token, action: () -> Unit) {
        dispatchOnMain {
            if (isSessionCurrent(token)) action()
        }
    }

    private fun closeStaleSocket(socket: Socket): Boolean {
        return try {
            socket.close()
            false
        } catch (_: IOException) {
            false
        }
    }

    private fun updateStageStatus() {
        when {
            mediaConnected -> publishStatus(VOICE_CONNECTED_STATUS)
            physicalLinkReady -> publishStatus(MEDIA_INITIALIZING_STATUS)
            bluetoothReady -> publishStatus(SEARCHING_STATUS)
            running -> publishStatus(SEARCHING_STATUS)
        }
    }

    private fun publishStatus(status: String) {
        dispatchOnMain {
            lastStatus = status
            listener?.onStatusChanged(status, running)
            updateNotification()
        }
    }

    private fun publishAudioSource(status: String, bluetooth: Boolean) {
        dispatchOnMain {
            audioSourceStatus = status
            audioSourceBluetooth = bluetooth
            listener?.onAudioSourceChanged(status, bluetooth)
        }
    }

    private fun publishLog(message: String) {
        dispatchOnMain { listener?.onLog(message) }
    }

    private fun publishToast(message: String) {
        dispatchOnMain { listener?.onToast(message) }
    }

    private fun handleError(t: Throwable) {
        val message = t.message ?: t.javaClass.simpleName
        dispatchOnMain { listener?.onError(message) }
    }

    private fun hasRequiredRuntimePermissions(): Boolean {
        return (
            WifiDirectTunnel.requiredPermissions().asList() +
                RiderAudioEngine.requiredPermissions().asList() +
                AudioRouteController.requiredPermissions().asList()
            ).distinct().all {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("摩声")
            .setContentText("正在后台运行中")
            .setStyle(Notification.BigTextStyle().bigText(lastStatus))
            .setOngoing(running)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "对讲状态提示",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val NO_SESSION_TOKEN = 0L
        const val ACTION_START_INTERCOM = "com.kuma.motointercom.action.START_INTERCOM"
        const val ACTION_STOP_INTERCOM = "com.kuma.motointercom.action.STOP_INTERCOM"
        const val EXTRA_RIDER_NAME = "com.kuma.motointercom.extra.RIDER_NAME"
        private const val CHANNEL_ID = "intercom_status"
        private const val NOTIFICATION_ID = 2601
        private const val AUDIO_STANDBY_STATUS = "当前音频源：待机"
        private const val AUDIO_SPEAKER_STATUS = "当前音频源：手机外放（无蓝牙）"
        private const val READY_STATUS = "请点击下方启动对讲"
        private const val SEARCHING_STATUS = "无线配对中，请把两台手机靠近.."
        private const val PEER_FOUND_STATUS = "已发现车友"
        private const val SIGNALING_CONNECTED_STATUS = "信令已连接"
        private const val MEDIA_INITIALIZING_STATUS = "媒体初始化中"
        private const val VOICE_CONNECTED_STATUS = "语音通道已连接"
        private const val SIGNAL_LOST_STATUS = "队友信号丢失，等待重新连接..."
        private const val ENDED_STATUS = "对讲已结束"
        private const val BLUETOOTH_RETRY_STATUS = "头盔蓝牙已断开，正在尝试重连..."

        fun startIntent(context: Context, riderName: String = ""): Intent =
            Intent(context, IntercomService::class.java)
                .setAction(ACTION_START_INTERCOM)
                .putExtra(EXTRA_RIDER_NAME, riderName)

        fun stopIntent(context: Context): Intent =
            Intent(context, IntercomService::class.java).setAction(ACTION_STOP_INTERCOM)
    }
}
