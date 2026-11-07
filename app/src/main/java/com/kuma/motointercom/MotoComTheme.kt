package com.kuma.motointercom

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class MotoComColors(
    val background: Color,
    val backgroundDeep: Color,
    val panel: Color,
    val panelRaised: Color,
    val panelGlow: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentInk: Color,
    val amber: Color,
    val hairline: Color,
    val isLight: Boolean
)

internal enum class MotoComThemeMode(
    val label: String,
    val colors: MotoComColors
) {
    NIGHTRIDE(
        label = "夜航",
        colors = MotoComColors(
            background = Color(0xFF0B100E),
            backgroundDeep = Color(0xFF070B09),
            panel = Color(0xFF141C18),
            panelRaised = Color(0xFF1B2721),
            panelGlow = Color(0xFF213329),
            outline = Color(0xFF2A3931),
            text = Color(0xFFF1F5EF),
            muted = Color(0xFF95A39B),
            accent = Color(0xFFC8F56B),
            accentDeep = Color(0xFF4D712C),
            accentInk = Color(0xFF142008),
            amber = Color(0xFFFFB264),
            hairline = Color.White,
            isLight = false
        )
    ),
    DAYLIGHT(
        label = "白昼",
        colors = MotoComColors(
            background = Color(0xFFF6F7F4),
            backgroundDeep = Color(0xFFEDEFEA),
            panel = Color(0xFFFFFFFF),
            panelRaised = Color(0xFFF1F3EE),
            panelGlow = Color(0xFFEAEFE5),
            outline = Color(0xFFD8DDD3),
            text = Color(0xFF142018),
            muted = Color(0xFF66736A),
            accent = Color(0xFF4F7A16),
            accentDeep = Color(0xFF31510B),
            accentInk = Color(0xFFFFFFFF),
            amber = Color(0xFFA65118),
            hairline = Color(0xFF1C2A20),
            isLight = true
        )
    )
}

internal val LocalMotoComColors = staticCompositionLocalOf {
    MotoComThemeMode.DAYLIGHT.colors
}

internal object MotoComThemePalette {
    val current: MotoComColors
        @Composable get() = LocalMotoComColors.current
}

@Composable
internal fun MotoComTheme(
    theme: MotoComThemeMode = MotoComThemeMode.DAYLIGHT,
    content: @Composable () -> Unit
) {
    val colors = theme.colors
    val colorScheme = if (colors.isLight) {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.panelGlow,
            onPrimaryContainer = colors.text,
            secondary = colors.muted,
            onSecondary = colors.panel,
            tertiary = colors.amber,
            onTertiary = colors.panel,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.panel,
            onSurface = colors.text,
            surfaceVariant = colors.panelRaised,
            onSurfaceVariant = colors.muted,
            surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panelRaised,
            surfaceContainerHighest = colors.panelRaised,
            outline = colors.outline,
            outlineVariant = colors.outline,
            scrim = Color.Black.copy(alpha = 0.4f),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    } else {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentInk,
            primaryContainer = colors.panelGlow,
            onPrimaryContainer = colors.text,
            secondary = colors.muted,
            onSecondary = colors.panel,
            tertiary = colors.amber,
            onTertiary = colors.panel,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.panel,
            onSurface = colors.text,
            surfaceVariant = colors.panelRaised,
            onSurfaceVariant = colors.muted,
            surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panelRaised,
            surfaceContainerHighest = colors.panelRaised,
            outline = colors.outline,
            outlineVariant = colors.outline,
            scrim = Color.Black.copy(alpha = 0.72f),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005)
        )
    }
    val typography = Typography(
        headlineLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            lineHeight = 36.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            lineHeight = 30.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 26.sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            lineHeight = 24.sp
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 15.sp,
            lineHeight = 22.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            lineHeight = 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 14.sp
        )
    )

    CompositionLocalProvider(LocalMotoComColors provides colors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(22.dp),
                extraLarge = RoundedCornerShape(28.dp)
            ),
            content = content
        )
    }
}
