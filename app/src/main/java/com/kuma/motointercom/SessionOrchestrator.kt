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
internal class SessionOrchestrator(
    private val pairingRepository: PairingRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onLog: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    elapsedRealtime: () -> Long = { System.nanoTime() / 1_000_000L },
    attemptTimeoutMs: Long = 10_000L
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
    private val signalingControl = SignalingControlCoordinator(elapsedRealtime, attemptTimeoutMs)
    private var confirmationAvailability = ConfirmationAvailability.UNAVAILABLE

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

    internal val activeControlAttempt: AttemptChannelSet?
        get() = signalingControl.activeAttempt

    private suspend fun handle(event: SessionEvent): Boolean {
        val previous = mutableState.value
        if (event is SessionEvent.ConfirmationAvailabilityChanged) {
            if (previous.runtimeSessionId != event.runtimeSessionId) return false
            confirmationAvailability = event.availability
        }
        val incomingPolicy = if (event is SessionEvent.IncomingConnectRequest) {
            resolveIncomingRequestPolicy(event)
        } else {
            null
        }
        val controlDecision = signalingControl.handle(previous, event, incomingPolicy)
        if (controlDecision != null) {
            if (!controlDecision.accepted) return false
            val next = controlDecision.state ?: previous
            mutableState.value = next
            signalingControl.onProductTransition(previous, next, event)
            resetConfirmationAvailabilityIfNeeded(event, next)
            maybePersistConnectedPeer(next)
            controlDecision.effects.forEach { effectChannel.send(it) }
            return true
        }

        val transition = reduceIntercomState(previous, event) ?: return false
        mutableState.value = transition.state
        signalingControl.onProductTransition(previous, transition.state, event)
        resetConfirmationAvailabilityIfNeeded(event, transition.state)
        maybePersistConnectedPeer(transition.state)
        transition.effects.forEach { effectChannel.send(it) }
        return true
    }

    private suspend fun resolveIncomingRequestPolicy(
        event: SessionEvent.IncomingConnectRequest
    ): IncomingRequestPolicy {
        val paired = try {
            pairingRepository.getByDeviceId(event.wireRequestKey.requesterDeviceId.value) != null
        } catch (t: Throwable) {
            onError(t)
            false
        }
        return IncomingRequestPolicy(paired, confirmationAvailability)
    }

    private fun resetConfirmationAvailabilityIfNeeded(
        event: SessionEvent,
        next: IntercomState
    ) {
        if (event is SessionEvent.RuntimeStarted || next == IntercomState.Offline) {
            confirmationAvailability = ConfirmationAvailability.UNAVAILABLE
        }
    }

    private suspend fun maybePersistConnectedPeer(state: IntercomState) {
        val connected = state as? IntercomState.Connected ?: return
        val deviceId = connected.peer.deviceId?.takeIf(String::isNotBlank)
        if (deviceId == null || !connected.peer.isVerifiedFor(connected.attempt.targetLock)) {
            onLog(
                "Pairing skipped: remote Socket identity is incomplete or unverified for " +
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
