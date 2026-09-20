package com.kuma.motointercom

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.kuma.motointercom.group.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupScreenComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun joinRequiresSixDigitsAndClearsCodeAfterExplicitAction() {
        var requested: String? = null
        compose.setContent { MotoComTheme { GroupScreen(GroupServiceState(), { requested = it }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("group_join").assertIsNotEnabled()
        compose.onNodeWithTag("group_code_input").performTextInput("123456")
        compose.onNodeWithTag("group_join").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("123456", requested) }
        compose.onNodeWithTag("group_join").assertIsNotEnabled()
    }
    @Test fun hostEndRequiresConfirmationAndBackDoesNotSendLeave() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val writer = GroupSessionOrchestrator(endpoint, "房主", { 0 }, {}, {})
        writer.dispatch(GroupSessionEvent.Create)
        var leaves = 0; var backs = 0
        compose.setContent { MotoComTheme { GroupScreen(GroupServiceState(writer.snapshot, busy = true), {},
            { if (it == GroupSessionEvent.Leave) leaves++ }, {}, {}, { backs++ }, {}, {}) } }
        compose.onNodeWithText("返回主页").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, leaves) }
        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        compose.onNodeWithText("结束全队对讲？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, leaves) }
        compose.onNodeWithText("结束房间").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }
    @Test fun unreadyPairsIdentifyBothMembers() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val writer = GroupSessionOrchestrator(endpoint, "阿甲", { 0 }, {}, {})
        writer.dispatch(GroupSessionEvent.Create)
        val socket = UUID.randomUUID()
        writer.dispatch(GroupSessionEvent.Authenticated(writer.snapshot.operation!!, socket,
            GroupAuthContext(writer.snapshot.view!!.key, endpoint,
                GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString()), UUID.randomUUID().toString()), 0))
        writer.dispatch(GroupSessionEvent.Control(socket, GroupControl.Join("阿乙")))
        compose.setContent { MotoComTheme { GroupScreen(GroupServiceState(writer.snapshot, busy = true), {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("全队语音已就绪").assertDoesNotExist()
        compose.onAllNodes(hasText("↔", substring = true)).onFirst().performScrollTo().assertIsDisplayed()
    }
}
