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
import org.webrtc.NetworkMonitorAutoDetect
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    private val voxState = AtomicReference(if (VOX_GATE_ENABLED) VoxState.LISTENING else VoxState.BYPASS)
    private val voxVolumeOpen = AtomicBoolean(!VOX_GATE_ENABLED)
    private var lastAudioLevelAt = 0L
    private var lastVoxLogAt = 0L
    private var voxNoiseFloor = VOX_INITIAL_NOISE_FLOOR
    private var voxAttackStartedAt = 0L
    private var voxAboveCloseStartedAt = 0L
    private var voxLastVoiceAt = 0L
    private var voxHangoverStartedAt = 0L
    private var voxCalibrationUntil = 0L

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
            Log.i(TAG, "createOffer 开始")
            peerConnectionOrThrow().createOffer(localSdpObserver(), sdpConstraints())
        }
    }

    fun createAnswer(remoteSdpJson: String) {
        runRtc {
            Log.i(TAG, "createAnswer 收到远端 Offer")
            setRemoteDescription(remoteSdpJson) {
                Log.i(TAG, "createAnswer 开始")
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
                Log.i(TAG, "收到远端 ICE candidate: ${candidateSummary(candidate)}")
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
        NetworkMonitorAutoDetect.setIncludeWifiDirect(true)
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
            setVolume(if (VOX_GATE_ENABLED) VOX_MUTED_VOLUME else VOX_OPEN_VOLUME)
        }
        val initialState = if (VOX_GATE_ENABLED) VoxState.LISTENING else VoxState.BYPASS
        voxState.set(initialState)
        voxVolumeOpen.set(!VOX_GATE_ENABLED)
        Log.i(
            TAG,
            "VOX 初始化 enabled=$VOX_GATE_ENABLED state=$initialState " +
                "trackEnabled=true volume=${if (VOX_GATE_ENABLED) VOX_MUTED_VOLUME else VOX_OPEN_VOLUME}"
        )

        localAudioSender = peerConnectionOrThrow().addTrack(localAudioTrack, listOf(STREAM_ID))
        setOpusBitrate(localAudioSender)
    }

    private fun handleAudioSamplesForVox(samples: JavaAudioDeviceModule.AudioSamples) {
        val energy = calculateApproxDb(samples) ?: return
        postAudioLevel(energy)

        val now = SystemClock.elapsedRealtime()
        if (!VOX_GATE_ENABLED) {
            logVoxSnapshot(now, energy, VOX_BASE_OPEN_THRESHOLD, VOX_BASE_OPEN_THRESHOLD - VOX_HYSTERESIS)
            return
        }

        if (voxCalibrationUntil == 0L) {
            voxCalibrationUntil = now + VOX_CALIBRATION_MS
        }

        var openThreshold = maxOf(VOX_BASE_OPEN_THRESHOLD, voxNoiseFloor + VOX_NOISE_MARGIN)
        var closeThreshold = openThreshold - VOX_HYSTERESIS

        when (voxState.get()) {
            VoxState.BYPASS -> Unit

            VoxState.LISTENING -> {
                val calibrating = now < voxCalibrationUntil
                val alpha = if (calibrating) VOX_CALIBRATION_ALPHA else VOX_NOISE_ALPHA
                if (calibrating || energy < openThreshold) {
                    voxNoiseFloor = (voxNoiseFloor + alpha * (energy - voxNoiseFloor))
                        .coerceIn(VOX_MIN_NOISE_FLOOR, VOX_MAX_NOISE_FLOOR)
                    openThreshold = maxOf(VOX_BASE_OPEN_THRESHOLD, voxNoiseFloor + VOX_NOISE_MARGIN)
                    closeThreshold = openThreshold - VOX_HYSTERESIS
                }

                if (!calibrating && energy >= openThreshold) {
                    if (voxAttackStartedAt == 0L) voxAttackStartedAt = now
                    if (now - voxAttackStartedAt >= VOX_ATTACK_MS) {
                        voxAttackStartedAt = 0L
                        transitionVoxState(VoxState.OPEN, energy, openThreshold, closeThreshold)
                    }
                } else {
                    voxAttackStartedAt = 0L
                }
            }

            VoxState.OPEN -> {
                if (energy >= closeThreshold) {
                    if (voxAboveCloseStartedAt == 0L) voxAboveCloseStartedAt = now
                    if (now - voxAboveCloseStartedAt >= VOX_ATTACK_MS) voxLastVoiceAt = now
                } else {
                    voxAboveCloseStartedAt = 0L
                }

                if (now - voxLastVoiceAt >= VOX_RELEASE_DEBOUNCE_MS) {
                    voxAboveCloseStartedAt = 0L
                    voxHangoverStartedAt = now
                    transitionVoxState(VoxState.HANGOVER, energy, openThreshold, closeThreshold)
                }
            }

            VoxState.HANGOVER -> {
                if (energy >= openThreshold) {
                    if (voxAttackStartedAt == 0L) voxAttackStartedAt = now
                    if (now - voxAttackStartedAt >= VOX_ATTACK_MS) {
                        voxAttackStartedAt = 0L
                        voxHangoverStartedAt = 0L
                        transitionVoxState(VoxState.OPEN, energy, openThreshold, closeThreshold)
                    }
                } else {
                    voxAttackStartedAt = 0L
                }

                if (energy >= closeThreshold) {
                    if (voxAboveCloseStartedAt == 0L) voxAboveCloseStartedAt = now
                    if (now - voxAboveCloseStartedAt >= VOX_ATTACK_MS) {
                        voxHangoverStartedAt = now
                    }
                } else {
                    voxAboveCloseStartedAt = 0L
                }

                if (voxState.get() == VoxState.HANGOVER && now - voxHangoverStartedAt >= VOX_HANGOVER_MS) {
                    voxAttackStartedAt = 0L
                    voxAboveCloseStartedAt = 0L
                    voxHangoverStartedAt = 0L
                    transitionVoxState(VoxState.LISTENING, energy, openThreshold, closeThreshold)
                }
            }
        }

        logVoxSnapshot(now, energy, openThreshold, closeThreshold)
    }

    private fun transitionVoxState(
        next: VoxState,
        energy: Double,
        openThreshold: Double,
        closeThreshold: Double
    ) {
        val previous = voxState.getAndSet(next)
        if (previous == next) return

        if (next == VoxState.OPEN) {
            val now = SystemClock.elapsedRealtime()
            voxAboveCloseStartedAt = now
            voxLastVoiceAt = now
        } else if (next == VoxState.LISTENING) {
            voxAboveCloseStartedAt = 0L
            voxLastVoiceAt = 0L
        }

        val shouldOpenVolume = next != VoxState.LISTENING
        val oldVolume = if (voxVolumeOpen.get()) VOX_OPEN_VOLUME else VOX_MUTED_VOLUME
        val newVolume = if (shouldOpenVolume) VOX_OPEN_VOLUME else VOX_MUTED_VOLUME
        if (voxVolumeOpen.compareAndSet(!shouldOpenVolume, shouldOpenVolume)) {
            runRtc { localAudioTrack?.setVolume(newVolume) }
        }

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "VOX state %s -> %s energy=%.1f noise=%.1f open=%.1f close=%.1f volume=%.1f->%.1f",
                previous,
                next,
                energy,
                voxNoiseFloor,
                openThreshold,
                closeThreshold,
                oldVolume,
                newVolume
            )
        )
    }

    private fun logVoxSnapshot(now: Long, energy: Double, openThreshold: Double, closeThreshold: Double) {
        if (now - lastVoxLogAt < VOX_LOG_INTERVAL_MS) return
        lastVoxLogAt = now
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "VOX enabled=%s state=%s energy=%.1f noise=%.1f open=%.1f close=%.1f volume=%.1f",
                VOX_GATE_ENABLED,
                voxState.get(),
                energy,
                voxNoiseFloor,
                openThreshold,
                closeThreshold,
                if (voxVolumeOpen.get()) VOX_OPEN_VOLUME else VOX_MUTED_VOLUME
            )
        )
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

        // 手机麦克风没有统一 SPL 校准值；这是 dBFS + 固定偏移的工程能量值，不是真实 dB SPL。
        return (20.0 * log10(rms / Short.MAX_VALUE.toDouble()) + PCM_DBFS_TO_APPROX_SPL_OFFSET)
            .coerceAtLeast(0.0)
    }

    private enum class VoxState {
        BYPASS,
        LISTENING,
        OPEN,
        HANGOVER
    }

    private fun localSdpObserver(): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            Log.i(TAG, "${sdp.type} 创建成功: ${sdpSummary(sdp.description)}")
            val local = SessionDescription(sdp.type, forceOpus32k(sdp.description))
            peerConnectionOrThrow().setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    Log.i(TAG, "setLocalDescription ${local.type} 成功: ${sdpSummary(local.description)}")
                    mainHandler.post { onLocalSdpGenerated(local.toJson()) }
                }

                override fun onSetFailure(error: String) {
                    val failure = IllegalStateException("setLocalDescription ${local.type} 失败: $error")
                    Log.e(TAG, failure.message, failure)
                    postError(failure)
                }
            }, local)
        }

        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) {
            val failure = IllegalStateException("创建 SDP 失败: $error")
            Log.e(TAG, failure.message, failure)
            postError(failure)
        }

        override fun onSetFailure(error: String) {
            val failure = IllegalStateException("设置 SDP 失败: $error")
            Log.e(TAG, failure.message, failure)
            postError(failure)
        }
    }

    private fun setRemoteDescription(remoteSdpJson: String, onSet: () -> Unit) {
        try {
            val remote = sessionDescriptionFromJson(remoteSdpJson)
            Log.i(TAG, "setRemoteDescription ${remote.type} 开始: ${sdpSummary(remote.description)}")
            peerConnectionOrThrow().setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    Log.i(TAG, "setRemoteDescription 成功")
                    remoteDescriptionSet = true
                    pendingRemoteCandidates.forEach {
                        if (!peerConnectionOrThrow().addIceCandidate(it)) {
                            Log.e(TAG, "补交 ICE candidate 失败")
                        } else {
                            Log.i(TAG, "补交 ICE candidate 成功: ${candidateSummary(it)}")
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
            Log.i(TAG, "生成本地 ICE candidate: ${candidateSummary(candidate)}")
            mainHandler.post { onLocalIceCandidateGenerated(candidate.toJson()) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "PeerConnection state=$newState")
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

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.i(TAG, "Signaling state=$state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ICE connection state=$state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.i(TAG, "ICE receiving=$receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(TAG, "ICE gathering state=$state")
        }
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

    private fun candidateSummary(candidate: IceCandidate): String =
        "mid=${candidate.sdpMid} mLine=${candidate.sdpMLineIndex} ${candidate.sdp}"

    private fun sdpSummary(sdp: String): String {
        val lines = sdp.lineSequence()
            .filter {
                it.startsWith("m=") ||
                    it.startsWith("c=") ||
                    it.startsWith("a=candidate:") ||
                    it.startsWith("a=ice-ufrag:")
            }
            .take(12)
            .toList()
        return lines.joinToString(" | ").ifBlank { "无候选摘要" }
    }

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
        private const val VOX_GATE_ENABLED = true
        private const val VOX_OPEN_VOLUME = 1.0
        private const val VOX_MUTED_VOLUME = 0.0
        private const val VOX_BASE_OPEN_THRESHOLD = 40.0
        private const val VOX_NOISE_MARGIN = 8.0
        private const val VOX_HYSTERESIS = 5.0
        private const val VOX_ATTACK_MS = 25L
        private const val VOX_RELEASE_DEBOUNCE_MS = 120L
        private const val VOX_HANGOVER_MS = 700L
        private const val VOX_CALIBRATION_MS = 500L
        private const val VOX_CALIBRATION_ALPHA = 0.10
        private const val VOX_NOISE_ALPHA = 0.02
        private const val VOX_INITIAL_NOISE_FLOOR = 32.0
        private const val VOX_MIN_NOISE_FLOOR = 20.0
        private const val VOX_MAX_NOISE_FLOOR = 55.0
        private const val VOX_LOG_INTERVAL_MS = 1_000L
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
