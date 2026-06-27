package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 前后座全双工语音媒体层。
 *
 * 第一阶段的 WifiDirectTunnel 只负责 TCP 信令通道；这里用 WebRTC 负责麦克风采集、
 * 3A、Opus 编码、RTP/UDP 传输和远端播放。
 */
class RiderAudioEngine(
    context: Context,
    private val onLocalSdpGenerated: (sdpJson: String) -> Unit,
    private val onLocalIceCandidateGenerated: (candidateJson: String) -> Unit,
    private val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onRemoteAudioTrack: (AudioTrack) -> Unit = {},
    private val onAudioLevelChanged: (Float) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rtc: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val oldMode = audioManager.mode
    @Suppress("DEPRECATION")
    private val oldSpeakerphone = audioManager.isSpeakerphoneOn

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RtpSender? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val closed = AtomicBoolean(false)
    private val voxAudioEnabled = AtomicBoolean(true)
    private var lastAudioLevelAt = 0L

    init {
        runRtc {
            require(hasRequiredPermissions(appContext)) { "缺少 RECORD_AUDIO 运行时权限" }
            initFactory()
            createPeerConnection()
            createLocalAudioTrack()
        }
    }

    fun createOffer() {
        runRtc {
            peerConnectionOrThrow().createOffer(localSdpObserver(), sdpConstraints())
        }
    }

    fun createAnswer(remoteSdpJson: String) {
        runRtc {
            setRemoteDescription(remoteSdpJson) {
                peerConnectionOrThrow().createAnswer(localSdpObserver(), sdpConstraints())
            }
        }
    }

    fun setRemoteAnswer(remoteSdpJson: String) {
        runRtc {
            setRemoteDescription(remoteSdpJson) {}
        }
    }

    fun addRemoteIceCandidate(candidateJson: String) {
        runRtc {
            try {
                val candidate = candidateFromJson(candidateJson)
                if (remoteDescriptionSet) {
                    check(peerConnectionOrThrow().addIceCandidate(candidate)) {
                        "addIceCandidate 返回 false"
                    }
                    Log.i(TAG, "addIceCandidate 成功")
                } else {
                    pendingRemoteCandidates += candidate
                    Log.i(TAG, "addIceCandidate 等待远端描述")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "addIceCandidate 失败", t)
                throw t
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runRtc(allowClosed = true) {
            try {
                peerConnection?.dispose()
                localAudioTrack?.dispose()
                audioSource?.dispose()
                factory?.dispose()
                audioDeviceModule?.release()
            } catch (t: Throwable) {
                postError(t)
            } finally {
                peerConnection = null
                localAudioTrack = null
                audioSource = null
                factory = null
                audioDeviceModule = null
                restoreAndroidAudio()
                rtc.shutdown()
            }
        }
    }

    private fun runRtc(allowClosed: Boolean = false, block: () -> Unit) {
        try {
            rtc.execute {
                try {
                    if (allowClosed || !closed.get()) block()
                } catch (t: Throwable) {
                    postError(t)
                }
            }
        } catch (t: Throwable) {
            postError(t)
        }
    }

    private fun initFactory() = mediaStep("PeerConnectionFactory 初始化") {
        initWebRtcOnce(appContext)

        configureAndroidAudio()

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            // 低延迟优先；耳机/头盔链路里这比高保真更重要。
            .setUseLowLatency(true)
            // 采集端 PCM 钩子：在 WebRTC 编码前做 VOX 门限判断。
            // 注意这里只做 RMS 计算和状态翻转，避免在音频线程里执行重活。
            .setSamplesReadyCallback(JavaAudioDeviceModule.SamplesReadyCallback { samples ->
                handleAudioSamplesForVox(samples)
            })
            // 优先启用设备硬件 AEC/NS；不支持时 WebRTC 会回退到软件处理。
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() = mediaStep("PeerConnection 创建") {
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            // 无公网、无服务器：只产出 Wi-Fi Direct 局域网 host candidate。
            iceServers = emptyList()
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factoryOrThrow().createPeerConnection(config, observer())
            ?: error("创建 PeerConnection 失败")
        peerConnection!!.setAudioRecording(true)
        peerConnection!!.setAudioPlayout(true)
    }

    private fun createLocalAudioTrack() = mediaStep("local audio track 创建") {
        // 3A 开关：AEC 回声消除、ANS 噪声抑制、AGC 自动增益。
        // Android Java API 不暴露“噪声抑制等级”；摩托车风噪要更狠时，需要自编 WebRTC
        // 并注入 AudioProcessingFactory。这里使用官方 AAR 能稳定拿到的最高层开关。
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSupression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoisesuppression2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        audioSource = factoryOrThrow().createAudioSource(constraints)
        localAudioTrack = factoryOrThrow().createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
        }
        voxAudioEnabled.set(true)

        localAudioSender = peerConnectionOrThrow().addTrack(localAudioTrack, listOf(STREAM_ID))
        setOpusBitrate(localAudioSender)
    }

    private fun handleAudioSamplesForVox(samples: JavaAudioDeviceModule.AudioSamples) {
        val db = calculateApproxDb(samples) ?: return
        postAudioLevel(db)

        // 45dB 固定门限：低于门限认为是环境底噪/风噪，关闭本地音轨停止向对端发包；
        // 达到门限立即恢复，满足免按键 Hands-Free VOX 对讲。
        val shouldEnable = db >= VOX_THRESHOLD_DB
        if (voxAudioEnabled.compareAndSet(!shouldEnable, shouldEnable)) {
            runRtc {
                localAudioTrack?.setEnabled(shouldEnable)
            }
        }
    }

    private fun postAudioLevel(db: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudioLevelAt < AUDIO_LEVEL_INTERVAL_MS) return
        lastAudioLevelAt = now
        val level = (db / PCM_DBFS_TO_APPROX_SPL_OFFSET).toFloat().coerceIn(0f, 1f)
        mainHandler.post { onAudioLevelChanged(level) }
    }

    private fun calculateApproxDb(samples: JavaAudioDeviceModule.AudioSamples): Double? {
        if (samples.audioFormat != AudioFormat.ENCODING_PCM_16BIT) return null

        val data = samples.data
        if (data.size < 2) return null

        var sumSquares = 0.0
        var count = 0
        var index = 0
        while (index + 1 < data.size) {
            val low = data[index].toInt() and 0xFF
            val high = data[index + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
            count++
            index += 2
        }
        if (count == 0) return null

        val rms = sqrt(sumSquares / count)
        if (rms <= 0.0) return 0.0

        // 手机麦克风没有统一 SPL 校准值，这里用 16-bit PCM dBFS + 90dB 偏移得到工程近似 dB。
        // 阈值固定为 45dB，目标是挡住持续低能量风噪，而不是做声学测量仪。
        return (20.0 * log10(rms / Short.MAX_VALUE.toDouble()) + PCM_DBFS_TO_APPROX_SPL_OFFSET)
            .coerceAtLeast(0.0)
    }

    private fun localSdpObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            val local = SessionDescription(sdp.type, forceOpus32k(sdp.description))
            peerConnectionOrThrow().setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    mainHandler.post { onLocalSdpGenerated(local.toJson()) }
                }
            }, local)
        }

        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = postError(IllegalStateException(error))
        override fun onSetFailure(error: String) = postError(IllegalStateException(error))
    }

    private fun setRemoteDescription(remoteSdpJson: String, onSet: () -> Unit) {
        try {
            Log.i(TAG, "setRemoteDescription 开始")
            val remote = sessionDescriptionFromJson(remoteSdpJson)
            peerConnectionOrThrow().setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    Log.i(TAG, "setRemoteDescription 成功")
                    remoteDescriptionSet = true
                    pendingRemoteCandidates.forEach {
                        if (!peerConnectionOrThrow().addIceCandidate(it)) {
                            Log.e(TAG, "补交 ICE candidate 失败")
                        }
                    }
                    pendingRemoteCandidates.clear()
                    onSet()
                }

                override fun onSetFailure(error: String) {
                    val failure = IllegalStateException("setRemoteDescription 失败: $error")
                    Log.e(TAG, failure.message, failure)
                    postError(failure)
                }
            }, remote)
        } catch (t: Throwable) {
            Log.e(TAG, "setRemoteDescription 调用失败", t)
            throw t
        }
    }

    private fun observer(): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            mainHandler.post { onLocalIceCandidateGenerated(candidate.toJson()) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            mainHandler.post { onConnectionStateChanged(newState) }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            enableRemoteTrack(transceiver.receiver.track())
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            enableRemoteTrack(receiver.track())
        }

        override fun onAddStream(stream: MediaStream) {
            stream.audioTracks.forEach { enableRemoteTrack(it) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(dataChannel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun enableRemoteTrack(track: MediaStreamTrack?) {
        if (track is AudioTrack) {
            track.setEnabled(true)
            mainHandler.post { onRemoteAudioTrack(track) }
        }
    }

    private fun sdpConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private fun setOpusBitrate(sender: RtpSender?) {
        val parameters = sender?.parameters ?: return
        parameters.encodings.forEach {
            it.maxBitrateBps = OPUS_BITRATE_BPS
            it.minBitrateBps = OPUS_BITRATE_BPS
        }
        sender.setParameters(parameters)
    }

    private fun forceOpus32k(sdp: String): String {
        val lines = sdp.replace("\r\n", "\n").split('\n').filter { it.isNotEmpty() }.toMutableList()
        val opusPayload = lines.firstOrNull {
            it.startsWith("a=rtpmap:") && it.contains("opus/48000", ignoreCase = true)
        }?.substringAfter("a=rtpmap:")?.substringBefore(' ') ?: return sdp

        val audioIndex = lines.indexOfFirst { it.startsWith("m=audio ") }
        if (audioIndex >= 0) {
            val parts = lines[audioIndex].split(' ').toMutableList()
            val header = parts.take(3)
            val payloads = parts.drop(3).filterNot { it == opusPayload }
            lines[audioIndex] = (header + opusPayload + payloads).joinToString(" ")
        }

        val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$opusPayload") }
        if (fmtpIndex >= 0) {
            lines[fmtpIndex] = mergeOpusFmtp(lines[fmtpIndex])
        } else {
            val rtpmapIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPayload") }
            lines.add(rtpmapIndex + 1, "a=fmtp:$opusPayload ${OPUS_FMTP}")
        }
        return lines.joinToString("\r\n", postfix = "\r\n")
    }

    private fun mergeOpusFmtp(line: String): String {
        val prefix = line.substringBefore(' ')
        val params = linkedMapOf<String, String>()
        line.substringAfter(' ', "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach {
                val key = it.substringBefore('=')
                val value = it.substringAfter('=', "")
                params[key] = value
            }
        OPUS_FMTP.split(';').map { it.trim() }.forEach {
            params[it.substringBefore('=')] = it.substringAfter('=', "")
        }
        return prefix + " " + params.entries.joinToString(";") {
            if (it.value.isEmpty()) it.key else "${it.key}=${it.value}"
        }
    }

    private fun configureAndroidAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
    }

    private fun restoreAndroidAudio() {
        audioManager.mode = oldMode
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = oldSpeakerphone
    }

    private fun factoryOrThrow(): PeerConnectionFactory =
        factory ?: error("PeerConnectionFactory 尚未初始化")

    private fun peerConnectionOrThrow(): PeerConnection =
        peerConnection ?: error("PeerConnection 尚未初始化")

    private fun postError(t: Throwable) {
        Log.e(TAG, "WebRTC 媒体层错误", t)
        mainHandler.post { onError(t) }
    }

    private inline fun <T> mediaStep(name: String, block: () -> T): T {
        Log.i(TAG, "$name 开始")
        return try {
            block().also { Log.i(TAG, "$name 成功") }
        } catch (t: Throwable) {
            Log.e(TAG, "$name 失败", t)
            throw t
        }
    }

    private fun SessionDescription.toJson(): String = JSONObject()
        .put("type", type.canonicalForm())
        .put("sdp", description)
        .toString()

    private fun IceCandidate.toJson(): String = JSONObject()
        .put("sdpMid", sdpMid)
        .put("sdpMLineIndex", sdpMLineIndex)
        .put("candidate", sdp)
        .toString()

    private fun sessionDescriptionFromJson(json: String): SessionDescription {
        val obj = JSONObject(json)
        return SessionDescription(
            SessionDescription.Type.fromCanonicalForm(obj.getString("type")),
            obj.getString("sdp")
        )
    }

    private fun candidateFromJson(json: String): IceCandidate {
        val obj = JSONObject(json)
        return IceCandidate(
            obj.getString("sdpMid"),
            obj.getInt("sdpMLineIndex"),
            obj.getString("candidate")
        )
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val TAG = "RiderAudioEngine"
        private const val STREAM_ID = "rider_audio_stream"
        private const val AUDIO_TRACK_ID = "rider_audio_track"
        private const val OPUS_BITRATE_BPS = 32_000
        private const val VOX_THRESHOLD_DB = 45.0
        private const val PCM_DBFS_TO_APPROX_SPL_OFFSET = 90.0
        private const val AUDIO_LEVEL_INTERVAL_MS = 80L
        private const val OPUS_FMTP =
            "minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0;sprop-stereo=0"
        private val webRtcInitialized = AtomicBoolean(false)

        fun requiredPermissions(): Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

        fun hasRequiredPermissions(context: Context): Boolean {
            return requiredPermissions().all {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun initWebRtcOnce(context: Context) {
            if (webRtcInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .createInitializationOptions()
                )
            }
        }
    }
}
