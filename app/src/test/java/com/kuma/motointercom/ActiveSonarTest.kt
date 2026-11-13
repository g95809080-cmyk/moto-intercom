package com.kuma.motointercom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSonarTest {
    @Test
    fun emptyFieldTransmitsWithoutAcousticShadows() {
        for (angle in 0..360 step 10) {
            assertEquals(1f, sonarTransmission(Math.toRadians(angle.toDouble()).toFloat(), 0.9f, emptyList()), 0f)
        }
    }

    @Test
    fun echoStartsAtTheFacingSurfaceAndEndsWhenItReturnsToTheSource() {
        val target = SonarTarget(0f, 0.7f, 0.1f)
        assertEquals(0f, sonarEchoEnergy(0.59f, target), 0f)
        assertEquals(1f, sonarEchoEnergy(0.6f, target), 0.0001f)
        assertTrue(sonarEchoEnergy(0.8f, target) in 0f..1f)
        assertEquals(0f, sonarEchoEnergy(1.21f, target), 0f)
    }

    @Test
    fun shadowOnlyAttenuatesWavefrontsBehindTheTarget() {
        val targets = listOf(SonarTarget(0f, 0.6f, 0.1f))
        assertEquals(1f, sonarTransmission(0f, 0.59f, targets), 0f)
        assertTrue(sonarTransmission(0f, 0.8f, targets) < 0.1f)
        assertEquals(1f, sonarTransmission(Math.PI.toFloat(), 0.8f, targets), 0f)
    }

    @Test
    fun removingATargetImmediatelyRemovesItsShadow() {
        val target = SonarTarget(359f, 0.6f, 0.1f)
        assertTrue(sonarTransmission(0f, 0.8f, listOf(target)) < 0.1f)
        assertEquals(1f, sonarTransmission(0f, 0.8f, emptyList()), 0f)
    }
}
