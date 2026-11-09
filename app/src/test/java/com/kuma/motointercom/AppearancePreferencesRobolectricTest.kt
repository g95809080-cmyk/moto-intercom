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
class AppearancePreferencesRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences
        get() = context.getSharedPreferences("motocom_appearance", Context.MODE_PRIVATE)

    @Test
    fun daylightIsTheDefaultAndNightrideSurvivesRecreation() {
        preferences.edit().clear().commit()

        assertEquals(MotoComThemeMode.DAYLIGHT, AppearancePreferences(context).load())
        assertTrue(AppearancePreferences(context).save(MotoComThemeMode.NIGHTRIDE))

        assertEquals(MotoComThemeMode.NIGHTRIDE, AppearancePreferences(context).load())
        assertTrue(AppearancePreferences(context).save(MotoComThemeMode.DAYLIGHT))
        assertEquals(MotoComThemeMode.DAYLIGHT, AppearancePreferences(context).load())
    }

    @Test
    fun unknownPersistedThemeFallsBackToDaylight() {
        preferences.edit().putString("theme", "UNKNOWN").commit()

        assertEquals(MotoComThemeMode.DAYLIGHT, AppearancePreferences(context).load())
    }

    @Test
    fun saveReportsCommitFailure() {
        val failing = AppearancePreferences(context) { false }

        assertFalse(failing.save(MotoComThemeMode.NIGHTRIDE))
    }
}
