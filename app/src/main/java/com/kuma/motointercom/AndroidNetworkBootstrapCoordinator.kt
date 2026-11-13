package com.kuma.motointercom

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

internal data class HotspotCredentials(
    val ssid: String,
    val passphrase: String,
    val security: String,
    val hostAddress: String? = null,
    val tcpPort: Int = CrossPlatformCapabilityAdvertisement.DEFAULT_TCP_PORT,
    val expiresAtElapsedRealtimeMs: Long? = null
) {
    init {
        require(ssid.isNotBlank()) { "Hotspot SSID must not be blank" }
        require(security in setOf("OPEN", "WPA2", "WPA3")) {
            "Unsupported hotspot security: $security"
        }
        if (security != "OPEN") {
            require(passphrase.length in 8..63) { "Hotspot passphrase length is invalid" }
        }
        require(tcpPort in 1..65535) { "Hotspot TCP port is invalid" }
    }

    fun redactedSummary(): String =
        "ssid=${ssid.take(32)}, security=$security, tcpPort=$tcpPort, passphrase=<redacted>"

    companion object {
        const val DEFAULT_MANUAL_PASSPHRASE = "123456789"
    }
}

internal sealed interface NetworkBootstrapEvent {
    data class PathSelected(val path: BootstrapPath) : NetworkBootstrapEvent
    data class HotspotReady(val credentials: HotspotCredentials) : NetworkBootstrapEvent
    data class NetworkReady(val network: Network?) : NetworkBootstrapEvent
    data class ManualActionRequired(val reason: String) : NetworkBootstrapEvent
    data class Failed(val reason: String, val cause: Throwable? = null) : NetworkBootstrapEvent
}

/**
 * Selects and owns Android's network-preparation side effects. It deliberately
 * does not write product state; callers forward events to SessionOrchestrator.
 */
