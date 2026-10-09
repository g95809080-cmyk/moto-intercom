package com.kuma.motointercom

import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun maintenanceFailuresCompleteReadAndExportCallbacksThenRecoverSameWorker() {
        val dir = temp.newFolder()
        PersistentLogStore(dir, "test").append(now, "I", "test", "existing-history")
        val unavailable = AtomicBoolean(true)
        val store = PersistentLogStore(dir, "test", readSegmentText = { file ->
            if (unavailable.get()) throw IOException("segment temporarily unavailable")
            file.readText(Charsets.UTF_8)
        })
        DiagnosticLogSession(store, temp.newFolder(), { "metadata" }, clock = { now }).use { log ->
            assertTrue(await(log::recent).isFailure)
            assertTrue(await(log::export).isFailure)
            unavailable.set(false)
            log.record("I", "test", "after-recovery")
            val lines = await(log::recent).getOrThrow()
            assertTrue(lines.any { it.endsWith("existing-history") })
            assertTrue(lines.any { it.endsWith("after-recovery") })
            val exported = await(log::export).getOrThrow().readText()
            assertTrue(exported.contains("existing-history"))
            assertTrue(exported.contains("after-recovery"))
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
        val store = PersistentLogStore(temp.newFolder(), "test")
        // The second file fits alone, but both files exceed this budget on LF and CRLF hosts.
        DiagnosticLogSession(store, exports, { "metadata" }, clock = { now }, exportBudgetBytes = 380).use { log ->
            log.record("I", "test", "one")
            val first = await(log::export).getOrThrow()
            log.record("I", "test", "two-${"x".repeat(250)}")
            assertTrue(await(log::export).isFailure)
            val singleExport = ByteArrayOutputStream()
            store.export(now, singleExport, "metadata")
            assertTrue("One new export must fit; existing exports consume the remaining budget", singleExport.size() <= 380)
            assertTrue(first.exists())
            assertEquals(listOf(first), exports.listFiles()!!.toList())
        }
    }

    @Test fun firstEnumerationFailureKeepsFilesAndCanRecoverWithoutForgettingBudget() {
        enumerationFailurePreservesBudget(failAt = 1)
    }

    @Test fun secondEnumerationFailureAfterTtlCleanupKeepsSharedFilesAndBudget() {
        enumerationFailurePreservesBudget(failAt = 2)
    }

    private fun enumerationFailurePreservesBudget(failAt: Int) {
        val directory = temp.newFolder()
        val reads = AtomicInteger()
        val failure = AtomicInteger()
        val exports = object : File(directory.path) {
            override fun listFiles(): Array<File>? =
                if (reads.incrementAndGet() == failure.get()) null else super.listFiles()
        }
        val time = AtomicLong(now)
        val headers = AtomicInteger()
        val store = PersistentLogStore(temp.newFolder(), "test")
        DiagnosticLogSession(store, exports, { headers.incrementAndGet(); "metadata" },
            clock = time::get, exportBudgetBytes = 380).use { log ->
            log.record("I", "test", "one")
            val shared = await(log::export).getOrThrow()
            val original = shared.readBytes()
            assertTrue(shared.setLastModified(now))
            val expired = File(directory, "motocom-logs-1-00000000-0000-0000-0000-000000000000.txt")
                .apply { writeText("expired"); assertTrue(setLastModified(now - DiagnosticLogSession.EXPORT_TTL_MS - 1)) }
            log.record("I", "test", "two-${"x".repeat(250)}")
            reads.set(0)
            headers.set(0)
            failure.set(failAt)

            val error = await(log::export).exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals("Cannot read export directory", error!!.message)
            assertEquals(failAt, reads.get())
            assertEquals("Failure must precede writing an export", 0, headers.get())
            assertArrayEquals(original, shared.readBytes())
            assertEquals(failAt == 1, expired.exists())
            assertEquals(if (failAt == 1) setOf(shared, expired) else setOf(shared), directory.listFiles()!!.toSet())

            // Subsequent reads succeed on the same worker. One new file fits alone,
            // but the retained share must still be included in the directory budget.
            val budgetError = await(log::export).exceptionOrNull()
            assertTrue(budgetError is IOException)
            assertTrue(budgetError!!.message!!.contains("storage budget"))
            assertArrayEquals(original, shared.readBytes())
            assertEquals(setOf(shared), directory.listFiles()!!.toSet())
            val single = ByteArrayOutputStream()
            store.export(now, single, "metadata")
            assertTrue("The failure must be due to existing exports", single.size() <= 380)
            assertTrue(await(log::recent).getOrThrow().any { it.contains("two-") })

            time.set(now + DiagnosticLogSession.EXPORT_TTL_MS + 1)
            val recovered = await(log::export).getOrThrow()
            assertFalse(shared.exists())
            assertTrue(recovered.readText().contains("two-"))
            assertEquals(setOf(recovered), directory.listFiles()!!.toSet())
        }
    }
}
