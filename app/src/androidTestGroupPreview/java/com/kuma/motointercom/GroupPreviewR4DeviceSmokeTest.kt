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
import com.kuma.motointercom.group.cloud.CloudJoinPollResult
import com.kuma.motointercom.group.cloud.CloudJoinSubmission
import com.kuma.motointercom.group.cloud.GroupCloudEvent
import com.kuma.motointercom.group.cloud.GroupCloudInviteCode
import com.kuma.motointercom.group.cloud.HttpGroupCloudClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupPreviewR4DeviceSmokeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext
    private val notifications get() = targetContext.getSystemService(NotificationManager::class.java)

    @Test
    fun r4NetworkSingleDeviceWithRemotePeerApprovalRotateResumeAndOwnerTransfer() {
        ensureNetworkPermissionsOnly()
        runShell("logcat -c")

        val remoteClient = HttpGroupCloudClient(LOOPBACK_CLOUD_URL)
        var remoteTokenToCheck = ""
        var firstInvite = ""
        var secondInvite = ""
        try {
            launchGroupActivity()
            waitForCondition(15_000) { exists(hasTag("mode_network")) }
            compose.onNodeWithText("摩声群组 · R5 预览").assertIsDisplayed()

            compose.onNodeWithTag("mode_network").performScrollTo().performClick()
            waitForCondition(5_000) { exists(hasTag("network_create")) }
            compose.onNodeWithText("网络群组 · R5 预览").assertIsDisplayed()
            compose.onNodeWithTag("network_join").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("network_server_input").performScrollTo().performTextClearance()
            compose.onNodeWithTag("network_server_input").performScrollTo().performTextInput(LOOPBACK_CLOUD_URL)

            compose.onNodeWithTag("network_create").performScrollTo().performClick()
            waitForCondition(20_000) { exists(hasTag("network_invite_code")) }

            firstInvite = readInviteCode()
            compose.onNodeWithTag("channel_rider").assertDoesNotExist()
            compose.onNodeWithText("群主 · 我", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("成员列表（1/50）").performScrollTo().assertIsDisplayed()
            assertOngoingNotification("摩声网络群组 · 公共频道", forbiddenSecrets = listOf(firstInvite))

            val submission = remoteClient.submitJoinRequest(
                inviteCode = GroupCloudInviteCode(firstInvite),
                deviceId = REMOTE_DEVICE_ID,
                runtimeId = "run-remote-smoke-1",
                nickname = "二号远端车"
            )
            assertTrue("Expected Pending approval state, got $submission", submission is CloudJoinSubmission.Pending)
            val pending = submission as CloudJoinSubmission.Pending

            waitForCondition(15_000) { exists(hasTag("approve_${pending.requestId}")) }
            compose.onNodeWithTag("approve_${pending.requestId}").performScrollTo().performClick()

            val pollResult = remoteClient.pollJoinRequest(pending.requestId, pending.requestSecret, waitMs = 10_000)
            assertTrue("Expected Admitted after owner approval, got $pollResult", pollResult is CloudJoinPollResult.Admitted)
            val remoteSession = (pollResult as CloudJoinPollResult.Admitted).session
            remoteTokenToCheck = remoteSession.sessionToken
            assertNull("Non-owner must not receive invite code", remoteSession.snapshot.inviteCode)
            val remoteRtc = checkNotNull(remoteSession.rtc) { "Expected Volc RTC credentials in NETWORK mode" }
            assertTrue("Expected Volc RTC AppID", remoteRtc.appId.isNotBlank())
            assertTrue("Expected Volc RTC token", remoteRtc.rtcToken.isNotBlank())
            assertEquals(50, remoteSession.snapshot.view.maxMembers)
            assertTrue(remoteSession.snapshot.view.links.isEmpty())

            waitForCondition(15_000) { exists(hasText("成员列表（2/50）")) }
            compose.onNodeWithText("二号远端车 · 普通成员").performScrollTo().assertIsDisplayed()
            waitForCondition(30_000) {
                exists(hasTestTag("group_voice_status") and hasText("火山引擎 RTC 语音已连接"))
            }
            compose.onNodeWithTag("group_voice_status").assertTextEquals("火山引擎 RTC 语音已连接")
            compose.onNodeWithTag("private_call_$REMOTE_DEVICE_ID").assertDoesNotExist()
            compose.onNodeWithTag("channel_rider").assertDoesNotExist()

            compose.onNodeWithTag("network_rotate_invite").performScrollTo().performClick()
            waitForCondition(10_000) {
                runCatching { readInviteCode() }.getOrNull()?.let { it != firstInvite } == true
            }
            secondInvite = readInviteCode()
            assertNotEquals(firstInvite, secondInvite)

            compose.onNodeWithText("收起对讲").performScrollTo().performClick()
            waitForCondition(10_000) { !exists(hasTag("group_leave")) }
            assertOngoingNotification("摩声网络群组 · 公共频道", forbiddenSecrets = listOf(firstInvite, secondInvite))

            launchGroupActivity()
            waitForCondition(15_000) { exists(hasTag("network_invite_code")) }
            assertEquals(secondInvite, readInviteCode())
            compose.onNodeWithText("成员列表（2/50）").performScrollTo().assertIsDisplayed()

            compose.onNodeWithTag("group_leave").performScrollTo().performClick()
            waitForCondition(10_000) { exists(hasTag("transfer_leave_$REMOTE_DEVICE_ID")) }
            compose.onNodeWithText("群主离开方式").assertIsDisplayed()
            compose.onNodeWithTag("transfer_leave_$REMOTE_DEVICE_ID").assertIsDisplayed().performClick()

            waitForCondition(20_000) { exists(hasTag("mode_network")) }
            waitForCondition(10_000) {
                notifications.activeNotifications.none { it.id == GroupIntercomService.NOTIFICATION_ID }
            }
            assertTrue(notifications.activeNotifications.none { it.id == GroupIntercomService.TERMINAL_NOTIFICATION_ID })

            val remoteBatch = remoteClient.pollEvents(
                token = remoteSession.sessionToken,
                roomId = remoteSession.roomId,
                afterSeq = remoteSession.latestSeq,
                waitMs = 5_000
            )
            val promotedSnap = remoteBatch.events.filterIsInstance<GroupCloudEvent.Snapshot>().lastOrNull()?.payload
            assertNotNull("Remote peer should receive promoted owner snapshot", promotedSnap)
            assertEquals(REMOTE_DEVICE_ID, promotedSnap!!.view.ownerId)
            assertNotNull("New owner should receive active invite code", promotedSnap.inviteCode)

            remoteClient.endRoom(remoteSession.sessionToken, remoteSession.roomId, promotedSnap.view.revision)

            val logs = runShell("logcat -d -s MotoComGroup")
            assertTrue("Missing group_cloud_create diagnostic:\n$logs", logs.contains("group_cloud_create "))
            assertTrue("Missing group_cloud_approval diagnostic:\n$logs", logs.contains("group_cloud_approval "))
            assertTrue("Missing group_cloud_owner_transfer diagnostic:\n$logs", logs.contains("group_cloud_owner_transfer "))
            assertTrue("Missing group_voice_ready diagnostic:\n$logs", logs.contains("group_voice_ready "))
            assertTrue("Missing group_end diagnostic:\n$logs", logs.contains("group_end "))
            assertFalse("First invite leaked into logs", logs.contains(firstInvite))
            assertFalse("Second invite leaked into logs", logs.contains(secondInvite))
            assertFalse("Remote session token leaked into logs", logs.contains(remoteTokenToCheck))
        } finally {
            remoteClient.close()
        }
    }

    private fun launchGroupActivity() {
        runShell("am start -W -n com.kuma.motointercom.group.preview/com.kuma.motointercom.GroupActivity")
    }

    private fun ensureNetworkPermissionsOnly() {
        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val uiAutomation = instrumentation.uiAutomation
        required.forEach { permission ->
            if (targetContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, permission)
            }
        }
    }

    private fun readInviteCode(): String {
        val text = compose.onNodeWithTag("network_invite_code")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        return checkNotNull(Regex("[0-9]{6}").find(text)?.value) { "Expected 6-digit invite code in '$text'" }
    }

    private fun assertOngoingNotification(expectedTitle: String, forbiddenSecrets: List<String>) {
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
        forbiddenSecrets.filter { it.isNotBlank() }.forEach { secret ->
            assertFalse("Secret leaked into notification title", title.contains(secret))
            assertFalse("Secret leaked into notification text", body.contains(secret))
        }
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

    companion object {
        private const val LOOPBACK_CLOUD_URL = "http://127.0.0.1:8443"
        private const val REMOTE_DEVICE_ID = "dev-remote-smoke"
    }
}
