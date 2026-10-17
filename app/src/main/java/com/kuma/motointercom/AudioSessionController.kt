package com.kuma.motointercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

internal interface RiderAudioRoute : Closeable {
    fun evidence(): AudioRouteEvidence = AudioRouteEvidence(0, false)
    fun select(selection: AudioRouteSelection)
    fun suspendForInterruption(restoreMode: Boolean = false) = Unit
    fun closeRoute(restoreInitialState: Boolean) = close()
}

/** Owns audio platform resources for one online runtime. */
internal class AudioSessionController(
    private val engine: RiderMediaEngine,
    private val route: RiderAudioRoute,
    initialAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
    private val audioCoordinator: CommunicationAudioCoordinator? = null
) : Closeable {

    private val lock = Any()
    private var closed = false
    private var activeLease: Any? = null
    private var activeSession: RiderMediaSession? = null
    private var preferredAudioRoute = initialAudioRoute
    private val ioCheckHandler by lazy { Handler(Looper.getMainLooper()) }
    private var ioCheck: MediaIoCheck? = null

    private inner class MediaIoCheck(val lease: Any, val session: RiderMediaSession, val upstreamCurrent: () -> Boolean) {
        var queryRevision = 0L
        var queryPending = false
        var pendingSince = 0L
        var routeRevision: Long? = null
        var badSince: Long? = null
        var retries = 0
        var exhaustedReported = false
        var verifiedRevision: Long? = null
        var verifiedAt = 0L
        val tick = Runnable { checkAudioIoRoute(this) }
        fun current() = !closed && ioCheck === this && activeLease === lease && activeSession === session && upstreamCurrent()
    }

    internal fun routeEvidence(): AudioRouteEvidence = route.evidence().let {
        val nativeReady = synchronized(lock) {
            if (!it.bluetooth) true else ioCheck?.let { check ->
                check.current() && check.verifiedRevision == it.revision &&
                    SystemClock.elapsedRealtime() - check.verifiedAt in 0..2_000
            } == true
        }
        if (!nativeReady || synchronized(lock) { closed || activeSession == null } ||
            audioCoordinator?.currentState()?.let { state -> state != AudioInterruptionState.NORMAL } == true)
            it.copy(ready = false) else it
    }

    fun updateAudioControls(controls: VersionedAudioControls) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            engine.updateAudioControls(controls.normalized())
        }
    }

    fun updateAudioRoute(selection: AudioRouteSelection, resetIoRecoveryBudget: Boolean = true) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            preferredAudioRoute = selection
            ioCheck?.apply {
                ++queryRevision
                queryPending = false
                verifiedRevision = null
                badSince = null
                if (resetIoRecoveryBudget) {
                    retries = 0
                    exhaustedReported = false
                }
            }
            if (activeSession != null || audioCoordinator == null) {
                val canApply = audioCoordinator?.canApplyPreferredRoute() ?: true
                if (canApply) {
                    if (audioCoordinator == null) route.select(selection)
                    else audioCoordinator.reapplyPreferredRoute(force = true)
                }
            }
        }
    }

    internal fun preferredAudioRoute(): AudioRouteSelection = synchronized(lock) {
        preferredAudioRoute
    }

    fun openMediaSession(
        callbacks: RiderMediaSessionCallbacks,
        icePolicy: MediaIcePolicy = MediaIcePolicy.Nearby
    ): RiderMediaSession {
        val lease = Any()
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            check(activeLease == null && activeSession == null) {
                "an audio media session is already active"
            }
            activeLease = lease
        }

        try {
            audioCoordinator?.beginMediaSession()
        } catch (t: Throwable) {
            synchronized(lock) {
                activeLease = null
                activeSession = null
            }
            throw t
        }

        val guardedCallbacks = callbacks.copy(
            isSessionCurrent = {
                synchronized(lock) { !closed && activeLease === lease } &&
                    callbacks.isSessionCurrent()
            }
        )
        return try {
            engine.openSession(guardedCallbacks, icePolicy).also { session ->
                val accepted = synchronized(lock) {
                    if (!closed && activeLease === lease && activeSession == null) {
                        activeSession = session
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    session.close()
                    error("audio session controller closed while opening media")
                }
                if (audioCoordinator != null) synchronized(lock) {
                    if (!closed && activeLease === lease && activeSession === session) {
                        MediaIoCheck(lease, session, guardedCallbacks.isSessionCurrent).also {
                            ioCheck = it
                            ioCheckHandler.post(it.tick)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                if (activeLease === lease) {
                    activeLease = null
                    activeSession = null
                }
            }
            audioCoordinator?.endMediaSession()
            throw t
        }
    }

    fun closeMediaSession(session: RiderMediaSession) {
        val shouldClose = synchronized(lock) {
            if (activeSession === session) {
                cancelIoCheck()
                activeSession = null
                activeLease = null
                true
            } else {
                false
            }
        }
        if (shouldClose) {
            try {
                audioCoordinator?.endMediaSession()
            } finally {
                session.close()
            }
        }
    }

    fun closeWhenDisposed(onDisposed: () -> Unit) {
        try { close() } finally { engine.closeWhenDisposed(onDisposed) }
    }

    override fun close() {
        val session = synchronized(lock) {
            if (closed) return
            closed = true
            cancelIoCheck()
            activeLease = null
            activeSession.also { activeSession = null }
        }

        var failure: Throwable? = null
        fun closeSafely(resource: Closeable?) {
            runCatching { resource?.close() }.exceptionOrNull()?.let {
                if (failure == null) failure = it else failure?.addSuppressed(it)
            }
        }
        val phoneCallActive = audioCoordinator?.isPhoneCallActive() == true
        closeSafely(session)
        closeSafely(audioCoordinator)
        closeSafely(engine)
        runCatching {
            route.closeRoute(
                restoreInitialState = !phoneCallActive
            )
        }.exceptionOrNull()?.let {
            if (failure == null) failure = it else failure?.addSuppressed(it)
        }
        failure?.let { throw it }
    }

    private fun cancelIoCheck() {
        ioCheck?.let { ioCheckHandler.removeCallbacks(it.tick) }
        ioCheck = null
    }

    /** Verify the current native recorder and renderer, then repeat the user's successful reapply. */
    private fun checkAudioIoRoute(check: MediaIoCheck) {
        val routeBefore: AudioRouteEvidence
        val query: Long
        synchronized(lock) {
            if (!check.current()) return
            ioCheckHandler.postDelayed(check.tick, 1_000)
            routeBefore = route.evidence()
            if (audioCoordinator?.currentState() != AudioInterruptionState.NORMAL || !routeBefore.ready || !routeBefore.bluetooth) {
                check.verifiedRevision = null
                check.badSince = null
                return
            }
            if (check.queryPending && SystemClock.elapsedRealtime() - check.pendingSince < 3_000) return
            check.queryPending = true
            check.pendingSince = SystemClock.elapsedRealtime()
            query = ++check.queryRevision
        }
        check.session.queryAudioIoRoute { snapshot ->
            synchronized(lock) {
                if (!check.current() || query != check.queryRevision) return@queryAudioIoRoute
                check.queryPending = false
                if (route.evidence() != routeBefore || audioCoordinator?.currentState() != AudioInterruptionState.NORMAL) return@queryAudioIoRoute
                val now = SystemClock.elapsedRealtime()
                if (snapshot == null || now - snapshot.sampledAtElapsedRealtimeMs !in 0..2_000) {
                    check.verifiedRevision = null
                    check.badSince = null
                    return@queryAudioIoRoute
                }
                if (check.routeRevision != routeBefore.revision) {
                    check.routeRevision = routeBefore.revision
                    check.badSince = null
                }
                if (!snapshot.connected) {
                    check.verifiedRevision = null
                    check.badSince = null
                } else if (snapshot.bluetoothReady) {
                    if (check.verifiedRevision != routeBefore.revision) {
                        DiagnosticLog.i("AudioSessionController", "Bluetooth I/O verified routeRevision=${routeBefore.revision} input=${snapshot.inputType} output=${snapshot.outputType} fresh=${snapshot.fresh}")
                    }
                    check.verifiedRevision = routeBefore.revision
                    check.verifiedAt = snapshot.sampledAtElapsedRealtimeMs
                    check.badSince = null
                } else {
                    check.verifiedRevision = null
                    val badSince = check.badSince ?: now.also { check.badSince = it }
                    if (now - badSince < 2_000) return@queryAudioIoRoute
                    val detail = "routeRevision=${routeBefore.revision} input=${snapshot.inputType} output=${snapshot.outputType} fresh=${snapshot.fresh}"
                    if (check.retries < 2) {
                        ++check.retries
                        check.badSince = null
                        DiagnosticLog.w("AudioSessionController", "Bluetooth I/O reapply attempt=${check.retries}/2 $detail")
                        audioCoordinator?.reapplyPreferredRoute(force = true)
                    } else if (!check.exhaustedReported) {
                        check.exhaustedReported = true
                        DiagnosticLog.w("AudioSessionController", "Bluetooth I/O recovery exhausted; $detail")
                    }
                }
            }
        }
    }

    companion object {
        fun start(
            context: Context,
            onScoConnected: (String) -> Unit,
            onScoDisconnected: () -> Unit,
            onSpeakerFallback: (Boolean) -> Unit,
            onError: (Throwable) -> Unit,
            isRuntimeCurrent: () -> Boolean,
            initialAudioControls: VersionedAudioControls = VersionedAudioControls(
                revision = 0,
                settings = AudioControlSettings()
            ),
            onVoxStateChanged: (VersionedAudioControls, VoxRuntimeState) -> Unit = { _, _ -> },
            initialAudioRoute: AudioRouteSelection = AudioRouteSelection.BLUETOOTH,
            onEarpieceActive: () -> Unit = {},
            onExternalAudioActive: (String) -> Unit = {},
            onAudioInterruptionChanged: (AudioInterruptionState) -> Unit = {},
            onPlatformError: (Throwable) -> Unit = onError
        ): AudioSessionController {
            val nativeReservation = LegacyRuntimeOwnership.retainAudioResources()
            val engine = try { RiderAudioEngine(
                context = context,
                onEngineError = onPlatformError,
                isRuntimeCurrent = isRuntimeCurrent,
                initialAudioControls = initialAudioControls,
                onVoxStateChanged = onVoxStateChanged,
                onNativeDisposed = nativeReservation::close
            ) } catch (error: Throwable) {
                nativeReservation.close()
                throw error
            }
            return try {
                var controller: AudioSessionController? = null
                var coordinator: CommunicationAudioCoordinator? = null
                val route = AudioRouteController(
                    context = context,
                    onScoConnected = onScoConnected,
                    onScoDisconnected = onScoDisconnected,
                    onSpeakerFallback = onSpeakerFallback,
                    onEarpieceActive = onEarpieceActive,
                    onExternalAudioActive = onExternalAudioActive,
                    onError = onError,
                    onRouteReady = { coordinator?.onRouteReady() },
                    onRouteInvalidated = { coordinator?.reapplyPreferredRoute() }
                )
                coordinator = CommunicationAudioCoordinator(
                    engine = engine,
                    route = route,
                    audioFocus = AndroidIntercomAudioFocus(context),
                    phoneState = AndroidIntercomPhoneState(context),
                    activateRoute = {
                        route.select(controller?.preferredAudioRoute() ?: initialAudioRoute)
                    },
                    onStateChanged = onAudioInterruptionChanged
                )
                controller = AudioSessionController(
                    engine = engine,
                    route = route,
                    initialAudioRoute = initialAudioRoute,
                    audioCoordinator = coordinator
                )
                coordinator?.start()
                controller ?: error("audio session controller was not created")
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
