package com.kuma.motointercom.group

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupRuntimeTest {
    @After fun resetOwnershipForTestIsolation() {
        GroupRuntimeOwnership::class.java.getDeclaredField("owner").apply {
            isAccessible = true
            set(GroupRuntimeOwnership, null)
        }
    }

    private class Network : GroupNetworkEffects {
        lateinit var dispatch: (GroupSessionEvent) -> Unit
        var release: (() -> Unit)? = null
        val sent = mutableListOf<GroupSessionEffect.Send>()
        val admitted = mutableListOf<UUID>()
        var flush = false
        override fun host(attempt: UUID, descriptor: GroupDescriptor, code: GroupJoinCode) { dispatch(GroupSessionEvent.HostReady(attempt)) }
        override fun search(effect: GroupSessionEffect.Search) = Unit
        override fun join(effect: GroupSessionEffect.JoinNetwork) = Unit
        override fun connect(effect: GroupSessionEffect.ConnectControl) = Unit
        override fun send(effect: GroupSessionEffect.Send) { sent += effect }
        override fun closeChannel(id: UUID) = Unit
        override fun admit(id: UUID) { admitted += id }
        override fun stop(flush: Boolean, done: () -> Unit) { this.flush = flush; release = done }
    }
    private class Audio : GroupAudioEffects {
        lateinit var disposed: () -> Unit
        val opened = mutableListOf<GroupMediaLease>()
        val removed = mutableListOf<GroupMediaLease>()
        var mute = false
        var closed = false
        override fun open(lease: GroupMediaLease, offerer: Boolean) { opened += lease }
        override fun close(lease: GroupMediaLease) { removed += lease }
        override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
        override fun mute(value: Boolean) { mute = value }
        override fun vox(value: Boolean) = Unit
        override fun block(peer: String, value: Boolean) = Unit
        override fun selectRoute(value: AudioRouteSelection) = Unit
        override fun poll() = Unit
        override fun close() { closed = true }
    }
    @Test fun productionExecutorDefersAudioUntilAdmissionAndWaitsForBothNativeAndNetworkCleanup() {
        val network = Network(); val audio = Audio()
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        var audioCreated = 0; var keepAliveClosed = false; var released = 0
        val runtime = GroupRuntime(ApplicationProvider.getApplicationContext<Context>(), endpoint, "host",
            AudioRouteSelection.SPEAKER, AudioControlSettings(), {}, {}, { released++ },
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { _, _, lifetime -> audioCreated++; audio.also { it.disposed = lifetime::disposed } },
            acquireKeepAlive = { Closeable { keepAliveClosed = true } }, busyPorts = emptyList())
        try {
            runtime.start(null)
            assertEquals(GroupPhase.IN_ROOM, runtime.snapshot.phase)
            assertEquals(0, audioCreated)
            val id = UUID.randomUUID()
            runtime.writer.dispatch(GroupSessionEvent.Authenticated(runtime.snapshot.operation!!, id,
                GroupAuthContext(runtime.snapshot.view!!.key, endpoint,
                    GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString()), UUID.randomUUID().toString()), groupNowMs()))
            assertEquals(0, audioCreated)
            runtime.writer.dispatch(GroupSessionEvent.Control(id, GroupControl.Join("member")))
            assertEquals(1, audioCreated); assertEquals(1, audio.opened.size); assertEquals(listOf(id), network.admitted)
            runtime.writer.dispatch(GroupSessionEvent.Mute(true)); assertTrue(audio.mute)
            runtime.close()
            assertTrue(audio.closed); assertTrue(network.flush)
            assertTrue(network.sent.any { it.terminal })
            network.release!!.invoke()
            assertEquals(0, released); assertTrue(GroupRuntimeOwnership.hasOwner()); assertFalse(keepAliveClosed)
            audio.disposed()
            assertEquals(1, released); assertFalse(GroupRuntimeOwnership.hasOwner()); assertTrue(keepAliveClosed)
            audio.disposed(); assertEquals(1, released)
        } finally {
            runtime.close(); network.release?.invoke(); if (audioCreated > 0) audio.disposed()
        }
    }
    private fun admit(runtime: GroupRuntime, endpoint: GroupAuthEndpoint) {
        val channel = UUID.randomUUID()
        runtime.dispatch(GroupSessionEvent.Authenticated(runtime.snapshot.operation!!, channel,
            GroupAuthContext(runtime.snapshot.view!!.key, endpoint,
                GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                UUID.randomUUID().toString()), groupNowMs()))
        runtime.dispatch(GroupSessionEvent.Control(channel, GroupControl.Join("member")))
    }

    @Test fun failedAudioConstructionWaitsForItsNativeDisposalBeforeReleasingOwnership() {
        val network = Network()
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        var pending: GroupAudioLifetime? = null
        var released = 0
        val runtime = GroupRuntime(ApplicationProvider.getApplicationContext<Context>(), endpoint, "host",
            AudioRouteSelection.SPEAKER, AudioControlSettings(), {}, {}, { released++ },
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { _, _, lifetime ->
                pending = lifetime
                lifetime.acquired()
                throw IllegalStateException("route construction failed after engine creation")
            }, acquireKeepAlive = { Closeable {} }, busyPorts = emptyList())
        try {
            runtime.start(null)
            admit(runtime, endpoint)
            assertNotNull(pending)
            runtime.close()
            network.release!!.invoke()
            assertEquals(0, released)
            assertTrue(GroupRuntimeOwnership.hasOwner())
            pending!!.disposed()
            assertEquals(1, released)
            assertFalse(GroupRuntimeOwnership.hasOwner())
        } finally {
            runtime.close()
            network.release?.invoke()
            pending?.disposed()
        }
    }

    @Test fun oldFailedConstructionDisposalCannotReleaseAReplacementAudioLease() {
        val network = Network()
        val audio = Audio()
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        var old: GroupAudioLifetime? = null
        var constructions = 0
        var released = 0
        val runtime = GroupRuntime(ApplicationProvider.getApplicationContext<Context>(), endpoint, "host",
            AudioRouteSelection.SPEAKER, AudioControlSettings(), {}, {}, { released++ },
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { _, _, lifetime ->
                constructions++
                if (old == null) {
                    old = lifetime
                    lifetime.acquired()
                    throw IllegalStateException("first route failed")
                }
                audio.also { it.disposed = lifetime::disposed }
            }, acquireKeepAlive = { Closeable {} }, busyPorts = emptyList())
        try {
            runtime.start(null)
            admit(runtime, endpoint)
            admit(runtime, endpoint)
            assertEquals(1, constructions)
            assertTrue(audio.opened.isEmpty())
            old!!.disposed()
            admit(runtime, endpoint)
            assertEquals(2, constructions)
            assertFalse(audio.opened.isEmpty())
            runtime.close()
            network.release!!.invoke()
            old!!.disposed()
            assertEquals(0, released)
            assertTrue(GroupRuntimeOwnership.hasOwner())
            audio.disposed()
            assertEquals(1, released)
        } finally {
            runtime.close()
            network.release?.invoke()
            old?.disposed()
            if (audio.opened.isNotEmpty()) audio.disposed()
        }
    }

    @Test fun audioFailureBeforeResourceAllocationDoesNotHoldTheRuntimeForever() {
        val network = Network()
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        var released = 0
        val runtime = GroupRuntime(ApplicationProvider.getApplicationContext<Context>(), endpoint, "host",
            AudioRouteSelection.SPEAKER, AudioControlSettings(), {}, {}, { released++ },
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { _, _, _ -> throw IllegalStateException("engine allocation failed") },
            acquireKeepAlive = { Closeable {} }, busyPorts = emptyList())
        try {
            runtime.start(null)
            admit(runtime, endpoint)
            runtime.close()
            network.release!!.invoke()
            assertEquals(1, released)
            assertFalse(GroupRuntimeOwnership.hasOwner())
        } finally {
            runtime.close()
            network.release?.invoke()
        }
    }

    @Test fun currentNetworkRefreshCannotSwitchToAnotherSameCodeRoom() {
        val local = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val host = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val effects = mutableListOf<GroupSessionEffect>()
        val writer = GroupSessionOrchestrator(local, "member", { 0 }, {}, effects::add)
        writer.dispatch(GroupSessionEvent.Search(GroupJoinCode("123456")))
        fun match(room: String, pass: String) = GroupBootstrapMatch(GroupDescriptor(GroupRoomKey(room, host.runtimeId), host, "host"),
            GroupNetworkDescriptor(com.kuma.motointercom.group.network.GroupWifiCredentials("DIRECT-test", pass), "192.168.49.1", 8899))
        val roomId = UUID.randomUUID().toString(); val original = match(roomId, "12345678")
        writer.dispatch(GroupSessionEvent.Found(writer.snapshot.operation!!, listOf(original)))
        val attempt = writer.snapshot.networkAttempt!!
        writer.dispatch(GroupSessionEvent.NetworkRefreshed(attempt, match(UUID.randomUUID().toString(), "87654321")))
        writer.dispatch(GroupSessionEvent.NetworkRefreshed(UUID.randomUUID(), match(roomId, "87654321")))
        writer.dispatch(GroupSessionEvent.NetworkReady(attempt))
        assertSame(original, effects.filterIsInstance<GroupSessionEffect.ConnectControl>().single().match)
    }

    @Test fun closeMediaFailureAcrossOrchestratorRuntimeAndMediaControllerBlocksNewChannelOpenAndRetriesClose() {
        class FailingSession : RiderMediaSession {
            var closes = 0
            var closeThrows = false
            override fun createOffer() = Unit
            override fun createAnswer(remoteSdpJson: String) = Unit
            override fun setRemoteAnswer(remoteSdpJson: String) = Unit
            override fun addRemoteIceCandidate(candidateJson: String) = Unit
            override fun setPlaybackMuted(muted: Boolean) = Unit
            override fun close() {
                closes++
                if (closeThrows) throw IllegalStateException("playout close failed")
            }
        }
        class RecordingEngine : RiderMediaEngine {
            val sessions = mutableListOf<FailingSession>()
            override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession =
                FailingSession().also(sessions::add)
            override fun updateAudioControls(controls: VersionedAudioControls) = Unit
            override fun suspendAudio() = Unit
            override fun resumeAudio() = Unit
            override fun close() = Unit
        }

        val network = Network()
        val engine = RecordingEngine()
        var demandEnds = 0
        var audioClosed = false
        var audioLifetime: GroupAudioLifetime? = null
        val openedPeers = mutableListOf<String>()
        val hostEndpoint = GroupAuthEndpoint(UUID(0, 1).toString(), UUID(0, 11).toString())
        val officerEndpoint = GroupAuthEndpoint(UUID(0, 2).toString(), UUID(0, 12).toString())
        val memberEndpoint = GroupAuthEndpoint(UUID(0, 3).toString(), UUID(0, 13).toString())

        val runtime = GroupRuntime(
            ApplicationProvider.getApplicationContext<Context>(),
            hostEndpoint,
            "host",
            AudioRouteSelection.SPEAKER,
            AudioControlSettings(),
            {},
            {},
            {},
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { snapshotProvider, _, lifetime ->
                audioLifetime = lifetime
                val controller = GroupMediaController(
                    engine,
                    { snapshotProvider().allows(it) },
                    {},
                    { demandEnds++ }
                )
                object : GroupAudioEffects {
                    override fun open(lease: GroupMediaLease, offerer: Boolean) {
                        openedPeers += lease.peer.deviceId
                        controller.open(
                            lease,
                            RiderMediaSessionCallbacks({}, {}, isSessionCurrent = { snapshotProvider().allows(lease) })
                        )
                    }
                    override fun close(lease: GroupMediaLease) = controller.close(lease)
                    override fun signal(lease: GroupMediaLease, message: GroupMessage) = Unit
                    override fun mute(value: Boolean) = Unit
                    override fun vox(value: Boolean) = Unit
                    override fun block(peer: String, value: Boolean) = controller.block(peer, value)
                    override fun selectRoute(value: AudioRouteSelection) = Unit
                    override fun poll() = Unit
                    override fun close() {
                        audioClosed = true
                        try {
                            controller.close()
                        } finally {
                            lifetime.disposed()
                        }
                    }
                }
            },
            acquireKeepAlive = { Closeable {} },
            busyPorts = emptyList()
        )
        try {
            runtime.start(null)
            val officerChannel = UUID.randomUUID()
            runtime.dispatch(
                GroupSessionEvent.Authenticated(
                    runtime.snapshot.operation!!,
                    officerChannel,
                    GroupAuthContext(runtime.snapshot.view!!.key, hostEndpoint, officerEndpoint, UUID.randomUUID().toString()),
                    groupNowMs()
                )
            )
            runtime.dispatch(GroupSessionEvent.Control(officerChannel, GroupControl.Join("officer")))
            val memberChannel = UUID.randomUUID()
            runtime.dispatch(
                GroupSessionEvent.Authenticated(
                    runtime.snapshot.operation!!,
                    memberChannel,
                    GroupAuthContext(runtime.snapshot.view!!.key, hostEndpoint, memberEndpoint, UUID.randomUUID().toString()),
                    groupNowMs()
                )
            )
            runtime.dispatch(GroupSessionEvent.Control(memberChannel, GroupControl.Join("member")))
            assertEquals(2, engine.sessions.size)

            runtime.dispatch(GroupSessionEvent.SetRole(officerEndpoint.deviceId, GroupRole.RIDING_OFFICER))
            val officerLease = runtime.snapshot.view!!.members.single { it.lease.deviceId == officerEndpoint.deviceId }.lease
            val hostLease = runtime.snapshot.local!!
            runtime.dispatch(
                GroupSessionEvent.Control(
                    officerChannel,
                    GroupControl.Signal(GroupFrame(runtime.snapshot.view!!.key, officerLease, hostLease, 1, GroupMessage.SwitchChannel(GroupChannel.Rider)))
                )
            )
            assertEquals(1, engine.sessions[0].closes)

            val memberSession = engine.sessions[1]
            memberSession.closeThrows = true
            val opensBeforeSwitch = openedPeers.size

            runtime.dispatch(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))

            assertEquals(opensBeforeSwitch, openedPeers.size)
            assertEquals(2, engine.sessions.size)
            assertTrue(audioClosed)
            assertEquals(2, memberSession.closes)
            assertEquals(1, demandEnds)
            assertEquals(GroupPhase.IDLE, runtime.snapshot.phase)
            assertEquals(GroupTerminalReason.FAILED, runtime.snapshot.terminalReason)
            assertTrue(network.flush)
            assertTrue(network.sent.any { it.terminal })
        } finally {
            runtime.close()
            network.release?.invoke()
            audioLifetime?.disposed()
        }
    }

    @Test fun audioDisposeFailureReleasesKeepAliveAndServiceWhileKeepingRuntimeOwnershipLocked() {
        val network = Network()
        val audio = Audio()
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        var lifetimeRef: GroupAudioLifetime? = null
        var keepAliveClosed = false
        var released = 0
        val runtime = GroupRuntime(
            ApplicationProvider.getApplicationContext<Context>(),
            endpoint,
            "host",
            AudioRouteSelection.SPEAKER,
            AudioControlSettings(),
            {},
            {},
            { released++ },
            networkFactory = { _, dispatch -> network.also { it.dispatch = dispatch } },
            audioFactory = { _, _, lifetime ->
                lifetimeRef = lifetime
                audio.also { it.disposed = lifetime::disposed }
            },
            acquireKeepAlive = { Closeable { keepAliveClosed = true } },
            busyPorts = emptyList()
        )
        try {
            runtime.start(null)
            admit(runtime, endpoint)
            assertNotNull(lifetimeRef)
            runtime.close()
            network.release!!.invoke()
            assertEquals(0, released)
            assertFalse(keepAliveClosed)
            assertTrue(GroupRuntimeOwnership.hasOwner())

            lifetimeRef!!.disposeFailed(IllegalStateException("peer dispose failed"))
            assertEquals(1, released)
            assertTrue(keepAliveClosed)
            assertTrue(GroupRuntimeOwnership.hasOwner())
        } finally {
            GroupRuntimeOwnership::class.java.getDeclaredField("owner").apply {
                isAccessible = true
                set(GroupRuntimeOwnership, null)
            }
        }
    }
}
