package com.kuma.motointercom.group

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class GroupAccessTest {
    @Test
    @Config(sdk = [33])
    fun androidTwelveRequestsBothLocationPermissionsAndStillRequiresFineLocation() {
        for (sdkInt in listOf(31, 32)) {
            val required = groupRequiredPermissions(sdkInt)
            assertTrue(required.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
            assertTrue(required.contains(Manifest.permission.ACCESS_FINE_LOCATION))
            assertFalse(required.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        }

        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        assertFalse(app.hasGroupPermissions(31))
        val upgradePrompt = app.missingGroupPromptPermissions(groupRequiredPermissions(31))
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in upgradePrompt)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in upgradePrompt)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(app.hasGroupPermissions(31))
    }
}
