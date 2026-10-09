package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuma.motointercom.group.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.audio.AudioRecordDataCallback
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeGroupAudioAdapterTest {
    @Test fun productionPollAndWriterDowngradeProofWithoutReplacingHealthyPeers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val failures = LinkedBlockingQueue<Throwable>()
        val groupRelease = NativeAudioRelease("group adapter")
        val rightRelease = NativeAudioRelease("group adapter peer")
        val right = rightRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) }, mediaMode = RiderMediaMode.GROUP,
            initialAudioControls = VersionedAudioControls(0, AudioControlSettings(voxEnabled = false)), onDisposed = rightRelease::onDisposed))
        lateinit var writer: GroupSessionOrchestrator
        lateinit var audio: GroupAudio
        var audioCreated = false
        val pending = mutableListOf<GroupSessionEffect.OpenMedia>()
        val closed = mutableListOf<GroupMediaLease>()
        val rightSessions = mutableMapOf<String, RiderMediaSession>()
        val id = { n: Long -> UUID(0, n).toString() }
        try {
            instrumentation.runOnMainSync {
                writer = GroupSessionOrchestrator(GroupAuthEndpoint(id(1), id(101)), "host", SystemClock::elapsedRealtime, {}, { effect ->
                    when (effect) {
                        is GroupSessionEffect.OpenMedia -> pending += effect
                        is GroupSessionEffect.CloseMedia -> { closed += effect.lease; audio.close(effect.lease) }
                        else -> Unit
                    }
                })
                writer.dispatch(GroupSessionEvent.Create)
                writer.dispatch(GroupSessionEvent.HostReady(writer.snapshot.operation!!))
                // Supply an admitted room fixture. Authentication and radio transport are tested separately.
                var room = writer.snapshot.view as GroupRoom
                val now = SystemClock.elapsedRealtime()
                for (n in 2L..4L) room = room.reduce(room.key, GroupEvent.Join(
                    GroupJoinProof(id(n), id(n + 100), id(n + 200), true, true, true, now + 5_000)), now).room
                room.activePairs.forEach { pair ->
                    val a = room.members.single { it.lease.deviceId == pair.first }.lease
                    val b = room.members.single { it.lease.deviceId == pair.second }.lease
                    room = room.reduce(room.key, GroupEvent.RestartLink(a, b), now).room
                }
                room.members.filter { it.lease.deviceId != id(1) }.forEach { member ->
                    room = room.reduce(room.key, GroupEvent.AudioAvailability(member.lease, true), now).room
                }
                room.links.toList().forEach { link ->
                    room.members.filter { it.lease.deviceId != id(1) && it.lease.deviceId in listOf(link.lease.pair.first, link.lease.pair.second) }.forEach { member ->
                        room = room.reduce(room.key, GroupEvent.ConfirmLink(member.lease, link.lease), now).room
                    }
                }
                setField(writer, "hostRoom", room)
                writer.dispatch(GroupSessionEvent.Tick)
                assertEquals(3, pending.size)
                audio = GroupAudio(context, { writer.snapshot }, { event ->
                    writer.dispatch(event)
                    if (event is GroupSessionEvent.Outgoing) when (val message = event.message) {
                        is GroupMessage.Offer -> rightSessions.getValue(event.lease.peer.deviceId).createAnswer(
                            JSONObject().put("type", "offer").put("sdp", message.sdp).toString())
                        is GroupMessage.Candidate -> rightSessions.getValue(event.lease.peer.deviceId).addRemoteIceCandidate(
                            JSONObject().put("sdpMid", message.mid).put("sdpMLineIndex", message.line).put("candidate", message.candidate).toString())
                        else -> Unit
                    }
                }, { writer.snapshot.operation != null }, AudioRouteSelection.SPEAKER,
                    AudioControlSettings(voxEnabled = false), {}, groupRelease::onDisposed)
                audioCreated = true
                groupRelease.own(field(audio, "engine") as RiderAudioEngine)
                pending.forEach { effect ->
                    val lease = effect.lease
                    rightSessions[lease.peer.deviceId] = right.openSession(RiderMediaSessionCallbacks(
                        { json -> audio.signal(lease, GroupMessage.Answer(lease.link, JSONObject(json).getString("sdp"))) },
                        { json -> val obj = JSONObject(json); audio.signal(lease, GroupMessage.Candidate(lease.link,
                            obj.getString("sdpMid"), obj.getInt("sdpMLineIndex"), obj.getString("candidate"))) },
                        onError = { failures.offer(it) }, isSessionCurrent = { true }
                    ))
                }
            }
            injectTone(field(audio, "engine") as RiderAudioEngine); injectTone(right)
            instrumentation.runOnMainSync { pending.forEach { audio.open(it.lease, it.offerer) }; right.resumeAudio() }
            await(15_000) { instrumentation.runOnMainSync { audio.poll() }; writer.snapshot.voiceReady }
            groupRelease.observeProducers(); rightRelease.observeProducers()
            val engine = field(audio, "engine") as RiderAudioEngine
            @Suppress("UNCHECKED_CAST")
            val sessions = (field(engine, "activeSessionSnapshot") as List<RiderMediaSession>).toList()
            assertEquals(3, sessions.size)
            val hotPeers = sessions.map { field(it, "peerConnection") }
            val outputs = sessions.map { (field(it, "playout") as DecodedAudioPlayout).snapshot()!! }
            val links = writer.snapshot.view!!.links.map { it.lease }
            val gate = (field(engine, "audioIoGate") as AudioIoGate).revision()
            val paused = field(sessions[0], "playout") as DecodedAudioPlayout
            instrumentation.runOnMainSync { paused.pause(); audio.poll() }
            assertFalse(writer.snapshot.voiceReady)
            repeat(3) { instrumentation.runOnMainSync { audio.poll() }; SystemClock.sleep(50) }
            assertFalse("Healthy peer proof hid the paused member", writer.snapshot.voiceReady)
            assertEquals(links, writer.snapshot.view!!.links.map { it.lease })
            assertTrue("Poll failure rebuilt healthy media", closed.isEmpty())
            listOf(1, 2).forEach { index ->
                assertSame(hotPeers[index], field(sessions[index], "peerConnection"))
                val current = (field(sessions[index], "playout") as DecodedAudioPlayout).snapshot()!!
                assertSame(outputs[index].thread, current.thread)
                assertEquals(outputs[index].sessionId, current.sessionId)
                assertTrue(current.writtenBytes > outputs[index].writtenBytes)
            }
            instrumentation.runOnMainSync { paused.resume(gate) }
            assertFalse("Requested resume reused old pair proof", writer.snapshot.voiceReady)
            await(8_000) { instrumentation.runOnMainSync { audio.poll() }; writer.snapshot.voiceReady }
            assertEquals(links, writer.snapshot.view!!.links.map { it.lease })
            assertTrue(closed.isEmpty())
            assertNull(failures.poll())
        } finally {
            try {
                if (audioCreated) NativeAudioRelease.closeAll(groupRelease, rightRelease) {
                    instrumentation.runOnMainSync { audio.close() }
                }
                else NativeAudioRelease.closeAll(rightRelease)
            } finally { activity.close() }
        }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name).run { isAccessible = true; set(target, value) }
    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Native GroupAudio/actor did not become ready" }; SystemClock.sleep(60) }
    }
    private fun injectTone(engine: RiderAudioEngine) {
        await(5_000) { field(engine, "audioDeviceModule") != null }
        val record = field(field(engine, "audioDeviceModule")!!, "audioInput")!!
        val callback = record.javaClass.getField("motoCaptureCallback")
        val original = callback.get(record) as AudioRecordDataCallback
        callback.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
            repeat(frame.capacity() / 2) { index ->
                val sample = if (index % 16 < 8) 8_192 else -8_192
                frame.put(index * 2, sample.toByte()); frame.put(index * 2 + 1, (sample shr 8).toByte())
            }
            original.onAudioDataRecorded(format, channels, rate, frame)
        })
    }
}
