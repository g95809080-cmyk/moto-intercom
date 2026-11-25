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
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

internal fun supportsGroupWifiP2pPreset(): Boolean =
    Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

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

/** Route A (Android 10+) preset credentials for silent Wi-Fi Direct group creation and join. */
internal data class GroupWifiP2pPreset(
    val networkName: String,
    val passphrase: String,
    val frequencyMhz: Int? = null,
    val prefer2GhzBand: Boolean = true
) {
    init {
        require(networkName.matches(Regex("^DIRECT-[0-9A-Za-z]{2}-.{1,22}$"))) { "Invalid P2P networkName" }
        require(networkName.toByteArray(Charsets.UTF_8).size <= 32) { "P2P networkName too long" }
        require(passphrase.length in 8..63 && passphrase.all { it.code in 32..126 }) { "Invalid P2P passphrase" }
        if (frequencyMhz != null) require(frequencyMhz in 2400..6000) { "Invalid P2P frequency" }
    }
    override fun toString() = "GroupWifiP2pPreset(REDACTED)"
}

/** Direct socket routing handle for Route A p2p0 connections without mutating process default network. */
internal class GroupLocalSocketLink(
    val hostAddress: String,
    val bindSocket: (Socket) -> Unit
)

internal fun resolveLocalP2pIpv4(interfaceName: String?): Inet4Address? {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
    val prioritized = buildList {
        if (!interfaceName.isNullOrBlank()) {
            interfaces.firstOrNull { it.name.equals(interfaceName, ignoreCase = true) }?.let(::add)
        }
        interfaces.filter { it.name.startsWith("p2p", ignoreCase = true) && !it.name.equals(interfaceName, ignoreCase = true) }
            .forEach(::add)
    }
    var fallback: Inet4Address? = null
    for (iface in prioritized) {
        val isUp = runCatching { iface.isUp }.getOrDefault(false)
        if (!isUp) continue
        val addresses = runCatching { iface.inetAddresses.toList() }.getOrDefault(emptyList())
        for (addr in addresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                if (addr.hostAddress?.startsWith("192.168.49.") == true) return addr
                if (fallback == null) fallback = addr
            }
        }
    }
    return fallback
}

internal fun bindSocketToP2pInterface(
    context: Context,
    interfaceName: String?,
    localIpv4: Inet4Address
): (Socket) -> Unit {
    val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    return { socket ->
        val matchingNetwork = runCatching {
            connectivity?.allNetworks?.firstOrNull { net ->
                val props = connectivity.getLinkProperties(net) ?: return@firstOrNull false
                val byName = !interfaceName.isNullOrBlank() && props.interfaceName.equals(interfaceName, ignoreCase = true)
                val byAddr = props.linkAddresses.any { it.address == localIpv4 }
                byName || byAddr
            }
        }.getOrNull()
        if (matchingNetwork != null) {
            runCatching { matchingNetwork.bindSocket(socket) }
        }
        socket.bind(InetSocketAddress(localIpv4, 0))
    }
}

