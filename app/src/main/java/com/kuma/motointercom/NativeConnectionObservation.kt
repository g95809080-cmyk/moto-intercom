package com.kuma.motointercom

import org.webrtc.PeerConnection

/** Entry-time metadata from exactly one native PeerConnection, before RTC/Main delivery. */
internal data class NativeConnectionObservation(
    val source: NativeConnectionObservationSource,
    val sequence: Long,
    val state: PeerConnection.PeerConnectionState,
    val elapsedMs: Long,
    val wallMs: Long,
    val firstLoss: ConnectionLossObservation?,
    val firstLossSequence: Long?
)

internal class NativeConnectionObservationSource(private val controlIsOpen: () -> Boolean = { true }) {
    private val lock = Any()
    private var active = true
    private var sequence = 0L
    private var latest: NativeConnectionObservation? = null
    private var lastConnected: NativeConnectionObservation? = null
    private var terminalFailureSequence: Long? = null
    private var lossCaptured = false
    private var firstLoss: ConnectionLossObservation? = null
    private var firstLossSequence: Long? = null
    private var connectedOwner: ConnectionAttempt? = null
    private var ownerLoss: ConnectionLossObservation? = null
    private var ownerHardLoss = false

    fun acceptConnectedOwner(attempt: ConnectionAttempt) = synchronized(lock) {
        if (!active) return@synchronized
        connectedOwner = attempt
        firstLoss?.let { recordOwnerLossLocked(it, terminalFailureSequence != null) }
    }

    /** Called only at a verified selected owner's actual loss producer, never generic capture. */
    fun recordOwnerLoss(observation: ConnectionLossObservation, hard: Boolean) = synchronized(lock) {
        recordOwnerLossLocked(observation, hard)
    }

    private fun recordOwnerLossLocked(observation: ConnectionLossObservation, hard: Boolean) {
        val at = observation.occurredAtElapsedMs ?: return
        if (!active || connectedOwner == null || observation.sourceAttempt != connectedOwner || at < 0) return
        val previous = ownerLoss
        if (previous == null || at < requireNotNull(previous.occurredAtElapsedMs) ||
            at == previous.occurredAtElapsedMs && observation.producerSequence?.let { incoming ->
                previous.producerSequence?.let { incoming < it } ?: true
            } == true) ownerLoss = observation
        ownerHardLoss = ownerHardLoss || hard
    }

    /** Final metadata claim shares the producer lock; factories and clocks stay outside. */
    fun <T> decideOwnerLoss(decide: (ConnectionLossObservation?) -> T): T = synchronized(lock) { decide(ownerLoss) }

    fun acknowledgeSelfHeal(healed: NativeConnectionObservation) = synchronized(lock) {
        if (ownerHardLoss) return@synchronized
        ownerLoss = null
        // A subsequent loss captured before Main acknowledges this heal starts its own episode.
        if (firstLossSequence?.let { it > healed.sequence } == true) firstLoss?.let {
            recordOwnerLossLocked(it, terminalFailureSequence != null)
        }
    }

    fun capture(
        state: PeerConnection.PeerConnectionState,
        elapsedMs: Long,
        wallMs: Long,
        freezeLoss: () -> ConnectionLossObservation?
    ): NativeConnectionObservation? {
        val losing = state in setOf(PeerConnection.PeerConnectionState.DISCONNECTED,
            PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.CLOSED)
        // Never acquire the policy/Service lock while holding the native receipt lock.
        val shouldFreeze = losing && synchronized(lock) { active && !lossCaptured }
        val frozen = if (shouldFreeze) freezeLoss()?.copy(occurredAtElapsedMs = elapsedMs) else null
        return synchronized(lock) {
            if (!active || elapsedMs < 0 || latest?.let { elapsedMs < it.elapsedMs } == true) return@synchronized null
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                lossCaptured = false
                firstLoss = null
                firstLossSequence = null
            } else if (losing && !lossCaptured) {
                lossCaptured = true
                firstLoss = frozen
                firstLossSequence = sequence + 1
            }
            NativeConnectionObservation(this, ++sequence, state, elapsedMs, wallMs, firstLoss, firstLossSequence).also {
                latest = it
                if (state == PeerConnection.PeerConnectionState.CONNECTED) lastConnected = it
                if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED)
                    terminalFailureSequence = it.sequence
                if (losing && connectedOwner != null) recordOwnerLossLocked(
                    firstLoss?.takeIf { loss -> loss.sourceAttempt == connectedOwner }
                        ?: ConnectionLossObservation(connectedOwner, null, elapsedMs),
                    state != PeerConnection.PeerConnectionState.DISCONNECTED)
            }
        }
    }

    /** Actor metadata claims only; no resource I/O or callbacks to Main under this lock. */
    fun <T> decideWindow(
        first: NativeConnectionObservation,
        observedUntilElapsedMs: Long,
        decide: (NativeConnectionObservation?) -> T
    ): T = synchronized(lock) {
        require(first.source === this)
        val healed = lastConnected?.takeIf {
            active && controlIsOpen() && !ownerHardLoss && terminalFailureSequence == null &&
                it.sequence > (first.firstLossSequence ?: first.sequence) &&
                it.elapsedMs in (first.firstLoss?.occurredAtElapsedMs ?: first.elapsedMs)..observedUntilElapsedMs
        }
        decide(healed)
    }

    fun retire() = synchronized(lock) { active = false }
}

internal data class ConnectedLossWindow(
    val generation: Long,
    val connected: IntercomState.Connected,
    val owner: VerifiedControlChannel,
    val firstReceipt: NativeConnectionObservation,
    val firstLoss: ConnectionLossObservation,
    val observedUntilElapsedMs: Long,
    val recoveryDeadlineElapsedMs: Long
)

internal fun PeerConnection.PeerConnectionState.toProductState(): WebRtcConnectionState = when (this) {
    PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
    PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
    PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
    PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
    else -> WebRtcConnectionState.OTHER
}
