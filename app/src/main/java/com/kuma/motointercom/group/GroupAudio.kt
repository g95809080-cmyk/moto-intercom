package com.kuma.motointercom.group

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.kuma.motointercom.*
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.io.Closeable

internal interface GroupAudioEffects : Closeable {
    fun open(lease: GroupMediaLease, offerer: Boolean)
    fun close(lease: GroupMediaLease)
    fun signal(lease: GroupMediaLease, message: GroupMessage)
    fun mute(value: Boolean)
    fun vox(value: Boolean)
    fun block(peer: String, value: Boolean)
    fun selectRoute(value: AudioRouteSelection)
    fun poll()
}
/** One shared platform; peer slots never own the coordinator, route or engine. Main-thread owner. */
internal class GroupAudio(
    context: Context,
    private val snapshot: () -> GroupSessionSnapshot,
    private val dispatch: (GroupSessionEvent) -> Unit,
    private val isCurrent: () -> Boolean,
    initialRoute: AudioRouteSelection,
    initialControls: AudioControlSettings,
    private val onRouteLabel: (String) -> Unit,
    private val onDisposed: () -> Unit
) : GroupAudioEffects {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var routeReady = false
    private var routeEpoch = 0L
    private var routeSelection = initialRoute
    private var controls = initialControls
    private var controlsRevision = 0L
    private val leases = mutableMapOf<String, GroupMediaLease>()
    private val previous = mutableMapOf<GroupMediaLease, RiderMediaEvidence>()
    private val engine = RiderAudioEngine(context, onEngineError = { post { unavailable() } },
        isRuntimeCurrent = { !closed && isCurrent() }, initialAudioControls = VersionedAudioControls(0, controls),
        mediaMode = RiderMediaMode.GROUP, onDisposed = onDisposed)
    private lateinit var coordinator: CommunicationAudioCoordinator
    private val route = try { AudioRouteController(context,
        onScoConnected = { onRouteLabel("蓝牙：$it") },
        onScoDisconnected = { unavailable(); coordinator.reapplyPreferredRoute() },
        onRouteInvalidated = {
            if (!closed && isCurrent()) { unavailable(); coordinator.reapplyPreferredRoute() }
        },
        onSpeakerFallback = { onRouteLabel("手机扬声器") },
        onEarpieceActive = { onRouteLabel("手机听筒") },
        onExternalAudioActive = { onRouteLabel(it) },
        onError = { unavailable() },
        onRouteReady = {
            if (!closed && isCurrent()) { routeReady = true; coordinator.onRouteReady() }
        }) } catch (error: Throwable) { engine.close(); throw error }
    private val media: GroupMediaController
    init {
        try {
        coordinator = CommunicationAudioCoordinator(engine, route, AndroidIntercomAudioFocus(context),
            AndroidIntercomPhoneState(context), activateRoute = {
                invalidateRoute(); route.select(routeSelection)
            }, onStateChanged = { state ->
                post { if (state != AudioInterruptionState.NORMAL) unavailable() }
            })
        media = GroupMediaController(engine, { !closed && isCurrent() && snapshot().allows(it) },
            { coordinator.beginMediaSession() }, { invalidateRoute(); coordinator.endMediaSession() })
        coordinator.start()
        } catch (error: Throwable) {
            runCatching { if (::coordinator.isInitialized) coordinator.close() }
            runCatching { route.close() }; engine.close()
            throw error
        }
    }
    private fun post(action: () -> Unit) { main.post { if (!closed && isCurrent()) action() } }
    private fun invalidateRoute() { routeReady = false; routeEpoch++; previous.clear() }
    private fun unavailable() {
        invalidateRoute(); engine.suspendAudio()
        dispatch(GroupSessionEvent.AudioAvailable(false))
    }
    override fun open(lease: GroupMediaLease, offerer: Boolean) {
        val accepted = media.open(lease, RiderMediaSessionCallbacks(
            onLocalSdpGenerated = { json ->
                val obj = JSONObject(json)
                val message = if (obj.getString("type").equals("offer", true)) GroupMessage.Offer(lease.link, obj.getString("sdp"))
                    else GroupMessage.Answer(lease.link, obj.getString("sdp"))
                dispatch(GroupSessionEvent.Outgoing(lease, message))
            }, onLocalIceCandidateGenerated = { json ->
                val obj = JSONObject(json)
                dispatch(GroupSessionEvent.Outgoing(lease, GroupMessage.Candidate(lease.link,
                    obj.getString("sdpMid"), obj.getInt("sdpMLineIndex"), obj.getString("candidate"))))
            }, onConnectionStateChanged = {
                if (it == PeerConnection.PeerConnectionState.FAILED || it == PeerConnection.PeerConnectionState.DISCONNECTED)
                    dispatch(GroupSessionEvent.MediaFailed(lease))
            }, onError = { dispatch(GroupSessionEvent.MediaFailed(lease)) },
            isSessionCurrent = { !closed && isCurrent() && snapshot().allows(lease) }))
        if (accepted) { leases[lease.peer.deviceId] = lease; if (offerer) media.offer(lease) }
    }
    override fun close(lease: GroupMediaLease) {
        if (leases[lease.peer.deviceId] == lease) leases.remove(lease.peer.deviceId)
        previous.remove(lease); media.close(lease)
    }
    override fun signal(lease: GroupMediaLease, message: GroupMessage) {
        fun sdp(type: String, value: String) = JSONObject().put("type", type).put("sdp", value).toString()
        when (message) {
            is GroupMessage.Offer -> media.answer(lease, sdp("offer", message.sdp))
            is GroupMessage.Answer -> media.remoteAnswer(lease, sdp("answer", message.sdp))
            is GroupMessage.Candidate -> media.candidate(lease, JSONObject().put("sdpMid", message.mid)
                .put("sdpMLineIndex", message.line).put("candidate", message.candidate).toString())
            else -> Unit
        }
    }
    override fun mute(value: Boolean) { controls = controls.copy(muted = value); updateControls() }
    override fun vox(value: Boolean) { controls = controls.copy(voxEnabled = value); updateControls() }
    private fun updateControls() { media.updateAudioControls(VersionedAudioControls(++controlsRevision, controls)) }
    override fun block(peer: String, value: Boolean) = media.block(peer, value)
    override fun selectRoute(value: AudioRouteSelection) {
        routeSelection = value; unavailable(); coordinator.reapplyPreferredRoute()
    }
    override fun poll() {
        val routeEvidence = route.evidence()
        val epoch = routeEpoch
        leases.values.toList().forEach { lease -> media.evidence(lease) { evidence ->
            if (closed || !isCurrent() || !snapshot().allows(lease) || epoch != routeEpoch || routeEvidence != route.evidence()) return@evidence
            val ready = routeEvidence.ready && routeReady && coordinator.currentState() == AudioInterruptionState.NORMAL
            if (evidence != null && (!evidence.audioIoEnabled || !ready)) {
                previous.remove(lease)
                dispatch(GroupSessionEvent.AudioAvailable(false)); return@evidence
            }
            if (evidence == null || !ready) return@evidence
            dispatch(GroupSessionEvent.AudioAvailable(true))
            if (!snapshot().allows(lease)) return@evidence
            val before = previous.put(lease, evidence) ?: return@evidence
            if (before.counters.streamIds != evidence.counters.streamIds || before.gateRevision != evidence.gateRevision ||
                before.nativeRevision != evidence.nativeRevision) return@evidence
            dispatch(GroupSessionEvent.Evidence(GroupVoiceEvidence(lease, evidence.connected, evidence.remoteTrack,
                evidence.audioIoEnabled, ready, before.counters.sent, before.counters.received, evidence.counters.sent,
                evidence.counters.received, groupNowMs())))
        } }
    }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null); leases.clear(); previous.clear()
        val phone = coordinator.isPhoneCallActive()
        runAllCleanupSteps(media::close, coordinator::close, engine::close, { route.closeRoute(!phone) })
    }
}
