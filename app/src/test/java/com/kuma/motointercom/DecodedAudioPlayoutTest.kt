package com.kuma.motointercom

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DecodedAudioPlayoutTest {
    private class Device(override val sessionId: Int) : PcmPlayback {
        @Volatile override var playing = false
        val closed = CountDownLatch(1)
        val writes = LinkedBlockingQueue<ByteArray>()
        override fun play() { playing = true }
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
            writes.offer(bytes.copyOfRange(offset, offset + length)); return length
        }
        override fun close() { playing = false; closed.countDown() }
    }
    private fun send(output: DecodedAudioPlayout, marker: Int = 1) = output.onData(
        ByteBuffer.wrap(ByteArray(960) { marker.toByte() }), 16, 48_000, 1, 480, 0
    )

    @Test fun delayedOldConstructorCanOnlyReleaseItsOwnDeviceAfterResume() {
        val revision = AtomicLong(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val old = Device(1)
        val current = Device(2)
        val failures = LinkedBlockingQueue<Throwable>()
        val output = DecodedAudioPlayout({ it == revision.get() }, {}, {}, { failures.offer(it) }) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                // Constructors need not respond to interrupt; pause must not wait or transfer their device.
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                old
            } else current
        }
        try {
            output.resume(1); send(output)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            output.pause()
            assertNull(output.snapshot())
            revision.set(2); output.resume(2); send(output, 2)
            assertNotNull(current.writes.poll(2, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(old.closed.await(2, TimeUnit.SECONDS))
            assertTrue(current.playing)
            assertEquals(2L, output.snapshot()!!.revision)
            assertFalse(old.playing)
            assertNull(failures.poll())
        } finally { release.countDown(); output.close() }
    }

    @Test fun oldConstructorFailureCannotFailTheReplacementOutput() {
        val revision = AtomicLong(1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldReturned = CountDownLatch(1)
        val calls = AtomicInteger()
        val current = Device(2)
        val failures = LinkedBlockingQueue<Throwable>()
        val output = DecodedAudioPlayout({ it == revision.get() }, {}, {}, { failures.offer(it) }) { _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                oldReturned.countDown()
                error("late old output init failure")
            } else current
        }
        try {
            output.resume(1); send(output); assertTrue(entered.await(2, TimeUnit.SECONDS))
            output.pause(); revision.set(2); output.resume(2); send(output)
            assertNotNull(current.writes.poll(2, TimeUnit.SECONDS))
            release.countDown(); assertTrue(oldReturned.await(2, TimeUnit.SECONDS))
            assertNull(failures.poll(100, TimeUnit.MILLISECONDS))
            assertTrue(current.playing)
        } finally { release.countDown(); output.close() }
    }

    @Test fun slowDeviceCannotGrowDecodedQueueOrRetainAllOldAudio() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val device = Device(1)
        val output = DecodedAudioPlayout({ true }, {}, {}, { throw it }) { _, _ ->
            entered.countDown(); release.await(2, TimeUnit.SECONDS); device
        }
        try {
            output.resume(1); send(output, 1); assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(1_000) { send(output, it % 100) }
            release.countDown()
            assertNotNull(device.writes.poll(2, TimeUnit.SECONDS))
            val remaining = mutableListOf<ByteArray>()
            while (true) remaining += device.writes.poll(100, TimeUnit.MILLISECONDS) ?: break
            assertTrue(remaining.size <= 10)
            assertEquals(99.toByte(), remaining.last()[0])
            output.pause()
            assertTrue(device.closed.await(2, TimeUnit.SECONDS))
            send(output, 99)
            assertNull(device.writes.poll(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); output.close() }
    }

    @Test fun invalidOrTruncatedNativeMetadataFailsBeforeAllocatingOrWriting() {
        val failures = LinkedBlockingQueue<Throwable>()
        val calls = AtomicInteger()
        val output = DecodedAudioPlayout({ true }, {}, {}, { failures.offer(it) }) { _, _ -> calls.incrementAndGet(); Device(1) }
        try {
            output.resume(1)
            output.onData(ByteBuffer.allocate(1), 16, 48_000, Int.MAX_VALUE, Int.MAX_VALUE, 0)
            assertTrue(failures.poll(2, TimeUnit.SECONDS) is IllegalArgumentException)
            assertEquals(0, calls.get())
            output.pause(); output.resume(2)
            output.onData(ByteBuffer.allocate(1), 16, 48_000, 1, 480, 0)
            assertTrue(failures.poll(2, TimeUnit.SECONDS) is IllegalArgumentException)
            assertEquals(0, calls.get())
        } finally { output.close() }
    }

    @Test fun copiedNativeMemorySurvivesReuseAndPartialWritesDoNotGrantEarlyEvidence() {
        val entered = CountDownLatch(1)
        val create = CountDownLatch(1)
        val zeroWrite = CountDownLatch(1)
        val allowProgress = CountDownLatch(1)
        val playing = java.util.concurrent.atomic.AtomicBoolean(false)
        val chunks = LinkedBlockingQueue<ByteArray>()
        val writes = AtomicInteger()
        val failures = LinkedBlockingQueue<Throwable>()
        val device = object : PcmPlayback {
            override val sessionId = 1
            override var playing = false
            override fun play() { playing = true }
            override fun close() { playing = false }
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                if (writes.incrementAndGet() == 1) {
                    zeroWrite.countDown(); allowProgress.await(2, TimeUnit.SECONDS); return 0
                }
                val count = minOf(length, 120)
                chunks.offer(bytes.copyOfRange(offset, offset + count)); return count
            }
        }
        val output = DecodedAudioPlayout({ true }, { playing.set(it) }, {}, { failures.offer(it) }) { _, _ ->
            entered.countDown(); create.await(2, TimeUnit.SECONDS); device
        }
        try {
            output.resume(1)
            val original = ByteArray(960) { (it % 100).toByte() }
            val expected = original.copyOf()
            output.onData(ByteBuffer.wrap(original), 16, 48_000, 1, 480, 0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            original.fill(0) // The native callback's buffer may be reused immediately after it returns.
            create.countDown(); assertTrue(zeroWrite.await(2, TimeUnit.SECONDS))
            assertFalse("play() or a zero write granted evidence", playing.get())
            allowProgress.countDown()
            val actual = ArrayList<Byte>()
            repeat(8) { actual.addAll(chunks.poll(2, TimeUnit.SECONDS)!!.toList()) }
            assertArrayEquals(expected, actual.toByteArray())
            assertTrue(playing.get())
            assertNull(failures.poll())
        } finally { create.countDown(); allowProgress.countDown(); output.close() }
    }

    @Test fun currentWriteFailureReleasesOutputAndReportsOnlyOnce() {
        val failures = LinkedBlockingQueue<Throwable>()
        val released = CountDownLatch(1)
        val output = DecodedAudioPlayout({ true }, {}, {}, { failures.offer(it) }) { _, _ ->
            object : PcmPlayback {
                override val sessionId = 1
                override var playing = false
                override fun play() { playing = true }
                override fun write(bytes: ByteArray, offset: Int, length: Int) = -6
                override fun close() { playing = false; released.countDown() }
            }
        }
        try {
            output.resume(1); send(output)
            assertTrue(failures.poll(2, TimeUnit.SECONDS)?.message.orEmpty().contains("write failed"))
            assertTrue(released.await(2, TimeUnit.SECONDS))
            assertNull(output.snapshot())
            repeat(10) { send(output) }
            assertNull(failures.poll(100, TimeUnit.MILLISECONDS))
        } finally { output.close() }
    }
}
