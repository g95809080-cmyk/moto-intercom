package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

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
}
