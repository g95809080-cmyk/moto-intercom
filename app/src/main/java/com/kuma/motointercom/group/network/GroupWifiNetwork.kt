@file:Suppress("DEPRECATION")

package com.kuma.motointercom.group.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address

internal fun requireGroupWifi(context: Context) {
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
    check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { "请授予附近 Wi-Fi 权限" }
    check(context.getSystemService(WifiManager::class.java).isWifiEnabled) { "请开启 Wi-Fi" }
    if (Build.VERSION.SDK_INT <= 32) {
        val location = context.getSystemService(LocationManager::class.java)
        check(location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { "请开启位置信息" }
    }
}

/** Single GO owner. UNKNOWN cleanup retains the process lease and blocks successor owners. */
@SuppressLint("MissingPermission")
internal class GroupWifiHost(
    private val context: Context,
    private val onReady: (GroupWifiCredentials, Inet4Address) -> Unit,
    private val onError: (String) -> Unit,
    private val ownership: GroupGoOwnership = GroupGoOwnership.process
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var lease: GroupGoOwnership.Lease? = null
    private var started = false
    private var closed = false
    private var createPending = false
    private var created = false
    private var ready = false
    private var removing = false
    private var verifyAbsence = false
    private var absenceProbe: Any? = null
    private var absenceTimeout: Runnable? = null
    private var closeResult: ((GroupNetworkCloseResult) -> Unit)? = null
    private val timeout = Runnable { fail("创建离线网络超时") }
    private val closeTimeout = Runnable { reportClose(GroupNetworkCloseResult.UNKNOWN) }
    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        try {
            requireGroupWifi(context)
            lease = checkNotNull(ownership.acquire()) { "上一个离线网络尚未确认释放" }
            manager = checkNotNull(context.getSystemService(WifiP2pManager::class.java))
            channel = checkNotNull(manager!!.initialize(context, handler.looper) { fail("Wi-Fi Direct 通道已断开") })
            handler.postDelayed(timeout, 45_000)
            manager!!.requestGroupInfo(channel) { group ->
                if (!closed && owns()) {
                    if (group != null) fail("已有 Wi-Fi Direct 群组，请先结束现有连接")
                    else create()
                }
            }
        } catch (_: Exception) { fail("离线网络不可用，请检查 Wi-Fi 和权限") }
    }
    private fun owns() = lease?.let(ownership::owns) == true
    private fun create() {
        if (closed || !owns()) return
        try {
            ownership.creating(lease!!); createPending = true
            manager!!.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!owns()) return
                    ownership.createFinished(lease!!); createPending = false; created = true
                    if (closed) cleanup() else poll()
                }
                override fun onFailure(reason: Int) {
                    if (!owns()) return
                    ownership.createFinished(lease!!); createPending = false
                    if (closed) cleanup() else fail("无法创建离线网络，请重试")
                }
            })
        } catch (_: Exception) {
            // A synchronous rejection did not enqueue createGroup in the framework.
            ownership.createFinished(lease!!); createPending = false
            fail("创建离线网络失败")
        }
    }
    private fun poll() {
        if (closed || !owns()) return
        try {
            manager!!.requestGroupInfo(channel) { group ->
                if (closed || !owns()) return@requestGroupInfo
                if (group?.isGroupOwner != true || group.networkName.isNullOrEmpty() || group.passphrase.isNullOrEmpty()) {
                    if (ready) fail("离线网络已断开") else handler.postDelayed(::poll, 500)
                    return@requestGroupInfo
                }
                try { manager!!.requestConnectionInfo(channel) { info ->
                    if (closed || !owns()) return@requestConnectionInfo
                    val address = info.groupOwnerAddress as? Inet4Address
                    if (info.groupFormed && info.isGroupOwner && address != null) {
                        if (!ready) {
                            try {
                                val credentials = GroupWifiCredentials(group.networkName, group.passphrase)
                                ready = true; handler.removeCallbacks(timeout)
                                onReady(credentials, address)
                            } catch (_: Exception) { fail("离线网络配置无效"); return@requestConnectionInfo }
                        }
                        handler.postDelayed(::poll, 1_000)
                    } else if (ready) fail("离线网络已断开") else handler.postDelayed(::poll, 500)
                } } catch (_: Exception) { fail("读取离线网络失败") }
            }
        } catch (_: Exception) { fail("读取离线网络失败") }
    }
    private fun fail(message: String) {
        if (!closed) { close { }; onError(message) }
    }
    fun close(onClosed: (GroupNetworkCloseResult) -> Unit) {
        check(Looper.myLooper() == handler.looper)
        if (closed) { onClosed(if (owns()) GroupNetworkCloseResult.UNKNOWN else GroupNetworkCloseResult.RELEASED); return }
        closed = true; closeResult = onClosed
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(closeTimeout, 5_000)
        cleanup()
    }
    /** Retry only while this lease still owns cleanup; never removes another owner's group. */
    fun retryCleanup() { check(Looper.myLooper() == handler.looper); if (closed) cleanup() }
    private fun cleanup() {
        if (!owns()) { finishReleased(); return }
        if (createPending || removing || absenceProbe != null) return
        if (!created) { finishReleased(); return }
        if (verifyAbsence) { confirmAbsent(); return }
        removing = true
        try {
            manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    removing = false
                    if (owns()) { created = false; finishReleased() }
                }
                override fun onFailure(reason: Int) {
                    removing = false
                    verifyAbsence = true
                    confirmAbsent()
                }
            })
        } catch (_: Exception) { removing = false; reportClose(GroupNetworkCloseResult.UNKNOWN) }
    }
    /** A failed remove may mean Android already removed the GO. Failure alone proves nothing. */
    private fun confirmAbsent() {
        if (!closed || !owns() || createPending || removing || absenceProbe != null) return
        val currentChannel = channel ?: return reportClose(GroupNetworkCloseResult.UNKNOWN)
        val token = Any()
        absenceProbe = token
        fun current() = absenceProbe === token && channel === currentChannel && owns() && !createPending && !removing
        fun unknown() {
            if (!current()) return
            clearAbsenceProbe()
            reportClose(GroupNetworkCloseResult.UNKNOWN)
        }
        absenceTimeout = Runnable { unknown() }.also { handler.postDelayed(it, 2_000) }
        try {
            manager!!.requestGroupInfo(currentChannel) { group ->
                if (!current()) return@requestGroupInfo
                if (group != null) { verifyAbsence = false; unknown(); return@requestGroupInfo }
                try {
                    manager!!.requestConnectionInfo(currentChannel) { info ->
                        if (!current()) return@requestConnectionInfo
                        if (info != null && !info.groupFormed) {
                            clearAbsenceProbe()
                            created = false
                            android.util.Log.i("MotoComGroupWifi", "GO absence confirmed after remove failure")
                            finishReleased()
                        } else {
                            if (info?.groupFormed == true) verifyAbsence = false
                            unknown()
                        }
                    }
                } catch (_: Exception) { unknown() }
            }
        } catch (_: Exception) { unknown() }
    }
    private fun clearAbsenceProbe() {
        absenceProbe = null
        absenceTimeout?.let(handler::removeCallbacks)
        absenceTimeout = null
    }
    private fun finishReleased() {
        if (lease != null && owns() && !ownership.releaseConfirmed(lease!!)) return
        clearAbsenceProbe()
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel?.close() }
        channel = null; manager = null
        reportClose(GroupNetworkCloseResult.RELEASED)
    }
    private fun reportClose(result: GroupNetworkCloseResult) {
        val callback = closeResult ?: return
        closeResult = null
        handler.removeCallbacks(closeTimeout)
        callback(result)
    }
}

