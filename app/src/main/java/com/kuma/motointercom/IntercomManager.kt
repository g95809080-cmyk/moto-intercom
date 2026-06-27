package com.kuma.motointercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.nio.charset.StandardCharsets
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
                    val frame = readFrame()
                    dispatch(JSONObject(frame))
                }
            } catch (e: EOFException) {
                notifyDisconnected(IOException("信令通道已关闭", e))
            } catch (e: IOException) {
                if (!closed.get()) notifyDisconnected(e)
            } catch (t: Throwable) {
                if (!closed.get()) postError(t)
            } finally {
                close()
            }
        }
    }

    private fun readFrame(): String {
        val stream = input ?: throw IOException("信令输入流未初始化")
        val length = stream.readInt()
        if (length !in 1..MAX_FRAME_BYTES) {
            throw IOException("非法信令帧长度: $length")
        }

        val bytes = ByteArray(length)
        stream.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun dispatch(message: JSONObject) {
        when (message.getString(KEY_TYPE)) {
            TYPE_IDENTITY -> {
                val name = message.optString(KEY_NAME).trim()
                if (name.isNotEmpty()) {
                    mainHandler.post { onRemoteRiderIdentified(name) }
                }
            }
            TYPE_OFFER -> audioEngineOrThrow().createAnswer(message.payloadString(KEY_SDP))
            TYPE_ANSWER -> audioEngineOrThrow().setRemoteAnswer(message.payloadString(KEY_SDP))
            TYPE_CANDIDATE -> audioEngineOrThrow().addRemoteIceCandidate(
                message.payloadString(KEY_CANDIDATE)
            )
            else -> throw IOException("未知信令类型: ${message.optString(KEY_TYPE)}")
        }
    }

    private fun sendLocalSdp(sdpJson: String) {
        try {
            val sdp = JSONObject(sdpJson)
            val type = when (sdp.getString("type").uppercase()) {
                "OFFER" -> TYPE_OFFER
                "ANSWER" -> TYPE_ANSWER
                else -> throw IOException("未知本地 SDP 类型: ${sdp.optString("type")}")
            }

            sendFrame(
                JSONObject()
                    .put(KEY_TYPE, type)
                    .put(KEY_SDP, sdpJson)
            )
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendIdentity() {
        try {
            sendFrame(
                JSONObject()
                    .put(KEY_TYPE, TYPE_IDENTITY)
                    .put(KEY_NAME, localRiderName.trim())
            )
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendLocalIceCandidate(candidateJson: String) {
        try {
            sendFrame(
                JSONObject()
                    .put(KEY_TYPE, TYPE_CANDIDATE)
                    .put(KEY_CANDIDATE, JSONObject(candidateJson))
            )
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendFrame(message: JSONObject) {
        if (closed.get()) return

        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) {
            postError(IOException("信令帧过大: ${bytes.size}"))
            return
        }

        try {
            writer.execute {
                try {
                    val stream = output ?: throw IOException("信令输出流未初始化")
                    stream.writeInt(bytes.size)
                    stream.write(bytes)
                    stream.flush()
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

    private fun JSONObject.payloadString(key: String): String {
        val value = get(key)
        return if (value is JSONObject) value.toString() else value.toString()
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
        private const val MAX_FRAME_BYTES = 1024 * 1024
        private const val KEY_TYPE = "type"
        private const val KEY_NAME = "name"
        private const val KEY_SDP = "sdp"
        private const val KEY_CANDIDATE = "candidate"
        private const val TYPE_IDENTITY = "IDENTITY"
        private const val TYPE_OFFER = "OFFER"
        private const val TYPE_ANSWER = "ANSWER"
        private const val TYPE_CANDIDATE = "CANDIDATE"
    }
}
