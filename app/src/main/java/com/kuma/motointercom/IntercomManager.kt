package com.kuma.motointercom

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import org.webrtc.PeerConnection
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Accept 后的 WebRTC 音频生命周期。Socket、framing 和协议顺序由 SignalingSessionV2 独占。
 */
internal class IntercomManager(
    private val audioSessionController: AudioSessionController,
    private val signalingSession: SignalingSessionV2,
    private val webRtcRole: WebRtcRole,
    private val onIntercomDisconnected: (IOException) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onAudioLevelChanged: (Float) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val isSessionCurrent: () -> Boolean
) : Closeable {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val disconnectedNotified = AtomicBoolean(false)

    private var mediaSession: RiderMediaSession? = null

    fun start() {
        if (closed.get()) return
        if (!started.compareAndSet(false, true)) return

        try {
            mediaSession = audioSessionController.openMediaSession(
                RiderMediaSessionCallbacks(
                    onLocalSdpGenerated = ::sendLocalSdp,
                    onLocalIceCandidateGenerated = ::sendLocalIceCandidate,
                    onConnectionStateChanged = onConnectionStateChanged,
                    onAudioLevelChanged = onAudioLevelChanged,
                    onError = ::onMediaFailure,
                    isSessionCurrent = { !closed.get() && isSessionCurrent() }
                )
            )
        } catch (t: Throwable) {
            postMain(
                after = ::close,
                block = { onError(t) }
            )
            return
        }

        if (webRtcRole == WebRtcRole.OFFERER) {
            mediaSession?.createOffer()
        }
    }

    fun handleRemoteSignaling(message: SignalingMessageV2) {
        if (closed.get()) return
        try {
            Log.d(TAG, "RX signaling frame: type=${message.type}")
            when (message) {
                is SignalingMessageV2.Offer ->
                    mediaSessionOrThrow().createAnswer(message.sdpJson)
                is SignalingMessageV2.Answer ->
                    mediaSessionOrThrow().setRemoteAnswer(message.sdpJson)
                is SignalingMessageV2.Candidate ->
                    mediaSessionOrThrow().addRemoteIceCandidate(message.candidateJson)
                else -> throw SignalingV2Exception(
                    "unexpected media signaling message: ${message.type}"
                )
            }
        } catch (t: Throwable) {
            onMediaFailure(t)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        try {
            mediaSession?.let(audioSessionController::closeMediaSession)
        } catch (t: Throwable) {
            postError(t)
        }
        signalingSession.close()
        mediaSession = null
    }

    private fun sendLocalSdp(sdpJson: String) {
        try {
            val type = JsonParser.parseString(sdpJson).asJsonObject.get("type")?.asString
            val message = when (type?.uppercase()) {
                "OFFER" -> SignalingMessageV2.Offer(sdpJson)
                "ANSWER" -> SignalingMessageV2.Answer(sdpJson)
                else -> throw IOException("未知本地 SDP 类型: $type")
            }
            sendFrame(message)
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendLocalIceCandidate(candidateJson: String) {
        try {
            sendFrame(SignalingMessageV2.Candidate(candidateJson))
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun sendFrame(message: SignalingMessageV2) {
        if (closed.get()) return
        signalingSession.send(message) { result ->
            result.exceptionOrNull()?.let { failure ->
                if (!closed.get()) {
                    notifyDisconnected(
                        failure as? IOException ?: IOException("signaling send failed", failure)
                    )
                }
            }
        }
    }

    private fun mediaSessionOrThrow(): RiderMediaSession =
        mediaSession ?: throw IOException("WebRTC media session 未初始化")

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
    requireClaimedDeviceId: Boolean = false,
    expectedRemoteRuntimeSessionId: RuntimeSessionId? = null
): PeerIdentity {
    val expected = expectedRemoteDeviceId?.trim()?.takeIf(String::isNotEmpty)
    val claimed = message.deviceId?.trim()?.takeIf(String::isNotEmpty)
    if (expected != null && claimed != null && expected != claimed) {
        throw SignalingProtocol.ProtocolException(
            "remote deviceId mismatch: expected=$expected claimed=$claimed"
        )
    }
    if (requireClaimedDeviceId && claimed == null) {
        throw SignalingProtocol.ProtocolException("remote deviceId is missing")
    }
    val remoteRuntimeSessionId = message.runtimeSessionId
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::RuntimeSessionId)
    if (
        (requireClaimedDeviceId || expectedRemoteRuntimeSessionId != null) &&
        remoteRuntimeSessionId == null
    ) {
        throw SignalingProtocol.ProtocolException("remote runtimeSessionId is missing")
    }
    if (
        expectedRemoteRuntimeSessionId != null &&
        remoteRuntimeSessionId != null &&
        expectedRemoteRuntimeSessionId != remoteRuntimeSessionId
    ) {
        throw SignalingProtocol.ProtocolException(
            "remote runtimeSessionId mismatch: " +
                "expected=${expectedRemoteRuntimeSessionId.value} " +
                "claimed=${remoteRuntimeSessionId.value}"
        )
    }
    val hasExtendedIdentity = claimed != null && remoteRuntimeSessionId != null
    return PeerIdentity(
        deviceId = claimed,
        nickname = message.name,
        deviceName = message.deviceName,
        runtimeSessionId = remoteRuntimeSessionId,
        isDeviceIdVerified = hasExtendedIdentity
    )
}
