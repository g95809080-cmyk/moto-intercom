package com.kuma.motointercom

import android.Manifest
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CloudServiceLifecycleTest {
    @Test fun acceptingOnABoundOnlyServiceAlsoEntersTheStartedLifecycle() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(*PermissionPolicy.cloudPermissions().toTypedArray())
        val controller=Robolectric.buildService(IntercomService::class.java).create()
        val service=controller.get()
        try {
            val pending=IntercomService::class.java.getDeclaredField("cloudStartPending").apply { isAccessible=true }
            pending.setBoolean(service,true)
            val retain=IntercomService::class.java.getDeclaredMethod("retainCloudForeground").apply { isAccessible=true }
            assertEquals(true,retain.invoke(service))
            val start=shadowOf(app).nextStartedService
            assertNotNull(start); assertEquals(IntercomService::class.java.name,start.component!!.className)
            assertEquals("com.kuma.motointercom.action.KEEP_CLOUD_SERVICE",start.action)
            service.onStartCommand(start,0,17)
            assertFalse(shadowOf(service).isStoppedBySelf)
            assertNotEquals(0,shadowOf(service).lastForegroundNotificationId)
            pending.setBoolean(service,false)
            service.onStartCommand(start,0,18)
            assertTrue(shadowOf(service).isStoppedBySelf); assertTrue(shadowOf(service).isForegroundStopped)
        } finally {
            val scopeField=IntercomService::class.java.getDeclaredField("serviceScope").apply { isAccessible=true }
            val scope=scopeField.get(service) as CoroutineScope
            val finished=CountDownLatch(1)
            scope.coroutineContext[Job]?.invokeOnCompletion { finished.countDown() } ?: finished.countDown()
            controller.destroy()
            assertTrue(finished.await(5,TimeUnit.SECONDS))
            val database=PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible=true }
            (database.get(null) as? PairingDatabase)?.close(); database.set(null,null)
        }
    }
}
