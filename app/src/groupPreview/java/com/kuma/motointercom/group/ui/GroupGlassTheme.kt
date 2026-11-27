package com.kuma.motointercom.group.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kuma.motointercom.MotoComTheme
import com.kuma.motointercom.MotoComThemeMode

@Immutable
internal data class GroupGlassColors(
    val isLight: Boolean,
    val canvas: Color,
    val canvasDeep: Color,
    val auraLime: Color,
    val auraSky: Color,
    val auraRose: Color,
    val card: Color,
    val cardElevated: Color,
    val glass: Color,
    val pillTrack: Color,
    val pillActive: Color,
    val codeCell: Color,
    val speakingSurface: Color,
    val privateSurface: Color,
    val dangerSoft: Color,
    val warningSoft: Color,
    val infoSoft: Color,
    val dock: Color,
    val chipNeutral: Color,
    val terminalSurface: Color,
    val hairlineBorder: Color,
    val glassBorder: Color,
    val limeActiveBorder: Color,
    val privateActiveBorder: Color,
    val warningBorder: Color,
    val dangerBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textOnObsidian: Color,
    val textOnLime: Color,
    val textLimeAccent: Color,
    val textCloud: Color,
    val textPrivate: Color,
    val textWarning: Color,
    val textDanger: Color,
    val textTerminal: Color,
    val brandObsidian: Color,
    val brandLime: Color,
    val brandLimeDot: Color,
    val statusOnline: Color,
    val statusCloud: Color,
    val statusPrivate: Color,
    val statusWarning: Color,
    val statusDanger: Color,
    val statusDisabled: Color,
    val switchOn: Color,
    val switchOff: Color,
    val switchThumb: Color,
    val sliderTrack: Color,
    val sliderFill: Color,
    val overlayScrim: Color,
    val avatarPalette: List<Color>,
    val avatarInk: Color
)

private val GroupAvatarPalette = listOf(
    Color(0xFFC5F569),
    Color(0xFFFAD1B0),
    Color(0xFFB9C8F7),
    Color(0xFFC7D0DA),
    Color(0xFFBCE7FB),
    Color(0xFFBAEED2)
)

internal val DaylightGroupGlassColors = GroupGlassColors(
    isLight = true,
    canvas = Color(0xFFF4F6F3),
    canvasDeep = Color(0xFFEEF1EC),
    auraLime = Color(0x5CB8F84E),
    auraSky = Color(0x3880E4F8),
    auraRose = Color(0x38F994A4),
    card = Color(0xD6FFFFFF),
    cardElevated = Color(0xF0FFFFFF),
    glass = Color(0xA8FFFFFF),
    pillTrack = Color(0xC7E4EAE2),
    pillActive = Color(0xFFFFFFFF),
    codeCell = Color(0xFFEEF1EC),
    speakingSurface = Color(0xFFF2FCE3),
    privateSurface = Color(0xFFFDECEF),
    dangerSoft = Color(0xFFFDECEF),
    warningSoft = Color(0xFFFEF3D6),
    infoSoft = Color(0xFFE6F3FE),
    dock = Color(0xF0FFFFFF),
    chipNeutral = Color(0xFFE5EAE3),
    terminalSurface = Color(0xFF101517),
    hairlineBorder = Color(0x14141C18),
    glassBorder = Color(0xDBFFFFFF),
    limeActiveBorder = Color(0xFFC8F86A),
    privateActiveBorder = Color(0xFFF9C6CE),
    warningBorder = Color(0xFFF7CA63),
    dangerBorder = Color(0xFFF46B7A),
    textPrimary = Color(0xFF161B1D),
    textSecondary = Color(0xFF5C6961),
    textMuted = Color(0xFF647169),
    textOnObsidian = Color(0xFFFFFFFF),
    textOnLime = Color(0xFF142107),
    textLimeAccent = Color(0xFF3F7A10),
    textCloud = Color(0xFF1A6BB2),
    textPrivate = Color(0xFFB12639),
    textWarning = Color(0xFF96600A),
    textDanger = Color(0xFFC2263C),
    textTerminal = Color(0xFFBAC3BA),
    brandObsidian = Color(0xFF161B1D),
    brandLime = Color(0xFFBFF652),
    brandLimeDot = Color(0xFF9AE62E),
    statusOnline = Color(0xFF5BD63A),
    statusCloud = Color(0xFF2292E4),
    statusPrivate = Color(0xFFDE4054),
    statusWarning = Color(0xFFC98512),
    statusDanger = Color(0xFFDE4054),
    statusDisabled = Color(0xFFD6DDD5),
    switchOn = Color(0xFF5BD63A),
    switchOff = Color(0xFFD6DDD5),
    switchThumb = Color(0xFFFFFFFF),
    sliderTrack = Color(0xFFDCE3DB),
    sliderFill = Color(0xFF9AE62E),
    overlayScrim = Color(0x5C121A16),
    avatarPalette = GroupAvatarPalette,
    avatarInk = Color(0xFF161B1D)
)

