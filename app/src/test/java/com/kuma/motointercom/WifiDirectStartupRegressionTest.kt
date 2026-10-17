package com.kuma.motointercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingP2pInputShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiDirectStartupRegressionTest {
    @Test fun disconnectedBroadcastPreservesOutstandingStartupQuery() {
        Harness().use { h ->
            h.startStartup()
            val startup = h.p2p.groups.removeFirst()
            h.disconnectBroadcast()
            assertTrue("An idle broadcast must not replace the startup query", h.p2p.infos.isEmpty())
            startup.onGroupInfoAvailable(null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue(h.ready())
            assertEquals(1, h.p2p.dns.size)
            assertEquals(0, h.disconnections)
        }
    }

    @Test fun disconnectedBroadcastPreservesQueuedServiceRegistration() {
        Harness().use { h ->
            h.call("setupServiceDiscovery")
            assertEquals(1, h.p2p.dns.size)
            h.disconnectBroadcast()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue("Queued registration must still complete", h.ready())
            assertEquals(0, h.disconnections)
            assertTrue(h.p2p.infos.isEmpty())
        }
    }

    @Test fun closedStartupQueryCannotPublishAfterClose() {
        Harness().use { h ->
            h.startStartup()
            val startup = h.p2p.groups.removeFirst()
            h.tunnel.close()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            startup.onGroupInfoAvailable(null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(h.ready())
            assertTrue(h.p2p.dns.isEmpty())
            assertFalse(h.field("running") as Boolean)
        }
    }

    @Test fun targetedAttemptStillConfirmsActualGroupLoss() {
        Harness().use { h ->
            h.startStartup()
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertTrue(h.ready())
            val clock = MonotonicClock { MonotonicTimestamp(android.os.SystemClock.elapsedRealtime()) }
            assertTrue(h.tunnel.connect(ConnectionAttemptFixture.create(
                clock = clock, runtimeSessionId = h.runtime, preferredTransport = Transport.WIFI_DIRECT)))
            h.disconnectBroadcast()
            assertEquals(1, h.p2p.infos.size)
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            assertEquals(1, h.disconnections)
            assertEquals(1, h.targetedFailures)
        }
    }

    private class Harness : AutoCloseable {
        val runtime = RuntimeSessionId("90000000-0000-4000-8000-000000000002")
        private val context = ApplicationProvider.getApplicationContext<Context>()
        var disconnections = 0
        var targetedFailures = 0
        val tunnel = WifiDirectTunnel(context = context,
            onControlChannelReady = { _, lease -> lease.close() },
            localDeviceId = "90000000-0000-4000-8000-000000000001", localDeviceName = "regression",
            sessionId = runtime, onDisconnected = { disconnections++ },
            onTargetedTransportFailed = { _, _ -> targetedFailures++ })
        val p2p: RecordingP2pInputShadow
        init {
            set("running", true)
            call("initP2p")
            p2p = Shadow.extract(field("manager") as WifiP2pManager)
            p2p.dns.clear(); p2p.groups.clear(); p2p.infos.clear()
            p2p.holdGroupRemoval = false; p2p.holdCancelConnect = false
            (field("setupRecoveryGate") as WifiDirectSetupRecoveryGate).updateP2pEnabled(true)
        }
        fun startStartup() { set("startupDiscoveryPending", true); call("startStartupDiscoveryIfReady") }
        fun ready() = field("serviceDiscoveryReady") as Boolean
        fun disconnectBroadcast() = (field("receiver") as BroadcastReceiver).onReceive(context,
            Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        fun field(name: String): Any? = WifiDirectTunnel::class.java.getDeclaredField(name).run { isAccessible = true; get(tunnel) }
        private fun set(name: String, value: Any) = WifiDirectTunnel::class.java.getDeclaredField(name).run { isAccessible = true; set(tunnel, value) }
        fun call(name: String) = WifiDirectTunnel::class.java.getDeclaredMethod(name).run { isAccessible = true; invoke(tunnel) }
        override fun close() { tunnel.close(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
