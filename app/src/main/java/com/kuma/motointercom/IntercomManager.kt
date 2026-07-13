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
    private val localDeviceId: String,
    private val localRuntimeSessionId: RuntimeSessionId,
    private val expectedRemoteDeviceId: String?,
    private val requireClaimedRemoteDeviceId: Boolean,
    private val onIntercomDisconnected: (IOException) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onRemoteIdentity: (PeerIdentity) -> Unit = {},
    private val onAudioLevelChanged: (Float) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val isSessionCurrent: () -> Boolean
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
                onError = ::onMediaFailure,
                isSessionCurrent = { !closed.get() && isSessionCurrent() }
            )
        } catch (t: Throwable) {
            postMain(
                after = ::close,
                block = { onError(t) }
            )
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
            is SignalingProtocol.Message.Identity -> {
                val identity = resolveRemoteIdentity(
                    message,
                    expectedRemoteDeviceId,
                    requireClaimedRemoteDeviceId
                )
                postMain { onRemoteIdentity(identity) }
            }
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
            sendFrame(
                SignalingProtocol.Message.Identity(
                    name = localRiderName.trim(),
                    deviceId = localDeviceId,
                    runtimeSessionId = localRuntimeSessionId.value
                )
            )
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
        postMain(
            block = { onIntercomDisconnected(e) },
            after = ::close
        )
    }

    private fun postError(t: Throwable) {
        postMain { onError(t) }
    }

    private fun onMediaFailure(t: Throwable) {
        postError(t)
        notifyDisconnected(
            t as? IOException ?: IOException("WebRTC media failure", t)
        )
    }

    private fun postMain(after: () -> Unit = {}, block: () -> Unit) {
        mainHandler.post {
            try {
                if (!closed.get() && isSessionCurrent()) block()
            } finally {
                after()
            }
        }
    }

    companion object {
        private const val TAG = "IntercomSignal"
    }
}

internal fun resolveRemoteIdentity(
    message: SignalingProtocol.Message.Identity,
    expectedRemoteDeviceId: String?,
    requireClaimedDeviceId: Boolean = false
): PeerIdentity {
    val expected = expectedRemoteDeviceId?.trim()?.takeIf(String::isNotEmpty)
    val claimed = message.deviceId?.trim()?.takeIf(String::isNotEmpty)
    if (expected != null && claimed != null && expected != claimed) {
        throw SignalingProtocol.ProtocolException(
            "remote deviceId mismatch: expected=$expected claimed=$claimed"
        )
    }
    val remoteRuntimeSessionId = message.runtimeSessionId?.let(::RuntimeSessionId)
    val hasExtendedIdentity = claimed != null && remoteRuntimeSessionId != null
    val verifiedDeviceId = if (requireClaimedDeviceId && !hasExtendedIdentity) {
        null
    } else {
        expected ?: claimed
    }
    return PeerIdentity(
        deviceId = verifiedDeviceId,
        nickname = message.name,
        runtimeSessionId = remoteRuntimeSessionId,
        isDeviceIdVerified = verifiedDeviceId != null
    )
}
