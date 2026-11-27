package com.kuma.motointercom.group.hybrid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.kuma.motointercom.group.network.groupBluetooth
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

internal data class GroupProximityReport(
    val reachable: Boolean,
    val rssiDbm: Int? = null
)

internal interface GroupLocalProximityProbe : Closeable {
    fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit)
    fun startScan(
        roomInstanceId: String,
        targetWifiSsid: String? = null,
        onReport: (GroupProximityReport) -> Unit
    )
}

internal interface GroupLocalProximityProbeFactory {
    fun create(context: Context): GroupLocalProximityProbe
}

/**
 * 50m proximity probe for HYBRID recovery: high-power BLE + low-frequency Wi-Fi scan (31s cadence,
 * safely below Android's 4-scans-per-2-minutes throttle) reading real host DIRECT-xx-... RSSI (>= -75 dBm).
 * Never touches audio or AudioManager; gracefully degrades to high-power BLE when fine location is absent.
 */
@SuppressLint("MissingPermission")
internal class GroupBleProximityProbe(
    context: Context,
    private val canScanWifi: (Context) -> Boolean = ::defaultCanScanWifi,
    private val readWifiRssi: (Context, String) -> Int? = ::defaultReadWifiRssi,
    private val triggerWifiScan: (Context) -> Boolean = ::defaultTriggerWifiScan
) : GroupLocalProximityProbe {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanActive = false
    private var wifiReceiverRegistered = false
    private var closed = false
    private var lastReport: GroupProximityReport? = null
    private var seenInWindow = false
    private var strongestBleRssiInWindow: Int? = null
    private var expectedTag: ByteArray? = null
    private var targetSsid: String? = null
    private var reportListener: ((GroupProximityReport) -> Unit)? = null

    private val startWindow = Runnable { beginScanWindow() }
    private val finishWindow = Runnable { endScanWindow() }
    private val wifiScanTick = Runnable { runWifiScanCycle() }
    private val wifiCacheTick = Runnable { evaluateWifiScanResults(scheduleNext = true) }
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed || intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            main.post { if (!closed) evaluateWifiScanResults(scheduleNext = false) }
        }
    }

    override fun startBeacon(roomInstanceId: String, onStatus: (Boolean) -> Unit) {
        check(Looper.myLooper() == main.looper)
        if (closed) return
        stopCurrent()
        val tag = proximityTag(roomInstanceId)
        runCatching {
            val adapter = groupBluetooth(appContext, advertise = true)
            val adv = checkNotNull(adapter.bluetoothLeAdvertiser)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(PROXIMITY_SERVICE), tag)
                .build()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    main.post { if (!closed) onStatus(true) }
                }

                override fun onStartFailure(errorCode: Int) {
                    main.post { if (!closed) onStatus(false) }
                }
            }
            advertiser = adv
            advertiseCallback = callback
            adv.startAdvertising(settings, data, callback)
        }.onFailure {
            onStatus(false)
        }
    }

    override fun startScan(
        roomInstanceId: String,
        targetWifiSsid: String?,
        onReport: (GroupProximityReport) -> Unit
    ) {
        check(Looper.myLooper() == main.looper)
        if (closed) return
        stopCurrent()
        expectedTag = proximityTag(roomInstanceId)
        targetSsid = targetWifiSsid?.takeIf { it.isNotBlank() }
        reportListener = onReport
        lastReport = null
        startWifiScanningIfAvailable()
        beginScanWindow()
    }

    private fun isWifiScanActive(): Boolean = targetSsid != null && canScanWifi(appContext)

    private fun startWifiScanningIfAvailable() {
        if (closed || !isWifiScanActive()) return
        registerWifiReceiver()
        evaluateWifiScanResults(scheduleNext = true)
        runWifiScanCycle()
    }

    private fun registerWifiReceiver() {
        if (wifiReceiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(wifiScanReceiver, filter)
            }
            wifiReceiverRegistered = true
        }
    }

    private fun runWifiScanCycle() {
        if (closed || !isWifiScanActive()) return
        runCatching { triggerWifiScan(appContext) }
        main.removeCallbacks(wifiScanTick)
        main.postDelayed(wifiScanTick, WIFI_SCAN_INTERVAL_MS)
    }

    private fun evaluateWifiScanResults(scheduleNext: Boolean) {
        if (closed) return
        val ssid = targetSsid
        if (ssid != null && canScanWifi(appContext)) {
            val rssi = runCatching { readWifiRssi(appContext, ssid) }.getOrNull()
            val reachable = rssi != null && rssi >= WIFI_RSSI_THRESHOLD_DBM
            publishReport(GroupProximityReport(reachable = reachable, rssiDbm = rssi))
            if (scheduleNext) {
                main.removeCallbacks(wifiCacheTick)
                main.postDelayed(wifiCacheTick, WIFI_CACHE_POLL_MS)
            }
        }
    }

    private fun beginScanWindow() {
        if (closed || scanActive) return
        val targetTag = expectedTag ?: return
        seenInWindow = false
        strongestBleRssiInWindow = null
        runCatching {
            val adapter = groupBluetooth(appContext, advertise = false)
            val bleScanner = checkNotNull(adapter.bluetoothLeScanner)
            val filter = ScanFilter.Builder()
                .setServiceData(ParcelUuid(PROXIMITY_SERVICE), targetTag)
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val payload = result.scanRecord?.getServiceData(ParcelUuid(PROXIMITY_SERVICE)) ?: return
                    if (payload.contentEquals(targetTag)) {
                        val rssi = result.rssi
                        main.post {
                            if (!closed) {
                                seenInWindow = true
                                val prev = strongestBleRssiInWindow
                                strongestBleRssiInWindow = if (prev == null) rssi else maxOf(prev, rssi)
                                if (!isWifiScanActive()) {
                                    publishReport(
                                        GroupProximityReport(
                                            reachable = rssi >= BLE_RSSI_THRESHOLD_DBM,
                                            rssiDbm = null
                                        )
                                    )
                                } else {
                                    evaluateWifiScanResults(scheduleNext = false)
                                }
                            }
                        }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if (!closed && !isWifiScanActive()) {
                            publishReport(GroupProximityReport(reachable = false, rssiDbm = null))
                        }
                    }
                }
            }
            scanner = bleScanner
            scanCallback = callback
            scanActive = true
            bleScanner.startScan(listOf(filter), settings, callback)
            main.postDelayed(finishWindow, SCAN_WINDOW_MS)
        }.onFailure {
            if (!isWifiScanActive()) publishReport(GroupProximityReport(reachable = false, rssiDbm = null))
            if (!closed) main.postDelayed(startWindow, SCAN_INTERVAL_MS)
        }
    }

    private fun endScanWindow() {
        stopScanOnly()
        if (closed) return
        if (!isWifiScanActive()) {
            val best = strongestBleRssiInWindow
            if (!seenInWindow || best == null || best < BLE_RSSI_THRESHOLD_DBM) {
                publishReport(GroupProximityReport(reachable = false, rssiDbm = null))
            }
        }
        main.postDelayed(startWindow, SCAN_INTERVAL_MS)
    }

    private fun publishReport(report: GroupProximityReport) {
        if (lastReport == report) return
        lastReport = report
        reportListener?.invoke(report)
    }

    private fun stopScanOnly() {
        main.removeCallbacks(finishWindow)
        val currentScanner = scanner
        val currentCallback = scanCallback
        scanner = null
        scanCallback = null
        scanActive = false
        if (currentScanner != null && currentCallback != null) {
            runCatching { currentScanner.stopScan(currentCallback) }
        }
    }

    private fun stopCurrent() {
        main.removeCallbacks(startWindow)
        main.removeCallbacks(wifiScanTick)
        main.removeCallbacks(wifiCacheTick)
        stopScanOnly()
        if (wifiReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(wifiScanReceiver) }
            wifiReceiverRegistered = false
        }
        val currentAdvertiser = advertiser
        val currentAdvCallback = advertiseCallback
        advertiser = null
        advertiseCallback = null
        if (currentAdvertiser != null && currentAdvCallback != null) {
            runCatching { currentAdvertiser.stopAdvertising(currentAdvCallback) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        expectedTag = null
        targetSsid = null
        reportListener = null
        stopCurrent()
    }

    companion object {
        val PROXIMITY_SERVICE: UUID = UUID.fromString("eb6c164c-c89e-43d1-9577-7bcdb4bab753")
        const val WIFI_RSSI_THRESHOLD_DBM = -75
        const val BLE_RSSI_THRESHOLD_DBM = -75
        const val WIFI_SCAN_INTERVAL_MS = 31_000L
        private const val WIFI_CACHE_POLL_MS = 10_000L
        private const val WIFI_RESULT_MAX_AGE_MS = 45_000L
        private const val SCAN_WINDOW_MS = 4_000L
        private const val SCAN_INTERVAL_MS = 8_000L

        fun proximityTag(roomInstanceId: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("motocom-hybrid-prox:$roomInstanceId".toByteArray(Charsets.UTF_8))
            return digest.copyOf(8)
        }

        /** Legacy advertising bytes: flags AD + one 128-bit service-data AD structure. */
        fun legacyServiceDataAdvertisementBytes(serviceDataBytes: Int): Int {
            require(serviceDataBytes >= 0)
            return 3 + 2 + 16 + serviceDataBytes
        }

        fun defaultCanScanWifi(context: Context): Boolean {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val wifi = context.getSystemService(WifiManager::class.java) ?: return false
            if (!wifi.isWifiEnabled) return false
            val location = context.getSystemService(LocationManager::class.java) ?: return false
            return location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

        @Suppress("DEPRECATION")
        fun defaultReadWifiRssi(context: Context, targetSsid: String): Int? {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return null
            val results = runCatching { wifi.scanResults }.getOrNull() ?: return null
            val nowElapsedMs = SystemClock.elapsedRealtime()
            var best: Int? = null
            for (item in results) {
                val rawSsid = item.SSID?.trim()?.removeSurrounding("\"") ?: continue
                if (rawSsid != targetSsid) continue
                if (item.timestamp > 0L) {
                    val ageMs = nowElapsedMs - (item.timestamp / 1_000L)
                    if (ageMs > WIFI_RESULT_MAX_AGE_MS) continue
                }
                if (item.level in -127..0) {
                    best = if (best == null) item.level else maxOf(best, item.level)
                }
            }
            return best
        }

        @Suppress("DEPRECATION")
        fun defaultTriggerWifiScan(context: Context): Boolean {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return false
            return runCatching { wifi.startScan() }.getOrDefault(false)
        }
    }
}
