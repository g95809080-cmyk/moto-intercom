package com.kuma.motointercom

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag

internal data class DiscoverScreenUiState(
    val presentation: DiscoverPresentation,
    val stateText: String,
    val supplementalText: String?,
    val emptyText: String,
    val radarRunning: Boolean
)

@Composable
internal fun MotoComDiscoverScreen(
    state: DiscoverScreenUiState,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onStart: () -> Unit,
    onWifiSettings: () -> Unit,
    onRescan: () -> Unit,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit,
    modifier: Modifier = Modifier
) {
    val presentation = state.presentation
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
        DiscoverHeader(onBack = onBack, onHelp = onHelp)
        Spacer(Modifier.height(14.dp))
        DiscoverRadarCard(state, onStart, onWifiSettings)
        DiscoverIdentityNotice()

        DiscoverGroup(
            tag = "discover_paired_container",
            cards = presentation.cards.withIndex().filter { !it.value.offlinePaired && (it.value.paired || it.value.preferred) },
            orderedPresences = presentation.orderedPresences,
            onSelectPresence = onSelectPresence,
            onConnect = onConnect
        )
        DiscoverGroup(
            tag = "discover_nearby_container",
            cards = presentation.cards.withIndex().filter {
                !it.value.preferred && (!it.value.paired || it.value.offlinePaired)
            }.sortedBy { if (it.value.offlinePaired) 0 else 1 },
            orderedPresences = presentation.orderedPresences,
            onSelectPresence = onSelectPresence,
            onConnect = onConnect
        )
        DiscoverGroup(
            tag = "discover_offline_paired_container",
            cards = presentation.cards.withIndex().filter { it.value.offlinePaired },
            orderedPresences = presentation.orderedPresences,
            onSelectPresence = onSelectPresence,
            onConnect = onConnect,
            renderCards = false
        )

        if (presentation.cards.isEmpty()) {
            Text(
                text = state.emptyText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(colorResource(R.color.motocom_surface), RoundedCornerShape(18.dp))
                    .padding(18.dp)
                    .testTag("discover_empty_text"),
                color = colorResource(R.color.motocom_text_secondary),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onRescan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(56.dp)
                .testTag("discover_rescan_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorResource(R.color.motocom_surface),
                contentColor = colorResource(R.color.motocom_text_primary)
            ),
            shape = RoundedCornerShape(18.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Icon(painterResource(R.drawable.ic_refresh_24), null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.discover_rescan_label), fontWeight = FontWeight.Bold)
        }
        Text(
            text = stringResource(R.string.discover_one_to_one_note),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .padding(horizontal = 10.dp),
            color = colorResource(R.color.motocom_text_muted_accessible),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DiscoverHeader(onBack: () -> Unit, onHelp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        DiscoverIconButton(
            "discover_back_button",
            stringResource(R.string.back_button_description),
            R.drawable.ic_arrow_back_24,
            onBack
        )
        Text(
            stringResource(R.string.nav_discover),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .testTag("discover_title"),
            color = colorResource(R.color.motocom_text_primary),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        DiscoverIconButton(
            "discover_help_button",
            stringResource(R.string.help_developing_description),
            R.drawable.ic_help_24,
            onHelp
        )
    }
}

@Composable
private fun DiscoverIconButton(tag: String, description: String, icon: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(dimensionResource(R.dimen.motocom_icon_button_size))
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(icon), description, Modifier.size(24.dp))
    }
}

@Composable
private fun DiscoverRadarCard(
    state: DiscoverScreenUiState,
    onStart: () -> Unit,
    onWifiSettings: () -> Unit
) {
    val presentation = state.presentation
    val accent = colorResource(R.color.motocom_accent_green)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, shape)
            .clip(shape)
            .background(colorResource(R.color.motocom_surface))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pulse by rememberInfiniteTransition(label = "radar").animateFloat(
                initialValue = 0.72f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
                label = "radarPulse"
            )
            Canvas(
                Modifier
                    .size(112.dp)
                    .testTag("discover_radar_ripple")
            ) {
                val radius = size.minDimension / 2f
                drawCircle(
                    color = accent.copy(alpha = if (state.radarRunning) 0.15f * pulse else 0.07f),
                    radius = radius,
                    style = Stroke(2.dp.toPx())
                )
                drawCircle(
                    color = accent.copy(alpha = 0.18f),
                    radius = radius * 0.68f,
                    style = Stroke(1.5.dp.toPx())
                )
                drawCircle(
                    color = accent.copy(alpha = 0.16f),
                    radius = radius * 0.36f,
                    style = Stroke(1.5.dp.toPx())
                )
                drawLine(
                    accent,
                    center,
                    center.copy(x = center.x + radius * 0.64f, y = center.y - radius * 0.72f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(accent, radius = 4.dp.toPx())
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text(
                    state.stateText.replace("MotoCom", "\nMotoCom").trimStart(),
                    modifier = Modifier.testTag("discover_state_text"),
                    color = colorResource(R.color.motocom_text_primary),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                state.supplementalText?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 5.dp).testTag("discover_status_supplemental"),
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 13.sp
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.discover_identity_short),
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (presentation.offlineStartVisible) {
            DiscoverPrimaryButton(stringResource(R.string.discover_start), "discover_offline_start_button", onStart)
        }
        if (presentation.wifiSettingsVisible) {
            DiscoverSecondaryButton(stringResource(R.string.wifi_settings_cta), "discover_wifi_settings_button", onWifiSettings)
        }
    }
}

@Composable
private fun DiscoverIdentityNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colorResource(R.color.motocom_surface_soft))
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("discover_identity_notice"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(R.drawable.ic_shield_check_24),
            null,
            Modifier.size(20.dp),
            tint = colorResource(R.color.motocom_text_secondary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.discover_identity_short),
            modifier = Modifier.weight(1f),
            color = colorResource(R.color.motocom_text_secondary),
            fontSize = 12.sp
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right_24),
            null,
            Modifier.size(18.dp),
            tint = colorResource(R.color.motocom_text_muted_accessible)
        )
    }
}