internal val NightRideGroupGlassColors = GroupGlassColors(
    isLight = false,
    canvas = Color(0xFF0B0F11),
    canvasDeep = Color(0xFF070A0B),
    auraLime = Color(0x47348C3C),
    auraSky = Color(0x3D1D6F9E),
    auraRose = Color(0x4DC83750),
    card = Color(0xD6161D21),
    cardElevated = Color(0xEB1E262A),
    glass = Color(0xA31E272B),
    pillTrack = Color(0x14FFFFFF),
    pillActive = Color(0x29FFFFFF),
    codeCell = Color(0x14FFFFFF),
    speakingSurface = Color(0x21B8F44A),
    privateSurface = Color(0x38B83249),
    dangerSoft = Color(0x2EDE4054),
    warningSoft = Color(0x29F7CA63),
    infoSoft = Color(0x2955B9F8),
    dock = Color(0xEB1E262A),
    chipNeutral = Color(0x14FFFFFF),
    terminalSurface = Color(0xFF101517),
    hairlineBorder = Color(0x1AFFFFFF),
    glassBorder = Color(0x24FFFFFF),
    limeActiveBorder = Color(0x70B8F44A),
    privateActiveBorder = Color(0x70F46B7A),
    warningBorder = Color(0xFFF7CA63),
    dangerBorder = Color(0xFFF46B7A),
    textPrimary = Color(0xFFF4F6F3),
    textSecondary = Color(0xFFA1ACA5),
    textMuted = Color(0xFF8B968F),
    textOnObsidian = Color(0xFFF4F6F3),
    textOnLime = Color(0xFF142107),
    textLimeAccent = Color(0xFFC8F86A),
    textCloud = Color(0xFF55B9F8),
    textPrivate = Color(0xFFF46B7A),
    textWarning = Color(0xFFF7CA63),
    textDanger = Color(0xFFF46B7A),
    textTerminal = Color(0xFFBAC3BA),
    brandObsidian = Color(0xFF1E2629),
    brandLime = Color(0xFFBFF652),
    brandLimeDot = Color(0xFFBFF652),
    statusOnline = Color(0xFF6FE244),
    statusCloud = Color(0xFF55B9F8),
    statusPrivate = Color(0xFFF46B7A),
    statusWarning = Color(0xFFF7CA63),
    statusDanger = Color(0xFFF46B7A),
    statusDisabled = Color(0xFF2D3834),
    switchOn = Color(0xFF6FE244),
    switchOff = Color(0xFF2D3834),
    switchThumb = Color(0xFFFFFFFF),
    sliderTrack = Color(0xFF293330),
    sliderFill = Color(0xFFBFF652),
    overlayScrim = Color(0xAD040708),
    avatarPalette = GroupAvatarPalette,
    avatarInk = Color(0xFF111618)
)

internal object GroupGlassTypography {
    val hero = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val brand = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val pageTitle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val cardTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp)
    val seatTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val compact = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val action = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    val pill = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val badge = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
    val codeDigit = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    val roomCode = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, fontFamily = FontFamily.Monospace)
    val metric = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, fontFamily = FontFamily.Monospace)
    val monoLog = TextStyle(fontSize = 11.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, fontFamily = FontFamily.Monospace)
}

internal val LocalGroupGlassColors = staticCompositionLocalOf { DaylightGroupGlassColors }

internal object GroupGlassPalette {
    val current: GroupGlassColors
        @Composable get() = LocalGroupGlassColors.current
}

@Composable
internal fun GroupGlassTheme(
    themeMode: MotoComThemeMode = MotoComThemeMode.DAYLIGHT,
    content: @Composable () -> Unit
) {
    val glassColors = if (themeMode == MotoComThemeMode.NIGHTRIDE) {
        NightRideGroupGlassColors
    } else {
        DaylightGroupGlassColors
    }
    MotoComTheme(theme = themeMode) {
        CompositionLocalProvider(LocalGroupGlassColors provides glassColors) {
            content()
        }
    }
}

internal fun avatarColorFor(seed: String, palette: List<Color>): Color {
    if (palette.isEmpty()) return Color(0xFFC5F569)
    val hash = seed.fold(0) { acc, char -> (acc * 31) + char.code }
    val index = ((hash % palette.size) + palette.size) % palette.size
    return palette[index]
}

internal fun avatarInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "骑"
    val firstChar = trimmed.first()
    return if (firstChar.isLetter()) firstChar.uppercase() else firstChar.toString()
}

@Immutable
internal data class GroupSystemBarStyle(
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val windowBackgroundColor: Int,
    val isLightStatusBars: Boolean,
    val isLightNavigationBars: Boolean
)

internal fun groupSystemBarStyle(
    themeMode: MotoComThemeMode,
    sdkInt: Int = Build.VERSION.SDK_INT
): GroupSystemBarStyle {
    val isLight = themeMode == MotoComThemeMode.DAYLIGHT
    val canvasArgb = if (isLight) {
        DaylightGroupGlassColors.canvas.toArgb()
    } else {
        NightRideGroupGlassColors.canvas.toArgb()
    }
    val legacyDarkNavArgb = NightRideGroupGlassColors.canvas.toArgb()
    val supportsLightNav = sdkInt >= Build.VERSION_CODES.O_MR1
    return GroupSystemBarStyle(
        statusBarColor = canvasArgb,
        navigationBarColor = if (isLight && !supportsLightNav) legacyDarkNavArgb else canvasArgb,
        windowBackgroundColor = canvasArgb,
        isLightStatusBars = isLight,
        isLightNavigationBars = isLight && supportsLightNav
    )
}
