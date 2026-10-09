package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import com.kuma.motointercom.group.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NativeGroupAudioInstrumentationTest {
    @Test fun disposalWaitsForItsOwnRtcCleanupAndLeavesAnotherHealthyPlatformOwned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        assertFalse("Previous fixture retained audio owners: ${NativeAudioRelease.describeOwners()}", AudioPlatformOwnership.hasOwner())
        val priorRelease = NativeAudioRelease("blocked prior fixture")
        val leftRelease = NativeAudioRelease("healthy independent left")
        val rightRelease = NativeAudioRelease("healthy independent right")
        val failures = LinkedBlockingQueue<Throwable>()
        val prior = priorRelease.own(RiderAudioEngine(context, onEngineError = failures::offer, onDisposed = priorRelease::onDisposed))
        val left = leftRelease.own(RiderAudioEngine(context, onEngineError = failures::offer, onDisposed = leftRelease::onDisposed))
        val right = rightRelease.own(RiderAudioEngine(context, onEngineError = failures::offer, onDisposed = rightRelease::onDisposed))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var disposal: CompletableFuture<Void>? = null
        try {
            injectTone(left); injectTone(right)
            left.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            right.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            val connected = CountDownLatch(2)
            offerer = left.openSession(RiderMediaSessionCallbacks(
                { answerer.createAnswer(it) }, { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = failures::offer, isSessionCurrent = { true }
            ))
            answerer = right.openSession(RiderMediaSessionCallbacks(
                { offerer.setRemoteAnswer(it) }, { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = failures::offer, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val before = healthy(offerer)
            drain(prior)
            assertNotNull(field(prior, "audioDeviceModule"))
            (field(prior, "rtc") as ExecutorService).execute {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            priorRelease.observeProducers()
            prior.close()
            disposal = CompletableFuture.runAsync { priorRelease.awaitReleased() }
            assertFalse("Async close was mistaken for disposal", disposal.isDone)
            assertTrue("Blocked prior token was silently removed", priorRelease.hasOwner())
            assertTrue(leftRelease.hasOwner()); assertTrue(rightRelease.hasOwner())
            healthy(offerer, before)
            assertFalse("Disposal barrier ignored the blocked RTC owner", disposal.isDone)
            release.countDown()
            disposal.get(8, TimeUnit.SECONDS)
            assertFalse(priorRelease.hasOwner())
            assertTrue("Fixture cleanup removed another owner's token", leftRelease.hasOwner())
            assertTrue(rightRelease.hasOwner())
            healthy(offerer)
            assertNull(failures.poll())
            NativeAudioRelease.closeAll(leftRelease, rightRelease)
            assertFalse("Remaining owners: ${NativeAudioRelease.describeOwners()}", AudioPlatformOwnership.hasOwner())
        } finally {
            release.countDown()
            try { NativeAudioRelease.closeAll(priorRelease, leftRelease, rightRelease) }
            finally { disposal?.get(8, TimeUnit.SECONDS) }
        }
    }

    @Test fun realSharedCaptureFailureTerminatesCurrentGroupAndReleasesPlatform() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        assertFalse("Previous fixture retained audio owners: ${NativeAudioRelease.describeOwners()}", AudioPlatformOwnership.hasOwner())
        val stopped = CountDownLatch(1)
        val disposed = CountDownLatch(2)
        val groupRelease = NativeAudioRelease("group capture-failure")
        val peerRelease = NativeAudioRelease("group capture-failure peer")
        val failureEvents = LinkedBlockingQueue<GroupSessionEvent.Failed>()
        val otherFailures = LinkedBlockingQueue<Throwable>()
        lateinit var writer: GroupSessionOrchestrator
        lateinit var audio: GroupAudio
        instrumentation.runOnMainSync {
            writer = GroupSessionOrchestrator(GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                "group", SystemClock::elapsedRealtime, {}, { effect ->
                    if (effect is GroupSessionEffect.Stop) { audio.close(); stopped.countDown() }
                })
            writer.dispatch(GroupSessionEvent.Create)
            audio = GroupAudio(context, { writer.snapshot }, { event ->
                if (event is GroupSessionEvent.Failed) failureEvents.offer(event)
                writer.dispatch(event)
            }, { writer.snapshot.operation != null }, AudioRouteSelection.SPEAKER,
                AudioControlSettings(voxEnabled = false), {}, { groupRelease.onDisposed(); disposed.countDown() })
        }
        val operation = writer.snapshot.operation!!
        val engine = field(audio, "engine") as RiderAudioEngine
        groupRelease.own(engine)
        val peer = peerRelease.own(RiderAudioEngine(context, onEngineError = { otherFailures.offer(it) },
            onDisposed = { peerRelease.onDisposed(); disposed.countDown() }))
        val injectFailure = AtomicBoolean()
        try {
            val module = injectTone(engine)
            injectTone(peer)
            val record = field(module, "audioInput")!!
            val callback = record.javaClass.getField("motoCaptureCallback")
            val original = callback.get(record) as AudioRecordDataCallback
            val errors = field(record, "errorCallback") as JavaAudioDeviceModule.AudioRecordErrorCallback
            callback.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
                // Invoke the actual ADM error callback on its currently authorized recording thread.
                if (injectFailure.compareAndSet(true, false)) errors.onWebRtcAudioRecordError("group native read failure")
                original.onAudioDataRecorded(format, channels, rate, frame)
            })
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            val connected = CountDownLatch(2)
            offerer = engine.openSession(RiderMediaSessionCallbacks(
                { answerer.createAnswer(it) }, { answerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { otherFailures.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = peer.openSession(RiderMediaSessionCallbacks(
                { offerer.setRemoteAnswer(it) }, { offerer.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { otherFailures.offer(it) }, isSessionCurrent = { true }
            ))
            engine.resumeAudio(); offerer.createOffer()
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            healthy(offerer)
            groupRelease.observeProducers(); peerRelease.observeProducers()
            injectFailure.set(true)
            assertTrue("Shared capture failure left the group running", stopped.await(6, TimeUnit.SECONDS))
            val failure = failureEvents.poll(1, TimeUnit.SECONDS)!!
            assertEquals(operation, failure.operation)
            assertTrue(failure.message.contains("capture:read"))
            assertEquals(GroupPhase.IDLE, writer.snapshot.phase)
            assertNull(writer.snapshot.operation)
            assertNull(output(offerer).snapshot())
            peer.close()
            assertTrue("Native teardown never released the platforms", disposed.await(6, TimeUnit.SECONDS))
            groupRelease.awaitReleased(); peerRelease.awaitReleased()
            assertFalse("Remaining audio owners: ${NativeAudioRelease.describeOwners()}", AudioPlatformOwnership.hasOwner())
            assertNull(otherFailures.poll())
        } finally {
            NativeAudioRelease.closeAll(groupRelease, peerRelease) { instrumentation.runOnMainSync { audio.close() } }
        }
    }

    @Test fun sixRealPeersKeepIndependentOutputAcrossMiddleCloseMuteReplacementAndGroupResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val leftRelease = NativeAudioRelease("six peers left")
        val rightRelease = NativeAudioRelease("six peers right")
        val left = leftRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) },
            mediaMode = RiderMediaMode.GROUP, onDisposed = leftRelease::onDisposed))
        val right = rightRelease.own(RiderAudioEngine(context, onEngineError = { failures.offer(it) },
            mediaMode = RiderMediaMode.GROUP, onDisposed = rightRelease::onDisposed))
        val leftSessions = arrayOfNulls<RiderMediaSession>(3)
        val rightSessions = arrayOfNulls<RiderMediaSession>(3)
        val connected = CountDownLatch(6)
        fun pair(index: Int, muted: Boolean = false, ready: CountDownLatch = connected) {
            val leftConnected = AtomicBoolean()
            val rightConnected = AtomicBoolean()
            leftSessions[index] = left.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { rightSessions[index]!!.createAnswer(it) },
                onLocalIceCandidateGenerated = { rightSessions[index]!!.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED && leftConnected.compareAndSet(false, true)) ready.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }, initialPlaybackMuted = muted
            ))
            rightSessions[index] = right.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { leftSessions[index]!!.setRemoteAnswer(it) },
                onLocalIceCandidateGenerated = { leftSessions[index]!!.addRemoteIceCandidate(it) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED && rightConnected.compareAndSet(false, true)) ready.countDown() },
                onError = { failures.offer(it) }, isSessionCurrent = { true }
            ))
        }
        try {
            left.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            right.updateAudioControls(VersionedAudioControls(1, AudioControlSettings(voxEnabled = false)))
            val leftModule = injectTone(left)
            injectTone(right)
            repeat(3) { pair(it) }
            left.resumeAudio(); right.resumeAudio()
            leftSessions.forEach { it!!.createOffer() }
            assertTrue("Six native peers did not connect: ${failures.peek()}", connected.await(20, TimeUnit.SECONDS))
            (leftSessions + rightSessions).forEach { healthy(it!!) }
            leftRelease.observeProducers(); rightRelease.observeProducers()
            val capture = NativeCaptureDiagnostics.currentProducer(leftModule, true)!!
            val initialPeers = leftSessions.map { field(it!!, "peerConnection") }
            val initialOutputs = leftSessions.map { output(it!!).snapshot()!! }

            // One member's renderer cannot borrow another member's output proof.
            output(leftSessions[1]!!).pause()
            assertFalse(evidence(leftSessions[1]!!).audioIoEnabled)
            listOf(0, 2).forEach { healthy(leftSessions[it]!!) }
            leftSessions[1]!!.close(); rightSessions[1]!!.close()
            drain(left); drain(right)
            assertSame("Middle close revoked shared capture", capture, NativeCaptureDiagnostics.currentProducer(leftModule, true))
            listOf(0, 2).forEach { index ->
                val current = healthy(leftSessions[index]!!)
                assertSame(initialPeers[index], field(leftSessions[index]!!, "peerConnection"))
                val device = output(leftSessions[index]!!).snapshot()!!
                assertSame(initialOutputs[index].thread, device.thread)
                assertEquals(initialOutputs[index].sessionId, device.sessionId)
                assertTrue("Healthy peer stopped writing", device.writtenBytes > initialOutputs[index].writtenBytes)
                assertTrue(current.counters.sent > 0 && current.counters.received > 0)
            }

            // A blocked replacement is silent in the actual PCM passed to Android.
            val replacementConnected = CountDownLatch(2)
            pair(1, muted = true, ready = replacementConnected)
            leftSessions[1]!!.createOffer()
            assertTrue(replacementConnected.await(15, TimeUnit.SECONDS))
            healthy(leftSessions[1]!!)
            assertEquals(0L, output(leftSessions[1]!!).snapshot()!!.nonzeroWrittenBytes)
            val before = leftSessions.map { healthy(it!!) }
            val hotPeers = leftSessions.map { field(it!!, "peerConnection") }
            val oldOutputs = leftSessions.map { output(it!!).snapshot()!! }
            leftRelease.observeProducers(); rightRelease.observeProducers()
            left.suspendAudio()
            leftSessions.forEach { assertNull(output(it!!).snapshot()) }
            left.resumeAudio()
            leftSessions.forEachIndexed { index, session ->
                val after = healthy(session!!, before[index])
                assertSame("Pause replaced a healthy PeerConnection", hotPeers[index], field(session, "peerConnection"))
                val device = output(session).snapshot()!!
                assertNotSame(oldOutputs[index].thread, device.thread)
                assertNotEquals(oldOutputs[index].sessionId, device.sessionId)
                assertTrue(after.counters.sent > before[index].counters.sent && after.counters.received > before[index].counters.received)
            }
            assertNotSame("Resume reused revoked capture thread", capture, NativeCaptureDiagnostics.currentProducer(leftModule, true))
            assertEquals(0L, output(leftSessions[1]!!).snapshot()!!.nonzeroWrittenBytes)
            leftSessions[1]!!.setPlaybackMuted(false)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (output(leftSessions[1]!!).snapshot()?.nonzeroWrittenBytes == 0L && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
            assertTrue("Unblocking never reached actual output", output(leftSessions[1]!!).snapshot()!!.nonzeroWrittenBytes > 0)
            assertNull(failures.poll())

            // Main is already handling recovery when a failed old worker posts its error.
            val mainEntered = CountDownLatch(1)
            val restore = CountDownLatch(1)
            val restored = CountDownLatch(1)
            val failedOutput = output(leftSessions[1]!!).snapshot()!!
            leftRelease.observeProducers(); rightRelease.observeProducers()
            Handler(Looper.getMainLooper()).post {
                mainEntered.countDown()
                restore.await(5, TimeUnit.SECONDS)
                left.suspendAudio(); left.resumeAudio(); restored.countDown()
            }
            try {
                assertTrue(mainEntered.await(2, TimeUnit.SECONDS))
                output(leftSessions[1]!!).onData(ByteBuffer.allocate(1), 16, 48_000, 1, 480, 0)
                failedOutput.thread.join(2_000)
                assertFalse("Failed output worker did not return", failedOutput.thread.isAlive)
            } finally { restore.countDown() }
            assertTrue(restored.await(3, TimeUnit.SECONDS))
            leftSessions.forEachIndexed { index, session ->
                healthy(session!!)
                assertSame(hotPeers[index], field(session, "peerConnection"))
            }
            assertNull("Stale output error closed a recovered peer", failures.poll(200, TimeUnit.MILLISECONDS))
            // A current failure still reaches its member callback; it cannot be silently discarded.
            output(leftSessions[1]!!).onData(ByteBuffer.allocate(1), 16, 48_000, 1, 480, 0)
            assertTrue(failures.poll(3, TimeUnit.SECONDS)?.message.orEmpty().contains("Truncated"))
            listOf(0, 2).forEach { healthy(leftSessions[it]!!) }
            assertEquals(false, field(left, "captureFailed").let { (it as AtomicBoolean).get() })
            assertNull(failures.poll())
        } finally { NativeAudioRelease.closeAll(leftRelease, rightRelease) }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run { isAccessible = true; get(target) }
    private fun output(session: RiderMediaSession) = field(session, "playout") as DecodedAudioPlayout
    private fun drain(engine: RiderAudioEngine) = (field(engine, "rtc") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    private fun evidence(session: RiderMediaSession): RiderMediaEvidence {
        val queue = LinkedBlockingQueue<RiderMediaEvidence>()
        session.queryEvidence { if (it != null) queue.offer(it) }
        return queue.poll(2, TimeUnit.SECONDS) ?: error("No evidence from current native peer")
    }
    private fun healthy(session: RiderMediaSession, before: RiderMediaEvidence? = null): RiderMediaEvidence {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        do {
            val evidence = evidence(session)
            if (evidence.audioIoEnabled && evidence.counters.sent > (before?.counters?.sent ?: 0) &&
                evidence.counters.received > (before?.counters?.received ?: 0)) return evidence
            SystemClock.sleep(20)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Peer did not prove fresh capture, its own output and bidirectional RTP")
    }
    private fun injectTone(engine: RiderAudioEngine): JavaAudioDeviceModule {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (field(engine, "audioDeviceModule") == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        val module = field(engine, "audioDeviceModule") as JavaAudioDeviceModule
        val record = field(module, "audioInput")!!
        val callback = record.javaClass.getField("motoCaptureCallback")
        val original = callback.get(record) as AudioRecordDataCallback
        callback.set(record, AudioRecordDataCallback { format, channels, rate, frame ->
            repeat(frame.capacity() / 2) { index ->
                val sample = if (index % 16 < 8) 8_192 else -8_192
                frame.put(index * 2, sample.toByte()); frame.put(index * 2 + 1, (sample shr 8).toByte())
            }
            original.onAudioDataRecorded(format, channels, rate, frame)
        })
        return module
    }
}
