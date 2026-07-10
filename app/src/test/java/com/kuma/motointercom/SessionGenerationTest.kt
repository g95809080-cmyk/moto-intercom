package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class SessionGenerationTest {
    @Test
    fun oldTokenStaysInvalidAfterRestart() {
        val sessions = SessionGeneration()
        val old = sessions.start()
        sessions.invalidate()
        val fresh = sessions.start()

        assertFalse(sessions.isCurrent(old))
        assertTrue(sessions.isCurrent(fresh))
        assertNotEquals(old, fresh)
    }

    @Test
    fun secondStartInvalidatesFirstToken() {
        val sessions = SessionGeneration()
        val first = sessions.start()
        val second = sessions.start()

        assertFalse(sessions.isCurrent(first))
        assertTrue(sessions.isCurrent(second))
    }

    @Test
    fun staleTokenCannotClaimSharedResourceAfterRestart() {
        val sessions = SessionGeneration()
        val old = sessions.start()
        sessions.invalidate()
        val fresh = sessions.start()
        val owner = AtomicLong(0L)

        assertFalse(sessions.claimIfCurrent(old) { owner.compareAndSet(0L, old.value) })
        assertTrue(sessions.claimIfCurrent(fresh) { owner.compareAndSet(0L, fresh.value) })
    }

    @Test
    fun resourceClaimBlocksRestartUntilClaimCompletes() {
        val sessions = SessionGeneration()
        val old = sessions.start()
        val claimEntered = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val restartFinished = CountDownLatch(1)
        val claimSucceeded = AtomicBoolean(false)
        var fresh: SessionGeneration.Token? = null

        val claimant = thread {
            claimSucceeded.set(
                sessions.claimIfCurrent(old) {
                    claimEntered.countDown()
                    releaseClaim.await()
                    true
                }
            )
        }
        assertTrue(claimEntered.await(1, TimeUnit.SECONDS))

        val restarter = thread {
            sessions.invalidate()
            fresh = sessions.start()
            restartFinished.countDown()
        }

        assertFalse(restartFinished.await(100, TimeUnit.MILLISECONDS))
        releaseClaim.countDown()
        claimant.join()
        restarter.join()

        assertTrue(claimSucceeded.get())
        assertFalse(sessions.isCurrent(old))
        assertTrue(sessions.isCurrent(requireNotNull(fresh)))
    }

    @Test
    fun staleWorkerCannotClaimAfterConcurrentRestartCompletes() {
        val sessions = SessionGeneration()
        val old = sessions.start()
        val restartFinished = CountDownLatch(1)
        val claimExecuted = AtomicBoolean(false)
        val claimSucceeded = AtomicBoolean(true)

        val restarter = thread {
            sessions.invalidate()
            sessions.start()
            restartFinished.countDown()
        }
        val staleWorker = thread {
            restartFinished.await()
            claimSucceeded.set(
                sessions.claimIfCurrent(old) {
                    claimExecuted.set(true)
                    true
                }
            )
        }

        restarter.join()
        staleWorker.join()

        assertFalse(claimSucceeded.get())
        assertFalse(claimExecuted.get())
    }
}
