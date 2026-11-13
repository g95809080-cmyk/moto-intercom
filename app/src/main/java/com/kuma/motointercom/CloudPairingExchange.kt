package com.kuma.motointercom

import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.UUID

internal const val CLOUD_PAIRING_CAPABILITY = "CLOUD_PAIRING_V1"
internal data class CloudPairingPrompt(val pairId: String, val peerDeviceId: String, val peerName: String, val actionNonce: String, val expiresAt: Long)

// One selected, pinned local channel owns this exchange. Wire signatures are
// reconstructed from both challenges; no remote-supplied transcript is signed.
internal class CloudPairingExchange(
    private val scope: CoroutineScope,
    private val identity: PinnedChannelIdentity,
    private val signer: DeviceSigner,
    private val store: CloudSessionStore,
    private val canContinue: () -> Boolean,
    private val authorize: suspend () -> Long?,
    private val send: suspend (SignalingMessageV2) -> Unit,
    private val requestApproval: (String, Long) -> Unit,
    private val completed: suspend (PairingProof, Long) -> Unit,
    private val failed: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val autoAuthorize: Boolean = false
) : Closeable {
    private val mutex = Mutex()
    private var own: SignalingMessageV2.CloudPairingOffer? = null
    private var remote: SignalingMessageV2.CloudPairingOffer? = null
    private var localSignature: String? = null
    private var remoteSignature: String? = null
    private var pendingProof: SignalingMessageV2.CloudPairingProof? = null
    private var grantEpoch: Long? = null
    private var finished = false
    private var closed = false
    private var timeout: Job? = null
    private val jobs = mutableSetOf<Job>()

    fun initiate() = launch {
        if (finished || own != null || !live()) return@launch
        grantEpoch = authorize() ?: return@launch
        val pairId = UUID.randomUUID().toString(); val created = now()
        own = makeOffer(pairId,created,created+300_000)
        armTimeout(own!!.expiresAt); send(own!!)
    }

    fun receive(message: SignalingMessageV2) = launch {
        if (!live() || finished) return@launch
        when (message) {
            is SignalingMessageV2.CloudPairingOffer -> offer(message)
            is SignalingMessageV2.CloudPairingProof -> {
                if (own == null || remote == null || grantEpoch == null) pendingProof = message
                else proof(message)
            }
            else -> Unit
        }
    }

    fun approve(pairId: String) = launch {
        val offer = remote ?: return@launch
        if (offer.pairId != pairId || offer.expiresAt <= now() || !live() || finished) return@launch
        grantEpoch = authorize() ?: return@launch
        respondAndSign(offer)
    }

    private suspend fun offer(message: SignalingMessageV2.CloudPairingOffer) {
        require(message.createdAt <= now()+60_000 && message.expiresAt > now() && message.expiresAt > message.createdAt && message.expiresAt-message.createdAt <= 300_000)
        withContext(Dispatchers.IO) { CloudCrypto.publicKey(message.publicKey) }
        val current = own
        if (current != null && current.pairId != message.pairId) {
            if (current.pairId < message.pairId) return // Deterministic simultaneous-request resolution.
            own = null; localSignature = null; remoteSignature = null; remote = null; pendingProof = null
        }
        if (remote != null) { require(remote == message); return }
        if (own != null) require(own!!.createdAt == message.createdAt && own!!.expiresAt == message.expiresAt)
        remote = message; armTimeout(message.expiresAt)
        if (grantEpoch != null || autoAuthorize) {
            if (grantEpoch == null) grantEpoch = authorize()
            if (grantEpoch != null) respondAndSign(message)
        } else requestApproval(message.pairId,message.expiresAt)
    }

    private suspend fun makeOffer(pairId: String, created: Long, expires: Long): SignalingMessageV2.CloudPairingOffer {
        val key = withContext(Dispatchers.IO) { signer.publicKeyBase64() }
        check(live())
        val epoch = grantEpoch ?: error("No local pairing authorization")
        check(store.noteProposal(identity.remoteDeviceId.value,pairId,epoch))
        check(live())
        return SignalingMessageV2.CloudPairingOffer(pairId,key,UUID.randomUUID().toString(),created,expires)
    }

    private suspend fun respondAndSign(offer: SignalingMessageV2.CloudPairingOffer) {
        if (own == null) { own = makeOffer(offer.pairId,offer.createdAt,offer.expiresAt); send(own!!) }
        val t = transcript()
        check(t.validAt(now()) && live())
        if (localSignature == null) {
            localSignature = withContext(Dispatchers.IO) { signer.sign(t.bytes()) }
            check(live())
            send(SignalingMessageV2.CloudPairingProof(t,localSignature!!))
        }
        pendingProof?.let { pendingProof = null; proof(it) }
        finishIfReady()
    }

    private fun transcript(): PairingTranscript {
        val a = own ?: error("Missing local offer"); val b = remote ?: error("Missing remote offer")
        require(a.pairId == b.pairId && a.createdAt == b.createdAt && a.expiresAt == b.expiresAt)
        val localFirst = identity.localDeviceId.value < identity.remoteDeviceId.value
        val first = if (localFirst) a else b; val second = if (localFirst) b else a
        val key = identity.wireRequestKey
        val responderSession = if (key.requesterDeviceId == identity.localDeviceId) identity.remoteSessionId else identity.localSessionId
        val binding = JsonObject().apply {
            addProperty("domain","motocom-cloud-pairing-v1")
            addProperty("requester",key.requesterDeviceId.value); addProperty("requesterSession",key.requesterSessionId.value)
            addProperty("responder",key.responderDeviceId.value); addProperty("responderSession",responderSession.value)
            addProperty("attempt",key.attemptId.value); addProperty("pairId",a.pairId)
            addProperty("challengeA",first.challenge); addProperty("challengeB",second.challenge)
        }
        return PairingTranscript(pairId=a.pairId,deviceIdA=if(localFirst)identity.localDeviceId.value else identity.remoteDeviceId.value,
            deviceIdB=if(localFirst)identity.remoteDeviceId.value else identity.localDeviceId.value,
            publicKeyA=first.publicKey,publicKeyB=second.publicKey,createdAt=a.createdAt,
            nonce=CloudCrypto.sha256(binding.toString().toByteArray(Charsets.UTF_8)),expiresAt=a.expiresAt)
    }

    private suspend fun proof(message: SignalingMessageV2.CloudPairingProof) {
        val t = transcript()
        require(message.transcript == t && t.validAt(now()))
        require(withContext(Dispatchers.IO) { CloudCrypto.verify(remote!!.publicKey,t.bytes(),message.signature) })
        if (remoteSignature != null) require(remoteSignature == message.signature)
        remoteSignature = message.signature; finishIfReady()
    }

    private suspend fun finishIfReady() {
        val ours = localSignature ?: return; val theirs = remoteSignature ?: return
        if (!live() || finished) return
        val t = transcript(); val first = t.deviceIdA == identity.localDeviceId.value
        val proof = PairingProof(t,if(first)ours else theirs,if(first)theirs else ours)
        require(withContext(Dispatchers.IO) { proof.verified() })
        if (!live()) return
        completed(proof,grantEpoch ?: return); finished = true; timeout?.cancel()
    }

    private fun launch(block: suspend () -> Unit) {
        if (closed) return
        val job = scope.launch {
            try { mutex.withLock { if (live()) block() } }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { if (live()) failed(); close() }
        }
        jobs.add(job); job.invokeOnCompletion { jobs.remove(job) }
    }
    private fun armTimeout(expiresAt: Long) {
        timeout?.cancel(); timeout = scope.launch { delay((expiresAt-now()).coerceAtLeast(0)); if (!finished && live()) failed(); close() }
    }
    private fun live(): Boolean = !closed && canContinue()
    override fun close() { closed = true; timeout?.cancel(); jobs.toList().forEach { it.cancel() }; jobs.clear() }
}
