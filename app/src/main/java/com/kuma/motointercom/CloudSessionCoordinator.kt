package com.kuma.motointercom

import kotlinx.coroutines.flow.StateFlow

// Service-hosted control boundary; it never owns native capture or playback.
internal interface CloudSessionCoordinator {
    val ride: StateFlow<RideSession?>
    suspend fun invite(pairId: String)
    suspend fun respond(inviteId: String, accept: Boolean)
    suspend fun localEstablished(rideId: String)
    suspend fun localLost(rideId: String, reason: LocalSessionEndReason)
    suspend fun end(rideId: String)
    suspend fun resumeManually(rideId: String)
}

// Implementations sign the exact HTTP method, path, body hash, timestamp and nonce
// with the device's non-exportable key. No unsigned/default transport exists.
internal interface SignedControlPlaneTransport {
    suspend fun post(path: String, body: String): String
    suspend fun get(path: String): String
}
