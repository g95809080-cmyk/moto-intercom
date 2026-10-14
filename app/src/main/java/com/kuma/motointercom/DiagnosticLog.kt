package com.kuma.motointercom

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log as AndroidLog
import java.io.File
import java.util.TimeZone
import java.util.UUID

/** Compatibility wrapper: original Logcat output plus local diagnostics. */
internal object DiagnosticLog {
    @Volatile private var application: Context? = null
    @Volatile private var session: DiagnosticLogSession? = null
    private var lastVoxSample = Long.MIN_VALUE
    private var lastStorageWarning = Long.MIN_VALUE

    @Synchronized
    fun initialize(context: Context) {
        val app = context.applicationContext
        if (application === app) return
        session?.close()
        application = app
        lastVoxSample = Long.MIN_VALUE
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "unknown"
        val origin = "$version API${Build.VERSION.SDK_INT} ${UUID.randomUUID()}"
        session = DiagnosticLogSession(
            store = PersistentLogStore(File(app.filesDir, "diagnostics"), origin),
            exportDirectory = File(app.cacheDir, "diagnostic-exports"),
            header = { now ->
                "MotoCom local diagnostics\nExported: ${PersistentLogStore.utc(now)}\n" +
                    "Timezone: ${TimeZone.getDefault().id}\nApp at export: $version\n" +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
                    "Retention: rolling 168 hours; 256 MiB history budget; unexpired history is preserved.\n" +
                    "Preview: last 300 records. VOX periodic DEBUG samples: once every 5 seconds; transitions are retained.\n" +
                    "Queue/storage omissions, if any, are recorded. Exports are kept for at least 24 hours; expired files are cleaned on the next export."
            },
            reportError = { error ->
                val now = SystemClock.elapsedRealtime()
                if (lastStorageWarning == Long.MIN_VALUE || now - lastStorageWarning >= 60_000) {
                    lastStorageWarning = now
                    AndroidLog.w("LocalDiagnostics", "Local diagnostic storage unavailable", error)
                }
            }
        )
    }

    fun forContext(context: Context): DiagnosticLogAccess? =
        session.takeIf { application === context.applicationContext }

    fun d(tag: String, message: String): Int = write("D", tag, message, null) { AndroidLog.d(tag, message) }
    fun i(tag: String, message: String): Int = write("I", tag, message, null) { AndroidLog.i(tag, message) }
    fun w(tag: String, message: String, error: Throwable? = null): Int = write("W", tag, message, error) { AndroidLog.w(tag, message, error) }
    fun e(tag: String, message: String?, error: Throwable? = null): Int = write("E", tag, message.orEmpty(), error) { AndroidLog.e(tag, message, error) }

    private inline fun write(level: String, tag: String, message: String, error: Throwable?, logcat: () -> Int): Int {
        val result = logcat()
        if (level == "D" && tag == "RiderAudioEngine" && message.startsWith("VOX enabled=")) {
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (lastVoxSample != Long.MIN_VALUE && now - lastVoxSample < 5_000) return result
                lastVoxSample = now
            }
        }
        val details = if (error == null) message else buildString {
            append(message.take(2048)); append("; "); append(error.javaClass.simpleName)
            append(": "); append(error.message.orEmpty().take(1024))
            error.stackTrace.take(8).forEach { append("\n at "); append(it) }
        }
        session?.record(level, tag, details)
        return result
    }
}
