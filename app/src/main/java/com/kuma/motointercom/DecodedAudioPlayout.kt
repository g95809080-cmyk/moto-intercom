package com.kuma.motointercom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import org.webrtc.AudioTrackSink
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal interface PcmPlayback : Closeable {
    val sessionId: Int
    val playing: Boolean
    fun play()
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
}

internal data class PlayoutSnapshot(val revision: Long, val thread: Thread, val sessionId: Int,
    val writtenBytes: Long, val nonzeroWrittenBytes: Long)

/** One output device per current grant. The SDK decoder callback never waits for device I/O. */
internal class DecodedAudioPlayout(
    private val permits: (Long) -> Boolean,
    private val onPlaying: (Boolean) -> Unit,
    private val onWritten: () -> Unit,
    private val onError: (Throwable) -> Unit,
    private val createPlayback: (Int, Int) -> PcmPlayback = ::androidPcmPlayback
) : AudioTrackSink, Closeable {
    private data class Frame(val rate: Int, val channels: Int, val bytes: ByteArray)
    private inner class Lease(val revision: Long) {
        val lock = Any()
        val queue = ArrayBlockingQueue<Frame>(10)
        @Volatile var stopped = false
        var playback: PcmPlayback? = null
        var rate = 0
        var channels = 0
        var writtenBytes = 0L
        var nonzeroWrittenBytes = 0L
        var published = false
        val rejected = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val thread = Thread({ render(this) }, "MotoDecodedAudio")
        fun current() = !stopped && active === this && permits(revision)
    }

    private val lock = Any()
    @Volatile private var active: Lease? = null
    private var closed = false

    fun resume(revision: Long) = synchronized(lock) {
        if (closed || !permits(revision) || active?.revision == revision) return
        pause()
        val lease = Lease(revision)
        active = lease
        lease.thread.start()
    }

    fun pause() = synchronized(lock) {
        val lease = active ?: return
        active = null
        lease.stopped = true
        lease.queue.clear()
        synchronized(lease.lock) {
            releasePlayback(lease)
            onPlaying(false)
        }
        lease.thread.interrupt()
        // No join: a delayed constructor owns only its old device and releases it on return.
    }

    override fun close() = synchronized(lock) { closed = true; pause() }

    override fun onData(data: ByteBuffer, bits: Int, rate: Int, channels: Int, frames: Int, timestamp: Long) {
        val lease = active ?: return
        if (!lease.current()) return
        try {
        // The pinned SDK delivers 10ms PCM16; bound before allocation and arithmetic.
        if (bits != 16 || channels !in 1..2 || rate !in 8_000..48_000 || frames !in 1..(rate / 50)) {
            reject(lease, IllegalArgumentException("Unsupported decoded PCM format")); return
        }
        val expected = frames.toLong() * channels * 2
        if (expected > 3_840 || data.remaining().toLong() < expected) {
            reject(lease, IllegalArgumentException("Truncated or oversized decoded PCM")); return
        }
        val size = expected.toInt()
        val copy = ByteArray(size)
        data.duplicate().get(copy)
        if (!lease.current()) return
        val frame = Frame(rate, channels, copy)
        if (!lease.queue.offer(frame)) {
            lease.queue.poll() // Drop the oldest frame instead of growing latency or blocking the decoder.
            lease.queue.offer(frame)
        }
        } catch (error: Throwable) { reject(lease, error) }
    }

    fun isCurrentRevision(revision: Long): Boolean = active?.let { it.revision == revision && it.current() } == true

    fun snapshot(): PlayoutSnapshot? {
        val lease = active ?: return null
        return synchronized(lease.lock) {
            val playback = lease.playback
            if (lease.current() && playback?.playing == true && lease.writtenBytes > 0)
                PlayoutSnapshot(lease.revision, lease.thread, playback.sessionId, lease.writtenBytes, lease.nonzeroWrittenBytes)
            else null
        }
    }

    private fun render(lease: Lease) {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        try {
            while (lease.current()) {
                val frame = lease.queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (lease.rate != frame.rate || lease.channels != frame.channels) {
                    synchronized(lease.lock) {
                        releasePlayback(lease)
                        if (lease.current()) onPlaying(false)
                    }
                    if (!lease.current()) return
                    val created = createPlayback(frame.rate, frame.channels)
                    synchronized(lease.lock) {
                        if (!lease.current()) { created.close(); return }
                        lease.playback = created
                        lease.rate = frame.rate
                        lease.channels = frame.channels
                        created.play()
                        check(created.playing) { "Decoded audio output did not start" }
                    }
                }
                var offset = 0
                val writeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (offset < frame.bytes.size && lease.current()) {
                    check(System.nanoTime() <= writeDeadline) { "Decoded audio output stalled" }
                    val written = synchronized(lease.lock) {
                        if (!lease.current()) return
                        val device = lease.playback ?: error("Missing decoded audio output")
                        val count = device.write(frame.bytes, offset, frame.bytes.size - offset)
                        check(count >= 0 && count <= frame.bytes.size - offset) { "Decoded audio write failed: $count" }
                        if (count > 0) {
                            check(device.playing) { "Decoded audio output stopped" }
                            lease.writtenBytes += count
                            for (index in offset until offset + count) if (frame.bytes[index] != 0.toByte()) lease.nonzeroWrittenBytes++
                            if (!lease.published) { lease.published = true; onPlaying(true) }
                            onWritten()
                        }
                        count
                    }
                    offset += written
                    if (written == 0) Thread.sleep(2)
                }
            }
        } catch (_: InterruptedException) {
            // Revocation interrupts only this lease's queue/backpressure wait.
        } catch (error: Throwable) {
            fail(lease, error)
        } finally {
            synchronized(lease.lock) {
                releasePlayback(lease)
                if (active === lease) {
                    onPlaying(false)
                    lease.rejected.get()?.let { if (permits(lease.revision)) onError(it) }
                }
            }
        }
    }

    private fun reject(lease: Lease, error: Throwable) {
        if (!lease.current() || !lease.rejected.compareAndSet(null, error)) return
        lease.stopped = true
        lease.queue.clear()
        lease.thread.interrupt() // Worker releases the device; JNI callback never waits for it.
    }

    private fun fail(lease: Lease, error: Throwable) = synchronized(lease.lock) {
        if (!lease.current()) return
        lease.stopped = true
        lease.queue.clear()
        releasePlayback(lease)
        lease.thread.interrupt()
        onPlaying(false)
        onError(error)
    }

    private fun releasePlayback(lease: Lease) {
        val device = lease.playback
        lease.playback = null
        lease.published = false
        lease.writtenBytes = 0
        lease.nonzeroWrittenBytes = 0
        if (device != null) runCatching(device::close)
    }
}

private fun androidPcmPlayback(rate: Int, channels: Int): PcmPlayback {
    val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
    check(minimum > 0) { "Unsupported decoded audio device format: $minimum" }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate).setChannelMask(mask).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(minimum, rate / 50 * channels * 2))
        .build()
    if (track.state != AudioTrack.STATE_INITIALIZED) { track.release(); error("Decoded audio output initialization failed") }
    return object : PcmPlayback {
        override val sessionId get() = track.audioSessionId
        override val playing get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
        override fun play() = track.play()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = track.write(bytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        override fun close() {
            try { if (playing) track.pause(); track.flush() } finally { track.release() }
        }
    }
}
