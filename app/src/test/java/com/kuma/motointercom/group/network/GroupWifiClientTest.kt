@file:Suppress("DEPRECATION")

package com.kuma.motointercom.group.network

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupWifiClientTest {
    private lateinit var context: Application
    private lateinit var wifi: WifiManager
    private lateinit var connectivity: ConnectivityManager
    private val ready = mutableListOf<Network>()
    private val errors = mutableListOf<String>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        wifi = context.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = true
        shadowOf(context.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        connectivity = context.getSystemService(ConnectivityManager::class.java)
    }
    private fun client() = GroupWifiClient(context, GroupWifiCredentials("DIRECT-test", "secret-pass"), ready::add, errors::add)
    private fun saved(ssid: String) = wifi.addNetwork(WifiConfiguration().apply { SSID = "\"$ssid\"" })
    private fun current(id: Int) { shadowOf(wifi.connectionInfo).setNetworkId(id) }
    @Test fun cancelUnregistersRequestAndLateAvailableCannotPublishNetwork() {
        val client = client(); client.start()
        val callbacks = shadowOf(connectivity).networkCallbacks.toList()
        assertEquals(1, callbacks.size)
        client.close()
        callbacks.single().onAvailable(ShadowNetwork.newInstance(77))
        callbacks.single().onUnavailable()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ready.isEmpty()); assertTrue(errors.isEmpty())
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }
    @Test @Config(sdk = [28]) fun legacyReusesSavedConfigurationWithoutDeletingOrRewritingIt() {
        val previous = saved("home")
        val target = saved("DIRECT-test")
        current(previous)
        val client = client(); client.start()
        assertTrue(errors.isEmpty())
        assertEquals(target, shadowOf(wifi).lastEnabledNetwork.first.toInt())
        current(target)
        client.close()
        assertEquals(setOf(previous, target), wifi.configuredNetworks.map { it.networkId }.toSet())
        assertEquals(previous, shadowOf(wifi).lastEnabledNetwork.first.toInt())
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }
    @Test @Config(sdk = [28]) fun legacyUserSwitchIsPreservedAndOnlyOurAddedConfigurationIsDeleted() {
        val previous = saved("home")
        val chosen = saved("user-choice")
        current(previous)
        val client = client(); client.start()
        assertTrue(errors.isEmpty())
        val own = shadowOf(wifi).lastEnabledNetwork.first.toInt()
        assertNotEquals(previous, own); assertNotEquals(chosen, own)
        wifi.enableNetwork(chosen, true); current(chosen)
        client.close()
        assertEquals(chosen, shadowOf(wifi).lastEnabledNetwork.first.toInt())
        assertEquals(setOf(previous, chosen), wifi.configuredNetworks.map { it.networkId }.toSet())
    }
}
