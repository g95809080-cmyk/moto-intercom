package com.kuma.motointercom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag

internal data class SettingsScreenUiState(
    val nickname: String,
    val nicknameFeedback: String,
    val audioSource: String,
    val productState: String,
    val attemptFacts: String,
    val discoveryCandidates: String,
    val deviceStatus: String,
    val optionalPermissionNotice: String?,
    val showOptionalPermissionCta: Boolean,
    val version: String
)

@Composable
internal fun MotoComSettingsScreen(
    state: SettingsScreenUiState,
    onBack: () -> Unit,
    onNicknameChanged: (String) -> Unit,
    onSaveNickname: () -> Unit,
    onOptionalPermission: () -> Unit,
    onLogs: () -> Unit,
    onAbout: () -> Unit,
    onPlaceholder: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .widthIn(max = dimensionResource(R.dimen.motocom_content_max_width))
            .fillMaxWidth()
            .padding(
                horizontal = dimensionResource(R.dimen.motocom_page_horizontal_padding),
                vertical = dimensionResource(R.dimen.motocom_page_vertical_padding)
            )
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.Top
    ) {
        SettingsHeader(onBack)

        SettingsSectionLabel(stringResource(R.string.settings_personal_section))
        SettingsPanel {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_nickname_label),
                    color = colorResource(R.color.motocom_text_primary),
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = state.nickname,
                    onValueChange = onNicknameChanged,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .testTag("settings_nickname_input"),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 15.sp,
                        textAlign = TextAlign.End
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (state.nickname.isBlank()) {
                                Text(
                                    stringResource(R.string.edit_text_hint),
                                    color = colorResource(R.color.motocom_text_muted),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.End
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                SettingsIconButton(
                    tag = "settings_save_nickname_button",
                    description = stringResource(R.string.settings_save_nickname),
                    icon = R.drawable.ic_chevron_right_24,
                    onClick = onSaveNickname
                )
            }
            if (state.nicknameFeedback.isNotBlank()) {
                Text(
                    state.nicknameFeedback,
                    Modifier.padding(top = 6.dp).testTag("settings_nickname_feedback"),
                    color = colorResource(R.color.motocom_text_secondary),
                    fontSize = 13.sp
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.section_vox))
        SettingsPanel {
            SettingsPlaceholderRow(
                text = stringResource(R.string.settings_vox_switch_developing),
                tag = "settings_vox_button",
                description = stringResource(R.string.vox_developing_description),
                onClick = onPlaceholder,
                icon = R.drawable.ic_mic_24
            ) {
                VisualSwitch()
            }
            SettingsPlaceholderSlider(
                text = stringResource(R.string.settings_vox_sensitivity_developing),
                tag = "settings_vox_sensitivity_button",
                description = stringResource(R.string.vox_sensitivity_developing_description),
                onClick = onPlaceholder
            )
            SettingsPlaceholderRow(
                text = stringResource(R.string.settings_vox_pending_developing),
                tag = "settings_vox_state_button",
                description = stringResource(R.string.vox_state_developing_description),
                onClick = onPlaceholder,
                icon = R.drawable.ic_info_24
            ) {
                Text(
                    state.productState,
                    color = colorResource(R.color.motocom_text_muted_accessible),
                    fontSize = 12.sp
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.section_audio_output))
        SettingsPanel {
            SettingsFact(state.audioSource, "settings_audio_source", hidden = true)
            SettingsPlaceholderRow(
                text = stringResource(R.string.settings_audio_bluetooth_developing),
                tag = "settings_audio_route_button",
                description = stringResource(R.string.audio_route_developing_description),
                onClick = onPlaceholder,
                icon = R.drawable.ic_headset_24
            ) {
                RadioMark(
                    selected = state.audioSource.contains("蓝牙耳机") &&
                        !state.audioSource.contains("未连接")
                )
            }
            SettingsPlaceholderRow(
                text = stringResource(R.string.settings_audio_earpiece_developing),
                tag = "settings_audio_earpiece_button",
                description = stringResource(R.string.audio_earpiece_developing_description),
                onClick = onPlaceholder,
                icon = R.drawable.ic_audio_24
            ) { RadioMark(selected = false) }
            SettingsPlaceholderRow(
                text = stringResource(R.string.settings_audio_speaker_developing),
                tag = "settings_audio_speaker_button",
                description = stringResource(R.string.audio_speaker_developing_description),
                onClick = onPlaceholder,
                icon = R.drawable.ic_audio_24
            ) { RadioMark(selected = false) }
        }

        SettingsSectionLabel(stringResource(R.string.settings_connection_device))
        SettingsPanel {
            val reconnectDescription = stringResource(R.string.reconnect_developing_description)
            SettingsNavigationRow(
                title = "优先使用 Wi-Fi Direct（P2P）",
                subtitle = state.attemptFacts,
                tag = "settings_reconnect_button",
                description = reconnectDescription,
                onClick = { onPlaceholder(reconnectDescription) },
                icon = R.drawable.ic_wifi_24
            )
            SettingsFact(state.productState, "settings_product_state")
            SettingsFact(state.discoveryCandidates, "settings_discovery_candidates")
        }

        SettingsSectionLabel(stringResource(R.string.settings_device_status))
        SettingsPanel {
            SettingsFact(state.deviceStatus, "settings_device_status_summary", maxLines = 1)
            state.optionalPermissionNotice?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp).testTag("settings_optional_permission_notice"),
                    color = colorResource(R.color.motocom_text_secondary),
                    fontSize = 13.sp
                )
            }
            if (state.showOptionalPermissionCta) {
                SettingsSecondaryButton(
                    stringResource(R.string.settings_optional_permission_grant),
                    "settings_optional_permission_button",
                    onOptionalPermission
                )
            }
        }

        SettingsSectionLabel(stringResource(R.string.section_advanced_settings))
        SettingsPanel {
            val reconnectDescription = stringResource(R.string.reconnect_developing_description)
            val helpDescription = stringResource(R.string.help_developing_description)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SettingsAdvancedAction(
                    icon = R.drawable.ic_refresh_24,
                    text = stringResource(R.string.settings_auto_reconnect_developing),
                    tag = "settings_reconnect_button_advanced",
                    description = reconnectDescription,
                    onClick = { onPlaceholder(reconnectDescription) }
                )
                SettingsAdvancedAction(
                    icon = R.drawable.ic_clipboard_24,
                    text = stringResource(R.string.settings_logs),
                    tag = "settings_logs_button",
                    description = stringResource(R.string.settings_logs),
                    onClick = onLogs
                )
                SettingsAdvancedAction(
                    icon = R.drawable.ic_help_24,
                    text = stringResource(R.string.settings_help_developing),
                    tag = "settings_help_button",
                    description = helpDescription,
                    onClick = { onPlaceholder(helpDescription) }
                )
                SettingsAdvancedAction(
                    icon = R.drawable.ic_info_24,
                    text = stringResource(R.string.settings_about),
                    tag = "settings_about_button",
                    description = stringResource(R.string.settings_about),
                    onClick = onAbout
                )
            }
            Text(
                state.version,
                Modifier.fillMaxWidth().padding(top = 6.dp).testTag("settings_version_text"),
                color = colorResource(R.color.motocom_text_secondary),
                fontSize = 12.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    val backDescription = stringResource(R.string.back_button_description)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SettingsIconButton(
            tag = "settings_back_button",
            description = backDescription,
            icon = R.drawable.ic_arrow_back_24,
            onClick = onBack
        )
        Text(
            stringResource(R.string.nav_settings),
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .testTag("settings_title"),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(dimensionResource(R.dimen.motocom_icon_button_size)))
    }
}

