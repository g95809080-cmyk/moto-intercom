package com.kuma.motointercom.group

import android.content.Context
import android.net.Network
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.group.network.GroupBoundedCallbacks
import com.kuma.motointercom.group.network.GroupWifiCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only replaces Android's physical-network binding. All control sockets and callbacks are real. */
@Implements(Network::class)
class LoopbackControlNetwork {
    @Implementation fun bindSocket(socket: Socket) { binding?.invoke(socket) }
    companion object { var binding: ((Socket) -> Unit)? = null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [LoopbackControlNetwork::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GroupControlOwnershipRobolectricTest {
    @Test fun hostCloseFirstCannotInstallLateAuthenticationBesideHealthyMember() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val a = f.newClient()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.beforeHostAuthentication = { channel ->
                if (channel.context.client == a.endpoint) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            val attempt = f.join(a)
            try {
                await(entered); val peer = f.host.peers[a.endpoint.deviceId]!!
                peer.channel.get().close(); release.countDown(); await(peer.authFinished)
                attempt.awaitWorker(); idle()
                assertNoHostGhost(f, peer.channel.get())
                a.assertUnpublishedAndRecovering(); f.assertHealthy(b, bLease)
            } finally { release.countDown() }
        }
    }

    @Test fun hostCloseDuringMapInstallPreservesHealthyMembersExactQueue() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val bChannel = f.host.peers[b.endpoint.deviceId]!!.channel.get()
            val bQueue = f.host.ingress()[bChannel.id]
            val a = f.newClient()
            val inserted = CountDownLatch(1); val release = CountDownLatch(1)
            val map = object : ConcurrentHashMap<UUID, GroupSocketChannel>(f.host.channels()) {
                override fun put(key: UUID, value: GroupSocketChannel): GroupSocketChannel? {
                    val old = super.put(key, value)
                    if (value.context.client == a.endpoint) { inserted.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return old
                }
            }
            field(f.host.runtime, "channels").set(f.host.runtime, map)
            val attempt = f.join(a)
            try {
                await(inserted); val peer = f.host.peers[a.endpoint.deviceId]!!
                assertFalse(f.host.ingress().containsKey(peer.channel.get().id))
                peer.channel.get().close(); release.countDown(); await(peer.authFinished)
                attempt.awaitWorker(); idle()
                assertNoHostGhost(f, peer.channel.get())
                assertSame(bChannel, f.host.channels()[bChannel.id]); assertSame(bQueue, f.host.ingress()[bChannel.id])
                a.assertUnpublishedAndRecovering(); f.assertHealthy(b, bLease)
            } finally { release.countDown() }
        }
    }
    private fun assertNoHostGhost(f: Fixture, channel: GroupSocketChannel) {
        assertFalse(f.host.channels().containsKey(channel.id)); assertFalse(f.host.ingress().containsKey(channel.id))
        assertFalse(f.host.events.any { it is GroupSessionEvent.Authenticated && it.channel == channel.id })
        assertFalse((field(f.host.writer, "pending").get(f.host.writer) as Map<*, *>).containsKey(channel.id))
        assertFalse(f.host.writer.snapshot.view!!.members.any { it.lease.deviceId == channel.context.client.deviceId })
        assertChannelReleased(channel)
    }
    @Test fun immediateStopSurvivesActualLastChannelRemovalDuringSnapshot() = snapshotStop(false)
    @Test fun flushedStopSurvivesActualLastChannelRemovalDuringSnapshot() = snapshotStop(true)
    private fun snapshotStop(flush: Boolean) {
        Fixture().use { f ->
            val a = f.newClient()
            val held = CountDownLatch(1); val releaseReader = CountDownLatch(1); val removed = CountDownLatch(1)
            val timerEntered = CountDownLatch(1); val releaseTimer = CountDownLatch(1)
            val interleave = AtomicBoolean(false)
            val registry = object : ConcurrentHashMap<UUID, GroupSocketChannel>() {
                private fun observed(count: Long): Long {
                    if (interleave.compareAndSet(true, false)) {
                        assertEquals(1L, count)
                        releaseReader.countDown()
                        assertTrue("Actual onClosed worker did not remove the last channel", removed.await(2, TimeUnit.SECONDS))
                        assertTrue(isEmpty())
                    }
                    return count
                }
                override val size: Int get() = observed(super.size.toLong()).toInt()
                override fun mappingCount(): Long = observed(super.mappingCount())
            }
            field(f.host.runtime, "channels").set(f.host.runtime, registry)
            f.afterHostMessage = { channel ->
                held.countDown()
                try { check(releaseReader.await(5, TimeUnit.SECONDS)) }
                finally { channel.close(); removed.countDown() }
            }
            val attempt = f.join(a)
            try {
                await(attempt.authFinished)
                val peer = f.host.peers[a.endpoint.deviceId]!!; await(peer.authFinished)
                idle(); await(held)
                val channel = peer.channel.get()
                val queue = checkNotNull(f.host.ingress()[channel.id])
                assertEquals(1, field(queue, "pending").getInt(queue))
                val timers = field(f.host.server!!, "scheduler").get(f.host.server) as ScheduledExecutorService
                timers.execute {
                    timerEntered.countDown()
                    try { releaseTimer.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
                await(timerEntered); interleave.set(true)
                if (flush) f.host.writer.dispatch(GroupSessionEvent.Leave)
                else f.host.writer.dispatch(GroupSessionEvent.Failed(f.host.writer.snapshot.operation!!, "fixture failure"))
                f.failure.get()?.let { throw it }
                assertEquals(GroupPhase.IDLE, f.host.writer.snapshot.phase)
                if (flush) {
                    assertEquals(0, f.host.stopCompletions)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
                    assertFalse(channel.isClosed); assertEquals(0, f.host.stopCompletions)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
                }
                assertFalse(interleave.get()); assertEquals(1, f.host.stopCompletions)
                assertTrue(registry.isEmpty()); assertTrue(f.host.ingress().isEmpty())
                assertChannelReleased(channel)
                assertTrue((field(f.host.server!!, "server").get(f.host.server) as java.net.ServerSocket).isClosed)
                assertNull(field(f.host.runtime, "client").get(f.host.runtime))
                assertNull(field(f.host.runtime, "server").get(f.host.runtime))
                idle(); assertFalse(field(queue, "accepting").getBoolean(queue)); assertEquals(0, field(queue, "pending").getInt(queue))
                assertFalse(f.host.events.any { it is GroupSessionEvent.Control })
                f.host.runtime.stop(flush) { f.host.stopCompletions++ }; assertEquals(1, f.host.stopCompletions)
                assertNull(f.failure.get())
            } finally { releaseReader.countDown(); releaseTimer.countDown() }
        }
    }
    @Test fun actualCloseBeforeRuntimeAuthenticationCannotInstallAndCurrentAttemptRecovers() {
        Fixture().use { f ->
            val b = f.joinHealthy()
            val bLease = b.writer.snapshot.local
            val a = f.newClient()
            val attempt = f.join(a, holdAuth = true)
            await(attempt.authEntered)
            attempt.channel.get().close()
            attempt.releaseAuth.countDown()
            await(attempt.authFinished); attempt.awaitWorker()
            idle()
            a.assertUnpublishedAndRecovering()
            assertEquals(1, a.events.count { it is GroupSessionEvent.ControlFailed })
            assertFalse(a.events.any { it is GroupSessionEvent.Authenticated })
            f.assertHealthy(b, bLease)
            // The real writer tick creates B's replacement control attempt on the same network.
            val network = a.writer.snapshot.networkAttempt
            f.clock = maxOf(f.clock, groupNowMs()) + 4_000
            a.writer.dispatch(GroupSessionEvent.Tick)
            val replacement = a.attempts.last()
            assertNotSame(attempt.parent, replacement.parent)
            assertEquals(network, a.writer.snapshot.networkAttempt)
            f.finishJoin(a, replacement)
            assertEquals(GroupPhase.IN_ROOM, a.writer.snapshot.phase)
            assertFalse(replacement.channel.get().isClosed)
            assertEquals(1, a.events.count { it is GroupSessionEvent.Authenticated })
            f.assertHealthy(b, bLease)
        }
    }

    @Test fun actualCloseBetweenChannelAndIngressInstallationRemovesOnlyItsOwnQueue() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val a = f.newClient()
            val inserted = CountDownLatch(1); val releasePut = CountDownLatch(1)
            val channels = object : ConcurrentHashMap<UUID, GroupSocketChannel>() {
                override fun put(key: UUID, value: GroupSocketChannel): GroupSocketChannel? {
                    val previous = super.put(key, value)
                    inserted.countDown(); check(releasePut.await(5, TimeUnit.SECONDS))
                    return previous
                }
            }
            val queues = object : ConcurrentHashMap<UUID, GroupBoundedCallbacks>() {
                val installed = AtomicReference<GroupBoundedCallbacks>()
                override fun put(key: UUID, value: GroupBoundedCallbacks): GroupBoundedCallbacks? {
                    installed.set(value); return super.put(key, value)
                }
            }
            field(a.runtime, "channels").set(a.runtime, channels)
            field(a.runtime, "ingress").set(a.runtime, queues)
            val attempt = f.join(a)
            try {
                await(inserted); attempt.channel.get().close(); releasePut.countDown()
                await(attempt.authFinished); attempt.awaitWorker(); idle()
                a.assertUnpublishedAndRecovering()
                val queue = checkNotNull(queues.installed.get())
                assertFalse(field(queue, "accepting").getBoolean(queue))
                assertEquals(0, field(queue, "pending").getInt(queue))
                assertFalse(a.events.any { it is GroupSessionEvent.Authenticated })
                f.assertHealthy(b, bLease)
            } finally { releasePut.countDown() }
        }
    }

    @Test fun queuedMainAuthenticationCannotPublishClosedChannelOrLeaveJoiningStuck() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val a = f.newClient(); val attempt = f.join(a)
            await(attempt.authFinished)
            val queue = checkNotNull(a.ingress()[attempt.channel.get().id])
            assertEquals(1, field(queue, "pending").getInt(queue))
            attempt.channel.get().close(); attempt.awaitWorker(); idle()
            a.assertUnpublishedAndRecovering()
            assertFalse(field(queue, "accepting").getBoolean(queue))
            assertEquals(0, field(queue, "pending").getInt(queue))
            assertFalse(a.events.any { it is GroupSessionEvent.Authenticated })
            assertEquals(1, a.events.count { it is GroupSessionEvent.ControlFailed })
            f.assertHealthy(b, bLease)
        }
    }

    @Test fun revokedAdapterCannotPublishLateAuthenticationIntoHealthyReplacement() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val a = f.newClient(); val old = f.join(a, holdAuth = true)
            await(old.authEntered)
            // Network loss is a real writer input, followed by its normal recovery timer.
            a.writer.dispatch(GroupSessionEvent.NetworkLost(a.writer.snapshot.networkAttempt!!))
            f.clock = maxOf(f.clock, groupNowMs()) + 4_000; a.writer.dispatch(GroupSessionEvent.Tick)
            a.writer.dispatch(GroupSessionEvent.NetworkReady(a.writer.snapshot.networkAttempt!!))
            val replacement = a.attempts.last()
            f.finishJoin(a, replacement)
            val lease = a.writer.snapshot.local
            old.releaseAuth.countDown(); await(old.authFinished); old.awaitWorker(); idle()
            assertEquals(GroupPhase.IN_ROOM, a.writer.snapshot.phase)
            assertEquals(lease, a.writer.snapshot.local)
            assertSame(replacement.channel.get(), a.channels()[replacement.channel.get().id])
            assertFalse(a.channels().containsKey(old.channel.get().id))
            assertEquals(1, a.events.count { it is GroupSessionEvent.Authenticated })
            assertFalse(a.events.any { it is GroupSessionEvent.ControlFailed })
            val before = a.events.size
            closed(a.runtime, old.channel.get()); idle()
            assertEquals(before, a.events.size)
            assertSame(replacement.channel.get(), a.channels()[replacement.channel.get().id])
            f.assertHealthy(b, bLease)
        }
    }

    @Test fun queuedActualEncryptedControlIsRevokedAtMainWhileAnotherMemberStaysHealthy() {
        Fixture().use { f ->
            val b = f.joinHealthy(); val bLease = b.writer.snapshot.local
            val a = f.joinHealthy(); val attempt = a.attempts.last()
            val before = a.events.count { it is GroupSessionEvent.Control }
            attempt.received.clear()
            f.host.peers[a.endpoint.deviceId]!!.channel.get().send {
                GroupControlCodec.encode(GroupControl.Terminated(GroupTermination.HOST_ENDED), groupNowMs())
            }
            assertNotNull(attempt.received.poll(3, TimeUnit.SECONDS))
            val queue = checkNotNull(a.ingress()[attempt.channel.get().id])
            attempt.channel.get().close(); attempt.awaitWorker(); idle()
            assertEquals(before, a.events.count { it is GroupSessionEvent.Control })
            assertEquals(GroupPhase.RECONNECTING, a.writer.snapshot.phase)
            assertTrue(a.ingress().isEmpty())
            assertFalse(field(queue, "accepting").getBoolean(queue))
            assertEquals(0, field(queue, "pending").getInt(queue))
            f.assertHealthy(b, bLease)
        }
    }

    @Test fun immediateStopRejectsHeldActualAuthentication() = stoppedAuthentication(false)
    @Test fun flushedStopRejectsHeldActualAuthenticationImmediately() = stoppedAuthentication(true)
    private fun stoppedAuthentication(flush: Boolean) {
        Fixture().use { f ->
            val a = f.newClient(); val attempt = f.join(a, holdAuth = true)
            await(attempt.authEntered)
            if (flush) a.writer.dispatch(GroupSessionEvent.Leave)
            else a.writer.dispatch(GroupSessionEvent.Failed(a.writer.snapshot.operation!!, "fixture failure"))
            assertEquals(GroupPhase.IDLE, a.writer.snapshot.phase)
            assertTrue(field(a.runtime, "stopped").getBoolean(a.runtime))
            assertEquals(if (flush) 0 else 1, a.stopCompletions)
            attempt.releaseAuth.countDown(); await(attempt.authFinished); attempt.awaitWorker(); idle()
            assertTrue(a.channels().isEmpty()); assertTrue(a.ingress().isEmpty())
            assertFalse(a.events.any { it is GroupSessionEvent.Authenticated || it is GroupSessionEvent.ControlFailed })
            if (flush) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); assertEquals(1, a.stopCompletions) }
            attempt.assertReleased()
            a.runtime.stop(flush) { a.stopCompletions++ }; assertEquals(1, a.stopCompletions)
        }
    }

    private class Attempt(val parent: GroupSocketClient, val worker: Thread, val holdAuth: Boolean) {
        val channel = AtomicReference<GroupSocketChannel>()
        val authEntered = CountDownLatch(1); val releaseAuth = CountDownLatch(if (holdAuth) 1 else 0)
        val authFinished = CountDownLatch(1); val received = LinkedBlockingQueue<GroupControl>()
        fun awaitWorker() { worker.join(3_000); assertFalse("Actual client producer still alive", worker.isAlive) }
        fun assertReleased() {
            assertTrue(channel.get().isClosed); assertTrue((field(parent, "socket").get(parent) as Socket).isClosed)
            assertChannelReleased(channel.get())
            val timer = field(parent, "scheduler").get(parent) as ExecutorService
            assertTrue(timer.isShutdown); assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
    private class Peer {
        val channel = AtomicReference<GroupSocketChannel>()
        val authFinished = CountDownLatch(1); val joinReceived = CountDownLatch(1)
    }
    private class Fixture : AutoCloseable {
        var clock = groupNowMs()
        val failure = AtomicReference<Throwable>()
        val nodes = mutableListOf<Node>()
        var afterHostMessage: (GroupSocketChannel) -> Unit = {}
        var beforeHostAuthentication: (GroupSocketChannel) -> Unit = {}
        val host = Node(this, true).also { nodes += it; it.writer.dispatch(GroupSessionEvent.Create) }
        fun newClient() = Node(this, false).also { nodes += it; host.peers[it.endpoint.deviceId] = Peer() }
        fun join(node: Node, holdAuth: Boolean = false): Attempt {
            node.nextHoldAuth = holdAuth
            node.writer.dispatch(GroupSessionEvent.Search(host.writer.snapshot.code!!))
            node.writer.dispatch(GroupSessionEvent.Found(node.writer.snapshot.operation!!, listOf(match())))
            node.writer.dispatch(GroupSessionEvent.NetworkReady(node.writer.snapshot.networkAttempt!!))
            return node.attempts.last()
        }
        private fun match() = GroupBootstrapMatch(GroupDescriptor(host.writer.snapshot.view!!.key, host.endpoint, "host"),
            GroupNetworkDescriptor(GroupWifiCredentials("DIRECT-fixture", "12345678"), "127.0.0.1", host.server!!.localPort))
        fun joinHealthy() = newClient().also { finishJoin(it, join(it)) }
        fun finishJoin(node: Node, attempt: Attempt) {
            await(attempt.authFinished); val peer = host.peers[node.endpoint.deviceId]!!; await(peer.authFinished)
            idle(); await(peer.joinReceived); idle()
            assertNotNull("Actual AEAD Welcome missing", attempt.received.poll(3, TimeUnit.SECONDS)); idle()
            assertEquals(GroupPhase.IN_ROOM, node.writer.snapshot.phase); assertNotNull(node.writer.snapshot.local)
            assertNull(failure.get())
        }
        fun assertHealthy(node: Node, lease: GroupMemberLease?) {
            assertEquals(GroupPhase.IN_ROOM, node.writer.snapshot.phase); assertEquals(lease, node.writer.snapshot.local)
            val attempt = node.attempts.last(); assertFalse(attempt.channel.get().isClosed)
            val before = (node.writer.snapshot.view as GroupRoster).publication
            attempt.received.clear()
            val available = !host.audioAvailable
            host.writer.dispatch(GroupSessionEvent.AudioAvailable(available)); host.audioAvailable = available
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var observed: GroupRoster? = null
            while (observed == null) {
                val remaining = deadline - System.nanoTime()
                assertTrue("Healthy member lost this encrypted room update", remaining > 0)
                val control = attempt.received.poll(remaining, TimeUnit.NANOSECONDS)
                assertNotNull("Healthy member lost this encrypted room update", control)
                idle()
                val roster = (control as? GroupControl.Roster)?.value
                if (roster != null && roster.publication > before &&
                    roster.members.single { it.lease.deviceId == host.endpoint.deviceId }.audioAvailable == available) observed = roster
            }
            val accepted = node.writer.snapshot.view as GroupRoster
            assertTrue(accepted.publication >= observed.publication)
            assertEquals(available, accepted.members.single { it.lease.deviceId == host.endpoint.deviceId }.audioAvailable)
            assertEquals(lease, node.writer.snapshot.local); assertNull(failure.get())
        }
        override fun close() {
            nodes.flatMap { it.attempts }.forEach { it.releaseAuth.countDown() }
            LoopbackControlNetwork.binding = null
            nodes.forEach { runCatching { it.runtime.stop(false) {} }; it.server?.close() }
            nodes.flatMap { it.attempts }.forEach { it.parent.close(); it.awaitWorker(); it.channel.get()?.let(::assertChannelReleased) }
            host.server?.let { server ->
                val readers = field(server, "readers").get(server) as ExecutorService
                assertTrue(readers.awaitTermination(3, TimeUnit.SECONDS))
                val timers = field(server, "scheduler").get(server) as ExecutorService
                assertTrue(timers.awaitTermination(2, TimeUnit.SECONDS))
            }
            idle()
        }
    }
    private class Node(private val fixture: Fixture, val isHost: Boolean) {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val events = mutableListOf<GroupSessionEvent>(); val effects = mutableListOf<GroupSessionEffect>()
        val attempts = mutableListOf<Attempt>(); val peers = ConcurrentHashMap<String, Peer>()
        var nextHoldAuth = false; var server: GroupSocketHost? = null; var stopCompletions = 0; var audioAvailable = false
        lateinit var writer: GroupSessionOrchestrator
        val runtime = GroupNetworkRuntime(ApplicationProvider.getApplicationContext<Context>(), endpoint, { writer.snapshot }) {
            events += it; writer.dispatch(it)
        }
        init { writer = GroupSessionOrchestrator(endpoint, "rider", { maxOf(groupNowMs(), fixture.clock) },
            { check(Looper.myLooper() == Looper.getMainLooper()) }, ::effect) }
        private fun effect(effect: GroupSessionEffect) {
            effects += effect
            when (effect) {
                is GroupSessionEffect.StartHost -> {
                    lateinit var owner: GroupSocketHost
                    owner = GroupSocketHost(effect.descriptor, effect.code, InetAddress.getLoopbackAddress(), 0,
                        { current(runtime, effect.operation) && server === owner },
                        { channel -> guarded {
                            val peer = peers.computeIfAbsent(channel.context.client.deviceId) { Peer() }
                            peer.channel.set(channel)
                            fixture.beforeHostAuthentication(channel)
                            authenticate(runtime, channel, effect.operation, effect.operation); peer.authFinished.countDown()
                        } },
                        { channel, bytes -> guarded {
                            val joined = GroupControlCodec.decode(bytes, groupNowMs()) is GroupControl.Join
                            message(runtime, channel, bytes)
                            if (joined) peers[channel.context.client.deviceId]!!.joinReceived.countDown()
                            fixture.afterHostMessage(channel)
                        } }, { channel -> closed(runtime, channel) }, { fixture.failure.set(AssertionError("Actual host failed")) })
                    server = owner; field(runtime, "server").set(runtime, owner); owner.start()
                    writer.dispatch(GroupSessionEvent.HostReady(effect.operation))
                }
                is GroupSessionEffect.ConnectControl -> {
                    val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = AtomicReference<Thread>()
                    LoopbackControlNetwork.binding = { worker.set(Thread.currentThread()); entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    field(runtime, "network").set(runtime, ShadowNetwork.newInstance(77))
                    runtime.connect(effect)
                    try {
                        await(entered)
                        val parent = field(runtime, "client").get(runtime) as GroupSocketClient
                        val attempt = Attempt(parent, worker.get(), nextHoldAuth); nextHoldAuth = false; attempts += attempt
                        @Suppress("UNCHECKED_CAST") val actualAuth = field(parent, "onAuthenticated").get(parent) as (GroupSocketChannel) -> Unit
                        field(parent, "onAuthenticated").set(parent, { channel: GroupSocketChannel -> guarded {
                            attempt.channel.set(channel); attempt.authEntered.countDown(); check(attempt.releaseAuth.await(5, TimeUnit.SECONDS))
                            actualAuth(channel); attempt.authFinished.countDown()
                        } })
                        @Suppress("UNCHECKED_CAST") val actualMessage = field(parent, "onMessage").get(parent) as (GroupSocketChannel, ByteArray) -> Unit
                        field(parent, "onMessage").set(parent, { channel: GroupSocketChannel, bytes: ByteArray -> guarded {
                            val decoded = GroupControlCodec.decode(bytes, groupNowMs())
                            actualMessage(channel, bytes); attempt.received.offer(decoded)
                        } })
                    } finally { LoopbackControlNetwork.binding = null; release.countDown() }
                }
                is GroupSessionEffect.Send -> runtime.send(effect)
                is GroupSessionEffect.AdmitChannel -> runtime.admit(effect.channel)
                is GroupSessionEffect.CloseChannel -> runtime.closeChannel(effect.channel)
                is GroupSessionEffect.Stop -> guarded { runtime.stop(effect.flushTerminal) { stopCompletions++ } }
                else -> Unit // Android wireless discovery/binding and WebRTC media are outside this fixture.
            }
        }
        private fun guarded(action: () -> Unit) { try { action() } catch (cause: Throwable) { fixture.failure.compareAndSet(null, cause); throw cause } }
        @Suppress("UNCHECKED_CAST") fun channels() = field(runtime, "channels").get(runtime) as ConcurrentHashMap<UUID, GroupSocketChannel>
        @Suppress("UNCHECKED_CAST") fun ingress() = field(runtime, "ingress").get(runtime) as ConcurrentHashMap<UUID, GroupBoundedCallbacks>
        fun assertUnpublishedAndRecovering() {
            assertTrue(channels().isEmpty()); assertTrue(ingress().isEmpty())
            assertNull(field(writer, "clientChannel").get(writer)); assertNull(field(writer, "controlAttempt").get(writer))
            assertEquals(GroupPhase.RECONNECTING, writer.snapshot.phase)
            attempts.last().assertReleased(); assertNull(fixture.failure.get())
        }
    }
    companion object {
        private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun await(latch: CountDownLatch) { assertTrue("Actual producer did not reach barrier", latch.await(15, TimeUnit.SECONDS)) }
        private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
        private fun authenticate(owner: GroupNetworkRuntime, channel: GroupSocketChannel, operation: UUID, attempt: UUID) =
            owner.javaClass.getDeclaredMethod("authenticated", GroupSocketChannel::class.java, UUID::class.java, UUID::class.java)
                .apply { isAccessible = true }.invoke(owner, channel, operation, attempt)
        private fun message(owner: GroupNetworkRuntime, channel: GroupSocketChannel, bytes: ByteArray) =
            owner.javaClass.getDeclaredMethod("message", GroupSocketChannel::class.java, ByteArray::class.java)
                .apply { isAccessible = true }.invoke(owner, channel, bytes)
        private fun closed(owner: GroupNetworkRuntime, channel: GroupSocketChannel) =
            owner.javaClass.getDeclaredMethod("closed", GroupSocketChannel::class.java).apply { isAccessible = true }.invoke(owner, channel)
        private fun current(owner: GroupNetworkRuntime, attempt: UUID) =
            owner.javaClass.getDeclaredMethod("current", UUID::class.java).apply { isAccessible = true }.invoke(owner, attempt) as Boolean
        private fun assertChannelReleased(channel: GroupSocketChannel) {
            assertTrue(channel.isClosed); assertTrue((field(channel, "socket").get(channel) as Socket).isClosed)
            val secure = checkNotNull(field(channel, "secure").get(channel))
            listOf("sending", "receiving", "context").forEach { name -> assertTrue((field(secure, name).get(secure) as ByteArray).all { it == 0.toByte() }) }
            val writer = field(channel, "writer").get(channel) as ExecutorService
            assertTrue(writer.isShutdown); assertTrue(writer.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
