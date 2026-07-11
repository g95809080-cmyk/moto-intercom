package com.kuma.motointercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import org.webrtc.PeerConnection
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi Direct TCP 信令层 + WebRTC 音频层的胶水。
 *
 * 帧格式固定为：
 *   4 字节大端长度 Int + UTF-8 JSON
 *
 * TCP 会粘包/半包，永远不要直接按 read(buffer) 当作一条 JSON。
 */
class IntercomManager(
    context: Context,
    private val signalingSocket: Socket,
    private val isServer: Boolean,
    private val localRiderName: String,
    private val onIntercomDisconnected: (IOException) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onRemoteRiderIdentified: (String) -> Unit = {},
    private val onAudioLevelChanged: (Float) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reader: ExecutorService = Executors.newSingleThreadExecutor()
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val disconnectedNotified = AtomicBoolean(false)
    private val protocol = SignalingProtocol(
        if (isServer) SignalingProtocol.SdpKind.OFFER else SignalingProtocol.SdpKind.ANSWER
    )

    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var audioEngine: RiderAudioEngine? = null

    fun start() {
        if (closed.get()) return
        if (!started.compareAndSet(false, true)) return

        try {
            require(RiderAudioEngine.hasRequiredPermissions(appContext)) {
                "缺少 RECORD_AUDIO 运行时权限"
            }
            input = DataInputStream(signalingSocket.getInputStream())
            output = DataOutputStream(signalingSocket.getOutputStream())
            audioEngine = RiderAudioEngine(
                context = appContext,
                onLocalSdpGenerated = ::sendLocalSdp,
                onLocalIceCandidateGenerated = ::sendLocalIceCandidate,
                onConnectionStateChanged = onConnectionStateChanged,
                onAudioLevelChanged = onAudioLevelChanged,
                onError = ::postError
            )
        } catch (t: Throwable) {
            postError(t)
            close()
            return
        }

        startReader()
        sendIdentity()

        // Wi-Fi Direct 组员主动发起 Offer；组长只等 Offer，避免双方同时 offer 冲突。
        if (!isServer) {
            audioEngine?.createOffer()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        try {
            audioEngine?.close()
        } catch (t: Throwable) {
            postError(t)
        }
        try {
            signalingSocket.close()
        } catch (_: Throwable) {
        }

        reader.shutdownNow()
        writer.shutdownNow()
        audioEngine = null
        input = null
        output = null
    }

    private fun startReader() {
        reader.execute {
            try {
                while (!closed.get()) {
                    dispatch(protocol.decode(readFrame()))
                }
            } catch (t: Throwable) {
                val failure = t as? IOException ?: IOException("invalid signaling message", t)
                if (!closed.get()) notifyDisconnected(failure)
            } finally {
                close()
            }
        }
    }

    private fun readFrame(): ByteArray {
        val stream = input ?: throw IOException("信令输入流未初始化")
        val length = stream.readInt()
        if (length !in 1..SignalingProtocol.MAX_FRAME_BYTES) {
            throw IOException("非法信令帧长度: $length")
        }

        return ByteArray(length).also(stream::readFully)
    }

    private fun dispatch(message: SignalingProtocol.Message) {
        Log.d(TAG, "RX signaling frame: type=${message.javaClass.simpleName}")
        when (message) {
            is SignalingProtocol.Message.Identity ->
                mainHandler.post { if (!closed.get()) onRemoteRiderIdentified(message.name) }
            is SignalingProtocol.Message.Offer ->
                audioEngineOrThrow().createAnswer(message.sdpJson)
            is SignalingProtocol.Message.Answer ->
                audioEngineOrThrow().setRemoteAnswer(message.sdpJson)
            is SignalingProtocol.Message.Candidate ->
                audioEngineOrThrow().addRemoteIceCandidate(message.candidateJson)
        }
    }

    private fun sendLocalSdp(sdpJson: String) {
        try {
            val type = JsonParser.parseString(sdpJson).asJsonObject.get("type")?.asString
            val message = when (type?.uppercase()) {
                "OFFER" -> SignalingProtocol.Message.Offer(sdpJson)
                "ANSWER" -> SignalingProtocol.Message.Answer(sdpJson)
                else -> throw IOException("未知本地 SDP 类型: $type")
            }
            sendFrame(message)
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendIdentity() {
        try {
            sendFrame(SignalingProtocol.Message.Identity(localRiderName.trim()))
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendLocalIceCandidate(candidateJson: String) {
        try {
            sendFrame(SignalingProtocol.Message.Candidate(candidateJson))
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendFrame(message: SignalingProtocol.Message) {
        if (closed.get() || writer.isShutdown) return
        val bytes = protocol.encode(message)

        try {
            writer.execute {
                try {
                    val stream = output ?: throw IOException("信令输出流未初始化")
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                    stream.flush()
                    Log.d(TAG, "TX signaling frame: type=${message.javaClass.simpleName} bytes=${bytes.size}")
                } catch (e: IOException) {
                    if (!closed.get()) notifyDisconnected(e)
                    close()
                } catch (t: Throwable) {
                    if (!closed.get()) postError(t)
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) postError(t)
        }
    }

    private fun audioEngineOrThrow(): RiderAudioEngine =
        audioEngine ?: throw IOException("音频引擎未初始化")

    private fun notifyDisconnected(e: IOException) {
        if (!disconnectedNotified.compareAndSet(false, true)) return
        mainHandler.post { onIntercomDisconnected(e) }
    }

    private fun postError(t: Throwable) {
        mainHandler.post { onError(t) }
    }

    companion object {
        private const val TAG = "IntercomSignal"
    }
}
