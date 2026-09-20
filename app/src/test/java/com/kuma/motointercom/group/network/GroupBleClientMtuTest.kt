package com.kuma.motointercom.group.network
import android.app.Application
import android.bluetooth.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupBleClientMtuTest {
    @Test fun negotiationFallbackAndLateCallbacksCanOnlyPublishReadyOnce() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("00:11:22:33:44:55")
        var ready = 0
        val client = GroupBleClient(context, GroupBleCandidate(device, -20), { ready++ }, {})
        val complete = GroupBleClient::class.java.getDeclaredMethod("completeReady", Int::class.javaPrimitiveType).apply { isAccessible = true }
        complete.invoke(client, 23)
        complete.invoke(client, 517)
        assertEquals(1, ready)
        assertEquals(20, ReflectionHelpers.getField<Int>(client, "packetBytes"))
        client.close(); complete.invoke(client, 247)
        assertEquals(1, ready)
    }
}
