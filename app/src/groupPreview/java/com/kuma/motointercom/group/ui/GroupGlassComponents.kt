package com.kuma.motointercom.group.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kuma.motointercom.group.GroupRole

internal enum class GlassBackdropMood {
    LOBBY,
    LOCAL,
    CLOUD,
    PRIVATE,
    NETWORK
}

internal enum class GlassCtaVariant {
    OBSIDIAN,
    LIME,
    SECONDARY,
    DANGER_SOFT,
    INFO_SOFT,
    SUCCESS_SOFT
}

internal enum class GlassIconKind {
    SUN,
    MOON,
    SETTINGS,
    BACK,
    MORE,
    COPY,
    REFRESH,
    CLOUD,
    WIFI,
    LOCK,
    MIC,
    MIC_OFF,
    HEADSET,
    EARPIECE,
    SPEAKER,
    LEAVE,
    CHAT,
    CROWN,
    CHECK,
    CLOSE,
    MUTE_SMALL,
    ALERT,
    LOGS,
    HELP,
    INFO,
    EXPORT_SHARE,
    EDIT,
    CODE,
    SHIELD,
    CHEVRON_UP,
    CHEVRON_DOWN
}

@Composable
internal fun GroupGlassBackdrop(
    mood: GlassBackdropMood,
    modifier: Modifier = Modifier
) {
    val colors = GroupGlassPalette.current
    Canvas(modifier = modifier.fillMaxSize().background(colors.canvas)) {
        val width = size.width
        val height = size.height
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(colors.canvas, colors.canvasDeep)
            )
        )
        val primaryAura = when (mood) {
            GlassBackdropMood.PRIVATE -> colors.auraRose
            GlassBackdropMood.CLOUD, GlassBackdropMood.NETWORK -> colors.auraSky
            GlassBackdropMood.LOBBY, GlassBackdropMood.LOCAL -> colors.auraLime
        }
        val secondaryAura = when (mood) {
            GlassBackdropMood.PRIVATE -> colors.auraSky
            GlassBackdropMood.CLOUD, GlassBackdropMood.NETWORK -> colors.auraLime
            GlassBackdropMood.LOBBY, GlassBackdropMood.LOCAL -> colors.auraSky
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primaryAura, Color.Transparent),
                center = Offset(width * 0.82f, height * 0.08f),
                radius = width * 0.78f
            ),
            center = Offset(width * 0.82f, height * 0.08f),
            radius = width * 0.78f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondaryAura, Color.Transparent),
                center = Offset(width * 0.16f, height * 0.18f),
                radius = width * 0.68f
            ),
            center = Offset(width * 0.16f, height * 0.18f),
            radius = width * 0.68f
        )
        if (mood == GlassBackdropMood.PRIVATE || !colors.isLight) {
            val tertiaryAura = if (mood == GlassBackdropMood.PRIVATE) colors.auraRose else colors.auraLime.copy(alpha = 0.14f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tertiaryAura, Color.Transparent),
                    center = Offset(width * 0.50f, height * 0.65f),
                    radius = width * 0.70f
                ),
                center = Offset(width * 0.50f, height * 0.65f),
                radius = width * 0.70f
            )
        }
    }
}

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = GroupGlassPalette.current.card,
    borderColor: Color = GroupGlassPalette.current.glassBorder,
    cornerRadius: Dp = 22.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (GroupGlassPalette.current.isLight) 4.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
internal fun GlassIconCircleButton(
    icon: GlassIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = GroupGlassPalette.current.textPrimary,
    containerColor: Color = GroupGlassPalette.current.cardElevated,
    borderColor: Color = GroupGlassPalette.current.glassBorder,
    circleSize: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(circleSize),
            shape = CircleShape,
            color = if (enabled) containerColor else containerColor.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, borderColor),
            shadowElevation = if (GroupGlassPalette.current.isLight && enabled) 2.dp else 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                GlassVectorIcon(
                    kind = icon,
                    tint = if (enabled) tint else GroupGlassPalette.current.textMuted,
                    size = 19.dp
                )
            }
        }
    }
}

