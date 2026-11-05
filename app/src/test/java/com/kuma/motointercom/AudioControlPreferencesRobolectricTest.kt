package com.kuma.motointercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioControlPreferencesRobolectricTest {
    @Test
    fun defaultsMatchTheExistingVoxGateAndMuteIsNeverPersisted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("moto_intercom", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AudioControlPreferences(context)

        assertEquals(AudioControlSettings(rnnoiseEnabled = BuildConfig.RNNOISE_BETA), preferences.load())
        assertTrue(
            preferences.saveVoxSettings(
                AudioControlSettings(muted = true, voxEnabled = false, voxSensitivity = 72)
            )
        )

        assertEquals(
            AudioControlSettings(muted = false, voxEnabled = false, voxSensitivity = 72),
            AudioControlPreferences(context).load()
        )
    }

    @Test
    fun corruptedSensitivityIsClampedWhenRead() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("moto_intercom", Context.MODE_PRIVATE).edit()
            .putInt("vox_sensitivity", 900)
            .commit()

        assertEquals(MAX_VOX_SENSITIVITY, AudioControlPreferences(context).load().voxSensitivity)
        assertFalse(AudioControlPreferences(context).load().muted)
    }

    @Test
    fun noiseChoiceSurvivesRecreationVoxEditsAndStartIntent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("moto_intercom", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AudioControlPreferences(context)
        assertTrue(preferences.saveVoxSettings(preferences.load().copy(rnnoiseEnabled = false)))
        assertFalse(AudioControlPreferences(context).load().rnnoiseEnabled)
        assertTrue(preferences.saveVoxSettings(preferences.load().copy(rnnoiseEnabled = true)))
        val reloaded = AudioControlPreferences(context).load()
        assertEquals(BuildConfig.RNNOISE_BETA, reloaded.rnnoiseEnabled)
        assertTrue(preferences.saveVoxSettings(reloaded.copy(voxSensitivity = 73)))
        val edited = AudioControlPreferences(context).load()
        assertEquals(BuildConfig.RNNOISE_BETA, edited.rnnoiseEnabled)
        val intent = IntercomService.startIntent(context, audioControls = edited)
        assertEquals(edited.rnnoiseEnabled,
            intent.getBooleanExtra("com.kuma.motointercom.extra.RNNOISE_ENABLED", !edited.rnnoiseEnabled))
    }
}
