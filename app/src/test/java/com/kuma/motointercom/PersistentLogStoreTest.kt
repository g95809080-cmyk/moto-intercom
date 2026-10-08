package com.kuma.motointercom

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentLogStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_791_424_000_000L
    private fun store(dir: File, origin: String = "1.2.0 API35 runtime-a") = PersistentLogStore(dir, origin)

    @Test fun reconstructionPreservesHistoryAndOriginalVersion() {
        val dir = temp.newFolder()
        store(dir).append(now, "I", "AudioRouteController", "SCO verification failed")
        val upgraded = store(dir, "1.3.0 API36 runtime-b")
        upgraded.append(now + 1, "I", "IntercomService", "recovered")
        val lines = upgraded.recent(now + 1)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("1.2.0 API35 runtime-a"))
        assertTrue(lines[1].contains("1.3.0 API36 runtime-b"))
    }

    @Test fun rollingSevenDayBoundaryIsExactInPreviewExportAndDisk() {
        val dir = temp.newFolder()
        val log = store(dir)
        log.append(now - PersistentLogStore.RETENTION_MS - 1, "I", "test", "expired")
        log.append(now - PersistentLogStore.RETENTION_MS, "I", "test", "boundary")
        log.append(now, "I", "test", "current")
        val lines = log.recent(now)
        assertEquals(2, lines.size)
        assertTrue(lines.first().endsWith("boundary"))
        val bytes = ByteArrayOutputStream()
        log.export(now, bytes, "metadata")
        assertFalse(bytes.toString("UTF-8").contains("expired"))
        assertTrue(bytes.toString("UTF-8").contains("boundary"))
        assertFalse(dir.listFiles()!!.filter { it.extension == "log" }.joinToString { it.readText() }.contains("expired"))
    }

    @Test fun continuousOnlineUseCleansOldDaysWithoutRestart() {
        val dir = temp.newFolder()
        val log = store(dir)
        val day = 24L * 60 * 60 * 1000
        repeat(10) { log.append(now + it * day, "I", "test", "day-$it") }
        val lines = log.recent(now + 9 * day)
        assertEquals(8, lines.size) // Inclusive boundary at exactly 168 hours.
        assertTrue(lines.first().endsWith("day-2"))
        assertFalse(dir.listFiles()!!.filter { it.extension == "log" }.joinToString { it.readText() }.contains("day-1\n"))
    }

    @Test fun truncatedTailAndInterruptedRewriteDoNotCorruptLaterRecords() {
        val dir = temp.newFolder()
        store(dir).append(now, "I", "test", "complete")
        val file = dir.listFiles()!!.single()
        file.appendText("$now\tpartial-record")
        val restored = store(dir)
        restored.append(now + 1, "I", "test", "after-kill")
        assertEquals(2, restored.recent(now + 1).size)
        assertFalse(restored.recent(now + 1).any { it.contains("partial-record") })
        assertTrue(file.renameTo(File(dir, "${file.name}.bak")))
        File(dir, "${file.name}.tmp").writeText("unfinished rewrite")
        val recovered = store(dir)
        assertEquals(2, recovered.recent(now + 1).size)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".bak") || it.name.endsWith(".tmp") })
    }

    @Test fun storageBudgetPreservesUnexpiredHistoryAndPersistsOmissionCount() {
        val dir = temp.newFolder()
        val log = PersistentLogStore(dir, "test", segmentBytes = 512, budgetBytes = 1024)
        val accepted = (0..19).map { log.append(now + it, "I", "test", "entry-$it-${"x".repeat(80)}") }
        assertTrue(accepted.any { !it })
        assertTrue(log.recent(now + 20).any { it.contains("entry-0-") })
        val rebuilt = PersistentLogStore(dir, "test", segmentBytes = 512, budgetBytes = 1024)
        assertTrue(rebuilt.recent(now + 20).any { it.contains("records omitted") })
        assertTrue(dir.listFiles()!!.filter { it.extension == "log" }.all { it.length() <= 512 })
        assertTrue(rebuilt.append(now + PersistentLogStore.RETENTION_MS + 1000, "I", "test", "resumed"))
        assertTrue(rebuilt.recent(now + PersistentLogStore.RETENTION_MS + 1000).single().endsWith("resumed"))
    }

    @Test fun previewIsBoundedButExportContainsAllRetainedRecordsInOrder() {
        val dir = temp.newFolder()
        val log = PersistentLogStore(dir, "test", segmentBytes = 1024)
        repeat(450) { log.append(now + it, "I", "test", "record-$it") }
        assertEquals(300, log.recent(now + 450).size)
        assertTrue(log.recent(now + 450).first().endsWith("record-150"))
        val bytes = ByteArrayOutputStream()
        log.export(now + 450, bytes, "header")
        val records = bytes.toString("UTF-8").lineSequence().filter { it.contains(" I/test ") }.toList()
        assertEquals(450, records.size)
        assertTrue(records.first().endsWith("record-0"))
        assertTrue(records.last().endsWith("record-449"))
    }

    @Test fun concurrentAppendersProduceWholeRecordsWithoutLoss() {
        val dir = temp.newFolder()
        val log = store(dir)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { worker -> pool.submit { repeat(50) { log.append(now, "I", "test", "$worker/$it") } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val messages = log.recent(now).map { it.substringAfter("] ") }
        assertEquals(200, messages.toSet().size)
    }

    @Test fun diagnosticsRedactExistingIceCredentialsAndCannotForgeLines() {
        val dir = temp.newFolder()
        store(dir).append(now, "E", "test", "password=hunter2 roomCode=123456 a=ice-ufrag:iceSecret a=ice-pwd:icePass candidate:1 ufrag candidateSecret\nforged\ttab")
        val text = store(dir).recent(now).single()
        listOf("hunter2", "123456", "iceSecret", "icePass", "candidateSecret").forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("[redacted]"))
        assertTrue(text.contains("\\nforged\\ttab"))
        assertFalse(text.contains('\n'))
    }

    @Test fun actualSerializedSteadyStateRecordsFitSevenDaysBudget() {
        val dir = temp.newFolder()
        val log = store(dir, "1.3.0+aee3b3f API36 3d662b21-1ed1-4a24-bdd6-27d73d3029ea")
        log.append(now, "D", "RiderAudioEngine", "VOX enabled=true state=OPEN energy=-18.1 noise=-32.0 open=-24.0 close=-28.0 volume=1.0")
        val voxBytes = dir.listFiles()!!.sumOf(File::length)
        log.append(now, "D", "IntercomManager", "RX signaling frame: type=HEARTBEAT")
        val heartbeatBytes = dir.listFiles()!!.sumOf(File::length) - voxBytes
        log.append(now, "I", "AudioRouteController", "modern route[device-change]: communication=BLUETOOTH_SCO mode=IN_COMMUNICATION")
        val routeBytes = dir.listFiles()!!.sumOf(File::length) - voxBytes - heartbeatBytes
        val sevenDaySeconds = PersistentLogStore.RETENTION_MS / 1000
        val estimate = voxBytes * (sevenDaySeconds / 5) + heartbeatBytes * sevenDaySeconds * 2 + routeBytes * (sevenDaySeconds / 30)
        assertTrue("168h estimate=$estimate", estimate < 256L * 1024 * 1024)
    }
}
