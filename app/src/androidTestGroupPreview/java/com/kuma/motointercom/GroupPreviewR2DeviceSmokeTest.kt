package com.kuma.motointercom

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuma.motointercom.group.groupRequiredPermissions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupPreviewR2DeviceSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val notifications get() = targetContext.getSystemService(NotificationManager::class.java)

    @Test
    fun r2SingleDeviceLifecycleChannelsNotificationsAndRedactedDiagnostics() {
        ensurePermissions()
        runShell("logcat -c")

        launchGroupActivity()
        waitForCondition(15_000) { exists(hasTag("group_create")) }
        compose.onNodeWithText("随时组队开聊").assertIsDisplayed()
        compose.onNodeWithTag("group_join").assertIsNotEnabled()
        compose.onNodeWithTag("group_code_input").performScrollTo().performTextInput("12345")
        compose.onNodeWithTag("group_join").assertIsNotEnabled()
        compose.onNodeWithTag("group_code_input").performTextInput("6")
        compose.onNodeWithTag("group_join").assertIsEnabled()
        compose.onNodeWithTag("group_code_input").performTextClearance()
        compose.onNodeWithTag("group_join").assertIsNotEnabled()

        compose.onNodeWithTag("group_create").performScrollTo().performClick()
        waitForCondition(30_000) { exists(hasText("房间码 ", substring = true)) && exists(hasTag("channel_rider") and isEnabled()) }

        val firstCode = readRoomCode()
        compose.onNodeWithText("群主 · 我", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("成员列表（1/4）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("group_voice_status").performScrollTo().assertTextContains("当前频道仅你 1 人", substring = true)
        compose.onNodeWithTag("channel_public").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("channel_rider").performScrollTo().assertIsEnabled()

        assertOngoingNotification("摩声群组 · 公共频道", forbiddenCode = firstCode)

        compose.onNodeWithTag("channel_rider").performScrollTo().performClick()
        waitForCondition(10_000) { exists(hasTag("channel_rider") and !isEnabled()) }
        compose.onNodeWithText("频道：领队频道", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("channel_public").performScrollTo().assertIsEnabled()
        assertOngoingNotification("摩声群组 · 领队频道", forbiddenCode = firstCode)

        compose.onNodeWithTag("rider_includes_formation").performScrollTo().performClick()
        waitForCondition(10_000) { exists(hasTag("rider_includes_formation") and isOff()) }
        compose.onNodeWithText("频道：领队频道", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rider_includes_formation").performScrollTo().performClick()
        waitForCondition(10_000) { exists(hasTag("rider_includes_formation") and isOn()) }

        compose.onNodeWithTag("channel_public").performScrollTo().performClick()
        waitForCondition(10_000) { exists(hasTag("channel_public") and !isEnabled()) }
        assertOngoingNotification("摩声群组 · 公共频道", forbiddenCode = firstCode)

        compose.onNodeWithText("收起对讲").performScrollTo().performClick()
        waitForCondition(10_000) { !exists(hasTag("group_leave")) }
        assertOngoingNotification("摩声群组 · 公共频道", forbiddenCode = firstCode)

        launchGroupActivity()
        waitForCondition(15_000) { exists(hasText("房间码 $firstCode")) }
        compose.onNodeWithText("成员列表（1/4）").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        waitForCondition(5_000) { exists(hasText("结束全队对讲？")) }
        compose.onNodeWithText("本地模式不支持转让群主", substring = true).assertIsDisplayed()
        compose.onNodeWithText("继续对讲").performClick()
        waitForCondition(5_000) { !exists(hasText("结束全队对讲？")) }
        compose.onNodeWithText("房间码 $firstCode").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        waitForCondition(5_000) { exists(hasText("结束全队对讲？")) }
        compose.onNodeWithText("结束房间").performClick()
        waitForCondition(25_000) { exists(hasTag("group_create")) }
        assertFalse(exists(hasText("房间码 $firstCode")))
        waitForCondition(10_000) {
            notifications.activeNotifications.none { it.id == GroupIntercomService.NOTIFICATION_ID }
        }
        assertTrue(notifications.activeNotifications.none { it.id == GroupIntercomService.TERMINAL_NOTIFICATION_ID })

        compose.onNodeWithTag("group_create").performScrollTo().performClick()
        waitForCondition(30_000) { exists(hasText("房间码 ", substring = true)) && exists(hasTag("channel_rider") and isEnabled()) }
        val secondCode = readRoomCode()
        assertOngoingNotification("摩声群组 · 公共频道", forbiddenCode = secondCode)

        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        waitForCondition(5_000) { exists(hasText("结束全队对讲？")) }
        compose.onNodeWithText("结束房间").performClick()
        waitForCondition(25_000) { exists(hasTag("group_create")) }
        waitForCondition(10_000) {
            notifications.activeNotifications.none { it.id == GroupIntercomService.NOTIFICATION_ID }
        }

        val logs = runShell("logcat -d -s MotoComGroup")
        assertTrue("Missing group_create diagnostic:\n$logs", logs.contains("group_create "))
        assertTrue("Missing group_channel_switch diagnostic:\n$logs", logs.contains("group_channel_switch "))
        assertTrue("Missing group_role_changed diagnostic:\n$logs", logs.contains("group_role_changed "))
        assertTrue("Missing group_end diagnostic:\n$logs", logs.contains("group_end "))
        assertFalse("First join code leaked into MotoComGroup logs", logs.contains(firstCode))
        assertFalse("Second join code leaked into MotoComGroup logs", logs.contains(secondCode))
    }

    private fun launchGroupActivity() {
        runShell("am start -W -n com.kuma.motointercom.group.preview/com.kuma.motointercom.GroupActivity")
    }

    private fun ensurePermissions() {
        val required = buildList {
            addAll(groupRequiredPermissions())
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val uiAutomation = instrumentation.uiAutomation
        required.forEach { permission ->
            if (targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, permission)
            }
        }
    }

    private fun readRoomCode(): String {
        val text = compose.onNode(hasText("房间码 ", substring = true))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        return checkNotNull(Regex("[0-9]{6}").find(text)?.value) { "Expected 6-digit code in '$text'" }
    }

    private fun assertOngoingNotification(expectedTitle: String, forbiddenCode: String) {
        waitForCondition(10_000) {
            val active = notifications.activeNotifications.firstOrNull { it.id == GroupIntercomService.NOTIFICATION_ID }
            active?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == expectedTitle
        }
        val active = notifications.activeNotifications.first { it.id == GroupIntercomService.NOTIFICATION_ID }.notification
        val title = active.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = active.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertEquals(expectedTitle, title)
        assertEquals(Notification.VISIBILITY_PUBLIC, active.visibility)
        assertTrue((active.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertFalse("Join code leaked into notification title", title.contains(forbiddenCode))
        assertFalse("Join code leaked into notification text", body.contains(forbiddenCode))
    }

    private fun hasTag(tag: String) = hasTestTag(tag)

    private fun exists(matcher: SemanticsMatcher): Boolean = runCatching {
        compose.onAllNodes(matcher, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun waitForCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    private fun runShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }
}
