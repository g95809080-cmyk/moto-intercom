package com.kuma.motointercom.group.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.MotoComThemeMode
import com.kuma.motointercom.group.GroupChannel
import com.kuma.motointercom.group.GroupMember
import com.kuma.motointercom.group.GroupMemberLease
import com.kuma.motointercom.group.GroupMemberStatus
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.GroupNetworkMediaState
import com.kuma.motointercom.group.GroupPhase
import com.kuma.motointercom.group.GroupRole
import com.kuma.motointercom.group.GroupRoomView
import com.kuma.motointercom.group.GroupServiceState
import com.kuma.motointercom.group.GroupSessionEvent
import com.kuma.motointercom.group.GroupSessionSnapshot
import com.kuma.motointercom.group.GroupTransport

internal data class GovernanceMemberTarget(
    val deviceId: String,
    val incarnation: Long
) {
    companion object {
        fun from(lease: GroupMemberLease): GovernanceMemberTarget =
            GovernanceMemberTarget(deviceId = lease.deviceId, incarnation = lease.incarnation)
    }
}

internal sealed interface GroupInRoomSheet {
    data object AudioRoute : GroupInRoomSheet
    data class Governance(
        val selectedTarget: GovernanceMemberTarget? = null,
        val selectionInvalidated: Boolean = false
    ) : GroupInRoomSheet
}

