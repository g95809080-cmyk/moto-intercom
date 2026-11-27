package com.kuma.motointercom.group.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.MotoComThemeMode
import com.kuma.motointercom.R
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.cloud.GroupCloudInviteCode
import com.kuma.motointercom.group.cloud.GroupCloudStartRequest
import com.kuma.motointercom.group.cloud.GroupEntitlementSummary
import com.kuma.motointercom.showDeveloperCloudSettings

@Composable
internal fun GroupLobbyContent(
    statusMessage: String,
    selectedMode: GroupMode,
    onSelectMode: (GroupMode) -> Unit,
    themeMode: MotoComThemeMode,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    route: AudioRouteSelection,
    voxEnabled: Boolean,
    localCode: String,
    onLocalCodeChange: (String) -> Unit,
    inviteInput: String,
    onInviteInputChange: (String) -> Unit,
    requireApproval: Boolean,
    onRequireApprovalChange: (Boolean) -> Unit,
    serverUrl: String,
    allowCustomServer: Boolean,
    onOpenDeveloperSettings: () -> Unit,
    onStartLocal: (String?) -> Unit,
    onStartNetwork: (GroupCloudStartRequest) -> Unit,
    entitlementSummary: GroupEntitlementSummary? = null,
    hasEntitlementToken: Boolean = false,
    onOpenActivationDialog: () -> Unit = {}
) {
    val colors = GroupGlassPalette.current
    val isCloudMode = selectedMode != GroupMode.LOCAL
    val creatingHybrid = selectedMode == GroupMode.HYBRID
    val routeLabel = when (route) {
        AudioRouteSelection.BLUETOOTH -> "蓝牙头盔"
        AudioRouteSelection.EARPIECE -> "听筒有线"
        AudioRouteSelection.SPEAKER -> "免提扬声器"
    }
    val capacityCaption = when (selectedMode) {
        GroupMode.LOCAL -> "最多 4 人 · 零流量直连"
        GroupMode.HYBRID -> "最多 4 人 · 本地优先"
        GroupMode.NETWORK -> "最多 50 人 · 远程云端"
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "摩声",
                    style = GroupGlassTypography.brand,
                    color = colors.textPrimary
                )
                GlassPillChip(
                    label = "R5",
                    containerColor = colors.speakingSurface,
                    contentColor = colors.textLimeAccent,
                    borderColor = colors.limeActiveBorder.copy(alpha = 0.6f)
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
                GlassIconCircleButton(
                    icon = GlassIconKind.SETTINGS,
                    contentDescription = "打开系统设置",
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open_settings")
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "随时组队开聊",
                    style = GroupGlassTypography.hero,
                    color = colors.textPrimary
                )
                Text(
                    text = when (selectedMode) {
                        GroupMode.LOCAL -> "附近免网直连 · 最多 4 人"
                        GroupMode.HYBRID -> "本地优先 · 断线自动切云"
                        GroupMode.NETWORK -> "云端公共频道 · 最多 50 人"
                    },
                    style = GroupGlassTypography.compact,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Image(
                painter = painterResource(id = R.drawable.group_helmet_hero),
                contentDescription = "摩声骑行头盔",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 112.dp, height = 86.dp)
            )
        }

        if (statusMessage.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.glass,
                border = BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Text(
                    text = statusMessage,
                    style = GroupGlassTypography.compact,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("group_status")
                )
            }
        } else {
            Text(
                text = "",
                modifier = Modifier.testTag("group_status").height(0.dp)
            )
        }

        GlassSegmentedControl(
            options = listOf(
                GlassSegmentOption(
                    value = GroupMode.LOCAL,
                    label = "本地 4人",
                    testTag = "mode_local",
                    dotColor = colors.brandLimeDot
                ),
                GlassSegmentOption(
                    value = GroupMode.HYBRID,
                    label = "双模 4人",
                    testTag = "mode_hybrid",
                    dotColor = colors.brandLimeDot
                ),
                GlassSegmentOption(
                    value = GroupMode.NETWORK,
                    label = "网络 50人",
                    testTag = "mode_network",
                    dotColor = colors.statusCloud
                )
            ),
            selected = selectedMode,
            onSelect = onSelectMode
        )

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "创建新房间",
                    style = GroupGlassTypography.seatTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                if (isCloudMode) {
                    val entitlementLabel = when {
                        !hasEntitlementToken -> "未激活 · 激活"
                        entitlementSummary?.isUsable == true ->
                            "剩 ${entitlementSummary.remainingMinutes}分 · ${entitlementSummary.maxMembers}人"
                        entitlementSummary != null -> "额度已尽 · 续码"
                        else -> "已激活 · 管理"
                    }
                    GlassPillChip(
                        label = entitlementLabel,
                        containerColor = if (hasEntitlementToken && entitlementSummary?.isUsable == true) {
                            colors.speakingSurface
                        } else {
                            colors.glass
                        },
                        contentColor = if (hasEntitlementToken && entitlementSummary?.isUsable == true) {
                            colors.textLimeAccent
                        } else {
                            colors.textSecondary
                        },
                        borderColor = if (hasEntitlementToken && entitlementSummary?.isUsable == true) {
                            colors.limeActiveBorder.copy(alpha = 0.7f)
                        } else {
                            colors.hairlineBorder
                        },
                        onClick = onOpenActivationDialog,
                        modifier = Modifier.testTag("entitlement_banner")
                    )
                } else {
                    Text(
                        text = capacityCaption,
                        style = GroupGlassTypography.badge,
                        color = colors.textMuted
                    )
                }
            }
            if (isCloudMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = requireApproval,
                            role = Role.Switch,
                            onValueChange = onRequireApprovalChange
                        )
                        .testTag("network_require_approval_prep"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "入房需群主审批",
                        style = GroupGlassTypography.body,
                        color = colors.textPrimary
                    )
                    GlassSwitch(checked = requireApproval, onCheckedChange = null)
                }
                GlassActionButton(
                    label = if (creatingHybrid) "创建双模房间" else "创建网络房间",
                    onClick = {
                        onStartNetwork(GroupCloudStartRequest.Create(serverUrl, requireApproval, selectedMode))
                    },
                    enabled = serverUrl.isNotBlank(),
                    variant = GlassCtaVariant.OBSIDIAN,
                    showWaveform = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (creatingHybrid) "hybrid_create" else "network_create")
                )
            } else {
                GlassActionButton(
                    label = "创建本地房间",
                    onClick = { onStartLocal(null) },
                    variant = GlassCtaVariant.OBSIDIAN,
                    showWaveform = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_create")
                )
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isCloudMode) "输入 6 位邀请码加入" else "输入 6 位房间码加入",
                    style = GroupGlassTypography.seatTitle,
                    color = colors.textPrimary
                )
                Text(
                    text = "支持直接粘贴",
                    style = GroupGlassTypography.badge,
                    color = colors.textMuted
                )
            }
            if (isCloudMode) {
                GlassSixDigitCodeInput(
                    value = inviteInput,
                    onValueChange = onInviteInputChange,
                    inputTag = "network_invite_input"
                )
                GlassActionButton(
                    label = "申请加入房间",
                    onClick = {
                        val validCode = GroupCloudInviteCode(inviteInput)
                        onInviteInputChange("")
                        onStartNetwork(GroupCloudStartRequest.Join(serverUrl, validCode, selectedMode))
                    },
                    enabled = serverUrl.isNotBlank() && GroupCloudInviteCode.isValid(inviteInput),
                    variant = GlassCtaVariant.LIME,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(if (creatingHybrid) "hybrid_join" else "network_join")
                )
            } else {
                GlassSixDigitCodeInput(
                    value = localCode,
                    onValueChange = onLocalCodeChange,
                    inputTag = "group_code_input"
                )
                GlassActionButton(
                    label = "加入本地房间",
                    onClick = {
                        val digits = localCode
                        onLocalCodeChange("")
                        onStartLocal(digits)
                    },
                    enabled = localCode.length == 6,
                    variant = GlassCtaVariant.LIME,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_join")
                )
            }
        }

        if (showDeveloperCloudSettings(allowCustomServer, selectedMode)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GlassPillChip(
                    label = "开发者设置",
                    leadingIcon = GlassIconKind.CODE,
                    containerColor = colors.card,
                    contentColor = colors.textSecondary,
                    borderColor = colors.glassBorder,
                    onClick = onOpenDeveloperSettings,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("developer_settings")
                )
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onOpenSettings)
            .testTag("lobby_audio_quick_bar"),
        shape = RoundedCornerShape(20.dp),
        color = colors.cardElevated,
        border = BorderStroke(1.dp, colors.glassBorder),
        shadowElevation = if (colors.isLight) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.statusOnline)
                )
                Text(
                    text = "$routeLabel · ${if (voxEnabled) "VOX 自动开麦" else "手动开关麦"}",
                    style = GroupGlassTypography.compact,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "音频设置 ›",
                style = GroupGlassTypography.badge,
                color = colors.textLimeAccent
            )
        }
    }
}
