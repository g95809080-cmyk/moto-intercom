package com.kuma.motointercom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyRuntimeOwnershipServiceRobolectricTest {
    @Test fun destroyedServiceBlocksReplacementUntilWifiCleanupFinishes() = verifyOwnership(false)
    @Test fun alreadyPendingRecoveryCleanupStillOwnsTheProcessAfterDestroy() = verifyOwnership(true)

    private fun verifyOwnership(alreadyPending: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(*(PermissionPolicy.corePermissions(35) +
            PermissionPolicy.optionalPermissions(35)).toTypedArray())
        val a = Robolectric.buildService(IntercomService::class.java).create()
        val b = Robolectric.buildService(IntercomService::class.java).create()
        var c: ServiceController<IntercomService>? = null
        val done = mutableListOf<() -> Unit>()
        var destroyedA = false
        try {
            val serviceA = a.get()
            val blockedIdentity = object : LocalIdentityStore {
                override suspend fun getOrCreateDeviceId(): String = CompletableDeferred<String>().await()
                override suspend fun getNickname() = "fixture"
                override suspend fun updateNickname(value: String) = Unit
            }
            field(serviceA, "identityStore").set(serviceA, blockedIdentity)
            serviceA.onStartCommand(IntercomService.startIntent(serviceA), 0, 1)
            assertTrue(field(serviceA, "running").getBoolean(serviceA))
            val tokenA = requireNotNull(field(serviceA, "legacyOwnership").get(serviceA))
            val heldA = field(serviceA, "runtimeKeepAlive").get(serviceA) as IntercomRuntimeKeepAlive
            assertTrue(heldA.isHeld)
            val runtimeA = RuntimeSessionId(field(serviceA, "activeRuntimeSessionId").get(serviceA) as String)
            val tunnel = WifiDirectTunnel(serviceA, { _, _ -> },
                localDeviceId = UUID.randomUUID().toString(), localDeviceName = "fixture", sessionId = runtimeA)
            val pending = PendingCloseOwner<WifiDirectTunnel> { resource, complete ->
                assertSame(tunnel, resource)
                done += complete
            }
            field(serviceA, "wifiTunnelCloseOwner").set(serviceA, pending)
            if (alreadyPending) pending.close(tunnel) {}
            else field(serviceA, "wifiTunnel").set(serviceA, tunnel)
            serviceA.onStartCommand(IntercomService.stopIntent(serviceA), 0, 2)
            assertTrue(pending.hasPending)
            assertTrue(heldA.isHeld)
            assertTrue(LegacyRuntimeOwnership.hasOwner())
            destroyOnly(a)
            destroyedA = true
            assertEquals(1, done.size)
            val serviceB = b.get()
            serviceB.onStartCommand(IntercomService.startIntent(serviceB), 0, 3)
            assertFalse(field(serviceB, "running").getBoolean(serviceB))
            assertNull(field(serviceB, "activeRuntimeSessionId").get(serviceB))
            assertNull(field(serviceB, "runtimeKeepAlive").get(serviceB))
            assertNull(field(serviceB, "audioSessionController").get(serviceB))
            assertTrue(shadowOf(serviceB).isStoppedBySelf)
            assertTrue(LegacyRuntimeOwnership.hasOwner())
            done.single().invoke()
            assertFalse(pending.hasPending)
            assertFalse(heldA.isHeld)
            assertFalse(LegacyRuntimeOwnership.hasOwner())
            c = Robolectric.buildService(IntercomService::class.java).create()
            val serviceC = c.get()
            field(serviceC, "identityStore").set(serviceC, blockedIdentity)
            serviceC.onStartCommand(IntercomService.startIntent(serviceC), 0, 4)
            assertTrue(field(serviceC, "running").getBoolean(serviceC))
            val tokenC = requireNotNull(field(serviceC, "legacyOwnership").get(serviceC))
            assertNotSame(tokenA, tokenC)
            val heldC = field(serviceC, "runtimeKeepAlive").get(serviceC) as IntercomRuntimeKeepAlive
            done.single().invoke()
            LegacyRuntimeOwnership.release(tokenA)
            assertTrue(LegacyRuntimeOwnership.hasOwner())
            assertTrue(heldC.isHeld)
            assertTrue(field(serviceC, "running").getBoolean(serviceC))
        } finally {
            done.forEach { it() }
            c?.let(::destroyOnly)
            destroyOnly(b)
            if (!destroyedA) destroyOnly(a)
            val database = PairingDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
            (database.get(null) as? PairingDatabase)?.close()
            database.set(null, null)
        }
    }

    private fun field(service: IntercomService, name: String) =
        service.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun destroyOnly(controller: ServiceController<IntercomService>) {
        val scope = field(controller.get(), "serviceScope").get(controller.get()) as CoroutineScope
        val complete = CountDownLatch(1)
        scope.coroutineContext[Job]?.invokeOnCompletion { complete.countDown() } ?: complete.countDown()
        controller.destroy()
        assertTrue("Service scope did not stop", complete.await(5L, TimeUnit.SECONDS))
    }
}