@Composable
internal fun GroupInRoomContent(
    state: GroupServiceState,
    snapshot: GroupSessionSnapshot?,
    room: GroupRoomView?,
    themeMode: MotoComThemeMode,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
    onAction: (GroupSessionEvent) -> Unit,
    onOpenSheet: (GroupInRoomSheet) -> Unit,
    onRequestLeaveDialog: () -> Unit,
    onSelectRoleTarget: (String) -> Unit
) {
    val colors = GroupGlassPalette.current
    val isNetwork = snapshot?.mode == GroupMode.NETWORK
    val isHybrid = snapshot?.mode == GroupMode.HYBRID
    val isCloudBacked = isNetwork || isHybrid
    val isCloudVoiceActive = isNetwork || (isHybrid && snapshot?.activeTransport == GroupTransport.CLOUD)
    val inRoom = snapshot?.phase == GroupPhase.IN_ROOM
    val actionsEnabled = inRoom && snapshot?.channelSwitching != true &&
        snapshot?.transportSwitching != true && snapshot?.cloudOffline != true
    val occupiedMembers = remember(room, snapshot?.activeSpeakerIds, snapshot?.local?.deviceId, snapshot?.names) {
        room?.members
            ?.filter { it.status != GroupMemberStatus.WAITING }
            ?.sortedWith(
                compareBy<GroupMember> { it.lease.deviceId != room.ownerId }
                    .thenBy { it.lease.deviceId !in (snapshot?.activeSpeakerIds ?: emptySet()) }
                    .thenBy { it.lease.deviceId != snapshot?.local?.deviceId }
                    .thenBy { snapshot?.names?.get(it.lease.deviceId).orEmpty() }
            )
            .orEmpty()
    }
    var showAllMembers by remember { mutableStateOf(false) }
    var showAllPending by remember { mutableStateOf(false) }
    var codeExpanded by remember(snapshot?.view?.key?.instanceId, snapshot?.mode) { mutableStateOf(true) }

    val headerTitle = when {
        isNetwork -> "网络群组"
        isHybrid -> "双模群组"
        else -> "本地群组"
    }
    val heroCodeDigits = when {
        isCloudBacked -> snapshot?.inviteCode?.takeIf { snapshot.isOwner }
        else -> snapshot?.code?.takeIf { snapshot.host }?.digits
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = onBack),
                    shape = RoundedCornerShape(999.dp),
                    color = colors.cardElevated,
                    border = BorderStroke(1.dp, colors.glassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GlassVectorIcon(kind = GlassIconKind.BACK, tint = colors.textPrimary, size = 16.dp)
                        Text(
                            text = "收起对讲",
                            style = GroupGlassTypography.badge,
                            color = colors.textPrimary
                        )
                    }
                }
                Text(
                    text = headerTitle,
                    style = GroupGlassTypography.pageTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                GlassIconCircleButton(
                    icon = if (themeMode == MotoComThemeMode.NIGHTRIDE) GlassIconKind.SUN else GlassIconKind.MOON,
                    contentDescription = if (themeMode == MotoComThemeMode.NIGHTRIDE) "切换到白昼模式" else "切换到夜航模式",
                    onClick = onToggleTheme,
                    modifier = Modifier.testTag("theme_toggle")
                )
                if (room != null) {
                    GlassIconCircleButton(
                        icon = GlassIconKind.MORE,
                        contentDescription = "房间治理与设置",
                        onClick = {
                            val defaultTarget = occupiedMembers.firstOrNull {
                                it.lease.deviceId != snapshot?.local?.deviceId
                            }?.lease?.let(GovernanceMemberTarget::from)
                            onOpenSheet(GroupInRoomSheet.Governance(defaultTarget))
                        },
                        modifier = Modifier.testTag("open_governance_sheet")
                    )
                } else {
                    GlassIconCircleButton(
                        icon = GlassIconKind.SETTINGS,
                        contentDescription = "打开系统设置",
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("open_settings")
                    )
                }
            }
        }

        if (heroCodeDigits != null && snapshot != null) {
            RoomCodeHeroCard(
                digits = heroCodeDigits,
                mode = snapshot.mode,
                occupiedCount = occupiedMembers.size,
                maxMembers = snapshot.maxMembers,
                expanded = codeExpanded,
                onToggleExpanded = { codeExpanded = !codeExpanded },
                canRotate = isCloudBacked && snapshot.isOwner,
                rotateEnabled = inRoom && !snapshot.cloudOffline,
                onRotate = { onAction(GroupSessionEvent.RotateInvite) },
                onCopy = { onCopy(heroCodeDigits) }
            )
        }

    snapshot?.matches?.takeIf { it.isNotEmpty() }?.let { matches ->
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("选择附近房间", style = GroupGlassTypography.seatTitle, color = colors.textPrimary)
            matches.forEach { match ->
                GlassActionButton(
                    label = "${match.descriptor.nickname} · ${match.descriptor.room.instanceId.takeLast(8)}",
                    onClick = { onAction(GroupSessionEvent.Select(match.descriptor.room)) },
                    variant = GlassCtaVariant.SECONDARY,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (state.message.isNotBlank()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.glass,
            border = BorderStroke(1.dp, colors.hairlineBorder)
        ) {
            Text(
                text = state.message,
                style = GroupGlassTypography.compact,
                color = colors.textSecondary,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 9.dp)
                    .testTag("group_status")
            )
        }
    } else {
        Text("", Modifier.testTag("group_status").height(0.dp))
    }

    if (room != null && snapshot != null) {
        val partnerName = snapshot.privatePartner?.lease?.deviceId?.let { snapshot.names[it] } ?: "队友"
        val selfBusyPrivate = snapshot.currentChannel is GroupChannel.Private ||
            snapshot.outgoingPrivateInvite != null ||
            snapshot.incomingPrivateInvite != null
        val voiceStatusText = when {
            snapshot.transportSwitching -> "正在切换传输路径，已断开旧语音通道（零双路音频）"
            snapshot.channelSwitching -> "正在切换频道，旧频道语音已断开"
            isNetwork || (isHybrid && snapshot.activeTransport == GroupTransport.CLOUD) -> when (snapshot.networkMediaState) {
                GroupNetworkMediaState.WAITING_FOR_PEER -> if (isHybrid) "当前频道在线 1 人，云端 RTC 待机中，不消耗停留时长"
                else "公共频道在线 1 人，RTC 待机中，不消耗停留时长"
                GroupNetworkMediaState.JOINING -> "正在加入火山引擎 RTC 音频房间"
                GroupNetworkMediaState.JOINED -> if (isHybrid) "双模云端兜底语音已连接" else "火山引擎 RTC 语音已连接"
                GroupNetworkMediaState.FAILED -> "RTC 语音连接失败，正在自动重试"
                GroupNetworkMediaState.IDLE -> "正在准备 RTC 语音凭证"
            }
            snapshot.voiceReady -> when (snapshot.currentChannel) {
                GroupChannel.Public -> if (isHybrid) "本地直连 · 公共频道语音已就绪" else "公共频道语音已就绪"
                GroupChannel.Rider -> if (isHybrid) "本地直连 · 领队频道语音已就绪" else "领队频道语音已就绪"
                is GroupChannel.Private -> if (isHybrid) "本地直连 · 私聊语音已就绪" else "私聊语音已就绪"
            }
            snapshot.channelMembers.size < 2 -> "当前频道仅你 1 人，等待成员进入此频道"
            else -> if (isHybrid) "本地直连语音确认中" else "语音连接确认中"
        }

        if (isHybrid) {
            val isCloudTransport = snapshot.activeTransport == GroupTransport.CLOUD
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hybrid_transport_card"),
                containerColor = if (isCloudTransport) colors.infoSoft else colors.card,
                borderColor = if (isCloudTransport) colors.statusCloud.copy(alpha = 0.35f) else colors.glassBorder,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isCloudTransport) {
                            GlassVectorIcon(kind = GlassIconKind.CLOUD, tint = colors.statusCloud, size = 18.dp)
                        } else {
                            GlassVectorIcon(kind = GlassIconKind.WIFI, tint = colors.statusOnline, size = 18.dp)
                        }
                        Column {
                            Text(
                                text = when {
                                    snapshot.transportSwitching -> "正在切换（零双路保护）"
                                    isCloudTransport -> "云端兜底"
                                    else -> "本地直连"
                                },
                                style = GroupGlassTypography.seatTitle,
                                color = colors.textPrimary,
                                modifier = Modifier.testTag("hybrid_transport_summary")
                            )
                            Text(
                                text = voiceStatusText,
                                style = GroupGlassTypography.badge,
                                color = colors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("group_voice_status")
                            )
                        }
                    }
                    if (snapshot.isOwner) {
                        val targetTransport = if (isCloudTransport) GroupTransport.LOCAL else GroupTransport.CLOUD
                        GlassPillChip(
                            label = if (targetTransport == GroupTransport.CLOUD) "切至云端" else "切回本地",
                            containerColor = if (targetTransport == GroupTransport.LOCAL) colors.speakingSurface else colors.cardElevated,
                            contentColor = if (targetTransport == GroupTransport.LOCAL) colors.textLimeAccent else colors.textPrimary,
                            borderColor = if (targetTransport == GroupTransport.LOCAL) colors.limeActiveBorder else colors.glassBorder,
                            onClick = { onAction(GroupSessionEvent.SwitchTransport(targetTransport)) },
                            enabled = actionsEnabled,
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .testTag("hybrid_switch_transport")
                        )
                    }
                }
                if (snapshot.cloudOffline) {
                    Text(
                        text = "云端暂离线，当前保持本地直连通话，正在后台重连云端",
                        style = GroupGlassTypography.badge,
                        color = colors.textWarning,
                        modifier = Modifier.testTag("hybrid_cloud_offline_banner")
                    )
                }
            }
        } else if (isNetwork) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = colors.infoSoft,
                borderColor = colors.statusCloud.copy(alpha = 0.35f),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassVectorIcon(kind = GlassIconKind.CLOUD, tint = colors.statusCloud, size = 18.dp)
                        Column {
                            Text("云端语音", style = GroupGlassTypography.seatTitle, color = colors.textPrimary)
                            Text(voiceStatusText, style = GroupGlassTypography.badge, color = colors.textSecondary)
                        }
                    }
                    if (snapshot.isOwner && snapshot.pendingJoinRequests.isNotEmpty()) {
                        GlassPillChip(
                            label = "待审批 ${snapshot.pendingJoinRequests.size} >",
                            containerColor = colors.warningSoft,
                            contentColor = colors.textWarning,
                            borderColor = colors.warningBorder,
                            onClick = { onOpenSheet(GroupInRoomSheet.Governance()) }
                        )
                    }
                }
            }
        } else {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassVectorIcon(kind = GlassIconKind.WIFI, tint = colors.statusOnline, size = 17.dp)
                    Text(
                        text = voiceStatusText,
                        style = GroupGlassTypography.compact,
                        color = colors.textSecondary,
                        modifier = Modifier.testTag("group_voice_status")
                    )
                }
            }
        }

        if (!isNetwork) {
            val channelOptions = buildList {
                add(
                    GlassSegmentOption(
                        value = "PUBLIC",
                        label = "公共",
                        testTag = "channel_public",
                        enabled = actionsEnabled && snapshot.currentChannel != GroupChannel.Public,
                        dotColor = colors.brandLimeDot
                    )
                )
                if (snapshot.canEnterRider) {
                    add(
                        GlassSegmentOption(
                            value = "RIDER",
                            label = "领队",
                            testTag = "channel_rider",
                            enabled = actionsEnabled && snapshot.currentChannel != GroupChannel.Rider,
                            dotColor = colors.statusCloud
                        )
                    )
                }
                add(
                    GlassSegmentOption(
                        value = "PRIVATE",
                        label = "私聊",
                        testTag = "channel_private_tab",
                        enabled = snapshot.currentChannel is GroupChannel.Private,
                        dotColor = colors.statusPrivate
                    )
                )
            }
            val selectedChannelKey = when (snapshot.currentChannel) {
                GroupChannel.Public -> "PUBLIC"
                GroupChannel.Rider -> "RIDER"
                is GroupChannel.Private -> "PRIVATE"
            }
            GlassSegmentedControl(
                options = channelOptions,
                selected = selectedChannelKey,
                onSelect = { key ->
                    when (key) {
                        "PUBLIC" -> onAction(GroupSessionEvent.SwitchChannel(GroupChannel.Public))
                        "RIDER" -> onAction(GroupSessionEvent.SwitchChannel(GroupChannel.Rider))
                    }
                }
            )
            if (!snapshot.canEnterRider) {
                Text(
                    text = if (snapshot.riderIncludesFormation) {
                        "领队频道仅限群主、骑行官、头车与尾车进入"
                    } else {
                        "领队频道仅限群主与骑行官进入"
                    },
                    style = GroupGlassTypography.badge,
                    color = colors.textMuted,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (snapshot.channelSwitching) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("channel_switching"),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.warningSoft,
                    border = BorderStroke(1.dp, colors.warningBorder)
                ) {
                    Text(
                        text = "正在切换到${snapshot.pendingSwitchTarget?.displayLabel() ?: "目标频道"}，旧频道语音已断开",
                        style = GroupGlassTypography.compact,
                        color = colors.textWarning,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }

        if (snapshot.currentChannel is GroupChannel.Private) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("private_active_banner"),
                containerColor = colors.privateSurface,
                borderColor = colors.privateActiveBorder,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        GlassVectorIcon(kind = GlassIconKind.LOCK, tint = colors.statusPrivate, size = 17.dp)
                        Column {
                            Text(
                                text = "私聊 · $partnerName",
                                style = GroupGlassTypography.seatTitle,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (snapshot.privatePartner?.status == GroupMemberStatus.RESERVED) {
                                    "对方（$partnerName）重连中，席位暂时保留 60 秒"
                                } else {
                                    "仅双方可听，其他队员听不到此频道"
                                },
                                style = GroupGlassTypography.badge,
                                color = colors.textPrivate
                            )
                        }
                    }
                    GlassPillChip(
                        label = "结束私聊",
                        containerColor = colors.dangerSoft,
                        contentColor = colors.textDanger,
                        borderColor = colors.dangerBorder,
                        onClick = { onAction(GroupSessionEvent.EndPrivate) },
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("private_end")
                    )
                }
            }
        }

        snapshot.incomingPrivateInvite?.let { invite ->
            val callerName = snapshot.names[invite.callerId] ?: "骑士"
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("private_incoming_card"),
                containerColor = NightRideGroupGlassColors.cardElevated,
                borderColor = NightRideGroupGlassColors.privateActiveBorder,
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        RiderAvatar(seed = invite.callerId, name = callerName, online = true, reconnecting = false, size = 36.dp)
                        Text(
                            text = "$callerName 邀请私聊",
                            style = GroupGlassTypography.cardTitle,
                            color = NightRideGroupGlassColors.textPrimary
                        )
                    }
                    GlassPillChip(
                        label = "私聊来电",
                        containerColor = NightRideGroupGlassColors.privateSurface,
                        contentColor = NightRideGroupGlassColors.textPrivate
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassActionButton(
                        label = "拒绝",
                        onClick = { onAction(GroupSessionEvent.DeclinePrivate(invite.inviteId)) },
                        enabled = inRoom,
                        variant = GlassCtaVariant.DANGER_SOFT,
                        minHeight = 48.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("private_decline")
                    )
                    GlassActionButton(
                        label = "接受",
                        onClick = { onAction(GroupSessionEvent.AcceptPrivate(invite.inviteId)) },
                        enabled = actionsEnabled,
                        variant = GlassCtaVariant.LIME,
                        minHeight = 48.dp,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("private_accept")
                    )
                }
            }
        }

        snapshot.outgoingPrivateInvite?.let { invite ->
            val calleeName = snapshot.names[invite.calleeId] ?: "骑士"
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("private_outgoing_card"),
                containerColor = colors.privateSurface,
                borderColor = colors.privateActiveBorder,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("正在呼叫 $calleeName 私聊", style = GroupGlassTypography.seatTitle, color = colors.textPrimary)
                        Text("等待对方接受（30 秒内有效）", style = GroupGlassTypography.badge, color = colors.textSecondary)
                    }
                    GlassPillChip(
                        label = "取消呼叫",
                        containerColor = colors.cardElevated,
                        contentColor = colors.textDanger,
                        borderColor = colors.dangerBorder,
                        onClick = { onAction(GroupSessionEvent.CancelPrivate(invite.inviteId)) },
                        enabled = inRoom,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .testTag("private_cancel")
                    )
                }
            }
        }

        if (isCloudBacked && snapshot.isOwner && snapshot.pendingJoinRequests.isNotEmpty()) {
            val visiblePending = if (showAllPending || snapshot.pendingJoinRequests.size <= 3) {
                snapshot.pendingJoinRequests
            } else {
                snapshot.pendingJoinRequests.take(3)
            }
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = colors.speakingSurface,
                borderColor = colors.limeActiveBorder,
                cornerRadius = 20.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                visiblePending.forEach { request ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassPillChip(
                                label = "待审批",
                                containerColor = colors.brandLime,
                                contentColor = colors.textOnLime
                            )
                            Text(
                                text = "${request.nickname} 申请入房",
                                style = GroupGlassTypography.seatTitle,
                                color = colors.textPrimary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassActionButton(
                                label = "同意",
                                onClick = { onAction(GroupSessionEvent.ApproveJoin(request.requestId)) },
                                enabled = inRoom && occupiedMembers.size < snapshot.maxMembers,
                                variant = GlassCtaVariant.OBSIDIAN,
                                minHeight = 46.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("approve_${request.requestId}")
                            )
                            GlassActionButton(
                                label = "拒绝",
                                onClick = { onAction(GroupSessionEvent.RejectJoin(request.requestId)) },
                                enabled = inRoom,
                                variant = GlassCtaVariant.SECONDARY,
                                minHeight = 46.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reject_${request.requestId}")
                            )
                        }
                    }
                }
                if (snapshot.pendingJoinRequests.size > visiblePending.size || showAllPending) {
                    TextButton(
                        onClick = { showAllPending = !showAllPending },
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) {
                        Text(if (showAllPending) "收起申请" else "展开全部 ${snapshot.pendingJoinRequests.size} 条申请")
                    }
                }
            }
        }

        val activeSpeakers = occupiedMembers.filter { it.lease.deviceId in snapshot.activeSpeakerIds }
        if (snapshot.activeSpeakerIds.isNotEmpty()) {
            Text(
                text = "正在发言：" + snapshot.activeSpeakerIds.joinToString("、") {
                    snapshot.names[it] ?: "骑士"
                },
                style = GroupGlassTypography.seatTitle,
                color = colors.textPrimary,
                modifier = Modifier.testTag("network_active_speakers")
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                activeSpeakers.forEach { speaker ->
                    val speakerId = speaker.lease.deviceId
                    val speakerName = snapshot.names[speakerId] ?: "骑士"
                    val isOwnerSpeaker = speaker.role == GroupRole.OWNER
                    GlassCard(
                        modifier = Modifier.width(106.dp),
                        containerColor = if (isOwnerSpeaker) colors.speakingSurface else colors.infoSoft,
                        borderColor = if (isOwnerSpeaker) colors.limeActiveBorder else colors.statusCloud.copy(alpha = 0.4f),
                        cornerRadius = 18.dp,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RiderAvatar(
                                seed = speakerId,
                                name = speakerName,
                                online = true,
                                reconnecting = false,
                                size = 40.dp
                            )
                            Text(
                                text = speakerName,
                                style = GroupGlassTypography.pill,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            GlassWaveformBars(
                                color = if (isOwnerSpeaker) colors.statusOnline else colors.statusCloud,
                                animated = true
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "成员列表（${occupiedMembers.size}/${snapshot.maxMembers}）",
                style = GroupGlassTypography.seatTitle,
                color = colors.textPrimary,
                modifier = Modifier.testTag("group_member_count")
            )
            if (occupiedMembers.size > 12) {
                TextButton(
                    onClick = { showAllMembers = !showAllMembers },
                    modifier = Modifier.testTag("toggle_all_members")
                ) {
                    Text(if (showAllMembers) "收起" else "展开全部 >")
                }
            }
        }

        val visibleMembers = if (showAllMembers || occupiedMembers.size <= 12) {
            occupiedMembers
        } else {
            occupiedMembers.take(12)
        }
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
        ) {
            visibleMembers.forEach { member ->
                UnifiedMemberRow(
                    member = member,
                    room = room,
                    snapshot = snapshot,
                    selfBusyPrivate = selfBusyPrivate,
                    actionsEnabled = actionsEnabled,
                    onAction = onAction,
                    onSelectRole = { onSelectRoleTarget(member.lease.deviceId) },
                    onOpenGovernance = {
                        onOpenSheet(GroupInRoomSheet.Governance(GovernanceMemberTarget.from(member.lease)))
                    }
                )
            }
            val remainingSeats = snapshot.maxMembers - occupiedMembers.size
            if (remainingSeats > 0 && heroCodeDigits != null) {
                val formattedCode = formatThreePlusThree(heroCodeDigits)
                val inviteHint = when (snapshot.mode) {
                    GroupMode.NETWORK -> "复制 $formattedCode 发给车友 · 最多支持 ${snapshot.maxMembers} 人远程对讲"
                    GroupMode.HYBRID -> "分享 $formattedCode 邀请车友 · 当前还可加入 $remainingSeats 人"
                    GroupMode.LOCAL -> "报出 $formattedCode 邀请车友 · 当前还可加入 $remainingSeats 人"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { onCopy(heroCodeDigits) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isNetwork) colors.infoSoft else colors.codeCell,
                    border = BorderStroke(1.dp, if (isNetwork) colors.statusCloud.copy(alpha = 0.35f) else colors.hairlineBorder)
                ) {
                    Text(
                        text = inviteHint,
                        style = GroupGlassTypography.badge,
                        color = if (isNetwork) colors.textCloud else colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }

        if (!isNetwork && (!isHybrid || snapshot.activeTransport == GroupTransport.LOCAL)) {
            val unreadyLinks = room.links.filter {
                !it.confirmedBy.containsAll(listOf(it.lease.pair.first, it.lease.pair.second))
            }
            if (unreadyLinks.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = colors.warningSoft,
                    borderColor = colors.warningBorder,
                    cornerRadius = 16.dp,
                    contentPadding = PaddingValues(12.dp)
                ) {
                    unreadyLinks.forEach { link ->
                        Text(
                            text = "${snapshot.names[link.lease.pair.first] ?: "骑士"} ↔ " +
                                "${snapshot.names[link.lease.pair.second] ?: "骑士"}：语音待确认",
                            style = GroupGlassTypography.badge,
                            color = colors.textWarning
                        )
                    }
                }
            }
        }

        if (snapshot.isOwner) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                if (!isNetwork) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = snapshot.riderIncludesFormation,
                                enabled = actionsEnabled,
                                role = Role.Switch,
                                onValueChange = { onAction(GroupSessionEvent.SetRiderIncludesFormation(it)) }
                            )
                            .testTag("rider_includes_formation"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "领队含头尾车",
                            style = GroupGlassTypography.body,
                            color = colors.textPrimary
                        )
                        GlassSwitch(
                            checked = snapshot.riderIncludesFormation,
                            onCheckedChange = null,
                            enabled = actionsEnabled
                        )
                    }
                }
                if (isCloudBacked) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = snapshot.requireApproval,
                                enabled = inRoom,
                                role = Role.Switch,
                                onValueChange = { onAction(GroupSessionEvent.SetRequireApproval(it)) }
                            )
                            .testTag("network_require_approval_room"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "入房需审批",
                            style = GroupGlassTypography.body,
                            color = colors.textPrimary
                        )
                        GlassSwitch(
                            checked = snapshot.requireApproval,
                            onCheckedChange = null,
                            enabled = inRoom
                        )
                    }
                }
                snapshot.removed.forEach { removedId ->
                    GlassActionButton(
                        label = "解除移除限制 · ${snapshot.names[removedId] ?: "骑士"}",
                        onClick = { onAction(GroupSessionEvent.Unblock(removedId)) },
                        variant = GlassCtaVariant.SECONDARY,
                        minHeight = 44.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = colors.dock,
        border = BorderStroke(1.dp, colors.glassBorder),
        shadowElevation = if (colors.isLight) 6.dp else 0.dp
    ) {
        val routeShortLabel = when (state.route) {
            AudioRouteSelection.BLUETOOTH -> "蓝牙头盔"
            AudioRouteSelection.EARPIECE -> "听筒有线"
            AudioRouteSelection.SPEAKER -> "扬声器"
        }
        val audioSubLabel = when {
            isCloudVoiceActive -> "云端直通"
            state.vox -> "VOX 自动开麦"
            else -> "手动开麦"
        }
        val selfMuted = snapshot?.participation?.selfMuted == true
        val isPrivateChannel = snapshot?.currentChannel is GroupChannel.Private
        val micLabel = when {
            selfMuted -> "已静音"
            isPrivateChannel -> "私聊麦开"
            isCloudVoiceActive -> "麦克风开"
            state.vox -> "声控已开"
            else -> "麦克风开"
        }
        val micVariant = when {
            selfMuted -> GlassCtaVariant.DANGER_SOFT
            isPrivateChannel || !colors.isLight -> GlassCtaVariant.LIME
            else -> GlassCtaVariant.OBSIDIAN
        }
        val leaveLabel = if (snapshot?.isOwner == true || snapshot?.host == true) "结束" else "离开"

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            val useStackedDock = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.15f
            val audioPill: @Composable (Modifier) -> Unit = { pillModifier ->
                Surface(
                    modifier = pillModifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { onOpenSheet(GroupInRoomSheet.AudioRoute) }
                        .testTag("open_audio_sheet"),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.codeCell,
                    border = BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        GlassVectorIcon(
                            kind = when (state.route) {
                                AudioRouteSelection.BLUETOOTH -> GlassIconKind.HEADSET
                                AudioRouteSelection.EARPIECE -> GlassIconKind.EARPIECE
                                AudioRouteSelection.SPEAKER -> GlassIconKind.SPEAKER
                            },
                            tint = colors.textPrimary,
                            size = 16.dp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = routeShortLabel,
                                style = GroupGlassTypography.badge,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = audioSubLabel,
                                style = GroupGlassTypography.badge.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            val micButton: @Composable (Modifier) -> Unit = { micModifier ->
                GlassActionButton(
                    label = micLabel,
                    onClick = {
                        if (room != null) {
                            onAction(GroupSessionEvent.Mute(!selfMuted))
                        }
                    },
                    enabled = room != null,
                    variant = micVariant,
                    leadingIcon = if (selfMuted) GlassIconKind.MIC_OFF else GlassIconKind.MIC,
                    minHeight = 52.dp,
                    modifier = micModifier.testTag("group_mic_toggle")
                )
            }
            val leaveButton: @Composable (Modifier) -> Unit = { leaveModifier ->
                Surface(
                    modifier = leaveModifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onRequestLeaveDialog)
                        .testTag("group_leave"),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.dangerSoft,
                    border = BorderStroke(1.dp, colors.dangerBorder.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = leaveLabel,
                            style = GroupGlassTypography.pill,
                            color = colors.textDanger,
                            maxLines = 1
                        )
                    }
                }
            }

            if (useStackedDock) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    micButton(Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        audioPill(Modifier.weight(1f))
                        leaveButton(Modifier)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    audioPill(Modifier.widthIn(max = 112.dp))
                    micButton(Modifier.weight(1f))
                    leaveButton(Modifier.widthIn(max = 68.dp))
                }
            }
        }
    }
}

