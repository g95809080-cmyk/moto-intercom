package com.kuma.motointercom

import android.content.Context
import android.content.SharedPreferences

internal class AppearancePreferences(
    context: Context,
    private val commitEditor: SharedPreferences.Editor.() -> Boolean = { commit() }
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): MotoComThemeMode {
        val persisted = preferences.getString(KEY_THEME, null)
        return MotoComThemeMode.entries.firstOrNull { it.name == persisted }
            ?: MotoComThemeMode.DAYLIGHT
    }

    fun save(theme: MotoComThemeMode): Boolean {
        val editor = preferences.edit().putString(KEY_THEME, theme.name)
        return commitEditor(editor)
    }

    private companion object {
        const val PREFERENCES_NAME = "motocom_appearance"
        const val KEY_THEME = "theme"
    }
}
