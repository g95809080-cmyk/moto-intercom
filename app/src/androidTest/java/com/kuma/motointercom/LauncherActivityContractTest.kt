package com.kuma.motointercom

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherActivityContractTest {
    @Test
    fun launcherResolvesDirectlyToMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val launchers = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL,
        )

        assertEquals(1, launchers.size)
        val activityInfo = launchers.single().activityInfo
        assertEquals("${context.packageName}.MainActivity", activityInfo.name)
        assertTrue(activityInfo.exported)
    }
}
