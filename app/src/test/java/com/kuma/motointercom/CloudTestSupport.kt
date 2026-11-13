package com.kuma.motointercom

import android.os.Looper
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

internal fun awaitCoroutineCompletionWithMainLooper(
    job: Job?,
    message: String,
    timeoutMs: Long = 5_000L,
) {
    val completed = CountDownLatch(1)
    job?.invokeOnCompletion { completed.countDown() } ?: completed.countDown()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (!completed.await(20L, TimeUnit.MILLISECONDS)) {
        if (System.nanoTime() >= deadline) fail(message)
        shadowOf(Looper.getMainLooper()).idle()
    }
}

internal class TestDeviceSigner : DeviceSigner {
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(key.public.encoded)
    override fun sign(bytes: ByteArray): String = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private); update(bytes); sign()
    })
}

internal fun testPair(a: DeviceSigner = TestDeviceSigner(), b: DeviceSigner = TestDeviceSigner(), now: Long = System.currentTimeMillis()): PairingProof {
    val t = PairingTranscript(pairId="pair_test",deviceIdA="a",deviceIdB="b",publicKeyA=a.publicKeyBase64(),
        publicKeyB=b.publicKeyBase64(),createdAt=now,nonce=UUID.randomUUID().toString(),expiresAt=now+300_000)
    return PairingProof(t,a.sign(t.bytes()),b.sign(t.bytes()))
}

internal fun testRide(now: Long, state: RideState = RideState.LOCAL_PENDING) = RideSession("ride_test","pair_test",listOf("a","b"),state,
    "123456789",1,false,if(state in setOf(RideState.CLOUD_PENDING,RideState.CLOUD_ACTIVE)) CloudEntryReason.INVITE_ACCEPTED else null,
    mapOf("a" to now,"b" to now),cloudStartedAt=if(state in setOf(RideState.CLOUD_PENDING,RideState.CLOUD_ACTIVE)) now else null)

internal class MemoryCloudStore(initial: List<CloudPairRecord> = emptyList()) : CloudSessionStore {
    val records = initial.associateBy { it.peerDeviceId }.toMutableMap()
    private val epochs = mutableMapOf<String,Long>()
    val ends = mutableSetOf<String>()
    val revoked = mutableSetOf<String>()
    private val starts = mutableMapOf<String,String>()
    private val cancelled = mutableMapOf<String,String>()
    private var cached: RideSession? = null
    override suspend fun pairs() = records.values.toList()
    override suspend fun epoch(peer: String) = epochs[peer] ?: 0L
    override suspend fun putProof(peer: String, proof: PairingProof, expectedEpoch: Long): Boolean {
        if(epoch(peer)!=expectedEpoch || !proof.verified()) return false
        records[peer]=CloudPairRecord(peer,proof,false); return true
    }
    override suspend fun noteProposal(peer: String,pairId: String,expectedEpoch: Long) = epoch(peer)==expectedEpoch
    override suspend fun markRegistered(peer: String,pairId: String) { records[peer]?.takeIf { it.proof.transcript.pairId==pairId }?.let { records[peer]=it.copy(registered=true) } }
    override suspend fun forget(peer: String) { records.remove(peer)?.let { revoked.add(it.proof.transcript.pairId) }; epochs[peer]=epoch(peer)+1 }
    override suspend fun forgetIfCurrent(peer: String,pairId: String,expectedEpoch: Long): Boolean {
        if(records[peer]?.proof?.transcript?.pairId!=pairId || epoch(peer)!=expectedEpoch) return false
        forget(peer); return true
    }
    override suspend fun revocations() = revoked.toSet()
    override suspend fun completeRevocation(pairId: String) { revoked.remove(pairId) }
    override suspend fun savedRide() = cached
    override suspend fun saveRide(ride: RideSession?) { cached=ride }
    override suspend fun clearRide(rideId: String) { if(cached?.rideId==rideId) cached=null }
    override suspend fun queueEnd(rideId: String) { ends.add(rideId); if(cached?.rideId==rideId) cached=null }
    override suspend fun pendingEnds() = ends.toSet()
    override suspend fun completeEnd(rideId: String) { ends.remove(rideId) }
    override suspend fun startRequest(pairId: String) = starts.getOrPut(pairId) { UUID.randomUUID().toString() }
    override suspend fun completeStart(pairId: String,requestId: String?) { if(requestId==null || starts[pairId]==requestId) starts.remove(pairId) }
    override suspend fun cancelStarts() { starts.forEach { (p,id)->cancelled[id]=p }; starts.clear() }
    override suspend fun cancelledStarts() = cancelled.toMap()
    override suspend fun completeCancelledStart(requestId: String) { cancelled.remove(requestId) }
}
