package com.kuma.motointercom

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupServiceLifecycleTest {
    private fun field(service: IntercomService, name: String) = IntercomService::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Test fun restartedServiceWithoutExplicitActionHasNoRoomOrMicrophoneIntent() {
        val owner = Robolectric.buildService(IntercomService::class.java).create()
        val service = owner.get()
        try {
            service.onStartCommand(null, 0, 1)
            assertNull(field(service, "groupRuntime").get(service))
            assertNull(field(service, "groupStarting").get(service))
            assertFalse(field(service, "running").getBoolean(service))
        } finally { owner.destroy() }
    }
    @Test fun globalCleanupOwnershipBlocksLegacyEvenAfterServiceRecreation() {
        val token = GroupRuntimeOwnership.acquire()!!
        val owner = Robolectric.buildService(IntercomService::class.java).create()
        try {
            val service = owner.get()
            service.onStartCommand(IntercomService.startIntent(service), 0, 1)
            assertFalse(field(service, "running").getBoolean(service))
            assertNull(field(service, "audioSessionController").get(service))
            assertTrue(GroupRuntimeOwnership.hasOwner())
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { owner.destroy(); GroupRuntimeOwnership.release(token) }
    }
    @Test fun foreignCleanupOwnerRejectsGroupStartAndStopsOnlyNewService() {
        val token = GroupRuntimeOwnership.acquire()!!
        val owner = Robolectric.buildService(IntercomService::class.java).create()
        try {
            val service = owner.get()
            service.onStartCommand(Intent(service, IntercomService::class.java)
                .setAction(IntercomService.ACTION_START_GROUP), 0, 9)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNull(field(service, "groupRuntime").get(service))
            assertTrue(GroupRuntimeOwnership.hasOwner())
        } finally { owner.destroy(); GroupRuntimeOwnership.release(token) }
    }
    @Test fun legacyRunningAndNativeDisposalBothBlockGroupStart() {
        val owner = Robolectric.buildService(IntercomService::class.java).create()
        val service = owner.get()
        val intent = Intent(service, IntercomService::class.java).setAction(IntercomService.ACTION_START_GROUP)
        try {
            field(service, "running").setBoolean(service, true)
            service.onStartCommand(intent, 0, 1)
            assertNull(field(service, "groupStarting").get(service))
            field(service, "running").setBoolean(service, false)
            val native = AudioPlatformOwnership.acquire()
            try {
                service.onStartCommand(intent, 0, 2)
                assertNull(field(service, "groupRuntime").get(service))
                assertNull(field(service, "groupStarting").get(service))
            } finally { AudioPlatformOwnership.release(native) }
        } finally { owner.destroy() }
    }
    @Test fun cancellingPendingIdentityLoadRevokesItsStartToken() {
        val owner = Robolectric.buildService(IntercomService::class.java).create()
        try {
            val service = owner.get()
            field(service, "groupStarting").set(service, UUID.randomUUID())
            service.groupAction(GroupSessionEvent.Leave)
            assertNull(field(service, "groupStarting").get(service))
            assertNull(field(service, "groupRuntime").get(service))
        } finally { owner.destroy() }
    }
    @Test fun permissionCallbackStartsOnlyAfterResumeAndDestroyedPageCannotRestart() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).setThrowInBindService(SecurityException("test binder unavailable"))
        val owner = Robolectric.buildActivity(GroupActivity::class.java).setup()
        val activity = owner.get()
        activity.requestStart("123456")
        val request = shadowOf(activity).lastRequestedPermission
        owner.pause()
        shadowOf(app).grantPermissions(*request.requestedPermissions)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size))
        assertNull(shadowOf(activity).nextStartedService)
        owner.resume()
        assertEquals(IntercomService.ACTION_START_GROUP, shadowOf(activity).nextStartedService.action)
        owner.pause().stop().destroy()
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size))
        assertNull(shadowOf(activity).nextStartedService)
        val fresh = Robolectric.buildActivity(GroupActivity::class.java).create(Bundle()).start().resume()
        assertNull(shadowOf(fresh.get()).nextStartedService)
        fresh.pause().stop().destroy()
    }
}
