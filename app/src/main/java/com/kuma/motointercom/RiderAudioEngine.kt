package com.kuma.motointercom

import android.Manifest
import android.os.SystemClock
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.ExternalAudioProcessingFactory
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
    val onRemoteAudioFrameReceived: () -> Unit = {},
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
    fun updateAudioControls(controls: VersionedAudioControls)
    fun openSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession
    fun onAudioRouteChanged() = Unit
    fun suspendAudio() = Unit
    fun resumeAudio() = Unit
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

internal fun runAudioPlatformCleanup(
    stopAudio: () -> Unit,
    closeExternalProcessor: () -> Unit,
    disposeTrack: () -> Unit,
    disposeSource: () -> Unit,
    disposeFactory: () -> Unit,
    destroyExternalProcessorFactory: () -> Unit,
    releaseAudioDevice: () -> Unit
) {
    runAllCleanupSteps(
        stopAudio,
        closeExternalProcessor,
        disposeTrack,
        disposeSource,
        disposeFactory,
        destroyExternalProcessorFactory,
        releaseAudioDevice
    )
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
    private val isRuntimeCurrent: () -> Boolean = { true },
    initialAudioControls: VersionedAudioControls = VersionedAudioControls(
        revision = 0,
        settings = AudioControlSettings()
    ),
    private val noiseReductionConfig: AudioNoiseReductionConfig =
        AudioNoiseReductionConfig.baseline(),
    private val onVoxStateChanged: (VersionedAudioControls, VoxRuntimeState) -> Unit = { _, _ -> }
) : RiderMediaEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rtc: ExecutorService = Executors.newSingleThreadExecutor()

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var externalAudioProcessingFactory: ExternalAudioProcessingFactory? = null
    private var externalCaptureProcessor: ExternalAudioProcessingFactory.AudioProcessing? = null
    private var externalProcessorGuard: AudioNoiseExternalProcessorGuard? = null
    private var noiseDiagnostics: AudioNoiseDiagnostics? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RtpSender? = null
    private var remoteDescriptionSet = false
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private val closed = AtomicBoolean(false)
    private val noiseDiagnosticsLogRunnable = object : Runnable {
        override fun run() {
            if (closed.get() || engineState == EngineState.FAILED || engineState == EngineState.CLOSED) {
                return
            }
            noiseDiagnostics?.snapshot()?.let { snapshot ->
                Log.i(TAG, snapshot.toLogLine())
            }
            mainHandler.postDelayed(this, AUDIO_DIAGNOSTIC_LOG_INTERVAL_MS)
        }
    }
    private val audioControlLock = Any()
    private var versionedAudioControls = initialAudioControls.normalized()
    private var audioControls = versionedAudioControls.settings
    private var voxGate = VoxGate(
        enabled = audioControls.voxEnabled,
        sensitivity = audioControls.voxSensitivity
    )
    private var gateState = voxGate.currentState()
    private var lastPublishedVoxSnapshot: Pair<VersionedAudioControls, VoxRuntimeState>? = null
    @Volatile private var engineState = EngineState.INITIALIZING
    @Volatile private var audioSuspended = false
    private var lastAudioLevelAt = 0L
    private var lastVoxLogAt = 0L
    private val sessionLock = Any()
    private var activeSession: MediaSession? = null

    init {
        runRtc { initializeRtc() }
    }

    override fun updateAudioControls(controls: VersionedAudioControls) {
        if (closed.get()) return
        val normalized = controls.normalized()
        val accepted = synchronized(audioControlLock) {
            if (normalized.revision <= versionedAudioControls.revision) {
                return@synchronized false
            }
            if (
                audioControls.voxEnabled != normalized.settings.voxEnabled ||
                audioControls.voxSensitivity != normalized.settings.voxSensitivity
            ) {
                voxGate = VoxGate(
                    enabled = normalized.settings.voxEnabled,
                    sensitivity = normalized.settings.voxSensitivity
                )
                gateState = voxGate.currentState()
            }
            versionedAudioControls = normalized
            audioControls = normalized.settings
            true
        }
        if (!accepted) return
        applyCurrentTrackVolume()
        publishCurrentVoxState()
    }

    override fun suspendAudio() {
        audioSuspended = true
        runRtc {
            peerConnection?.setAudioRecording(false)
            peerConnection?.setAudioPlayout(false)
            audioDeviceModule?.setMicrophoneMute(true)
            audioDeviceModule?.setSpeakerMute(true)
        }
    }

    override fun resumeAudio() {
        audioSuspended = false
        runRtc {
            if (engineState != EngineState.READY) return@runRtc
            audioDeviceModule?.setMicrophoneMute(false)
            audioDeviceModule?.setSpeakerMute(false)
            peerConnection?.setAudioRecording(true)
            peerConnection?.setAudioPlayout(true)
        }
    }

    private fun initializeRtc() {
        try {
            require(hasRequiredPermissions(appContext)) { "缺少 RECORD_AUDIO 运行时权限" }
            initFactory()
            createLocalAudioTrack()
            engineState = EngineState.READY
        } catch (t: Throwable) {
            val experimentalModeActive = noiseDiagnostics?.snapshot()?.activeMode
                ?.takeUnless { it == AudioNoiseReductionMode.BASELINE }
            if (experimentalModeActive != null) {
                runCatching(::disposePlatformResources).exceptionOrNull()?.let(t::addSuppressed)
                noiseDiagnostics?.recordFallback(
                    "experimental_init_failed:${t::class.java.simpleName}"
                )
                try {
                    createAudioPlatform(AudioNoiseReductionMode.BASELINE)
                    createLocalAudioTrack()
                    engineState = EngineState.READY
                    Log.w(TAG, "实验音频初始化失败，已重建 BASELINE", t)
                    return
                } catch (baselineFailure: Throwable) {
                    t.addSuppressed(baselineFailure)
                    runCatching(::disposePlatformResources).exceptionOrNull()
                        ?.let(t::addSuppressed)
                }
            }
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
            runCatching { disposeMediaSessionResources(session) }
                .exceptionOrNull()
                ?.let(t::addSuppressed)
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
        mainHandler.removeCallbacks(noiseDiagnosticsLogRunnable)
        runRtc(allowClosed = true, onFailure = ::postEngineError) {
            try {
                engineState = EngineState.CLOSED
                runAllCleanupSteps(
                    { disposeMediaSessionResources(session) },
                    ::disposePlatformResources
                )
            } catch (t: Throwable) {
                Log.e(TAG, "WebRTC 资源关闭失败", t)
            } finally {
                rtc.shutdown()
            }
        }
    }

    private fun disposeMediaSessionResources(session: MediaSession? = synchronized(sessionLock) {
        activeSession
    }) {
        session?.let(::removeRemoteAudioSink)
        val peer = peerConnection
        peerConnection = null
        localAudioSender = null
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        peer?.let { connection ->
            runAllCleanupSteps(
                {
                    connection.setAudioRecording(false)
                    connection.setAudioPlayout(false)
                },
                connection::dispose
            )
        }
    }

    private fun disposePlatformResources() {
        val track = localAudioTrack
        val source = audioSource
        val connectionFactory = factory
        val deviceModule = audioDeviceModule
        val externalFactory = externalAudioProcessingFactory
        val externalGuard = externalProcessorGuard
        localAudioTrack = null
        audioSource = null
        factory = null
        audioDeviceModule = null
        externalAudioProcessingFactory = null
        externalCaptureProcessor = null
        externalProcessorGuard = null

        runAudioPlatformCleanup(
            stopAudio = {
                deviceModule?.setMicrophoneMute(true)
                deviceModule?.setSpeakerMute(true)
            },
            closeExternalProcessor = { externalGuard?.close() },
            disposeTrack = { track?.dispose() },
            disposeSource = { source?.dispose() },
            disposeFactory = { connectionFactory?.dispose() },
            destroyExternalProcessorFactory = { externalFactory?.destroy() },
            releaseAudioDevice = { deviceModule?.release() }
        )
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

        val audioManager = appContext.getSystemService(AudioManager::class.java)
            ?: error("AudioManager 不可用")
        val isDebuggableApp =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val requestedActiveMode = noiseReductionConfig.effectiveMode(isDebuggableApp)
        val initialRouteProbe = AudioBluetoothProfileDetector.probe(audioManager)
        val hardwareAecAvailable = runCatching {
            JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        }.getOrDefault(false)
        val hardwareNsAvailable = runCatching {
            JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()
        }.getOrDefault(false)
        noiseDiagnostics = AudioNoiseDiagnostics(
            deviceModel = Build.MODEL,
            requestedMode = noiseReductionConfig.requestedMode,
            initialActiveMode = requestedActiveMode,
            initialBluetoothProfile = initialRouteProbe.availableProfile,
            initialActiveBluetoothProfile = initialRouteProbe.activeProfile,
            initialActiveRouteDeviceType = initialRouteProbe.activeDeviceType,
            initialActiveRouteDeviceName = initialRouteProbe.activeDeviceName,
            hardwareAecAvailable = hardwareAecAvailable,
            hardwareNsAvailable = hardwareNsAvailable,
            initialHardwareAecRequested = true,
            initialHardwareNsRequested = requestedActiveMode !=
                AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS
        )
        Log.i(TAG, "audio_noise_init ${noiseDiagnostics?.snapshot()?.toLogLine()}")
        mainHandler.removeCallbacks(noiseDiagnosticsLogRunnable)
        mainHandler.postDelayed(noiseDiagnosticsLogRunnable, AUDIO_DIAGNOSTIC_LOG_INTERVAL_MS)

        createAudioPlatform(requestedActiveMode)
    }

    private fun createAudioPlatform(activeMode: AudioNoiseReductionMode) {
        noiseDiagnostics?.updateHardwareRequests(
            aecRequested = true,
            nsRequested = activeMode != AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS
        )
        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // 低延迟优先；耳机/头盔链路里这比高保真更重要。
            .setUseLowLatency(true)
            // SamplesReadyCallback 只做诊断和 VOX 观测；它不是发送前的 PCM 修改入口。
            .setSamplesReadyCallback(JavaAudioDeviceModule.SamplesReadyCallback { samples ->
                handleAudioSamplesForVox(samples)
            })
            // 软件 NS A/B 时关闭硬件 NS；其他模式保持当前硬件优先基线。
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(activeMode != AudioNoiseReductionMode.WEBRTC_SOFTWARE_NS)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()

        val createBaseFactory = {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioDeviceModule ?: error("AudioDeviceModule 尚未初始化"))
        }
        if (activeMode == AudioNoiseReductionMode.EXTERNAL_CAPTURE_POST_PROCESSING) {
            var candidateFactory: ExternalAudioProcessingFactory? = null
            try {
                val createdFactory = ExternalAudioProcessingFactory()
                candidateFactory = createdFactory
                val candidateProcessor = PassThroughCaptureProcessor()
                createdFactory.setCapturePostProcessing(candidateProcessor)
                val createdPeerConnectionFactory = createBaseFactory()
                    .setAudioProcessingFactory(createdFactory)
                    .createPeerConnectionFactory()
                factory = createdPeerConnectionFactory
                externalAudioProcessingFactory = createdFactory
                externalCaptureProcessor = candidateProcessor
                Log.i(TAG, "audio_noise_external_capture_post_processing=enabled")
            } catch (t: Throwable) {
                runCatching { candidateFactory?.destroy() }
                externalProcessorGuard = null
                throw t
            }
        } else {
            factory = createBaseFactory().createPeerConnectionFactory()
        }
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
        peerConnection!!.setAudioRecording(!audioSuspended)
        peerConnection!!.setAudioPlayout(!audioSuspended)
        audioDeviceModule?.setMicrophoneMute(audioSuspended)
        audioDeviceModule?.setSpeakerMute(audioSuspended)
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
        val initialApplication = synchronized(audioControlLock) {
            currentAudioControlApplicationLocked()
        }
        localAudioTrack = factoryOrThrow().createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(true)
            setVolume(initialApplication.trackVolume)
        }
        publishCurrentVoxState()
        Log.i(
            TAG,
            "VOX 初始化 enabled=${audioControls.voxEnabled} " +
                "sensitivity=${audioControls.voxSensitivity} " +
                "trackEnabled=true volume=${initialApplication.trackVolume}"
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
        val startedAtNanos = System.nanoTime()
        try {
            val energy = calculateApproxDb(samples) ?: return
            val now = SystemClock.elapsedRealtime()
            val result = synchronized(audioControlLock) {
                val decision = voxGate.update(energy, now)
                gateState = decision.state
                VoxSampleResult(
                    decision = decision,
                    controls = versionedAudioControls,
                    state = effectiveVoxRuntimeState(audioControls, decision.state),
                    trackVolume = effectiveTrackVolume(audioControls, decision.trackVolume)
                )
            }
            val decision = result.decision
            if (decision.stateChanged) {
                applyCurrentTrackVolume()
                logVoxStateChange(decision, energy, result.trackVolume)
            }
            publishCurrentVoxState()
            postAudioLevel(energy)
            logVoxSnapshot(now, energy, result)
        } finally {
            recordAudioNoiseDiagnostics(samples, System.nanoTime() - startedAtNanos)
        }
    }

    private fun recordAudioNoiseDiagnostics(
        samples: JavaAudioDeviceModule.AudioSamples,
        processingNanos: Long
    ) {
        val diagnostics = noiseDiagnostics ?: return
        diagnostics.recordAudioFrame(
            sampleRate = samples.sampleRate,
            channelCount = samples.channelCount,
            audioFormat = samples.audioFormat,
            frameBytes = samples.data.size,
            processingNanos = processingNanos
        )
    }

    override fun onAudioRouteChanged() {
        val diagnostics = noiseDiagnostics ?: return
        appContext.getSystemService(AudioManager::class.java)?.let { audioManager ->
            diagnostics.updateActiveRoute(AudioBluetoothProfileDetector.probe(audioManager))
        }
    }

    private fun logVoxSnapshot(now: Long, energy: Double, result: VoxSampleResult) {
        if (now - lastVoxLogAt < VOX_LOG_INTERVAL_MS) return
        lastVoxLogAt = now
        val decision = result.decision
        runRtc {
            Log.d(
                TAG,
                String.format(
                    Locale.US,
                    "VOX enabled=%s state=%s energy=%.1f noise=%.1f open=%.1f close=%.1f volume=%.1f",
                    result.controls.settings.voxEnabled,
                    result.state,
                    energy,
                    decision.noiseFloor,
                    decision.openThreshold,
                    decision.closeThreshold,
                    result.trackVolume
                )
            )
        }
    }

    private fun logVoxStateChange(
        decision: VoxGate.Decision,
        energy: Double,
        trackVolume: Double
    ) {
        runRtc {
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
                    trackVolume
                )
            )
        }
    }

    private fun currentAudioControlApplicationLocked(): AudioControlApplication =
        AudioControlApplication(
            controls = versionedAudioControls,
            state = effectiveVoxRuntimeState(audioControls, gateState),
            trackVolume = trackVolumeForCurrentState(audioControls, gateState)
        )

    private fun applyCurrentTrackVolume() {
        runRtc {
            val volume = synchronized(audioControlLock) {
                currentAudioControlApplicationLocked().trackVolume
            }
            if (engineState == EngineState.READY) {
                localAudioTrack?.setVolume(volume)
            }
        }
    }

    private fun publishCurrentVoxState() {
        synchronized(audioControlLock) {
            val application = currentAudioControlApplicationLocked()
            val snapshot = application.controls to application.state
            if (lastPublishedVoxSnapshot == snapshot) {
                return
            }
            lastPublishedVoxSnapshot = snapshot
            // Enqueue while holding the state lock so callbacks preserve state-transition order.
            postRuntimeMain {
                onVoxStateChanged(application.controls, application.state)
            }
        }
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
            if (session.remoteAudioTrackAttached.compareAndSet(false, true)) {
                val sink = remoteAudioSink(session)
                runCatching { track.addSink(sink) }.getOrElse {
                    session.remoteAudioTrackAttached.set(false)
                    throw it
                }
                session.remoteAudioTrack = track
                session.remoteAudioSink = sink
                postSessionMain(session) { session.callbacks.onRemoteAudioTrack(track) }
            }
        }
    }

    private fun remoteAudioSink(session: MediaSession): AudioTrackSink =
        object : AudioTrackSink {
            override fun onData(
                audioData: java.nio.ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
                absoluteCaptureTimestampMs: Long
            ) {
                if (
                    numberOfFrames <= 0 ||
                    session.closed.get() ||
                    !session.remoteAudioFrameReceived.compareAndSet(false, true)
                ) return
                postSessionMain(session) { session.callbacks.onRemoteAudioFrameReceived() }
            }
        }

    private fun removeRemoteAudioSink(session: MediaSession) {
        val track = session.remoteAudioTrack ?: return
        val sink = session.remoteAudioSink ?: return
        runCatching { track.removeSink(sink) }
        session.remoteAudioTrack = null
        session.remoteAudioSink = null
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

    /**
     * Debug-only pass-through processor used to prove the AAR capture post-processing hook.
     * It intentionally does not mutate the buffer or perform any DSP.
     */
    private inner class PassThroughCaptureProcessor :
        ExternalAudioProcessingFactory.AudioProcessing {
        private val guard = AudioNoiseExternalProcessorGuard(
            onFrameFinished = { processingNanos ->
                val keepEnabled = noiseDiagnostics?.recordExternalProcessorFrame(processingNanos)
                    ?: true
                if (!keepEnabled) {
                    Log.w(TAG, "audio_noise_external_processor_overrun; bypassing experimental processor")
                }
                keepEnabled
            },
            onProcessingFailure = { failure ->
                noiseDiagnostics?.recordFallback(
                    "external_processor_failed:${failure::class.java.simpleName}"
                )
                Log.w(TAG, "audio_noise_external_processor_failed; bypassing experimental processor", failure)
            }
        )

        init {
            externalProcessorGuard = guard
        }

        override fun initialize(sampleRateHz: Int, numChannels: Int) {
            Log.i(
                TAG,
                "audio_noise_external_processor_init sampleRate=$sampleRateHz " +
                    "channels=$numChannels"
            )
        }

        override fun reset(newRate: Int) {
            Log.i(TAG, "audio_noise_external_processor_reset sampleRate=$newRate")
        }

        override fun process(
            numBands: Int,
            numFrames: Int,
            buffer: java.nio.ByteBuffer
        ) {
            guard.process(numBands, numFrames, buffer)
        }
    }

    private inner class MediaSession(
        val callbacks: RiderMediaSessionCallbacks
    ) : RiderMediaSession {
        val closed = AtomicBoolean(false)
        val remoteAudioTrackAttached = AtomicBoolean(false)
        val remoteAudioFrameReceived = AtomicBoolean(false)
        var remoteAudioTrack: AudioTrack? = null
        var remoteAudioSink: AudioTrackSink? = null
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
                    disposeMediaSessionResources(this)
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

    private data class AudioControlApplication(
        val controls: VersionedAudioControls,
        val state: VoxRuntimeState,
        val trackVolume: Double
    )

    private data class VoxSampleResult(
        val decision: VoxGate.Decision,
        val controls: VersionedAudioControls,
        val state: VoxRuntimeState,
        val trackVolume: Double
    )

    companion object {
        private const val TAG = "RiderAudioEngine"
        private const val STREAM_ID = "rider_audio_stream"
        private const val AUDIO_TRACK_ID = "rider_audio_track"
        private const val OPUS_BITRATE_BPS = 32_000
        private const val VOX_LOG_INTERVAL_MS = 1_000L
        private const val AUDIO_DIAGNOSTIC_LOG_INTERVAL_MS = 5_000L
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
