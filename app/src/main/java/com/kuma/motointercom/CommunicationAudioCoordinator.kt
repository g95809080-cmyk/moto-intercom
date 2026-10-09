package com.kuma.motointercom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AudioInterruptionState {
    NORMAL,
    PHONE_RINGING,
    PHONE_ACTIVE,
    FOCUS_LOST,
    RESUMING,
    ROUTE_UNAVAILABLE
}

internal enum class PhoneCallState {
    IDLE,
    RINGING,
    OFFHOOK
}

internal enum class AudioFocusResult {
    GRANTED,
    DELAYED,
    FAILED
}

internal interface IntercomAudioFocus : Closeable {
    fun setListener(listener: (Int) -> Unit)
    fun request(): AudioFocusResult
    fun abandon()
}

internal interface IntercomPhoneState : Closeable {
    fun start(listener: (PhoneCallState) -> Unit)
    fun currentState(): PhoneCallState
}

internal interface IntercomAudioPrompt : Closeable {
    fun playResumePrompt()
}

internal class AndroidIntercomAudioPrompt : IntercomAudioPrompt {
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
    }.getOrNull()

    override fun playResumePrompt() {
        runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    override fun close() {
        runCatching { toneGenerator?.release() }
    }
}

internal class AndroidIntercomAudioFocus(context: Context) : IntercomAudioFocus {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var listener: ((Int) -> Unit)? = null
    private val callbackHandler = Handler(Looper.getMainLooper())
    private var requestRevision = 0L
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: AudioFocusRequest? = null

    override fun setListener(listener: (Int) -> Unit) {
        this.listener = listener
    }

    override fun request(): AudioFocusResult {
        // Android keys queued focus events by listener identity. A new media
        // request must not reuse the identity of an abandoned request.
        abandon()
        val revision = ++requestRevision
        val requestListener = AudioManager.OnAudioFocusChangeListener { change ->
            if (revision != requestRevision || focusListener == null) return@OnAudioFocusChangeListener
            DiagnosticLog.i("CommunicationAudioFocus", "focus change=$change revision=$revision")
            listener?.invoke(change)
        }
        focusListener = requestListener
        focusRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(requestListener, callbackHandler)
                .build()
        } else {
            null
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(requireNotNull(focusRequest))
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                requestListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        DiagnosticLog.i("CommunicationAudioFocus", "request result=$result gain=TRANSIENT_MAY_DUCK usage=VOICE_COMMUNICATION")
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusResult.GRANTED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusResult.DELAYED
            else -> {
                abandon()
                AudioFocusResult.FAILED
            }
        }
    }

    override fun abandon() {
        ++requestRevision
        val abandonedRequest = focusRequest
        val abandonedListener = focusListener
        focusRequest = null
        focusListener = null
        DiagnosticLog.i("CommunicationAudioFocus", "abandon focus")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            abandonedRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            abandonedListener?.let { audioManager.abandonAudioFocus(it) }
        }
    }

    override fun close() {
        abandon()
        listener = null
    }
}

internal class AndroidIntercomPhoneState(context: Context) : IntercomPhoneState {
    private val appContext = context.applicationContext
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val subscriptionManager =
        appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
    private val callbackExecutor = Executor { command ->
        Handler(Looper.getMainLooper()).post(command)
    }
    private val lock = Any()
    private var state = PhoneCallState.IDLE
    private var listener: ((PhoneCallState) -> Unit)? = null
    private val callStates = mutableMapOf<Int, PhoneCallState>()
    private var telephonyCallbacks = emptyList<Pair<TelephonyManager, TelephonyCallback>>()
    private var phoneListeners = emptyList<Pair<TelephonyManager, PhoneStateListener>>()
    private var started = false

    private data class SubscriptionPhoneManager(
        val key: Int,
        val manager: TelephonyManager
    )

