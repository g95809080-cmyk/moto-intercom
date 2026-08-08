package com.kuma.motointercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiDirectTunnelIdentityRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = MonotonicClock { MonotonicTimestamp(1_000L) }

    @Test
    fun legacyServiceInstanceRemainsPendingAndIsNotPublished() {
        val published = mutableListOf<List<WifiDirectRiderDevice>>()
        val tunnel = tunnel(onPeersChanged = published::add)
        val device = device("AA:BB:CC:DD:EE:01")

        invokeServiceResponse(tunnel, "MotoCom-12345678", device)
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = peerRegistry(tunnel).snapshot()
        assertEquals(setOf(device.deviceAddress), snapshot.pending)
        assertTrue(snapshot.accepted.isEmpty())
        assertTrue(published.flatten().isEmpty())
    }

    @Test
    fun verifiedV2ServiceInstanceIsAcceptedAndPublished() {
        val published = mutableListOf<List<WifiDirectRiderDevice>>()
        val tunnel = tunnel(onPeersChanged = published::add)
        val device = device("AA:BB:CC:DD:EE:02")
        val instanceName = P2pServiceInstanceCodec.encode(REMOTE_DEVICE_ID, REMOTE_SESSION_ID)

        invokeServiceResponse(tunnel, instanceName, device)
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = peerRegistry(tunnel).snapshot()
        assertEquals(setOf(device.deviceAddress), snapshot.accepted)
        assertEquals(REMOTE_DEVICE_ID, published.single().single().identity.claimedDeviceId)
    }

    @Test
    fun txtRecordWithoutDeviceIdRemainsPendingAndIsNotPublished() {
        val published = mutableListOf<List<WifiDirectRiderDevice>>()
        val tunnel = tunnel(onPeersChanged = published::add)
        val device = device("AA:BB:CC:DD:EE:03")
        invokeServiceResponse(tunnel, "MotoCom-12345678", device)

        invokeTxtRecord(
            tunnel,
            mapOf(
                "appId" to "MotoCom",
                "protocolVersion" to "2",
                "sessionId" to REMOTE_SESSION_ID.value,
                "nickname" to "Rider B"
            ),
            device
        )
        shadowOf(Looper.getMainLooper()).idle()

        val snapshot = peerRegistry(tunnel).snapshot()
        assertEquals(setOf(device.deviceAddress), snapshot.pending)
        assertTrue(snapshot.accepted.isEmpty())
        assertTrue(published.flatten().isEmpty())
    }

    @Test
    fun newTargetedAttemptInvalidatesPendingRecoveryGeneration() {
        val tunnel = tunnel()
        setRunning(tunnel, true)
        val recovery = pendingRecovery(tunnel)
        val stale = checkNotNull(recovery.next(true, false, false))

        val connected = tunnel.connect(
            ConnectionAttemptFixture.create(
                clock = clock,
                preferredTransport = Transport.WIFI_DIRECT
            )
        )

        assertTrue(connected)
        assertFalse(recovery.isCurrent(stale))
    }

    @Test
    fun targetedAttemptSuppressesStaleDelayedRetryAndStartsFreshBoundedSequence() {
        val tunnel = tunnel()
        setRunning(tunnel, true)
        invokeServiceResponse(tunnel, "MotoCom-12345678", device("AA:BB:CC:DD:EE:04"))
        val recovery = pendingRecovery(tunnel)
        val staleGeneration = recovery.currentGeneration

        assertTrue(
            tunnel.connect(
                ConnectionAttemptFixture.create(
                    clock = clock,
                    preferredTransport = Transport.WIFI_DIRECT
                )
            )
        )
        assertEquals(staleGeneration + 1, recovery.currentGeneration)

        val mainLooper = shadowOf(Looper.getMainLooper())
        mainLooper.idleFor(Duration.ofMillis(1_500L))
        assertEquals(1, field<Int>(tunnel, "pendingRetryAttempt"))
        mainLooper.idleFor(Duration.ofMillis(4_500L))
        assertEquals(4, field<Int>(tunnel, "pendingRetryAttempt"))
        mainLooper.idleFor(Duration.ofMillis(3_000L))
        assertEquals(4, field<Int>(tunnel, "pendingRetryAttempt"))
        tunnel.close()
    }

    @Test
    fun stalePendingActionListenerCannotRunCallbacks() {
        val tunnel = tunnel()
        setRunning(tunnel, true)
        peerRegistry(tunnel).markPending("AA:BB:CC:DD:EE:05")
        val recovery = pendingRecovery(tunnel)
        val retry = checkNotNull(recovery.next(true, false, false))
        var successes = 0
        var failures = 0
        val listener = WifiDirectTunnel::class.java.declaredMethods
            .single { it.name == "pendingDiscoveryAction" }
            .apply { isAccessible = true }
            .invoke(
                tunnel,
                retry,
                "test callback",
                { successes++ },
                { _: Int -> failures++ }
            ) as WifiP2pManager.ActionListener

        listener.onSuccess()
        assertEquals(1, successes)
        recovery.invalidate()
        listener.onSuccess()
        listener.onFailure(WifiP2pManager.ERROR)

        assertEquals(1, successes)
        assertEquals(0, failures)
        tunnel.close()
    }

    @Test
    fun p2pDisableAndCloseInvalidatePendingRecoveryGeneration() {
        val tunnel = tunnel()
        val recovery = pendingRecovery(tunnel)
        val beforeDisable = checkNotNull(recovery.next(true, false, false))
        val receiver = field<BroadcastReceiver>(tunnel, "receiver")

        receiver.onReceive(
            context,
            Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).putExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED
            )
        )

        assertFalse(recovery.isCurrent(beforeDisable))
        val beforeClose = checkNotNull(recovery.next(true, false, false))
        tunnel.close()
        assertFalse(recovery.isCurrent(beforeClose))
    }

    private fun tunnel(
        onPeersChanged: (List<WifiDirectRiderDevice>) -> Unit = {}
    ) = WifiDirectTunnel(
        context = context,
        onControlChannelReady = {},
        localDeviceId = LOCAL_DEVICE_ID,
        localDeviceName = "Phone A",
        sessionId = LOCAL_SESSION_ID,
        onPeersChanged = onPeersChanged,
        monotonicClock = clock
    )

    private fun device(address: String) = WifiP2pDevice().apply {
        deviceAddress = address
        deviceName = "Phone B"
    }

    private fun invokeServiceResponse(
        tunnel: WifiDirectTunnel,
        instanceName: String,
        device: WifiP2pDevice
    ) {
        WifiDirectTunnel::class.java.getDeclaredMethod(
            "handleServiceResponse",
            String::class.java,
            String::class.java,
            WifiP2pDevice::class.java
        ).apply { isAccessible = true }
            .invoke(tunnel, instanceName, "_motocom._tcp.local.", device)
    }

    private fun invokeTxtRecord(
        tunnel: WifiDirectTunnel,
        record: Map<String, String>,
        device: WifiP2pDevice
    ) {
        WifiDirectTunnel::class.java.getDeclaredMethod(
            "handleTxtRecord",
            Map::class.java,
            WifiP2pDevice::class.java
        ).apply { isAccessible = true }
            .invoke(tunnel, record, device)
    }

    private fun peerRegistry(tunnel: WifiDirectTunnel): WifiDirectPeerRegistry =
        field(tunnel, "peerRegistry")

    private fun pendingRecovery(tunnel: WifiDirectTunnel): WifiDirectPendingDiscoveryRecovery =
        field(tunnel, "pendingDiscoveryRecovery")

    private fun setRunning(tunnel: WifiDirectTunnel, value: Boolean) {
        WifiDirectTunnel::class.java.getDeclaredField("running").apply {
            isAccessible = true
            setBoolean(tunnel, value)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(tunnel: WifiDirectTunnel, name: String): T =
        WifiDirectTunnel::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(tunnel) as T

    private companion object {
        const val LOCAL_DEVICE_ID = "a0000000-0000-4000-8000-000000000001"
        val LOCAL_SESSION_ID = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        const val REMOTE_DEVICE_ID = "b0000000-0000-4000-8000-000000000002"
        val REMOTE_SESSION_ID = RuntimeSessionId("20000000-0000-4000-8000-000000000002")
    }
}
