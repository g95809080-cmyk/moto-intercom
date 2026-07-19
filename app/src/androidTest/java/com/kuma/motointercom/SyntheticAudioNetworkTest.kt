package com.kuma.motointercom

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyntheticAudioNetworkTest {
    @Test
    fun exchange() {
        val arguments = InstrumentationRegistry.getArguments()
        val role = arguments.getString("role") ?: error("Missing role")
        val port = arguments.getString("port")?.toIntOrNull() ?: DEFAULT_PORT
        when (role) {
            "server" -> runServer(port)
            "client" -> runClient(
                host = arguments.getString("host") ?: error("Missing host"),
                port = port
            )
            else -> error("Unsupported role: $role")
        }
    }

    private fun runServer(port: Int) {
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.soTimeout = SOCKET_TIMEOUT_MILLIS
            server.bind(InetSocketAddress("0.0.0.0", port))
            server.accept().use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                assertEquals(MAGIC, input.readInt())
                val streamId = input.readUTF()
                val frameCount = input.readInt()
                val sink = TestAudioSink().also { it.start(streamId) }
                repeat(frameCount) {
                    val frame = readFrame(input, streamId)
                    // Monotonic clocks are process-local and cannot be compared
                    // across emulator nodes. First-frame latency is asserted by
                    // SyntheticAudioMetricsTest with one deterministic clock.
                    assertTrue(sink.accept(frame, frame.sentAtNanos))
                }

                val metrics = sink.metrics()
                assertEquals(frameCount, metrics.frameCount)
                assertEquals(0, metrics.droppedFrames)
                assertTrue(metrics.normalizedRms in 0.34..0.37)
                assertTrue(metrics.dominantFrequencyHz in 990.0..1_010.0)
                assertEquals(0.0, metrics.firstFrameLatencyMillis, 0.0)

                output.writeInt(MAGIC)
                output.writeInt(metrics.frameCount)
                output.writeDouble(metrics.normalizedRms)
                output.writeDouble(metrics.dominantFrequencyHz)
                output.flush()
            }
        }
    }

    private fun runClient(host: String, port: Int) {
        val frames = SyntheticAudioSource().frames(NETWORK_FRAME_COUNT)
        Socket().use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS)
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            output.writeInt(MAGIC)
            output.writeUTF(SyntheticAudioSource.PRIMARY_STREAM)
            output.writeInt(frames.size)
            frames.forEach { writeFrame(output, it) }
            output.flush()

            assertEquals(MAGIC, input.readInt())
            assertEquals(NETWORK_FRAME_COUNT, input.readInt())
            assertTrue(input.readDouble() in 0.34..0.37)
            assertTrue(input.readDouble() in 990.0..1_010.0)
        }
    }

    private fun writeFrame(output: DataOutputStream, frame: SyntheticPcmFrame) {
        output.writeInt(frame.sequence)
        output.writeLong(frame.sentAtNanos)
        output.writeInt(frame.sampleRate)
        output.writeInt(frame.samples.size)
        frame.samples.forEach { output.writeShort(it.toInt()) }
    }

    private fun readFrame(input: DataInputStream, streamId: String): SyntheticPcmFrame {
        val sequence = input.readInt()
        val sentAt = input.readLong()
        val sampleRate = input.readInt()
        val sampleCount = input.readInt()
        require(sampleCount in 1..MAX_SAMPLES_PER_FRAME)
        val samples = ShortArray(sampleCount) { input.readShort() }
        return SyntheticPcmFrame(streamId, sequence, sentAt, sampleRate, samples)
    }

    companion object {
        private const val MAGIC = 0x4D43504D
        private const val DEFAULT_PORT = 39027
        private const val NETWORK_FRAME_COUNT = 50
        private const val MAX_SAMPLES_PER_FRAME = 4_096
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
    }
}
