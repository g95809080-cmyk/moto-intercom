package com.kuma.motointercom.group.cloud

import android.content.Context
import androidx.core.content.edit

/** Stores only the non-secret cloud server URL; never persists RTC/session tokens or invite codes. */
internal class GroupCloudPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadServerUrl(): String = prefs.getString(KEY_SERVER_URL, DEFAULT_DEV_SERVER_URL).orEmpty()

    fun saveServerUrl(url: String) {
        prefs.edit { putString(KEY_SERVER_URL, url.trim()) }
    }

    companion object {
        private const val PREFS_NAME = "motocom_group_cloud_prefs"
        private const val KEY_SERVER_URL = "server_url"
        const val DEFAULT_DEV_SERVER_URL = "http://127.0.0.1:8443"
    }
}
