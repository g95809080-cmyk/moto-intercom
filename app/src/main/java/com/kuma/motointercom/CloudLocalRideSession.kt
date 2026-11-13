package com.kuma.motointercom

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.webrtc.PeerConnection
import java.io.Closeable
import kotlin.coroutines.resume

internal data class LocalRideAccess(val api: CloudControlPlane,val signer: DeviceSigner,val proof: PairingProof,
    val current: ()->Boolean,val publish: suspend (RideSession)->Unit,val lost: suspend ()->Unit)

internal interface LocalRideAudioHost {
    val localSelected: Boolean
    fun canSwitch(): Boolean
    fun bind(engine: RiderMediaEngine)
    suspend fun activateLocal(budgetMs: Long): Boolean
    fun released(engine: RiderMediaEngine,success: Boolean)
    suspend fun resumeCloud(): Boolean
    fun committed(transport: Transport)
    fun changed()
    fun level(level: Float)
    fun vox(controls: VersionedAudioControls,state: VoxRuntimeState)
}

// A ride's authenticated local shadow and prepared WebRTC connection. Discovery
// does not enter the normal local runtime or acquire a second audio/focus owner.
internal class CloudLocalRideSession(private val context: Context,initial: RideSession,private val access: LocalRideAccess,
    private val controls: ()->VersionedAudioControls,private val host: LocalRideAudioHost) : Closeable {
    val rideId=initial.rideId
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val released=ResourceReleaseBarrier()
    private val nativeReleases=linkedMapOf<RiderAudioEngine,ResourceReleaseBarrier>()
    private val probeReleases=linkedMapOf<LocalShadowProbe,ResourceReleaseBarrier>()
    private var ride=initial
    private var probe: LocalShadowProbe?=null
    private var wire: ShadowChannel?=null
    private var engine: RiderAudioEngine?=null
    private var media: RiderMediaSession?=null
    private var transport=Transport.LAN
    private var mediaConnected=false
    private var lastPong=0L
    private var ping=0L
    private var pendingPing=0L
    private var nextPing=0L
    private var lastReportReady=false
    private var readySent: String?=null
    private var peerReady: String?=null
    private var goSent: String?=null
    private var switched: String?=null
    private var trial=false
    var committed=false;private set
    var ownsLocalMedia=false;private set
    var audioReady=false;private set
    private var closed=false
    private var recovering=false
    private var transition: Job?=null
    private var readiness: Job?=null
    private val leader=access.api.deviceId<initial.members.single {it!=access.api.deviceId}
    fun start() {
        openProbe()
        scope.launch {
            while(isActive && !closed) {
                if(!access.current()) {close();return@launch}
                try {tick()} catch(c: CancellationException){throw c} catch(_: Exception) {
                    if(trial && !committed) abort()
                }
                delay(500)
            }
        }
    }
    fun update(value: RideSession) {
        if(closed || value.rideId!=rideId || value.revision<ride.revision)return
        ride=value
        val s=value.localSwitch
        if(value.state==RideState.ENDED) {close();return}
        if(value.state==RideState.LOCAL_ACTIVE && s?.phase=="COMMITTED" && switched==s.switchId && audioReady) {
            committed=true;trial=false;host.committed(transport);host.changed()
        } else if(s?.phase=="FAILED" && (trial || host.localSelected) && !recovering) abort()
        else if(value.state==RideState.SWITCHING_LOCAL && s?.phase=="ARMED" && validProbe(s.probeId) && host.canSwitch()) {
            if(readySent!=s.switchId) {readySent=s.switchId;wire?.send("READY",s.switchId)}
            maybeGo()
        }
    }
    private fun openProbe() {
        if(closed || probe!=null)return
        probe=LocalShadowProbe(context,access.api.deviceId,ride.members.single {it!=access.api.deviceId},::selected,::failed,
            keepSelectedP2p={committed && host.localSelected}).also {
            probeReleases[it]=ResourceReleaseBarrier();it.start()
        }
    }
    private fun selected(session: SignalingSessionV2) {
        if(closed || !access.current()) {session.close();return}
        transport=session.channel.transport
        wire=ShadowChannel(session,ride,access.signer,access.proof,onAuthenticated={createMedia(session.requestRole)},
            onMessage=::message,onFailure=::failed).also {it.start()}
    }
    private fun createMedia(role: RequestRole) {
        if(closed || !access.current() || engine!=null)return
        val created=RiderAudioEngine(context,onEngineError={failed()},isRuntimeCurrent={!closed && access.current()},
            initialAudioControls=controls(),onVoxStateChanged={c,s->if(host.localSelected)host.vox(c,s)},initialAudioIoEnabled=false,opusDtxEnabled=false)
        engine=created;host.bind(created)
        val release=ResourceReleaseBarrier();nativeReleases[created]=release
        created.whenReleased {ok ->handler.post {host.released(created,ok);release.complete(ok)}}
        media=created.openSession(RiderMediaSessionCallbacks(
            onLocalSdpGenerated={wire?.send("SDP",it)},onLocalIceCandidateGenerated={wire?.send("CANDIDATE",it)},
            onConnectionStateChanged={state ->
                mediaConnected=state==PeerConnection.PeerConnectionState.CONNECTED
                if(!mediaConnected && state in setOf(PeerConnection.PeerConnectionState.DISCONNECTED,PeerConnection.PeerConnectionState.FAILED,PeerConnection.PeerConnectionState.CLOSED)) failed()
            },onAudioLevelChanged={if(host.localSelected)host.level(it)},onError={failed()},isSessionCurrent={!closed && engine===created && access.current()}))
        if(role==RequestRole.REQUESTER)media?.createOffer()
    }
    private fun message(kind: String,data: String) {
        if(closed || !access.current())return
        when(kind) {
            "SDP" -> when(JsonParser.parseString(data).asJsonObject["type"].asString.lowercase()) {
                "offer"->media?.createAnswer(data);"answer"->media?.setRemoteAnswer(data);else->failed()
            }
            "CANDIDATE" -> media?.addRemoteIceCandidate(data)
            "PING" -> wire?.send("PONG",data)
            "PONG" -> if(data.toLongOrNull()==pendingPing && SystemClock.elapsedRealtime()-pendingPing in 0..1_000) lastPong=SystemClock.elapsedRealtime()
            "READY" -> if(ride.localSwitch?.let {it.switchId==data && it.phase in setOf("PREPARING","ARMED") && it.probeId==wire?.probeId}==true) {peerReady=data;maybeGo()}
            "GO" -> if(!leader && ride.localSwitch?.let {it.switchId==data && it.phase=="ARMED"}==true && readySent==data) switch(data)
            "ACTIVE" -> Unit // Actual RTP, not this acknowledgement, is activation evidence.
            "ABORT" -> if(ride.localSwitch?.switchId==data)abort()
        }
    }
    private fun validProbe(id: String)=wire?.probeId==id && mediaConnected && lastPong>0 && SystemClock.elapsedRealtime()-lastPong<=1_500
    private suspend fun tick() {
        if(recovering)return
        if(ride.state==RideState.CLOUD_ACTIVE && ride.localSwitchAttempts>=3) {close();return}
        val channel=wire
        val now=SystemClock.elapsedRealtime()
        if(channel?.probeId!=null && now>=nextPing) {
            pendingPing=now;nextPing=now+500;channel.send("PING",now.toString())
        }
        if(committed) {
            if(!mediaConnected || lastPong>0 && now-lastPong>1_500) failed()
            return
        }
        val id=channel?.probeId ?: return
        if(trial) {
            val s=ride.localSwitch
            if(s==null || access.api.serverTimeForSwitch()>s.expiresAt) abort()
            return
        }
        if(now-ping<1_000)return
        ping=now
        val ready=validProbe(id) && host.canSwitch()
        if(!ready && !lastReportReady && ride.state==RideState.CLOUD_ACTIVE)return
        if(ride.state in setOf(RideState.CLOUD_ACTIVE,RideState.SWITCHING_LOCAL)) {
            val updated=access.api.localProbe(rideId,id,ready)
            lastReportReady=ready
            if(!access.current() || closed)return
            access.publish(updated)
            val s=updated.localSwitch
            if(updated.state==RideState.SWITCHING_LOCAL && s?.probeId==id && s.phase=="PREPARING" && ready && access.api.deviceId !in s.prepared) {
                access.publish(access.api.localSwitch(rideId,s.switchId,id,"PREPARED"))
            }
        }
    }
    private fun maybeGo() {
        val s=ride.localSwitch ?: return
        if(!leader || s.phase!="ARMED" || readySent!=s.switchId || peerReady!=s.switchId || goSent==s.switchId || !validProbe(s.probeId))return
        goSent=s.switchId
        wire?.send("GO",s.switchId) {if(it)switch(s.switchId) else failed()}
    }
    private fun switch(id: String) {
        if(closed || switched==id || transition?.isActive==true || !host.canSwitch())return
        val s=ride.localSwitch?.takeIf {it.switchId==id && validProbe(it.probeId)} ?: return
        switched=id;trial=true;host.changed()
        transition=scope.launch {
            try {
            val deadline=SystemClock.elapsedRealtime()+300
            val activated=host.activateLocal((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))
            if(activated)ownsLocalMedia=true
            val ok=activated && evidenceBefore(deadline)
            if(!ok || !access.current() || closed) {if(!closed)abort();return@launch}
            audioReady=true;host.changed();wire?.send("ACTIVE",id)
            try {access.publish(access.api.localSwitch(rideId,id,s.probeId,"COMPLETE"))}
            catch(c: CancellationException){throw c} catch(_: Exception){abort()}
            } catch(c: CancellationException){throw c} catch(_: Exception){abort()}
        }
    }
    private suspend fun evidenceBefore(deadline: Long): Boolean {
        val fresh=LocalActivationEvidence()
        while(!closed && access.current() && SystemClock.elapsedRealtime()<deadline) {
            val evidence=withTimeoutOrNull((deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) {
                suspendCancellableCoroutine<RiderMediaEvidence?> {continuation ->
                    val session=media
                    if(session==null)continuation.resume(null) else session.queryEvidence {if(continuation.isActive)continuation.resume(it)}
                }
            }
            if(fresh.sample(evidence))return true
            delay(10)
        }
        return false
    }
    private fun failed() {if(!closed && !recovering)abort()}
    private fun abort() {
        if(closed || recovering)return
        recovering=true;audioReady=false;host.changed()
        val s=ride.localSwitch;val hadLocal=committed
        if(s!=null)wire?.send("ABORT",s.switchId)
        transition?.cancel();transition=null
        val old=probe;probe=null
        wire?.close();wire=null;mediaConnected=false;lastPong=0
        val network=CompletableDeferred<Boolean>()
        val networkRelease=old?.let {probeReleases[it]}
        old?.closeWhenReleased {ok ->networkRelease?.complete(ok);network.complete(ok)} ?: network.complete(true)
        scope.launch {
            val stopped=disposeMedia()
            if(!stopped || !network.await() || !access.current() || closed)return@launch
            trial=false;committed=false;readySent=null;peerReady=null;goSent=null;switched=null
            if(hadLocal) access.lost()
            else {
                host.resumeCloud()
                if(s!=null)runCatching {access.publish(access.api.localSwitch(rideId,s.switchId,s.probeId,"FAIL"))}
            }
            recovering=false
            delay(1_000)
            if(!closed && access.current())openProbe()
        }
    }
    private suspend fun disposeMedia(): Boolean {
        val old=engine ?: return true
        engine=null;runCatching {media?.close()};media=null
        val done=CompletableDeferred<Boolean>()
        runCatching {old.close()}.onFailure {done.complete(false)}
        nativeReleases[old]!!.whenReleased {done.complete(it)}
        return done.await()
    }
    fun updateControls(value: VersionedAudioControls) {engine?.updateAudioControls(value)}
    fun audioPolicyChanged(state: AudioInterruptionState) {
        if(closed)return
        if(state!=AudioInterruptionState.NORMAL) {audioReady=false;readiness?.cancel();host.changed()}
        else if(committed && host.localSelected) {
            readiness?.cancel()
            readiness=scope.launch {audioReady=evidenceBefore(SystemClock.elapsedRealtime()+3_000);host.changed()}
        }
    }
    override fun close() {
        if(closed)return
        closed=true;audioReady=false;transition?.cancel();wire?.close();wire=null
        probe=null;engine=null;media=null
        scope.cancel()
        nativeReleases.forEach {(engine,barrier)->runCatching {engine.close()}.onFailure {barrier.complete(false)}}
        probeReleases.forEach {(probe,barrier)->runCatching {probe.closeWhenReleased {barrier.complete(it)}}.onFailure {barrier.complete(false)}}
        val pending=nativeReleases.values.toList()+probeReleases.values.toList()
        CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
            try {
                var success=true
                for(barrier in pending) {
                    val done=CompletableDeferred<Boolean>();barrier.whenReleased {done.complete(it)}
                    success=done.await() && success
                }
                released.complete(success)
            } finally {cancel()}
        }
    }
    fun closeWhenReleased(callback: (Boolean)->Unit) {close();released.whenReleased(callback)}
}

private fun CloudControlPlane.serverTimeForSwitch(): Long = (this as? HttpCloudControlPlane)?.serverNow() ?: System.currentTimeMillis()
