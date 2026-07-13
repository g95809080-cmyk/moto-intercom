package com.kuma.motointercom

import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single instance is owned by the foreground service and is the only product-state writer. */
class SessionOrchestrator(
    private val pairingRepository: PairingRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onError: (Throwable) -> Unit = {}
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<SessionEvent>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<IntercomState>(IntercomState.Offline)

    val state: StateFlow<IntercomState> = mutableState.asStateFlow()

    init {
        scope.launch {
            for (event in events) handle(event)
        }
    }

    fun dispatch(event: SessionEvent): Boolean = events.trySend(event).isSuccess

    private suspend fun handle(event: SessionEvent) {
        val next = nextIntercomState(mutableState.value, event) ?: return
        mutableState.value = next
        if (next is IntercomState.Connected) persistConnectedPeer(next)
    }

    private suspend fun persistConnectedPeer(state: IntercomState.Connected) {
        val deviceId = state.peer.deviceId?.takeIf(String::isNotBlank) ?: return
        try {
            pairingRepository.saveConnectedPeer(
                PairingRecord(
                    remoteDeviceId = deviceId,
                    remoteNickname = state.peer.nickname,
                    deviceName = state.peer.deviceName,
                    localAlias = "",
                    shortCode = "",
                    pairedAt = state.connectedAt,
                    lastConnectedAt = state.connectedAt,
                    isPreferred = false,
                    lastTransport = state.transport,
                    failureCount = 0
                )
            )
        } catch (t: Throwable) {
            onError(t)
        }
    }

    override fun close() {
        events.close()
        scope.cancel()
    }
}
