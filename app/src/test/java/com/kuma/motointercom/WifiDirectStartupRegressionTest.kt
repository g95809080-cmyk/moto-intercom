package com.kuma.motointercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], shadows = [RecordingP2pInputShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class WifiDirectStartupRegressionTest {
    @Test fun startupQuerySurvivesIdleDisconnectedBroadcast() {
        Harness().use { h ->
            h.startStartup()
            val obsoleteStartup = h.p2p.groups.removeFirst()
            h.confirmIdleDisconnect()
            obsoleteStartup.onGroupInfoAvailable(null)
            h.idle(3)
            assertTrue("Idle loss must restart the invalidated initialization", h.ready())
            assertEquals(1, h.p2p.dns.size)
        }
    }

    @Test fun queuedRegistrationSurvivesConfirmedIdleDisconnect() {
        Harness().use { h ->
            h.call("setupServiceDiscovery")
            h.confirmIdleDisconnect()
            h.idle(3)
            assertTrue("Revoked setup callbacks need a replacement setup", h.ready())
        }
    }

    @Test fun cleanStartupStillRegistersDiscoveryOnce() {
        Harness().use { h ->
            h.startStartup()
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            h.idle(3)
            assertTrue(h.ready())
            assertEquals(1, h.p2p.dns.size)
        }
    }

    @Test fun closedStartupCannotPublishLate() {
        Harness().use { h ->
            h.startStartup()
            val obsoleteStartup = h.p2p.groups.removeFirst()
            h.tunnel.close()
            h.idle(3)
            obsoleteStartup.onGroupInfoAvailable(null)
            h.idle(3)
            assertFalse(h.ready())
        }
    }

    @Test fun missingPassiveCancelAckIsBoundedAndLateAckCannotRestartSetup() {
        Harness().use { h ->
            h.p2p.holdCancelConnect = true
            h.startStartup()
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            val obsoleteCancel = h.p2p.cancellations.single()
            assertTrue(h.removing())
            h.idle(6)
            assertFalse("Passive cancel must have an execution timeout", h.removing())
            assertTrue(h.ready())
            val registrations = h.p2p.dns.size
            obsoleteCancel.onSuccess()
            obsoleteCancel.onFailure(WifiP2pManager.ERROR)
            h.idle(2)
            assertEquals(registrations, h.p2p.dns.size)
            assertTrue(h.ready())
        }
    }

    @Test fun missingPassiveRemovalAckRechecksGroupAndRejectsLateAck() {
        Harness().use { h ->
            h.p2p.holdGroupRemoval = true
            h.startStartup()
            h.p2p.groups.removeFirst().onGroupInfoAvailable(WifiP2pGroup())
            val obsoleteRemoval = h.p2p.removals.single()
            assertTrue(h.removing())
            h.idle(6)
            assertFalse("Passive group removal must have an execution timeout", h.removing())
            assertFalse("A timeout is not proof that the native group was removed", h.ready())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            h.idle(3)
            assertTrue(h.ready())
            val registrations = h.p2p.dns.size
            obsoleteRemoval.onSuccess()
            obsoleteRemoval.onFailure(WifiP2pManager.ERROR)
            h.idle(2)
            assertEquals(registrations, h.p2p.dns.size)
            assertTrue(h.ready())
        }
    }

    private class Harness : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val tunnel = WifiDirectTunnel(context,
            onControlChannelReady = { _, lease -> lease.close() },
            localDeviceId = "90000000-0000-4000-8000-000000000001",
            localNickname = "骑士", localDeviceName = "regression",
            sessionId = RuntimeSessionId("90000000-0000-4000-8000-000000000002"))
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
        fun removing() = field("removingGroup") as Boolean
        fun confirmIdleDisconnect() {
            (field("receiver") as BroadcastReceiver).onReceive(context,
                Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
            replyNoGroup()
        }
        private fun replyNoGroup() {
            p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
            p2p.groups.removeFirst().onGroupInfoAvailable(null)
        }
        fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        private fun field(name: String): Any? = WifiDirectTunnel::class.java.getDeclaredField(name).run {
            isAccessible = true; get(tunnel)
        }
        private fun set(name: String, value: Any) = WifiDirectTunnel::class.java.getDeclaredField(name).run {
            isAccessible = true; set(tunnel, value)
        }
        fun call(name: String) = WifiDirectTunnel::class.java.getDeclaredMethod(name).run {
            isAccessible = true; invoke(tunnel)
        }
        override fun close() { tunnel.close(); shadowOf(Looper.getMainLooper()).idle() }
    }
}
