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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
                    colors.accent.copy(alpha = if (colors.isLight) 0.065f else 0.085f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.22f),
                radius = size.width * 0.92f
            ),
            radius = size.width * 0.92f,
            center = Offset(size.width * 0.5f, size.height * 0.22f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colors.panelGlow.copy(alpha = if (colors.isLight) 0.55f else 0.22f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.82f, size.height * 0.08f),
                radius = size.width * 0.65f
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.82f, size.height * 0.08f)
        )
    }
}

@Composable
internal fun MotoComTopBar(
    active: Boolean = false,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center
) {
    val colors = MotoComThemePalette.current
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("motocom_top_bar")
            .clearAndSetSemantics {
                contentDescription = "摩声"
            },
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.accent.copy(alpha = if (colors.isLight) 0.12f else 0.16f))
                    .border(
                        1.dp,
                        colors.accent.copy(alpha = if (active) 0.45f else 0.24f),
                        RoundedCornerShape(9.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                MotoComWordmarkPulse(active)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "摩声",
                color = colors.text,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    letterSpacing = 2.sp
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
            .heightIn(min = 40.dp),
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
            .heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(11.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.72f))
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = colors.muted,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.sp
            )
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = colors.outline.copy(alpha = 0.65f),
            thickness = 1.dp
        )
    }
}

@Composable
internal fun MotoComPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    containerColor: Color = MotoComThemePalette.current.panel,
    borderColor: Color? = MotoComThemePalette.current.outline.copy(alpha = 0.72f),
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(14.dp),
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
    animated: Boolean = active,
    modifier: Modifier = Modifier
) {
    val colors = MotoComThemePalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) {
                    colors.accent.copy(alpha = if (colors.isLight) 0.11f else 0.15f)
                } else {
                    colors.panelRaised.copy(alpha = 0.9f)
                }
            )
            .border(
                1.dp,
                if (active) colors.accent.copy(alpha = 0.34f) else colors.outline,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MotoComPulseDot(active = active, animated = animated)
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = if (active) colors.accent else colors.text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.6.sp
            )
        )
    }
}

@Composable
private fun MotoComPulseDot(active: Boolean, animated: Boolean) {
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
    if (!animated) {
        Box(
            Modifier.size(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(colors.accent, CircleShape)
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
    enabled: Boolean = true,
    interactive: Boolean = true,
    contentDescription: String? = null
) {
    val colors = MotoComThemePalette.current
    Box(
        modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(
                if (interactive) {
                    Modifier
                        .toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Switch,
                            onValueChange = onCheckedChange
                        )
                        .semantics {
                            contentDescription?.let { this.contentDescription = it }
                        }
                } else {
                    Modifier.clearAndSetSemantics {}
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(25.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        colors.accent.copy(
                            alpha = when {
                                !enabled -> 0.16f
                                colors.isLight -> 0.88f
                                else -> 0.28f
                            }
                        )
                    } else {
                        colors.panelRaised
                    }
                )
                .border(
                    1.dp,
                    if (checked) {
                        colors.accent.copy(alpha = if (enabled) 0.62f else 0.28f)
                    } else {
                        colors.outline
                    },
                    CircleShape
                ),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(19.dp)
                    .background(
                        when {
                            !enabled -> colors.muted.copy(alpha = 0.45f)
                            checked && colors.isLight -> colors.accentInk
                            checked -> colors.accent
                            else -> colors.muted
                        },
                        CircleShape
                    )
            )
        }
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
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.panel)
            .border(
                1.dp,
                colors.outline.copy(alpha = 0.78f),
                RoundedCornerShape(20.dp)
            )
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MotoComThemeMode.entries.forEach { theme ->
            val selected = theme == selectedTheme
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (selected) {
                            colors.accent.copy(alpha = if (colors.isLight) 0.12f else 0.16f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .border(
                        1.dp,
                        if (selected) {
                            colors.accent.copy(alpha = 0.38f)
                        } else {
                            Color.Transparent
                        },
                        RoundedCornerShape(15.dp)
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
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(theme.colors.accent, theme.colors.accentDeep)
                            )
                        )
                        .border(1.dp, colors.outline, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = theme.label,
                    color = if (selected) colors.text else colors.muted,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    ),
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
            .background(colors.panelRaised.copy(alpha = 0.92f))
            .border(1.dp, colors.outline, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_headset_24),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = colors.accent
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = colors.text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
