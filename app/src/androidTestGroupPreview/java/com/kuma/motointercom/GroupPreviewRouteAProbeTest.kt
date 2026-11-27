package com.kuma.motointercom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuma.motointercom.group.network.GroupGoOwnership
import com.kuma.motointercom.group.network.GroupLocalSocketLink
import com.kuma.motointercom.group.network.GroupNetworkCloseResult
import com.kuma.motointercom.group.network.GroupWifiCredentials
import com.kuma.motointercom.group.network.GroupWifiHost
import com.kuma.motointercom.group.network.GroupWifiP2pClient
import com.kuma.motointercom.group.network.GroupWifiP2pPreset
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device Route A (Android 10+ WifiP2pConfig.Builder) verification probe:
 * - Host test: verifies 2.4GHz preset GO creation (DIRECT-mc-...) and clean GroupGoOwnership release.
 * - Client test (pass -e routeA_ssid / -e routeA_pass / optional -e routeA_freq): verifies 0-popup p2p0 join.
 */
@RunWith(AndroidJUnit4::class)
class GroupPreviewRouteAProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val main = Handler(Looper.getMainLooper())

    @Test
    fun routeAHostCreates24GhzPresetGroupAndReleasesCleanly() {
        assumeTrue("Route A requires Android 10+", Build.VERSION.SDK_INT >= 29)
        grantWifiP2pPermissions()

        val preset = GroupWifiP2pPreset(
            networkName = "DIRECT-mc-probe24g",
            passphrase = "ProbeRouteA20269",
            prefer2GhzBand = true
        )
        val readyLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val readyCredentials = AtomicReference<GroupWifiCredentials?>()
        val readyAddress = AtomicReference<Inet4Address?>()
        val readyFrequency = AtomicReference<Int?>()
        val errorMessage = AtomicReference<String?>()
        val closeResult = AtomicReference<GroupNetworkCloseResult?>()
        val hostRef = AtomicReference<GroupWifiHost?>()

        main.post {
            val host = GroupWifiHost(
                context = targetContext,
                onReady = { credentials, address ->
                    readyCredentials.set(credentials)
                    readyAddress.set(address)
                    readyLatch.countDown()
                },
                onError = { message ->
                    errorMessage.set(message)
                    readyLatch.countDown()
                },
                ownership = GroupGoOwnership.process,
                preset = preset,
                onGroupFrequency = { freq -> readyFrequency.set(freq) }
            )
            hostRef.set(host)
            host.start()
        }

        try {
            assertTrue("Timed out waiting for Route A 2.4GHz GO", readyLatch.await(20, TimeUnit.SECONDS))
            assertNull("Route A host error: ${errorMessage.get()}", errorMessage.get())
            assertEquals(preset.networkName, readyCredentials.get()?.ssid)
            assertNotNull("Expected IPv4 GO address", readyAddress.get())
            val frequency = readyFrequency.get()
            assertNotNull("Expected reported operating frequency", frequency)
            assertTrue("Expected 2.4GHz operating frequency, got $frequency", frequency!! in 2412..2484)
        } finally {
            main.post {
                hostRef.get()?.close { result ->
                    closeResult.set(result)
                    closeLatch.countDown()
                } ?: closeLatch.countDown()
            }
            assertTrue("Timed out waiting for GO close", closeLatch.await(10, TimeUnit.SECONDS))
            assertEquals(GroupNetworkCloseResult.RELEASED, closeResult.get())
        }
    }

    @Test
    fun routeAClientSilentJoinWhenArgsProvided() {
        assumeTrue("Route A requires Android 10+", Build.VERSION.SDK_INT >= 29)
        val args = InstrumentationRegistry.getArguments()
        val ssid = args.getString("routeA_ssid")?.takeIf { it.isNotBlank() }
        val passphrase = args.getString("routeA_pass")?.takeIf { it.isNotBlank() }
        assumeTrue("Provide -e routeA_ssid and -e routeA_pass for two-device Route A silent join probe", ssid != null && passphrase != null)
        val freq = args.getString("routeA_freq")?.toIntOrNull()
        grantWifiP2pPermissions()

        val preset = GroupWifiP2pPreset(
            networkName = ssid!!,
            passphrase = passphrase!!,
            frequencyMhz = freq,
            prefer2GhzBand = true
        )
        val readyLatch = CountDownLatch(1)
        val closeLatch = CountDownLatch(1)
        val readyLink = AtomicReference<GroupLocalSocketLink?>()
        val errorMessage = AtomicReference<String?>()
        val closeResult = AtomicReference<GroupNetworkCloseResult?>()
        val clientRef = AtomicReference<GroupWifiP2pClient?>()

        main.post {
            val client = GroupWifiP2pClient(
                context = targetContext,
                preset = preset,
                onReady = { link ->
                    readyLink.set(link)
                    readyLatch.countDown()
                },
                onError = { message ->
                    errorMessage.set(message)
                    readyLatch.countDown()
                },
                ownership = GroupGoOwnership.process
            )
            clientRef.set(client)
            client.start()
        }

        try {
            assertTrue("Timed out waiting for Route A silent client join", readyLatch.await(20, TimeUnit.SECONDS))
            assertNull("Route A client error: ${errorMessage.get()}", errorMessage.get())
            assertNotNull("Expected GO IPv4 hostAddress", readyLink.get()?.hostAddress)
        } finally {
            main.post {
                clientRef.get()?.close { result ->
                    closeResult.set(result)
                    closeLatch.countDown()
                } ?: closeLatch.countDown()
            }
            assertTrue("Timed out waiting for Route A client close", closeLatch.await(10, TimeUnit.SECONDS))
            assertEquals(GroupNetworkCloseResult.RELEASED, closeResult.get())
        }
    }

    private fun grantWifiP2pPermissions() {
        val uiAutomation = instrumentation.uiAutomation
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissions.forEach { permission ->
            if (targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, permission)
            }
        }
    }
}
