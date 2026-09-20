package com.kuma.motointercom

import android.content.Context
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

    internal fun routeEvidence(): AudioRouteEvidence = route.evidence().let {
        if (synchronized(lock) { closed || activeSession == null } ||
            audioCoordinator?.currentState()?.let { state -> state != AudioInterruptionState.NORMAL } == true)
            it.copy(ready = false) else it
    }

    fun updateAudioControls(controls: VersionedAudioControls) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            engine.updateAudioControls(controls.normalized())
        }
    }

    fun updateAudioRoute(selection: AudioRouteSelection) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            preferredAudioRoute = selection
            if (activeSession != null || audioCoordinator == null) {
                val canApply = audioCoordinator?.canApplyPreferredRoute() ?: true
                if (canApply) route.select(selection)
            }
        }
    }

    internal fun preferredAudioRoute(): AudioRouteSelection = synchronized(lock) {
        preferredAudioRoute
    }

    fun openMediaSession(callbacks: RiderMediaSessionCallbacks): RiderMediaSession {
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
            engine.openSession(guardedCallbacks).also { session ->
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
                activeSession = null
                activeLease = null
                true
            } else {
                false
            }
        }
        if (shouldClose) {
            try {
                session.close()
            } finally {
                audioCoordinator?.endMediaSession()
            }
        }
    }

    override fun close() {
        val session = synchronized(lock) {
            if (closed) return
            closed = true
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
            onAudioInterruptionChanged: (AudioInterruptionState) -> Unit = {}
        ): AudioSessionController {
            val engine = RiderAudioEngine(
                context = context,
                onEngineError = onError,
                isRuntimeCurrent = isRuntimeCurrent,
                initialAudioControls = initialAudioControls,
                onVoxStateChanged = onVoxStateChanged
            )
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
