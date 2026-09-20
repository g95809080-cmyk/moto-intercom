package com.kuma.motointercom.group

import android.Manifest
import android.app.Application
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.network.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [DeferredP2p::class])
class GroupNetworkReleaseTest {
    @Test fun recoveryAndStopShareOneCleanupAndNotifyAllWaiters() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        context.getSystemService(WifiManager::class.java).isWifiEnabled = true
        DeferredP2p.create = null; DeferredP2p.remove = null; DeferredP2p.existing = null
        DeferredP2p.connection = null; DeferredP2p.deferInfo = false; DeferredP2p.infoCallbacks.clear()
        val ownership = GroupGoOwnership()
        val host = GroupWifiHost(context, { _, _ -> fail("not expected") }, { }, ownership)
        host.start(); shadowOf(Looper.getMainLooper()).idle()
        val endpoint = GroupAuthEndpoint("test-host", "runtime")
        val writer = GroupSessionOrchestrator(endpoint, "host", { 0 }, { }, { })
        val network = GroupNetworkRuntime(context, endpoint, { writer.snapshot }, { })
        val hostField = network.javaClass.getDeclaredField("wifiHost").apply { isAccessible = true }
        hostField.set(network, host)
        val release = network.javaClass.getDeclaredMethod("releaseHost", kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
        var first = 0; var second = 0
        release.invoke(network, { first++; throw IllegalStateException("observer failure") })
        release.invoke(network, { second++ })
        DeferredP2p.create!!.onSuccess()
        DeferredP2p.remove!!.onFailure(WifiP2pManager.ERROR)
        val retryField = network.javaClass.getDeclaredField("releaseRetry").apply { isAccessible = true }
        val retry = retryField.get(network)
        assertNotNull(retry)
        assertEquals(0, first); assertEquals(0, second)
        DeferredP2p.connection = WifiP2pInfo().apply { groupFormed = false }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, first); assertEquals(1, second)
        assertNull(hostField.get(network)); assertNull(retryField.get(network))
        val successor = checkNotNull(ownership.acquire())
        (retry as Runnable).run()
        assertTrue(ownership.owns(successor))
        assertEquals(1, first); assertEquals(1, second)
    }
}
