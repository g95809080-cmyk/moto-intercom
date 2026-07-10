package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
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
    fun concurrentRestartNeverLeavesOldTokenCurrent() {
        repeat(250) {
            val sessions = SessionGeneration()
            val old = sessions.start()
            val start = CountDownLatch(1)
            val claimSucceeded = AtomicBoolean(false)
            val owner = AtomicLong(0L)
            var fresh: SessionGeneration.Token? = null

            val claimant = thread {
                start.await()
                claimSucceeded.set(
                    sessions.claimIfCurrent(old) { owner.compareAndSet(0L, old.value) }
                )
            }
            val restarter = thread {
                start.await()
                sessions.invalidate()
                fresh = sessions.start()
            }

            start.countDown()
            claimant.join()
            restarter.join()

            assertFalse(sessions.isCurrent(old))
            assertTrue(sessions.isCurrent(requireNotNull(fresh)))
            assertFalse(sessions.claimIfCurrent(old) { error("stale claim executed") })
            assertTrue(!claimSucceeded.get() || owner.get() == old.value)
        }
    }
}