@Composable
private fun SettingsIconButton(tag: String, description: String, icon: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), description, Modifier.size(24.dp), tint = colorResource(R.color.motocom_text_primary))
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 3.dp),
        color = colorResource(R.color.motocom_text_muted_accessible),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun SettingsPanel(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(colorResource(R.color.motocom_surface))
            .padding(horizontal = 14.dp, vertical = 0.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsFact(
    text: String,
    tag: String,
    maxLines: Int = Int.MAX_VALUE,
    hidden: Boolean = false
) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .then(if (hidden) Modifier.height(1.dp).alpha(0f) else Modifier.padding(vertical = 2.dp))
            .testTag(tag),
        color = colorResource(R.color.motocom_text_secondary),
        fontSize = 13.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SettingsSecondaryButton(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .defaultMinSize(minHeight = 44.dp)
            .testTag(tag),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.motocom_surface_soft),
            contentColor = colorResource(R.color.motocom_text_primary)
        ),
        shape = RoundedCornerShape(12.dp)
    ) { Text(text) }
}

@Composable
private fun SettingsPlaceholderRow(
    text: String,
    tag: String,
    description: String,
    onClick: (String) -> Unit,
    icon: Int,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button) { onClick(description) }
            .semantics { contentDescription = description }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(icon),
            null,
            Modifier.size(21.dp),
            tint = colorResource(R.color.motocom_text_muted_accessible)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            Modifier.weight(1f),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 14.sp
        )
        trailing()
    }
}