@Composable
private fun DiscoverGroup(
    tag: String,
    cards: List<IndexedValue<DiscoverCardPresentation>>,
    orderedPresences: List<RiderPresence>,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit,
    renderCards: Boolean = true
) {
    if (cards.isEmpty()) return
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .testTag("${tag}_label")
    )
    if (renderCards) {
        Column(Modifier.fillMaxWidth().testTag(tag)) {
            cards.forEach { indexed ->
                DiscoverPresenceCard(
                    indexed.value,
                    orderedPresences[indexed.index],
                    onSelectPresence,
                    onConnect
                )
            }
        }
    } else {
        Spacer(Modifier.height(0.dp).testTag(tag))
    }
}

@Composable
private fun DiscoverPresenceCard(
    card: DiscoverCardPresentation,
    presence: RiderPresence,
    onSelectPresence: (RiderPresence) -> Unit,
    onConnect: (RiderPresence) -> Unit
) {
    val cardId = presence.deviceId ?: card.title
    val shape = RoundedCornerShape(16.dp)
    val status = when {
        card.preferred && presence.isSelectableForUi() ->
            stringResource(R.string.discover_status_recommended)
        card.offlinePaired -> stringResource(R.string.discover_status_pending)
        !presence.isSelectableForUi() -> stringResource(R.string.discover_status_unavailable)
        card.paired -> stringResource(R.string.discover_status_paired)
        else -> stringResource(R.string.discover_status_online)
    }
    val statusColor = when {
        card.preferred && presence.isSelectableForUi() -> colorResource(R.color.motocom_accent_green)
        card.offlinePaired -> Color(0xFFFFA31A)
        !presence.isSelectableForUi() -> colorResource(R.color.motocom_text_muted_accessible)
        else -> colorResource(R.color.motocom_accent_green_dark)
    }
    val facts = buildList {
        if (card.paired) add(stringResource(R.string.discover_fact_paired))
        if (card.preferred) add(stringResource(R.string.discover_fact_preferred))
        if (!presence.isSelectableForUi()) add(stringResource(R.string.discover_fact_unavailable))
    }.joinToString(stringResource(R.string.discover_fact_separator))
        .ifBlank { stringResource(R.string.discover_fact_current) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(colorResource(R.color.motocom_surface))
            .then(
                if (card.preferred && presence.isSelectableForUi()) {
                    Modifier.border(1.dp, colorResource(R.color.motocom_accent_green), shape)
                } else {
                    Modifier
                }
            )
            .testTag("discover_card_$cardId")
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .testTag("discover_select_$cardId")
                .semantics { contentDescription = card.title }
                .clickable { onSelectPresence(presence) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.rider_helmet_avatar),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, colorResource(R.color.motocom_border), CircleShape)
            )
            Column(Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        card.title,
                        color = colorResource(R.color.motocom_text_primary),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(7.dp))
                    DiscoverStatusChip(status, statusColor)
                }
                Text(
                    card.deviceText,
                    modifier = Modifier.padding(top = 4.dp),
                    color = colorResource(R.color.motocom_text_secondary),
                    fontSize = 12.sp
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painterResource(R.drawable.ic_wifi_24),
                        null,
                        Modifier.size(15.dp),
                        tint = colorResource(R.color.motocom_text_muted_accessible)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${card.transportText} ·",
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        facts,
                        modifier = Modifier.testTag("discover_facts_$cardId"),
                        color = colorResource(R.color.motocom_text_secondary),
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (card.connectVisible) {
            Button(
                onClick = { onConnect(presence) },
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(76.dp)
                    .height(48.dp)
                    .testTag("discover_connect_$cardId"),
                enabled = card.connectEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(R.color.motocom_accent_green),
                    contentColor = colorResource(R.color.motocom_text_primary),
                    disabledContainerColor = colorResource(R.color.motocom_surface_soft),
                    disabledContentColor = colorResource(R.color.motocom_text_muted_accessible)
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    stringResource(if (card.connectEnabled) R.string.discover_connect else R.string.discover_connect_pending),
                    modifier = Modifier.testTag("discover_connect_${cardId}_text"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        } else {
            Icon(
                painter = painterResource(
                    when {
                        card.offlinePaired -> R.drawable.ic_hourglass_24
                        presence.isSelectableForUi() -> R.drawable.ic_chevron_right_24
                        else -> R.drawable.ic_block_24
                    }
                ),
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(24.dp),
                tint = colorResource(R.color.motocom_text_muted_accessible)
            )
        }
    }
}

@Composable
private fun DiscoverStatusChip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun DiscoverPrimaryButton(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.motocom_accent_green),
            contentColor = colorResource(R.color.motocom_text_primary)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, Modifier.testTag("${tag}_text"), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiscoverSecondaryButton(text: String, tag: String, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .defaultMinSize(minHeight = 48.dp)
            .testTag(tag),
        colors = ButtonDefaults.buttonColors(
            containerColor = colorResource(R.color.motocom_surface_soft),
            contentColor = colorResource(R.color.motocom_text_primary)
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, Modifier.testTag("${tag}_text"))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun DiscoverScreenPreview() {
    MotoComTheme {
        MotoComDiscoverScreen(
            state = DiscoverScreenUiState(
                presentation = DiscoverPresentation(false, false, null, emptyList(), emptyList()),
                stateText = "正在搜索附近 MotoCom 车友",
                supplementalText = null,
                emptyText = "暂无附近车友",
                radarRunning = true
            ),
            onBack = {},
            onHelp = {},
            onStart = {},
            onWifiSettings = {},
            onRescan = {},
            onSelectPresence = {},
            onConnect = {}
        )
    }
}
