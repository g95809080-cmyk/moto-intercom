package com.kuma.motointercom

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun MotoComBackdrop(modifier: Modifier = Modifier) {
    val colors = MotoComThemePalette.current
    Canvas(modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(colors.background, colors.backgroundDeep)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.accent.copy(alpha = 0.075f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.27f),
                radius = size.width * 0.9f
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.5f, size.height * 0.27f)
        )
        val horizon = size.height * 0.31f
        drawLine(
            color = colors.hairline.copy(alpha = 0.035f),
            start = Offset(0f, horizon),
            end = Offset(size.width, horizon),
            strokeWidth = 1.dp.toPx()
        )
        repeat(5) { index ->
            val y = horizon + (index + 1) * 18.dp.toPx()
            drawLine(
                color = colors.hairline.copy(alpha = 0.018f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

@Composable
internal fun MotoComTopBar(
    active: Boolean = false,
    statusDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = statusDescription?.let { "摩声，$it" } ?: "摩声"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            MotoComWordmarkPulse(active)
            Spacer(Modifier.width(11.dp))
            Text(
                text = "摩声",
                color = colors.text,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 25.sp,
                    lineHeight = 30.sp,
                    letterSpacing = 2.5.sp
                )
            )
        }
    }
}

@Composable
private fun MotoComWordmarkPulse(active: Boolean) {
    val colors = MotoComThemePalette.current
    if (!active) {
        Row(
            Modifier
                .width(19.dp)
                .height(22.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height((5 + (0.46f + index * 0.22f) * 14).dp)
                        .clip(CircleShape)
                        .background(
                            colors.accent.copy(alpha = if (index == 1) 1f else 0.78f)
                        )
                )
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "moto_wordmark")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1500,
                easing = LinearEasing
            )
        ),
        label = "moto_wordmark_phase"
    )
    Row(
        Modifier
            .width(19.dp)
            .height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val strength = 0.38f + 0.62f * abs(sin(phase + index * 0.86f))
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + strength * 14).dp)
                    .clip(CircleShape)
                    .background(
                        colors.accent.copy(alpha = if (index == 1) 1f else 0.78f)
                    )
            )
        }
    }
}

@Composable
internal fun MotoComPageHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = colors.text,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 30.sp,
                lineHeight = 36.sp
            )
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun MotoComSectionLabel(
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.muted,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 10.sp,
                letterSpacing = 1.5.sp
            )
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = colors.outline.copy(alpha = 0.58f),
            thickness = 1.dp
        )
    }
}

@Composable
internal fun MotoComPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    containerColor: Color = MotoComThemePalette.current.panel,
    borderColor: Color? = MotoComThemePalette.current.hairline.copy(alpha = 0.055f),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(13.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
internal fun MotoComStatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.panelRaised.copy(alpha = 0.82f))
            .border(1.dp, colors.outline, RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MotoComPulseDot(active)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = colors.text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 10.sp,
                letterSpacing = 1.3.sp
            )
        )
    }
}

@Composable
private fun MotoComPulseDot(active: Boolean) {
    val colors = MotoComThemePalette.current
    if (!active) {
        Box(
            Modifier.size(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(colors.muted, CircleShape)
            )
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "moto_pulse_dot")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "moto_pulse_dot_alpha"
    )
    Box(
        Modifier.size(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(colors.accent.copy(alpha = (1f - pulse) * 0.28f), CircleShape)
        )
        Box(
            Modifier
                .size(5.dp)
                .background(colors.accent, CircleShape)
        )
    }
}

@Composable
internal fun MotoComToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MotoComThemePalette.current
    Box(
        modifier
            .width(39.dp)
            .height(23.dp)
            .clip(CircleShape)
            .background(
                if (checked) {
                    colors.accent.copy(alpha = if (enabled) 0.24f else 0.12f)
                } else {
                    colors.background.copy(alpha = 0.7f)
                }
            )
            .border(
                1.dp,
                if (checked) {
                    colors.accent.copy(alpha = 0.42f)
                } else {
                    colors.outline
                },
                CircleShape
            )
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(15.dp)
                .background(
                    when {
                        !enabled -> colors.muted.copy(alpha = 0.45f)
                        checked -> colors.accent
                        else -> colors.muted
                    },
                    CircleShape
                )
        )
    }
}

@Composable
internal fun MotoComThemeSelector(
    selectedTheme: MotoComThemeMode,
    onThemeSelected: (MotoComThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.panel.copy(alpha = 0.9f))
            .border(
                1.dp,
                colors.hairline.copy(alpha = 0.055f),
                RoundedCornerShape(18.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MotoComThemeMode.entries.forEach { theme ->
            val selected = theme == selectedTheme
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            theme.colors.accent.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .border(
                        1.dp,
                        if (selected) {
                            theme.colors.accent.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(14.dp)
                    )
                    .semantics {
                        this.selected = selected
                        contentDescription = "主题：${theme.label}"
                    }
                    .testTag("settings_theme_${theme.name.lowercase()}")
                    .clickable(role = Role.Button) { onThemeSelected(theme) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(13.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(theme.colors.accent, theme.colors.accentDeep)
                            )
                        )
                        .border(1.dp, theme.colors.text.copy(alpha = 0.18f), CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = theme.label,
                    color = if (selected) theme.colors.accent else colors.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun MotoComDevicePill(
    label: String,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colors.panelRaised.copy(alpha = 0.82f))
            .border(1.dp, colors.outline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_headset_24),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = colors.accent
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = colors.text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 9.sp,
                letterSpacing = 0.7.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
