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

internal data class AudioRouteEvidence(val revision: Long, val ready: Boolean)

/** Owns the process audio mode and routes intercom audio to a headset or phone. */
internal class AudioRouteController(
    context: Context,
    private val fallbackToSpeaker: Boolean = true,
    private val onScoConnected: (String) -> Unit = {},
    private val onScoDisconnected: () -> Unit = {},
    private val onSpeakerFallback: (noBluetooth: Boolean) -> Unit = {},
    private val onEarpieceActive: () -> Unit = {},
    private val onExternalAudioActive: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val modernRouteFactory: (() -> CommunicationDeviceRoute)? = null,
    private val onRouteReady: () -> Unit = {},
    private val onRouteInvalidated: () -> Unit = {}
) : RiderAudioRoute {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val evidenceLock = Any()
    private var evidenceRevision = 0L
    private var verifiedDevice: String? = null
    private var invalidationPending = false
    private fun actualDevice(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice?.let { "${it.id}:${it.type}" }
    } else {
        @Suppress("DEPRECATION")
        "${audioManager.isBluetoothScoOn}:${audioManager.isSpeakerphoneOn}:" +
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.sorted().joinToString(",")
    }
    /** Read both before and after an asynchronous stats query; checks the actual platform route. */
    override fun evidence(): AudioRouteEvidence {
        synchronized(evidenceLock) {
            if (verifiedDevice != null && verifiedDevice != actualDevice()) invalidateEvidence()
            return AudioRouteEvidence(evidenceRevision, !closed.get() && verifiedDevice != null)
        }
    }
    private fun invalidateEvidence() {
        synchronized(evidenceLock) {
            if (verifiedDevice != null) invalidationPending = true
            verifiedDevice = null; evidenceRevision++
        }
        mainHandler.post(::deliverInvalidation)
    }
    private fun deliverInvalidation() {
        val pending = synchronized(evidenceLock) {
            invalidationPending.also { invalidationPending = false }
        }
        if (pending && !closed.get()) onRouteInvalidated()
    }
    private fun publishVerifiedRoute(request: VersionedAudioRouteSelection) {
        val device = actualDevice() ?: return
        val revision = synchronized(evidenceLock) { evidenceRevision }
        postMainForRoute(request) {
            deliverInvalidation()
            val accepted = synchronized(evidenceLock) {
                if (revision != evidenceRevision || actualDevice() != device) false
                else { verifiedDevice = device; true }
            }
            if (accepted) onRouteReady()
        }
    }
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
    @Volatile private var legacyFallbackActive = false
    private var modernRoute: CommunicationDeviceRoute? = null
    private val speakerFallbackRecovery = AudioSpeakerFallbackRecovery()
    private var speakerFallbackRunnable: Runnable? = null
    private var phoneRouteVerificationRunnable: Runnable? = null

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
                        legacyFallbackActive = false
                        scoEverConnected = true
                        @Suppress("DEPRECATION")
                        audioManager.isBluetoothScoOn = true
                        publishBluetoothConnected(bluetoothDeviceName())
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        if (legacyFallbackActive) return@execute
                        if (fallbackToSpeaker) {
                            fallbackToPhone(!scoEverConnected, "legacy SCO disconnected", request)
                        } else if (scoEverConnected) {
                            postMainForRoute(request, onScoDisconnected)
                        }
                    }

                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        if (legacyFallbackActive) return@execute
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
            evidence()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                rerouteAfterDeviceChange("device added")
            } else if (addedDevices.any(::isLegacyBluetoothCommunicationDevice)) {
                retryLegacyBluetoothAfterDeviceAdded()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            logDevices("AudioDeviceCallback removed", removedDevices.toList())
            evidence()
            rerouteAfterDeviceChange("device removed")
        }
    }

    override fun select(selection: AudioRouteSelection) {
        if (closed.get()) return
        invalidateEvidence()
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
                cancelPhoneRouteVerification()
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

    override fun suspendForInterruption(restoreMode: Boolean) {
        if (closed.get()) return
        invalidateEvidence()
        val request = synchronized(routeRequestLock) {
            VersionedAudioRouteSelection(routeRequest.revision + 1, routeRequest.selection).also {
                routeRequest = it
                wantBluetoothSco = false
                bluetoothReported = false
                bluetoothReportRevision = null
            }
        }
        cancelSpeakerFallbackRetry()
        cancelPhoneRouteVerification()
        ROUTE_EXECUTOR.execute {
            if (!isCurrentRouteRequest(request)) return@execute
            try {
                if (restoreMode) audioManager.mode = AudioManager.MODE_NORMAL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    modernRoute?.clear()
                } else {
                    stopLegacySco()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to suspend audio route for interruption", t)
            }
        }
    }

    private fun selectBluetooth(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || !wantBluetoothSco) return
        legacyFallbackActive = false
        if (!hasRequiredPermissions(appContext)) {
            throw SecurityException("缺少蓝牙音频路由运行时权限")
        }
        registerAudioDeviceCallback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernRoute().register()
            routeModernBluetooth("selection", request)
        } else {
            registerReceiver()
            startLegacySco(request)
        }
    }

    private fun selectPhoneRoute(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || wantBluetoothSco) return
        registerAudioDeviceCallback()
        val selection = request.selection
        check(selection != AudioRouteSelection.BLUETOOTH)
        cancelSpeakerFallbackRetry()
        cancelPhoneRouteVerification()
        modernFallbackActive = false
        legacyFallbackActive = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val route = modernRoute()
            route.register()
            when (route.routeTo(selection)) {
                ModernAudioRoute.RouteResult.ROUTED ->
                    verifyModernPhoneRoute(route, request, attempt = 1)
                ModernAudioRoute.RouteResult.NO_MATCHING_DEVICE ->
                    reportRouteError(
                        request,
                        IllegalStateException("requested phone audio route unavailable: $selection")
                    )
                ModernAudioRoute.RouteResult.REJECTED ->
                    reportRouteError(
                        request,
                        IllegalStateException("requested phone audio route was rejected: $selection")
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
                if (selection == AudioRouteSelection.EARPIECE) {
                    val externalOutput = legacyExternalAudioOutputLabel()
                    if (externalOutput == null) {
                        completePhoneRoute(request)
                    } else {
                        completeExternalAudioRoute(request, externalOutput)
                    }
                } else {
                    completePhoneRoute(request)
                }
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
        cancelPhoneRouteVerification()
        bluetoothReported = false
        bluetoothReportRevision = null
        when (request.selection) {
            AudioRouteSelection.EARPIECE -> postMainForRoute(request, onEarpieceActive)
            AudioRouteSelection.SPEAKER -> postMainForRoute(request) { onSpeakerFallback(false) }
            AudioRouteSelection.BLUETOOTH -> Unit
        }
        publishVerifiedRoute(request)
    }

    private fun completeExternalAudioRoute(
        request: VersionedAudioRouteSelection,
        outputLabel: String
    ) {
        if (
            !isCurrentRouteRequest(request) ||
            request.selection != AudioRouteSelection.EARPIECE ||
            wantBluetoothSco
        ) return
        cancelPhoneRouteVerification()
        bluetoothReported = false
        bluetoothReportRevision = null
        postMainForRoute(request) { onExternalAudioActive(outputLabel) }
        publishVerifiedRoute(request)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun verifyModernPhoneRoute(
        route: CommunicationDeviceRoute,
        request: VersionedAudioRouteSelection,
        attempt: Int
    ) {
        if (!isCurrentRouteRequest(request) || wantBluetoothSco) return
        if (route.isActive(request.selection)) {
            completePhoneRoute(request)
            return
        }
        if (attempt >= PHONE_ROUTE_VERIFY_ATTEMPTS) {
            reportRouteError(
                request,
                IllegalStateException(
                    "requested phone audio route verification failed: " +
                        "${request.selection}, ${route.stateSummary()}"
                )
            )
            return
        }
        val retry = Runnable {
            phoneRouteVerificationRunnable = null
            if (!isCurrentRouteRequest(request)) return@Runnable
            ROUTE_EXECUTOR.execute {
                verifyModernPhoneRoute(route, request, attempt + 1)
            }
        }
        phoneRouteVerificationRunnable = retry
        mainHandler.postDelayed(retry, PHONE_ROUTE_VERIFY_DELAY_MS)
    }

    private fun cancelPhoneRouteVerification() {
        phoneRouteVerificationRunnable?.let(mainHandler::removeCallbacks)
        phoneRouteVerificationRunnable = null
    }

    fun reset() = closeRoute(restoreInitialState = true)

    override fun closeRoute(restoreInitialState: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        wantBluetoothSco = false
        cancelSpeakerFallbackRetry()
        cancelPhoneRouteVerification()
        legacyFallbackActive = false
        ROUTE_EXECUTOR.execute {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val route = modernRoute
                    modernRoute = null
                    if (restoreInitialState) {
                        restoreStep { initialMode?.let { audioManager.mode = it } }
                    }
                    restoreStep { route?.close(restoreInitialState) }
                } else {
                    restoreStep { stopLegacySco() }
                    if (restoreInitialState) {
                        restoreStep {
                            initialSpeakerphoneOn?.let {
                                @Suppress("DEPRECATION")
                                audioManager.isSpeakerphoneOn = it
                            }
                        }
                        restoreStep { initialMode?.let { audioManager.mode = it } }
                    }
                }
            } finally {
                unregisterReceiver()
                unregisterAudioDeviceCallback()
            }
        }
    }

    override fun close() = closeRoute(restoreInitialState = true)

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
        invalidateEvidence()
        cancelSpeakerFallbackRetry()
        modernFallbackActive = false
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        val route = modernRoute()
        Log.i(TAG, "modern route[$reason]: ${route.stateSummary()}")
        when (route.routeTo(AudioRouteSelection.BLUETOOTH)) {
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
    private fun modernRoute(): CommunicationDeviceRoute {
        return modernRoute ?: (
            modernRouteFactory?.invoke() ?: ModernAudioRoute(
                audioManager = audioManager,
                callbackExecutor = ROUTE_EXECUTOR,
                onBluetoothConnected = { name ->
                    if (!closed.get() && wantBluetoothSco) publishBluetoothConnected(name)
                },
                onDeviceChanged = { evidence() },
                onDeviceLost = {
                    evidence()
                    if (!closed.get() && wantBluetoothSco && !modernFallbackActive) {
                        rerouteModernOnExecutor(
                            "communication device changed",
                            currentRouteRequest()
                        )
                    }
                }
            )
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

    private fun retryLegacyBluetoothAfterDeviceAdded() {
        if (closed.get() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        ROUTE_EXECUTOR.execute {
            val request = currentRouteRequest()
            if (
                !isCurrentRouteRequest(request) ||
                request.selection != AudioRouteSelection.BLUETOOTH ||
                !wantBluetoothSco
            ) return@execute
            try {
                startLegacySco(request)
            } catch (t: Throwable) {
                if (!isCurrentRouteRequest(request)) return@execute
                reportRouteError(request, t)
                if (fallbackToSpeaker) fallbackToPhone(true, "legacy device-added route error", request)
            }
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
            val firstFallback = !legacyFallbackActive
            legacyFallbackActive = true
            stopLegacySco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            if (audioManager.isSpeakerphoneOn && firstFallback) {
                completeSpeakerFallback(noBluetooth, request)
            } else if (!audioManager.isSpeakerphoneOn) {
                reportRouteError(request, IllegalStateException("phone speaker route was not accepted"))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun trySpeakerFallback(
        route: CommunicationDeviceRoute,
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
            route.routeTo(AudioRouteSelection.SPEAKER) == ModernAudioRoute.RouteResult.ROUTED
        } catch (t: Throwable) {
            reportRouteError(request, t)
            false
        }
        if (
            speakerRouted &&
            route.isActive(AudioRouteSelection.SPEAKER) &&
            isCurrentRouteRequest(request)
        ) {
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
        publishVerifiedRoute(request)
    }

    private fun cancelSpeakerFallbackRetry() {
        speakerFallbackRecovery.reset()
        speakerFallbackRunnable?.let(mainHandler::removeCallbacks)
        speakerFallbackRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun startLegacySco(request: VersionedAudioRouteSelection) {
        if (!isCurrentRouteRequest(request) || !wantBluetoothSco) return
        Log.i(TAG, "legacy SCO route: sdk=${Build.VERSION.SDK_INT}, mode=${modeName(audioManager.mode)}")
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w(TAG, "legacy SCO route: isBluetoothScoAvailableOffCall=false")
            if (fallbackToSpeaker) fallbackToPhone(true, "legacy SCO unavailable", request)
            return
        }
        legacyFallbackActive = false
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
        cancelPhoneRouteVerification()
        modernFallbackActive = false
        legacyFallbackActive = false
        if (bluetoothReported && bluetoothReportRevision == request.revision) {
            publishVerifiedRoute(request)
            return
        }
        bluetoothReported = true
        bluetoothReportRevision = request.revision
        postMainForRoute(request) { onScoConnected(name.ifBlank { "头盔蓝牙" }) }
        publishVerifiedRoute(request)
    }

    private fun bluetoothDeviceName(): String {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        return device?.let(::safeProductName)?.takeIf(String::isNotBlank) ?: "头盔蓝牙"
    }

    private fun registerAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered.compareAndSet(false, true)) return
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        Log.i(TAG, "audio route: AudioDeviceCallback registered")
    }

    private fun unregisterAudioDeviceCallback() {
        if (!audioDeviceCallbackRegistered.compareAndSet(true, false)) return
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            Log.i(TAG, "audio route: AudioDeviceCallback unregistered")
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
            "productName=${safeProductName(device).ifBlank { "-" }}, address=$address"
    }

    private fun safeProductName(device: AudioDeviceInfo): String =
        runCatching { device.productName?.toString().orEmpty() }.getOrDefault("")

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

    private fun isLegacyBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    private fun legacyExternalAudioOutputLabel(): String? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstNotNullOfOrNull { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 音频设备"
                AudioDeviceInfo.TYPE_HEARING_AID -> "助听设备"
                else -> null
            }
        }

    private fun reportRouteError(request: VersionedAudioRouteSelection, t: Throwable) {
        invalidateEvidence()
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
        private const val PHONE_ROUTE_VERIFY_DELAY_MS = 250L
        private const val PHONE_ROUTE_VERIFY_ATTEMPTS = 7
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
