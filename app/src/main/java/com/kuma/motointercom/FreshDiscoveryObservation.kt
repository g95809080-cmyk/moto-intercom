package com.kuma.motointercom

internal enum class DiscoveryObservationKind(val transport: Transport) {
    LAN_NSD(Transport.LAN),
    LAN_UDP(Transport.LAN),
    WIFI_DIRECT_TXT(Transport.WIFI_DIRECT),
    WIFI_DIRECT_V2_INSTANCE(Transport.WIFI_DIRECT)
}

internal data class FreshDiscoveryReceipt internal constructor(
    val source: FreshDiscoverySource,
    val sourceEpoch: Long,
    val sequence: Long,
    val receivedAtElapsedRealtimeMs: Long,
    val kind: DiscoveryObservationKind,
    val observationKey: String? = null,
    val keyRevision: Long = 0L
)

/** A receipt belongs to one real adapter and its current discovery registration. */
internal data class FreshDiscoveryObservation(
    val receipt: FreshDiscoveryReceipt,
    val candidate: DiscoveryCandidate
) {
    val source get() = receipt.source
    val sourceEpoch get() = receipt.sourceEpoch
    val sequence get() = receipt.sequence
    val receivedAtElapsedRealtimeMs get() = receipt.receivedAtElapsedRealtimeMs
}

internal fun DiscoveryCandidate.hasStableV2ObservationIdentity(): Boolean = runCatching {
    require(identity.protocolVersion == SignalingV2Codec.PROTOCOL_VERSION)
    requireCanonicalUuid(requireNotNull(identity.claimedDeviceId), "deviceId")
    requireCanonicalUuid(requireNotNull(identity.sourceSessionId).value, "sessionId")
}.isSuccess

internal class FreshDiscoverySource(val transport: Transport) {
    private val lock = Any()
    private var epoch = 1L
    private var nextSequence = 0L
    private var closed = false
    private val latestEndpoint = mutableMapOf<String, Long>()
    private val latestDevice = mutableMapOf<String, Long>()
    private val keyRevisions = mutableMapOf<Pair<DiscoveryObservationKind, String>, Long>()

    val currentEpoch: Long get() = synchronized(lock) { epoch }

    fun advanceEpoch(): Long = synchronized(lock) {
        epoch++
        latestEndpoint.clear()
        latestDevice.clear()
        keyRevisions.clear()
        epoch
    }

    fun isCurrentEpoch(expected: Long): Boolean = synchronized(lock) { !closed && epoch == expected }

    // Prepare the clock in the actual input callback, before resolving or dispatching to Main.
    fun capture(
        kind: DiscoveryObservationKind,
        receivedAtElapsedRealtimeMs: Long,
        expectedEpoch: Long = currentEpoch,
        observationKey: String? = null
    ): FreshDiscoveryReceipt? = synchronized(lock) {
        if (closed || epoch != expectedEpoch || kind.transport != transport ||
            receivedAtElapsedRealtimeMs < 0L
        ) return@synchronized null
        FreshDiscoveryReceipt(this, epoch, ++nextSequence, receivedAtElapsedRealtimeMs, kind,
            observationKey, observationKey?.let { keyRevisions[kind to it] ?: 0L } ?: 0L)
    }

    // A real lost callback revokes pending resolution and final adoption under the same lock.
    fun invalidateKey(kind: DiscoveryObservationKind, key: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if (closed || epoch != expectedEpoch || kind.transport != transport) return@synchronized false
        val scopedKey = kind to key
        keyRevisions[scopedKey] = (keyRevisions[scopedKey] ?: 0L) + 1L
        true
    }

    /** claim may only update the adapter's in-memory registry; publish and I/O stay outside. */
    fun <T : Any> accept(
        receipt: FreshDiscoveryReceipt,
        candidate: DiscoveryCandidate,
        claim: () -> T?
    ): Pair<T, FreshDiscoveryObservation>? = synchronized(lock) {
        if (!isCurrentReceipt(receipt) || candidate.transport != transport ||
            !candidate.hasStableV2ObservationIdentity()
        ) return@synchronized null
        val deviceId = requireNotNull(candidate.identity.claimedDeviceId)
        if (receipt.sequence <= (latestEndpoint[candidate.endpointId] ?: 0L) ||
            receipt.sequence <= (latestDevice[deviceId] ?: 0L)
        ) return@synchronized null
        val result = claim() ?: return@synchronized null
        latestEndpoint[candidate.endpointId] = receipt.sequence
        latestDevice[deviceId] = receipt.sequence
        result to FreshDiscoveryObservation(receipt, candidate)
    }

    fun isCurrent(observation: FreshDiscoveryObservation): Boolean = synchronized(lock) {
        val receipt = observation.receipt
        isCurrentReceipt(receipt) && observation.candidate.transport == transport &&
            latestEndpoint[observation.candidate.endpointId] == receipt.sequence &&
            latestDevice[observation.candidate.identity.claimedDeviceId] == receipt.sequence
    }

    fun close() = synchronized(lock) {
        closed = true
        epoch++
        latestEndpoint.clear()
        latestDevice.clear()
        keyRevisions.clear()
    }

    fun <T : Any> claimIfCurrent(observation: FreshDiscoveryObservation, claim: () -> T?): T? =
        synchronized(lock) { if (isCurrent(observation)) claim() else null }

    private fun isCurrentReceipt(receipt: FreshDiscoveryReceipt): Boolean =
        !closed && receipt.source === this && receipt.sourceEpoch == epoch &&
            receipt.kind.transport == transport && receipt.sequence in 1..nextSequence &&
            (receipt.observationKey == null || receipt.keyRevision ==
                (keyRevisions[receipt.kind to receipt.observationKey] ?: 0L))
}
