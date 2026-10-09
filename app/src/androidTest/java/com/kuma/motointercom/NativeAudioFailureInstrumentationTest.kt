package com.kuma.motointercom

import android.Manifest
import android.media.AudioFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule

@RunWith(AndroidJUnit4::class)
class NativeAudioFailureInstrumentationTest {
    @Test fun nativeReadFailureReportsOnceMutesFramesAndIgnoresErrorsAfterClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val failures = LinkedBlockingQueue<Throwable>()
        val engine = RiderAudioEngine(context, onEngineError = { failures.offer(it) })
        try {
            val admField = RiderAudioEngine::class.java.getDeclaredField("audioDeviceModule").apply { isAccessible = true }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var module: Any? = null
            while (module == null && SystemClock.elapsedRealtime() < deadline) {
                module = admField.get(engine)
                if (module == null) SystemClock.sleep(10)
            }
            assertNotNull("Native ADM did not initialize", module)
            val record = module!!.javaClass.getDeclaredField("audioInput").run { isAccessible = true; get(module) }
            val nativeHardwareNs = record.javaClass.getDeclaredField("isNoiseSuppressorSupported").run {
                isAccessible = true; getBoolean(record)
            }
            assertFalse("Hardware NS must not replace the software path", nativeHardwareNs)
            val errors = record.javaClass.getDeclaredField("errorCallback").run {
                isAccessible = true; get(record) as JavaAudioDeviceModule.AudioRecordErrorCallback
            }
            val capture = record.javaClass.getField("motoCaptureCallback").get(record) as AudioRecordDataCallback
            errors.onWebRtcAudioRecordError("injected native read failure")
            assertTrue(failures.poll(5, TimeUnit.SECONDS)?.message.orEmpty().contains("capture:read"))
            errors.onWebRtcAudioRecordInitError("duplicate native failure")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
            val frame = ByteBuffer.allocateDirect(960)
            for (index in 0 until 960) frame.put(index, 32)
            capture.onAudioDataRecorded(AudioFormat.ENCODING_PCM_16BIT, 1, 48_000, frame)
            assertTrue((0 until 960).all { frame.get(it) == 0.toByte() })
            engine.close()
            errors.onWebRtcAudioRecordError("late closed capture error")
            assertNull(failures.poll(200, TimeUnit.MILLISECONDS))
        } finally { engine.close() }
    }
}
