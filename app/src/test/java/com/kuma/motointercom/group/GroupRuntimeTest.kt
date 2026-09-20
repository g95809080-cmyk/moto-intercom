package com.kuma.motointercom.group

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kuma.motointercom.*
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
            audioFactory = { _, _, disposed -> audioCreated++; audio.also { it.disposed = disposed } },
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
}