@SuppressLint("MissingPermission")
internal class AndroidNetworkBootstrapCoordinator(
    context: Context,
    private val localCapabilities: CrossPlatformCapabilities,
    private val onEvent: (NetworkBootstrapEvent) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val nowElapsedRealtimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) : Closeable {
    private val context = context.applicationContext
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val closed = AtomicBoolean(false)
    private var hotspotController: AndroidLocalOnlyHotspotController? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkMonitorCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var pendingHotspotReady: HotspotCredentials? = null
    private var activePreparationKey: PreparationKey? = null
    private var activePath: BootstrapPath? = null

    private data class PreparationKey(
        val remotePlatform: PeerPlatform,
        val remoteCapabilities: Set<String>,
        val remoteRole: BootstrapNetworkRole,
        val commonLanAvailable: Boolean,
        val hostPreference: BootstrapHostPreference
    )

    fun prepare(
        remoteCapabilities: CrossPlatformCapabilities,
        commonLanAvailable: Boolean,
        hostPreference: BootstrapHostPreference = BootstrapHostPreference.AUTOMATIC
    ): BootstrapPath {
        check(!closed.get()) { "Network bootstrap coordinator is closed" }
        val key = PreparationKey(
            remotePlatform = remoteCapabilities.platform,
            remoteCapabilities = remoteCapabilities.capabilities,
            remoteRole = remoteCapabilities.networkRole,
            commonLanAvailable = commonLanAvailable,
            hostPreference = hostPreference
        )
        activePreparationKey?.let { if (it == key) return activePath ?: BootstrapPath.UNSUPPORTED }
        stopNetworkResources()
        activePreparationKey = key
        val path = CrossPlatformBootstrapPolicy.choosePath(
            local = localCapabilities,
            remote = remoteCapabilities,
            commonLanAvailable = commonLanAvailable,
            hostPreference = hostPreference
        )
        activePath = path
        onEvent(NetworkBootstrapEvent.PathSelected(path))
        when (path) {
            BootstrapPath.COMMON_LAN -> {
                monitorCurrentWifiNetwork()
                onEvent(NetworkBootstrapEvent.NetworkReady(null))
            }
            BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT -> {
                monitorCurrentWifiNetwork()
                startLocalOnlyHotspot()
            }
            BootstrapPath.MANUAL_SYSTEM_WIFI -> {
                monitorCurrentWifiNetwork()
                onEvent(
                    NetworkBootstrapEvent.ManualActionRequired(
                        "请打开系统个人热点/Wi‑Fi 设置，完成网络接入后返回 MotoCom"
                    )
                )
            }
            BootstrapPath.ANDROID_WIFI_DIRECT -> onEvent(NetworkBootstrapEvent.NetworkReady(null))
            BootstrapPath.UNSUPPORTED -> onEvent(
                NetworkBootstrapEvent.Failed("双方没有可用的离线网络路径")
            )
        }
        return path
    }

    fun acceptHotspotReady(credentials: HotspotCredentials): Boolean {
        if (closed.get()) return false
        if (!localCapabilities.canJoinWifi()) {
            onEvent(NetworkBootstrapEvent.Failed("当前 Android 没有可用的 Wi‑Fi 加入能力"))
            return false
        }
        if (credentials.expiresAtElapsedRealtimeMs?.let { nowElapsedRealtimeMs() >= it } == true) {
            onEvent(NetworkBootstrapEvent.Failed("收到的热点凭据已过期"))
            return false
        }
        activePath = BootstrapPath.MANUAL_SYSTEM_WIFI
        closeHotspotResources()
        if (boundNetwork != null) {
            runCatching { connectivityManager?.bindProcessToNetwork(null) }
            boundNetwork = null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            monitorCurrentWifiNetwork()
            onEvent(
                NetworkBootstrapEvent.ManualActionRequired(
                    "当前 Android 版本不支持 App 内加入热点，请使用系统 Wi‑Fi 设置"
                )
            )
            return false
        }
        stopNetworkMonitor()
        requestWifiNetwork(credentials)
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        stopNetworkResources()
        activePreparationKey = null
        activePath = null
    }

    private fun stopNetworkResources() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        stopNetworkMonitor()
        if (boundNetwork != null) {
            runCatching { connectivityManager?.bindProcessToNetwork(null) }
            boundNetwork = null
        }
        closeHotspotResources()
    }

    private fun closeHotspotResources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotController?.close()
        }
        hotspotController = null
        pendingHotspotReady = null
    }

    private fun startLocalOnlyHotspot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onEvent(
                NetworkBootstrapEvent.ManualActionRequired(
                    "当前 Android 版本不支持自动创建临时热点，请使用系统热点设置"
                )
            )
            return
        }
        val manager = wifiManager
        if (manager == null || !manager.isWifiEnabled) {
            onEvent(NetworkBootstrapEvent.ManualActionRequired("请先打开 Wi‑Fi"))
            return
        }
        hotspotController?.close()
        pendingHotspotReady = null
        val controller = AndroidLocalOnlyHotspotController(
            wifiManager = manager,
            handler = handler,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            onReady = { credentials ->
                if (!closed.get()) {
                    val ready = credentials.copy(
                        hostAddress = credentials.hostAddress ?:
                            localPrivateWifiAddress(context, localOnly = true)
                    )
                    pendingHotspotReady = ready.takeIf { it.hostAddress == null }
                    onEvent(NetworkBootstrapEvent.HotspotReady(ready))
                }
            },
            onFailed = { error ->
                if (!closed.get()) {
                    onEvent(
                        NetworkBootstrapEvent.Failed(
                            "系统拒绝创建临时热点：${error.message ?: "unknown"}"
                        )
                    )
                }
            }
        )
        hotspotController = controller
        controller.start()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestWifiNetwork(credentials: HotspotCredentials) {
        val manager = connectivityManager
        if (manager == null) {
            onEvent(NetworkBootstrapEvent.Failed("ConnectivityManager 不可用"))
            return
        }
        networkCallback?.let { callback ->
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(credentials.ssid)
            .apply {
                if (credentials.security == "OPEN") {
                    setIsHiddenSsid(false)
                } else if (credentials.security == "WPA3") {
                    setWpa3Passphrase(credentials.passphrase)
                } else {
                    setWpa2Passphrase(credentials.passphrase)
                }
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (closed.get() || networkCallback !== this) return
                val bound = runCatching { manager.bindProcessToNetwork(network) }.getOrDefault(false)
                if (!bound) {
                    onEvent(NetworkBootstrapEvent.Failed("无法绑定临时 Wi‑Fi 网络"))
                    return
                }
                boundNetwork = network
                onEvent(NetworkBootstrapEvent.NetworkReady(network))
            }

            override fun onUnavailable() {
                if (!closed.get() && networkCallback === this) {
                    onEvent(NetworkBootstrapEvent.Failed("加入热点失败，请确认热点仍在广播且未启用客户端隔离"))
                }
            }

            override fun onLost(network: Network) {
                if (!closed.get() && networkCallback === this) {
                    if (boundNetwork == network) {
                        runCatching { manager.bindProcessToNetwork(null) }
                        boundNetwork = null
                    }
                    onEvent(NetworkBootstrapEvent.Failed("临时 Wi‑Fi 网络已断开"))
                }
            }
        }
        networkCallback = callback
        runCatching { manager.requestNetwork(request, callback) }
            .onFailure { onEvent(NetworkBootstrapEvent.Failed("请求 Wi‑Fi 网络失败", it)) }
    }

    private fun monitorCurrentWifiNetwork() {
        val manager = connectivityManager ?: return
        stopNetworkMonitor()
        val request = NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (closed.get() || networkMonitorCallback !== this) return
                if (activePath == BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT) {
                    if (hotspotController == null) startLocalOnlyHotspot()
                    val capabilities = manager.getNetworkCapabilities(network)
                    if (
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).not()
                    ) {
                        val hostAddress = wifiNetworkPrivateAddress(manager, network)
                        if (hostAddress != null) {
                            pendingHotspotReady?.let { credentials ->
                                pendingHotspotReady = null
                                onEvent(
                                    NetworkBootstrapEvent.HotspotReady(
                                        credentials.copy(hostAddress = hostAddress)
                                    )
                                )
                            }
                        }
                    }
                    return
                }
                onEvent(NetworkBootstrapEvent.NetworkReady(network))
            }

            override fun onLost(network: Network) {
                if (!closed.get() && networkMonitorCallback === this) {
                    if (activePath == BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT && hotspotController != null) {
                        return
                    }
                    onEvent(NetworkBootstrapEvent.Failed("当前 Wi‑Fi 网络已断开"))
                }
            }
        }
        networkMonitorCallback = callback
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure {
                networkMonitorCallback = null
                onEvent(NetworkBootstrapEvent.Failed("监听 Wi‑Fi 网络变化失败", it))
            }
    }

    private fun stopNetworkMonitor() {
        val callback = networkMonitorCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        networkMonitorCallback = null
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@SuppressLint("MissingPermission")
private class AndroidLocalOnlyHotspotController(
    private val wifiManager: WifiManager,
    private val handler: Handler,
    private val nowElapsedRealtimeMs: () -> Long,
    private val onReady: (HotspotCredentials) -> Unit,
    private val onFailed: (Throwable) -> Unit
) : Closeable {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var closed = false

    fun start() {
        if (closed) return
        runCatching {
            wifiManager.startLocalOnlyHotspot(callback, handler)
        }.onFailure(onFailed)
    }

    override fun close() {
        closed = true
        reservation?.close()
        reservation = null
    }

    private val callback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation) {
            if (closed) {
                localOnlyHotspotReservation.close()
                return
            }
            reservation = localOnlyHotspotReservation
            val configuration = localOnlyHotspotReservation.wifiConfiguration
            if (configuration == null) {
                localOnlyHotspotReservation.close()
                reservation = null
                onFailed(IllegalStateException("Local-only Hotspot returned no configuration"))
                return
            }
            val ssid = configuration.SSID.unquote()
            val passphrase = configuration.preSharedKey.unquote()
            val security = if (passphrase.isBlank()) "OPEN" else "WPA2"
            if (ssid.isBlank() || (security != "OPEN" && passphrase.length !in 8..63)) {
                localOnlyHotspotReservation.close()
                reservation = null
                onFailed(IllegalStateException("Local-only Hotspot returned invalid credentials"))
                return
            }
            onReady(
                HotspotCredentials(
                    ssid = ssid,
                    passphrase = passphrase,
                    security = security,
                    expiresAtElapsedRealtimeMs = nowElapsedRealtimeMs() + HOTSPOT_CREDENTIAL_TTL_MS
                )
            )
        }

        override fun onStopped() {
            if (!closed) onFailed(IllegalStateException("Local-only Hotspot stopped"))
        }

        override fun onFailed(reason: Int) {
            if (!closed) onFailed(IllegalStateException("Local-only Hotspot failed: $reason"))
        }
    }

    private companion object {
        const val HOTSPOT_CREDENTIAL_TTL_MS = 10 * 60 * 1000L
    }
}