@Composable
internal fun GroupInRoomSheetOverlay(
    activeSheet: GroupInRoomSheet,
    state: GroupServiceState,
    snapshot: GroupSessionSnapshot?,
    room: GroupRoomView?,
    onUpdateSheet: (GroupInRoomSheet?) -> Unit,
    onAction: (GroupSessionEvent) -> Unit,
    onRoute: (AudioRouteSelection) -> Unit,
    onVox: (Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val isNetwork = snapshot?.mode == GroupMode.NETWORK
    val isHybrid = snapshot?.mode == GroupMode.HYBRID
    val inRoom = snapshot?.phase == GroupPhase.IN_ROOM
    val actionsEnabled = inRoom && snapshot?.channelSwitching != true &&
        snapshot?.transportSwitching != true && snapshot?.cloudOffline != true
    val occupiedMembers = remember(room, snapshot?.activeSpeakerIds, snapshot?.local?.deviceId, snapshot?.names) {
        room?.members
            ?.filter { it.status != GroupMemberStatus.WAITING }
            ?.sortedWith(
                compareBy<GroupMember> { it.lease.deviceId != room.ownerId }
                    .thenBy { it.lease.deviceId !in (snapshot?.activeSpeakerIds ?: emptySet()) }
                    .thenBy { it.lease.deviceId != snapshot?.local?.deviceId }
                    .thenBy { snapshot?.names?.get(it.lease.deviceId).orEmpty() }
            )
            .orEmpty()
    }

    when (activeSheet) {
        GroupInRoomSheet.AudioRoute -> {
            GlassBottomSheetOverlay(onDismissRequest = { onUpdateSheet(null) }) {
                AudioRouteOverlaySheetContent(
                    currentRoute = state.route,
                    audioLabel = state.audioLabel,
                    voxEnabled = state.vox,
                    showVox = !isNetwork,
                    isHybridCloud = isHybrid && snapshot?.activeTransport == GroupTransport.CLOUD,
                    onRoute = onRoute,
                    onVox = onVox,
                    onClose = { onUpdateSheet(null) }
                )
            }
        }
        is GroupInRoomSheet.Governance -> {
            if (room != null && snapshot != null) {
                GlassBottomSheetOverlay(onDismissRequest = { onUpdateSheet(null) }) {
                    GovernanceBottomSheetContent(
                        snapshot = snapshot,
                        occupiedMembers = occupiedMembers,
                        selectedTarget = activeSheet.selectedTarget,
                        selectionInvalidated = activeSheet.selectionInvalidated,
                        onSelectMember = { target ->
                            onUpdateSheet(GroupInRoomSheet.Governance(selectedTarget = target, selectionInvalidated = false))
                        },
                        onInvalidateSelection = {
                            onUpdateSheet(GroupInRoomSheet.Governance(selectedTarget = null, selectionInvalidated = true))
                        },
                        actionsEnabled = actionsEnabled,
                        inRoom = inRoom,
                        onAction = onAction,
                        onOpenSettings = {
                            onUpdateSheet(null)
                            onOpenSettings()
                        },
                        onClose = { onUpdateSheet(null) }
                    )
                }
            }
        }
    }
}

private fun formatThreePlusThree(digits: String): String {
    val clean = digits.trim()
    return if (clean.length == 6) "${clean.take(3)} · ${clean.drop(3)}" else clean
}

internal fun formatStackedThreePlusThree(digits: String): String {
    val clean = digits.trim()
    return if (clean.length == 6) "${clean.take(3)}\n${clean.drop(3)}" else clean
}

@Composable
private fun RoomCodeHeroCard(
    digits: String,
    mode: GroupMode,
    occupiedCount: Int,
    maxMembers: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    canRotate: Boolean,
    rotateEnabled: Boolean,
    onRotate: () -> Unit,
    onCopy: () -> Unit
) {
    val colors = GroupGlassPalette.current
    val isCloudBacked = mode != GroupMode.LOCAL
    val isNetwork = mode == GroupMode.NETWORK
    val modeDotColor = if (isNetwork) colors.statusCloud else colors.brandLimeDot
    val titleLabel = when (mode) {
        GroupMode.LOCAL -> "本地直连 · 房间码"
        GroupMode.HYBRID -> "双模优先 · 邀请码"
        GroupMode.NETWORK -> "云端网络 · 邀请码"
    }
    val shortLabel = if (isCloudBacked) "邀请码" else "房间码"
    val footerHint = when (mode) {
        GroupMode.LOCAL -> "队友输入 6 位码即可直连入房"
        GroupMode.HYBRID -> "近距零流量直连 · 失联自动切云"
        GroupMode.NETWORK -> "复制邀请码发给车友即可入房"
    }
    val formattedDigits = formatThreePlusThree(digits)
    val stackedDigits = formatStackedThreePlusThree(digits)
    val semanticCodeText = if (isCloudBacked) digits else "房间码 $digits"
    val codeTag = if (isCloudBacked) "network_invite_code" else "local_room_code"
    val actionControls: @Composable () -> Unit = {
        AdaptiveTrailingRow(
            horizontalSpacing = 6.dp,
            verticalSpacing = 6.dp,
            fillMaxWidth = false,
            alignTrailingEnd = true,
            alignLeadingEndWhenStacked = true,
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassIconCircleButton(
                        icon = if (expanded) GlassIconKind.CHEVRON_UP else GlassIconKind.CHEVRON_DOWN,
                        contentDescription = if (expanded) "收起房间码" else "展开房间码",
                        onClick = onToggleExpanded,
                        containerColor = colors.codeCell,
                        circleSize = 32.dp,
                        modifier = Modifier.testTag("room_code_toggle")
                    )
                    if (canRotate) {
                        GlassIconCircleButton(
                            icon = GlassIconKind.REFRESH,
                            contentDescription = "刷新邀请码",
                            onClick = onRotate,
                            enabled = rotateEnabled,
                            containerColor = colors.codeCell,
                            circleSize = 32.dp,
                            modifier = Modifier.testTag("network_rotate_invite")
                        )
                    }
                }
            },
            trailing = {
                GlassPillChip(
                    label = "复制",
                    leadingIcon = GlassIconKind.COPY,
                    containerColor = colors.brandObsidian,
                    contentColor = colors.textOnObsidian,
                    onClick = onCopy,
                    modifier = Modifier.testTag("room_code_copy")
                )
            }
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = colors.cardElevated,
        borderColor = colors.glassBorder,
        cornerRadius = if (expanded) 22.dp else 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = if (expanded) 12.dp else 9.dp)
    ) {
        if (expanded) {
            AdaptiveTrailingRow(
                horizontalSpacing = 8.dp,
                verticalSpacing = 6.dp,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(modeDotColor)
                        )
                        Text(
                            text = titleLabel,
                            style = GroupGlassTypography.badge,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                trailing = actionControls
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onCopy)
                    .testTag(codeTag),
                shape = RoundedCornerShape(16.dp),
                color = colors.codeCell,
                border = BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FitRoomCodeDigits(
                        singleLineText = formattedDigits,
                        stackedText = stackedDigits,
                        semanticCodeText = semanticCodeText,
                        baseStyle = GroupGlassTypography.metric.copy(letterSpacing = 2.sp),
                        color = colors.textPrimary,
                        modifier = Modifier.testTag("${codeTag}_expanded_digits")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = footerHint,
                    style = GroupGlassTypography.badge,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$occupiedCount/$maxMembers 人",
                    style = GroupGlassTypography.badge,
                    color = colors.textPrimary
                )
            }
        } else {
            AdaptiveTrailingRow(
                horizontalSpacing = 8.dp,
                verticalSpacing = 8.dp,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    AdaptiveTrailingRow(
                        horizontalSpacing = 7.dp,
                        verticalSpacing = 2.dp,
                        fillMaxWidth = false,
                        alignTrailingEnd = false,
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClick = onCopy)
                            .testTag(codeTag),
                        leading = {
                            Row(
                                modifier = Modifier.testTag("${codeTag}_label"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Spacer(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(modeDotColor)
                                )
                                Text(
                                    text = shortLabel,
                                    style = GroupGlassTypography.badge,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        },
                        trailing = {
                            FitRoomCodeDigits(
                                singleLineText = formattedDigits,
                                stackedText = stackedDigits,
                                semanticCodeText = semanticCodeText,
                                baseStyle = GroupGlassTypography.cardTitle.copy(
                                    fontFamily = GroupGlassTypography.metric.fontFamily,
                                    letterSpacing = 1.sp
                                ),
                                color = colors.textPrimary,
                                modifier = Modifier.testTag("${codeTag}_digits")
                            )
                        }
                    )
                },
                trailing = actionControls
            )
        }
    }
}

