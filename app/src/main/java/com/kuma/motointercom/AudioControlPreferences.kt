package com.kuma.motointercom

import android.content.Context

internal class AudioControlPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): AudioControlSettings = AudioControlSettings(
        muted = false,
        voxEnabled = preferences.getBoolean(KEY_VOX_ENABLED, true),
        voxSensitivity = preferences.getInt(KEY_VOX_SENSITIVITY, DEFAULT_VOX_SENSITIVITY)
    ).normalized()

    fun saveVoxSettings(settings: AudioControlSettings): Boolean {
        val normalized = settings.normalized()
        return preferences.edit()
            .putBoolean(KEY_VOX_ENABLED, normalized.voxEnabled)
            .putInt(KEY_VOX_SENSITIVITY, normalized.voxSensitivity)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "moto_intercom"
        const val KEY_VOX_ENABLED = "vox_enabled"
        const val KEY_VOX_SENSITIVITY = "vox_sensitivity"
    }
}
