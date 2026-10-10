package com.kuma.motointercom

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers

/** Only the Android network binding is shadowed; TCP, leases, workers and Service are real. */
@Implements(Network::class)
class ControlledLanNetworkShadow : ShadowNetwork() {
    val sockets = CopyOnWriteArrayList<Socket>()
    var beforeBind: (Socket) -> Unit = {}

    @Implementation public override fun bindSocket(socket: Socket) {
        sockets += socket
        beforeBind(socket)
        super.bindSocket(socket)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], shadows = [ControlledLanNetworkShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class LanConnectFailureServiceRobolectricTest {
    @Test fun tcpRefusalEndsCurrentAttemptBeforeItsDeadline() = runBlocking {
        Harness().use { h ->
            h.prepare()
            h.begin()
            h.workerBarrier()
            h.mainBarrier()
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
            assertEquals(IntercomState.Discovering(h.runtime), h.actor.state.value)
            assertTrue(SystemClock.elapsedRealtime() < h.attempt.deadlineElapsedRealtimeMs)
            assertEquals(0, h.pendingCount())
            assertTrue(h.logs.any { it.contains("stage=TCP") && it.contains(h.attempt.id.value) })
        }
    }

    @Test fun cellularDefaultBindsActualLanSocketToLocalOnlyWifi() = runBlocking {
        Harness().use { h ->
            h.prepare()
            assertEquals(h.mobile, h.connectivity.activeNetwork)
            assertFalse(h.connectivity.getNetworkCapabilities(h.wifi)!!
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            h.begin()
            h.workerBarrier()
            val bound = h.wifiShadow.sockets.single()
            assertTrue(h.wifiShadow.isSocketBound(bound))
            assertTrue(bound.isClosed)
            assertTrue(h.mobileShadow.sockets.isEmpty())
            assertNull(h.connectivity.boundNetworkForProcess)
            h.mainBarrier()
            assertTrue(h.logs.any { it.contains("network=${h.wifi}") && it.contains("local=127.0.0.1") })
        }
    }

    @Test fun mismatchedWifiAndVpnCannotFallBackToDefaultCellular() = runBlocking {
        Harness().use { h ->
            h.prepare(wifiIp = "127.0.0.2")
            val vpn = h.addNetwork(2, ConnectivityManager.TYPE_VPN, "127.0.0.1",
                NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN)
            h.begin()
            h.workerBarrier()
            h.mainBarrier()
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
            assertTrue(h.logs.any { it.contains("No matching Wi-Fi network") && it.contains("stage=WIFI_BIND") })
            assertTrue(h.wifiShadow.sockets.isEmpty())
            assertTrue(h.mobileShadow.sockets.isEmpty())
            assertTrue(h.networkShadow(vpn).sockets.isEmpty())
            assertEquals(0, h.pendingCount())
        }
    }

    @Test fun freshDiscoveryCannotResubmitFailedAttemptWhileMainDeliveryIsQueued() = runBlocking {
        Harness().use { h ->
            h.prepare()
            h.begin()
            h.workerBarrier()
            assertEquals(h.attempt, h.actor.currentAttempt)
            repeat(3) { h.observePeer() }
            h.workerBarrier()
            assertEquals(1, h.wifiShadow.sockets.size)
            h.mainBarrier()
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
        }
    }

    @Test fun queuedFailureCannotEndReplacementAttemptOrNotifyItsUi() = runBlocking {
        Harness().use { h ->
            h.prepare()
            h.begin()
            h.workerBarrier()
            assertEquals(h.attempt, h.actor.currentAttempt)
            val replacement = h.replaceAttempt()
            h.mainBarrier()
            assertEquals(replacement, h.actor.currentAttempt)
            assertEquals(IntercomState.Connecting(replacement), h.actor.state.value)
            assertEquals(ConnectionAttemptTerminalOutcome.CANCELED, h.actor.terminalOutcome(h.attempt.id))
            assertNull(h.actor.terminalOutcome(replacement.id))
            assertTrue(h.errors.isEmpty())
        }
    }

    @Test fun failureRetiresDiscoveryClaimThatWasAlreadyWaitingOnRegistry() = runBlocking {
        Harness().use { h ->
            h.prepare()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val threadError = AtomicReference<Throwable>()
            h.wifiShadow.beforeBind = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                throw IOException("EHOSTUNREACH registry interleaving fixture")
            }
            var discovery: Thread? = null
            try {
                h.begin()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val registry = requireNotNull(field(h.adapter, "deviceRegistry").get(h.adapter))
                synchronized(registry) {
                    discovery = Thread {
                        try {
                            LanDiscoveryCoordinator::class.java.getDeclaredMethod("connectTargetIfAvailable")
                                .apply { isAccessible = true }.invoke(h.adapter)
                        } catch (failure: Throwable) { threadError.set(failure) }
                    }.also { it.start() }
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (discovery!!.state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.sleep(5)
                    assertEquals(Thread.State.BLOCKED, discovery!!.state)
                    release.countDown()
                    h.workerBarrier()
                }
                discovery!!.join(2_000)
                assertFalse(discovery!!.isAlive)
                assertNull(threadError.get())
                h.workerBarrier()
                assertEquals(1, h.wifiShadow.sockets.size)
                h.mainBarrier()
                assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
            } finally {
                release.countDown()
                discovery?.join(2_000)
            }
        }
    }

    @Test fun bindFailureCompletingAfterReplacementReleasesSocketWithoutChangingNewOwner() = runBlocking {
        Harness().use { h ->
            h.prepare()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.wifiShadow.beforeBind = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                throw IOException("EHOSTUNREACH delayed binding fixture")
            }
            try {
                h.begin()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val socket = h.wifiShadow.sockets.single()
                val replacement = h.replaceAttempt()
                assertTrue(socket.isClosed)
                release.countDown()
                h.workerBarrier()
                h.mainBarrier()
                assertEquals(replacement, h.actor.currentAttempt)
                assertNull(h.actor.terminalOutcome(replacement.id))
                assertTrue(h.errors.isEmpty())
                assertEquals(0, h.pendingCount())
            } finally { release.countDown() }
        }
    }

    @Test fun stopDuringBindingClosesActualPendingSocketAndSuppressesFailure() = runBlocking {
        Harness().use { h ->
            h.prepare()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.wifiShadow.beforeBind = {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                throw IOException("EHOSTUNREACH stopped binding fixture")
            }
            try {
                h.begin()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                h.f.stopRuntime()
                release.countDown()
                h.f.awaitMain { h.actor.state.value == IntercomState.Offline && h.worker.isShutdown }
                assertTrue(h.worker.awaitTermination(2, TimeUnit.SECONDS))
                assertTrue(h.wifiShadow.sockets.single().isClosed)
                assertEquals(0, h.pendingCount())
                assertTrue(h.errors.isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun rejectedWorkerSubmissionReportsExactAttemptFailure() = runBlocking {
        Harness().use { h ->
            h.prepare()
            h.worker.shutdownNow()
            h.begin()
            h.mainBarrier()
            assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
            assertTrue(h.logs.any { it.contains("stage=SUBMIT") && it.contains(h.attempt.id.value) })
            assertEquals(0, h.pendingCount())
        }
    }

    @Test fun helloEofReportsFailureAfterActualTcpConnectAndClosesSocket() = runBlocking {
        ServerSocket(0).use { peer ->
            val accepting = Executors.newSingleThreadExecutor()
            try {
                val remote = accepting.submit { peer.accept().use { } }
                Harness(peer.localPort).use { h ->
                    h.prepare()
                    h.begin()
                    h.workerBarrier()
                    remote.get(2, TimeUnit.SECONDS)
                    h.mainBarrier()
                    assertEquals(ConnectionAttemptTerminalOutcome.FAILED, h.actor.terminalOutcome(h.attempt.id))
                    assertTrue(h.logs.any { it.contains("stage=HELLO") })
                    assertTrue(h.wifiShadow.sockets.single().isClosed)
                    assertEquals(0, h.pendingCount())
                    assertEquals(0, (field(h.f.service, "signalingSessions").get(h.f.service) as Map<*, *>).size)
                }
            } finally { accepting.shutdownNow() }
        }
    }

    @Test fun lanFailurePreservesExistingDualPlanTargetAndDeadline() = runBlocking {
        Harness().use { h ->
            h.prepare()
            h.begin(dualPlan = true)
            h.workerBarrier()
            h.mainBarrier()
            assertEquals(IntercomState.Connecting(h.attempt), h.actor.state.value)
            assertEquals(h.attempt, h.actor.currentAttempt)
            assertNull(h.actor.terminalOutcome(h.attempt.id))
            assertEquals(listOf(Transport.LAN, Transport.WIFI_DIRECT), h.attempt.channelPlan.plannedTransports.toList())
            assertTrue(h.logs.any { it.startsWith("Targeted transport open failed") })
            assertEquals(0, h.pendingCount())
        }
    }

    private class Harness(private val peerPort: Int = ServerSocket(0).use { it.localPort }) : AutoCloseable {
        val f = IncomingConfirmationServiceFixture()
        val actor get() = f.actor
        val runtime = RuntimeSessionId.create()
        private val remoteDevice = UUID.randomUUID().toString()
        private val remoteRuntime = RuntimeSessionId.create()
        lateinit var attempt: ConnectionAttempt
        lateinit var adapter: LanDiscoveryCoordinator
        lateinit var worker: ExecutorService
        val logs = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val connectivity = f.service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        private val wifiManager = f.service.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifi = ShadowNetwork.newInstance(1)
        val mobile = ShadowNetwork.newInstance(0)
        val wifiShadow get() = networkShadow(wifi)
        val mobileShadow get() = networkShadow(mobile)

        suspend fun prepare(wifiIp: String = "127.0.0.1") {
            val token = f.activateRuntime(runtime)
            f.service.setListener(object : IntercomService.Listener {
                override fun onStatusChanged(status: String, running: Boolean) = Unit
                override fun onLog(message: String) { logs += message }
                override fun onError(message: String) { errors += message }
            })
            // Use the actual Service factory with LAN only; zero IP prevents unrelated discovery loops.
            shadowOf(wifiManager).setConnectionInfo(ShadowWifiInfo.newInstance())
            val plan = newAttempt()
            IntercomService::class.java.declaredMethods.single {
                it.name.startsWith("startDiscoveryTransports") && it.parameterCount == 4 &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.apply { isAccessible = true }.invoke(f.service, token.value, f.localDeviceId, runtime.value, plan)
            adapter = field(f.service, "lanDiscovery").get(f.service) as LanDiscoveryCoordinator
            (field(adapter, "executor").get(adapter) as ExecutorService).shutdownNow()
            worker = Executors.newSingleThreadExecutor()
            field(adapter, "executor").set(adapter, worker)
            shadowOf(wifiManager.connectionInfo).setInetAddress(InetAddress.getByName("127.0.0.1"))
            shadowOf(connectivity).clearAllNetworks()
            addNetwork(0, ConnectivityManager.TYPE_MOBILE, "10.0.0.2", NetworkCapabilities.TRANSPORT_CELLULAR)
            addNetwork(1, ConnectivityManager.TYPE_WIFI, wifiIp, NetworkCapabilities.TRANSPORT_WIFI)
            shadowOf(connectivity).setActiveNetworkInfo(connectivity.getNetworkInfo(mobile))
        }

        fun addNetwork(id: Int, type: Int, ip: String, vararg transports: Int): Network {
            val network = when (id) {
                0 -> mobile
                1 -> wifi
                else -> ShadowNetwork.newInstance(id)
            }
            val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, true)
            val capabilities = ShadowNetworkCapabilities.newInstance()
            transports.forEach { shadowOf(capabilities).addTransportType(it) }
            val properties = LinkProperties().apply {
                interfaceName = if (type == ConnectivityManager.TYPE_WIFI) "wlan0" else "fixture$id"
                val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
                    ReflectionHelpers.ClassParameter.from(InetAddress::class.java, InetAddress.getByName(ip)),
                    ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 24))
                ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress",
                    ReflectionHelpers.ClassParameter.from(LinkAddress::class.java, address))
            }
            shadowOf(connectivity).addNetwork(network, info)
            shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
            shadowOf(connectivity).setLinkProperties(network, properties)
            return network
        }

        suspend fun begin(dualPlan: Boolean = false) {
            if (dualPlan) {
                assertTrue(actor.dispatchAndAwait(SessionEvent.ConnectPresenceRequested(runtime, remoteDevice,
                    remoteRuntime, setOf(Transport.LAN, Transport.WIFI_DIRECT))))
                attempt = requireNotNull(actor.currentAttempt)
            } else {
                attempt = newAttempt()
                assertTrue(actor.dispatchAndAwait(SessionEvent.ConnectRequested(attempt)))
            }
            observePeer()
            assertTrue(adapter.connect(attempt))
        }

        fun observePeer() {
            val source = field(adapter, "observationSource").get(adapter) as FreshDiscoverySource
            val receipt = requireNotNull(source.capture(DiscoveryObservationKind.LAN_NSD, SystemClock.elapsedRealtime()))
            val device = LanRiderDevice("fixture-peer", remoteDevice, remoteRuntime, "remote", "remote phone",
                2, "127.0.0.1", peerPort)
            LanDiscoveryCoordinator::class.java.getDeclaredMethod("rememberObservedLanDevice", String::class.java,
                LanRiderDevice::class.java, FreshDiscoveryReceipt::class.java, java.lang.Long::class.java)
                .apply { isAccessible = true }.invoke(adapter, device.discoveryEndpointId, device, receipt, null)
        }

        suspend fun replaceAttempt(): ConnectionAttempt {
            val replacement = attempt.copy(id = ConnectionAttemptId.create(), trigger = ConnectionTrigger.RECOVERY)
            assertTrue(actor.dispatchAndAwait(SessionEvent.AttemptReplaced(replacement)))
            assertTrue(adapter.prepareRetry(replacement))
            return replacement
        }

        fun workerBarrier() { worker.submit {}.get(4, TimeUnit.SECONDS) }
        suspend fun mainBarrier() {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(actor.dispatchAndAwait(SessionEvent.AutomaticReconnectChanged(true)))
        }
        fun pendingCount() = (field(adapter, "pendingSockets").get(adapter) as Map<*, *>).size
        fun networkShadow(network: Network): ControlledLanNetworkShadow = Shadow.extract(network)
        private fun newAttempt() = ConnectionAttempt(ConnectionAttemptId.create(), runtime,
            TargetLock(remoteDevice, remoteRuntime), ConnectionTrigger.USER, ChannelPlan.single(Transport.LAN),
            SystemClock.elapsedRealtime() + 10_000L)

        override fun close() {
            try { f.close() } finally {
                if (::adapter.isInitialized) adapter.close()
                if (::worker.isInitialized) worker.shutdownNow()
            }
        }
    }

    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    }
}
