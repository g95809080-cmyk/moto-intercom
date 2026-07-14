package com.kuma.motointercom

internal object WifiDirectAutoConnectPolicy {
    fun shouldConnect(
        autoConnect: Boolean,
        peerAvailable: Boolean,
        stableIdentityClaimed: Boolean,
        validatingGroup: Boolean,
        connecting: Boolean,
        connectionActive: Boolean
    ): Boolean =
        autoConnect &&
            peerAvailable &&
            stableIdentityClaimed &&
            !validatingGroup &&
            !connecting &&
            !connectionActive
}
