package com.kuma.motointercom

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudPairingExchangeTest {
    private suspend fun until(condition: ()->Boolean) { withTimeout(5_000) { while(!condition()) delay(10) } }
    private class Link : AutoCloseable {
        val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val a=DeviceId.create(); val b=DeviceId.create()
        val sa=RuntimeSessionId(UUID.randomUUID().toString()); val sb=RuntimeSessionId(UUID.randomUUID().toString())
        val key=WireRequestKey(a,sa,ConnectionAttemptId(UUID.randomUUID().toString()),b)
        val ia=PinnedChannelIdentity(a,sa,b,sb,key); val ib=PinnedChannelIdentity(b,sb,a,sa,key)
        val proofA=AtomicReference<PairingProof>(); val proofB=AtomicReference<PairingProof>()
        val approval=AtomicReference<String>(); val failures=AtomicInteger(); val live=AtomicBoolean(true)
        val sentB=AtomicInteger()
        lateinit var ea: CloudPairingExchange
        lateinit var eb: CloudPairingExchange
        fun prepare(autoB: Boolean=false,tamper: Boolean=false,identityB: PinnedChannelIdentity=ib) {
            val signerA=TestDeviceSigner(); val signerB=TestDeviceSigner()
            ea=CloudPairingExchange(scope,ia,signerA,MemoryCloudStore(),live::get,{0L},
                { eb.receive(it) },{_,_->error("Initiator does not need extra consent")},{p,_->proofA.set(p)},{failures.incrementAndGet()})
            eb=CloudPairingExchange(scope,identityB,signerB,MemoryCloudStore(),live::get,{0L},
                { message ->
                    sentB.incrementAndGet()
                    ea.receive(if(tamper && message is SignalingMessageV2.CloudPairingProof)
                        message.copy(transcript=message.transcript.copy(nonce="changed_nonce_123456")) else message)
                },{id,_->approval.set(id)},{p,_->proofB.set(p)},{failures.incrementAndGet()},autoAuthorize=autoB)
        }
        override fun close() { runBlocking(dispatcher) { ea.close(); eb.close(); scope.cancel() }; dispatcher.close() }
    }
    @Test fun legacyPairingRequiresExplicitApprovalBeforeItEmitsAKeyOrSignature() = runBlocking {
        Link().use { link ->
            link.prepare(); withContext(link.dispatcher) { link.ea.initiate() }
            until { link.approval.get()!=null }
            assertEquals(0,link.sentB.get()); assertNull(link.proofB.get())
            withContext(link.dispatcher) { link.eb.approve(link.approval.get()) }
            until { link.proofA.get()!=null && link.proofB.get()!=null }
            assertEquals(link.proofA.get(),link.proofB.get()); assertTrue(link.proofA.get().verified())
            assertEquals(0,link.failures.get())
        }
    }
    @Test fun explicitlyAcceptedFreshLocalPairCanCompleteWithPriorCloudConsent() = runBlocking {
        Link().use { link ->
            link.prepare(autoB=true); withContext(link.dispatcher) { link.ea.initiate() }
            until { link.proofA.get()!=null && link.proofB.get()!=null }
            assertNull(link.approval.get()); assertEquals(0,link.failures.get())
        }
    }
    @Test fun aRemoteTranscriptCannotChooseWhatTheOtherDeviceSigns() = runBlocking {
        Link().use { link ->
            link.prepare(autoB=true,tamper=true); withContext(link.dispatcher) { link.ea.initiate() }
            until { link.failures.get()>0 }
            assertNull(link.proofA.get())
        }
    }
    @Test fun proofCannotBeReplayedFromAnotherPinnedLocalSession() = runBlocking {
        Link().use { link ->
            val other=link.ib.copy(localSessionId=RuntimeSessionId(UUID.randomUUID().toString()))
            link.prepare(autoB=true,identityB=other)
            withContext(link.dispatcher) { link.ea.initiate() }
            until { link.failures.get()>0 }
            assertNull(link.proofA.get()); assertNull(link.proofB.get())
        }
    }
    @Test fun closingChannelBeforeApprovalCannotRestoreTrust() = runBlocking {
        Link().use { link ->
            link.prepare(); withContext(link.dispatcher) { link.ea.initiate() }
            until { link.approval.get()!=null }
            withContext(link.dispatcher) { link.live.set(false); link.eb.close(); link.eb.approve(link.approval.get()) }
            delay(50); assertEquals(0,link.sentB.get()); assertNull(link.proofB.get())
        }
    }
}
