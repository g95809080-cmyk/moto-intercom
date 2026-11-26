package com.kuma.motointercom

import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.cloud.GroupCloudPreferences
import com.kuma.motointercom.group.cloud.GroupCloudUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupReleaseCloudPolicyTest {
    @Test
    fun releaseBuildUsesOnlyBuiltInHttpsEndpoint() {
        assertFalse(BuildConfig.DEBUG)
        assertEquals("https://group.toneride.top", BuildConfig.GROUP_CLOUD_BASE_URL)
        assertEquals(
            "https://group.toneride.top",
            GroupCloudUrlPolicy.normalizeConfiguredUrl("https://group.toneride.top/")
        )
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeConfiguredUrl("https://evil.example")
        }
        assertThrows(IllegalArgumentException::class.java) {
            GroupCloudUrlPolicy.normalizeConfiguredUrl("http://127.0.0.1:8443")
        }
        assertEquals(
            "https://group.toneride.top",
            GroupCloudPreferences(RuntimeEnvironment.getApplication()).loadServerUrl()
        )
        assertFalse(showDeveloperCloudSettings(BuildConfig.DEBUG, GroupMode.NETWORK))
        assertFalse(showDeveloperCloudSettings(BuildConfig.DEBUG, GroupMode.HYBRID))
    }
}
