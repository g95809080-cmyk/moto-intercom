package com.kuma.motointercom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAudioCallbackGateTest {
    @Test
    fun callbackSurvivesTransportGenerationRolloverButNotRuntimeRollover() {
        val runtimeA = RuntimeSessionId("runtime-a")
        val runtimeB = RuntimeSessionId("runtime-b")
        val generations = SessionGeneration()
        val firstTransportGeneration = generations.start()

        assertTrue(generations.isCurrent(firstTransportGeneration))
        assertTrue(
            canDeliverRuntimeAudioCallback(
                running = true,
                activeRuntimeSessionId = runtimeA,
                callbackRuntimeSessionId = runtimeA
            )
        )

        generations.invalidate()
        val replacementTransportGeneration = generations.start()

        assertFalse(generations.isCurrent(firstTransportGeneration))
        assertTrue(generations.isCurrent(replacementTransportGeneration))
        assertTrue(
            canDeliverRuntimeAudioCallback(
                running = true,
                activeRuntimeSessionId = runtimeA,
                callbackRuntimeSessionId = runtimeA
            )
        )
        assertFalse(
            canDeliverRuntimeAudioCallback(
                running = true,
                activeRuntimeSessionId = runtimeB,
                callbackRuntimeSessionId = runtimeA
            )
        )
        assertFalse(
            canDeliverRuntimeAudioCallback(
                running = false,
                activeRuntimeSessionId = runtimeA,
                callbackRuntimeSessionId = runtimeA
            )
        )
    }
}
