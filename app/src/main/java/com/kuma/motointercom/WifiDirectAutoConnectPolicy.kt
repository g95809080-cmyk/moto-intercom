package com.kuma.motointercom

internal object WifiDirectAutoConnectPolicy {
    fun shouldConnect(
        autoConnect: Boolean,
        peerAvailable: Boolean,
        validatingGroup: Boolean,
        connecting: Boolean,
        connectionActive: Boolean
    ): Boolean =
        autoConnect && peerAvailable && !validatingGroup && !connecting && !connectionActive
}
