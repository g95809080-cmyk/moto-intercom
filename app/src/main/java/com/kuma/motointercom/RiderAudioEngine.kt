package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
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
import kotlin.math.log10
import kotlin.math.sqrt

internal data class RiderMediaSessionCallbacks(
    val onLocalSdpGenerated: (sdpJson: String) -> Unit,
    val onLocalIceCandidateGenerated: (candidateJson: String) -> Unit,
    val onConnectionStateChanged: (PeerConnection.PeerConnectionState) -> Unit = {},
    val onRemoteAudioTrack: (AudioTrack) -> Unit = {},
    val onAudioLevelChanged: (Float) -> Unit = {},
    val onError: (Throwable) -> Unit = {},
    val isSessionCurrent: () -> Boolean
)

internal interface RiderMediaSession : Closeable {
    fun createOffer()
    fun createAnswer(remoteSdpJson: String)
    fun setRemoteAnswer(remoteSdpJson: String)
    fun addRemoteIceCandidate(candidateJson: String)
}

internal interface RiderMediaEngine : Closeable {
    fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession
}

internal fun runAllCleanupSteps(vararg steps: () -> Unit) {
    var failure: Throwable? = null
    steps.forEach { step ->
        runCatching(step).exceptionOrNull()?.let {
            if (failure == null) failure = it else failure?.addSuppressed(it)
        }
    }
    failure?.let { throw it }
}

/**
 * Online-runtime audio platform owner with one replaceable WebRTC media session.
 *
 * The audio device module, factory, local source/track, and VOX state stay alive
 * until the online runtime stops. PeerConnection and signaling callbacks belong
 * to the current [RiderMediaSession].
 */
