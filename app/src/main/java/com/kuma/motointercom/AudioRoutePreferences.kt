package com.kuma.motointercom

import android.content.Context
import android.content.SharedPreferences

internal class AudioRoutePreferences(
    context: Context,
    private val commit: SharedPreferences.Editor.() -> Boolean = { commit() }
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): AudioRouteSelection = audioRouteSelectionFromPersisted(
        preferences.getString(KEY_SELECTION, null)
    )

    fun save(selection: AudioRouteSelection): Boolean {
        val editor = preferences.edit().putString(KEY_SELECTION, selection.name)
        return commit(editor)
    }

    private companion object {
        const val PREFERENCES_NAME = "motocom_audio_route_preferences"
        const val KEY_SELECTION = "preferred_audio_route"
    }
}
