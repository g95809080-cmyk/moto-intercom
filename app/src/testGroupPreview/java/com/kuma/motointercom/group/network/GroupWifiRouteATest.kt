package com.kuma.motointercom.group.network

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RouteAP2pShadow::class])
class GroupWifiRouteATest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var ownership: GroupGoOwnership

    @Before
    fun setup() {
        ownership = GroupGoOwnership()
        RouteAP2pShadow.reset()
        shadowOf(app).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        @Suppress("DEPRECATION")
        app.getSystemService(WifiManager::class.java).isWifiEnabled = true
    }

    @Test
    fun presetHostUses2GhzConfigBuilderAndRedactsToString() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", prefer2GhzBand = true)
        assertFalse(preset.toString().contains("DIRECT-mc-abcd1234"))
        assertFalse(preset.toString().contains("SecretPass123456"))

        val host = GroupWifiHost(
            context = app,
            onReady = { _, _ -> },
            onError = { fail("Unexpected error: $it") },
            ownership = ownership,
            preset = preset
        )
        host.start()
        shadowOf(Looper.getMainLooper()).idle()

        val captured = checkNotNull(RouteAP2pShadow.lastCreateConfig)
        assertEquals("DIRECT-mc-abcd1234", captured.networkName)
        assertEquals("SecretPass123456", captured.passphrase)
        assertEquals(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, captured.groupOwnerBand)

        var closed: GroupNetworkCloseResult? = null
        host.close { closed = it }
        RouteAP2pShadow.createListener!!.onSuccess()
        RouteAP2pShadow.removeListener!!.onSuccess()
        assertEquals(GroupNetworkCloseResult.RELEASED, closed)
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun hostChannelDisconnectFencesPendingCreateAndReleasesLeaseWhenNoGroupExists() {
        var errors = 0
        val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, { errors++ }, ownership)
        host.start()
        shadowOf(Looper.getMainLooper()).idle()
        val staleCreate = checkNotNull(RouteAP2pShadow.createListener)

        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply { groupFormed = false }
        checkNotNull(RouteAP2pShadow.channelListener).onChannelDisconnected()

        assertEquals(1, errors)
        assertFalse(ownership.hasOwner())
        staleCreate.onSuccess()
        staleCreate.onFailure(WifiP2pManager.ERROR)
        assertFalse(ownership.hasOwner())
        assertNull(RouteAP2pShadow.removeListener)
    }

    @Test
    fun hostChannelDisconnectRemovesGroupDiscoveredOnReplacementChannel() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456")
        val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, {}, ownership, preset)
        host.start()
        shadowOf(Looper.getMainLooper()).idle()
        val staleCreate = checkNotNull(RouteAP2pShadow.createListener)
        RouteAP2pShadow.existingGroup = observedGroup(preset.networkName, owner = true)

        checkNotNull(RouteAP2pShadow.channelListener).onChannelDisconnected()

        assertTrue(ownership.hasOwner())
        assertNotNull(RouteAP2pShadow.removeListener)
        staleCreate.onSuccess()
        assertTrue(ownership.hasOwner())
        RouteAP2pShadow.removeListener!!.onSuccess()
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun disconnectedHostNeverRemovesAnotherGroup() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456")
        for (otherIsOwner in listOf(false, true)) {
            RouteAP2pShadow.reset()
            val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, {}, ownership, preset)
            host.start()
            shadowOf(Looper.getMainLooper()).idle()
            RouteAP2pShadow.existingGroup = observedGroup("DIRECT-mc-another", owner = otherIsOwner)
            checkNotNull(RouteAP2pShadow.channelListener).onChannelDisconnected()
            assertNull(RouteAP2pShadow.removeListener)
            assertFalse(ownership.hasOwner())
        }
    }

    private fun observedGroup(ssid: String, owner: Boolean) = WifiP2pGroup().also {
        // The SDK 35 Robolectric setter for the name writes the interface field.
        WifiP2pGroup::class.java.getDeclaredField("mNetworkName").apply { isAccessible = true }.set(it, ssid)
        WifiP2pGroup::class.java.getMethod("setIsGroupOwner", Boolean::class.javaPrimitiveType).invoke(it, owner)
        assertEquals(ssid, it.networkName)
        assertEquals(owner, it.isGroupOwner)
    }

    @Test
    fun recoveryWaitsForAbsenceWhenLegacyCreateNeverReportedItsIdentity() {
        val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, {}, ownership)
        host.start()
        shadowOf(Looper.getMainLooper()).idle()
        RouteAP2pShadow.existingGroup = observedGroup("DIRECT-unknown", owner = true)
        checkNotNull(RouteAP2pShadow.channelListener).onChannelDisconnected()
        host.retryCleanup()
        assertTrue(ownership.hasOwner())
        assertNull(RouteAP2pShadow.removeListener)
        RouteAP2pShadow.existingGroup = null
        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply { groupFormed = false }
        host.retryCleanup()
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun delayedRemovalRechecksTheGroupAfterSynchronousRemoveFailure() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456")
        val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, {}, ownership, preset)
        host.start()
        shadowOf(Looper.getMainLooper()).idle()
        RouteAP2pShadow.existingGroup = observedGroup(preset.networkName, owner = true)
        RouteAP2pShadow.rejectRemove = true
        checkNotNull(RouteAP2pShadow.channelListener).onChannelDisconnected()
        assertTrue(ownership.hasOwner())
        RouteAP2pShadow.rejectRemove = false
        RouteAP2pShadow.existingGroup = observedGroup("DIRECT-mc-another", owner = true)
        host.retryCleanup()
        assertNull(RouteAP2pShadow.removeListener)
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun failedChannelReplacementKeepsLeaseUntilRetryConfirmsAbsence() {
        val host = GroupWifiHost(app, { _, _ -> fail("not expected") }, {}, ownership)
        host.start()
        shadowOf(Looper.getMainLooper()).idle()
        val disconnect = checkNotNull(RouteAP2pShadow.channelListener)
        RouteAP2pShadow.rejectInitialize = true
        disconnect.onChannelDisconnected()
        host.retryCleanup()
        assertTrue(ownership.hasOwner())

        RouteAP2pShadow.rejectInitialize = false
        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply { groupFormed = false }
        host.retryCleanup()
        val successor = checkNotNull(ownership.acquire())
        disconnect.onChannelDisconnected()
        assertTrue(ownership.owns(successor))
        assertNull(RouteAP2pShadow.removeListener)
    }

    @Test
    fun routeAClientPrefersExactFrequencyWhenPresentAndCleansUpOwnership() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", frequencyMhz = 2437, prefer2GhzBand = true)
        val goAddr = InetAddress.getByName("192.168.49.1") as Inet4Address
        val clientIpv4 = InetAddress.getByName("192.168.49.42") as Inet4Address
        var readyLink: GroupLocalSocketLink? = null
        var boundInterface: String? = null
        var boundAddr: Inet4Address? = null

        val client = GroupWifiP2pClient(
            context = app,
            preset = preset,
            onReady = { readyLink = it },
            onError = { fail("Unexpected error: $it") },
            ownership = ownership,
            p2pAddressResolver = { clientIpv4 },
            socketBinderFactory = { _, iface, addr ->
                boundInterface = iface
                boundAddr = addr
                {}
            }
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        val config = checkNotNull(RouteAP2pShadow.lastConnectConfig)
        assertEquals("DIRECT-mc-abcd1234", config.networkName)
        assertEquals("SecretPass123456", config.passphrase)
        assertEquals(2437, config.groupOwnerBand)

        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply {
            groupFormed = true
            isGroupOwner = false
            groupOwnerAddress = goAddr
        }
        RouteAP2pShadow.connectListener!!.onSuccess()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(readyLink)
        assertEquals("192.168.49.1", readyLink!!.hostAddress)
        assertEquals(clientIpv4, boundAddr)
        assertNull(boundInterface)

        var closeResult: GroupNetworkCloseResult? = null
        client.close { closeResult = it }
        RouteAP2pShadow.removeListener!!.onSuccess()
        assertEquals(GroupNetworkCloseResult.RELEASED, closeResult)
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun routeAClientCancelsPendingConnectAndReleasesLeaseOnFailure() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", prefer2GhzBand = true)
        val client = GroupWifiP2pClient(
            context = app,
            preset = preset,
            onReady = { fail("Should not become ready") },
            onError = {},
            ownership = ownership
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        val config = checkNotNull(RouteAP2pShadow.lastConnectConfig)
        assertEquals(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, config.groupOwnerBand)

        var closeResult: GroupNetworkCloseResult? = null
        client.close { closeResult = it }
        assertTrue(RouteAP2pShadow.cancelConnectCalled)
        assertTrue(ownership.hasOwner())
        RouteAP2pShadow.connectListener!!.onFailure(WifiP2pManager.ERROR)
        assertEquals(GroupNetworkCloseResult.RELEASED, closeResult)
        assertFalse(ownership.hasOwner())
        RouteAP2pShadow.cancelConnectListener?.onSuccess()
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun presetHostLocksExactFrequencyMutuallyExclusiveWith2GhzBand() {
        val preset = GroupWifiP2pPreset(
            networkName = "DIRECT-mc-abcd1234",
            passphrase = "SecretPass123456",
            frequencyMhz = 2462,
            prefer2GhzBand = true
        )
        val host = GroupWifiHost(
            context = app,
            onReady = { _, _ -> },
            onError = { fail("Unexpected error: $it") },
            ownership = ownership,
            preset = preset
        )
        host.start()
        shadowOf(Looper.getMainLooper()).idle()

        val captured = checkNotNull(RouteAP2pShadow.lastCreateConfig)
        assertEquals("DIRECT-mc-abcd1234", captured.networkName)
        assertEquals("SecretPass123456", captured.passphrase)
        assertEquals(2462, captured.groupOwnerBand)

        var closed: GroupNetworkCloseResult? = null
        host.close { closed = it }
        RouteAP2pShadow.createListener!!.onSuccess()
        RouteAP2pShadow.removeListener!!.onSuccess()
        assertEquals(GroupNetworkCloseResult.RELEASED, closed)
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun routeAClientReleasesLeaseWhenCancelConnectSucceedsWithoutConnectCallback() {
        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply {
            groupFormed = false
        }
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", prefer2GhzBand = true)
        val client = GroupWifiP2pClient(
            context = app,
            preset = preset,
            onReady = { fail("Should not become ready") },
            onError = {},
            ownership = ownership
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()
        val connectCb = checkNotNull(RouteAP2pShadow.connectListener)

        var closeResult: GroupNetworkCloseResult? = null
        client.close { closeResult = it }
        assertTrue(RouteAP2pShadow.cancelConnectCalled)
        assertNotNull(RouteAP2pShadow.cancelConnectListener)

        // Simulate ROMs where cancelConnect succeeds and original connect() callback never arrives.
        RouteAP2pShadow.cancelConnectListener!!.onSuccess()
        assertEquals(GroupNetworkCloseResult.RELEASED, closeResult)
        assertFalse(ownership.hasOwner())
        assertNull(RouteAP2pShadow.removeListener)

        // Late fenced callbacks must be ignored and must never call removeGroup or acquire/corrupt lease.
        connectCb.onSuccess()
        connectCb.onFailure(WifiP2pManager.ERROR)
        assertFalse(ownership.hasOwner())
        assertNull(RouteAP2pShadow.removeListener)
    }

    @Test
    fun routeAClientRemovesFormedGroupWhenCancelConnectRacesWithJoin() {
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", prefer2GhzBand = true)
        val client = GroupWifiP2pClient(
            context = app,
            preset = preset,
            onReady = { fail("Should not become ready") },
            onError = {},
            ownership = ownership
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        // Group forms right as cancelConnect races with connect().
        var closeResult: GroupNetworkCloseResult? = null
        client.close { closeResult = it }
        RouteAP2pShadow.existingGroup = WifiP2pGroup()
        RouteAP2pShadow.cancelConnectListener!!.onSuccess()

        assertNotNull("Formed group discovered during cancel must invoke removeGroup()", RouteAP2pShadow.removeListener)
        assertNull(closeResult)
        RouteAP2pShadow.removeListener!!.onSuccess()
        assertEquals(GroupNetworkCloseResult.RELEASED, closeResult)
        assertFalse(ownership.hasOwner())
    }

    @Test
    fun routeAClientRecoversViaFallbackWhenCancelConnectFailsWithoutConnectCallback() {
        RouteAP2pShadow.connectionInfo = WifiP2pInfo().apply {
            groupFormed = false
        }
        val preset = GroupWifiP2pPreset("DIRECT-mc-abcd1234", "SecretPass123456", prefer2GhzBand = true)
        val client = GroupWifiP2pClient(
            context = app,
            preset = preset,
            onReady = { fail("Should not become ready") },
            onError = {},
            ownership = ownership
        )
        client.start()
        shadowOf(Looper.getMainLooper()).idle()

        var closeResult: GroupNetworkCloseResult? = null
        client.close { closeResult = it }
        RouteAP2pShadow.cancelConnectListener!!.onFailure(WifiP2pManager.BUSY)
        assertNull(closeResult)
        assertTrue(ownership.hasOwner())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
        assertEquals(GroupNetworkCloseResult.RELEASED, closeResult)
        assertFalse(ownership.hasOwner())
    }
}

@Implements(WifiP2pManager::class)
class RouteAP2pShadow : ShadowWifiP2pManager() {
    @Implementation
    override fun initialize(context: Context, looper: Looper, listener: WifiP2pManager.ChannelListener?): WifiP2pManager.Channel? {
        if (rejectInitialize) return null
        channelListener = listener
        return super.initialize(context, looper, listener)
    }

    @Implementation
    override fun createGroup(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ActionListener) {
        createListener = listener
    }

    @Implementation
    override fun requestGroupInfo(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.GroupInfoListener) {
        listener.onGroupInfoAvailable(existingGroup)
    }

    @Implementation
    fun requestConnectionInfo(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ConnectionInfoListener) {
        listener.onConnectionInfoAvailable(connectionInfo)
    }

    @Implementation
    fun createGroup(channel: WifiP2pManager.Channel?, config: WifiP2pConfig, listener: WifiP2pManager.ActionListener) {
        lastCreateConfig = config
        createListener = listener
    }

    @Implementation
    fun connect(channel: WifiP2pManager.Channel?, config: WifiP2pConfig?, listener: WifiP2pManager.ActionListener?) {
        lastConnectConfig = config
        connectListener = listener
    }

    @Implementation
    fun cancelConnect(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ActionListener?) {
        cancelConnectCalled = true
        cancelConnectListener = listener
    }

    @Implementation
    override fun removeGroup(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ActionListener) {
        check(!rejectRemove)
        removeListener = listener
    }

    companion object {
        var channelListener: WifiP2pManager.ChannelListener? = null
        var rejectInitialize = false
        var rejectRemove = false
        var existingGroup: WifiP2pGroup? = null
        var connectionInfo: WifiP2pInfo? = null
        var lastCreateConfig: WifiP2pConfig? = null
        var lastConnectConfig: WifiP2pConfig? = null
        var createListener: WifiP2pManager.ActionListener? = null
        var connectListener: WifiP2pManager.ActionListener? = null
        var cancelConnectListener: WifiP2pManager.ActionListener? = null
        var removeListener: WifiP2pManager.ActionListener? = null
        var cancelConnectCalled = false

        fun reset() {
            channelListener = null
            rejectInitialize = false
            rejectRemove = false
            existingGroup = null
            connectionInfo = null
            lastCreateConfig = null
            lastConnectConfig = null
            createListener = null
            connectListener = null
            cancelConnectListener = null
            removeListener = null
            cancelConnectCalled = false
        }
    }
}