@Composable
private fun SettingsPlaceholderSlider(
    text: String,
    tag: String,
    description: String,
    onClick: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clickable(role = Role.Button) { onClick(description) }
            .semantics { contentDescription = description }
            .testTag(tag)
            .padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_tune_24),
                null,
                Modifier.size(21.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
            Spacer(Modifier.width(10.dp))
            Text(text, color = colorResource(R.color.motocom_text_primary), fontSize = 14.sp)
        }
        SettingsSliderVisual()
    }
}

@Composable
private fun SettingsSliderVisual() {
    val trackColor = colorResource(R.color.motocom_border)
    val accentColor = colorResource(R.color.motocom_accent_green)
    Column(Modifier.fillMaxWidth().padding(start = 31.dp, top = 3.dp)) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val y = size.height / 2f
            val start = 0f
            val end = size.width
            drawLine(
                color = trackColor,
                start = androidx.compose.ui.geometry.Offset(start, y),
                end = androidx.compose.ui.geometry.Offset(end, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = accentColor,
                start = androidx.compose.ui.geometry.Offset(start, y),
                end = androidx.compose.ui.geometry.Offset(end * 0.52f, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(accentColor, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(end * 0.52f, y))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("低", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
            Text("中", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
            Text("高", color = colorResource(R.color.motocom_text_secondary), fontSize = 11.sp)
        }
    }
}

@Composable
private fun VisualSwitch() {
    Box(
        Modifier
            .size(width = 42.dp, height = 25.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colorResource(R.color.motocom_accent_green))
            .padding(3.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Box(Modifier.size(19.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colorResource(R.color.motocom_accent_green) else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (selected) colorResource(R.color.motocom_accent_green) else colorResource(R.color.motocom_text_muted),
                shape = CircleShape
            )
            .then(
                if (!selected) Modifier
                    .background(Color.Transparent)
                    .testTag("settings_radio_unselected") else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_check_circle_24), null, Modifier.size(22.dp), tint = Color.Unspecified)
        } else {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color.Transparent).semantics { })
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    tag: String,
    description: String,
    onClick: () -> Unit,
    icon: Int
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(icon), null, Modifier.size(21.dp), tint = colorResource(R.color.motocom_text_muted_accessible))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colorResource(R.color.motocom_text_primary), fontSize = 14.sp)
            Text(subtitle, color = colorResource(R.color.motocom_text_secondary), fontSize = 12.sp)
        }
        Icon(painterResource(R.drawable.ic_chevron_right_24), null, Modifier.size(18.dp), tint = colorResource(R.color.motocom_text_muted_accessible))
    }
}

@Composable
private fun SettingsAdvancedAction(
    icon: Int,
    text: String,
    tag: String,
    description: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .defaultMinSize(minHeight = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = colorResource(R.color.motocom_text_primary))
        Text(
            text,
            Modifier.padding(top = 2.dp),
            color = colorResource(R.color.motocom_text_secondary),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenPreview() {
    MotoComTheme {
        MotoComSettingsScreen(
            SettingsScreenUiState(
                nickname = "骑行者 A",
                nicknameFeedback = "",
                audioSource = "当前音频源：未连接",
                productState = "当前状态：离线",
                attemptFacts = "计划通道：无 · 当前通道：无",
                discoveryCandidates = "可用发现候选：无",
                deviceStatus = "蓝牙：未连接",
                optionalPermissionNotice = null,
                showOptionalPermissionCta = false,
                version = "v1.2.0"
            ),
            onBack = {},
            onNicknameChanged = {},
            onSaveNickname = {},
            onOptionalPermission = {},
            onLogs = {},
            onAbout = {},
            onPlaceholder = {}
        )
    }
}
