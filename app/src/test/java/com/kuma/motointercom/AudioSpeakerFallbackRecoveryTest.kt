package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSpeakerFallbackRecoveryTest {
    @Test
    fun retriesAreBoundedAndGenerationScoped() {
        val recovery = AudioSpeakerFallbackRecovery(maxAttempts = 3)

        val first = checkNotNull(recovery.next())
        val second = checkNotNull(recovery.next())
        val third = checkNotNull(recovery.next())

        assertEquals(1, first.number)
        assertEquals(2, second.number)
        assertEquals(3, third.number)
        assertNull(recovery.next())

        recovery.reset()

        assertFalse(recovery.isCurrent(first))
        val fresh = checkNotNull(recovery.next())
        assertTrue(recovery.isCurrent(fresh))
        assertEquals(first.generation + 1, fresh.generation)
    }
}
