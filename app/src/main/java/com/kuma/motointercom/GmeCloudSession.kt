package com.kuma.motointercom

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gme.TMG.ITMGContext
import com.gme.av.sdk.AVError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable

internal class GmeCloudSession(
    context: Context,
    private val ticketSource: GmeTicketSource,
    private val onState: (CloudIntercomState) -> Unit,
    private val onClosed: () -> Unit,
    private val onLog: (String) -> Unit,
    initialAudioControls: VersionedAudioControls = VersionedAudioControls(0,AudioControlSettings()),
    private val onVoxState: (VersionedAudioControls,VoxRuntimeState) -> Unit = {_,_->},
    private val onAudioLevel: (Float) -> Unit = {},
    private val onRouteRequired: ()->Unit = {},
    private val onUsageWarning: ()->Unit = {},
    private val engineFactory: (Context,ITMGContext.ITMGDelegate) -> GmeEngine = ::TencentGmeEngine,
    private val audioIdleWitness: (() ->Boolean)? = null
) : ITMGContext.ITMGDelegate(), Closeable, CommunicationAudioTarget {

    private val appContext = context.applicationContext
    private val activityWitness=GmeAudioActivityWitness.from(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val remoteUsers = linkedSetOf<String>()
    private val mediaPolicy = CloudMediaPolicy()
    private val vox = CloudVoxController(initialAudioControls)
    private val audioAuthorized = MutableStateFlow(false)
    private var enginePaused = false
    private var audioRevision=0L
    private var pauseNeedsRecovery=false
    private var appliedSending: Boolean? = null
    private var publishedVox: Pair<VersionedAudioControls,VoxRuntimeState>? = null
    private var lastAudioStateLog = ""
    var nativeReleaseSucceeded: Boolean = true
        private set

    private var ticketJob: Job? = null
    private var engine: GmeEngine? = null
    private var peerDeviceId = ""
    private var rideId=""
    private var peerName = ""
    private var ownOpenId = ""
    private var peerOpenId = ""
    private var cloudDeadlineElapsed = 0L
    private var enterDeadlineElapsed: Long?=null
    private var enterRemainingMs=ENTER_ROOM_TIMEOUT_MS
    private var enteredRoom = false
    private var closeRequested = false
    private var completed = false
    private var lastState: CloudIntercomState = CloudIntercomState.Idle
    val audioIsReady get()=enteredRoom && audioAuthorized.value && !enginePaused && !closeRequested && !completed

    private val poll = object : Runnable {
        override fun run() {
            if (completed) return
            if (!closeRequested && enteredRoom && SystemClock.elapsedRealtime() >= cloudDeadlineElapsed) {
                fail("本次骑行云端时长已达上限，请手动延长后继续")
                return
            }
            if (!closeRequested && enteredRoom && mediaPolicy.peerWaitExpired(SystemClock.elapsedRealtime())) {
                leaveForManualResume()
                return
            }
            try {
                engine?.poll()
            } catch (error: Throwable) {
                fail("云端音频引擎异常")
                return
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val sampleAudio = object : Runnable {
        override fun run() {
            if (completed || closeRequested || !enteredRoom) return
            try {
                val active=audioAuthorized.value && !enginePaused
                val output=if(active && !vox.settings().settings.muted) vox.sample(engine?.micLevel() ?: 0,SystemClock.elapsedRealtime())
                    else vox.current(active)
                setAudioSending(output.sendEnabled)
                publishVox(output.state)
                logAudioState(active,output)
                onAudioLevel(output.level)
            } catch (_: Throwable) { fail("云端音量采样失败"); return }
            mainHandler.postDelayed(this,AUDIO_SAMPLE_MS)
        }
    }

    private val enterRoomTimeout = Runnable {
        if (!completed && !enteredRoom && !enginePaused) fail("进入云端房间超时")
    }

    private val exitRoomTimeout = Runnable {
        if (!completed) completeClose()
    }

    private val peerTimeout = Runnable {
        if (!completed && !closeRequested && mediaPolicy.peerWaitExpired(SystemClock.elapsedRealtime())) {
            leaveForManualResume()
        }
    }

    private val cloudLimitTimeout = Runnable {
        if (!completed && !closeRequested) fail("本次骑行云端时长已达上限，请手动延长后继续")
    }
    private val cloudWarningTimeout=Runnable { if(!completed && !closeRequested) onUsageWarning() }

    fun start(ride: ApprovedCloudRide) {
        if (completed || ticketJob != null) return
        peerDeviceId = ride.peerDeviceId
        rideId=ride.rideId
        peerName = ride.peerName
        val remaining = ride.cloudDeadlineAt - System.currentTimeMillis()
        if (remaining <= 0) { fail("本次骑行云端时长已达上限，请手动延长后继续"); return }
        cloudDeadlineElapsed = SystemClock.elapsedRealtime() + remaining
        mainHandler.postDelayed(cloudLimitTimeout,remaining)
        mainHandler.postDelayed(cloudWarningTimeout,(remaining-30*60*1_000L).coerceAtLeast(0))
        emit(CloudIntercomState.Preparing(peerDeviceId, peerName))
        ticketJob = scope.launch {
            try {
                var ticket = ticketSource.fetch(ride)
                audioAuthorized.first { it }
                while (ticket.expiresAtEpochSeconds <= System.currentTimeMillis()/1_000+30) {
                    ticket=ticketSource.fetch(ride)
                    audioAuthorized.first { it }
                }
                if (closeRequested || completed) return@launch
                if (SystemClock.elapsedRealtime() >= cloudDeadlineElapsed) {
                    fail("本次骑行云端时长已达上限，请手动延长后继续")
                    return@launch
                }
                ownOpenId = ticket.openId
                peerOpenId = ticket.peerOpenId
                emit(CloudIntercomState.JoiningRoom(peerName))
                enterRoom(ticket)
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (error: Throwable) {
                fail("无法获取云端房间票据，请稍后重试")
            }
        }
    }

    fun setMuted(muted: Boolean) {
        updateAudioControls(VersionedAudioControls(vox.settings().revision+1,vox.settings().settings.copy(muted=muted)))
    }

    fun updateAudioControls(controls: VersionedAudioControls) {
        if (completed || closeRequested || !vox.update(controls)) return
        mediaPolicy.setMuted(vox.settings().settings.muted)
        if (enteredRoom) runCatching { applyAudioControls() }.onFailure { fail("切换云端麦克风失败") }
        else publishVox(vox.current(false).state)
    }

    override fun suspendAudio() {
        if (completed || closeRequested) return
        audioRevision++
        audioAuthorized.value=false
        vox.reset()
        if(!enteredRoom) {
            enterDeadlineElapsed?.let { enterRemainingMs=(it-SystemClock.elapsedRealtime()).coerceAtLeast(1) }
            enterDeadlineElapsed=null; mainHandler.removeCallbacks(enterRoomTimeout)
        }
        if (enteredRoom) runCatching {
            setAudioSending(false); checkOk(engine?.mic(false)); checkOk(engine?.speaker(false))
        }.onFailure { fail("暂停云端音频失败"); return }
        if (engine!=null && !enginePaused && !pauseNeedsRecovery) runCatching {
            checkOk(engine?.pause()); enginePaused=true
        }.onFailure { fail("暂停云端音频失败"); return }
        publishVox(vox.current(false).state); onAudioLevel(0f)
    }

    override fun resumeAudio() {
        if (completed || closeRequested) return
        audioRevision++
        try {
            if (enteredRoom) setAudioSending(false)
            if (enginePaused || pauseNeedsRecovery) { checkOk(engine?.resume()); enginePaused=false;pauseNeedsRecovery=false }
            vox.reset()
            audioAuthorized.value=true
            if (enteredRoom) applyAudioControls()
            else if(engine!=null) armEnterTimeout()
        } catch (_: Throwable) { fail("恢复云端音频失败") }
    }

    override fun close() {
        stop()
    }

    override fun suspendForHandover(callback: (Boolean)->Unit) {
        if(completed || closeRequested || !enteredRoom) {callback(false);return}
        audioRevision++;audioAuthorized.value=false;vox.reset()
        try {
            setAudioSending(false);checkOk(engine?.mic(false));checkOk(engine?.speaker(false))
            if(!enginePaused) {pauseNeedsRecovery=true;checkOk(engine?.pause());enginePaused=true;pauseNeedsRecovery=false}
            publishVox(vox.current(false).state);onAudioLevel(0f)
            whenAudioApplied(false,callback)
        } catch(_: Throwable) {callback(false)}
    }

    override fun whenAudioApplied(enabled: Boolean,callback: (Boolean)->Unit) {
        val revision=audioRevision
        val deadline=SystemClock.elapsedRealtime()+200
        var quiet=0
        val poll=object : Runnable {
            override fun run() {
                if(completed || closeRequested || audioRevision!=revision) {callback(false);return}
                if(enabled) {callback(enteredRoom && audioAuthorized.value && !enginePaused);return}
                val idle=runCatching {enginePaused && !audioAuthorized.value && engine?.audioDevicesDisabled()==true &&
                    (audioIdleWitness?.invoke() ?: activityWitness.idle())}.getOrElse {callback(false);return}
                quiet=if(idle)quiet+1 else 0
                if(quiet>=2) callback(true)
                else if(SystemClock.elapsedRealtime()>=deadline)callback(false)
                else mainHandler.postDelayed(this,10)
            }
        }
        poll.run()
    }

    fun stop() {
        if (completed || closeRequested) return
        closeRequested = true
        ticketJob?.cancel()
        mainHandler.removeCallbacks(enterRoomTimeout)
        mainHandler.removeCallbacks(peerTimeout)
        mainHandler.removeCallbacks(cloudLimitTimeout)
        mainHandler.removeCallbacks(sampleAudio)
        mainHandler.removeCallbacks(cloudWarningTimeout)
        emit(CloudIntercomState.Stopping(peerName))
        val current = engine
        if (current == null || !enteredRoom) {
            scheduleCloseCompletion(0)
            return
        }
        try {
            current.audioSend(false); current.mic(false); current.speaker(false)
            val result = current.exit()
            if (result == AVError.AV_OK) {
                mainHandler.postDelayed(exitRoomTimeout, EXIT_ROOM_TIMEOUT_MS)
            } else {
                completeClose()
            }
        } catch (_: Throwable) {
            completeClose()
        }
    }

    private fun leaveForManualResume() {
        if(completed || closeRequested)return
        stop()
        emit(CloudIntercomState.ResumeAvailable(rideId,peerName))
    }

    override fun OnEvent(type: ITMGContext.ITMG_MAIN_EVENT_TYPE, data: Intent?) {
        if (completed) return
        when (type) {
            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_ENTER_ROOM -> {
                mainHandler.removeCallbacks(enterRoomTimeout)
                enterDeadlineElapsed=null
                val result = data?.getIntExtra("result", -1) ?: -1
                onLog("GME enter-room event result=$result")
                if (result != AVError.AV_OK) {
                    fail("进入云端房间失败（$result）")
                    return
                }
                enteredRoom = true
                if (closeRequested) {
                    runCatching { engine?.audioSend(false); engine?.mic(false); engine?.speaker(false); engine?.exit() }
                    scheduleCloseCompletion(EXIT_ROOM_TIMEOUT_MS)
                    return
                }
                configureAudioForAnchor()
                if (!closeRequested) {
                    updatePeerTimeout()
                    mainHandler.removeCallbacks(sampleAudio)
                    mainHandler.post(sampleAudio)
                    emit(currentRoomState())
                }
            }

            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_EXIT_ROOM -> {
                if (closeRequested) {
                    completeClose()
                } else {
                    fail("已离开云端房间")
                }
            }

            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_ROOM_DISCONNECT,
            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_RECONNECT_START -> {
                if (!closeRequested) emit(CloudIntercomState.Reconnecting(peerName))
            }

            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVENT_TYPE_RECONNECT_SUCCESS -> {
                if (!closeRequested) { configureAudioForAnchor(); if(!closeRequested) emit(currentRoomState()) }
            }

            ITMGContext.ITMG_MAIN_EVENT_TYPE.ITMG_MAIN_EVNET_TYPE_USER_UPDATE -> {
                handleUserUpdate(data)
            }

            else -> Unit
        }
    }

    private fun enterRoom(ticket: GmeTicket) {
        if (closeRequested || completed) return
        val current = try {
            engineFactory(appContext,this)
        } catch (error: Throwable) {
            fail("GME SDK 初始化失败")
            return
        }
        engine = current
        val initResult = current.initialize(ticket.appId,ticket.openId)
        onLog("GME init result=$initResult")
        if (initResult != AVError.AV_OK && initResult != AVError.AV_ERR_HAS_IN_THE_STATE) {
            fail("GME 初始化失败（$initResult）")
            return
        }
        startPolling()
        enterRemainingMs=ENTER_ROOM_TIMEOUT_MS; armEnterTimeout()
        val enterResult = current.enter(ticket)
        onLog("GME enter-room result=$enterResult")
        if (enterResult != AVError.AV_OK) {
            mainHandler.removeCallbacks(enterRoomTimeout)
            fail("进入云端房间失败（$enterResult）")
        }
    }
    private fun armEnterTimeout() {
        mainHandler.removeCallbacks(enterRoomTimeout)
        enterDeadlineElapsed=SystemClock.elapsedRealtime()+enterRemainingMs
        mainHandler.postDelayed(enterRoomTimeout,enterRemainingMs)
    }

    private fun configureAudioForAnchor() {
        val current = engine ?: return
        runCatching {
            val anchorResult=current.anchor()
            onLog("GME anchor result=$anchorResult")
            checkOk(anchorResult)
            suspendAudio()
            onRouteRequired()
        }.onFailure { fail("开启云端音频失败") }
    }

    private fun applyAudioControls() {
        if (!enteredRoom || completed || closeRequested) return
        val active=audioAuthorized.value && !enginePaused
        val output=vox.current(active)
        // Enable capture while upstream sending is closed, then open sending only
        // when mute and the independent VOX gate both permit it.
        setAudioSending(false)
        checkOk(engine?.micVolume(100))
        checkOk(engine?.mic(active && !vox.settings().settings.muted))
        checkOk(engine?.speaker(active))
        setAudioSending(output.sendEnabled)
        publishVox(output.state)
        logAudioState(active,output)
    }
    private fun setAudioSending(enabled: Boolean) {
        if (!enteredRoom || appliedSending==enabled) return
        checkOk(engine?.audioSend(enabled))
        appliedSending=enabled
        onLog("GME audio send=$enabled")
    }
    private fun checkOk(result: Int?) { check(result==AVError.AV_OK) }
    private fun publishVox(state: VoxRuntimeState) {
        val snapshot=vox.settings() to state
        if (publishedVox!=snapshot) { publishedVox=snapshot; onVoxState(snapshot.first,snapshot.second) }
    }
    private fun logAudioState(active: Boolean, output: CloudVoxController.Output) {
        val signature="$active|${vox.settings().settings.muted}|${vox.settings().settings.voxEnabled}|${output.sendEnabled}|${output.state}"
        if (signature==lastAudioStateLog) return
        lastAudioStateLog=signature
        onLog("GME audio state active=$active muted=${vox.settings().settings.muted} "+
            "vox=${vox.settings().settings.voxEnabled} send=${output.sendEnabled} state=${output.state}")
    }

    private fun handleUserUpdate(data: Intent?) {
        val users = data?.getStringArrayExtra("user_list").orEmpty()
        if (data?.getIntExtra("event_id", 0) == ITMGContext.ITMG_EVENT_ID_USER_ENTER &&
            users.any { it.isNotBlank() && it != ownOpenId && it != peerOpenId }) {
            fail("云端房间成员与本次骑行不符")
            return
        }
        when (data?.getIntExtra("event_id", 0)) {
            ITMGContext.ITMG_EVENT_ID_USER_ENTER -> {
                users.asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it != ownOpenId }
                    .forEach(remoteUsers::add)
            }

            ITMGContext.ITMG_EVENT_ID_USER_EXIT -> {
                users.asSequence()
                    .map(String::trim)
                    .forEach(remoteUsers::remove)
            }
        }
        if (enteredRoom && !closeRequested) {
            updatePeerTimeout()
            emit(currentRoomState())
        }
    }

    private fun updatePeerTimeout() {
        mediaPolicy.updatePeers(remoteUsers.size, SystemClock.elapsedRealtime())
        mainHandler.removeCallbacks(peerTimeout)
        mediaPolicy.peerDeadlineMs?.let { deadline ->
            mainHandler.postDelayed(peerTimeout, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    private fun currentRoomState(): CloudIntercomState =
        if (remoteUsers.isEmpty()) {
            CloudIntercomState.WaitingForPeer(peerName)
        } else {
            CloudIntercomState.Connected(peerName, remoteUsers.size)
        }

    private fun startPolling() {
        mainHandler.removeCallbacks(poll)
        mainHandler.postDelayed(poll, POLL_INTERVAL_MS)
    }

    private fun fail(message: String) {
        if (completed || closeRequested) return
        emit(CloudIntercomState.Failed(peerName, message))
        onLog("GME cloud session failed: $message")
        closeRequested = true
        ticketJob?.cancel()
        mainHandler.removeCallbacks(enterRoomTimeout)
        mainHandler.removeCallbacks(peerTimeout)
        mainHandler.removeCallbacks(cloudLimitTimeout)
        mainHandler.removeCallbacks(sampleAudio)
        mainHandler.removeCallbacks(cloudWarningTimeout)
        val current = engine
        if (current != null && enteredRoom) {
            runCatching { current.audioSend(false); current.mic(false); current.speaker(false); current.exit() }
        }
        scheduleCloseCompletion(CLOSE_GRACE_MS)
    }

    private fun scheduleCloseCompletion(delayMs: Long) {
        mainHandler.removeCallbacks(exitRoomTimeout)
        if (delayMs <= 0) {
            mainHandler.post { completeClose() }
        } else {
            mainHandler.postDelayed(exitRoomTimeout, delayMs)
        }
    }

    private fun completeClose() {
        if (completed) return
        completed = true
        closeRequested = true
        mainHandler.removeCallbacks(poll)
        mainHandler.removeCallbacks(enterRoomTimeout)
        mainHandler.removeCallbacks(exitRoomTimeout)
        ticketJob?.cancel()
        mainHandler.removeCallbacks(peerTimeout)
        mainHandler.removeCallbacks(cloudLimitTimeout)
        mainHandler.removeCallbacks(sampleAudio)
        mainHandler.removeCallbacks(cloudWarningTimeout)
        val current = engine
        engine = null
        if (current != null) {
            if (enteredRoom) runCatching { current.audioSend(false); current.mic(false); current.speaker(false) }
            nativeReleaseSucceeded=runCatching { current.uninitialize()==AVError.AV_OK }.getOrDefault(false)
        }
        enteredRoom = false
        remoteUsers.clear()
        scope.cancel()
        onClosed()
    }

    private fun emit(next: CloudIntercomState) {
        if (completed || next == lastState) return
        lastState = next
        onState(next)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 33L
        const val AUDIO_SAMPLE_MS = 20L
        const val ENTER_ROOM_TIMEOUT_MS = 20_000L
        const val EXIT_ROOM_TIMEOUT_MS = 2_000L
        const val CLOSE_GRACE_MS = 300L
    }
}
