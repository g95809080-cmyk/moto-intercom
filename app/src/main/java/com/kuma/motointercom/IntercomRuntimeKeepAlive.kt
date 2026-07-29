package com.kuma.motointercom

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import java.io.Closeable

internal class IntercomRuntimeKeepAlive private constructor(
    private val cpuLock: PowerManager.WakeLock,
    private val wifiLock: WifiManager.WifiLock
) : Closeable {
    internal val isHeld: Boolean
        get() = cpuLock.isHeld && wifiLock.isHeld

    override fun close() {
        try {
            if (wifiLock.isHeld) wifiLock.release()
        } finally {
            if (cpuLock.isHeld) cpuLock.release()
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        @SuppressLint("Wakelock", "WakelockTimeout")
        fun acquire(context: Context): IntercomRuntimeKeepAlive {
            val appContext = context.applicationContext
            val tag = "${appContext.packageName}:intercom"
            val cpuLock = appContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                .apply { setReferenceCounted(false) }
            val wifiLock = appContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
                .apply { setReferenceCounted(false) }

            acquireLocks(
                acquireCpu = cpuLock::acquire,
                acquireWifi = wifiLock::acquire,
                releaseCpu = cpuLock::release
            )
            return IntercomRuntimeKeepAlive(cpuLock, wifiLock)
        }

        internal fun acquireLocks(
            acquireCpu: () -> Unit,
            acquireWifi: () -> Unit,
            releaseCpu: () -> Unit
        ) {
            acquireCpu()
            try {
                acquireWifi()
            } catch (failure: Throwable) {
                releaseCpu()
                throw failure
            }
        }
    }
}
