package com.kuma.motointercom.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.*
import java.io.Closeable

internal interface GroupRuntimeHandle : Closeable {
    val snapshot: GroupSessionSnapshot
    fun start(code: GroupJoinCode?)
    fun dispatch(event: GroupSessionEvent)
    fun route(value: AudioRouteSelection)
    fun vox(value: Boolean)
}

internal class GroupAudioLifetime(
    private val release: () -> Unit,
    private val fail: (Throwable) -> Unit = {}
) {
    constructor(release: () -> Unit) : this(release, {})
    private var acquired = false
    private var released = false
    fun acquired() { check(!released); acquired = true }
    fun disposed() {
        if (released) return
        released = true
        release()
    }
    fun disposeFailed(error: Throwable) {
        if (released) return
        released = true
        fail(error)
    }
    fun constructionFailed() {
        if (!acquired) disposed()
    }
}

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
    private val audioFactory: ((() -> GroupSessionSnapshot, (GroupSessionEvent) -> Unit, GroupAudioLifetime) -> GroupAudioEffects)? = null,
    private val acquireKeepAlive: () -> Closeable = { IntercomRuntimeKeepAlive.acquire(context) },
    busyPorts: List<Int> = listOf(8888, 8890)
) : GroupRuntimeHandle {
    private val main = Handler(Looper.getMainLooper())
    private var stopping = false
    private val audioLeases = mutableSetOf<Any>()
    private var audioDisposalFailed = false
    private var networkReleased = false
    private var released = false
    private var ownership: Any? = null
    private var audio: GroupAudioEffects? = null
    private var keepAlive: Closeable? = null
    private val busy = GroupLegacyBusyServer(endpoint, nickname, busyPorts)
    val writer = GroupSessionOrchestrator(endpoint, nickname, ::groupNowMs,
        { check(Looper.myLooper() == Looper.getMainLooper()) }, ::execute, GroupDiagnostics())
    private val network = networkFactory?.invoke({ writer.snapshot }, writer::dispatch)
        ?: GroupNetworkRuntime(context, endpoint, { writer.snapshot }, writer::dispatch)
    override val snapshot get() = writer.snapshot
    private val tick = object : Runnable {
        override fun run() {
            if (!stopping && snapshot.operation != null) {
                writer.dispatch(GroupSessionEvent.Tick); audio?.poll()
                main.postDelayed(this, 1_000)
            }
        }
    }
    override fun start(code: GroupJoinCode?) {
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
    override fun dispatch(event: GroupSessionEvent) = writer.dispatch(event)
    override fun route(value: AudioRouteSelection) { initialRoute = value; audio?.selectRoute(value) }
    override fun vox(value: Boolean) { initialControls = initialControls.copy(voxEnabled = value); audio?.vox(value) }
    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    private fun audio(): GroupAudioEffects = audio ?: run {
        check(!audioDisposalFailed) { "音频平台释放失败，请重启应用" }
        check(audioLeases.isEmpty()) { "正在释放旧音频平台，请稍后重试" }
        val token = Any()
        audioLeases += token
        val lifetime = GroupAudioLifetime(
            release = {
                runOnMain {
                    audioLeases.remove(token)
                    releaseIfDone()
                }
            },
            fail = {
                runOnMain {
                    audioDisposalFailed = true
                    snapshot.operation?.takeUnless { stopping }?.let { operation ->
                        writer.dispatch(GroupSessionEvent.Failed(operation, "音频资源释放失败，请重启应用"))
                    }
                    releaseIfDone()
                }
            }
        )
        try {
            (audioFactory?.invoke({ snapshot }, writer::dispatch, lifetime) ?: GroupAudio(context, { snapshot }, writer::dispatch,
                { !stopping && snapshot.operation != null }, initialRoute,
                initialControls.copy(muted = snapshot.participation.selfMuted), onAudioLabel, lifetime))
                .also { lifetime.acquired(); audio = it }
        } catch (error: Throwable) {
            lifetime.constructionFailed()
            throw error
        }
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
        stopping = true; main.removeCallbacks(tick)
        if (runCatching { audio?.close() }.isFailure) audioDisposalFailed = true
        audio = null
        busy.close()
        network.stop(flush) { networkReleased = true; releaseIfDone() }
    }
    private fun releaseIfDone() {
        if (stopping && (audioLeases.isEmpty() || audioDisposalFailed) && networkReleased && !released) {
            released = true; runCatching { keepAlive?.close() }; keepAlive = null
            if (!audioDisposalFailed) {
                ownership?.let(GroupRuntimeOwnership::release); ownership = null
            }
            onReleased()
        }
    }
    override fun close() {
        if (snapshot.operation != null) writer.dispatch(GroupSessionEvent.Leave) else stop(false)
    }
}
