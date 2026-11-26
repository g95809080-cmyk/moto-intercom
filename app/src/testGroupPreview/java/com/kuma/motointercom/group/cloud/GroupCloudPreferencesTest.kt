package com.kuma.motointercom.group.cloud

import android.content.Context
import com.kuma.motointercom.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupCloudPreferencesTest {
    @Test
    fun productionBuildIgnoresAndClearsPersistedOverride() {
        val context = RuntimeEnvironment.getApplication()
        val stored = sharedPreferences(context)
        stored.edit()
            .putString("developer_server_url", "http://127.0.0.1:8443")
            .putString("server_url", "https://old.example")
            .apply()

        val preferences = GroupCloudPreferences(context, allowCustomServer = false)

        assertEquals(BuildConfig.GROUP_CLOUD_BASE_URL, preferences.loadServerUrl())
        assertFalse(stored.contains("server_url"))
        assertFalse(stored.contains("developer_server_url"))
        preferences.saveServerUrl("https://evil.example")
        assertFalse(stored.contains("developer_server_url"))
    }

    @Test
    fun debugBuildNormalizesPersistsAndResetsOverride() {
        val context = RuntimeEnvironment.getApplication()
        sharedPreferences(context).edit().clear().apply()
        val preferences = GroupCloudPreferences(context, allowCustomServer = true)
        assertEquals(BuildConfig.GROUP_CLOUD_BASE_URL, preferences.loadServerUrl())

        preferences.saveServerUrl("http://127.0.0.1:8443/")

        assertEquals("http://127.0.0.1:8443", GroupCloudPreferences(context, true).loadServerUrl())
        preferences.resetServerUrl()
        assertEquals(BuildConfig.GROUP_CLOUD_BASE_URL, preferences.loadServerUrl())
    }

    @Test
    fun invalidPersistedOverrideFallsBackToBuiltInServer() {
        val context = RuntimeEnvironment.getApplication()
        sharedPreferences(context).edit()
            .clear()
            .putString("developer_server_url", "http://192.168.1.20:8443")
            .apply()

        assertEquals(
            BuildConfig.GROUP_CLOUD_BASE_URL,
            GroupCloudPreferences(context, allowCustomServer = true).loadServerUrl()
        )
    }

    private fun sharedPreferences(context: Context) =
        context.getSharedPreferences("motocom_group_cloud_prefs", Context.MODE_PRIVATE)
}
