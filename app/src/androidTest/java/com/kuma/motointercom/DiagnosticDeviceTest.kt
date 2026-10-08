package com.kuma.motointercom

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticDeviceTest {
    private fun <T> await(register: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        register { result = it; latch.countDown() }
        assertTrue("diagnostic worker must answer", latch.await(30, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    @Test fun originalExportUriRemainsReadableAfterSecondExportAndPrivateHistoryIsHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DiagnosticLog.initialize(context)
        val log = DiagnosticLog.forContext(context)!!
        log.record("I", "DiagnosticDeviceTest", "before-first-export")
        val first = await(log::export)
        val chooser = diagnosticExportChooser(context, first)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        val before = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        log.record("I", "DiagnosticDeviceTest", "after-first-export")
        val second = await(log::export)
        assertNotEquals(first, second)
        val after = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals(before, after)
        assertTrue(after.contains("before-first-export"))
        assertFalse(after.contains("after-first-export"))
        val privateFile = java.io.File(context.filesDir, "diagnostics").listFiles()!!.first { it.extension == "log" }
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-files", privateFile)
            fail("Private history must not be exposed")
        } catch (_: IllegalArgumentException) { }
    }
}
