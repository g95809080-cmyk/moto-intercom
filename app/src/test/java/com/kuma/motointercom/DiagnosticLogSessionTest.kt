package com.kuma.motointercom

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_791_424_000_000L
    private fun <T> await(register: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        register { result = it; latch.countDown() }
        assertTrue("background callback must complete", latch.await(10, TimeUnit.SECONDS))
        return result!!
    }

    @Test fun fullQueueReturnsFailureForReadAndExportInsteadOfHanging() {
        DiagnosticLogSession(PersistentLogStore(temp.newFolder(), "test"), temp.newFolder(), { "header" }, queueCapacity = 1, startWorker = false).use { log ->
            log.record("I", "test", "queued")
            assertTrue(await(log::recent).isFailure)
            assertTrue(await(log::export).isFailure)
        }
    }

    @Test fun diskFailureDoesNotKillWorkerAndCanRecover() {
        val dir = File(temp.root, "initially-a-file").apply { writeText("blocked") }
        DiagnosticLogSession(PersistentLogStore(dir, "test"), temp.newFolder(), { "header" }, clock = { now }).use { log ->
            log.record("I", "test", "cannot-write")
            assertTrue(await(log::recent).isFailure)
            assertTrue(dir.delete())
            log.record("I", "test", "recovered")
            val lines = await(log::recent).getOrThrow()
            assertTrue(lines.any { it.endsWith("recovered") })
            assertTrue(lines.any { it.contains("records omitted") })
        }
    }

    @Test fun aSecondExportPreservesTheFirstFileUntilItsTtl() {
        val exports = temp.newFolder()
        val time = AtomicLong(now)
        DiagnosticLogSession(PersistentLogStore(temp.newFolder(), "test"), exports, { "metadata" }, clock = time::get).use { log ->
            log.record("I", "test", "first")
            val first = await(log::export).getOrThrow()
            val original = first.readText()
            log.record("I", "test", "second")
            val second = await(log::export).getOrThrow()
            assertNotEquals(first, second)
            assertEquals(original, first.readText())
            assertFalse(first.readText().contains("second"))
            assertTrue(second.readText().contains("second"))
            first.setLastModified(now)
            time.set(now + DiagnosticLogSession.EXPORT_TTL_MS + 1)
            await(log::export).getOrThrow()
            assertFalse(first.exists())
        }
    }

    @Test fun exportBudgetFailureKeepsPreviouslySharedFiles() {
        val exports = temp.newFolder()
        DiagnosticLogSession(PersistentLogStore(temp.newFolder(), "test"), exports, { "metadata" }, clock = { now }, exportBudgetBytes = 400).use { log ->
            log.record("I", "test", "one")
            val first = await(log::export).getOrThrow()
            log.record("I", "test", "two-${"x".repeat(250)}")
            assertTrue(await(log::export).isFailure)
            assertTrue(first.exists())
            assertEquals(listOf(first), exports.listFiles()!!.toList())
        }
    }
}
