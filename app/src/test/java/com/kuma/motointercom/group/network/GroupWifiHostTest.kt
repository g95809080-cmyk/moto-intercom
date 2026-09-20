package com.kuma.motointercom.group.network

import android.Manifest
import android.app.Application
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [DeferredP2p::class])
class GroupWifiHostTest {
    private lateinit var context: Application
    private lateinit var ownership: GroupGoOwnership
    private val errors = mutableListOf<String>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        context.getSystemService(WifiManager::class.java).isWifiEnabled = true
        ownership = GroupGoOwnership()
        DeferredP2p.create = null; DeferredP2p.remove = null; DeferredP2p.existing = null
        DeferredP2p.connection = null; DeferredP2p.deferInfo = false; DeferredP2p.infoCallbacks.clear()
    }
    private fun host() = GroupWifiHost(context, { _, _ -> fail("unexpected ready") }, errors::add, ownership)
    @Test fun closeDuringCreateDoesNotLetLateSuccessDeleteNewOwner() {
        val host = host(); host.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(DeferredP2p.create)
        val results = mutableListOf<GroupNetworkCloseResult>()
        host.close(results::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf(GroupNetworkCloseResult.UNKNOWN), results)
        assertNull(ownership.acquire())
        DeferredP2p.create!!.onSuccess()
        assertNotNull(DeferredP2p.remove)
        assertNull(ownership.acquire())
        DeferredP2p.remove!!.onSuccess()
        assertNotNull(ownership.acquire())
        assertEquals(1, results.size)
    }
    @Test fun removeFailureRetainsOwnerUntilExplicitRetrySucceeds() {
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle()
        host.close { }
        DeferredP2p.create!!.onSuccess()
        DeferredP2p.remove!!.onFailure(WifiP2pManager.BUSY)
        assertNull(ownership.acquire())
        host.retryCleanup()
        DeferredP2p.remove!!.onSuccess()
        assertNotNull(ownership.acquire())
    }
    @Test fun preExistingGroupIsNeverRemoved() {
        DeferredP2p.existing = WifiP2pGroup()
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle()
        assertNull(DeferredP2p.create); assertNull(DeferredP2p.remove)
        assertEquals(1, errors.size)
        assertNotNull(ownership.acquire())
    }
    @Test fun createFailureAfterCloseReleasesWithoutRemove() {
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle()
        val results = mutableListOf<GroupNetworkCloseResult>()
        host.close(results::add)
        DeferredP2p.create!!.onFailure(WifiP2pManager.ERROR)
        assertEquals(listOf(GroupNetworkCloseResult.RELEASED), results)
        assertNull(DeferredP2p.remove)
        assertNotNull(ownership.acquire())
    }
    @Test fun alreadyRemovedGroupReleasesAfterBothAbsenceResponses() {
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle()
        host.close { }
        DeferredP2p.create!!.onSuccess()
        DeferredP2p.connection = WifiP2pInfo().apply { groupFormed = false }
        DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        assertFalse(ownership.hasOwner())
        var result: GroupNetworkCloseResult? = null
        host.close { result = it }
        assertEquals(GroupNetworkCloseResult.RELEASED, result)
        assertNotNull(ownership.acquire())
    }
    @Test fun absentGroupButFormedConnectionCannotRelease() {
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle(); host.close { }
        DeferredP2p.create!!.onSuccess()
        DeferredP2p.connection = WifiP2pInfo().apply { groupFormed = true }
        DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        assertTrue(ownership.hasOwner())
        DeferredP2p.connection!!.groupFormed = false
        host.retryCleanup(); DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        assertFalse(ownership.hasOwner())
    }
    @Test fun expiredAbsenceCallbackCannotReleaseCurrentOrSuccessorOwner() {
        val host = host(); host.start(); shadowOf(Looper.getMainLooper()).idle(); host.close { }
        DeferredP2p.create!!.onSuccess()
        DeferredP2p.deferInfo = true
        DeferredP2p.connection = WifiP2pInfo().apply { groupFormed = false }
        DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        val stale = DeferredP2p.infoCallbacks.single()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(ownership.hasOwner())
        host.retryCleanup(); DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        stale.onGroupInfoAvailable(null)
        assertTrue(ownership.hasOwner())
        DeferredP2p.infoCallbacks.last().onGroupInfoAvailable(null)
        val successor = checkNotNull(ownership.acquire())
        stale.onGroupInfoAvailable(null)
        host.retryCleanup()
        assertTrue(ownership.owns(successor))
    }
}

@Implements(WifiP2pManager::class)
class DeferredP2p : ShadowWifiP2pManager() {
    @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.GroupInfoListener) {
        if (deferInfo) infoCallbacks += listener else listener.onGroupInfoAvailable(existing)
    }
    @Implementation fun requestConnectionInfo(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ConnectionInfoListener) {
        listener.onConnectionInfoAvailable(connection)
    }
    @Implementation override fun createGroup(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ActionListener) { create = listener }
    @Implementation override fun removeGroup(channel: WifiP2pManager.Channel?, listener: WifiP2pManager.ActionListener) { remove = listener }
    companion object {
        var create: WifiP2pManager.ActionListener? = null
        var remove: WifiP2pManager.ActionListener? = null
        var existing: WifiP2pGroup? = null
        var connection: WifiP2pInfo? = null
        var deferInfo = false
        val infoCallbacks = mutableListOf<WifiP2pManager.GroupInfoListener>()
    }
}
