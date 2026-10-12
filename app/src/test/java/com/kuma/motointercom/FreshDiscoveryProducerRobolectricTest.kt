package com.kuma.motointercom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNsdManager
import org.robolectric.shadows.ShadowWifiP2pManager
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking

@Implements(NsdManager::class)
class RecordingNsdInputShadow : ShadowNsdManager() {
    data class Resolution(val info: NsdServiceInfo, val listener: NsdManager.ResolveListener)
    val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    val resolutions = mutableListOf<Resolution>()
    @Implementation fun __constructor__(context: Context, @ClassName("android.net.nsd.INsdManager") service: Any) = Unit
    @Implementation fun registerService(info: NsdServiceInfo, protocol: Int, listener: NsdManager.RegistrationListener) {
        Handler(Looper.getMainLooper()).post { listener.onServiceRegistered(info) }
    }
    @Implementation fun discoverServices(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
        discoveries += listener
    }
    @Implementation fun resolveService(info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        resolutions += Resolution(info, listener)
    }
    @Implementation fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) = Unit
    @Implementation fun unregisterService(listener: NsdManager.RegistrationListener) = Unit
}

@Implements(WifiP2pManager::class)
class RecordingP2pInputShadow : ShadowWifiP2pManager() {
    data class DnsListeners(val service: WifiP2pManager.DnsSdServiceResponseListener, val txt: WifiP2pManager.DnsSdTxtRecordListener)
    val dns = mutableListOf<DnsListeners>()
    val infos = ArrayDeque<WifiP2pManager.ConnectionInfoListener>()
    val groups = ArrayDeque<WifiP2pManager.GroupInfoListener>()
    val peers = ArrayDeque<WifiP2pManager.PeerListListener>()
    var infoRequests = 0
    var holdGroupRemoval = false
    var holdCancelConnect = false
    val cancellations = mutableListOf<WifiP2pManager.ActionListener>()
    val removals = mutableListOf<WifiP2pManager.ActionListener>()
    val connections = mutableListOf<String>()
    var throwNextGroupQuery = false
    private fun success(listener: WifiP2pManager.ActionListener?) { Handler(Looper.getMainLooper()).post { listener?.onSuccess() } }
    @Implementation fun setDnsSdResponseListeners(channel: WifiP2pManager.Channel,
        service: WifiP2pManager.DnsSdServiceResponseListener, txt: WifiP2pManager.DnsSdTxtRecordListener) { dns += DnsListeners(service, txt) }
    @Implementation fun clearLocalServices(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) = success(listener)
    @Implementation fun addLocalService(channel: WifiP2pManager.Channel, service: WifiP2pServiceInfo, listener: WifiP2pManager.ActionListener?) = success(listener)
    @Implementation fun clearServiceRequests(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) = success(listener)
    @Implementation fun addServiceRequest(channel: WifiP2pManager.Channel, request: WifiP2pServiceRequest, listener: WifiP2pManager.ActionListener?) = success(listener)
    @Implementation fun discoverServices(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) = success(listener)
    @Implementation fun requestConnectionInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ConnectionInfoListener) { infoRequests++; infos += listener }
    @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.GroupInfoListener) {
        if (throwNextGroupQuery) { throwNextGroupQuery = false; throw IllegalStateException("fixture SDK group query failure") }
        groups += listener
    }
    @Implementation fun requestPeers(channel: WifiP2pManager.Channel, listener: WifiP2pManager.PeerListListener) { peers += listener }
    @Implementation fun cancelConnect(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener?) {
        if (holdCancelConnect && listener != null) cancellations += listener else success(listener)
    }
    @Implementation override fun removeGroup(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
        if (holdGroupRemoval) removals += listener else success(listener)
    }
    @Implementation fun connect(channel: WifiP2pManager.Channel, config: android.net.wifi.p2p.WifiP2pConfig,
        listener: WifiP2pManager.ActionListener?) { connections += config.deviceAddress; success(listener) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingNsdInputShadow::class, RecordingP2pInputShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class FreshDiscoveryProducerRobolectricTest {
    @Test fun actualDualOriginHelloGlareAdoptsServiceMediaWinnerAndRevokesOldNativeTarget() = runBlocking {
        val f = IncomingConfirmationServiceFixture("c0000000-0000-4000-8000-000000000003")
        var harness: WifiHarness? = null
        val acceptEntered = CountDownLatch(1)
        val releaseAccept = CountDownLatch(1)
        val pauseAccept = AtomicBoolean(true)
        try {
            val token = f.activateRuntime(LOCAL_RUNTIME)
            setField(field(f.actor, "signalingControl")!!, "clock", MonotonicClock {
                if (f.actor.activeControlAttempt?.phase == SignalingAttemptPhase.ACCEPTING && pauseAccept.compareAndSet(true, false)) {
                    acceptEntered.countDown(); check(releaseAccept.await(10, TimeUnit.SECONDS))
                }
                MonotonicTimestamp(android.os.SystemClock.elapsedRealtime())
            })
            val serviceLogs = CopyOnWriteArrayList<String>()
            f.service.setListener(object : IntercomService.Listener {
                override fun onStatusChanged(status: String, running: Boolean) { serviceLogs += status }
                override fun onLog(message: String) { serviceLogs += message }
                override fun onError(message: String) { serviceLogs += "ERROR $message" }
            })
            val engine = TransientLossServiceRobolectricTest.RecordingEngine()
            setField(f.service, "audioSessionController", AudioSessionController(engine,
                TransientLossServiceRobolectricTest.RecordingRoute()))
            val h = WifiHarness(withServer = true, localId = f.localDeviceId, requestRecovery = true,
                onReady = { session, lease -> f.register(token, session, lease) },
                captureLoss = { attempt -> f.actor.captureConnectionLossObservation().takeIf { it.sourceAttempt == attempt } },
                onSelectedLost = { attempt, loss -> invokeServiceEntry(f.service, "onSelectedWifiDirectGroupLost", token.value, attempt, loss) })
            harness = h
            setField(f.service, "wifiTunnel", h.tunnel)
            h.time.set(android.os.SystemClock.elapsedRealtime())
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val origin = h.attempt("30000000-0000-4000-8000-000000000030").copy(trigger = ConnectionTrigger.USER)
            assertTrue(f.actor.dispatchAndAwait(SessionEvent.ConnectRequested(origin)))
            assertTrue(h.tunnel.connect(origin))
            f.awaitMain { h.p2p.infos.isNotEmpty() }
            val oldInfo = h.p2p.infos.removeFirst()
            oldInfo.onConnectionInfoAvailable(h.validInfo())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(h.validGroup())
            f.awaitMain { acceptEntered.count == 0L && (field(f.service, "pendingMediaMessages") as Map<*, *>).isNotEmpty() }
            assertTrue("Actual Offer must wait for the actor's send commit", engine.sessions.isEmpty())
            assertFalse(h.installed.single().first.isClosed)
            assertNull(field(f.service, "intercomManager"))
            releaseAccept.countDown()
            try { f.awaitMain { engine.sessions.size == 1 } }
            catch (failure: Throwable) { throw AssertionError("actor=${f.actor.state.value} errors=${h.errors} installed=${h.installed.map { it.first.requestRole to it.first.phase }} logs=$serviceLogs", failure) }
            val winner = requireNotNull(f.actor.currentAttempt)
            assertNotEquals(origin.id, winner.id)
            assertEquals(origin.deadlineElapsedRealtimeMs, winner.deadlineElapsedRealtimeMs)
            val selected = h.installed.single().first
            assertEquals(RequestRole.RESPONDER, selected.requestRole)
            assertEquals(origin, selected.originatingAttempt)
            assertEquals(winner, field(h.tunnel, "selectedAttempt"))
            assertNull(field(h.tunnel, "targetAttempt"))
            engine.sessions.single().emit(org.webrtc.PeerConnection.PeerConnectionState.CONNECTED)
            f.awaitMain { f.actor.state.value is IntercomState.Connected }
            oldInfo.onConnectionInfoAvailable(WifiP2pInfo())
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(selected.isClosed)
            assertEquals(winner, field(h.tunnel, "selectedAttempt"))
            h.time.set(android.os.SystemClock.elapsedRealtime())
            val lossAt = h.time.get()
            h.disconnectBroadcast()
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            f.awaitMain { f.actor.state.value is IntercomState.Recovering && selected.isClosed && engine.sessions.single().closed }
            val recovery = f.actor.state.value as IntercomState.Recovering
            assertEquals(winner.targetLock, recovery.attempt.targetLock)
            assertEquals(lossAt + 10_000L, recovery.attempt.deadlineElapsedRealtimeMs)
            assertTrue(selected.isClosed)
            assertTrue(engine.sessions.single().closed)
        } finally { releaseAccept.countDown(); try { f.close() } finally { harness?.close() } }
    }

    @Test fun asynchronousSecondSdkQueryFailureDoesNotRevokeSelectedSocket() {
        WifiHarness(withServer = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val a = h.attempt("30000000-0000-4000-8000-000000000026")
            val selected = h.connectThroughPlatformCallbacks(a, 1).first
            h.tunnel.retainSelectedChannel(a)
            h.disconnectBroadcast()
            h.p2p.throwNextGroupQuery = true
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertFalse(selected.isClosed)
            assertTrue(h.failed.isEmpty())
            assertEquals(1, h.errors.size)
            h.disconnectBroadcast()
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(h.validInfo())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(h.validGroup())
            assertFalse(selected.isClosed)
        }
    }

    @Test fun cancelConnectAcknowledgmentResumesReplacementExactlyOnceForSuccessAndFailure() {
        for (success in listOf(true, false)) WifiHarness().use { h ->
            h.p2p.holdCancelConnect = true
            invoke(h.tunnel, "clearUntrustedGroupBeforeDiscovery")
            h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            val old = h.p2p.cancellations.single()
            val b = h.attempt("30000000-0000-4000-8000-000000000021")
            assertTrue(h.tunnel.connect(b))
            h.peer.status = WifiP2pDevice.AVAILABLE
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.p2p.connections.isEmpty())
            if (success) old.onSuccess() else old.onFailure(WifiP2pManager.ERROR)
            if (!success) {
                h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
                h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
                h.p2p.groups.removeFirst().onGroupInfoAvailable(null)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(h.peer.deviceAddress), h.p2p.connections)
            assertEquals(b, field(h.tunnel, "targetAttempt"))
            assertTrue(h.failed.isEmpty())
            old.onFailure(WifiP2pManager.BUSY); old.onSuccess()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, h.p2p.connections.size)
            h.p2p.holdCancelConnect = false
        }
    }

    @Test fun exactOldPassiveReleaseCannotRemoveReplacementSelectedRealSocket() {
        WifiHarness(withServer = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val a = h.attempt("30000000-0000-4000-8000-000000000022")
            val old = h.connectThroughPlatformCallbacks(a, 1).first
            h.tunnel.retainSelectedChannel(a)
            val b = h.attempt("30000000-0000-4000-8000-000000000023")
            val selected = h.connectThroughPlatformCallbacks(b, 2).first
            h.tunnel.retainSelectedChannel(b)
            old.close()
            h.p2p.holdGroupRemoval = true
            h.tunnel.retainPassiveIngress(a)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(old.isClosed)
            assertFalse(selected.isClosed)
            assertEquals(b, field(h.tunnel, "selectedAttempt"))
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertTrue(h.p2p.removals.isEmpty())
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun missingNativeCleanupAckFailsOnlyReplacementBranchWithoutIssuingConnect() {
        WifiHarness(withServer = true, rejectHello = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            h.p2p.holdGroupRemoval = true
            setState(h.tunnel, "GROUP_READY")
            h.openGroupThroughPlatformCallbacks(h.attempt("30000000-0000-4000-8000-000000000024"))
            awaitIo { h.p2p.removals.isNotEmpty() }
            h.time.addAndGet(4_000L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            val b = h.attempt("30000000-0000-4000-8000-000000000025")
            assertTrue(h.tunnel.connect(b))
            h.peer.status = WifiP2pDevice.AVAILABLE
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            h.time.addAndGet(1_000L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals(1, h.failed.count { it.first == b })
            assertTrue(field(h.tunnel, "removingGroup") as Boolean)
            assertTrue(h.p2p.connections.isEmpty())
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun oldStartupGroupQueryCannotRemoveNewSelectedRealSocket() {
        WifiHarness(withServer = true).use { h ->
            invoke(h.tunnel, "clearUntrustedGroupBeforeDiscovery")
            val startup = h.p2p.groups.removeFirst()
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val attempt = h.attempt("30000000-0000-4000-8000-000000000014")
            val selected = h.connectThroughPlatformCallbacks(attempt, 1).first
            h.tunnel.retainSelectedChannel(attempt)
            h.p2p.holdGroupRemoval = true
            startup.onGroupInfoAvailable(h.validGroup())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Startup cleanup is no longer authorized after selected admission", h.p2p.removals.isEmpty())
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertEquals(attempt, field(h.tunnel, "selectedAttempt"))
            assertFalse(selected.isClosed)
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun delayedNegativeGroupQueryCannotRevokeNewHealthyConnectionInfo() {
        WifiHarness(withServer = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val attempt = h.attempt("30000000-0000-4000-8000-000000000013")
            val selected = h.connectThroughPlatformCallbacks(attempt, 1).first
            h.tunnel.retainSelectedChannel(attempt)
            h.disconnectBroadcast()
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(WifiP2pInfo())
            val negative = h.p2p.groups.removeFirst()
            h.connectedBroadcast()
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(h.validInfo())
            val generation = field(h.tunnel, "socketTransportGeneration")
            negative.onGroupInfoAvailable(null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertEquals(generation, field(h.tunnel, "socketTransportGeneration"))
            assertEquals(attempt, field(h.tunnel, "selectedAttempt"))
            assertFalse(selected.isClosed)
        }
    }

    @Test fun emptyDisconnectBroadcastQueriesNativeGroupBeforeTouchingSelectedSocket() {
        WifiHarness(withServer = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY")
            val attempt = h.attempt("30000000-0000-4000-8000-000000000011")
            val selected = h.connectThroughPlatformCallbacks(attempt, 1).first
            h.tunnel.retainSelectedChannel(attempt)
            val generation = field(h.tunnel, "socketTransportGeneration")
            h.disconnectBroadcast()
            assertEquals("Missing NetworkInfo is not proof of radio loss", "SIGNALING_READY", state(h.tunnel))
            assertEquals(generation, field(h.tunnel, "socketTransportGeneration"))
            assertFalse(selected.isClosed)
            assertTrue(h.p2p.infos.isNotEmpty())
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(h.validInfo())
            assertTrue(h.p2p.groups.isNotEmpty())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(h.validGroup())
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertFalse(selected.isClosed)
        }
    }

    @Test fun transientRealSocketEofRetriesOnlyAfterRevalidatingCurrentGroup() {
        WifiHarness(withServer = true, eofFirstHello = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            h.p2p.holdGroupRemoval = true
            setState(h.tunnel, "GROUP_READY")
            val attempt = h.attempt("30000000-0000-4000-8000-000000000012")
            h.openGroupThroughPlatformCallbacks(attempt)
            awaitIo { h.errors.isNotEmpty() }
            assertTrue("EOF must retain group: ${h.errors}", h.p2p.removals.isEmpty())
            h.time.addAndGet(500L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500L))
            assertTrue("Retry must obtain new connection info", h.p2p.infos.isNotEmpty())
            h.p2p.infos.removeFirst().onConnectionInfoAvailable(h.validInfo())
            assertTrue("Retry must validate group membership again", h.p2p.groups.isNotEmpty())
            h.p2p.groups.removeFirst().onGroupInfoAvailable(h.validGroup())
            awaitIo { h.installed.size == 1 }
            assertEquals(attempt, h.installed.single().first.originatingAttempt)
            assertEquals(attempt.deadlineElapsedRealtimeMs, h.installed.single().first.originatingAttempt!!.deadlineElapsedRealtimeMs)
            assertTrue(h.p2p.removals.isEmpty())
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun actualEofBudgetCannotBeRefilledBySameAttemptReentryAndPreparedReplacementWaitsForAck() {
        WifiHarness(withServer = true, eofHelloCount = 3).use { h ->
            val dns = h.p2p.dns.last().txt
            dns.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            h.p2p.holdGroupRemoval = true
            setState(h.tunnel, "GROUP_READY")
            val a = h.attempt("30000000-0000-4000-8000-000000000033")
            for (n in 1..3) {
                h.openGroupThroughPlatformCallbacks(a)
                awaitIo { h.errors.size >= n }
                assertEquals(minOf(n, 2), field(h.tunnel, "socketRetryCount"))
            }
            awaitIo { h.failed.size == 1 && h.p2p.removals.size == 1 }
            assertEquals(a, h.failed.single().first)
            assertEquals(a.deadlineElapsedRealtimeMs, h.failed.single().first.deadlineElapsedRealtimeMs)
            val oldRemoval = h.p2p.removals.single()
            val b = h.attempt("30000000-0000-4000-8000-000000000034")
            assertTrue(h.tunnel.prepareRetry(b))
            assertEquals(0, field(h.tunnel, "socketRetryCount"))
            h.peer.status = WifiP2pDevice.AVAILABLE
            dns.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(h.p2p.connections.isEmpty())
            oldRemoval.onSuccess()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Prepared retry cannot issue native work before its resume", h.p2p.connections.isEmpty())
            assertTrue(h.tunnel.connect(b))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(h.peer.deviceAddress), h.p2p.connections)
            assertEquals(b, field(h.tunnel, "targetAttempt"))
            assertEquals(0, field(h.tunnel, "socketRetryCount"))
            oldRemoval.onSuccess()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, h.p2p.connections.size)
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun oldBusyRemovalRetryCannotIssueAnotherRemoveAgainstReplacement() {
        WifiHarness(withServer = true, rejectHello = true).use { h ->
            val dns = h.p2p.dns.last().txt
            dns.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            h.p2p.holdGroupRemoval = true
            setState(h.tunnel, "GROUP_READY")
            h.openGroupThroughPlatformCallbacks(h.attempt("30000000-0000-4000-8000-000000000035"))
            awaitIo { h.p2p.removals.size == 1 }
            h.p2p.removals.single().onFailure(WifiP2pManager.BUSY)
            shadowOf(Looper.getMainLooper()).idle()
            val b = h.attempt("30000000-0000-4000-8000-000000000036")
            assertTrue(h.tunnel.connect(b))
            h.time.addAndGet(1_000L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertEquals("BUSY retry of A cannot remove B's group", 1, h.p2p.removals.size)
            assertEquals(b, field(h.tunnel, "targetAttempt"))
            assertEquals("GROUP_READY", state(h.tunnel))
            assertFalse(field(h.tunnel, "removingGroup") as Boolean)
            assertTrue("Replacement must freshly query the native group", h.p2p.infos.isNotEmpty())
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun actualHelloFailureRemovalBlocksReplacementPlatformConnectUntilAcknowledged() {
        WifiHarness(withServer = true, rejectHello = true).use { h ->
            val dns = h.p2p.dns.last().txt
            dns.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            h.p2p.holdGroupRemoval = true
            setState(h.tunnel, "GROUP_READY") // Existing native group; HELLO below must still verify.
            h.openGroupThroughPlatformCallbacks(h.attempt("30000000-0000-4000-8000-000000000001"))
            awaitIo { h.p2p.removals.isNotEmpty() }
            assertTrue("Invalid real Socket HELLO must not be admitted", h.installed.isEmpty())
            assertTrue(field(h.tunnel, "removingGroup") as Boolean)
            assertEquals(0, h.p2p.connections.size)
            val removal = h.p2p.removals.single()
            val b = h.attempt("30000000-0000-4000-8000-000000000002")
            assertTrue(h.tunnel.connect(b))
            h.peer.status = WifiP2pDevice.AVAILABLE
            h.time.incrementAndGet()
            dns.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("An old native removeGroup may still tear down this replacement", 0, h.p2p.connections.size)
            val fresh = h.observed.last()
            assertTrue(h.tunnel.observationSource.isCurrent(fresh))
            removal.onSuccess()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(h.peer.deviceAddress), h.p2p.connections)
            assertEquals(b, field(h.tunnel, "targetAttempt"))
            assertTrue("Old cleanup must not clear B's discovery receipt", h.tunnel.observationSource.isCurrent(fresh))
            removal.onSuccess()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, h.p2p.connections.size)
            h.p2p.holdGroupRemoval = false
        }
    }

    @Test fun delayedLanSnapshotCannotRetireNewTxtRuntime() = runBlocking {
        val f = IncomingConfirmationServiceFixture()
        val ownership = requireNotNull(LegacyRuntimeOwnership.acquire())
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        var oldResolve: CompletableFuture<Void>? = null
        try {
            setField(f.service, "legacyOwnership", ownership)
            val token = f.activateRuntime(LOCAL_RUNTIME)
            setField(f.service, "localDeviceId", LOCAL_DEVICE)
            val generations = field(f.service, "sessions") as SessionGeneration
            fun cached(transport: Transport, candidates: List<DiscoveryCandidate>) =
                invokeServiceEntry(f.service, "onDiscoveryCandidatesChanged", token.value, LOCAL_RUNTIME.value, transport, candidates)
            fun fresh(observation: FreshDiscoveryObservation) =
                invokeServiceEntry(f.service, "onFreshDiscoveryObservation", token.value, LOCAL_RUNTIME.value, observation)
            fun assertCurrent(runtime: RuntimeSessionId) {
                val presences = (field(f.service, "presenceAggregator") as PresenceAggregator).snapshot().presences.filter { it.deviceId == REMOTE_DEVICE }
                assertEquals(1, presences.size); assertEquals(runtime, presences.single().sessionId); assertTrue(presences.single().isSelectable)
            }
            @Suppress("UNCHECKED_CAST") fun latest(): FreshDiscoveryObservation {
                val admission = requireNotNull(field(f.service, "discoveryPresenceAdmission"))
                return requireNotNull((field(admission, "latest") as Map<String, FreshDiscoveryObservation>)[REMOTE_DEVICE])
            }
            LanHarness(activeToken = token, isCurrentToken = generations::isCurrent,
                afterLog = { message ->
                    if (message.startsWith("发现局域网车友：") && pauseOnce.compareAndSet(true, false)) {
                        paused.countDown(); check(release.await(3, TimeUnit.SECONDS)) { "LAN publication was not released" }
                    }
                }, onSnapshot = { devices -> cached(Transport.LAN, devices.map { device -> DiscoveryCandidate(
                    Transport.LAN, device.discoveryEndpointId, device.ip, device.port,
                    DiscoveryIdentityClaim(device.deviceId, device.sessionId, device.name, device.deviceName, device.protocolVersion)) }) },
                onObserved = ::fresh).use { lan ->
                WifiHarness(onSnapshot = { peers -> cached(Transport.WIFI_DIRECT, peers.map { peer ->
                    val address = peer.device.deviceAddress.trim()
                    DiscoveryCandidate(Transport.WIFI_DIRECT, address.lowercase(java.util.Locale.ROOT), address, null, peer.identity)
                }) }, onObserved = ::fresh).use { wifi ->
                    setField(f.service, "lanDiscovery", lan.adapter); setField(f.service, "wifiTunnel", wifi.tunnel)
                    try {
                        lan.startNsd(); val listener = lan.nsd.discoveries.last()
                        lan.time.set(1_000L); listener.onServiceFound(nsdInfo(REMOTE_R1))
                        val a = lan.nsd.resolutions.last()
                        oldResolve = CompletableFuture.runAsync { a.listener.onServiceResolved(a.info) }
                        assertTrue(paused.await(2, TimeUnit.SECONDS))
                        val txtListener = wifi.p2p.dns.last().txt
                        wifi.time.set(2_000L); txtListener.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R2), wifi.peer)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertCurrent(REMOTE_R2); assertEquals(wifi.observed.single().receipt, latest().receipt)
                        release.countDown(); oldResolve!!.get(2, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(1, lan.observed.size); assertCurrent(REMOTE_R2)
                        assertEquals(REMOTE_R2, latest().candidate.identity.sourceSessionId)
                        wifi.time.set(3_000L); txtListener.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R2), wifi.peer)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(2, wifi.observed.size); assertEquals(wifi.observed.last().receipt, latest().receipt)
                        assertEquals(3_000L, latest().receivedAtElapsedRealtimeMs); assertCurrent(REMOTE_R2)
                        lan.time.set(4_000L); listener.onServiceFound(nsdInfo(REMOTE_R1))
                        lan.nsd.resolutions.last().let { it.listener.onServiceResolved(it.info) }
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals(2, lan.observed.size); assertCurrent(REMOTE_R2)
                        assertEquals(wifi.observed.last().receipt, latest().receipt)
                        val r3 = RuntimeSessionId("20000000-0000-4000-8000-000000000003")
                        lan.time.set(5_000L); listener.onServiceFound(nsdInfo(r3))
                        lan.nsd.resolutions.last().let { it.listener.onServiceResolved(it.info) }
                        shadowOf(Looper.getMainLooper()).idle()
                        assertCurrent(r3); assertEquals(lan.observed.last().receipt, latest().receipt)
                    } finally { release.countDown(); oldResolve?.get(2, TimeUnit.SECONDS) }
                }
            }
        } finally { release.countDown(); try { f.close() } finally { LegacyRuntimeOwnership.release(ownership) } }
    }

    @Test fun actualNsdLostRevokesPendingResolutionAndFinalObservationClaim() {
        LanHarness().use { h ->
            h.startNsd()
            val listener = h.nsd.discoveries.last()
            listener.onServiceFound(nsdInfo(REMOTE_R1))
            val pending = h.nsd.resolutions.last()
            listener.onServiceLost(pending.info)
            pending.listener.onServiceResolved(pending.info)
            assertTrue(h.observed.isEmpty())
            assertTrue(h.snapshots.last().isEmpty())
            listener.onServiceFound(nsdInfo(REMOTE_R1))
            h.nsd.resolutions.last().let { it.listener.onServiceResolved(it.info) }
            val accepted = h.observed.single()
            assertTrue(h.adapter.observationSource.isCurrent(accepted))
            listener.onServiceLost(nsdInfo(REMOTE_R1))
            assertFalse(h.adapter.observationSource.isCurrent(accepted))
            assertNull(h.adapter.observationSource.claimIfCurrent(accepted) { error("Lost receipt adopted") })
            assertTrue(h.snapshots.last().isEmpty())
            // A genuinely new found event can resolve after the loss.
            listener.onServiceFound(nsdInfo(REMOTE_R1))
            h.nsd.resolutions.last().let { it.listener.onServiceResolved(it.info) }
            assertEquals(2, h.observed.size)
            assertTrue(h.adapter.observationSource.isCurrent(h.observed.last()))
        }
    }

    @Test fun registeredNsdResolveOrderingProbeEpochAndCloseCannotRestoreOldInput() {
        LanHarness().use { h ->
            h.startNsd()
            val listener = h.nsd.discoveries.last()
            h.time.set(1_000L)
            listener.onServiceFound(nsdInfo(REMOTE_R1))
            val a = h.nsd.resolutions.last()
            h.time.set(2_000L)
            listener.onServiceFound(nsdInfo(REMOTE_R2, "127.0.0.2"))
            val b = h.nsd.resolutions.last()
            h.time.set(9_000L)
            b.listener.onServiceResolved(b.info)
            a.listener.onServiceResolved(a.info)
            assertEquals(1, h.observed.size)
            val current = h.observed.single()
            assertEquals(2_000L, current.receivedAtElapsedRealtimeMs)
            assertEquals(REMOTE_R2, current.candidate.identity.sourceSessionId)
            assertEquals("127.0.0.2", h.snapshots.last().single().ip)
            listener.onServiceLost(b.info)
            assertEquals(1, h.observed.size)
            assertFalse(h.adapter.observationSource.isCurrent(current))
            h.adapter.probeDiscovery()
            shadowOf(Looper.getMainLooper()).idle()
            val newListener = h.nsd.discoveries.last()
            assertNotSame(listener, newListener)
            val count = h.nsd.resolutions.size
            listener.onServiceFound(nsdInfo(REMOTE_R1))
            a.listener.onServiceResolved(a.info)
            assertEquals(count, h.nsd.resolutions.size)
            assertEquals(1, h.observed.size)
            h.time.set(10_000L)
            newListener.onServiceFound(nsdInfo(REMOTE_R2))
            h.nsd.resolutions.last().let { it.listener.onServiceResolved(it.info) }
            assertEquals(2, h.observed.size)
            assertEquals(10_000L, h.observed.last().receivedAtElapsedRealtimeMs)
            h.adapter.close()
            newListener.onServiceFound(nsdInfo(REMOTE_R2))
            assertEquals(2, h.observed.size)
            assertFalse(h.adapter.observationSource.isCurrent(h.observed.last()))
        }
    }

    @Test fun realUdpEqualFramesAreFreshButSupersededRuntimeAndExpiryAreNot() {
        LanHarness().use { h ->
            h.startUdp()
            DatagramSocket(InetSocketAddress(LOOPBACK, 0)).use { sender ->
                fun deliver(runtime: RuntimeSessionId, name: String, at: Long, count: Int) {
                    h.time.set(at); send(sender, udpBytes(runtime, name)); awaitIo(false) { h.observed.size == count }
                }
                deliver(REMOTE_R1, "r1", 1_000L, 1)
                deliver(REMOTE_R1, "r1", 1_100L, 2)
                assertEquals(h.observed[0].candidate, h.observed[1].candidate)
                assertEquals(h.observed[0].sequence + 1L, h.observed[1].sequence)
                deliver(REMOTE_R2, "r2", 1_200L, 3)
                h.time.set(1_300L); send(sender, udpBytes(REMOTE_R1, "old-r1"))
                awaitIo(false) { h.logs.any { it.contains("old-r1") } }
                deliver(REMOTE_R2, "marker-r2", 1_400L, 4)
                assertTrue(h.observed.drop(2).all { it.candidate.identity.sourceSessionId == REMOTE_R2 })
                assertEquals(h.observed[1].sequence + 3L, h.observed.last().sequence)
                h.time.set(20_000L); invoke(h.adapter, "expireLanBroadcastDevices")
                h.adapter.close()
                assertEquals(4, h.observed.size)
            }
        }
    }

    @Test fun actualTxtV2AndCachedReconciliationKeepInputReceiptAndRegistrationIdentity() {
        WifiHarness().use { h ->
            val listeners = h.p2p.dns.last()
            fun txtInput(runtime: RuntimeSessionId, at: Long) {
                h.time.set(at); listeners.txt.onDnsSdTxtRecordAvailable("fixture", txt(runtime), h.peer)
                shadowOf(Looper.getMainLooper()).idle()
            }
            txtInput(REMOTE_R1, 1_000L); txtInput(REMOTE_R1, 1_100L)
            assertEquals(2, h.observed.size)
            assertEquals(h.observed[0].candidate, h.observed[1].candidate)
            assertEquals(h.observed[0].sequence + 1L, h.observed[1].sequence)
            h.time.set(1_200L)
            listeners.service.onDnsSdServiceAvailable(P2pServiceInstanceCodec.encode(REMOTE_DEVICE, REMOTE_R2),
                "_motocom._tcp.local.", h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, h.observed.size)
            txtInput(REMOTE_R1, 1_300L)
            invoke(h.tunnel, "requestPeers")
            val list = ReflectionHelpers.newInstance(WifiP2pDeviceList::class.java)
            ReflectionHelpers.setField(list, "mDevices", hashMapOf(h.peer.deviceAddress to h.peer))
            h.p2p.peers.removeFirst().onPeersAvailable(list)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, h.observed.size)
            val epoch = h.tunnel.observationSource.currentEpoch
            h.reinstallDiscovery()
            assertTrue(h.tunnel.observationSource.currentEpoch > epoch)
            txtInput(REMOTE_R2, 1_500L)
            assertEquals(3, h.observed.size)
            h.time.set(2_000L)
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R2), h.peer)
            // A delayed Main observer retains the actual producer time.
            h.time.set(9_000L)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, h.observed.size)
            assertEquals(2_000L, h.observed.last().receivedAtElapsedRealtimeMs)
            h.tunnel.close()
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R2), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, h.observed.size)
        }
    }

    @Test fun productionReadyConnectCreatesFreshTcpHelloAndLeaseForNewAttempt() {
        WifiHarness(withServer = true).use { h ->
            h.p2p.dns.last().txt.onDnsSdTxtRecordAvailable("fixture", txt(REMOTE_R1), h.peer)
            shadowOf(Looper.getMainLooper()).idle()
            setState(h.tunnel, "GROUP_READY") // Platform fixture; readiness below requires real HELLO.
            val a = h.attempt("30000000-0000-4000-8000-000000000001")
            val first = h.connectThroughPlatformCallbacks(a, 1)
            val transportA = field(h.tunnel, "socketTransport")
            val generationA = field(h.tunnel, "socketTransportGeneration") as Int
            val b = h.attempt("30000000-0000-4000-8000-000000000002")
            val second = h.connectThroughPlatformCallbacks(b, 2)
            assertEquals(2, h.p2p.infoRequests)
            assertEquals("SIGNALING_READY", state(h.tunnel))
            assertNotSame(transportA, field(h.tunnel, "socketTransport"))
            assertTrue((field(h.tunnel, "socketTransportGeneration") as Int) > generationA)
            assertNotSame(first.second.socket, second.second.socket)
            assertNotEquals(first.first.channel.channelId, second.first.channel.channelId)
            assertEquals(b, second.first.originatingAttempt)
            assertEquals(b, second.second.originatingAttempt)
            assertEquals(b.deadlineElapsedRealtimeMs, field(second.second, "admissionDeadlineElapsedMs"))
            assertEquals(PendingSocketLease.Stage.TRANSFERRED, second.second.currentStage)
            assertFalse(first.first.isClosed)
            assertFalse(second.first.isClosed)
            val current = field(h.tunnel, "socketTransport")
            assertTrue(h.tunnel.connect(b)); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, h.p2p.infoRequests)
            assertSame(current, field(h.tunnel, "socketTransport"))
            assertEquals(2, h.installed.size)
            assertTrue(h.errors.toString(), h.errors.isEmpty())
        }
    }

    private class LanHarness(
        activeToken: SessionGeneration.Token? = null,
        isCurrentToken: ((SessionGeneration.Token) -> Boolean)? = null,
        afterLog: (String) -> Unit = {},
        onSnapshot: (List<LanRiderDevice>) -> Unit = {},
        onObserved: (FreshDiscoveryObservation) -> Unit = {}
    ) : AutoCloseable {
        val time = AtomicLong(1_000L)
        val observed = CopyOnWriteArrayList<FreshDiscoveryObservation>()
        val snapshots = CopyOnWriteArrayList<List<LanRiderDevice>>()
        val logs = CopyOnWriteArrayList<String>()
        private val sessions = SessionGeneration()
        private val token = activeToken ?: sessions.start()
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val nsd: RecordingNsdInputShadow = Shadow.extract(context.getSystemService(Context.NSD_SERVICE) as NsdManager)
        val adapter = LanDiscoveryCoordinator(context, token, isCurrentToken ?: sessions::isCurrent, LOCAL_DEVICE, LOCAL_RUNTIME,
            "local", "fixture", 2, { devices -> snapshots += devices; onSnapshot(devices) }, { _, lease -> lease.close() },
            { message -> logs += message; afterLog(message) },
            { throw AssertionError("LAN producer failed", it) },
            monotonicClock = MonotonicClock { MonotonicTimestamp(time.get()) },
            onFreshObservation = { observation -> observed += observation; onObserved(observation) })
        private var udpRun: CompletableFuture<Void>? = null
        fun startNsd() { invoke(adapter, "startNsdDiscovery"); shadowOf(Looper.getMainLooper()).idle() }
        fun startUdp() {
            udpRun = CompletableFuture.runAsync { invoke(adapter, "runLanUdpListener", "127.0.0.2") }
            awaitIo(false) { (field(adapter, "udpSocket") as AtomicReference<*>).get() != null }
        }
        override fun close() { adapter.close(); udpRun?.get(2, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }

    private class WifiHarness(withServer: Boolean = false, onSnapshot: (List<WifiDirectRiderDevice>) -> Unit = {},
        onObserved: (FreshDiscoveryObservation) -> Unit = {}, rejectHello: Boolean = false,
        eofFirstHello: Boolean = false, localId: String = LOCAL_DEVICE, requestRecovery: Boolean = false,
        eofHelloCount: Int = if (eofFirstHello) 1 else 0,
        onReady: ((SignalingSessionV2, PendingSocketLease) -> Unit)? = null,
        captureLoss: (ConnectionAttempt) -> ConnectionLossObservation? = { null },
        onSelectedLost: (ConnectionAttempt, ConnectionLossObservation) -> Unit = { _, _ -> }) : AutoCloseable {
        val time = AtomicLong(1_000L)
        private val clock = MonotonicClock { MonotonicTimestamp(time.get()) }
        val observed = CopyOnWriteArrayList<FreshDiscoveryObservation>()
        val installed = CopyOnWriteArrayList<Pair<SignalingSessionV2, PendingSocketLease>>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val failed = CopyOnWriteArrayList<Pair<ConnectionAttempt, String>>()
        val peer = WifiP2pDevice().apply { deviceAddress = "02:00:00:00:00:02"; deviceName = "remote"; status = WifiP2pDevice.CONNECTED }
        private val server = if (withServer) PeerServer(clock, rejectHello, eofHelloCount, localId, requestRecovery) else null
        private val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var tunnel: WifiDirectTunnel
        val p2p: RecordingP2pInputShadow
        init {
            tunnel = WifiDirectTunnel(context, onControlChannelReady = { session, lease ->
                assertEquals(field(tunnel, "targetAttempt"), session.originatingAttempt)
                if (onReady == null) assertTrue(lease.tryTransfer({ true }) { installed += session to lease })
                else { onReady(session, lease); installed += session to lease }
            }, signalingPort = server?.port ?: 8888, localDeviceId = localId, localDeviceName = "fixture",
                sessionId = LOCAL_RUNTIME, monotonicClock = clock, onPeersChanged = onSnapshot,
                onFreshObservation = { observation -> observed += observation; onObserved(observation) },
                onError = errors::add, localP2pAddressResolver = { LOOPBACK },
                onTargetedTransportFailed = { attempt, reason -> failed += attempt to reason },
                captureConnectionLoss = captureLoss, onSelectedGroupLost = onSelectedLost)
            setField(tunnel, "running", true); invoke(tunnel, "initP2p")
            p2p = Shadow.extract(field(tunnel, "manager") as WifiP2pManager)
            p2p.dns.clear(); p2p.infos.clear(); p2p.groups.clear(); p2p.peers.clear()
            p2p.removals.clear(); p2p.cancellations.clear(); p2p.connections.clear()
            p2p.holdGroupRemoval = false; p2p.holdCancelConnect = false
            p2p.throwNextGroupQuery = false
            (field(tunnel, "setupRecoveryGate") as WifiDirectSetupRecoveryGate).updateP2pEnabled(true)
            reinstallDiscovery()
        }
        fun reinstallDiscovery() { invoke(tunnel, "setupServiceDiscovery"); shadowOf(Looper.getMainLooper()).idle(); assertTrue(field(tunnel, "serviceDiscoveryReady") as Boolean) }
        fun attempt(id: String) = ConnectionAttempt(ConnectionAttemptId(id), LOCAL_RUNTIME,
            TargetLock(REMOTE_DEVICE, REMOTE_R1), ConnectionTrigger.RECOVERY, ChannelPlan.single(Transport.WIFI_DIRECT), time.get() + 10_000L)
        fun connectThroughPlatformCallbacks(attempt: ConnectionAttempt, count: Int): Pair<SignalingSessionV2, PendingSocketLease> {
            openGroupThroughPlatformCallbacks(attempt)
            awaitIo { installed.size == count }
            assertTrue(server!!.errors.toString(), server.errors.isEmpty())
            return installed.last()
        }
        fun openGroupThroughPlatformCallbacks(attempt: ConnectionAttempt) {
            assertTrue(tunnel.connect(attempt)); assertEquals("GROUP_READY", state(tunnel))
            assertTrue(p2p.infos.isNotEmpty())
            p2p.infos.removeFirst().onConnectionInfoAvailable(validInfo())
            assertTrue(p2p.groups.isNotEmpty())
            p2p.groups.removeFirst().onGroupInfoAvailable(validGroup())
        }
        fun validInfo() = WifiP2pInfo().apply { groupFormed = true; isGroupOwner = false; groupOwnerAddress = LOOPBACK }
        fun validGroup(): WifiP2pGroup {
            val group = WifiP2pGroup()
            ReflectionHelpers.setField(group, "mOwner", peer)
            ReflectionHelpers.setField(group, "mClients", mutableListOf<WifiP2pDevice>())
            ReflectionHelpers.setField(group, "mInterface", "fixture-p2p")
            return group
        }
        fun disconnectBroadcast() = (field(tunnel, "receiver") as android.content.BroadcastReceiver)
            .onReceive(context, android.content.Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        @Suppress("DEPRECATION") fun connectedBroadcast() {
            val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI,
                0, true, true)
            (field(tunnel, "receiver") as android.content.BroadcastReceiver).onReceive(context,
                android.content.Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, info))
        }
        override fun close() { try { tunnel.close(); shadowOf(Looper.getMainLooper()).idle() } finally { installed.forEach { it.first.close() }; server?.close() } }
    }

    private class PeerServer(private val clock: MonotonicClock, private val rejectHello: Boolean = false,
        eofHelloCount: Int = 0, private val localId: String = LOCAL_DEVICE,
        private val requestRecovery: Boolean = false) : AutoCloseable {
        private val listener = ServerSocket(0, 8, LOOPBACK)
        val port get() = listener.localPort
        val errors = ConcurrentLinkedQueue<Throwable>()
        private val sockets = ConcurrentLinkedQueue<Socket>()
        private val sessions = ConcurrentLinkedQueue<SignalingSessionV2>()
        private val closed = AtomicBoolean(false)
        private val remainingEofs = java.util.concurrent.atomic.AtomicInteger(eofHelloCount)
        private val executor = Executors.newCachedThreadPool()
        init { executor.execute {
            try { while (!closed.get()) {
                val socket = listener.accept(); sockets += socket
                executor.execute {
                    if (remainingEofs.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) { socket.close(); return@execute }
                    if (rejectHello) {
                        try {
                            socket.soTimeout = 2_000
                            SignalingV2Framing.read(java.io.DataInputStream(socket.getInputStream()))
                            java.io.DataOutputStream(socket.getOutputStream()).apply { writeInt(0); flush() }
                        }
                        finally { socket.close() }
                        return@execute
                    }
                    try {
                        val origin = if (requestRecovery) ConnectionAttempt(
                            ConnectionAttemptId("20000000-0000-4000-8000-000000000077"), REMOTE_R1,
                            TargetLock(localId, LOCAL_RUNTIME), ConnectionTrigger.RECOVERY,
                            ChannelPlan.single(Transport.WIFI_DIRECT), clock.now().elapsedRealtimeMs + 10_000L) else null
                        val session = SignalingSessionV2.establish(socket, Transport.WIFI_DIRECT, PhysicalSocketRole.ACCEPTOR,
                            clock.now().elapsedRealtimeMs, REMOTE_DEVICE, REMOTE_R1, "remote", "fixture", origin,
                            TargetLock(localId, LOCAL_RUNTIME), clock)
                        sessions += session
                        if (requestRecovery) {
                            session.startReader({ envelope ->
                                if (envelope.message is SignalingMessageV2.ConnectAccept) session.send(
                                    SignalingMessageV2.Offer("{\"type\":\"offer\",\"sdp\":\"v=0\"}"))
                            }, { if (!closed.get()) errors += it })
                            session.send(SignalingMessageV2.ConnectRequest(RequestTrigger.RECOVERY, Transport.WIFI_DIRECT))
                        }
                    }
                    catch (t: Throwable) { if (!closed.get()) errors += t }
                }
            } } catch (t: Throwable) { if (!closed.get()) errors += t }
        } }
        override fun close() {
            closed.set(true); listener.close(); sessions.forEach { it.close() }; sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun invokeServiceEntry(service: IntercomService, prefix: String, vararg arguments: Any?) {
            val method = IntercomService::class.java.declaredMethods.single { it.name.startsWith(prefix) &&
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.parameterCount == arguments.size }.apply { isAccessible = true }
            try { method.invoke(service, *arguments) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        val LOOPBACK = InetAddress.getByName("127.0.0.1") as Inet4Address
        const val LOCAL_DEVICE = "a0000000-0000-4000-8000-000000000001"
        const val REMOTE_DEVICE = "b0000000-0000-4000-8000-000000000001"
        val LOCAL_RUNTIME = RuntimeSessionId("10000000-0000-4000-8000-000000000001")
        val REMOTE_R1 = RuntimeSessionId("20000000-0000-4000-8000-000000000001")
        val REMOTE_R2 = RuntimeSessionId("20000000-0000-4000-8000-000000000002")
        fun txt(runtime: RuntimeSessionId) = mapOf("appId" to "MotoCom", "protocolVersion" to "2", "deviceId" to REMOTE_DEVICE,
            "sessionId" to runtime.value, "nickname" to "remote", "deviceName" to "fixture")
        @Suppress("DEPRECATION") fun nsdInfo(runtime: RuntimeSessionId, ip: String = "127.0.0.1") = NsdServiceInfo().apply {
            serviceName = "fixture-remote"; serviceType = "_motocom._tcp."; host = InetAddress.getByName(ip); port = 8890
            setAttribute("id", REMOTE_DEVICE); setAttribute("sessionId", runtime.value); setAttribute("name", "remote")
            setAttribute("deviceName", "fixture"); setAttribute("protocolVersion", "2")
        }
        fun udpBytes(runtime: RuntimeSessionId, name: String) = JSONObject().put("type", "MOTOCOM_HELLO").put("id", REMOTE_DEVICE)
            .put("sessionId", runtime.value).put("name", name).put("deviceName", "fixture").put("protocolVersion", 2).put("tcpPort", 8890).toString().toByteArray()
        fun send(sender: DatagramSocket, bytes: ByteArray) { sender.send(DatagramPacket(bytes, bytes.size, LOOPBACK, 8889)) }
        fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        fun setField(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
        fun state(tunnel: WifiDirectTunnel) = (field(tunnel, "state") as Enum<*>).name
        fun setState(tunnel: WifiDirectTunnel, name: String) {
            val f = tunnel.javaClass.getDeclaredField("state").apply { isAccessible = true }
            f.set(tunnel, requireNotNull(f.type.enumConstants).single { (it as Enum<*>).name == name })
        }
        fun invoke(owner: Any, name: String, vararg args: Any?) {
            val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
            try { method.invoke(owner, *args) } catch (t: InvocationTargetException) { throw t.targetException }
        }
        fun awaitIo(drainMain: Boolean = true, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Actual producer/HELLO did not complete" }
                if (drainMain) shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(3L)
            }
        }
    }
}