/** Requests a local-only network; callers bind individual sockets, never the entire process. */
@SuppressLint("MissingPermission")
internal class GroupWifiClient(
    private val context: Context,
    private val credentials: GroupWifiCredentials,
    private val onReady: (Network) -> Unit,
    private val onError: (String) -> Unit
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private var started = false
    private var closed = false
    private var registered = false
    private var selected: Network? = null
    private var legacyTarget = -1
    private var addedNetwork = -1
    private var previousNetwork = -1
    private val deadline = Runnable { fail() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { consider(network) } }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
            handler.post { consider(network) }
        }
        override fun onUnavailable() { handler.post { fail() } }
        override fun onLost(network: Network) { handler.post { if (!closed && selected == network) fail() } }
    }
    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        try {
            requireGroupWifi(context)
            handler.postDelayed(deadline, 45_000)
            val builder = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (Build.VERSION.SDK_INT >= 29) {
                builder.setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
                    .setWpa2Passphrase(credentials.passphrase).build())
                connectivity.requestNetwork(builder.build(), callback)
                registered = true
            } else {
                startLegacy()
                connectivity.registerNetworkCallback(builder.build(), callback)
                registered = true
                pollLegacy()
            }
        } catch (_: Exception) { fail() }
    }
    private fun startLegacy() {
        previousNetwork = wifi.connectionInfo.networkId
        // Never rewrite an existing user's saved configuration or subsequently delete it.
        val quoted = quote(credentials.ssid)
        legacyTarget = wifi.configuredNetworks?.firstOrNull { it.SSID == quoted }?.networkId ?: -1
        if (legacyTarget < 0) {
            val configuration = WifiConfiguration().apply {
                SSID = quoted; preSharedKey = quote(credentials.passphrase)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            addedNetwork = wifi.addNetwork(configuration)
            check(addedNetwork >= 0)
            legacyTarget = addedNetwork
        }
        check(wifi.enableNetwork(legacyTarget, true))
        check(wifi.reconnect())
    }
    private fun pollLegacy() {
        if (closed || Build.VERSION.SDK_INT >= 29) return
        runCatching {
            if (selected != null && wifi.connectionInfo.networkId != legacyTarget) fail()
            else connectivity.allNetworks.forEach(::consider)
        }.onFailure { fail() }
        if (!closed) handler.postDelayed(::pollLegacy, 500)
    }
    private fun consider(network: Network) {
        if (closed || selected != null) return
        try {
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return
            if (Build.VERSION.SDK_INT <= 28) {
                val info = wifi.connectionInfo
                if (info.networkId != legacyTarget || info.ssid != quote(credentials.ssid) ||
                    connectivity.getNetworkInfo(network)?.isConnected != true) return
            }
            val properties = connectivity.getLinkProperties(network) ?: return
            if (properties.linkAddresses.none { it.address is Inet4Address && !it.address.isLoopbackAddress }) return
            selected = network; handler.removeCallbacks(deadline)
            onReady(network)
        } catch (_: Exception) { fail() }
    }
    private fun fail() { if (!closed) { close(); onError("加入离线 Wi-Fi 失败或连接已断开，请检查系统确认和权限") } }
    override fun close() {
        check(Looper.myLooper() == handler.looper)
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false; selected = null
        if (Build.VERSION.SDK_INT <= 28 && legacyTarget >= 0) {
            val stillOurs = runCatching { wifi.connectionInfo.networkId == legacyTarget }.getOrDefault(false)
            if (addedNetwork >= 0) runCatching { wifi.removeNetwork(addedNetwork) }
            if (stillOurs && previousNetwork >= 0 && previousNetwork != legacyTarget) {
                runCatching { wifi.enableNetwork(previousNetwork, true); wifi.reconnect() }
            }
        }
        legacyTarget = -1; addedNetwork = -1
    }
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
