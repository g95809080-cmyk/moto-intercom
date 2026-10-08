package com.kuma.motointercom

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Only this application's diagnostics directory is managed here. */
internal class PersistentLogStore(
    private val directory: File,
    private val origin: String,
    private val segmentBytes: Long = 1024L * 1024,
    private val budgetBytes: Long = 256L * 1024 * 1024,
    private val readSegmentText: (File) -> String = { it.readText(Charsets.UTF_8) }
) {
    private var initialized = false
    private var nextSegment = 0L
    private var active: File? = null
    private var storedBytes = 0L
    private var maintainedAt = Long.MIN_VALUE

    init { require(segmentBytes > 0 && budgetBytes >= segmentBytes) }

    @Synchronized
    fun append(time: Long, level: String, tag: String, message: String): Boolean {
        prepare(time)
        val record = encode(time, level, tag, message)
        val gap = readGap()
        val gapRecord = gap?.let { encode(time, "W", "LocalDiagnostics", gapMessage(it)) }
        if (storedBytes + record.size + (gapRecord?.size ?: 0) > budgetBytes) {
            saveGap(Gap(gap?.first ?: time, time, (gap?.count ?: 0) + 1))
            return false
        }
        if (gapRecord != null) {
            writeRecord(gapRecord)
            delete(gapFile())
        }
        writeRecord(record)
        return true
    }

    @Synchronized
    fun recent(now: Long, limit: Int = 300): List<String> {
        require(limit > 0)
        prepare(now)
        val result = ArrayDeque<String>()
        readGap()?.takeIf { it.last >= now - RETENTION_MS }?.let {
            result.addFirst(display(it.last, "W", "LocalDiagnostics", gapMessage(it)))
        }
        for (file in segments().asReversed()) {
            val records = completeLines(file).mapNotNull(::decode)
            for ((time, text) in records.asReversed()) {
                if (time >= now - RETENTION_MS && result.size < limit) result.addFirst(text)
            }
            if (result.size >= limit) break
        }
        return result.toList()
    }

    @Synchronized
    fun export(now: Long, output: OutputStream, header: String) {
        maintain(now)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.write(header)
        writer.write("\n\n")
        for (file in segments()) {
            for (line in completeLines(file)) {
                val entry = decode(line) ?: continue
                if (entry.first >= now - RETENTION_MS) {
                    writer.write(entry.second)
                    writer.newLine()
                }
            }
        }
        readGap()?.takeIf { it.last >= now - RETENTION_MS }?.let {
            writer.write(display(it.last, "W", "LocalDiagnostics", gapMessage(it)))
            writer.newLine()
        }
        writer.flush()
    }

    @Synchronized
    fun maintain(now: Long) {
        prepare(now)
        maintainFiles(now)
    }

    private fun prepare(now: Long) {
        if (!initialized) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostics directory")
            recoverRewrites()
            nextSegment = (segments().lastOrNull()?.name?.removePrefix("diagnostic-")?.removeSuffix(".log")?.toLongOrNull() ?: -1) + 1
            maintainFiles(now)
            // A failed first maintenance must retry recovery and capacity accounting.
            initialized = true
        } else if (maintainedAt == Long.MIN_VALUE || now < maintainedAt || now - maintainedAt >= MAINTENANCE_MS) {
            maintainFiles(now)
        }
    }

    private fun maintainFiles(now: Long) {
        try {
            for (file in segments()) {
                val original = readSegmentText(file)
                val retained = completeLines(original).filter { (decode(it)?.first ?: Long.MIN_VALUE) >= now - RETENTION_MS }
                val text = if (retained.isEmpty()) "" else retained.joinToString("\n", postfix = "\n")
                when {
                    text.isEmpty() -> delete(file)
                    text != original -> rewrite(file, text)
                }
            }
            readGap()?.takeIf { it.last < now - RETENTION_MS }?.let { delete(gapFile()) }
            val files = segments()
            storedBytes = files.sumOf(File::length)
            active = files.lastOrNull()
            maintainedAt = now
        } catch (error: IOException) {
            // Recovery also applies to a live store after a partial cleanup/rewrite.
            initialized = false
            maintainedAt = Long.MIN_VALUE
            throw error
        }
    }

    private fun writeRecord(record: ByteArray) {
        if (record.size > segmentBytes) throw IOException("Diagnostic record exceeds segment budget")
        var file = active
        if (file == null || file.length() + record.size > segmentBytes) {
            file = File(directory, "diagnostic-${String.format(Locale.US, "%020d", nextSegment++)}.log")
            active = file
        }
        try {
            FileOutputStream(file, true).use { it.write(record) }
            storedBytes += record.size
        } catch (error: IOException) {
            // A partially written final record must be repaired before the next append.
            initialized = false
            throw error
        }
    }

    private fun encode(time: Long, level: String, tag: String, message: String): ByteArray =
        "$time\t${display(time, level, tag, message)}\n".toByteArray(Charsets.UTF_8)

    private fun display(time: Long, level: String, tag: String, message: String): String =
        "${utc(time)} ${clean(level).take(8)}/${clean(tag).take(64)} [${clean(origin).take(128)}] ${clean(message)}"

    private fun decode(line: String): Pair<Long, String>? {
        val separator = line.indexOf('\t')
        if (separator <= 0) return null
        val time = line.substring(0, separator).toLongOrNull() ?: return null
        val text = line.substring(separator + 1)
        return if (text.isBlank()) null else time to text
    }

    private fun completeLines(file: File): List<String> = completeLines(readSegmentText(file))
    private fun completeLines(text: String): List<String> {
        // A process can die between writing the payload and its terminating newline.
        val complete = if (text.endsWith('\n')) text else text.substringBeforeLast('\n', "")
        return complete.lineSequence().filter(String::isNotEmpty).toList()
    }

    private fun directoryFiles(): Array<File> = directory.listFiles() ?: throw IOException("Cannot read diagnostics directory")

    private fun segments(): List<File> = directoryFiles()
        .filter { it.isFile && SEGMENT_NAME.matches(it.name) }.sortedBy(File::getName)

    private fun rewrite(file: File, text: String) {
        val temp = File(directory, "${file.name}.tmp")
        val backup = File(directory, "${file.name}.bak")
        try {
            FileOutputStream(temp).use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            if (file.exists() && !file.renameTo(backup)) throw IOException("Cannot back up diagnostic segment")
            if (!temp.renameTo(file)) {
                if (backup.exists()) backup.renameTo(file)
                throw IOException("Cannot replace diagnostic segment")
            }
            delete(backup)
        } catch (error: IOException) {
            initialized = false
            throw error
        }
    }

    private fun recoverRewrites() {
        for (file in directoryFiles()) {
            val originalName = file.name.removeSuffix(".bak").removeSuffix(".tmp")
            if (!SEGMENT_NAME.matches(originalName) && originalName != "overflow.state") continue
            val original = File(directory, originalName)
            when {
                file.name.endsWith(".bak") -> {
                    if (!original.exists() && !file.renameTo(original)) throw IOException("Cannot recover diagnostics")
                    if (original.exists() && file.exists()) delete(file)
                }
                file.name.endsWith(".tmp") -> delete(file)
            }
        }
    }

    private data class Gap(val first: Long, val last: Long, val count: Long)
    private fun gapFile() = File(directory, "overflow.state")
    private fun readGap(): Gap? {
        if (!gapFile().exists()) return null
        val parts = gapFile().readText().trim().split('\t')
        if (parts.size != 3) return null
        return Gap(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
    }
    private fun saveGap(gap: Gap) = rewrite(gapFile(), "${gap.first}\t${gap.last}\t${gap.count}\n")
    private fun gapMessage(gap: Gap) = "Storage budget reached: ${gap.count} records omitted from ${utc(gap.first)} to ${utc(gap.last)}; retained history was preserved"
    private fun delete(file: File) { if (file.exists() && !file.delete()) throw IOException("Cannot remove expired diagnostics") }

    companion object {
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val MAINTENANCE_MS = 60L * 60 * 1000
        private val SEGMENT_NAME = Regex("diagnostic-[0-9]{20}\\.log")
        private val SECRETS = Regex("""(?i)((?:\b(?:pin|password|passphrase|psk|secret|token|authorization|room[_ -]?code|join[_ -]?code|ice-pwd|ice-ufrag)\b)["']?\s*[:=]\s*["']?|\bufrag\s+)[^"'\s,;]+""")

        fun clean(message: String): String = SECRETS.replace(message.take(4096)) { "${it.groupValues[1]}[redacted]" }
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

        fun utc(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(time))
    }
}