    override fun start(listener: (PhoneCallState) -> Unit) {
        synchronized(lock) {
            if (started) return
            started = true
            this.listener = listener
        }
        if (!hasPermission()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModern()
            } else {
                registerLegacy()
            }
            publishAggregatedState()
        }
    }

    override fun currentState(): PhoneCallState = synchronized(lock) { state }

    override fun close() {
        val modern: List<Pair<TelephonyManager, TelephonyCallback>>
        val legacy: List<Pair<TelephonyManager, PhoneStateListener>>
        synchronized(lock) {
            if (!started) return
            started = false
            modern = telephonyCallbacks
            legacy = phoneListeners
            telephonyCallbacks = emptyList()
            phoneListeners = emptyList()
            listener = null
            state = PhoneCallState.IDLE
            callStates.clear()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modern.forEach { (manager, callback) ->
                runCatching { manager.unregisterTelephonyCallback(callback) }
            }
        } else {
            legacy.forEach { (manager, phoneListener) ->
                @Suppress("DEPRECATION")
                runCatching { manager.listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModern() {
        val managers = subscriptionManagers()
        val callbacks = managers.map { subscription ->
            val manager = subscription.manager
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(callState: Int) {
                    updateCallState(subscription.key, callState)
                }
            }
            synchronized(lock) { callStates[subscription.key] = PhoneCallState.IDLE }
            manager.registerTelephonyCallback(callbackExecutor, callback)
            manager to callback
        }
        synchronized(lock) { telephonyCallbacks = callbacks }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy() {
        val managers = subscriptionManagers()
        val listeners = managers.map { subscription ->
            val manager = subscription.manager
            val phoneListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    updateCallState(subscription.key, state)
                }
            }
            synchronized(lock) { callStates[subscription.key] = PhoneCallState.IDLE }
            manager.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            manager to phoneListener
        }
        synchronized(lock) { phoneListeners = listeners }
    }

    private fun subscriptionManagers(): List<SubscriptionPhoneManager> {
        val canReadPhoneState = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val ids = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && canReadPhoneState
        ) {
            runCatching {
                subscriptionManager?.activeSubscriptionInfoList
                    ?.map { it.subscriptionId }
                    ?.distinct()
                    .orEmpty()
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return if (ids.isEmpty()) {
            listOf(SubscriptionPhoneManager(DEFAULT_SUBSCRIPTION_KEY, telephonyManager))
        } else {
            ids.map { id ->
                SubscriptionPhoneManager(
                    key = id,
                    manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        telephonyManager.createForSubscriptionId(id)
                    } else {
                        telephonyManager
                    }
                )
            }
        }
    }

    private fun publishAggregatedState() {
        subscriptionManagers().forEach { subscription ->
            runCatching {
                synchronized(lock) {
                    callStates[subscription.key] = toCallState(subscription.manager.callState)
                }
            }
        }
        val next = synchronized(lock) {
            callStates.values.maxByOrNull(::priority) ?: PhoneCallState.IDLE
        }
        updateState(next)
    }

    private fun updateCallState(key: Int, callState: Int) {
        val next = synchronized(lock) {
            callStates[key] = toCallState(callState)
            callStates.values.maxByOrNull(::priority) ?: PhoneCallState.IDLE
        }
        updateState(next)
    }

    private fun updateState(next: PhoneCallState) {
        val callback = synchronized(lock) {
            if (!started || state == next) return@synchronized null
            state = next
            listener
        }
        callback?.invoke(next)
    }

    private fun toCallState(value: Int): PhoneCallState = when (value) {
        TelephonyManager.CALL_STATE_RINGING -> PhoneCallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> PhoneCallState.OFFHOOK
        else -> PhoneCallState.IDLE
    }

    private fun priority(value: PhoneCallState): Int = when (value) {
        PhoneCallState.IDLE -> 0
        PhoneCallState.RINGING -> 1
        PhoneCallState.OFFHOOK -> 2
    }

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val DEFAULT_SUBSCRIPTION_KEY = -1
    }
}

