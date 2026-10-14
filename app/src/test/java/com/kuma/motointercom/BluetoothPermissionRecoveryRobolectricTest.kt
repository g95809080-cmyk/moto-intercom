package com.kuma.motointercom

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BluetoothPermissionRecoveryRobolectricTest {
    @Test fun settingsGrantReappliesBluetoothOnceThroughTheRealActivityAndService() =
        verifyGrant(AudioRouteSelection.BLUETOOTH, fromSettings = true)

    @Test fun permissionResultReappliesBluetoothWithoutTappingTheAudioRoute() =
        verifyGrant(AudioRouteSelection.BLUETOOTH, fromSettings = false)

    @Test fun grantingBluetoothPermissionPreservesTheExplicitPhonePreference() =
        verifyGrant(AudioRouteSelection.EARPIECE, fromSettings = true)

    private fun verifyGrant(preferred: AudioRouteSelection, fromSettings: Boolean) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        AudioRoutePreferences(application).save(preferred)
        val databaseField = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        databaseField.set(null, null)
        val serviceOwner = Robolectric.buildService(IntercomService::class.java).create()
        val calls = mutableListOf<AudioRouteSelection>()
        val engine = object : RiderMediaEngine {
            override fun updateAudioControls(controls: VersionedAudioControls) = Unit
            override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession = error("unused")
            override fun close() = Unit
        }
        val route = object : RiderAudioRoute {
            override fun select(selection: AudioRouteSelection) { calls.add(selection) }
            override fun close() = Unit
        }
        val audio = AudioSessionController(engine, route, preferred)
        field(serviceOwner.get(), "audioSessionController").set(serviceOwner.get(), audio)
        shadowOf(application).setComponentNameAndServiceForBindService(
            ComponentName(application, IntercomService::class.java), serviceOwner.get().onBind(Intent()))
        val activityOwner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        calls.clear()
        try {
            shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            if (fromSettings) activityOwner.pause().resume() else {
                val code = MainActivity::class.java.getDeclaredField("REQUEST_OPTIONAL_PERMISSIONS").run {
                    isAccessible = true; getInt(null)
                }
                activityOwner.get().onRequestPermissionsResult(code, arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    intArrayOf(PackageManager.PERMISSION_GRANTED))
                activityOwner.pause().resume()
            }
            shadowOf(Looper.getMainLooper()).idle()
            val expected = if (preferred == AudioRouteSelection.BLUETOOTH) listOf(preferred) else emptyList()
            assertEquals(expected, calls)
            activityOwner.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(expected, calls)
            assertEquals(preferred, AudioRoutePreferences(application).load())
        } finally {
            activityOwner.pause().stop().destroy()
            val scope = field(serviceOwner.get(), "serviceScope").get(serviceOwner.get()) as CoroutineScope
            val stopped = CountDownLatch(1)
            scope.coroutineContext[Job]?.invokeOnCompletion { stopped.countDown() } ?: stopped.countDown()
            serviceOwner.destroy()
            assertTrue("Service scope did not stop", stopped.await(5, TimeUnit.SECONDS))
            (databaseField.get(null) as? PairingDatabase)?.close()
            databaseField.set(null, null)
            audio.close()
        }
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