@Composable
internal fun GlassActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: GlassCtaVariant = GlassCtaVariant.OBSIDIAN,
    enabled: Boolean = true,
    leadingIcon: GlassIconKind? = null,
    showWaveform: Boolean = false,
    minHeight: Dp = 50.dp
) {
    val colors = GroupGlassPalette.current
    val backgroundColor = when (variant) {
        GlassCtaVariant.OBSIDIAN -> colors.brandObsidian
        GlassCtaVariant.LIME -> colors.brandLime
        GlassCtaVariant.SECONDARY -> colors.cardElevated
        GlassCtaVariant.DANGER_SOFT -> colors.dangerSoft
        GlassCtaVariant.INFO_SOFT -> colors.infoSoft
        GlassCtaVariant.SUCCESS_SOFT -> colors.speakingSurface
    }
    val foregroundColor = when (variant) {
        GlassCtaVariant.OBSIDIAN -> colors.textOnObsidian
        GlassCtaVariant.LIME -> colors.textOnLime
        GlassCtaVariant.SECONDARY -> colors.textPrimary
        GlassCtaVariant.DANGER_SOFT -> colors.textDanger
        GlassCtaVariant.INFO_SOFT -> colors.textCloud
        GlassCtaVariant.SUCCESS_SOFT -> colors.textLimeAccent
    }
    val borderStroke = when (variant) {
        GlassCtaVariant.SECONDARY -> BorderStroke(1.dp, colors.glassBorder)
        GlassCtaVariant.DANGER_SOFT -> BorderStroke(1.dp, colors.dangerBorder.copy(alpha = 0.45f))
        GlassCtaVariant.INFO_SOFT -> BorderStroke(1.dp, colors.statusCloud.copy(alpha = 0.35f))
        GlassCtaVariant.SUCCESS_SOFT -> BorderStroke(1.dp, colors.limeActiveBorder)
        GlassCtaVariant.OBSIDIAN -> if (!colors.isLight) BorderStroke(1.dp, colors.glassBorder) else null
        GlassCtaVariant.LIME -> null
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        border = borderStroke,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = foregroundColor,
            disabledContainerColor = colors.statusDisabled.copy(alpha = 0.72f),
            disabledContentColor = colors.textMuted
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        modifier = modifier.heightIn(min = minHeight)
    ) {
        if (showWaveform) {
            GlassWaveformBars(
                color = if (enabled) {
                    if (variant == GlassCtaVariant.OBSIDIAN) colors.brandLime else foregroundColor
                } else colors.textMuted,
                animated = false,
                modifier = Modifier.padding(end = 8.dp)
            )
        } else if (leadingIcon != null) {
            GlassVectorIcon(
                kind = leadingIcon,
                tint = if (enabled) foregroundColor else colors.textMuted,
                size = 18.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = GroupGlassTypography.action,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun GlassPillChip(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = GroupGlassPalette.current.chipNeutral,
    contentColor: Color = GroupGlassPalette.current.textSecondary,
    borderColor: Color? = null,
    dotColor: Color? = null,
    leadingIcon: GlassIconKind? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(999.dp)
    val surfaceModifier = if (onClick != null) {
        modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        modifier
    }
    Surface(
        modifier = surfaceModifier,
        shape = shape,
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.55f),
        border = borderColor?.let { BorderStroke(1.dp, it) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            if (leadingIcon != null) {
                GlassVectorIcon(
                    kind = leadingIcon,
                    tint = if (enabled) contentColor else GroupGlassPalette.current.textMuted,
                    size = 13.dp
                )
            }
            Text(
                text = label,
                style = GroupGlassTypography.badge,
                color = if (enabled) contentColor else GroupGlassPalette.current.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = GroupGlassPalette.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.switchThumb,
            checkedTrackColor = colors.switchOn,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = colors.switchThumb,
            uncheckedTrackColor = colors.switchOff,
            uncheckedBorderColor = colors.hairlineBorder
        )
    )
}

internal data class GlassSegmentOption<T>(
    val value: T,
    val label: String,
    val testTag: String? = null,
    val enabled: Boolean = true,
    val dotColor: Color? = null
)

@Composable
internal fun <T> GlassSegmentedControl(
    options: List<GlassSegmentOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = GroupGlassPalette.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        color = colors.pillTrack,
        border = BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                val baseModifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .let { current -> option.testTag?.let { tag -> current.testTag(tag) } ?: current }
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        enabled = option.enabled,
                        role = Role.Tab,
                        onClick = { onSelect(option.value) }
                    )
                Surface(
                    modifier = baseModifier,
                    shape = RoundedCornerShape(999.dp),
                    color = if (isSelected) colors.pillActive else Color.Transparent,
                    shadowElevation = if (isSelected && colors.isLight) 2.dp else 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected && option.dotColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(option.dotColor)
                            )
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = option.label,
                            style = GroupGlassTypography.pill,
                            color = when {
                                !option.enabled -> colors.textMuted
                                isSelected -> colors.textPrimary
                                else -> colors.textSecondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal fun sixDigitCellIndexForX(
    xPx: Float,
    totalWidthPx: Float,
    gapPx: Float,
    length: Int = 6,
    isRtl: Boolean = false
): Int {
    if (length <= 1 || totalWidthPx <= 0f) return 0
    val cellWidth = (totalWidthPx - gapPx * (length - 1)).coerceAtLeast(1f) / length
    val stride = cellWidth + gapPx
    val logicalX = if (isRtl) (totalWidthPx - xPx) else xPx
    return (logicalX / stride).toInt().coerceIn(0, length - 1)
}

@Composable
internal fun GlassSixDigitCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    inputTag: String,
    modifier: Modifier = Modifier,
    length: Int = 6
) {
    val colors = GroupGlassPalette.current
    val gapPx = with(LocalDensity.current) { 8.dp.toPx() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var isFocused by remember { mutableStateOf(false) }
    var lastSyncedValue by remember { mutableStateOf(value) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var pendingTapCursor by remember { mutableStateOf<Int?>(null) }
    if (value != lastSyncedValue) {
        lastSyncedValue = value
        fieldValue = TextFieldValue(value, TextRange(value.length))
        pendingTapCursor = null
    }
    val selectionColors = remember(colors.limeActiveBorder) {
        TextSelectionColors(
            handleColor = colors.limeActiveBorder,
            backgroundColor = Color.Transparent
        )
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { incoming ->
                val clampedStart = incoming.selection.start.coerceIn(0, incoming.text.length)
                val clampedEnd = incoming.selection.end.coerceIn(0, incoming.text.length)
                val startDigits = incoming.text.take(clampedStart).count { ch -> ch in '0'..'9' }
                val endDigits = incoming.text.take(clampedEnd).count { ch -> ch in '0'..'9' }
                val digitsOnly = incoming.text.filter { ch -> ch in '0'..'9' }.take(length)
                val tapCursor = pendingTapCursor
                val nextSelection = if (
                    tapCursor != null &&
                    digitsOnly == fieldValue.text &&
                    incoming.selection.collapsed
                ) {
                    pendingTapCursor = null
                    TextRange(tapCursor.coerceIn(0, digitsOnly.length))
                } else {
                    TextRange(
                        start = startDigits.coerceIn(0, digitsOnly.length),
                        end = endDigits.coerceIn(0, digitsOnly.length)
                    )
                }
                fieldValue = TextFieldValue(text = digitsOnly, selection = nextSelection)
                if (digitsOnly != lastSyncedValue) {
                    lastSyncedValue = digitsOnly
                    onValueChange(digitsOnly)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = GroupGlassTypography.codeDigit.copy(color = Color.Transparent),
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .testTag(inputTag),
            decorationBox = { innerTextField ->
                val activeCellIndex = if (
                    fieldValue.text.length >= length && fieldValue.selection.start > 0
                ) {
                    (fieldValue.selection.start - 1).coerceIn(0, length - 1)
                } else {
                    fieldValue.selection.start.coerceIn(0, length - 1)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .pointerInput(length, gapPx, isRtl) {
                            awaitPointerEventScope {
                                var downPosition: Offset? = null
                                var downUptimeMs = 0L
                                var multiTouch = false
                                while (true) {
                                    val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                                    val activeChanges = initialEvent.changes
                                    if (activeChanges.size > 1) {
                                        multiTouch = true
                                        pendingTapCursor = null
                                    }
                                    val primary = activeChanges.firstOrNull()
                                    if (primary != null) {
                                        if (primary.pressed && !primary.previousPressed) {
                                            downPosition = primary.position
                                            downUptimeMs = primary.uptimeMillis
                                            multiTouch = activeChanges.size > 1
                                            pendingTapCursor = null
                                        } else if (primary.pressed && downPosition != null) {
                                            if ((primary.position - downPosition!!).getDistance() > viewConfiguration.touchSlop) {
                                                downPosition = null
                                                pendingTapCursor = null
                                            }
                                        } else if (!primary.pressed && primary.previousPressed) {
                                            val startPos = downPosition
                                            val pressDuration = primary.uptimeMillis - downUptimeMs
                                            val isShortTap = !multiTouch &&
                                                startPos != null &&
                                                pressDuration < viewConfiguration.longPressTimeoutMillis &&
                                                (primary.position - startPos).getDistance() <= viewConfiguration.touchSlop
                                            if (isShortTap) {
                                                val cellIndex = sixDigitCellIndexForX(
                                                    xPx = primary.position.x,
                                                    totalWidthPx = size.width.toFloat(),
                                                    gapPx = gapPx,
                                                    length = length,
                                                    isRtl = isRtl
                                                )
                                                val targetCursor = (cellIndex + 1).coerceAtMost(fieldValue.text.length)
                                                pendingTapCursor = targetCursor
                                            } else {
                                                pendingTapCursor = null
                                            }
                                            downPosition = null
                                            multiTouch = false
                                        }
                                    }
                                    awaitPointerEvent(PointerEventPass.Final)
                                    val unresolvedTap = pendingTapCursor
                                    if (unresolvedTap != null) {
                                        pendingTapCursor = null
                                        val clamped = unresolvedTap.coerceIn(0, fieldValue.text.length)
                                        if (fieldValue.selection != TextRange(clamped)) {
                                            fieldValue = fieldValue.copy(selection = TextRange(clamped))
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(length) { index ->
                            val charText = fieldValue.text.getOrNull(index)?.toString().orEmpty()
                            val isCurrent = if (isFocused) {
                                index == activeCellIndex
                            } else {
                                index == fieldValue.text.length.coerceAtMost(length - 1) && fieldValue.text.length < length
                            }
                            val borderColor = when {
                                isFocused && isCurrent -> colors.limeActiveBorder
                                charText.isNotEmpty() -> colors.limeActiveBorder.copy(alpha = if (isFocused) 0.72f else 1f)
                                isCurrent -> colors.textSecondary.copy(alpha = 0.45f)
                                else -> colors.hairlineBorder
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(colors.codeCell)
                                    .border(if (isFocused && isCurrent) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = charText.ifEmpty { "·" },
                                    style = GroupGlassTypography.codeDigit,
                                    color = if (charText.isNotEmpty()) colors.textPrimary else colors.textMuted.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        innerTextField()
                    }
                }
            }
        )
    }
}

@Composable
internal fun GlassWaveformBars(
    color: Color,
    animated: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 4
) {
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "waveform")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520),
                repeatMode = RepeatMode.Reverse
            ),
            label = "wavePhase"
        ).value
    } else {
        0.85f
    }
    val baseFractions = listOf(0.42f, 0.96f, 0.64f, 0.82f)
    Canvas(modifier = modifier.size(width = 22.dp, height = 18.dp)) {
        val count = barCount.coerceAtMost(baseFractions.size)
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * count) / (count - 1).coerceAtLeast(1)
        repeat(count) { index ->
            val factor = if (index % 2 == 0) phase else (1.35f - phase).coerceIn(0.32f, 1.0f)
            val barHeight = (size.height * baseFractions[index] * factor).coerceAtLeast(4.dp.toPx())
            val top = (size.height - barHeight) / 2f
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
internal fun RiderAvatar(
    seed: String,
    name: String,
    online: Boolean,
    reconnecting: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val colors = GroupGlassPalette.current
    val fill = avatarColorFor(seed, colors.avatarPalette)
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatarInitial(name),
                style = GroupGlassTypography.pageTitle,
                color = colors.avatarInk
            )
        }
        val dotColor = when {
            reconnecting -> colors.statusWarning
            online -> colors.statusOnline
            else -> colors.textMuted
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(12.dp)
                .clip(CircleShape)
                .background(colors.cardElevated)
                .padding(2.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

@Composable
internal fun RolePillBadge(
    role: GroupRole,
    modifier: Modifier = Modifier
) {
    val colors = GroupGlassPalette.current
    val (container, ink) = when (role) {
        GroupRole.OWNER -> colors.speakingSurface to colors.textLimeAccent
        GroupRole.HEAD -> colors.warningSoft to colors.textWarning
        GroupRole.RIDING_OFFICER -> colors.infoSoft to colors.textCloud
        GroupRole.TAIL -> colors.chipNeutral to colors.textSecondary
        GroupRole.MEMBER -> colors.chipNeutral to colors.textSecondary
    }
    GlassPillChip(
        label = when (role) {
            GroupRole.OWNER -> "群主"
            GroupRole.RIDING_OFFICER -> "骑行官"
            GroupRole.HEAD -> "头车"
            GroupRole.TAIL -> "尾车"
            GroupRole.MEMBER -> "成员"
        },
        containerColor = container,
        contentColor = ink,
        modifier = modifier
    )
}

@Composable
internal fun GlassVectorIcon(
    kind: GlassIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        val strokeWidth = 1.9f * unit
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(px: Float, py: Float) = Offset(px * unit, py * unit)

        when (kind) {
            GlassIconKind.SUN -> {
                drawCircle(color = tint, radius = 4f * unit, center = pt(12f, 12f), style = stroke)
                listOf(
                    pt(12f, 3f) to pt(12f, 5.2f),
                    pt(12f, 18.8f) to pt(12f, 21f),
                    pt(3f, 12f) to pt(5.2f, 12f),
                    pt(18.8f, 12f) to pt(21f, 12f),
                    pt(5.6f, 5.6f) to pt(7.2f, 7.2f),
                    pt(16.8f, 16.8f) to pt(18.4f, 18.4f),
                    pt(18.4f, 5.6f) to pt(16.8f, 7.2f),
                    pt(7.2f, 16.8f) to pt(5.6f, 18.4f)
                ).forEach { (start, end) ->
                    drawLine(color = tint, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }
            }
            GlassIconKind.MOON -> {
                val path = Path().apply {
                    moveTo(19.5f * unit, 14.5f * unit)
                    cubicTo(17.5f * unit, 19.5f * unit, 11.2f * unit, 21f * unit, 7f * unit, 17.6f * unit)
                    cubicTo(3.2f * unit, 14.5f * unit, 3.6f * unit, 7.5f * unit, 9.5f * unit, 4.5f * unit)
                    cubicTo(7.4f * unit, 9.8f * unit, 11.8f * unit, 16.2f * unit, 19.5f * unit, 14.5f * unit)
                    close()
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.SETTINGS -> {
                drawCircle(color = tint, radius = 3.2f * unit, center = pt(12f, 12f), style = stroke)
                drawCircle(color = tint, radius = 7.4f * unit, center = pt(12f, 12f), style = stroke)
                listOf(
                    pt(12f, 2.5f) to pt(12f, 4.6f),
                    pt(12f, 19.4f) to pt(12f, 21.5f),
                    pt(2.5f, 12f) to pt(4.6f, 12f),
                    pt(19.4f, 12f) to pt(21.5f, 12f)
                ).forEach { (start, end) ->
                    drawLine(color = tint, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }
            }
            GlassIconKind.BACK -> {
                val path = Path().apply {
                    moveTo(15f * unit, 5.5f * unit)
                    lineTo(8.5f * unit, 12f * unit)
                    lineTo(15f * unit, 18.5f * unit)
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.MORE -> {
                listOf(6f, 12f, 18f).forEach { cx ->
                    drawCircle(color = tint, radius = 1.6f * unit, center = pt(cx, 12f))
                }
            }
            GlassIconKind.COPY -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(9f, 9f),
                    size = Size(10.5f * unit, 10.5f * unit),
                    cornerRadius = CornerRadius(2.2f * unit, 2.2f * unit),
                    style = stroke
                )
                val path = Path().apply {
                    moveTo(14.5f * unit, 9f * unit)
                    lineTo(14.5f * unit, 6.2f * unit)
                    lineTo(6.2f * unit, 6.2f * unit)
                    lineTo(6.2f * unit, 14.5f * unit)
                    lineTo(9f * unit, 14.5f * unit)
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.REFRESH -> {
                drawArc(
                    color = tint,
                    startAngle = 35f,
                    sweepAngle = 285f,
                    useCenter = false,
                    topLeft = pt(4.5f, 4.5f),
                    size = Size(15f * unit, 15f * unit),
                    style = stroke
                )
                val arrow = Path().apply {
                    moveTo(15.5f * unit, 4.5f * unit)
                    lineTo(19.5f * unit, 5.5f * unit)
                    lineTo(18.5f * unit, 9.5f * unit)
                }
                drawPath(path = arrow, color = tint, style = stroke)
            }
            GlassIconKind.CLOUD -> {
                val path = Path().apply {
                    moveTo(7.2f * unit, 18f * unit)
                    lineTo(17.4f * unit, 18f * unit)
                    cubicTo(20.2f * unit, 18f * unit, 21f * unit, 13.4f * unit, 17.6f * unit, 12.6f * unit)
                    cubicTo(17.2f * unit, 7.6f * unit, 10.5f * unit, 6.8f * unit, 8.8f * unit, 10.8f * unit)
                    cubicTo(4.6f * unit, 11.2f * unit, 4.2f * unit, 18f * unit, 7.2f * unit, 18f * unit)
                    close()
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.WIFI -> {
                drawArc(color = tint, startAngle = -140f, sweepAngle = 100f, useCenter = false, topLeft = pt(3f, 6f), size = Size(18f * unit, 18f * unit), style = stroke)
                drawArc(color = tint, startAngle = -135f, sweepAngle = 90f, useCenter = false, topLeft = pt(6.5f, 10f), size = Size(11f * unit, 11f * unit), style = stroke)
                drawCircle(color = tint, radius = 1.5f * unit, center = pt(12f, 18f))
            }
            GlassIconKind.LOCK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(5.5f, 11f),
                    size = Size(13f * unit, 9f * unit),
                    cornerRadius = CornerRadius(2.4f * unit, 2.4f * unit),
                    style = stroke
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = pt(8f, 4.5f),
                    size = Size(8f * unit, 10f * unit),
                    style = stroke
                )
            }
            GlassIconKind.MIC -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(9f, 4f),
                    size = Size(6f * unit, 10f * unit),
                    cornerRadius = CornerRadius(3f * unit, 3f * unit),
                    style = stroke
                )
                drawArc(
                    color = tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = pt(6f, 8.5f),
                    size = Size(12f * unit, 9f * unit),
                    style = stroke
                )
                drawLine(color = tint, start = pt(12f, 17.5f), end = pt(12f, 20.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(9f, 20.5f), end = pt(15f, 20.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.MIC_OFF -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(9f, 4f),
                    size = Size(6f * unit, 10f * unit),
                    cornerRadius = CornerRadius(3f * unit, 3f * unit),
                    style = stroke
                )
                drawLine(color = tint, start = pt(4.5f, 4.5f), end = pt(19.5f, 19.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(12f, 17.5f), end = pt(12f, 20.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.HEADSET -> {
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = pt(4.5f, 5f),
                    size = Size(15f * unit, 14f * unit),
                    style = stroke
                )
                drawRoundRect(color = tint, topLeft = pt(4f, 12.5f), size = Size(3.6f * unit, 6.2f * unit), cornerRadius = CornerRadius(1.8f * unit, 1.8f * unit), style = stroke)
                drawRoundRect(color = tint, topLeft = pt(16.4f, 12.5f), size = Size(3.6f * unit, 6.2f * unit), cornerRadius = CornerRadius(1.8f * unit, 1.8f * unit), style = stroke)
            }
            GlassIconKind.EARPIECE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(7f, 3.5f),
                    size = Size(10f * unit, 17f * unit),
                    cornerRadius = CornerRadius(2.4f * unit, 2.4f * unit),
                    style = stroke
                )
                drawLine(color = tint, start = pt(10.5f, 6.5f), end = pt(13.5f, 6.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = 1f * unit, center = pt(12f, 17.5f))
            }
            GlassIconKind.SPEAKER -> {
                val body = Path().apply {
                    moveTo(4.5f * unit, 9.5f * unit)
                    lineTo(8.5f * unit, 9.5f * unit)
                    lineTo(13f * unit, 5.8f * unit)
                    lineTo(13f * unit, 18.2f * unit)
                    lineTo(8.5f * unit, 14.5f * unit)
                    lineTo(4.5f * unit, 14.5f * unit)
                    close()
                }
                drawPath(path = body, color = tint, style = stroke)
                drawArc(color = tint, startAngle = -45f, sweepAngle = 90f, useCenter = false, topLeft = pt(12.5f, 8.5f), size = Size(6f * unit, 7f * unit), style = stroke)
                drawArc(color = tint, startAngle = -48f, sweepAngle = 96f, useCenter = false, topLeft = pt(13.5f, 5.5f), size = Size(8.5f * unit, 13f * unit), style = stroke)
            }
            GlassIconKind.LEAVE -> {
                val door = Path().apply {
                    moveTo(13.5f * unit, 5.5f * unit)
                    lineTo(6.5f * unit, 5.5f * unit)
                    lineTo(6.5f * unit, 18.5f * unit)
                    lineTo(13.5f * unit, 18.5f * unit)
                }
                drawPath(path = door, color = tint, style = stroke)
                drawLine(color = tint, start = pt(11f, 12f), end = pt(19.5f, 12f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                val tip = Path().apply {
                    moveTo(16.5f * unit, 9f * unit)
                    lineTo(19.5f * unit, 12f * unit)
                    lineTo(16.5f * unit, 15f * unit)
                }
                drawPath(path = tip, color = tint, style = stroke)
            }
            GlassIconKind.CHAT -> {
                drawRoundRect(
                    color = tint,
                    topLeft = pt(4.5f, 5.5f),
                    size = Size(15f * unit, 11.5f * unit),
                    cornerRadius = CornerRadius(4f * unit, 4f * unit),
                    style = stroke
                )
                drawCircle(color = tint, radius = 1f * unit, center = pt(9f, 11.2f))
                drawCircle(color = tint, radius = 1f * unit, center = pt(12f, 11.2f))
                drawCircle(color = tint, radius = 1f * unit, center = pt(15f, 11.2f))
            }
            GlassIconKind.CROWN -> {
                val path = Path().apply {
                    moveTo(4f * unit, 17.5f * unit)
                    lineTo(5.8f * unit, 7.5f * unit)
                    lineTo(10f * unit, 11.5f * unit)
                    lineTo(12f * unit, 6.5f * unit)
                    lineTo(14f * unit, 11.5f * unit)
                    lineTo(18.2f * unit, 7.5f * unit)
                    lineTo(20f * unit, 17.5f * unit)
                    close()
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.CHECK -> {
                val path = Path().apply {
                    moveTo(5.5f * unit, 12.5f * unit)
                    lineTo(10f * unit, 17f * unit)
                    lineTo(18.5f * unit, 7.5f * unit)
                }
                drawPath(path = path, color = tint, style = stroke)
            }
            GlassIconKind.CLOSE -> {
                drawLine(color = tint, start = pt(7f, 7f), end = pt(17f, 17f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(17f, 7f), end = pt(7f, 17f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.MUTE_SMALL -> {
                val speaker = Path().apply {
                    moveTo(5f * unit, 10f * unit)
                    lineTo(8f * unit, 10f * unit)
                    lineTo(12f * unit, 6.5f * unit)
                    lineTo(12f * unit, 17.5f * unit)
                    lineTo(8f * unit, 14f * unit)
                    lineTo(5f * unit, 14f * unit)
                    close()
                }
                drawPath(path = speaker, color = tint, style = stroke)
                drawLine(color = tint, start = pt(15.5f, 9.5f), end = pt(19.5f, 14.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(19.5f, 9.5f), end = pt(15.5f, 14.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.ALERT -> {
                val tri = Path().apply {
                    moveTo(12f * unit, 4f * unit)
                    lineTo(20.5f * unit, 19f * unit)
                    lineTo(3.5f * unit, 19f * unit)
                    close()
                }
                drawPath(path = tri, color = tint, style = stroke)
                drawLine(color = tint, start = pt(12f, 10f), end = pt(12f, 13.8f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = 1f * unit, center = pt(12f, 16.5f))
            }
            GlassIconKind.LOGS -> {
                drawRoundRect(color = tint, topLeft = pt(5.5f, 4.5f), size = Size(13f * unit, 15f * unit), cornerRadius = CornerRadius(2.4f * unit, 2.4f * unit), style = stroke)
                drawLine(color = tint, start = pt(9f, 9.5f), end = pt(15f, 9.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(9f, 13f), end = pt(15f, 13f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = tint, start = pt(9f, 16.2f), end = pt(13f, 16.2f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.HELP -> {
                drawCircle(color = tint, radius = 8.5f * unit, center = pt(12f, 12f), style = stroke)
                drawArc(color = tint, startAngle = 190f, sweepAngle = 190f, useCenter = false, topLeft = pt(9.5f, 7.5f), size = Size(5f * unit, 4.5f * unit), style = stroke)
                drawLine(color = tint, start = pt(12f, 12f), end = pt(12f, 13.8f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = 1f * unit, center = pt(12f, 16.8f))
            }
            GlassIconKind.INFO -> {
                drawCircle(color = tint, radius = 8.5f * unit, center = pt(12f, 12f), style = stroke)
                drawLine(color = tint, start = pt(12f, 11.2f), end = pt(12f, 16.5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = 1f * unit, center = pt(12f, 8.2f))
            }
            GlassIconKind.EXPORT_SHARE -> {
                drawLine(color = tint, start = pt(12f, 14.5f), end = pt(12f, 5f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                val up = Path().apply {
                    moveTo(8.5f * unit, 8.5f * unit)
                    lineTo(12f * unit, 5f * unit)
                    lineTo(15.5f * unit, 8.5f * unit)
                }
                drawPath(path = up, color = tint, style = stroke)
                val tray = Path().apply {
                    moveTo(5.5f * unit, 14f * unit)
                    lineTo(5.5f * unit, 18.5f * unit)
                    lineTo(18.5f * unit, 18.5f * unit)
                    lineTo(18.5f * unit, 14f * unit)
                }
                drawPath(path = tray, color = tint, style = stroke)
            }
            GlassIconKind.EDIT -> {
                val pen = Path().apply {
                    moveTo(15.5f * unit, 5.5f * unit)
                    lineTo(18.5f * unit, 8.5f * unit)
                    lineTo(9.5f * unit, 17.5f * unit)
                    lineTo(5.8f * unit, 18.2f * unit)
                    lineTo(6.5f * unit, 14.5f * unit)
                    close()
                }
                drawPath(path = pen, color = tint, style = stroke)
            }
            GlassIconKind.CODE -> {
                val left = Path().apply {
                    moveTo(8.5f * unit, 8f * unit)
                    lineTo(4.5f * unit, 12f * unit)
                    lineTo(8.5f * unit, 16f * unit)
                }
                val right = Path().apply {
                    moveTo(15.5f * unit, 8f * unit)
                    lineTo(19.5f * unit, 12f * unit)
                    lineTo(15.5f * unit, 16f * unit)
                }
                drawPath(path = left, color = tint, style = stroke)
                drawPath(path = right, color = tint, style = stroke)
                drawLine(color = tint, start = pt(13.2f, 6f), end = pt(10.8f, 18f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }
            GlassIconKind.SHIELD -> {
                val shield = Path().apply {
                    moveTo(12f * unit, 4f * unit)
                    lineTo(19f * unit, 7f * unit)
                    lineTo(19f * unit, 12.2f * unit)
                    cubicTo(19f * unit, 16.5f * unit, 15.8f * unit, 19.2f * unit, 12f * unit, 20.5f * unit)
                    cubicTo(8.2f * unit, 19.2f * unit, 5f * unit, 16.5f * unit, 5f * unit, 12.2f * unit)
                    lineTo(5f * unit, 7f * unit)
                    close()
                }
                drawPath(path = shield, color = tint, style = stroke)
            }
            GlassIconKind.CHEVRON_UP -> {
                val chevron = Path().apply {
                    moveTo(6.5f * unit, 14.5f * unit)
                    lineTo(12f * unit, 9f * unit)
                    lineTo(17.5f * unit, 14.5f * unit)
                }
                drawPath(path = chevron, color = tint, style = stroke)
            }
            GlassIconKind.CHEVRON_DOWN -> {
                val chevron = Path().apply {
                    moveTo(6.5f * unit, 9.5f * unit)
                    lineTo(12f * unit, 15f * unit)
                    lineTo(17.5f * unit, 9.5f * unit)
                }
                drawPath(path = chevron, color = tint, style = stroke)
            }
        }
    }
}

@Composable
internal fun GlassBottomSheetOverlay(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = GroupGlassPalette.current
    BackHandler(onBack = onDismissRequest)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.overlayScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
                .testTag("glass_bottom_sheet_scrim")
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 10.dp, end = 10.dp, top = 48.dp, bottom = 10.dp)
                .widthIn(max = 600.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    }
                    .testTag("glass_bottom_sheet_surface"),
                shape = RoundedCornerShape(28.dp),
                color = colors.cardElevated,
                border = BorderStroke(1.dp, colors.glassBorder),
                shadowElevation = if (colors.isLight) 12.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(colors.textMuted.copy(alpha = 0.35f))
                        )
                    }
                    content()
                }
            }
        }
    }
}
