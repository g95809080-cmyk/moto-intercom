package com.kuma.motointercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CloudPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L
    @Before fun reset() {
        context.getSharedPreferences("gme_cloud_state",0).edit().clear().commit()
        context.getSharedPreferences("gme_cloud_preferences",0).edit().clear().commit()
    }
    @Test fun privacyDefaultsOffAndOptOutRetainsVersionedConsentOnly() {
        val p=CloudPreferences(context)
        assertFalse(p.enabled); assertNull(p.consentAt); assertFalse(p.wifiOnly)
        p.accept(now); p.wifiOnly=true
        assertTrue(CloudPreferences(context).enabled); assertEquals(now,p.consentAt)
        p.disable(); assertFalse(p.enabled)
        p.accept(now+1); assertEquals(now,p.consentAt)
    }
    @Test fun forgetAtomicallyInvalidatesProofProposalStartAndRideAndRejectsLateWrites() = runBlocking {
        val store=RideSessionStore(context); val proof=testPair(now=now)
        assertTrue(store.putProof("b",proof,0)); store.markRegistered("b","pair_test")
        store.noteProposal("b","pair_unfinished",0)
        val request=store.startRequest("pair_test"); store.saveRide(testRide(now))
        store.forget("b")
        assertNull(store.savedRide()); assertEquals(setOf("pair_test","pair_unfinished"),store.revocations())
        assertEquals("pair_test",store.cancelledStarts()[request])
        assertFalse(store.putProof("b",proof,0))
        store.saveRide(testRide(now)); assertNull(store.savedRide())
    }
    @Test fun lateEndCannotDeleteANewerRideAndCompletedEndCannotBeRestored() = runBlocking {
        val store=RideSessionStore(context); val proof=testPair(now=now)
        store.putProof("b",proof,0); store.markRegistered("b","pair_test")
        val old=testRide(now); store.saveRide(old); store.queueEnd(old.rideId); store.completeEnd(old.rideId)
        store.saveRide(old); assertNull(store.savedRide())
        val next=old.copy(rideId="ride_next")
        store.saveRide(next); store.queueEnd(old.rideId)
        assertEquals(next,store.savedRide())
    }
    @Test fun cancelledStartHasANewIdAndLateCompletionDoesNotEraseIt() = runBlocking {
        val store=RideSessionStore(context)
        val first=store.startRequest("pair_test")
        assertEquals(first,store.startRequest("pair_test"))
        store.cancelStarts()
        val next=store.startRequest("pair_test"); assertNotEquals(first,next)
        store.completeStart("pair_test",first)
        assertEquals(next,store.startRequest("pair_test"))
        assertEquals(mapOf(first to "pair_test"),store.cancelledStarts())
    }
    @Test fun lateRegistrationFailureCannotRemoveANewerProof() = runBlocking {
        val store=RideSessionStore(context); val old=testPair(now=now)
        store.putProof("b",old,0); store.forget("b")
        val a=TestDeviceSigner(); val b=TestDeviceSigner()
        val transcript=old.transcript.copy(pairId="pair_new",publicKeyA=a.publicKeyBase64(),publicKeyB=b.publicKeyBase64())
        val next=PairingProof(transcript,a.sign(transcript.bytes()),b.sign(transcript.bytes()))
        store.putProof("b",next,1)
        assertFalse(store.forgetIfCurrent("b","pair_test",0))
        assertEquals(next,store.pairs().single().proof)
        assertTrue(store.forgetIfCurrent("b","pair_new",1)); assertTrue(store.pairs().isEmpty())
    }
    @Test fun dualProofBindsBothKeysAndExactTranscript() {
        val proof=testPair(now=now)
        assertTrue(proof.verified()); assertTrue(proof.transcript.validAt(now))
        assertFalse(proof.copy(signatureB=proof.signatureA).verified())
        assertFalse(proof.copy(transcript=proof.transcript.copy(nonce="another_nonce_123456")).verified())
        assertFalse(proof.transcript.validAt(now+300_000))
        assertEquals(proof,PairingProof.parse(proof.json().toString()))
    }
}
