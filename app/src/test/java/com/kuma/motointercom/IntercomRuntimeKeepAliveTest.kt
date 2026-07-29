package com.kuma.motointercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IntercomRuntimeKeepAliveTest {
    @Test
    fun holdsCpuAndWifiUntilClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keepAlive = IntercomRuntimeKeepAlive.acquire(context)

        assertTrue(keepAlive.isHeld)

        keepAlive.close()
        keepAlive.close()

        assertFalse(keepAlive.isHeld)
    }

    @Test
    fun keepsLocksHeldUntilPendingCloseCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val keepAlive = IntercomRuntimeKeepAlive.acquire(context)
        val callbacks = mutableListOf<() -> Unit>()
        val owner = PendingCloseOwner<Any> { _, complete -> callbacks += complete }
        var releases = 0

        owner.close(Any()) {}
        owner.closeAll(emptyList()) {
            keepAlive.close()
            releases++
        }

        assertTrue(keepAlive.isHeld)
        assertEquals(0, releases)

        callbacks.single().invoke()

        assertFalse(keepAlive.isHeld)
        assertEquals(1, releases)
    }
}
