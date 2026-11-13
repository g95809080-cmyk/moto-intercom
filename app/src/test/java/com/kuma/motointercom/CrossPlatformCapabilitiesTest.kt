package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformCapabilitiesTest {
    @Test
    fun androidApiGateOnlyAdvertisesLocalOnlyHotspotFromApi26() {
        assertTrue(
            MotoComCapability.ANDROID_LOCAL_ONLY_HOTSPOT_HOST !in
                CrossPlatformCapabilities.localAndroid(25, true, true, true).capabilities
        )
        assertTrue(
            MotoComCapability.ANDROID_LOCAL_ONLY_HOTSPOT_HOST in
                CrossPlatformCapabilities.localAndroid(26, true, true, true).capabilities
        )
    }

    @Test
    fun androidHostWinsAutomaticIosPathWhenNoCommonLanExists() {
        val android = CrossPlatformCapabilities.localAndroid(28, true, true, false)
        val ios = CrossPlatformCapabilities(
            platform = PeerPlatform.IOS,
            capabilities = setOf(
                MotoComCapability.BLE_BOOTSTRAP,
                MotoComCapability.WIFI_JOIN,
                MotoComCapability.LAN
            )
        )

        assertEquals(
            BootstrapPath.ANDROID_LOCAL_ONLY_HOTSPOT,
            CrossPlatformBootstrapPolicy.choosePath(android, ios, commonLanAvailable = false)
        )
    }

    @Test
    fun commonLanPrecedesHotspotAndAndroidDirectIsKeptAsFallback() {
        val android = CrossPlatformCapabilities.localAndroid(28, true, true, true)
        val ios = CrossPlatformCapabilities(
            platform = PeerPlatform.IOS,
            capabilities = setOf(MotoComCapability.LAN, MotoComCapability.WIFI_JOIN)
        )

        assertEquals(
            BootstrapPath.COMMON_LAN,
            CrossPlatformBootstrapPolicy.choosePath(android, ios, commonLanAvailable = true)
        )

        val secondAndroid = CrossPlatformCapabilities.localAndroid(25, true, true, true)
        assertEquals(
            BootstrapPath.ANDROID_WIFI_DIRECT,
            CrossPlatformBootstrapPolicy.choosePath(
                secondAndroid,
                secondAndroid.copy(capabilities = secondAndroid.capabilities),
                commonLanAvailable = false
            )
        )
    }

    @Test
    fun androidPairPrefersExistingWifiDirectBeforeCreatingHotspot() {
        val firstAndroid = CrossPlatformCapabilities.localAndroid(28, true, true, true)
        val secondAndroid = CrossPlatformCapabilities.localAndroid(28, true, true, true)

        assertEquals(
            BootstrapPath.ANDROID_WIFI_DIRECT,
            CrossPlatformBootstrapPolicy.choosePath(
                firstAndroid,
                secondAndroid,
                commonLanAvailable = false
            )
        )
    }

    @Test
    fun explicitRemoteAndroidHostFallsBackToSystemWifiJoin() {
        val local = CrossPlatformCapabilities.localAndroid(28, true, true, false)
        val remote = CrossPlatformCapabilities(
            platform = PeerPlatform.ANDROID,
            capabilities = setOf(
                MotoComCapability.WIFI_JOIN,
                MotoComCapability.ANDROID_LOCAL_ONLY_HOTSPOT_HOST
            ),
            networkRole = BootstrapNetworkRole.HOST
        )

        assertEquals(
            BootstrapPath.MANUAL_SYSTEM_WIFI,
            CrossPlatformBootstrapPolicy.choosePath(
                local,
                remote,
                commonLanAvailable = false,
                hostPreference = BootstrapHostPreference.REMOTE
            )
        )
    }

    @Test
    fun iosManualHotspotIsSelectedWhenAndroidCannotHost() {
        val android = CrossPlatformCapabilities.localAndroid(25, true, true, false)
        val ios = CrossPlatformCapabilities(
            platform = PeerPlatform.IOS,
            capabilities = setOf(
                MotoComCapability.LAN,
                MotoComCapability.IOS_PERSONAL_HOTSPOT_MANUAL
            )
        )

        assertEquals(
            BootstrapPath.MANUAL_SYSTEM_WIFI,
            CrossPlatformBootstrapPolicy.choosePath(android, ios, commonLanAvailable = false)
        )
    }

    @Test
    fun hotspotSummaryNeverIncludesPassphrase() {
        val credentials = HotspotCredentials(
            ssid = "MotoCom",
            passphrase = "123456789",
            security = "WPA2"
        )

        val summary = credentials.redactedSummary()
        assertTrue("123456789" !in summary)
        assertTrue("<redacted>" in summary)
    }
}
