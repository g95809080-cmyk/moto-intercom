package com.kuma.motointercom

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RejectedForegroundStartRegressionTest {
    @Test fun busyStartAcknowledgesForegroundBeforeStoppingWithoutTakingExistingOwner() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(*PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).toTypedArray())
        val owner = checkNotNull(LegacyRuntimeOwnership.acquire())
        try {
            verifyRejectedStart()
            assertTrue(LegacyRuntimeOwnership.hasOwner())
        } finally {
            LegacyRuntimeOwnership.release(owner)
        }
    }

    @Test fun missingMicrophonePermissionUsesOnlyConnectedDeviceTypeThenStops() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        verifyRejectedStart()
        assertFalse(LegacyRuntimeOwnership.hasOwner())
    }

    @Test fun permissionLossOnExistingRuntimeStopsItEvenWhileServiceRemainsBound() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(*PermissionPolicy.corePermissions(Build.VERSION.SDK_INT).toTypedArray())
        verifyRejectedStart(active = true)
        assertFalse(LegacyRuntimeOwnership.hasOwner())
    }

    private fun verifyRejectedStart(active: Boolean = false) {
        val database = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        database.set(null, null)
        val controller = Robolectric.buildService(IntercomService::class.java).create()
        val service = controller.get()
        try {
            if (active) {
                field(service, "identityStore").set(service, object : LocalIdentityStore {
                    override suspend fun getOrCreateDeviceId(): String = CompletableDeferred<String>().await()
                    override suspend fun getNickname() = "C04"
                    override suspend fun updateNickname(value: String) = Unit
                })
                service.onStartCommand(IntercomService.startIntent(service), 0, 1)
                assertTrue(field(service, "running").getBoolean(service))
                shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.READ_PHONE_STATE)
            }
            service.onStartCommand(IntercomService.startIntent(service), 0, 42)
            val shadow = shadowOf(service)
            assertNotEquals("Rejected FGS must acknowledge the platform start obligation", 0, shadow.lastForegroundNotificationId)
            assertTrue(shadow.isForegroundStopped)
            assertTrue(shadow.isStoppedBySelf)
            assertEquals(42, shadow.stopSelfId)
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)
            }
            assertFalse(field(service, "running").getBoolean(service))
            assertNull(field(service, "audioSessionController").get(service))
            assertNull(field(service, "activeRuntimeSessionId").get(service))
            assertNull(field(service, "runtimeKeepAlive").get(service))
        } finally {
            val scope = field(service, "serviceScope").get(service) as CoroutineScope
            val completed = CountDownLatch(1)
            scope.coroutineContext[Job]?.invokeOnCompletion { completed.countDown() } ?: completed.countDown()
            controller.destroy()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            (database.get(null) as? PairingDatabase)?.close()
            database.set(null, null)
        }
    }

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
}
