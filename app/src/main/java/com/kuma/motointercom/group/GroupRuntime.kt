package com.kuma.motointercom.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.*
import java.io.Closeable

/** Service-owned effect executor. Product state is exclusively in writer. */
internal class GroupRuntime(
    private val context: Context,
    endpoint: GroupAuthEndpoint,
    nickname: String,
    private var initialRoute: AudioRouteSelection,
    private var initialControls: AudioControlSettings,
    private val onSnapshot: (GroupSessionSnapshot) -> Unit,
    private val onAudioLabel: (String) -> Unit,
    private val onReleased: () -> Unit,
    networkFactory: ((() -> GroupSessionSnapshot, (GroupSessionEvent) -> Unit) -> GroupNetworkEffects)? = null,
    private val audioFactory: ((() -> GroupSessionSnapshot, (GroupSessionEvent) -> Unit, () -> Unit) -> GroupAudioEffects)? = null,
    private val acquireKeepAlive: () -> Closeable = { IntercomRuntimeKeepAlive.acquire(context) },
    busyPorts: List<Int> = listOf(8888, 8890)
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var stopping = false
    private var audioReleased = true
    private var networkReleased = false
    private var released = false
    private var ownership: Any? = null
    private var audio: GroupAudioEffects? = null
    private var keepAlive: Closeable? = null
    private val busy = GroupLegacyBusyServer(endpoint, nickname, busyPorts)
    val writer = GroupSessionOrchestrator(endpoint, nickname, ::groupNowMs,
        { check(Looper.myLooper() == Looper.getMainLooper()) }, ::execute)
    private val network = networkFactory?.invoke({ writer.snapshot }, writer::dispatch)
        ?: GroupNetworkRuntime(context, endpoint, { writer.snapshot }, writer::dispatch)
    val snapshot get() = writer.snapshot
    private val tick = object : Runnable {
        override fun run() {
            if (!stopping && snapshot.operation != null) {
                writer.dispatch(GroupSessionEvent.Tick); audio?.poll()
                main.postDelayed(this, 1_000)
            }
        }
    }
    fun start(code: GroupJoinCode?) {
        try {
            ownership = checkNotNull(GroupRuntimeOwnership.acquire())
            keepAlive = acquireKeepAlive(); busy.start()
            writer.dispatch(if (code == null) GroupSessionEvent.Create else GroupSessionEvent.Search(code))
            main.post(tick)
        } catch (_: Exception) {
            stop(false)
            throw IllegalStateException("无法启动群组，请确认双人模式已结束")
        }
    }
    fun route(value: AudioRouteSelection) { initialRoute = value; audio?.selectRoute(value) }
    fun vox(value: Boolean) { initialControls = initialControls.copy(voxEnabled = value); audio?.vox(value) }
    private fun audio(): GroupAudioEffects = audio ?: run {
        val disposed = { audioReleased = true; releaseIfDone() }
        (audioFactory?.invoke({ snapshot }, writer::dispatch, disposed) ?: GroupAudio(context, { snapshot }, writer::dispatch,
            { !stopping && snapshot.operation != null }, initialRoute,
            initialControls.copy(muted = snapshot.participation.selfMuted), onAudioLabel, disposed))
            .also { audio = it; audioReleased = false }
    }
    private fun execute(effect: GroupSessionEffect) {
        when (effect) {
            is GroupSessionEffect.StartHost -> network.host(effect.operation, effect.descriptor, effect.code)
            is GroupSessionEffect.RecoverHost -> network.host(effect.operation, effect.descriptor, effect.code)
            is GroupSessionEffect.Search -> network.search(effect)
            is GroupSessionEffect.JoinNetwork -> network.join(effect)
            is GroupSessionEffect.ConnectControl -> network.connect(effect)
            is GroupSessionEffect.Send -> network.send(effect)
            is GroupSessionEffect.CloseChannel -> network.closeChannel(effect.channel)
            is GroupSessionEffect.AdmitChannel -> network.admit(effect.channel)
            is GroupSessionEffect.OpenMedia -> audio().open(effect.lease, effect.offerer)
            is GroupSessionEffect.CloseMedia -> audio?.close(effect.lease)
            is GroupSessionEffect.MediaSignal -> audio?.signal(effect.lease, effect.message)
            is GroupSessionEffect.Mute -> audio?.mute(effect.value)
            is GroupSessionEffect.Block -> audio?.block(effect.peerId, effect.value)
            is GroupSessionEffect.Publish -> onSnapshot(effect.snapshot)
            is GroupSessionEffect.Stop -> stop(effect.flushTerminal)
        }
    }
    private fun stop(flush: Boolean) {
        if (stopping) return
        stopping = true; main.removeCallbacksAndMessages(null)
        runCatching { audio?.close() }; audio = null
        busy.close()
        network.stop(flush) { networkReleased = true; releaseIfDone() }
    }
    private fun releaseIfDone() {
        if (stopping && audioReleased && networkReleased && !released) {
            released = true; runCatching { keepAlive?.close() }; keepAlive = null
            ownership?.let(GroupRuntimeOwnership::release); ownership = null
            onReleased()
        }
    }
    override fun close() {
        if (snapshot.operation != null) writer.dispatch(GroupSessionEvent.Leave) else stop(false)
    }
}
