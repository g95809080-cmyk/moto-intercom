package com.kuma.motointercom.group.hybrid

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupLocalProximityProbeTest {
    @Test
    fun legacyBeaconServiceDataFitsThirtyOneByteLimit() {
        val tag = GroupBleProximityProbe.proximityTag("room-payload")
        assertEquals(8, tag.size)
        assertTrue(GroupBleProximityProbe.legacyServiceDataAdvertisementBytes(tag.size) <= 31)
    }

    @Test
    fun wifiScanEnforcesMinus75DbmMarginAnd31SecondThrottleCadence() {
        val app = RuntimeEnvironment.getApplication()
        var currentRssi: Int? = -85
        var scanTriggerCount = 0
        val reports = mutableListOf<GroupProximityReport>()

        val probe = GroupBleProximityProbe(
            context = app,
            canScanWifi = { true },
            readWifiRssi = { _, ssid ->
                assertEquals("DIRECT-mc-test50m", ssid)
                currentRssi
            },
            triggerWifiScan = {
                scanTriggerCount += 1
                true
            }
        )
        try {
            probe.startScan("room-50m", "DIRECT-mc-test50m") { reports += it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, scanTriggerCount)
            assertEquals(GroupProximityReport(reachable = false, rssiDbm = -85), reports.last())

            currentRssi = -75
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(GroupProximityReport(reachable = true, rssiDbm = -75), reports.last())
            assertEquals("Must not exceed 1 active startScan within 30s", 1, scanTriggerCount)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(110))
            assertTrue("Expected <= 4 Wi-Fi startScans in 120s, got $scanTriggerCount", scanTriggerCount <= 4)
            assertTrue("Expected at least 3 periodic Wi-Fi scans in 120s, got $scanTriggerCount", scanTriggerCount >= 3)
        } finally {
            probe.close()
        }
    }

    @Test
    fun gracefullyDegradesWithoutCrashingWhenWifiScanPermissionIsDenied() {
        val app = RuntimeEnvironment.getApplication()
        var wifiScans = 0
        val probe = GroupBleProximityProbe(
            context = app,
            canScanWifi = { false },
            readWifiRssi = { _, _ -> throw AssertionError("Should not read Wi-Fi RSSI without permission") },
            triggerWifiScan = {
                wifiScans += 1
                true
            }
        )
        try {
            probe.startScan("room-no-loc", "DIRECT-mc-noloc") {}
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(0, wifiScans)
        } finally {
            probe.close()
        }
    }
}
