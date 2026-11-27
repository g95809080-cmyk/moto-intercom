package com.kuma.motointercom.group.cloud

import android.content.Context
import androidx.core.content.edit
import com.kuma.motointercom.BuildConfig

/** Stores only an optional debug cloud URL; never persists RTC/session tokens or invite codes. */
internal class GroupCloudPreferences(
    context: Context,
    private val allowCustomServer: Boolean = BuildConfig.DEBUG
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val removeCurrent = !allowCustomServer && prefs.contains(KEY_SERVER_URL)
        if (removeCurrent || prefs.contains(LEGACY_KEY_SERVER_URL)) {
            prefs.edit {
                if (removeCurrent) remove(KEY_SERVER_URL)
                remove(LEGACY_KEY_SERVER_URL)
            }
        }
    }

    fun loadServerUrl(): String {
        if (!allowCustomServer) return DEFAULT_SERVER_URL
        val saved = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
            ?: return DEFAULT_SERVER_URL
        return runCatching { GroupCloudUrlPolicy.normalizeConfiguredUrl(saved) }
            .getOrDefault(DEFAULT_SERVER_URL)
    }

    fun saveServerUrl(url: String) {
        if (!allowCustomServer) {
            resetServerUrl()
            return
        }
        val normalized = GroupCloudUrlPolicy.normalizeConfiguredUrl(url)
        prefs.edit { putString(KEY_SERVER_URL, normalized) }
    }

    fun resetServerUrl() {
        prefs.edit { remove(KEY_SERVER_URL) }
    }

    companion object {
        private const val PREFS_NAME = "motocom_group_cloud_prefs"
        private const val KEY_SERVER_URL = "developer_server_url"
        private const val LEGACY_KEY_SERVER_URL = "server_url"
        val DEFAULT_SERVER_URL: String = BuildConfig.GROUP_CLOUD_BASE_URL
    }
}
