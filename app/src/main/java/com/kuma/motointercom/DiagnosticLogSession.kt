package com.kuma.motointercom

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface DiagnosticLogAccess {
    fun record(level: String, tag: String, message: String)
    fun recent(callback: (Result<List<String>>) -> Unit)
    fun export(callback: (Result<File>) -> Unit)
}

/** A single, bounded, process-lifetime writer. No Activity references are retained. */
internal class DiagnosticLogSession(
    private val store: PersistentLogStore,
    private val exportDirectory: File,
    private val header: (Long) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 512,
    private val exportBudgetBytes: Long = 512L * 1024 * 1024,
    private val reportError: (Throwable) -> Unit = {},
    startWorker: Boolean = true
) : DiagnosticLogAccess, AutoCloseable {
    private val commands = ArrayBlockingQueue<() -> Unit>(queueCapacity)
    private val dropped = AtomicLong()
    @Volatile private var closed = false
    private val worker = Thread(::runWorker, "MotoCom-diagnostics").apply {
        isDaemon = true
        if (startWorker) start()
    }

    override fun record(level: String, tag: String, message: String) {
        val time = clock()
        // Bound retained arguments before they enter the audio-thread-safe queue.
        val safeTag = PersistentLogStore.clean(tag).take(64)
        val safeMessage = PersistentLogStore.clean(message)
        if (closed || !commands.offer({
                try { store.append(time, level, safeTag, safeMessage) }
                catch (error: Throwable) { dropped.incrementAndGet(); reportSafely(error) }
            })) dropped.incrementAndGet()
    }

    override fun recent(callback: (Result<List<String>>) -> Unit) = submit(callback) { store.recent(clock()) }

    override fun export(callback: (Result<File>) -> Unit) = submit(callback) {
        val now = clock()
        if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) throw IOException("Cannot create export directory")
        val oldFiles = exportFiles()
        oldFiles.filter { it.lastModified() < now - EXPORT_TTL_MS }.forEach {
            if (!it.delete()) throw IOException("Cannot remove expired export")
        }
        val used = exportFiles().sumOf(File::length)
        val file = File(exportDirectory, "motocom-logs-$now-${UUID.randomUUID()}.txt")
        try {
            file.outputStream().use { raw ->
                val limited = object : OutputStream() {
                    private var written = 0L
                    override fun write(value: Int) { reserve(1); raw.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { reserve(length); raw.write(bytes, offset, length) }
                    private fun reserve(length: Int) {
                        if (used + written + length > exportBudgetBytes) throw IOException("Export storage budget reached; previous exports remain available")
                        written += length
                    }
                }
                store.export(now, limited, header(now))
            }
            file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun exportFiles(): List<File> =
        (exportDirectory.listFiles() ?: throw IOException("Cannot read export directory"))
            .filter { EXPORT_NAME.matches(it.name) }

    private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        if (closed || !commands.offer({ callback(runCatching(operation)) })) {
            callback(Result.failure(IOException("Diagnostics queue is busy; retry shortly")))
        }
    }

    private fun runWorker() {
        while (!closed) {
            try {
                val command = commands.poll(1, TimeUnit.HOURS)
                val omitted = dropped.get()
                if (omitted > 0) {
                    try {
                        if (store.append(clock(), "W", "LocalDiagnostics", "Diagnostics queue/storage failure: $omitted records omitted")) dropped.addAndGet(-omitted)
                    } catch (error: Throwable) { reportSafely(error) }
                }
                if (command == null) store.maintain(clock()) else command()
            } catch (_: InterruptedException) {
                if (closed) break
            } catch (error: Throwable) {
                reportSafely(error)
            }
        }
        commands.clear()
    }

    private fun reportSafely(error: Throwable) { runCatching { reportError(error) } }

    override fun close() { closed = true; worker.interrupt(); commands.clear() }

    companion object {
        const val EXPORT_TTL_MS = 24L * 60 * 60 * 1000
        private val EXPORT_NAME = Regex("motocom-logs-[0-9]+-[0-9a-f-]+\\.txt")
    }
}
