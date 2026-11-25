package com.kuma.motointercom

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.PeerConnection
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RiderAudioEngineGroupOwnershipTest {
    private class RtcQueue : AbstractExecutorService() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            queue.add(command)
        }
        fun drain() {
            while (true) (queue.poll() ?: return).run()
        }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return mutableListOf<Runnable>().also { pending ->
                while (true) pending += queue.poll() ?: break
            }
        }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    private fun callbacks(current: () -> Boolean = { true }) = RiderMediaSessionCallbacks(
        onLocalSdpGenerated = {}, onLocalIceCandidateGenerated = {}, isSessionCurrent = current)

    private fun gate(engine: RiderAudioEngine) = RiderAudioEngine::class.java
        .getDeclaredField("audioIoGate").apply { isAccessible = true }.get(engine) as AudioIoGate

    private fun finish(engine: RiderAudioEngine, rtc: RtcQueue) {
        engine.close()
        rtc.drain()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun groupAllowsThreePeersAndClosingOneOnlyReleasesItsOwnCapacity() {
        val rtc = RtcQueue()
        var disposed = 0
        val engine = RiderAudioEngine(ApplicationProvider.getApplicationContext(), mediaMode = RiderMediaMode.GROUP,
            onDisposed = { disposed++ }, rtc = rtc)
        try {
            val first = engine.openSession(callbacks())
            val second = engine.openSession(callbacks())
            val third = engine.openSession(callbacks())
            assertThrows(IllegalStateException::class.java) { engine.openSession(callbacks()) }
            second.close()
            val replacement = engine.openSession(callbacks())
            second.close()
            assertThrows(IllegalStateException::class.java) { engine.openSession(callbacks()) }
            first.close()
            third.close()
            replacement.close()
        } finally { finish(engine, rtc) }
        engine.close()
        assertEquals(1, disposed)
    }

    @Test fun singleModeStillOnlyAllowsOnePeer() {
        val rtc = RtcQueue()
        val engine = RiderAudioEngine(ApplicationProvider.getApplicationContext(), rtc = rtc)
        try {
            val first = engine.openSession(callbacks())
            assertThrows(IllegalStateException::class.java) { engine.openSession(callbacks()) }
            first.close()
            engine.openSession(callbacks())
        } finally { finish(engine, rtc) }
    }

    @Test fun closingOnePeerKeepsSharedAudioGrantAndLastPeerRevokesTheOldGrant() {
        val rtc = RtcQueue()
        val engine = RiderAudioEngine(ApplicationProvider.getApplicationContext(), mediaMode = RiderMediaMode.GROUP, rtc = rtc)
        try {
            val first = engine.openSession(callbacks())
            val second = engine.openSession(callbacks())
            val third = engine.openSession(callbacks())
            engine.resumeAudio()
            val audioGate = gate(engine)
            val grant = audioGate.revision()
            first.close()
            assertEquals(grant, audioGate.revision())
            assertTrue(audioGate.allows(grant))
            engine.suspendAudio()
            assertFalse(audioGate.allows(grant))
            engine.resumeAudio()
            val resumed = audioGate.revision()
            assertTrue(audioGate.allows(resumed))
            second.close()
            assertTrue(audioGate.allows(resumed))
            third.close()
            assertFalse(audioGate.allows(resumed))
        } finally { finish(engine, rtc) }
    }

    @Test fun nativeReceiptsReturnWhileBusinessLockIsHeldAndCannotDeliverAfterClose() {
        val rtc = RtcQueue()
        val businessLock = Any()
        val predicateCalls = AtomicInteger()
        val states = mutableListOf<PeerConnection.PeerConnectionState>()
        val engine = RiderAudioEngine(ApplicationProvider.getApplicationContext(),
            isRuntimeCurrent = { synchronized(businessLock) { predicateCalls.incrementAndGet(); true } },
            mediaMode = RiderMediaMode.GROUP, rtc = rtc)
        val session = engine.openSession(callbacks {
            synchronized(businessLock) { predicateCalls.incrementAndGet(); true }
        }.copy(onConnectionStateChanged = states::add))
        val observer = RiderAudioEngine::class.java.getDeclaredMethod("observer", session.javaClass)
            .apply { isAccessible = true }.invoke(engine, session) as PeerConnection.Observer
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                listOf(PeerConnection.PeerConnectionState.CONNECTED, PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.CONNECTED, PeerConnection.PeerConnectionState.FAILED)
                    .forEach(observer::onConnectionChange)
            } catch (error: Throwable) {
                failure.set(error)
            } finally { returned.countDown() }
        }
        try {
            synchronized(businessLock) {
                val before = predicateCalls.get()
                worker.start()
                assertTrue("native receipt waited for a business lock", returned.await(2, TimeUnit.SECONDS))
                assertEquals(before, predicateCalls.get())
            }
            failure.get()?.let { throw AssertionError(it) }
            session.close()
        } finally {
            worker.join(3_000)
            finish(engine, rtc)
        }
        assertTrue(states.isEmpty())
    }

    @Test fun closeFailureInvokesOnDisposeFailedAndNeverCallsOnDisposed() {
        val rtc = RtcQueue()
        var disposed = 0
        var disposeFailed = 0
        val engine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(),
            mediaMode = RiderMediaMode.GROUP,
            onDisposed = { disposed++ },
            onDisposeFailed = { disposeFailed++ },
            rtc = rtc
        )
        // Pre-shutdown the executor so close() cannot complete native teardown on the RTC thread.
        rtc.shutdown()
        engine.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, disposed)
        assertEquals(1, disposeFailed)
    }

    @Test fun closePlatformCleanupFailureInvokesOnDisposeFailedAndNeverCallsOnDisposed() {
        val rtc = RtcQueue()
        var disposed = 0
        val failures = mutableListOf<Throwable>()
        val engine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(),
            mediaMode = RiderMediaMode.GROUP,
            onDisposed = { disposed++ },
            onDisposeFailed = { failures += it },
            rtc = rtc
        )
        val trackField = RiderAudioEngine::class.java.getDeclaredField("localAudioTrack").apply { isAccessible = true }
        trackField.set(engine, object : org.webrtc.AudioTrack(1L) {
            override fun dispose() {
                throw IllegalStateException("native audio track dispose failed")
            }
        })
        engine.close()
        rtc.drain()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, disposed)
        assertEquals(1, failures.size)
        assertTrue(failures.single().message!!.contains("native audio track dispose failed"))
    }

    @Test fun asynchronouslyClosedPeerDisposeFailureFailsFinalEngineDisposal() {
        val rtc = RtcQueue()
        var disposed = 0
        val failures = mutableListOf<Throwable>()
        val engine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(),
            mediaMode = RiderMediaMode.GROUP,
            onDisposed = { disposed++ },
            onDisposeFailed = { failures += it },
            rtc = rtc,
            disposePeerConnection = { throw IllegalStateException("peer dispose failed") }
        )
        val session = engine.openSession(callbacks())

        session.close()
        engine.close()
        rtc.drain()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, disposed)
        assertEquals(1, failures.size)
        assertTrue(failures.single().message!!.contains("peer dispose failed"))
    }

    @Test fun initializationRollbackFailureFailsFinalEngineDisposal() {
        val rtc = RtcQueue()
        val failures = mutableListOf<Throwable>()
        val engine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(), mediaMode = RiderMediaMode.GROUP,
            onDisposed = { fail("cleanup failure must reach the handover barrier") },
            onDisposeFailed = { failures += it }, rtc = rtc,
            disposePeerConnection = { throw IllegalStateException("rollback failed") }
        )
        engine.openSession(callbacks())
        rtc.drain()
        engine.close()
        rtc.drain()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("rollback failed", failures.single().message)
    }

    @Test fun singleModePlayoutErrorRoutesToEngineFailureWhileGroupModeRoutesToSessionError() {
        val singleRtc = RtcQueue()
        val singleEngineErrors = mutableListOf<Throwable>()
        val singleSessionErrors = mutableListOf<Throwable>()
        val singleEngine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(),
            onEngineError = { singleEngineErrors += it },
            mediaMode = RiderMediaMode.SINGLE,
            rtc = singleRtc
        )
        val singleSession = singleEngine.openSession(
            callbacks().copy(onError = { singleSessionErrors += it })
        )
        try {
            val playout = singleSession.javaClass.getDeclaredField("playout").apply { isAccessible = true }.get(singleSession)
            val onError = playout.javaClass.getDeclaredField("onError").apply { isAccessible = true }.get(playout) as (Throwable) -> Unit
            onError(IllegalStateException("single playout boom"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, singleEngineErrors.size)
            assertTrue(singleEngineErrors.single().message!!.contains("playout"))
            assertTrue(singleSessionErrors.isEmpty())
        } finally {
            finish(singleEngine, singleRtc)
        }

        val groupRtc = RtcQueue()
        val groupEngineErrors = mutableListOf<Throwable>()
        val groupSessionErrors = mutableListOf<Throwable>()
        val groupEngine = RiderAudioEngine(
            ApplicationProvider.getApplicationContext(),
            onEngineError = { groupEngineErrors += it },
            mediaMode = RiderMediaMode.GROUP,
            rtc = groupRtc
        )
        val groupSession = groupEngine.openSession(
            callbacks().copy(onError = { groupSessionErrors += it })
        )
        try {
            val playout = groupSession.javaClass.getDeclaredField("playout").apply { isAccessible = true }.get(groupSession)
            val onError = playout.javaClass.getDeclaredField("onError").apply { isAccessible = true }.get(playout) as (Throwable) -> Unit
            onError(IllegalStateException("group playout boom"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(groupEngineErrors.isEmpty())
            assertEquals(1, groupSessionErrors.size)
            assertTrue(groupSessionErrors.single().message!!.contains("group playout boom"))
        } finally {
            finish(groupEngine, groupRtc)
        }
    }
}
