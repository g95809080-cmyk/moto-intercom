package com.kuma.motointercom

import android.Manifest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.PeerConnection

/** Native JNI receipt/queue boundary; product ownership is covered by real Service JVM tests. */
@RunWith(AndroidJUnit4::class)
class NativeTransientObservationInstrumentationTest {
    @Test fun actualNativeSelfHealIsRecordedBeforeBlockedRtcAndMainTimerDelivery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val errors = LinkedBlockingQueue<Throwable>()
        val receipts = LinkedBlockingQueue<NativeConnectionObservation>()
        val connected = CountDownLatch(2)
        val engine = RiderAudioEngine(context, onEngineError = { errors.offer(it) })
        val peer = RiderAudioEngine(context, onEngineError = { errors.offer(it) })
        val relay = CandidateRelay()
        val releaseRtc = CountDownLatch(1)
        val releaseMain = CountDownLatch(1)
        try {
            lateinit var offerer: RiderMediaSession
            lateinit var answerer: RiderMediaSession
            offerer = engine.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { answerer.createAnswer(stripInlineCandidates(it)) },
                onLocalIceCandidateGenerated = { relay.rewrite(it)?.let(answerer::addRemoteIceCandidate) },
                onConnectionStateObserved = { receipts.offer(it); if (it.state == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                freezeConnectionLoss = { ConnectionLossObservation(null, null) },
                onError = { errors.offer(it) }, isSessionCurrent = { true }
            ))
            answerer = peer.openSession(RiderMediaSessionCallbacks(
                onLocalSdpGenerated = { offerer.setRemoteAnswer(stripInlineCandidates(it)) },
                onLocalIceCandidateGenerated = { relay.rewrite(it)?.let(offerer::addRemoteIceCandidate) },
                onConnectionStateChanged = { if (it == PeerConnection.PeerConnectionState.CONNECTED) connected.countDown() },
                onError = { errors.offer(it) }, isSessionCurrent = { true }
            ))
            offerer.createOffer()
            assertTrue("Relay-backed JNI peers did not connect: $errors", connected.await(20, TimeUnit.SECONDS))
            assertTrue(relay.endpoints.isNotEmpty())
            val initial = awaitReceipt(receipts, PeerConnection.PeerConnectionState.CONNECTED, 2_000L)
            val originalPeer = field(engine, "peerConnection")
            relay.forwarding.set(false)
            val loss = awaitReceipt(receipts, PeerConnection.PeerConnectionState.DISCONNECTED, 40_000L)
            assertSame(initial.source, loss.source)
            assertEquals(loss.elapsedMs, loss.firstLoss?.occurredAtElapsedMs)
            val rtcEntered = CountDownLatch(1)
            (field(engine, "rtc") as ExecutorService).execute { rtcEntered.countDown(); releaseRtc.await(8, TimeUnit.SECONDS) }
            assertTrue(rtcEntered.await(2, TimeUnit.SECONDS))
            val mainEntered = CountDownLatch(1)
            val timerRan = CountDownLatch(1)
            val timerProof = AtomicReference<NativeConnectionObservation?>()
            val until = loss.elapsedMs + 2_000L
            Handler(Looper.getMainLooper()).post {
                mainEntered.countDown(); releaseMain.await(8, TimeUnit.SECONDS)
            }
            assertTrue(mainEntered.await(2, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).postDelayed({
                timerProof.set(loss.source.decideWindow(loss, until) { it }); timerRan.countDown()
            }, (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            relay.forwarding.set(true)
            var proof: NativeConnectionObservation? = null
            while (SystemClock.elapsedRealtime() <= until && proof == null) {
                proof = loss.source.decideWindow(loss, until) { it }
                if (proof == null) SystemClock.sleep(10)
            }
            assertNotNull("Actual JNI CONNECTED did not enter within the bounded window", proof)
            assertSame(loss.source, proof!!.source)
            assertTrue(proof!!.elapsedMs - loss.elapsedMs in 0..2_000L)
            assertFalse("Held RTC/Main cannot have delivered self-heal", receipts.any { it.sequence == proof!!.sequence })
            SystemClock.sleep((until + 100L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            releaseMain.countDown()
            assertTrue(timerRan.await(2, TimeUnit.SECONDS))
            assertEquals(proof, timerProof.get())
            assertSame("Observation cannot replace the actual native peer", originalPeer, field(engine, "peerConnection"))
            releaseRtc.countDown()
            val delivered = awaitReceipt(receipts, PeerConnection.PeerConnectionState.CONNECTED, 3_000L)
            assertEquals(proof, delivered)
            assertTrue("Native media errors: $errors", errors.isEmpty())
            android.util.Log.i("MotoNativeRecovery", "actualJNI firstLoss=${loss.elapsedMs} heal=${proof!!.elapsedMs} " +
                "deltaMs=${proof!!.elapsedMs - loss.elapsedMs} delayedMainTimer=true samePeer=true")
        } finally {
            releaseMain.countDown(); releaseRtc.countDown()
            engine.close(); peer.close(); relay.close()
        }
    }

    private fun awaitReceipt(queue: LinkedBlockingQueue<NativeConnectionObservation>, state: PeerConnection.PeerConnectionState,
        timeout: Long): NativeConnectionObservation {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val event = queue.poll((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: break
            if (event.state == state) return event
            if (event.state == PeerConnection.PeerConnectionState.FAILED) error("Native peer failed before $state")
        }
        error("Actual JNI did not produce $state")
    }
    private fun stripInlineCandidates(json: String): String = JSONObject(json).apply {
        put("sdp", getString("sdp").lineSequence().filterNot {
            it.startsWith("a=candidate:") || it.startsWith("a=end-of-candidates")
        }.joinToString("\r\n"))
    }.toString()
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private class CandidateRelay : AutoCloseable {
        val forwarding = AtomicBoolean(true)
        val endpoints = CopyOnWriteArrayList<DatagramSocket>()
        private val closed = AtomicBoolean(false)
        private val workers = Executors.newCachedThreadPool()
        fun rewrite(json: String): String? {
            val candidate = JSONObject(json)
            val parts = candidate.getString("candidate").trim().split(Regex("\\s+")).toMutableList()
            if (parts.size < 8 || !parts[2].equals("udp", true)) return null
            val address = InetAddress.getByName(parts[4]) as? Inet4Address ?: return null
            val target = InetSocketAddress(address, parts[5].toInt())
            val socket = DatagramSocket(InetSocketAddress(address, 0)).apply { soTimeout = 200 }
            endpoints += socket
            parts[0] = "candidate:relay${socket.localPort}"
            parts[5] = socket.localPort.toString()
            workers.execute {
                var other: InetSocketAddress? = null
                val buffer = ByteArray(8_192)
                while (!closed.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val origin = packet.socketAddress as InetSocketAddress
                        val destination = if (origin == target) other else { other = origin; target }
                        if (forwarding.get() && destination != null)
                            socket.send(DatagramPacket(packet.data, packet.length, destination))
                    } catch (_: SocketTimeoutException) { }
                    catch (failure: Throwable) { if (!closed.get()) throw failure }
                }
            }
            return candidate.put("candidate", parts.joinToString(" ")).toString()
        }
        override fun close() { closed.set(true); endpoints.forEach { it.close() }; workers.shutdownNow() }
    }
}
