package com.kuma.motointercom

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class StartupAccessControllerTest {
    private lateinit var activity: Activity
    private lateinit var access: StartupAccessController
    private var starts = 0
    private var lastStatus = ""

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("startup_access", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        (activity.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = true
        shadowOf(activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(true)
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIgnoringBatteryOptimizations(activity.packageName, true)
        access = controller()
    }

    private fun controller() = StartupAccessController(activity, { lastStatus = it }, {}, { starts++ })
    private fun grantCore() = shadowOf(activity.application).grantPermissions(*PermissionPolicy.corePermissions(android.os.Build.VERSION.SDK_INT).toTypedArray())
    private fun grantAll() = shadowOf(activity.application).grantPermissions(
        *(PermissionPolicy.corePermissions(android.os.Build.VERSION.SDK_INT) + PermissionPolicy.optionalPermissions(android.os.Build.VERSION.SDK_INT)).toTypedArray())

    @Test fun skippedGuideRequestsAllPermissionsAndContinuesExactlyOnceAfterGrant() {
        OnboardingPreferences(activity).save(OnboardingPhase.COMPLETE)
        access.begin()
        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(StartupAccessController.PERMISSIONS, request.requestCode)
        assertTrue(request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(request.requestedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(0, starts)
        grantAll()
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        assertEquals(0, starts)
        access.onResume()
        access.onResume()
        assertEquals(1, starts)
    }

    @Test @Config(sdk = [33])
    fun android13RequestsNearbyAndNotificationsWithoutLegacyLocationPermission() {
        access.begin()
        val permissions = shadowOf(activity).lastRequestedPermission.requestedPermissions
        assertTrue(permissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertFalse(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        grantCore()
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        access.onResume()
        assertEquals(1, starts)
    }

    @Test @Config(sdk = [23])
    fun android6UsesLocationPermissionAndCanStart() {
        access.begin()
        assertTrue(shadowOf(activity).lastRequestedPermission.requestedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        grantAll()
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        access.onResume()
        assertEquals(1, starts)
    }

    @Test fun wifiOffWaitsForSettingsResultThenStarts() {
        grantAll()
        (activity.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = false
        access.begin()
        assertEquals(Settings.ACTION_WIFI_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertEquals(0, starts)
        (activity.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = true
        access.onActivityResult(StartupAccessController.WIFI)
        access.onResume()
        assertEquals(1, starts)
    }

    @Test fun deniedCoreNeverStartsAndNextClickOffersSettingsWithoutClearingData() {
        access.begin()
        shadowOf(activity).nextStartedActivity // consume the OS permission request intent
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        access.onResume()
        assertEquals(0, starts)
        assertTrue(lastStatus.contains("缺少必要权限"))
        access.begin()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        grantAll()
        access.onActivityResult(StartupAccessController.APP_SETTINGS)
        access.onResume()
        assertEquals(1, starts)
    }

    @Test fun bluetoothDenialStillStartsPhoneAudioAndDoesNotPromptAgain() {
        access.begin()
        grantCore()
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        access.onResume()
        assertEquals(1, starts)
        access.begin()
        assertEquals(2, starts)
    }

    @Test fun locationOffBlocksRuntimeAndReturningWithoutEnablingDoesNotLoop() {
        grantAll()
        shadowOf(activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(false)
        access.begin()
        assertEquals(0, starts)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        access.onActivityResult(StartupAccessController.LOCATION)
        access.onResume()
        assertEquals(0, starts)
        shadowOf(activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).setLocationEnabled(true)
        access.begin()
        assertEquals(1, starts)
    }

    @Test fun deniedBatteryExemptionContinuesAndStateRemainsUnapproved() {
        grantAll()
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIgnoringBatteryOptimizations(activity.packageName, false)
        access.begin()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals("package:${activity.packageName}", intent.data.toString())
        access.onActivityResult(StartupAccessController.BACKGROUND)
        access.onResume()
        assertEquals(1, starts)
        assertFalse(backgroundAllowed(activity))
    }

    @Test fun skippingBatteryPromptContinuesWithoutRepeatingPrompt() {
        grantAll()
        shadowOf(activity.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIgnoringBatteryOptimizations(activity.packageName, false)
        access.begin()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, starts)
        access.begin()
        assertEquals(2, starts)
    }

    @Test fun permissionRequestSurvivesRecreationAndDoesNotStartBeforeResult() {
        access.begin()
        val saved = Bundle().also(access::save)
        access.close()
        access = controller().also { it.restore(saved) }
        access.onResume()
        assertEquals(0, starts)
        grantAll()
        access.onPermissionsResult(StartupAccessController.PERMISSIONS)
        access.onResume()
        assertEquals(1, starts)
    }

    @Test fun backgroundSettingsAloneNeverStartsIntercom() {
        access.requestBackground()
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        access.onResume()
        assertEquals(0, starts)
    }
}
