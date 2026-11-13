package com.kuma.motointercom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.util.UUID
import kotlin.coroutines.resume

internal const val CLOUD_SHADOW_CAPABILITY="CLOUD_SHADOW_V1"

// Every frame is signed, including SDP/ICE and switch messages. A bare V2 HELLO
// pins names but is not proof that the peer holds the registered pairing key.
internal class ShadowFrameCodec(private val binding: String,private val signer: DeviceSigner,
    private val peerKey: String,private val first: Boolean) {
    val nonce=UUID.randomUUID().toString()
    private var peerNonce: String?=null
    private var sent=0L; private var received=0L
    @Volatile var authenticated=false; private set
    val probeId: String? get()=peerNonce?.let {
        CloudCrypto.sha256((binding+"\n"+(if(first) "$nonce\n$it" else "$it\n$nonce")).toByteArray())
    }
    fun encode(kind: String,data: String=""): String {
        require(kind in kinds && data.toByteArray().size<=20_000)
        require(kind=="HELLO" || probeId!=null)
        require(kind in setOf("HELLO","AUTH") || authenticated)
        val body=JsonObject().apply {
            addProperty("binding",binding);addProperty("channel",if(kind=="HELLO") "" else probeId)
            addProperty("sequence",sent++);addProperty("kind",kind);addProperty("data",if(kind=="HELLO") nonce else data)
        }
        return JsonObject().apply {add("body",body);addProperty("signature",signer.sign(body.toString().toByteArray()))}.toString()
    }
    fun receive(text: String): Pair<String,String> {
        require(text.toByteArray().size<=24_000)
        val root=JsonParser.parseString(text).asJsonObject
        require(root.keySet()==setOf("body","signature"))
        val body=root.getAsJsonObject("body")
        require(body.keySet()==setOf("binding","channel","sequence","kind","data"))
        require(CloudCrypto.verify(peerKey,body.toString().toByteArray(),root["signature"].asString))
        require(body["binding"].asString==binding && body["sequence"].asLong==received)
        val kind=body["kind"].asString;val data=body["data"].asString
        require(kind in kinds && data.toByteArray().size<=20_000)
        when(kind) {
            "HELLO" -> {require(received==0L && peerNonce==null && body["channel"].asString.isEmpty());UUID.fromString(data);peerNonce=data}
            "AUTH" -> {require(peerNonce!=null && !authenticated && body["channel"].asString==probeId);authenticated=true}
            else -> require(authenticated && body["channel"].asString==probeId)
        }
        received++;return kind to data
    }
    companion object {
        private val kinds=setOf("HELLO","AUTH","SDP","CANDIDATE","PING","PONG","READY","GO","ACTIVE","ABORT")
    }
}

internal class ShadowChannel(private val session: SignalingSessionV2,ride: RideSession,signer: DeviceSigner,proof: PairingProof,
    private val onAuthenticated: (String)->Unit,private val onMessage: (String,String)->Unit,private val onFailure: ()->Unit) : Closeable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val incoming=Channel<String>(64)
    private val sendLock=Mutex()
    private val local=session.pinnedIdentity.localDeviceId.value
    private val remote=session.pinnedIdentity.remoteDeviceId.value
    private val first=local<remote
    private val codec: ShadowFrameCodec
    @Volatile private var closed=false
    val probeId get()=codec.probeId?.takeIf {codec.authenticated}
    init {
        require(ride.members.toSet()==setOf(local,remote) && proof.transcript.pairId==ride.pairId && proof.verified())
        val pin=session.pinnedIdentity
        val members=if(first) "$local\n${pin.localSessionId.value}\n$remote\n${pin.remoteSessionId.value}"
            else "$remote\n${pin.remoteSessionId.value}\n$local\n${pin.localSessionId.value}"
        val binding=CloudCrypto.sha256(("MOTOCOM_SHADOW_V1\n${ride.rideId}\n${ride.pairId}\n$members\n${session.wireRequestKey.attemptId.value}\n${session.channel.transport}").toByteArray())
        val key=if(proof.transcript.deviceIdA==remote) proof.transcript.publicKeyA else proof.transcript.publicKeyB
        codec=ShadowFrameCodec(binding,signer,key,first)
    }
    fun start() {
        val authDeadline=System.nanoTime()+3_000_000_000L
        scope.launch {delay(((authDeadline-System.nanoTime())/1_000_000).coerceAtLeast(0));if(!codec.authenticated)fail()}
        session.startReader({ envelope ->
            val frame=envelope.message as? SignalingMessageV2.CloudShadow
            if(frame==null || !incoming.trySend(frame.frame).isSuccess) fail()
        },{fail()})
        scope.launch {
            try {
                sendNow("HELLO","")
                for(frame in incoming) {
                    val (kind,data)=codec.receive(frame)
                    when(kind) {
                        "HELLO" -> sendNow("AUTH","")
                        "AUTH" -> withContext(Dispatchers.Main.immediate) {if(!closed) onAuthenticated(codec.probeId!!)}
                        else -> withContext(Dispatchers.Main.immediate) {if(!closed) onMessage(kind,data)}
                    }
                }
            } catch(c: CancellationException) {throw c} catch(_: Exception) {fail()}
        }
    }
    fun send(kind: String,data: String="",onSent: (Boolean)->Unit = {}) {scope.launch {
        try {sendNow(kind,data);withContext(Dispatchers.Main.immediate) {if(!closed)onSent(true)}}
        catch(c: CancellationException){throw c} catch(_: Exception){withContext(Dispatchers.Main.immediate) {onSent(false)};fail()}
    }}
    private suspend fun sendNow(kind: String,data: String)=sendLock.withLock {
        val text=codec.encode(kind,data)
        suspendCancellableCoroutine<Unit> { continuation ->
            session.send(SignalingMessageV2.CloudShadow(text)) {result ->
                if(continuation.isActive) continuation.resumeWith(result)
            }
        }
    }
    private fun fail() {if(closed)return;scope.launch(Dispatchers.Main.immediate) {try {if(!closed)onFailure()} finally {close()}}}
    override fun close() {if(closed)return;closed=true;incoming.close();session.close();scope.cancel()}
}
