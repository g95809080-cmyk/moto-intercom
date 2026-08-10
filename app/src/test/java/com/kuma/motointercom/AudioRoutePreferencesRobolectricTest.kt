package com.kuma.motointercom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioRoutePreferencesRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AudioRoutePreferences(context)

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsToBluetoothAndPersistsExplicitSelection() {
        assertEquals(AudioRouteSelection.BLUETOOTH, preferences.load())

        assertTrue(preferences.save(AudioRouteSelection.EARPIECE))

        assertEquals(AudioRouteSelection.EARPIECE, AudioRoutePreferences(context).load())
    }

    @Test
    fun invalidStoredValueFallsBackToBluetooth() {
        assertTrue(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTION, "NOT_A_ROUTE")
                .commit()
        )

        assertEquals(AudioRouteSelection.BLUETOOTH, preferences.load())
    }

    @Test
    fun saveFailureIsReported() {
        val readOnly = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        readOnly.edit().clear().commit()
        val failing = AudioRoutePreferences(
            context = context,
            commit = { false }
        )

        assertFalse(failing.save(AudioRouteSelection.SPEAKER))
        assertEquals(AudioRouteSelection.BLUETOOTH, preferences.load())
    }

    private companion object {
        const val PREFS_NAME = "motocom_audio_route_preferences"
        const val KEY_SELECTION = "preferred_audio_route"
    }
}
