package com.kuma.motointercom

internal data class RelayTransportEvidence(
    val sessionId: String,
    val sampledAtElapsedMs: Long,
    val pairId: String,
    val state: String,
    val localType: String,
    val remoteType: String,
    val protocol: String?,
    val networkType: String?,
    val roundTripTimeSeconds: Double?,
    val bytesSent: Long,
    val bytesReceived: Long
)

/** Follow the transport reference. A nominated pair alone does not prove selection. */
internal fun selectedTransportEvidence(
    sessionId: String,
    sampledAtElapsedMs: Long,
    stats: Iterable<Triple<String, String, Map<String, Any>>>
): RelayTransportEvidence? {
    val records = stats.associateBy { it.first }
    val pairIds = records.values.filter { it.second == "transport" }
        .mapNotNull { it.third["selectedCandidatePairId"] as? String }.distinct()
    if (pairIds.size != 1) return null
    val pair = records[pairIds.single()]?.takeIf { it.second == "candidate-pair" } ?: return null
    val fields = pair.third
    val local = records[fields["localCandidateId"] as? String]?.takeIf { it.second == "local-candidate" }?.third ?: return null
    val remote = records[fields["remoteCandidateId"] as? String]?.takeIf { it.second == "remote-candidate" }?.third ?: return null
    val sent = (fields["bytesSent"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
    val received = (fields["bytesReceived"] as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
    return RelayTransportEvidence(sessionId, sampledAtElapsedMs, pair.first,
        fields["state"] as? String ?: return null,
        local["candidateType"] as? String ?: return null, remote["candidateType"] as? String ?: return null,
        local["protocol"] as? String, local["networkType"] as? String,
        (fields["currentRoundTripTime"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }, sent, received)
}
