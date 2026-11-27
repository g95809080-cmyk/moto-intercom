package com.kuma.motointercom

import androidx.compose.runtime.mutableStateOf
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
        compose.onNodeWithText("收起对讲").performClick()
        compose.runOnIdle { assertEquals(1, backs); assertEquals(0, leaves) }
        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        compose.onNodeWithText("结束全队对讲？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, leaves) }
        compose.onNodeWithText("结束房间").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }
    @Test fun ordinaryMemberCannotSeeOrCopyTheRoomCode() {
        val endpoint = GroupAuthEndpoint(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val writer = GroupSessionOrchestrator(endpoint, "房主", { 0 }, {}, {})
        writer.dispatch(GroupSessionEvent.Create)
        val original = writer.snapshot
        val memberView = GroupSessionSnapshot(original.phase, original.operation, original.networkAttempt,
            original.view, original.local, original.participation, original.code, false, original.message,
            original.matches, original.names, original.removed)
        compose.setContent { MotoComTheme { GroupScreen(GroupServiceState(memberView, busy = true),
            {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("房间码 ${memberView.code!!.digits}").assertDoesNotExist()
        compose.onNodeWithText("复制房间码").assertDoesNotExist()
        compose.onNodeWithText("结束全队对讲").assertDoesNotExist()
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

    @Test fun roleControlsFormationToggleAndChannelSwitcherRespectPermissionsAndLock() {
        val hostId = UUID(0, 1).toString()
        val officerId = UUID(0, 2).toString()
        val memberId = UUID(0, 3).toString()
        val roomKey = GroupRoomKey(UUID(0, 10).toString(), UUID(0, 11).toString())
        val hostLease = GroupMemberLease(hostId, UUID(0, 11).toString(), 1, 1)
        val officerLease = GroupMemberLease(officerId, UUID(0, 12).toString(), 2, 1)
        val memberLease = GroupMemberLease(memberId, UUID(0, 13).toString(), 3, 1)
        val roster = GroupRoster(
            key = roomKey,
            hostId = hostId,
            publication = 1,
            ended = false,
            members = listOf(
                GroupMember(hostLease, role = GroupRole.OWNER),
                GroupMember(officerLease, role = GroupRole.RIDING_OFFICER),
                GroupMember(memberLease, role = GroupRole.MEMBER)
            ),
            links = emptyList(),
            names = mapOf(hostId to "队长", officerId to "领队小王", memberId to "新手小李"),
            riderIncludesFormation = true
        )
        val participation = GroupParticipation().begin(roomKey)
        val actions = mutableListOf<GroupSessionEvent>()
        val state = mutableStateOf(
            GroupServiceState(
                GroupSessionSnapshot(
                    GroupPhase.IN_ROOM, UUID.randomUUID(), null, roster, hostLease, participation,
                    GroupJoinCode("654321"), true, "房间已创建", emptyList(), roster.names, emptySet()
                ),
                busy = true
            )
        )
        compose.setContent { MotoComTheme { GroupScreen(state.value, {}, { actions += it }, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("队长 · 群主 · 我").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("领队小王 · 骑行官").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rider_includes_formation").performScrollTo().performClick()
        compose.onNodeWithTag("member_role_$memberId").performScrollTo().performClick()
        compose.onNodeWithTag("role_option_HEAD").performClick()
        compose.onNodeWithTag("group_leave").performScrollTo().performClick()
        compose.onNodeWithText("本地模式不支持转让群主", substring = true).assertIsDisplayed()
        compose.onNodeWithText("继续对讲").performClick()
        compose.runOnIdle {
            assertTrue(actions.any { it is GroupSessionEvent.SetRiderIncludesFormation && !it.included })
            assertTrue(actions.any { it is GroupSessionEvent.SetRole && it.peerId == memberId && it.role == GroupRole.HEAD })
        }

        state.value = GroupServiceState(
            GroupSessionSnapshot(
                GroupPhase.IN_ROOM, UUID.randomUUID(), null, roster, memberLease, participation,
                null, false, "已加入房间", emptyList(), roster.names, emptySet()
            ),
            busy = true
        )
        compose.onNodeWithTag("channel_rider").assertDoesNotExist()
        compose.onNodeWithTag("rider_includes_formation").assertDoesNotExist()
        compose.onNodeWithTag("member_role_$officerId").assertDoesNotExist()
        compose.onNodeWithText("领队频道仅限群主", substring = true).performScrollTo().assertIsDisplayed()

        state.value = GroupServiceState(
            GroupSessionSnapshot(
                GroupPhase.IN_ROOM, UUID.randomUUID(), null, roster, officerLease, participation,
                null, false, "正在切换到领队频道", emptyList(), roster.names, emptySet(),
                channelSwitching = true, pendingSwitchTarget = GroupChannel.Rider
            ),
            busy = true
        )
        compose.onNodeWithTag("channel_switching").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("channel_rider").assertIsNotEnabled()
        compose.onNodeWithTag("channel_public").assertIsNotEnabled()
    }

    @Test fun privateCallIncomingOutgoingAndActiveReservedStatesDispatchExpectedEvents() {
        val hostId = UUID(0, 1).toString()
        val peerId = UUID(0, 2).toString()
        val inviteId = UUID(0, 77).toString()
        val roomKey = GroupRoomKey(UUID(0, 10).toString(), UUID(0, 11).toString())
        val hostLease = GroupMemberLease(hostId, UUID(0, 11).toString(), 1, 1)
        val peerLease = GroupMemberLease(peerId, UUID(0, 12).toString(), 2, 1)
        val participation = GroupParticipation().begin(roomKey)
        val names = mapOf(hostId to "队长", peerId to "队友乙")

        val incomingRoster = GroupRoster(
            roomKey, hostId, 1, false,
            listOf(GroupMember(hostLease, role = GroupRole.OWNER), GroupMember(peerLease)),
            emptyList(), names, true, listOf(GroupPrivateInvite(inviteId, peerId, hostId, 30_000))
        )
        val outgoingRoster = GroupRoster(
            roomKey, hostId, 2, false,
            listOf(GroupMember(hostLease, role = GroupRole.OWNER), GroupMember(peerLease)),
            emptyList(), names, true, listOf(GroupPrivateInvite(inviteId, hostId, peerId, 30_000))
        )
        val activePrivateRoster = GroupRoster(
            roomKey, hostId, 3, false,
            listOf(
                GroupMember(hostLease, role = GroupRole.OWNER, channel = GroupChannel.Private(inviteId), channelRevision = 2),
                GroupMember(peerLease, status = GroupMemberStatus.RESERVED, reservedUntilMs = 60_000,
                    channel = GroupChannel.Private(inviteId), channelRevision = 2)
            ),
            emptyList(), names, true, emptyList()
        )

        val actions = mutableListOf<GroupSessionEvent>()
        val state = mutableStateOf(
            GroupServiceState(
                GroupSessionSnapshot(
                    GroupPhase.IN_ROOM, UUID.randomUUID(), null, incomingRoster, hostLease, participation,
                    GroupJoinCode("123456"), true, "来电", emptyList(), names, emptySet()
                ),
                busy = true
            )
        )
        compose.setContent { MotoComTheme { GroupScreen(state.value, {}, { actions += it }, {}, {}, {}, {}, {}) } }

        compose.onNodeWithTag("private_incoming_card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("private_accept").performScrollTo().performClick()
        compose.onNodeWithTag("private_decline").performScrollTo().performClick()

        state.value = GroupServiceState(
            GroupSessionSnapshot(
                GroupPhase.IN_ROOM, UUID.randomUUID(), null, outgoingRoster, hostLease, participation,
                GroupJoinCode("123456"), true, "呼叫中", emptyList(), names, emptySet()
            ),
            busy = true
        )
        compose.onNodeWithTag("private_outgoing_card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("private_cancel").performScrollTo().performClick()

        state.value = GroupServiceState(
            GroupSessionSnapshot(
                GroupPhase.IN_ROOM, UUID.randomUUID(), null, activePrivateRoster, hostLease, participation,
                GroupJoinCode("123456"), true, "私聊中", emptyList(), names, emptySet()
            ),
            busy = true
        )
        compose.onNodeWithTag("private_active_banner").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("对方（队友乙）重连中", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("private_end").performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(actions.any { it is GroupSessionEvent.AcceptPrivate && it.inviteId == inviteId })
            assertTrue(actions.any { it is GroupSessionEvent.DeclinePrivate && it.inviteId == inviteId })
            assertTrue(actions.any { it is GroupSessionEvent.CancelPrivate && it.inviteId == inviteId })
            assertTrue(actions.any { it == GroupSessionEvent.EndPrivate })
        }
    }
}