private fun String?.unquote(): String =
    orEmpty().removeSurrounding("\"").trim()

internal fun localPrivateWifiAddress(context: Context, localOnly: Boolean = false): String? {
    val appContext = context.applicationContext
    val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    val wifiAddresses = connectivity?.allNetworks
        ?.asSequence()
        ?.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                null
            } else {
                network to capabilities
            }
        }
        ?.filter { (_, capabilities) ->
            !localOnly || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        ?.sortedBy { (_, capabilities) ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        ?.mapNotNull { (network, _) -> connectivity.getLinkProperties(network) }
        ?.flatMap { it.linkAddresses.asSequence() }
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.mapNotNull { it.hostAddress }
        ?.firstOrNull { java.net.InetAddress.getByName(it).isSiteLocalAddress }
    if (wifiAddresses != null) return wifiAddresses
    if (localOnly) return null

    @Suppress("DEPRECATION")
    val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    @Suppress("DEPRECATION")
    val ipAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
    if (ipAddress != 0) {
        val address = "${ipAddress and 0xff}.${ipAddress shr 8 and 0xff}." +
            "${ipAddress shr 16 and 0xff}.${ipAddress shr 24 and 0xff}"
        if (java.net.InetAddress.getByName(address).isSiteLocalAddress) return address
    }
    return null
}

private fun wifiNetworkPrivateAddress(
    connectivityManager: ConnectivityManager,
    network: Network
): String? = connectivityManager.getLinkProperties(network)
    ?.linkAddresses
    ?.asSequence()
    ?.map { it.address }
    ?.filterIsInstance<Inet4Address>()
    ?.firstOrNull { it.isSiteLocalAddress }
    ?.hostAddress
