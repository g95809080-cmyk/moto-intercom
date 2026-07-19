package com.kuma.motointercom

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticAudioSourceIsolationTest {
    @Test
    fun syntheticAudioHarnessExistsOnlyInAndroidTestSourceSet() {
        val productionRoots = listOf(File("src/main"), File("src/debug"))
        val forbiddenNames = setOf("SyntheticAudioSource.kt", "TestAudioSink.kt")

        productionRoots.filter(File::exists).forEach { root ->
            val leaked = root.walkTopDown()
                .filter(File::isFile)
                .filter { it.name in forbiddenNames }
                .toList()
            assertTrue("Synthetic audio leaked into ${root.path}: $leaked", leaked.isEmpty())
        }

        assertTrue(
            File("src/androidTest/java/com/kuma/motointercom/SyntheticAudioSource.kt").isFile
        )
        assertTrue(
            File("src/androidTest/java/com/kuma/motointercom/TestAudioSink.kt").isFile
        )
        assertFalse(File("src/main/java/com/kuma/motointercom/SyntheticAudioSource.kt").exists())
        assertFalse(File("src/main/java/com/kuma/motointercom/TestAudioSink.kt").exists())
    }
}
