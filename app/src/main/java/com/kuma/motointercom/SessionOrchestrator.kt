package com.kuma.motointercom

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A single instance is owned by the foreground service and is the only product-state writer. */
class SessionOrchestrator(
    private val pairingRepository: PairingRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onLog: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private data class QueuedEvent(
        val event: SessionEvent,
        val completion: CompletableDeferred<Boolean>?,
        val onProcessed: ((Boolean) -> Unit)?
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<QueuedEvent>(Channel.UNLIMITED)
    private val effectChannel = Channel<SessionEffect>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<IntercomState>(IntercomState.Offline)

    val state: StateFlow<IntercomState> = mutableState.asStateFlow()
    val effects: Flow<SessionEffect> = effectChannel.receiveAsFlow()

    init {
        scope.launch {
            for (queued in events) {
                val accepted = try {
                    handle(queued.event)
                } catch (t: Throwable) {
                    onError(t)
                    false
                }
                queued.completion?.complete(accepted)
                try {
                    queued.onProcessed?.invoke(accepted)
                } catch (t: Throwable) {
                    onError(t)
                }
            }
        }
    }

    fun dispatch(
        event: SessionEvent,
        onProcessed: ((Boolean) -> Unit)? = null
    ): Boolean = events.trySend(QueuedEvent(event, null, onProcessed)).isSuccess

    internal suspend fun dispatchAndAwait(event: SessionEvent): Boolean {
        val completion = CompletableDeferred<Boolean>()
        if (!events.trySend(QueuedEvent(event, completion, null)).isSuccess) return false
        return completion.await()
    }

    internal val currentAttempt: ConnectionAttempt?
        get() = mutableState.value.connectionAttemptOrNull()

    private suspend fun handle(event: SessionEvent): Boolean {
        val transition = reduceIntercomState(mutableState.value, event) ?: return false
        mutableState.value = transition.state
        maybePersistConnectedPeer(transition.state)
        transition.effects.forEach { effectChannel.send(it) }
        return true
    }

    private suspend fun maybePersistConnectedPeer(state: IntercomState) {
        val connected = state as? IntercomState.Connected ?: return
        val deviceId = connected.peer.deviceId?.takeIf(String::isNotBlank)
        if (deviceId == null || !connected.peer.isDeviceIdVerified) {
            onLog(
                "Pairing skipped: remote deviceId is unknown or unverified for " +
                    "runtime=${connected.runtimeSessionId.value} attempt=${connected.attemptId.value}"
            )
            return
        }
        try {
            pairingRepository.saveConnectedPeer(
                PairingRecord(
                    remoteDeviceId = deviceId,
                    remoteNickname = connected.peer.nickname,
                    deviceName = connected.peer.deviceName,
                    localAlias = "",
                    shortCode = "",
                    pairedAt = connected.connectedAt,
                    lastConnectedAt = connected.connectedAt,
                    isPreferred = false,
                    lastTransport = connected.transport.name,
                    failureCount = 0
                )
            )
        } catch (t: Throwable) {
            onError(t)
        }
    }

    override fun close() {
        events.close()
        effectChannel.close()
        scope.cancel()
    }
}
