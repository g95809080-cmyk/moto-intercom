package com.kuma.motointercom

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioFocusInterruptionInstrumentationTest {
    @Test
    fun mediaFocusClientReceivesMayDuckLossFromIntercomFocus() {
        assumeTrue("AudioFocusRequest requires API 26", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(AudioManager::class.java)
        val changes = CopyOnWriteArrayList<Int>()
        val mediaRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(changes::add)
            .build()
        val mediaResult = audioManager.requestAudioFocus(mediaRequest)
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, mediaResult)

        val intercomFocus = AndroidIntercomAudioFocus(context)
        try {
            assertEquals(AudioFocusResult.GRANTED, intercomFocus.request())
            waitForFocusChange(changes)
            assertTrue(
                "media focus changes=$changes",
                changes.contains(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            )
        } finally {
            intercomFocus.close()
            audioManager.abandonAudioFocusRequest(mediaRequest)
        }
    }

    private fun waitForFocusChange(changes: List<Int>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (changes.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20L)
        }
    }
}
