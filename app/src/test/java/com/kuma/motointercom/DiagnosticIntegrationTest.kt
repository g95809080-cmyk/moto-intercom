package com.kuma.motointercom

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticIntegrationTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<MainScreen>()
    @After fun cleanup() {
        screens.forEach(MainScreen::closeDiagnostics)
        (DiagnosticLog.forContext(app) as? AutoCloseable)?.close()
    }

    private fun <T> await(register: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        register { result = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    @Test fun serviceDiagnosticsAreSavedWithoutAnActivityListener() {
        DiagnosticLog.initialize(app)
        val service = Robolectric.buildService(IntercomService::class.java).get()
        val publish = IntercomService::class.java.getDeclaredMethod("publishLog", String::class.java).apply { isAccessible = true }
        publish.invoke(service, "background recovery attempt")
        DiagnosticLog.w("AudioRouteController", "Bluetooth verify failed")
        val lines = await(DiagnosticLog.forContext(app)!!::recent)
        assertTrue(lines.any { it.contains("IntercomService") && it.endsWith("background recovery attempt") })
        assertTrue(lines.any { it.contains("AudioRouteController") && it.endsWith("Bluetooth verify failed") })
    }

    @Test fun periodicVoxSamplesAreRateLimitedButTransitionsAreRetained() {
        DiagnosticLog.initialize(app)
        repeat(20) { DiagnosticLog.d("RiderAudioEngine", "VOX enabled=true sample=$it") }
        DiagnosticLog.i("RiderAudioEngine", "VOX state=OPEN")
        val lines = await(DiagnosticLog.forContext(app)!!::recent)
        assertEquals(1, lines.count { it.contains("VOX enabled=") })
        assertEquals(1, lines.count { it.contains("VOX state=OPEN") })
    }

    @Test fun exportChooserGrantsOnlyReadAccessToOneContentUri() {
        val expected = Uri.parse("content://${app.packageName}.diagnostic-files/diagnostic_exports/sample.txt")
        val chooser = diagnosticExportChooser(app, expected)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(expected, uri)
    }

    @Test fun recreatedScreenLoadsSavedHistoryAndLateResultsDoNotReopenOldPage() {
        val fake = FakeDiagnostics()
        val first = screen(fake)
        fake.completeRecent(listOf("saved background log"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logState(first).logText.contains("saved background log"))
        first.closeDiagnostics()
        val second = screen(fake)
        fake.completeRecent(listOf("saved background log", "after-restart"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(logState(second).logText.contains("after-restart"))
        second.pauseDiagnostics()
        second.resumeDiagnostics()
        second.closeDiagnostics()
        fake.completeRecent(listOf("late result"))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(logState(second).logText.contains("late result"))
    }

    @Test fun exportPreparationAndFailureReturnToARetryableState() {
        val fake = FakeDiagnostics()
        val screen = screen(fake)
        fake.completeRecent(listOf("saved log"))
        shadowOf(Looper.getMainLooper()).idle()
        val export = MainScreen::class.java.getDeclaredMethod("exportLogs").apply { isAccessible = true }
        export.invoke(screen)
        assertTrue(logState(screen).exporting)
        fake.exportCallback!!(Result.failure(java.io.IOException("disk full")))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(logState(screen).exporting)
        assertTrue(logState(screen).exportEnabled)
        assertNotNull(logState(screen).errorText)
    }

    private fun screen(fake: DiagnosticLogAccess): MainScreen {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val screen = MainScreen(
            activity, "", Bundle().apply { putString("main_route", "LOGS") }, {}, { false }, { true }, {}, {}, {}, {}, diagnostics = fake
        )
        activity.setContentView(screen.root)
        screens += screen
        return screen
    }
    @Suppress("UNCHECKED_CAST")
    private fun logState(screen: MainScreen): LogsScreenUiState =
        (MainScreen::class.java.getDeclaredField("logsUiState").apply { isAccessible = true }.get(screen) as State<LogsScreenUiState>).value

    private class FakeDiagnostics : DiagnosticLogAccess {
        val recentCallbacks = ArrayDeque<(Result<List<String>>) -> Unit>()
        var exportCallback: ((Result<File>) -> Unit)? = null
        override fun record(level: String, tag: String, message: String) = Unit
        override fun recent(callback: (Result<List<String>>) -> Unit) { recentCallbacks.addLast(callback) }
        override fun export(callback: (Result<File>) -> Unit) { exportCallback = callback }
        fun completeRecent(lines: List<String>) { recentCallbacks.removeFirst()(Result.success(lines)) }
    }
}
