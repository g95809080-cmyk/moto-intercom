package com.kuma.motointercom

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the process audio mode and routes intercom audio to a headset or phone. */
internal class AudioRouteController(
    context: Context,
    private val fallbackToSpeaker: Boolean = true,
    private val onScoConnected: (String) -> Unit = {},
    private val onScoDisconnected: () -> Unit = {},
    private val onSpeakerFallback: (noBluetooth: Boolean) -> Unit = {},
    private val onEarpieceActive: () -> Unit = {},
    private val onError: (Throwable) -> Unit = {}
) : RiderAudioRoute {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialMode: Int? = null
    private var initialSpeakerphoneOn: Boolean? = null
    private val receiverRegistered = AtomicBoolean(false)
    private val audioDeviceCallbackRegistered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val routeRequestLock = Any()
    private var routeRequest = VersionedAudioRouteSelection(0, AudioRouteSelection.BLUETOOTH)

    @Volatile private var wantBluetoothSco = false
    @Volatile private var scoEverConnected = false
    @Volatile private var bluetoothReported = false
    @Volatile private var bluetoothReportRevision: Long? = null
    @Volatile private var modernFallbackActive = false
    private var modernRoute: ModernAudioRoute? = null
    private val speakerFallbackRecovery = AudioSpeakerFallbackRecovery()
    private var speakerFallbackRunnable: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED || closed.get()) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            Log.i(TAG, "legacy SCO broadcast state=$state")
            ROUTE_EXECUTOR.execute {
                val request = currentRouteRequest()
                if (
                    !isCurrentRouteRequest(request) ||
                    request.selection != AudioRouteSelection.BLUETOOTH ||
                    !wantBluetoothSco
                ) return@execute
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        scoEverConnected = true
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        publishBluetoothConnected(bluetoothDeviceName())
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        if (scoEverConnected) postMainForRoute(request, onScoDisconnected)
                        if (fallbackToSpeaker) {
                            fallbackToPhone(!scoEverConnected, "legacy SCO disconnected", request)
                        }
                    }

                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        reportRouteError(request, IllegalStateException("蓝牙 SCO 通道开启失败"))
                        if (fallbackToSpeaker) fallbackToPhone(true, "legacy SCO error", request)
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            logDevices("AudioDeviceCallback added", addedDevices.toList())
            rerouteAfterDeviceChange("device added")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            logDevices("AudioDeviceCallback removed", removedDevices.toList())
            rerouteAfterDeviceChange("device removed")
        }
    }

    override fun select(selection: AudioRouteSelection) {
        if (closed.get()) return
        val request = synchronized(routeRequestLock) {
            VersionedAudioRouteSelection(routeRequest.revision + 1, selection).also {
                routeRequest = it
                wantBluetoothSco = selection == AudioRouteSelection.BLUETOOTH
                if (!wantBluetoothSco) {
                    bluetoothReported = false
                    bluetoothReportRevision = null
                }
            }
        }
        ROUTE_EXECUTOR.execute {
            if (!isCurrentRouteRequest(request)) return@execute
            try {
                captureInitialState()
                cancelSpeakerFallbackRetry()
                when (request.selection) {
                    AudioRouteSelection.BLUETOOTH -> selectBluetooth(request)
                    AudioRouteSelection.EARPIECE,
                    AudioRouteSelection.SPEAKER -> selectPhoneRoute(request)
                }
            } catch (t: Throwable) {
                if (!isCurrentRouteRequest(request)) return@execute
                reportRouteError(request, t)
                if (
                    request.selection == AudioRouteSelection.BLUETOOTH &&
                    fallbackToSpeaker &&
                    wantBluetoothSco
                ) {
                    fallbackToPhone(true, "route error", request)
                }
            }
        }
    }

    private fun selectBluetooth(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || !wantBluetoothSco) return
        if (!hasRequiredPermissions(appContext)) {
            throw SecurityException("缺少蓝牙音频路由运行时权限")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerAudioDeviceCallback()
            modernRoute().register()
            routeModernBluetooth("selection", request)
        } else {
            registerReceiver()
            startLegacySco(request)
        }
    }

    private fun selectPhoneRoute(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || wantBluetoothSco) return
        val selection = request.selection
        check(selection != AudioRouteSelection.BLUETOOTH)
        cancelSpeakerFallbackRetry()
        modernFallbackActive = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val route = modernRoute()
            val routed = when (selection) {
                AudioRouteSelection.EARPIECE -> route.routeToEarpiece()
                AudioRouteSelection.SPEAKER -> route.routeToSpeaker()
                AudioRouteSelection.BLUETOOTH -> false
            }
            val active = when (selection) {
                AudioRouteSelection.EARPIECE -> route.isEarpieceActive()
                AudioRouteSelection.SPEAKER -> route.isSpeakerActive()
                AudioRouteSelection.BLUETOOTH -> false
            }
            if (!isCurrentRouteRequest(request)) return
            if (routed && active) {
                completePhoneRoute(request)
            } else {
                reportRouteError(
                    request,
                    IllegalStateException("requested phone audio route unavailable: $selection")
                )
            }
        } else {
            stopLegacySco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = selection == AudioRouteSelection.SPEAKER
            @Suppress("DEPRECATION")
            val active = audioManager.isSpeakerphoneOn == (selection == AudioRouteSelection.SPEAKER)
            if (!isCurrentRouteRequest(request)) return
            if (active) {
                completePhoneRoute(request)
            } else {
                reportRouteError(
                    request,
                    IllegalStateException("requested legacy phone audio route unavailable: $selection")
                )
            }
        }
    }

    private fun completePhoneRoute(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || wantBluetoothSco) return
        bluetoothReported = false
        bluetoothReportRevision = null
        when (request.selection) {
            AudioRouteSelection.EARPIECE -> postMainForRoute(request, onEarpieceActive)
            AudioRouteSelection.SPEAKER -> postMainForRoute(request) { onSpeakerFallback(false) }
            AudioRouteSelection.BLUETOOTH -> Unit
        }
    }

    fun reset() {
        if (!closed.compareAndSet(false, true)) return
        wantBluetoothSco = false
        cancelSpeakerFallbackRetry()
        ROUTE_EXECUTOR.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val route = modernRoute
                    modernRoute = null
                    restoreStep { initialMode?.let { audioManager.mode = it } }
                    restoreStep { route?.close() }
                } else {
                    restoreStep { stopLegacySco() }
                    restoreStep {
                        initialSpeakerphoneOn?.let {
                            @Suppress("DEPRECATION")
                            audioManager.isSpeakerphoneOn = it
                        }
                    }
                    restoreStep { initialMode?.let { audioManager.mode = it } }
                }
            } finally {
                unregisterReceiver()
                unregisterAudioDeviceCallback()
            }
        }
    }

    override fun close() = reset()

    private fun captureInitialState() {
        if (initialMode != null) return
        initialMode = audioManager.mode
        @Suppress("DEPRECATION")
        initialSpeakerphoneOn = audioManager.isSpeakerphoneOn
    }

    private fun restoreStep(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            logError(t)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routeModernBluetooth(
        reason: String,
        request: VersionedAudioRouteSelection
    ) {
        if (!isCurrentRouteRequest(request) || !wantBluetoothSco) return
        cancelSpeakerFallbackRetry()
        modernFallbackActive = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val route = modernRoute()
        Log.i(TAG, "modern route[$reason]: ${route.stateSummary()}")
        when (route.route()) {
            ModernAudioRoute.RouteResult.ROUTED -> Unit
            ModernAudioRoute.RouteResult.NO_MATCHING_DEVICE -> {
                Log.w(TAG, "modern route[$reason]: no Bluetooth communication device")
                if (fallbackToSpeaker) {
                    fallbackToPhone(true, "no communication Bluetooth", request)
                }
                return
            }
            ModernAudioRoute.RouteResult.REJECTED -> {
                Log.w(TAG, "modern route[$reason]: setCommunicationDevice rejected")
                if (fallbackToSpeaker) {
                    fallbackToPhone(false, "Bluetooth route rejected", request)
                }
                return
            }
        }
        route.currentName()?.let(::publishBluetoothConnected)
        scheduleModernRouteVerification(reason, request)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun modernRoute(): ModernAudioRoute {
        return modernRoute ?: ModernAudioRoute(
            audioManager = audioManager,
            callbackExecutor = ROUTE_EXECUTOR,
            onBluetoothConnected = { name ->
                if (!closed.get() && wantBluetoothSco) publishBluetoothConnected(name)
            },
            onDeviceLost = {
                if (!closed.get() && wantBluetoothSco && !modernFallbackActive) {
                    rerouteModernOnExecutor("communication device changed", currentRouteRequest())
                }
            }
        ).also { modernRoute = it }
    }

    private fun scheduleModernRouteVerification(
        reason: String,
        request: VersionedAudioRouteSelection
    ) {
        mainHandler.postDelayed({
            if (!isCurrentRouteRequest(request)) return@postDelayed
            ROUTE_EXECUTOR.execute {
                if (
                    !isCurrentRouteRequest(request) ||
                    !wantBluetoothSco ||
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) return@execute
                val route = modernRoute ?: return@execute
                Log.i(TAG, "modern route[$reason]: delayed verify ${route.stateSummary()}")
                route.currentName()?.let(::publishBluetoothConnected)
            }
        }, MODERN_ROUTE_VERIFY_DELAY_MS)
    }

    private fun rerouteAfterDeviceChange(reason: String) {
        if (closed.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        ROUTE_EXECUTOR.execute {
            rerouteModernOnExecutor(reason, currentRouteRequest())
        }
    }

    private fun rerouteModernOnExecutor(
        reason: String,
        request: VersionedAudioRouteSelection
    ) {
        if (
            !isCurrentRouteRequest(request) ||
                request.selection != AudioRouteSelection.BLUETOOTH ||
                !wantBluetoothSco ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) return
        try {
            routeModernBluetooth(reason, request)
        } catch (t: Throwable) {
            if (!isCurrentRouteRequest(request)) return
            reportRouteError(request, t)
            if (fallbackToSpeaker) fallbackToPhone(true, "callback route error", request)
        }
    }

    private fun fallbackToPhone(noBluetooth: Boolean, reason: String) {
        fallbackToPhone(noBluetooth, reason, currentRouteRequest())
    }

    private fun fallbackToPhone(
        noBluetooth: Boolean,
        reason: String,
        request: VersionedAudioRouteSelection
    ) {
        if (
            !isCurrentRouteRequest(request) ||
            request.selection != AudioRouteSelection.BLUETOOTH
        ) return
        Log.i(TAG, "fallback to phone: reason=$reason, noBluetooth=$noBluetooth")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernFallbackActive = true
            val route = modernRoute()
            cancelSpeakerFallbackRetry()
            trySpeakerFallback(route, noBluetooth, reason, request)
        } else {
            wantBluetoothSco = false
            stopLegacySco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            if (audioManager.isSpeakerphoneOn) {
                completeSpeakerFallback(noBluetooth, request)
            } else {
                reportRouteError(request, IllegalStateException("phone speaker route was not accepted"))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun trySpeakerFallback(
        route: ModernAudioRoute,
        noBluetooth: Boolean,
        reason: String,
        request: VersionedAudioRouteSelection
    ) {
        if (!isCurrentRouteRequest(request)) return
        val attempt = speakerFallbackRecovery.next()
        if (attempt == null) {
            route.clear()
            reportRouteError(
                request,
                IllegalStateException(
                    "phone speaker route verification failed: ${route.stateSummary()}"
                )
            )
            return
        }

        val speakerRouted = try {
            route.routeToSpeaker()
        } catch (t: Throwable) {
            reportRouteError(request, t)
            false
        }
        if (speakerRouted && route.isSpeakerActive() && isCurrentRouteRequest(request)) {
            Log.i(TAG, "phone speaker route verified attempt=${attempt.number} reason=$reason")
            completeSpeakerFallback(noBluetooth, request)
            return
        }

        Log.w(
            TAG,
            "phone speaker route pending attempt=${attempt.number} reason=$reason ${route.stateSummary()}"
        )
        val retryRunnable = Runnable {
            speakerFallbackRunnable = null
            if (
                !isCurrentRouteRequest(request) ||
                !speakerFallbackRecovery.isCurrent(attempt)
            ) return@Runnable
            ROUTE_EXECUTOR.execute {
                if (
                    !isCurrentRouteRequest(request) ||
                    !speakerFallbackRecovery.isCurrent(attempt)
                ) return@execute
                trySpeakerFallback(route, noBluetooth, reason, request)
            }
        }
        speakerFallbackRunnable = retryRunnable
        mainHandler.postDelayed(retryRunnable, SPEAKER_FALLBACK_RETRY_DELAY_MS)
    }

    private fun completeSpeakerFallback(
        noBluetooth: Boolean,
        request: VersionedAudioRouteSelection
    ) {
        if (!isCurrentRouteRequest(request)) return
        cancelSpeakerFallbackRetry()
        if (bluetoothReported) {
            bluetoothReported = false
            bluetoothReportRevision = null
            postMainForRoute(request, onScoDisconnected)
        }
        postMainForRoute(request) { onSpeakerFallback(noBluetooth) }
    }

    private fun cancelSpeakerFallbackRetry() {
        speakerFallbackRecovery.reset()
        speakerFallbackRunnable?.let(mainHandler::removeCallbacks)
        speakerFallbackRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun startLegacySco(request: VersionedAudioRouteSelection) {
        Log.i(TAG, "legacy SCO route: sdk=${Build.VERSION.SDK_INT}, mode=${modeName(audioManager.mode)}")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w(TAG, "legacy SCO route: isBluetoothScoAvailableOffCall=false")
            if (fallbackToSpeaker) fallbackToPhone(true, "legacy SCO unavailable", request)
            return
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = false
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
    }

    @SuppressLint("MissingPermission")
    private fun stopLegacySco() {
        Log.i(TAG, "legacy SCO route: stop")
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
    }

    private fun publishBluetoothConnected(name: String) {
        val request = currentRouteRequest()
        if (
            !isCurrentRouteRequest(request) ||
            request.selection != AudioRouteSelection.BLUETOOTH ||
            !wantBluetoothSco
        ) return
        cancelSpeakerFallbackRetry()
        modernFallbackActive = false
        if (bluetoothReported && bluetoothReportRevision == request.revision) return
        bluetoothReported = true
        bluetoothReportRevision = request.revision
        postMainForRoute(request) { onScoConnected(name.ifBlank { "头盔蓝牙" }) }
    }

    private fun bluetoothDeviceName(): String {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return device?.productName?.toString()?.takeIf(String::isNotBlank) ?: "头盔蓝牙"
    }

    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!audioDeviceCallbackRegistered.compareAndSet(false, true)) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        Log.i(TAG, "modern route: AudioDeviceCallback registered")
    }

    private fun unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered.compareAndSet(true, false)) return
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            Log.i(TAG, "modern route: AudioDeviceCallback unregistered")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun logDevices(reason: String, devices: List<AudioDeviceInfo>) {
        if (devices.isEmpty()) {
            Log.i(TAG, "modern route: $reason: empty")
        } else {
            devices.forEachIndexed { index, device ->
                Log.i(TAG, "modern route: $reason[$index]=${deviceSummary(device)}")
            }
        }
    }

    private fun deviceSummary(device: AudioDeviceInfo): String {
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.address.takeIf(String::isNotBlank) ?: "-"
        } else {
            "unavailable"
        }
        return "id=${device.id}, type=${typeName(device.type)}(${device.type}), " +
            "productName=${device.productName}, address=$address"
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
        else -> "TYPE_$type"
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "MODE_NORMAL"
        AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
        AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
        else -> "MODE_$mode"
    }

    private fun registerReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered.compareAndSet(true, false)) return
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun currentRouteRequest(): VersionedAudioRouteSelection =
        synchronized(routeRequestLock) { routeRequest }

    private fun isCurrentRouteRequest(request: VersionedAudioRouteSelection): Boolean =
        !closed.get() && synchronized(routeRequestLock) { routeRequest == request }

    private fun postMainForRoute(
        request: VersionedAudioRouteSelection,
        block: () -> Unit
    ) {
        mainHandler.post {
            if (isCurrentRouteRequest(request)) block()
        }
    }

    private fun reportRouteError(request: VersionedAudioRouteSelection, t: Throwable) {
        logError(t)
        postMainForRoute(request) { onError(t) }
    }

    private fun logError(t: Throwable) {
        Log.e(TAG, "audio route error", t)
    }

    companion object {
        private const val TAG = "AudioRouteController"
        private const val MODERN_ROUTE_VERIFY_DELAY_MS = 1_500L
        private const val SPEAKER_FALLBACK_RETRY_DELAY_MS = 250L
        private val ROUTE_EXECUTOR = Executors.newSingleThreadExecutor()

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                emptyArray()
            }

        fun hasRequiredPermissions(context: Context): Boolean =
            requiredPermissions().all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
    }
}