internal class CommunicationAudioCoordinator(
    private val engine: RiderMediaEngine,
    private val route: RiderAudioRoute,
    private val audioFocus: IntercomAudioFocus,
    private val phoneState: IntercomPhoneState,
    private val activateRoute: () -> Unit,
    private val audioPrompt: IntercomAudioPrompt = AndroidIntercomAudioPrompt(),
    private val onStateChanged: (AudioInterruptionState) -> Unit = {}
) : Closeable {
    private val lock = Any()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private var mediaActive = false
    private var phoneCallState = PhoneCallState.IDLE
    private var awaitingRoute = false
    private var awaitingFocusGain = false
    private var resumePromptPending = false
    private var state = AudioInterruptionState.NORMAL
    private var retryRunnable: Runnable? = null

    internal fun currentState(): AudioInterruptionState = synchronized(lock) { state }

    init {
        audioFocus.setListener(::onAudioFocusChanged)
    }

    fun start() {
        if (closed.get()) return
        phoneState.start(::onPhoneStateChanged)
        val initialCallState: PhoneCallState
        synchronized(lock) {
            if (closed.get()) return
            initialCallState = phoneState.currentState()
            phoneCallState = initialCallState
        }
        if (initialCallState != PhoneCallState.IDLE) {
            publish(phoneStateToInterruption(initialCallState))
        }
    }

    fun beginMediaSession() {
        val callState: PhoneCallState
        synchronized(lock) {
            check(!closed.get()) { "audio coordinator is closed" }
            check(!mediaActive) { "an audio media session is already active" }
            mediaActive = true
            callState = phoneCallState
        }
        if (callState == PhoneCallState.IDLE) {
            requestFocusAndRoute()
        } else {
            suspendForPhone(callState)
        }
    }

    fun endMediaSession() {
        synchronized(lock) {
            if (!mediaActive) return
            mediaActive = false
            awaitingRoute = false
            awaitingFocusGain = false
            resumePromptPending = false
            cancelRetry()
            engine.suspendAudio()
            audioFocus.abandon()
            route.suspendForInterruption(restoreMode = phoneCallState == PhoneCallState.IDLE)
            publish(
                if (phoneCallState == PhoneCallState.IDLE) {
                    AudioInterruptionState.NORMAL
                } else {
                    phoneStateToInterruption(phoneCallState)
                }
            )
        }
    }

    internal fun canApplyPreferredRoute(): Boolean = synchronized(lock) {
        mediaActive && phoneCallState == PhoneCallState.IDLE &&
            (state == AudioInterruptionState.NORMAL ||
                state == AudioInterruptionState.RESUMING && awaitingRoute)
    }

    internal fun isPhoneCallActive(): Boolean = synchronized(lock) {
        phoneCallState != PhoneCallState.IDLE
    }

    /** A route event cannot reacquire focus or revive a phone/focus interruption. */
    internal fun reapplyPreferredRoute(force: Boolean = false) = synchronized(lock) {
        if (closed.get() || !canApplyPreferredRoute() || awaitingFocusGain || (!force && awaitingRoute)) return
        publish(AudioInterruptionState.RESUMING)
        activateAuthorizedRoute()
    }

    /** Called by the route after the selected device has been verified. */
    fun onRouteReady() {
        synchronized(lock) {
            if (closed.get()) return
            if (!mediaActive || phoneCallState != PhoneCallState.IDLE || !awaitingRoute) return
            awaitingRoute = false
            if (resumePromptPending) {
                resumePromptPending = false
                audioPrompt.playResumePrompt()
            }
            engine.resumeAudio()
            publish(AudioInterruptionState.NORMAL)
        }
    }

    private fun onPhoneStateChanged(next: PhoneCallState) {
        val previous: PhoneCallState
        val active: Boolean
        synchronized(lock) {
            previous = phoneCallState
            phoneCallState = next
            active = mediaActive
        }
        if (!active || previous == next && next == PhoneCallState.IDLE) return
        if (next == PhoneCallState.IDLE) {
            requestFocusAndRoute()
        } else if (previous == PhoneCallState.IDLE) {
            suspendForPhone(next)
        } else {
            // RINGING -> OFFHOOK is still one phone interruption. Update only
            // the local label; do not repeatedly tear down focus and routing.
            publish(phoneStateToInterruption(next))
        }
    }

    private fun onAudioFocusChanged(change: Int) {
        val phoneState = synchronized(lock) {
            if (closed.get() || !mediaActive) return
            phoneCallState
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (phoneState == PhoneCallState.IDLE) suspendForFocus(permanent = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (phoneState == PhoneCallState.IDLE) {
                    suspendForFocus(permanent = false)
                } else {
                    publish(phoneStateToInterruption(phoneState))
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(lock) {
                    if (phoneState == PhoneCallState.IDLE && awaitingFocusGain) {
                        awaitingFocusGain = false
                        cancelRetry()
                        publish(AudioInterruptionState.RESUMING)
                        activateAuthorizedRoute()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
    }

    private fun suspendForPhone(callState: PhoneCallState) {
        synchronized(lock) {
            cancelRetry()
            awaitingRoute = false
            awaitingFocusGain = false
            resumePromptPending = true
            engine.suspendAudio()
            audioFocus.abandon()
            route.suspendForInterruption(restoreMode = false)
            publish(phoneStateToInterruption(callState))
        }
    }

    private fun suspendForFocus(permanent: Boolean) {
        synchronized(lock) {
            if (closed.get() || !mediaActive || phoneCallState != PhoneCallState.IDLE) return
            cancelRetry()
            awaitingRoute = false
            awaitingFocusGain = !permanent
            resumePromptPending = true
            engine.suspendAudio()
            // Transient loss stays on the focus stack so Android can grant it back.
            if (permanent) audioFocus.abandon()
            route.suspendForInterruption(restoreMode = true)
            publish(AudioInterruptionState.FOCUS_LOST)
        }
    }

    private fun requestFocusAndRoute() {
        synchronized(lock) {
            if (closed.get() || !mediaActive || phoneCallState != PhoneCallState.IDLE) return
            cancelRetry()
            awaitingFocusGain = false
            publish(AudioInterruptionState.RESUMING)
            when (val focusResult = audioFocus.request()) {
                AudioFocusResult.GRANTED -> {
                    // A verified communication route is required before WebRTC I/O resumes.
                    if (closed.get() || !mediaActive || phoneCallState != PhoneCallState.IDLE) {
                        audioFocus.abandon()
                        return
                    }
                    activateAuthorizedRoute()
                }
                AudioFocusResult.DELAYED -> {
                    awaitingRoute = false
                    awaitingFocusGain = true
                    engine.suspendAudio()
                    route.suspendForInterruption(restoreMode = true)
                }
                AudioFocusResult.FAILED -> {
                    awaitingRoute = false
                    engine.suspendAudio()
                    route.suspendForInterruption(restoreMode = true)
                    scheduleRetry()
                }
            }
        }
    }

    private fun activateAuthorizedRoute() {
        if (closed.get() || !mediaActive || phoneCallState != PhoneCallState.IDLE) return
        engine.suspendAudio()
        awaitingRoute = true
        activateRoute()
    }

    private fun scheduleRetry() {
        synchronized(lock) {
            if (retryRunnable != null || closed.get() || !mediaActive) return
            val retry = Runnable {
                synchronized(lock) { retryRunnable = null }
                requestFocusAndRoute()
            }
            retryRunnable = retry
            retryHandler.postDelayed(retry, RETRY_DELAY_MS)
        }
    }

    private fun cancelRetry() {
        retryRunnable?.let(retryHandler::removeCallbacks)
        retryRunnable = null
    }

    private fun publish(next: AudioInterruptionState) {
        val changed = synchronized(lock) {
            if (state == next) {
                false
            } else {
                state = next
                true
            }
        }
        if (changed) onStateChanged(next)
    }

    private fun phoneStateToInterruption(state: PhoneCallState): AudioInterruptionState =
        when (state) {
            PhoneCallState.RINGING -> AudioInterruptionState.PHONE_RINGING
            PhoneCallState.OFFHOOK -> AudioInterruptionState.PHONE_ACTIVE
            PhoneCallState.IDLE -> AudioInterruptionState.NORMAL
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            mediaActive = false
            cancelRetry()
            awaitingRoute = false
            awaitingFocusGain = false
            resumePromptPending = false
        }
        engine.suspendAudio()
        phoneState.close()
        audioFocus.close()
        audioPrompt.close()
        route.suspendForInterruption(restoreMode = phoneCallState == PhoneCallState.IDLE)
    }

    companion object {
        private const val RETRY_DELAY_MS = 1_000L
    }
}