internal class RiderAudioEngine(
    context: Context,
    private val onEngineError: (Throwable) -> Unit = {},
    private val isRuntimeCurrent: () -> Boolean = { true }
) : RiderMediaEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rtc: ExecutorService = Executors.newSingleThreadExecutor()

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RtpSender? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val closed = AtomicBoolean(false)
    private val voxGate = VoxGate(enabled = VOX_GATE_ENABLED)
    private var engineState = EngineState.INITIALIZING
    private var lastAudioLevelAt = 0L
    private var lastVoxLogAt = 0L
    private val sessionLock = Any()
    private var activeSession: MediaSession? = null

    init {
        runRtc { initializeRtc() }
    }

    private fun initializeRtc() {
        try {
            require(hasRequiredPermissions(appContext)) { "缺少 RECORD_AUDIO 运行时权限" }
            initFactory()
            createLocalAudioTrack()
            engineState = EngineState.READY
        } catch (t: Throwable) {
            engineState = EngineState.FAILED
            runCatching(::disposePlatformResources).exceptionOrNull()?.let(t::addSuppressed)
            postEngineError(t)
        }
    }

    override fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession {
        check(!closed.get()) { "WebRTC engine is closed" }
        val session = MediaSession(callbacks)
        synchronized(sessionLock) {
            check(activeSession == null) { "a WebRTC media session is already active" }
            activeSession = session
        }
        runRtc(onFailure = session::postError) {
            initializeMediaSession(session)
        }
        return session
    }

    private fun initializeMediaSession(session: MediaSession) {
        if (!isActiveSession(session)) return
        try {
            requireEngineReady()
            createPeerConnection(session)
            attachLocalAudioTrack()
            session.state = MediaSessionState.READY
        } catch (t: Throwable) {
            session.state = MediaSessionState.FAILED
            runCatching(::disposeMediaSessionResources).exceptionOrNull()?.let(t::addSuppressed)
            session.postError(t)
        }
    }

    private fun createOffer(session: MediaSession) {
        runSession(session) {
            requireSessionReady(session)
            Log.i(TAG, "createOffer 开始")
            peerConnectionOrThrow().createOffer(localSdpObserver(session), sdpConstraints())
        }
    }

    private fun createAnswer(session: MediaSession, remoteSdpJson: String) {
        runSession(session) {
            requireSessionReady(session)
            Log.i(TAG, "createAnswer 收到远端 Offer")
            setRemoteDescription(session, remoteSdpJson) {
                Log.i(TAG, "createAnswer 开始")
                peerConnectionOrThrow().createAnswer(localSdpObserver(session), sdpConstraints())
            }
        }
    }

    private fun setRemoteAnswer(session: MediaSession, remoteSdpJson: String) {
        runSession(session) {
            requireSessionReady(session)
            setRemoteDescription(session, remoteSdpJson) {}
        }
    }

    private fun addRemoteIceCandidate(session: MediaSession, candidateJson: String) {
        runSession(session) {
            requireSessionReady(session)
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
        val session = synchronized(sessionLock) {
            activeSession.also { activeSession = null }
        }
        session?.markClosed()
        runRtc(allowClosed = true, onFailure = ::postEngineError) {
            try {
                engineState = EngineState.CLOSED
                runAllCleanupSteps(
                    ::disposeMediaSessionResources,
                    ::disposePlatformResources
                )
            } catch (t: Throwable) {
                Log.e(TAG, "WebRTC 资源关闭失败", t)
            } finally {
                rtc.shutdown()
            }
        }
    }

    private fun disposeMediaSessionResources() {
        val peer = peerConnection
        peerConnection = null
        localAudioSender = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        peer?.dispose()
    }

    private fun disposePlatformResources() {
        val track = localAudioTrack
        val source = audioSource
        val connectionFactory = factory
        val deviceModule = audioDeviceModule
        localAudioTrack = null
        audioSource = null
        factory = null
        audioDeviceModule = null

        var failure: Throwable? = null
        fun dispose(action: () -> Unit) {
            runCatching(action).exceptionOrNull()?.let {
                if (failure == null) failure = it else failure?.addSuppressed(it)
            }
        }
        track?.let { dispose(it::dispose) }
        source?.let { dispose(it::dispose) }
        connectionFactory?.let { dispose(it::dispose) }
        deviceModule?.let { dispose(it::release) }
        failure?.let { throw it }
    }

    private fun requireEngineReady() {
        check(engineState == EngineState.READY) { "WebRTC engine state=$engineState" }
    }

    private fun requireSessionReady(session: MediaSession) {
        requireEngineReady()
        check(session.state == MediaSessionState.READY) {
            "WebRTC media session state=${session.state}"
        }
    }

    private fun runSession(session: MediaSession, block: () -> Unit) {
        runRtc(onFailure = session::postError) {
            if (isActiveSession(session)) block()
        }
    }

    private fun isActiveSession(session: MediaSession): Boolean =
        !session.closed.get() && synchronized(sessionLock) { activeSession === session }

    private fun runRtc(
        allowClosed: Boolean = false,
        onFailure: (Throwable) -> Unit = ::postEngineError,
        block: () -> Unit
    ) {
        try {
            rtc.execute {
                try {
                    if (allowClosed || !closed.get()) block()
                } catch (t: Throwable) {
                    onFailure(t)
                }
            }
        } catch (t: Throwable) {
            onFailure(t)
        }
    }

    private fun initFactory() = mediaStep("PeerConnectionFactory 初始化") {
        NetworkMonitorAutoDetect.setIncludeWifiDirect(true)
        initWebRtcOnce(appContext)

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

    private fun createPeerConnection(session: MediaSession) = mediaStep("PeerConnection 创建") {
        check(peerConnection == null) { "previous PeerConnection is still active" }
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

        peerConnection = factoryOrThrow().createPeerConnection(config, observer(session))
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
        Log.i(
            TAG,
            "VOX 初始化 enabled=$VOX_GATE_ENABLED " +
                "trackEnabled=true volume=${if (VOX_GATE_ENABLED) VOX_MUTED_VOLUME else VOX_OPEN_VOLUME}"
        )

    }

    private fun attachLocalAudioTrack() = mediaStep("local audio track attach") {
        localAudioSender = peerConnectionOrThrow().addTrack(
            localAudioTrack ?: error("local audio track 尚未初始化"),
            listOf(STREAM_ID)
        )
        setOpusBitrate(localAudioSender)
    }

    private fun handleAudioSamplesForVox(samples: JavaAudioDeviceModule.AudioSamples) {
        val energy = calculateApproxDb(samples) ?: return
        val now = SystemClock.elapsedRealtime()
        val decision = voxGate.update(energy, now)
        if (decision.stateChanged) {
            runRtc {
                if (engineState == EngineState.READY) {
                    localAudioTrack?.setVolume(decision.trackVolume)
                }
            }
            Log.i(
                TAG,
                String.format(
                    Locale.US,
                    "VOX state=%s energy=%.1f noise=%.1f open=%.1f close=%.1f volume=%.1f",
                    decision.state,
                    energy,
                    decision.noiseFloor,
                    decision.openThreshold,
                    decision.closeThreshold,
                    decision.trackVolume
                )
            )
        }
        postAudioLevel(energy)
        logVoxSnapshot(now, energy, decision)
    }

    private fun logVoxSnapshot(now: Long, energy: Double, decision: VoxGate.Decision) {
        if (now - lastVoxLogAt < VOX_LOG_INTERVAL_MS) return
        lastVoxLogAt = now
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "VOX enabled=%s state=%s energy=%.1f noise=%.1f open=%.1f close=%.1f volume=%.1f",
                VOX_GATE_ENABLED,
                decision.state,
                energy,
                decision.noiseFloor,
                decision.openThreshold,
                decision.closeThreshold,
                decision.trackVolume
            )
        )
    }

    private fun postAudioLevel(db: Double) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAudioLevelAt < AUDIO_LEVEL_INTERVAL_MS) return
        lastAudioLevelAt = now
        val level = (db / PCM_DBFS_TO_APPROX_SPL_OFFSET).toFloat().coerceIn(0f, 1f)
        val session = synchronized(sessionLock) { activeSession } ?: return
        postSessionMain(session) { session.callbacks.onAudioLevelChanged(level) }
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

    private fun localSdpObserver(session: MediaSession): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            runSession(session) {
                requireSessionReady(session)
                Log.i(TAG, "${sdp.type} 创建成功: ${sdpSummary(sdp.description)}")
                val local = SessionDescription(sdp.type, forceOpus32k(sdp.description))
                peerConnectionOrThrow().setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        runSession(session) {
                            if (session.state != MediaSessionState.READY) return@runSession
                            Log.i(TAG, "setLocalDescription ${local.type} 成功: ${sdpSummary(local.description)}")
                            postSessionMain(session) {
                                session.callbacks.onLocalSdpGenerated(local.toJson())
                            }
                        }
                    }

                    override fun onSetFailure(error: String) {
                        runSession(session) {
                            reportSdpFailure(session, "setLocalDescription ${local.type} 失败: $error")
                        }
                    }
                }, local)
            }
        }

        override fun onSetSuccess() {
            runSession(session) { Unit }
        }

        override fun onCreateFailure(error: String) {
            runSession(session) { reportSdpFailure(session, "创建 SDP 失败: $error") }
        }

        override fun onSetFailure(error: String) {
            runSession(session) { reportSdpFailure(session, "设置 SDP 失败: $error") }
        }
    }

    private fun setRemoteDescription(
        session: MediaSession,
        remoteSdpJson: String,
        onSet: () -> Unit
    ) {
        try {
            val remote = sessionDescriptionFromJson(remoteSdpJson)
            Log.i(TAG, "setRemoteDescription ${remote.type} 开始: ${sdpSummary(remote.description)}")
            peerConnectionOrThrow().setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    runSession(session) {
                        if (session.state != MediaSessionState.READY) return@runSession
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
                }

                override fun onSetFailure(error: String) {
                    runSession(session) {
                        reportSdpFailure(session, "setRemoteDescription 失败: $error")
                    }
                }
            }, remote)
        } catch (t: Throwable) {
            Log.e(TAG, "setRemoteDescription 调用失败", t)
            throw t
        }
    }

    private fun observer(session: MediaSession): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            runSession(session) {
                if (session.state != MediaSessionState.READY) return@runSession
                Log.i(TAG, "生成本地 ICE candidate: ${candidateSummary(candidate)}")
                postSessionMain(session) {
                    session.callbacks.onLocalIceCandidateGenerated(candidate.toJson())
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            runSession(session) {
                if (session.state != MediaSessionState.READY) return@runSession
                Log.i(TAG, "PeerConnection state=$newState")
                postSessionMain(session) {
                    session.callbacks.onConnectionStateChanged(newState)
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) {
                    enableRemoteTrack(session, transceiver.receiver.track())
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) {
                    enableRemoteTrack(session, receiver.track())
                }
            }
        }

        override fun onAddStream(stream: MediaStream) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) {
                    stream.audioTracks.forEach { enableRemoteTrack(session, it) }
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) Log.i(TAG, "Signaling state=$state")
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) Log.i(TAG, "ICE connection state=$state")
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) Log.i(TAG, "ICE receiving=$receiving")
            }
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            runSession(session) {
                if (session.state == MediaSessionState.READY) Log.i(TAG, "ICE gathering state=$state")
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
            runSession(session) { if (session.state == MediaSessionState.READY) Unit }
        }
        override fun onRemoveStream(stream: MediaStream) {
            runSession(session) { if (session.state == MediaSessionState.READY) Unit }
        }
        override fun onDataChannel(dataChannel: DataChannel) {
            runSession(session) { if (session.state == MediaSessionState.READY) Unit }
        }
        override fun onRenegotiationNeeded() {
            runSession(session) { if (session.state == MediaSessionState.READY) Unit }
        }
    }

    private fun enableRemoteTrack(session: MediaSession, track: MediaStreamTrack?) {
        if (track is AudioTrack) {
            track.setEnabled(true)
            postSessionMain(session) { session.callbacks.onRemoteAudioTrack(track) }
        }
    }

    private fun reportSdpFailure(session: MediaSession, message: String) {
        if (engineState != EngineState.READY || session.state != MediaSessionState.READY) return
        val failure = IllegalStateException(message)
        Log.e(TAG, failure.message, failure)
        session.postError(failure)
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

    private fun factoryOrThrow(): PeerConnectionFactory =
        factory ?: error("PeerConnectionFactory 尚未初始化")

    private fun peerConnectionOrThrow(): PeerConnection =
        peerConnection ?: error("PeerConnection 尚未初始化")

    private fun postEngineError(t: Throwable) {
        Log.e(TAG, "WebRTC audio platform error", t)
        postRuntimeMain { onEngineError(t) }
    }

    private fun postRuntimeMain(block: () -> Unit) {
        mainHandler.post {
            if (!closed.get() && isRuntimeCurrent()) block()
        }
    }

    private fun postSessionMain(session: MediaSession, block: () -> Unit) {
        mainHandler.post {
            if (
                !closed.get() &&
                isRuntimeCurrent() &&
                isActiveSession(session) &&
                session.callbacks.isSessionCurrent()
            ) {
                block()
            }
        }
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

    private inner class MediaSession(
        val callbacks: RiderMediaSessionCallbacks
    ) : RiderMediaSession {
        val closed = AtomicBoolean(false)
        @Volatile var state = MediaSessionState.INITIALIZING

        override fun createOffer() = this@RiderAudioEngine.createOffer(this)

        override fun createAnswer(remoteSdpJson: String) =
            this@RiderAudioEngine.createAnswer(this, remoteSdpJson)

        override fun setRemoteAnswer(remoteSdpJson: String) =
            this@RiderAudioEngine.setRemoteAnswer(this, remoteSdpJson)

        override fun addRemoteIceCandidate(candidateJson: String) =
            this@RiderAudioEngine.addRemoteIceCandidate(this, candidateJson)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            state = MediaSessionState.CLOSED
            val shouldDispose = synchronized(sessionLock) {
                if (activeSession === this) {
                    activeSession = null
                    true
                } else {
                    false
                }
            }
            if (shouldDispose) {
                runRtc(allowClosed = true, onFailure = ::postEngineError) {
                    disposeMediaSessionResources()
                }
            }
        }

        fun markClosed() {
            if (closed.compareAndSet(false, true)) state = MediaSessionState.CLOSED
        }

        fun postError(t: Throwable) {
            Log.e(TAG, "WebRTC media session error", t)
            postSessionMain(this) { callbacks.onError(t) }
        }
    }

    private open inner class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {
            runRtc { Unit }
        }

        override fun onSetSuccess() {
            runRtc { Unit }
        }

        override fun onCreateFailure(error: String) {
            runRtc { Unit }
        }

        override fun onSetFailure(error: String) {
            runRtc { Unit }
        }
    }

    private enum class EngineState {
        INITIALIZING,
        READY,
        FAILED,
        CLOSED
    }

    private enum class MediaSessionState {
        INITIALIZING,
        READY,
        FAILED,
        CLOSED
    }

    companion object {
        private const val TAG = "RiderAudioEngine"
        private const val STREAM_ID = "rider_audio_stream"
        private const val AUDIO_TRACK_ID = "rider_audio_track"
        private const val OPUS_BITRATE_BPS = 32_000
        private const val VOX_GATE_ENABLED = true
        private const val VOX_OPEN_VOLUME = 1.0
        private const val VOX_MUTED_VOLUME = 0.0
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
