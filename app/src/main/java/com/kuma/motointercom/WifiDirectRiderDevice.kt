package com.kuma.motointercom

import android.net.wifi.p2p.WifiP2pDevice

internal data class WifiDirectRiderDevice(
    val device: WifiP2pDevice,
    val identity: DiscoveryIdentityClaim
)
