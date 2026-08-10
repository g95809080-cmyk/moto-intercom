package com.kuma.motointercom

import android.content.Context
import java.io.Closeable

internal interface RiderAudioRoute : Closeable {
    fun select(selection: AudioRouteSelection)
}

/** Owns audio platform resources for one online runtime. */
internal class AudioSessionController(
    private val engine: RiderMediaEngine,
    private val route: RiderAudioRoute
) : Closeable {

    private val lock = Any()
    private var closed = false
    private var activeLease: Any? = null
    private var activeSession: RiderMediaSession? = null

    fun updateAudioControls(controls: VersionedAudioControls) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            engine.updateAudioControls(controls.normalized())
        }
    }

    fun updateAudioRoute(selection: AudioRouteSelection) {
        synchronized(lock) {
            check(!closed) { "audio session controller is closed" }
            route.select(selection)
        }
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
        if (shouldClose) session.close()
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
        closeSafely(session)
        closeSafely(engine)
        closeSafely(route)
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
            onExternalAudioActive: (String) -> Unit = {}
        ): AudioSessionController {
            val engine = RiderAudioEngine(
                context = context,
                onEngineError = onError,
                isRuntimeCurrent = isRuntimeCurrent,
                initialAudioControls = initialAudioControls,
                onVoxStateChanged = onVoxStateChanged
            )
            return try {
                val route = AudioRouteController(
                    context = context,
                    onScoConnected = onScoConnected,
                    onScoDisconnected = onScoDisconnected,
                    onSpeakerFallback = onSpeakerFallback,
                    onEarpieceActive = onEarpieceActive,
                    onExternalAudioActive = onExternalAudioActive,
                    onError = onError
                )
                route.select(initialAudioRoute)
                AudioSessionController(engine, route)
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
        }
    }
}