/** Single GO owner. UNKNOWN cleanup retains the process lease and blocks successor owners. */
@SuppressLint("MissingPermission")
internal class GroupWifiHost(
    private val context: Context,
    private val onReady: (GroupWifiCredentials, Inet4Address) -> Unit,
    private val onError: (String) -> Unit,
    private val ownership: GroupGoOwnership = GroupGoOwnership.process,
    private val preset: GroupWifiP2pPreset? = null,
    private val onGroupFrequency: (Int?) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var lease: GroupGoOwnership.Lease? = null
    private var started = false
    private var closed = false
    private var createPending = false
    private var createAttemptToken: Any? = null
    private var channelToken: Any? = null
    private var recoveringChannel = false
    private var ownedNetworkName = preset?.networkName?.takeIf { Build.VERSION.SDK_INT >= 29 }
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
            initializeChannel()
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
    private fun initializeChannel() {
        val token = Any()
        channelToken = token
        channel = checkNotNull(manager!!.initialize(context, handler.looper) {
            if (channelToken === token) recoverDisconnectedChannel()
        })
    }
    private fun recoverDisconnectedChannel() {
        if (!owns()) return
        val notify = !closed
        closed = true
        handler.removeCallbacksAndMessages(null)
        if (closeResult != null) handler.postDelayed(closeTimeout, 5_000)
        channelToken = null
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel?.close() }
        channel = null
        recoveringChannel = true
        createAttemptToken = null
        // The service may have created the group before its reply was lost.
        created = created || createPending
        if (createPending) ownership.createFinished(lease!!)
        createPending = false
        removing = false
        verifyAbsence = created
        clearAbsenceProbe()
        cleanup()
        if (notify) onError("Wi-Fi Direct 通道已断开")
    }
    private fun create() {
        if (closed || !owns()) return
        val attemptToken = Any()
        try {
            ownership.creating(lease!!); createPending = true; createAttemptToken = attemptToken
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!owns() || createAttemptToken !== attemptToken) return
                    createAttemptToken = null
                    ownership.createFinished(lease!!); createPending = false; created = true
                    if (closed) cleanup() else poll()
                }
                override fun onFailure(reason: Int) {
                    if (!owns() || createAttemptToken !== attemptToken) return
                    createAttemptToken = null
                    ownership.createFinished(lease!!); createPending = false
                    if (closed) cleanup() else fail("无法创建离线网络，请重试")
                }
            }
            val activePreset = preset
            if (activePreset != null && Build.VERSION.SDK_INT >= 29) {
                val builder = WifiP2pConfig.Builder()
                    .setNetworkName(activePreset.networkName)
                    .setPassphrase(activePreset.passphrase)
                    .enablePersistentMode(false)
                if (activePreset.frequencyMhz != null) {
                    builder.setGroupOperatingFrequency(activePreset.frequencyMhz)
                } else if (activePreset.prefer2GhzBand) {
                    builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                }
                manager!!.createGroup(channel!!, builder.build(), listener)
            } else {
                manager!!.createGroup(channel, listener)
            }
        } catch (_: Exception) {
            // A synchronous rejection did not enqueue createGroup in the framework.
            createAttemptToken = null
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
                                ownedNetworkName = group.networkName
                                val frequency = if (Build.VERSION.SDK_INT >= 29) {
                                    group.frequency.takeIf { it in 2400..6000 }
                                } else null
                                ready = true; handler.removeCallbacks(timeout)
                                onGroupFrequency(frequency)
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
        if (channel == null) {
            try { initializeChannel() } catch (_: Exception) {
                reportClose(GroupNetworkCloseResult.UNKNOWN)
                return
            }
        }
        if (verifyAbsence) { confirmAbsent(); return }
        removing = true
        val token = channelToken
        try {
            manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!owns() || channelToken !== token) return
                    removing = false
                    created = false
                    finishReleased()
                }
                override fun onFailure(reason: Int) {
                    if (!owns() || channelToken !== token) return
                    removing = false
                    verifyAbsence = true
                    confirmAbsent(allowRecoveryRemoval = false)
                }
            })
        } catch (_: Exception) {
            removing = false
            if (recoveringChannel) verifyAbsence = true
            reportClose(GroupNetworkCloseResult.UNKNOWN)
        }
    }
    /** A failed remove may mean Android already removed the GO. Failure alone proves nothing. */
    private fun confirmAbsent(allowRecoveryRemoval: Boolean = true) {
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
                if (group != null) {
                    if (recoveringChannel && (!group.isGroupOwner ||
                            (ownedNetworkName != null && !group.networkName.isNullOrEmpty() && group.networkName != ownedNetworkName))) {
                        // The replacement channel sees another group. We have no claim on it.
                        created = false
                        finishReleased()
                        return@requestGroupInfo
                    }
                    if (recoveringChannel && (ownedNetworkName == null || group.networkName.isNullOrEmpty())) {
                        unknown()
                        return@requestGroupInfo
                    }
                    verifyAbsence = recoveringChannel && !allowRecoveryRemoval
                    if (recoveringChannel && allowRecoveryRemoval) {
                        clearAbsenceProbe()
                        cleanup()
                    } else unknown()
                    return@requestGroupInfo
                }
                try {
                    manager!!.requestConnectionInfo(currentChannel) { info ->
                        if (!current()) return@requestConnectionInfo
                        if (info != null && !info.groupFormed) {
                            clearAbsenceProbe()
                            created = false
                            android.util.Log.i("MotoComGroupWifi", "GO absence confirmed after remove failure")
                            finishReleased()
                        } else {
                            if (!recoveringChannel && info?.groupFormed == true) verifyAbsence = false
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
        channelToken = null
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

/**
 * Route A (Android 10+ Fast Connection API) silent Wi-Fi Direct client over p2p0.
 * Connects directly to a preset DIRECT-xx-... group without system picker popups or disturbing cellular/main Wi-Fi.
 */
@SuppressLint("MissingPermission")
internal class GroupWifiP2pClient(
    private val context: Context,
    private val preset: GroupWifiP2pPreset,
    private val expectedHostAddress: String? = null,
    private val onReady: (GroupLocalSocketLink) -> Unit,
    private val onError: (String) -> Unit,
    private val ownership: GroupGoOwnership = GroupGoOwnership.process,
    private val p2pAddressResolver: (String?) -> Inet4Address? = ::resolveLocalP2pIpv4,
    private val socketBinderFactory: (Context, String?, Inet4Address) -> ((Socket) -> Unit) = ::bindSocketToP2pInterface
) {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var lease: GroupGoOwnership.Lease? = null
    private var started = false
    private var closed = false
    private var connectPending = false
    private var connectAttemptToken: Any? = null
    private var cancelFallback: Runnable? = null
    private var joined = false
    private var ready = false
    private var removing = false
    private var removeAttempted = false
    private var verifyAbsence = false
    private var absenceProbe: Any? = null
    private var absenceTimeout: Runnable? = null
    private var closeResult: ((GroupNetworkCloseResult) -> Unit)? = null
    private val timeout = Runnable { fail("直连 Wi-Fi Direct 超时") }
    private val closeTimeout = Runnable { reportClose(GroupNetworkCloseResult.UNKNOWN) }

    fun start() {
        check(Looper.myLooper() == handler.looper && !started && !closed)
        started = true
        if (Build.VERSION.SDK_INT < 29) {
            fail("当前系统版本不支持预设直连，请使用标准配对")
            return
        }
        try {
            requireGroupWifi(context)
            lease = checkNotNull(ownership.acquire()) { "上一个 Wi-Fi Direct 连接尚未释放" }
            manager = checkNotNull(context.getSystemService(WifiP2pManager::class.java))
            channel = checkNotNull(manager!!.initialize(context, handler.looper) { fail("Wi-Fi Direct 通道已断开") })
            handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
            manager!!.requestGroupInfo(channel) { group ->
                if (closed || !owns()) return@requestGroupInfo
                when {
                    group != null && !group.isGroupOwner && group.networkName == preset.networkName -> {
                        joined = true
                        poll()
                    }
                    group != null -> fail("已有 Wi-Fi Direct 群组，请先结束现有连接")
                    else -> connect()
                }
            }
        } catch (_: Exception) {
            fail("离线直连不可用，请检查 Wi-Fi 和权限")
        }
    }

    private fun owns() = lease?.let(ownership::owns) == true

    private fun connect() {
        if (closed || !owns() || Build.VERSION.SDK_INT < 29) return
        val attemptToken = Any()
        try {
            ownership.creating(lease!!)
            connectPending = true
            connectAttemptToken = attemptToken
            val builder = WifiP2pConfig.Builder()
                .setNetworkName(preset.networkName)
                .setPassphrase(preset.passphrase)
                .enablePersistentMode(false)
            if (preset.frequencyMhz != null) {
                builder.setGroupOperatingFrequency(preset.frequencyMhz)
            } else if (preset.prefer2GhzBand) {
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
            }
            manager!!.connect(channel, builder.build(), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!owns()) return
                    if (connectAttemptToken !== attemptToken) {
                        if (closed) {
                            clearAbsenceProbe()
                            joined = true
                            verifyAbsence = false
                            cleanup()
                        }
                        return
                    }
                    clearCancelFallback()
                    connectAttemptToken = null
                    ownership.createFinished(lease!!)
                    connectPending = false
                    joined = true
                    if (closed) cleanup() else poll()
                }

                override fun onFailure(reason: Int) {
                    if (!owns() || connectAttemptToken !== attemptToken) return
                    clearCancelFallback()
                    connectAttemptToken = null
                    ownership.createFinished(lease!!)
                    connectPending = false
                    if (closed) cleanup() else fail("静默直连 Wi-Fi Direct 失败")
                }
            })
        } catch (_: Exception) {
            connectAttemptToken = null
            ownership.createFinished(lease!!)
            connectPending = false
            fail("静默直连 Wi-Fi Direct 失败")
        }
    }

    private fun poll() {
        if (closed || !owns()) return
        try {
            manager!!.requestConnectionInfo(channel) { info ->
                if (closed || !owns()) return@requestConnectionInfo
                val goAddress = info?.groupOwnerAddress as? Inet4Address
                if (info?.groupFormed != true || info.isGroupOwner || goAddress == null) {
                    if (ready) fail("Wi-Fi Direct 直连已断开") else handler.postDelayed(::poll, POLL_PENDING_MS)
                    return@requestConnectionInfo
                }
                try {
                    manager!!.requestGroupInfo(channel) { group ->
                        if (closed || !owns()) return@requestGroupInfo
                        if (group != null && group.networkName != preset.networkName) {
                            fail("Wi-Fi Direct 群组不匹配")
                            return@requestGroupInfo
                        }
                        val interfaceName = group?.`interface`
                        val localIpv4 = p2pAddressResolver(interfaceName)
                        if (localIpv4 == null) {
                            if (ready) fail("Wi-Fi Direct 本地地址失效") else handler.postDelayed(::poll, POLL_PENDING_MS)
                            return@requestGroupInfo
                        }
                        if (!ready) {
                            val hostIp = goAddress.hostAddress ?: expectedHostAddress
                            if (hostIp.isNullOrBlank()) {
                                fail("缺少 Wi-Fi Direct 主机地址")
                                return@requestGroupInfo
                            }
                            ready = true
                            handler.removeCallbacks(timeout)
                            val binder = socketBinderFactory(context, interfaceName, localIpv4)
                            onReady(GroupLocalSocketLink(hostIp, binder))
                        }
                        handler.postDelayed(::poll, POLL_READY_MS)
                    }
                } catch (_: Exception) {
                    fail("读取 Wi-Fi Direct 状态失败")
                }
            }
        } catch (_: Exception) {
            fail("读取 Wi-Fi Direct 连接信息失败")
        }
    }

    private fun fail(message: String) {
        if (!closed) {
            close { }
            onError(message)
        }
    }

    fun close(onClosed: (GroupNetworkCloseResult) -> Unit) {
        check(Looper.myLooper() == handler.looper)
        if (closed) {
            onClosed(if (owns()) GroupNetworkCloseResult.UNKNOWN else GroupNetworkCloseResult.RELEASED)
            return
        }
        closed = true
        closeResult = onClosed
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(closeTimeout, 5_000)
        if (connectPending && manager != null && channel != null) {
            val pendingToken = connectAttemptToken
            val cancelInvoked = runCatching {
                manager!!.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (!closed || !owns() || !connectPending || connectAttemptToken !== pendingToken) return
                        finishCancelledConnect()
                    }

                    override fun onFailure(reason: Int) {
                        if (!closed || !owns() || !connectPending || connectAttemptToken !== pendingToken) return
                        scheduleCancelFallback(pendingToken)
                    }
                })
            }.isSuccess
            if ((!cancelInvoked || connectPending) && closed && owns() && connectPending && connectAttemptToken === pendingToken) {
                scheduleCancelFallback(pendingToken)
            }
        }
        cleanup()
    }

    private fun finishCancelledConnect() {
        clearCancelFallback()
        connectAttemptToken = null
        lease?.let(ownership::createFinished)
        connectPending = false
        joined = true
        verifyAbsence = true
        confirmAbsent(removeOnPresence = true)
    }

    private fun scheduleCancelFallback(pendingToken: Any?) {
        if (cancelFallback != null) return
        val fallback = Runnable {
            cancelFallback = null
            if (!closed || !owns() || !connectPending || connectAttemptToken !== pendingToken) return@Runnable
            finishCancelledConnect()
        }
        cancelFallback = fallback
        handler.postDelayed(fallback, CANCEL_CONNECT_FALLBACK_MS)
    }

    private fun clearCancelFallback() {
        cancelFallback?.let(handler::removeCallbacks)
        cancelFallback = null
    }

    fun retryCleanup() {
        check(Looper.myLooper() == handler.looper)
        if (closed) cleanup()
    }

    private fun cleanup() {
        if (!owns()) {
            finishReleased()
            return
        }
        if (connectPending || removing || absenceProbe != null) return
        if (!joined) {
            finishReleased()
            return
        }
        if (verifyAbsence) {
            confirmAbsent(removeOnPresence = !removeAttempted)
            return
        }
        removing = true
        removeAttempted = true
        try {
            manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    removing = false
                    if (owns()) {
                        joined = false
                        finishReleased()
                    }
                }

                override fun onFailure(reason: Int) {
                    removing = false
                    verifyAbsence = true
                    confirmAbsent(removeOnPresence = false)
                }
            })
        } catch (_: Exception) {
            removing = false
            reportClose(GroupNetworkCloseResult.UNKNOWN)
        }
    }

    private fun confirmAbsent(removeOnPresence: Boolean) {
        if (!closed || !owns() || connectPending || removing || absenceProbe != null) return
        val currentChannel = channel ?: return reportClose(GroupNetworkCloseResult.UNKNOWN)
        val token = Any()
        absenceProbe = token
        fun current() = absenceProbe === token && channel === currentChannel && owns() && !connectPending && !removing
        fun unknown() {
            if (!current()) return
            clearAbsenceProbe()
            reportClose(GroupNetworkCloseResult.UNKNOWN)
        }
        absenceTimeout = Runnable { unknown() }.also { handler.postDelayed(it, 2_000) }
        try {
            manager!!.requestGroupInfo(currentChannel) { group ->
                if (!current()) return@requestGroupInfo
                if (group != null) {
                    verifyAbsence = false
                    if (removeOnPresence) {
                        clearAbsenceProbe()
                        cleanup()
                    } else {
                        unknown()
                    }
                    return@requestGroupInfo
                }
                try {
                    manager!!.requestConnectionInfo(currentChannel) { info ->
                        if (!current()) return@requestConnectionInfo
                        if (info != null && !info.groupFormed) {
                            clearAbsenceProbe()
                            joined = false
                            finishReleased()
                        } else {
                            if (info?.groupFormed == true) {
                                verifyAbsence = false
                                if (removeOnPresence) {
                                    clearAbsenceProbe()
                                    cleanup()
                                    return@requestConnectionInfo
                                }
                            }
                            unknown()
                        }
                    }
                } catch (_: Exception) {
                    unknown()
                }
            }
        } catch (_: Exception) {
            unknown()
        }
    }

    private fun clearAbsenceProbe() {
        absenceProbe = null
        absenceTimeout?.let(handler::removeCallbacks)
        absenceTimeout = null
    }

    private fun finishReleased() {
        if (lease != null && owns() && !ownership.releaseConfirmed(lease!!)) return
        clearCancelFallback()
        connectAttemptToken = null
        clearAbsenceProbe()
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel?.close() }
        channel = null
        manager = null
        reportClose(GroupNetworkCloseResult.RELEASED)
    }

    private fun reportClose(result: GroupNetworkCloseResult) {
        val callback = closeResult ?: return
        closeResult = null
        handler.removeCallbacks(closeTimeout)
        callback(result)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val POLL_PENDING_MS = 300L
        private const val POLL_READY_MS = 1_000L
        private const val CANCEL_CONNECT_FALLBACK_MS = 1_500L
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