internal data class FittedRoomCodeLayout(
    val displayText: String,
    val style: TextStyle,
    val singleLineFits: Boolean,
    val maxLines: Int,
    val softWrap: Boolean
)

internal fun resolveFittedRoomCodeLayout(
    singleLineText: String,
    stackedText: String,
    baseStyle: TextStyle,
    maxWidthPx: Int,
    minFontSizeSp: Float = 10f,
    measureTextWidthPx: (String, TextStyle) -> Int
): FittedRoomCodeLayout {
    if (maxWidthPx == Constraints.Infinity || maxWidthPx <= 0 || !baseStyle.fontSize.isSp || baseStyle.fontSize.value <= 0f) {
        return FittedRoomCodeLayout(
            displayText = singleLineText,
            style = baseStyle,
            singleLineFits = true,
            maxLines = 1,
            softWrap = false
        )
    }
    if (measureTextWidthPx(singleLineText, baseStyle) <= maxWidthPx) {
        return FittedRoomCodeLayout(
            displayText = singleLineText,
            style = baseStyle,
            singleLineFits = true,
            maxLines = 1,
            softWrap = false
        )
    }
    val baseSizeSp = baseStyle.fontSize.value
    val baseLineHeightSp = if (baseStyle.lineHeight.isSp) baseStyle.lineHeight.value else baseSizeSp * 1.25f
    val baseLetterSpacingSp = if (baseStyle.letterSpacing.isSp) baseStyle.letterSpacing.value else 0f
    val floorSp = minOf(minFontSizeSp, baseSizeSp)
    fun scaledStyle(sizeSp: Float): TextStyle {
        val ratio = sizeSp / baseSizeSp
        return baseStyle.copy(
            fontSize = sizeSp.sp,
            lineHeight = (baseLineHeightSp * ratio).sp,
            letterSpacing = (baseLetterSpacingSp * ratio).sp
        )
    }
    val floorStyle = scaledStyle(floorSp)
    if (measureTextWidthPx(singleLineText, floorStyle) <= maxWidthPx) {
        var low = floorSp
        var high = baseSizeSp
        var bestStyle = floorStyle
        repeat(8) {
            val mid = (low + high) / 2f
            val candidate = scaledStyle(mid)
            if (measureTextWidthPx(singleLineText, candidate) <= maxWidthPx) {
                bestStyle = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return FittedRoomCodeLayout(
            displayText = singleLineText,
            style = bestStyle,
            singleLineFits = true,
            maxLines = 1,
            softWrap = false
        )
    }
    val stackedLines = stackedText.split('\n')
    val stackedFitsAtFloor = stackedLines.maxOf { line -> measureTextWidthPx(line, floorStyle) } <= maxWidthPx
    return FittedRoomCodeLayout(
        displayText = stackedText,
        style = floorStyle,
        singleLineFits = false,
        maxLines = if (stackedFitsAtFloor) stackedLines.size else Int.MAX_VALUE,
        softWrap = !stackedFitsAtFloor
    )
}

@Composable
internal fun FitRoomCodeDigits(
    singleLineText: String,
    stackedText: String,
    semanticCodeText: String,
    baseStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minFontSizeSp: Float = 10f
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val fit = remember(
            singleLineText,
            stackedText,
            baseStyle,
            maxWidthPx,
            minFontSizeSp,
            textMeasurer,
            density.density,
            density.fontScale
        ) {
            resolveFittedRoomCodeLayout(
                singleLineText = singleLineText,
                stackedText = stackedText,
                baseStyle = baseStyle,
                maxWidthPx = maxWidthPx,
                minFontSizeSp = minFontSizeSp,
                measureTextWidthPx = { sample, candidateStyle ->
                    textMeasurer.measure(
                        text = AnnotatedString(sample),
                        style = candidateStyle,
                        softWrap = false,
                        maxLines = 1
                    ).size.width
                }
            )
        }
        Text(
            text = fit.displayText,
            style = fit.style,
            color = color,
            maxLines = fit.maxLines,
            softWrap = fit.softWrap,
            textAlign = TextAlign.Center,
            modifier = Modifier.clearAndSetSemantics {
                text = AnnotatedString(semanticCodeText)
            }
        )
    }
}

@Composable
internal fun AdaptiveTrailingRow(
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = true,
    alignTrailingEnd: Boolean = true,
    alignLeadingEndWhenStacked: Boolean = false,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit
) {
    Layout(
        modifier = modifier,
        content = {
            Box { leading() }
            Box { trailing() }
        }
    ) { measurables, constraints ->
        val hGap = horizontalSpacing.roundToPx()
        val vGap = verticalSpacing.roundToPx()
        val relaxed = constraints.copy(minWidth = 0, minHeight = 0)
        val leadPlaceable = measurables[0].measure(relaxed)
        val trailPlaceable = measurables[1].measure(relaxed)
        val hasBoundedWidth = constraints.hasBoundedWidth
        val fitsSingleRow = !hasBoundedWidth ||
            leadPlaceable.width + hGap + trailPlaceable.width <= constraints.maxWidth
        if (fitsSingleRow) {
            val naturalWidth = leadPlaceable.width + hGap + trailPlaceable.width
            val layoutWidth = if (fillMaxWidth && hasBoundedWidth) {
                constraints.maxWidth
            } else {
                naturalWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            }
            val rawHeight = maxOf(leadPlaceable.height, trailPlaceable.height)
            val layoutHeight = rawHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            val trailX = if (alignTrailingEnd) {
                (layoutWidth - trailPlaceable.width).coerceAtLeast(leadPlaceable.width + hGap)
            } else {
                leadPlaceable.width + hGap
            }
            layout(layoutWidth, layoutHeight) {
                leadPlaceable.placeRelative(0, (layoutHeight - leadPlaceable.height) / 2)
                trailPlaceable.placeRelative(trailX, (layoutHeight - trailPlaceable.height) / 2)
            }
        } else {
            val layoutWidth = if (fillMaxWidth) {
                constraints.maxWidth
            } else {
                maxOf(leadPlaceable.width, trailPlaceable.width)
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
            }
            val rawHeight = leadPlaceable.height + vGap + trailPlaceable.height
            val layoutHeight = rawHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            val topOffset = ((layoutHeight - rawHeight) / 2).coerceAtLeast(0)
            val leadX = if (alignLeadingEndWhenStacked) {
                (layoutWidth - leadPlaceable.width).coerceAtLeast(0)
            } else {
                0
            }
            val trailX = if (alignTrailingEnd) {
                (layoutWidth - trailPlaceable.width).coerceAtLeast(0)
            } else {
                0
            }
            layout(layoutWidth, layoutHeight) {
                leadPlaceable.placeRelative(leadX, topOffset)
                trailPlaceable.placeRelative(trailX, topOffset + leadPlaceable.height + vGap)
            }
        }
    }
}

@Composable
private fun UnifiedMemberRow(
    member: GroupMember,
    room: GroupRoomView,
    snapshot: GroupSessionSnapshot,
    selfBusyPrivate: Boolean,
    actionsEnabled: Boolean,
    onAction: (GroupSessionEvent) -> Unit,
    onSelectRole: () -> Unit,
    onOpenGovernance: () -> Unit
) {
    val colors = GroupGlassPalette.current
    val memberId = member.lease.deviceId
    val isSelf = memberId == snapshot.local?.deviceId
    val displayName = snapshot.names[memberId] ?: "骑士"
    val isSpeaking = memberId in snapshot.activeSpeakerIds
    val isReconnecting = member.status == GroupMemberStatus.RESERVED
    val isInPrivate = member.channel is GroupChannel.Private
    val isNetwork = snapshot.mode == GroupMode.NETWORK
    val peerBusyPrivate = isInPrivate || room.privateInvites.any { it.involves(memberId) }
    val canCallPrivate = !isNetwork && actionsEnabled && member.status == GroupMemberStatus.ADMITTED &&
        !isSelf && !selfBusyPrivate && !peerBusyPrivate
    val isLocallyMuted = snapshot.participation.isBlocked(memberId)

    val statusText = when {
        isReconnecting -> "重连中，席位暂时保留"
        isNetwork && !isSelf -> "已加入"
        isNetwork && snapshot.networkMediaState == GroupNetworkMediaState.JOINED -> "RTC 在线"
        isNetwork -> "等待 RTC 语音"
        isInPrivate -> "私聊中"
        isSpeaking -> "正在发言"
        !member.audioAvailable -> "音频待就绪"
        else -> "收听中"
    }
    val rowContainer = when {
        isInPrivate -> colors.privateSurface
        isSpeaking -> colors.speakingSurface
        else -> colors.cardElevated
    }
    val rowBorder = when {
        isInPrivate -> colors.privateActiveBorder
        isSpeaking -> colors.limeActiveBorder
        else -> colors.hairlineBorder
    }
    val semanticMemberTitle = "$displayName · ${member.role.displayLabel()}" + (if (isSelf) " · 我" else "")
    val visibleMemberTitle = if (isSelf) "$displayName (我)" else displayName

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpenGovernance),
        shape = RoundedCornerShape(16.dp),
        color = rowContainer,
        border = BorderStroke(1.dp, rowBorder)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            val isCompactWidth = maxWidth < 310.dp || LocalDensity.current.fontScale > 1.15f
            val useTwoRowActions = !isSelf && (!isNetwork || isCompactWidth)
            val identityHeader: @Composable (Modifier) -> Unit = { headerModifier ->
                Row(
                    modifier = headerModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RiderAvatar(
                        seed = memberId,
                        name = displayName,
                        online = member.status == GroupMemberStatus.ADMITTED && (isNetwork || member.audioAvailable),
                        reconnecting = isReconnecting,
                        size = 36.dp
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (isCompactWidth) {
                            Text(
                                text = visibleMemberTitle,
                                style = GroupGlassTypography.seatTitle,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clearAndSetSemantics {
                                        text = AnnotatedString(semanticMemberTitle)
                                    }
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RolePillBadge(role = member.role)
                                Text(
                                    text = "频道：${member.channel.displayLabel()} · $statusText",
                                    style = GroupGlassTypography.badge,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = visibleMemberTitle,
                                    style = GroupGlassTypography.seatTitle,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .clearAndSetSemantics {
                                            text = AnnotatedString(semanticMemberTitle)
                                        }
                                )
                                RolePillBadge(role = member.role)
                            }
                            Text(
                                text = "频道：${member.channel.displayLabel()} · $statusText",
                                style = GroupGlassTypography.badge,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (isSpeaking) {
                        GlassWaveformBars(
                            color = if (isInPrivate) colors.statusPrivate else colors.statusOnline,
                            animated = true
                        )
                    }
                }
            }
            val peerActionsContent: @Composable () -> Unit = {
                if (!isNetwork) {
                    when {
                        isInPrivate -> GlassPillChip(
                            label = "私聊中",
                            containerColor = colors.dangerSoft,
                            contentColor = colors.textPrivate
                        )
                        isReconnecting -> GlassPillChip(
                            label = "重连中",
                            containerColor = colors.warningSoft,
                            contentColor = colors.textWarning
                        )
                        else -> GlassPillChip(
                            label = "私聊",
                            containerColor = colors.privateSurface,
                            contentColor = colors.textPrivate,
                            borderColor = colors.privateActiveBorder,
                            onClick = { onAction(GroupSessionEvent.RequestPrivate(memberId)) },
                            enabled = canCallPrivate,
                            modifier = Modifier.testTag("private_call_$memberId")
                        )
                    }
                    if (snapshot.isOwner) {
                        GlassPillChip(
                            label = "角色",
                            containerColor = colors.card,
                            contentColor = colors.textPrimary,
                            borderColor = colors.glassBorder,
                            onClick = onSelectRole,
                            enabled = actionsEnabled,
                            modifier = Modifier.testTag("member_role_$memberId")
                        )
                    }
                }
                GlassIconCircleButton(
                    icon = GlassIconKind.MUTE_SMALL,
                    contentDescription = if (isLocallyMuted) "取消本地静音" else "本地静音",
                    onClick = { onAction(GroupSessionEvent.Block(memberId, !isLocallyMuted)) },
                    tint = if (isLocallyMuted) colors.textDanger else colors.textSecondary,
                    containerColor = if (isLocallyMuted) colors.dangerSoft else colors.chipNeutral,
                    circleSize = 34.dp,
                    modifier = Modifier.testTag("member_mute_$memberId")
                )
                if (snapshot.isOwner) {
                    GlassIconCircleButton(
                        icon = GlassIconKind.MORE,
                        contentDescription = "管理成员 $displayName",
                        onClick = onOpenGovernance,
                        enabled = actionsEnabled,
                        circleSize = 34.dp
                    )
                }
            }

            if (useTwoRowActions) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    identityHeader(Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = if (isCompactWidth) 0.dp else 46.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        peerActionsContent()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    identityHeader(Modifier.weight(1f))
                    if (!isSelf) {
                        peerActionsContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRouteOverlaySheetContent(
    currentRoute: AudioRouteSelection,
    audioLabel: String,
    voxEnabled: Boolean,
    showVox: Boolean,
    isHybridCloud: Boolean,
    onRoute: (AudioRouteSelection) -> Unit,
    onVox: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val colors = GroupGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("音频通路与声控", style = GroupGlassTypography.pageTitle, color = colors.textPrimary)
                if (audioLabel.isNotBlank()) {
                    Text(audioLabel, style = GroupGlassTypography.badge, color = colors.textSecondary)
                }
            }
            GlassIconCircleButton(icon = GlassIconKind.CLOSE, contentDescription = "关闭", onClick = onClose, circleSize = 34.dp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AudioRouteSelection.entries.forEach { selection ->
                val isSelected = selection == currentRoute
                Surface(
                    onClick = { onRoute(selection) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 82.dp)
                        .testTag("sheet_route_${selection.name}"),
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) colors.brandObsidian else colors.card,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) colors.limeActiveBorder else colors.glassBorder
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        GlassVectorIcon(
                            kind = when (selection) {
                                AudioRouteSelection.BLUETOOTH -> GlassIconKind.HEADSET
                                AudioRouteSelection.EARPIECE -> GlassIconKind.EARPIECE
                                AudioRouteSelection.SPEAKER -> GlassIconKind.SPEAKER
                            },
                            tint = if (isSelected) colors.brandLime else colors.textPrimary,
                            size = 24.dp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (selection) {
                                AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"
                                AudioRouteSelection.EARPIECE -> "听筒有线"
                                AudioRouteSelection.SPEAKER -> "扬声器"
                            },
                            style = GroupGlassTypography.badge,
                            color = if (isSelected) colors.textOnObsidian else colors.textPrimary
                        )
                    }
                }
            }
        }
        if (showVox) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .toggleable(value = voxEnabled, role = Role.Switch, onValueChange = onVox)
                    .testTag("sheet_vox_toggle"),
                shape = RoundedCornerShape(16.dp),
                color = colors.card,
                border = BorderStroke(1.dp, colors.glassBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("声控 VOX", style = GroupGlassTypography.seatTitle, color = colors.textPrimary)
                        if (isHybridCloud) {
                            Text(
                                text = "云端兜底自动拾音中（切回本地后沿用此设置）",
                                style = GroupGlassTypography.badge,
                                color = colors.textSecondary
                            )
                        }
                    }
                    GlassSwitch(checked = voxEnabled, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun GovernanceBottomSheetContent(
    snapshot: GroupSessionSnapshot,
    occupiedMembers: List<GroupMember>,
    selectedTarget: GovernanceMemberTarget?,
    selectionInvalidated: Boolean,
    onSelectMember: (GovernanceMemberTarget) -> Unit,
    onInvalidateSelection: () -> Unit,
    actionsEnabled: Boolean,
    inRoom: Boolean,
    onAction: (GroupSessionEvent) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    val colors = GroupGlassPalette.current
    val isNetwork = snapshot.mode == GroupMode.NETWORK
    val isCloudBacked = isNetwork || snapshot.mode == GroupMode.HYBRID
    val peerMembers = occupiedMembers.filter { it.lease.deviceId != snapshot.local?.deviceId }
    val targetMember = if (selectionInvalidated) {
        null
    } else {
        selectedTarget?.let { target ->
            peerMembers.firstOrNull {
                it.lease.deviceId == target.deviceId && it.lease.incarnation == target.incarnation
            }
        }
    }
    val effectiveInvalidated = selectionInvalidated || (selectedTarget != null && targetMember == null)

    LaunchedEffect(selectedTarget, targetMember == null, selectionInvalidated) {
        if (!selectionInvalidated && selectedTarget != null && targetMember == null) {
            onInvalidateSelection()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("房间治理与成员管理", style = GroupGlassTypography.pageTitle, color = colors.textPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassIconCircleButton(icon = GlassIconKind.SETTINGS, contentDescription = "系统设置", onClick = onOpenSettings, circleSize = 34.dp)
                GlassIconCircleButton(icon = GlassIconKind.CLOSE, contentDescription = "关闭", onClick = onClose, circleSize = 34.dp)
            }
        }

        if (isCloudBacked && snapshot.isOwner && snapshot.pendingJoinRequests.isNotEmpty()) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = colors.speakingSurface,
                borderColor = colors.limeActiveBorder,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                snapshot.pendingJoinRequests.forEach { request ->
                    Text("待审批 · ${request.nickname} 申请入房", style = GroupGlassTypography.seatTitle, color = colors.textPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassActionButton(
                            label = "同意",
                            onClick = { onAction(GroupSessionEvent.ApproveJoin(request.requestId)) },
                            enabled = inRoom && occupiedMembers.size < snapshot.maxMembers,
                            variant = GlassCtaVariant.OBSIDIAN,
                            minHeight = 44.dp,
                            modifier = Modifier.weight(1f)
                        )
                        GlassActionButton(
                            label = "拒绝",
                            onClick = { onAction(GroupSessionEvent.RejectJoin(request.requestId)) },
                            enabled = inRoom,
                            variant = GlassCtaVariant.SECONDARY,
                            minHeight = 44.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (peerMembers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                peerMembers.forEach { peer ->
                    val peerId = peer.lease.deviceId
                    val selected = targetMember != null &&
                        peerId == targetMember.lease.deviceId &&
                        peer.lease.incarnation == targetMember.lease.incarnation
                    GlassPillChip(
                        label = snapshot.names[peerId] ?: "骑士",
                        containerColor = if (selected) colors.brandObsidian else colors.card,
                        contentColor = if (selected) colors.textOnObsidian else colors.textPrimary,
                        borderColor = if (selected) colors.limeActiveBorder else colors.glassBorder,
                        onClick = { onSelectMember(GovernanceMemberTarget.from(peer.lease)) },
                        modifier = Modifier.testTag("governance_select_$peerId")
                    )
                }
            }
        }

        if (effectiveInvalidated) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("governance_target_invalidated"),
                containerColor = colors.warningSoft,
                borderColor = colors.warningBorder,
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Text(
                    text = "所选成员会话已失效",
                    style = GroupGlassTypography.seatTitle,
                    color = colors.textWarning
                )
                Text(
                    text = if (peerMembers.isNotEmpty()) {
                        "该成员已离开或重新入房。为防止误操作，治理按钮已禁用，请先在上方重新选择目标成员。"
                    } else {
                        "当前房间内已无其他可管理成员。"
                    },
                    style = GroupGlassTypography.compact,
                    color = colors.textSecondary
                )
            }
        } else if (targetMember == null) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                contentPadding = PaddingValues(14.dp)
            ) {
                Text(
                    text = if (peerMembers.isNotEmpty()) {
                        "请在上方选择一位成员以管理角色、静音或房间权限。"
                    } else {
                        "当前房间内暂无其他成员。"
                    },
                    style = GroupGlassTypography.compact,
                    color = colors.textSecondary
                )
            }
        } else {
            val targetId = targetMember.lease.deviceId
            val targetName = snapshot.names[targetId] ?: "骑士"
            val targetMuted = snapshot.participation.isBlocked(targetId)
            GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    RiderAvatar(
                        seed = targetId,
                        name = targetName,
                        online = targetMember.status == GroupMemberStatus.ADMITTED,
                        reconnecting = targetMember.status == GroupMemberStatus.RESERVED,
                        size = 38.dp
                    )
                    Text(targetName, style = GroupGlassTypography.cardTitle, color = colors.textPrimary)
                    RolePillBadge(role = targetMember.role)
                }

                if (!isNetwork && snapshot.isOwner) {
                    val roleOptions = listOf(
                        GroupRole.MEMBER to "普通成员",
                        GroupRole.RIDING_OFFICER to "骑行官",
                        GroupRole.HEAD to "头车",
                        GroupRole.TAIL to "尾车"
                    )
                    roleOptions.chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (role, label) ->
                                val current = targetMember.role == role
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = actionsEnabled, role = Role.RadioButton) {
                                            onAction(GroupSessionEvent.SetRole(targetId, role))
                                        },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (current) colors.speakingSurface else colors.cardElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (current) colors.limeActiveBorder else colors.glassBorder
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = GroupGlassTypography.pill,
                                            color = if (current) colors.textLimeAccent else colors.textPrimary
                                        )
                                        if (current) {
                                            GlassVectorIcon(kind = GlassIconKind.CHECK, tint = colors.textLimeAccent, size = 15.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = targetMuted,
                            role = Role.Switch,
                            onValueChange = { onAction(GroupSessionEvent.Block(targetId, it)) }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("本机静音该成员", style = GroupGlassTypography.body, color = colors.textPrimary)
                    GlassSwitch(checked = targetMuted, onCheckedChange = null)
                }

                if (snapshot.isOwner) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isCloudBacked && targetMember.status == GroupMemberStatus.ADMITTED) {
                            GlassActionButton(
                                label = "设为新群主",
                                onClick = {
                                    onAction(GroupSessionEvent.TransferOwner(targetId, alsoLeave = false))
                                    onClose()
                                },
                                enabled = actionsEnabled,
                                variant = GlassCtaVariant.INFO_SOFT,
                                leadingIcon = GlassIconKind.CROWN,
                                minHeight = 46.dp,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        GlassActionButton(
                            label = "移出房间",
                            onClick = {
                                onAction(GroupSessionEvent.Remove(targetId))
                                onClose()
                            },
                            enabled = actionsEnabled,
                            variant = GlassCtaVariant.DANGER_SOFT,
                            leadingIcon = GlassIconKind.LEAVE,
                            minHeight = 46.dp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun OwnerLeaveSelectionDialog(
    snapshot: GroupSessionSnapshot,
    room: GroupRoomView,
    onTransferAndLeave: (String) -> Unit,
    onDisband: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = GroupGlassPalette.current
    val transferable = room.members.filter {
        it.status == GroupMemberStatus.ADMITTED && it.lease.deviceId != snapshot.local?.deviceId
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.cardElevated,
        title = {
            Text("选择离开方式", style = GroupGlassTypography.pageTitle, color = colors.textPrimary)
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (transferable.isNotEmpty()) {
                        "当前模式不能留下无主群，可转让群主后离开，或直接解散全队。"
                    } else {
                        "当前没有其他可接任的在线成员，离开将直接解散该房间。"
                    },
                    style = GroupGlassTypography.compact,
                    color = colors.textSecondary
                )
                transferable.forEach { member ->
                    val targetId = member.lease.deviceId
                    val targetName = snapshot.names[targetId] ?: "骑士"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { onTransferAndLeave(targetId) }
                            .testTag("transfer_leave_$targetId"),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.card,
                        border = BorderStroke(1.dp, colors.glassBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RiderAvatar(seed = targetId, name = targetName, online = true, reconnecting = false, size = 34.dp)
                            Text(
                                text = "转让给 $targetName",
                                style = GroupGlassTypography.seatTitle,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            RolePillBadge(role = member.role)
                        }
                    }
                }
            }
        },
        confirmButton = {
            GlassActionButton(
                label = "解散全队",
                onClick = onDisband,
                variant = GlassCtaVariant.DANGER_SOFT,
                minHeight = 44.dp,
                modifier = Modifier.testTag("network_confirm_disband")
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("继续对讲", color = colors.textSecondary)
            }
        }
    )
}

private fun GroupRole.displayLabel(): String = when (this) {
    GroupRole.OWNER -> "群主"
    GroupRole.RIDING_OFFICER -> "骑行官"
    GroupRole.HEAD -> "头车"
    GroupRole.TAIL -> "尾车"
    GroupRole.MEMBER -> "成员"
}

private fun GroupChannel.displayLabel(): String = when (this) {
    GroupChannel.Public -> "公共频道"
    GroupChannel.Rider -> "领队频道"
    is GroupChannel.Private -> "私聊频道"
}
