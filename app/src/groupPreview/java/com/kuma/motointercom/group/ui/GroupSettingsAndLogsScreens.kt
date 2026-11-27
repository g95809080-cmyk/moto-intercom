package com.kuma.motointercom.group.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuma.motointercom.AudioRouteSelection
import com.kuma.motointercom.BuildConfig
import com.kuma.motointercom.MAX_VOX_SENSITIVITY
import com.kuma.motointercom.MIN_VOX_SENSITIVITY
import com.kuma.motointercom.MotoComThemeMode
import com.kuma.motointercom.group.GroupMode
import com.kuma.motointercom.group.cloud.GroupCloudPreferences
import com.kuma.motointercom.showDeveloperCloudSettings
import java.io.File
import kotlin.math.roundToInt

private val AUTH_HEADER_NAME_REGEX = Regex(
    """(?i)(?:"(?:proxy-)?authorization"|(?<![A-Za-z0-9_-])(?:proxy-)?authorization(?![A-Za-z0-9_-]))"""
)
private val STANDALONE_AUTH_SCHEME_REGEX = Regex(
    """(?i)\b(?:digest|oauth|aws4-hmac-sha256|hmac[A-Za-z0-9_-]*)(?![A-Za-z0-9_-])"""
)
private val BEARER_KEYWORD_REGEX = Regex("""(?i)\bbearer\b""")
private val SENSITIVE_KEY_REGEX = Regex(
    """(?i)(?:"(?:[A-Za-z0-9_.-]*(?:passphrase|password|passwd|secret|token|credential|signature|cookie|cnonce|nonce|opaque|api_?key|access_?key|private_?key|invite(?:_?code)?|room[_ -]?code|join[_ -]?code|local[_ -]?code|group[_ -]?code|pair[_ -]?code|auth[_ -]?code|ice-pwd|ice-ufrag)|code|pin|psk)"|\b(?:[A-Za-z0-9_.-]*(?:passphrase|password|passwd|secret|token|credential|signature|cookie|cnonce|nonce|opaque|api_?key|access_?key|private_?key|invite(?:_?code)?|room[_ -]?code|join[_ -]?code|local[_ -]?code|group[_ -]?code|pair[_ -]?code|auth[_ -]?code|ice-pwd|ice-ufrag)|code|pin|psk)\b)"""
)
private val UFRAG_KEYWORD_REGEX = Regex("""(?i)\bufrag\b""")
private val SIX_DIGIT_STANDALONE_REGEX = Regex("(?<![0-9])[0-9]{6}(?![0-9])")

private fun isHttpTchar(ch: Char): Boolean =
    ch in '0'..'9' || ch in 'A'..'Z' || ch in 'a'..'z' ||
        ch in "!#$%&'*+-.^_`|~"

private fun isAuthOwsStart(text: String, index: Int): Boolean {
    if (index >= text.length) return false
    val ch = text[index]
    return ch == ' ' || ch == '\t' || text.startsWith("\\t", index)
}

private fun skipAuthOws(text: String, startIndex: Int): Int {
    var pos = startIndex
    while (pos < text.length) {
        val ch = text[pos]
        when {
            ch == ' ' || ch == '\t' -> pos++
            text.startsWith("\\t", pos) -> pos += 2
            ch == '\r' && pos + 1 < text.length && text[pos + 1] == '\n' && isAuthOwsStart(text, pos + 2) -> pos += 2
            ch == '\n' && isAuthOwsStart(text, pos + 1) -> pos++
            text.startsWith("\\r\\n", pos) && isAuthOwsStart(text, pos + 4) -> pos += 4
            text.startsWith("\\n", pos) && isAuthOwsStart(text, pos + 2) -> pos += 2
            else -> break
        }
    }
    return pos
}

private fun findQuotedValueEnd(text: String, startIndex: Int, quote: Char): Int {
    var pos = startIndex + 1
    while (pos < text.length) {
        val ch = text[pos]
        if (ch == '\r' || ch == '\n') return -1
        if (ch == '\\' && pos + 1 < text.length) {
            pos += 2
            continue
        }
        if (ch == quote) return pos + 1
        pos++
    }
    return -1
}

private fun findUnclosedQuotedValueEnd(text: String, startIndex: Int): Int {
    var pos = startIndex
    while (pos < text.length) {
        val ch = text[pos]
        if (ch == '\r') {
            if (pos + 1 < text.length && text[pos + 1] == '\n' && isAuthOwsStart(text, pos + 2)) {
                pos += 2
                continue
            }
            break
        }
        if (ch == '\n') {
            if (isAuthOwsStart(text, pos + 1)) {
                pos++
                continue
            }
            break
        }
        pos++
    }
    return pos
}

private fun openEnclosingDelimitersBefore(text: String, limit: Int): Pair<Int, Int> {
    var pos = 0
    var brackets = 0
    var braces = 0
    while (pos < limit) {
        val ch = text[pos]
        when (ch) {
            '\r', '\n' -> {
                brackets = 0
                braces = 0
                pos++
            }
            '"' -> {
                val end = findQuotedValueEnd(text, pos, '"')
                pos = if (end in (pos + 1)..limit) end else pos + 1
            }
            '[' -> {
                brackets++
                pos++
            }
            ']' -> {
                if (brackets > 0) brackets--
                pos++
            }
            '{' -> {
                braces++
                pos++
            }
            '}' -> {
                if (braces > 0) braces--
                pos++
            }
            else -> pos++
        }
    }
    return brackets to braces
}

private fun findUnquotedAuthValueEnd(
    text: String,
    startIndex: Int,
    stopOnUnmatchedBracket: Boolean,
    stopOnUnmatchedBrace: Boolean
): Int {
    var pos = startIndex
    var bracketDepth = 0
    var braceDepth = 0
    while (pos < text.length) {
        val ch = text[pos]
        if (ch == '\r') {
            if (pos + 1 < text.length && text[pos + 1] == '\n' && isAuthOwsStart(text, pos + 2)) {
                pos += 2
                continue
            }
            break
        }
        if (ch == '\n') {
            if (isAuthOwsStart(text, pos + 1)) {
                pos++
                continue
            }
            break
        }
        if (text.startsWith("\\\\", pos)) {
            pos += 2
            continue
        }
        if (text.startsWith("\\r\\n", pos)) {
            if (isAuthOwsStart(text, pos + 4)) {
                pos += 4
                continue
            }
            break
        }
        if (text.startsWith("\\n", pos)) {
            if (isAuthOwsStart(text, pos + 2)) {
                pos += 2
                continue
            }
            break
        }
        if (text.startsWith("\\r", pos)) break
        when (ch) {
            '"' -> {
                val quotedEnd = findQuotedValueEnd(text, pos, '"')
                if (quotedEnd > pos) {
                    pos = quotedEnd
                } else {
                    return findUnclosedQuotedValueEnd(text, pos + 1)
                }
            }
            '[' -> {
                bracketDepth++
                pos++
            }
            ']' -> {
                if (bracketDepth > 0) {
                    bracketDepth--
                    pos++
                } else if (stopOnUnmatchedBracket) {
                    return pos
                } else {
                    pos++
                }
            }
            '{' -> {
                braceDepth++
                pos++
            }
            '}' -> {
                if (braceDepth > 0) {
                    braceDepth--
                    pos++
                } else if (stopOnUnmatchedBrace) {
                    return pos
                } else {
                    pos++
                }
            }
            else -> pos++
        }
    }
    return pos
}

private fun scanUnquotedTokenEnd(
    text: String,
    startIndex: Int,
    allowSingleQuote: Boolean
): Int {
    var pos = startIndex
    var bracketDepth = 0
    while (pos < text.length) {
        val ch = text[pos]
        if (ch.isWhitespace() || ch == '"' || ch == ',' || ch == ';' || ch == '}') break
        if (!allowSingleQuote && ch == '\'') break
        if (ch == '[') {
            bracketDepth++
            pos++
            continue
        }
        if (ch == ']') {
            if (bracketDepth > 0) {
                bracketDepth--
                pos++
                continue
            }
            break
        }
        if (ch == '\\') {
            if (pos + 1 < text.length) {
                val next = text[pos + 1]
                if (next == 't' || next == 'r' || next == 'n') break
                if (next == '\\') {
                    pos += 2
                    continue
                }
            }
            pos++
            continue
        }
        pos++
    }
    return pos
}

private fun scanQuotedOrTokenValueEnd(
    text: String,
    startIndex: Int,
    allowSingleQuoteInUnquoted: Boolean
): Pair<Int, Char?> {
    if (startIndex >= text.length) return startIndex to null
    val firstChar = text[startIndex]
    if (firstChar == '"' || firstChar == '\'') {
        val quotedEnd = findQuotedValueEnd(text, startIndex, firstChar)
        return if (quotedEnd > startIndex) {
            quotedEnd to firstChar
        } else {
            findUnclosedQuotedValueEnd(text, startIndex) to null
        }
    }
    return scanUnquotedTokenEnd(text, startIndex, allowSingleQuoteInUnquoted) to null
}

private fun redactAuthorizationHeaders(raw: String): String {
    var searchPos = 0
    var emittedPos = 0
    var output: StringBuilder? = null
    while (searchPos < raw.length) {
        val match = AUTH_HEADER_NAME_REGEX.find(raw, searchPos) ?: break
        val afterName = skipAuthOws(raw, match.range.last + 1)
        if (afterName >= raw.length || (raw[afterName] != ':' && raw[afterName] != '=')) {
            searchPos = match.range.last + 1
            continue
        }
        val valueStart = skipAuthOws(raw, afterName + 1)
        if (valueStart >= raw.length) {
            break
        }
        val firstChar = raw[valueStart]
        val isQuotedStart = firstChar == '"' || firstChar == '\''
        val quotedEnd = if (isQuotedStart) findQuotedValueEnd(raw, valueStart, firstChar) else -1
        val valueEnd = when {
            quotedEnd > valueStart -> quotedEnd
            isQuotedStart -> findUnclosedQuotedValueEnd(raw, valueStart)
            else -> {
                val (openBrackets, openBraces) = openEnclosingDelimitersBefore(raw, match.range.first)
                findUnquotedAuthValueEnd(
                    text = raw,
                    startIndex = valueStart,
                    stopOnUnmatchedBracket = openBrackets > 0,
                    stopOnUnmatchedBrace = openBraces > 0
                )
            }
        }
        if (valueEnd <= valueStart) {
            searchPos = valueStart
            continue
        }
        val builder = output ?: StringBuilder(raw.length).also { output = it }
        builder.append(raw, emittedPos, valueStart)
        if (quotedEnd > valueStart) {
            builder.append(firstChar).append("[REDACTED]").append(firstChar)
        } else {
            builder.append("[REDACTED]")
        }
        emittedPos = valueEnd
        searchPos = valueEnd
    }
    val builder = output ?: return raw
    builder.append(raw, emittedPos, raw.length)
    return builder.toString()
}

private fun scanAuthParamListEnd(text: String, startIndex: Int): Int {
    var pos = startIndex
    var lastValidEnd = -1
    while (pos < text.length) {
        val keyStart = pos
        while (pos < text.length && isHttpTchar(text[pos])) pos++
        if (pos == keyStart) break
        val eqPos = skipAuthOws(text, pos)
        if (eqPos >= text.length || text[eqPos] != '=') break
        val valueStart = skipAuthOws(text, eqPos + 1)
        val (valueEnd, _) = scanQuotedOrTokenValueEnd(text, valueStart, allowSingleQuoteInUnquoted = true)
        if (valueEnd <= valueStart) break
        lastValidEnd = valueEnd
        val commaPos = skipAuthOws(text, valueEnd)
        if (commaPos >= text.length || text[commaPos] != ',') break
        pos = skipAuthOws(text, commaPos + 1)
    }
    return lastValidEnd
}

private fun redactStandaloneAuthParams(raw: String): String {
    var searchPos = 0
    var emittedPos = 0
    var output: StringBuilder? = null
    while (searchPos < raw.length) {
        val match = STANDALONE_AUTH_SCHEME_REGEX.find(raw, searchPos) ?: break
        val paramsStart = skipAuthOws(raw, match.range.last + 1)
        if (paramsStart <= match.range.last + 1 || paramsStart >= raw.length) {
            searchPos = match.range.last + 1
            continue
        }
        val paramsEnd = scanAuthParamListEnd(raw, paramsStart)
        if (paramsEnd <= paramsStart) {
            searchPos = match.range.last + 1
            continue
        }
        val builder = output ?: StringBuilder(raw.length).also { output = it }
        builder.append(raw, emittedPos, paramsStart)
        builder.append("[REDACTED]")
        emittedPos = paramsEnd
        searchPos = paramsEnd
    }
    val builder = output ?: return raw
    builder.append(raw, emittedPos, raw.length)
    return builder.toString()
}

private fun redactStandaloneBearer(raw: String): String {
    var searchPos = 0
    var emittedPos = 0
    var output: StringBuilder? = null
    while (searchPos < raw.length) {
        val match = BEARER_KEYWORD_REGEX.find(raw, searchPos) ?: break
        val afterOws = skipAuthOws(raw, match.range.last + 1)
        val valueStart = when {
            afterOws < raw.length && (raw[afterOws] == ':' || raw[afterOws] == '=') ->
                skipAuthOws(raw, afterOws + 1)
            afterOws > match.range.last + 1 -> afterOws
            else -> -1
        }
        if (valueStart < 0 || valueStart >= raw.length) {
            searchPos = match.range.last + 1
            continue
        }
        val (valueEnd, quote) = scanQuotedOrTokenValueEnd(raw, valueStart, allowSingleQuoteInUnquoted = false)
        if (valueEnd <= valueStart) {
            searchPos = match.range.last + 1
            continue
        }
        val builder = output ?: StringBuilder(raw.length).also { output = it }
        builder.append(raw, emittedPos, valueStart)
        if (quote != null) {
            builder.append(quote).append("[REDACTED]").append(quote)
        } else {
            builder.append("[REDACTED]")
        }
        emittedPos = valueEnd
        searchPos = valueEnd
    }
    val builder = output ?: return raw
    builder.append(raw, emittedPos, raw.length)
    return builder.toString()
}

private fun redactSensitiveKeyValues(raw: String): String {
    var searchPos = 0
    var emittedPos = 0
    var output: StringBuilder? = null
    while (searchPos < raw.length) {
        val match = SENSITIVE_KEY_REGEX.find(raw, searchPos) ?: break
        val sepPos = skipAuthOws(raw, match.range.last + 1)
        if (sepPos >= raw.length || (raw[sepPos] != ':' && raw[sepPos] != '=')) {
            searchPos = match.range.last + 1
            continue
        }
        val valueStart = skipAuthOws(raw, sepPos + 1)
        if (valueStart >= raw.length) {
            break
        }
        val (valueEnd, quote) = scanQuotedOrTokenValueEnd(raw, valueStart, allowSingleQuoteInUnquoted = false)
        if (valueEnd <= valueStart) {
            searchPos = valueStart
            continue
        }
        val builder = output ?: StringBuilder(raw.length).also { output = it }
        builder.append(raw, emittedPos, valueStart)
        if (quote != null) {
            builder.append(quote).append("[REDACTED]").append(quote)
        } else {
            builder.append("[REDACTED]")
        }
        emittedPos = valueEnd
        searchPos = valueEnd
    }
    val builder = output ?: return raw
    builder.append(raw, emittedPos, raw.length)
    return builder.toString()
}

private fun redactUfrag(raw: String): String {
    var searchPos = 0
    var emittedPos = 0
    var output: StringBuilder? = null
    while (searchPos < raw.length) {
        val match = UFRAG_KEYWORD_REGEX.find(raw, searchPos) ?: break
        val valueStart = skipAuthOws(raw, match.range.last + 1)
        if (valueStart <= match.range.last + 1 || valueStart >= raw.length) {
            searchPos = match.range.last + 1
            continue
        }
        val valueEnd = scanUnquotedTokenEnd(raw, valueStart, allowSingleQuote = false)
        if (valueEnd <= valueStart) {
            searchPos = match.range.last + 1
            continue
        }
        val builder = output ?: StringBuilder(raw.length).also { output = it }
        builder.append(raw, emittedPos, valueStart)
        builder.append("[REDACTED]")
        emittedPos = valueEnd
        searchPos = valueEnd
    }
    val builder = output ?: return raw
    builder.append(raw, emittedPos, raw.length)
    return builder.toString()
}

internal fun redactDiagnosticLine(raw: String): String {
    val maskedAuth = redactAuthorizationHeaders(raw)
    val maskedStandaloneAuth = redactStandaloneAuthParams(maskedAuth)
    val maskedBearer = redactStandaloneBearer(maskedStandaloneAuth)
    val maskedKeys = redactSensitiveKeyValues(maskedBearer)
    val maskedUfrag = redactUfrag(maskedKeys)
    return SIX_DIGIT_STANDALONE_REGEX.replace(maskedUfrag, "******")
}

internal fun redactDiagnosticLines(lines: List<String>): String =
    lines.joinToString("\n", transform = ::redactDiagnosticLine)

internal fun copyRedactedDiagnosticLogs(context: Context, lines: List<String>): Boolean {
    val payload = redactDiagnosticLines(lines)
    if (payload.isBlank()) return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("摩声诊断日志", payload))
    return true
}

internal fun redactDiagnosticExportFile(file: File): File {
    val parentDir = file.parentFile ?: file.absoluteFile.parentFile
    val tempFile = File(parentDir, "${file.name}.redacted.tmp")
    try {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                var firstLine = true
                reader.forEachLine { line ->
                    if (!firstLine) writer.newLine()
                    firstLine = false
                    writer.write(redactDiagnosticLine(line))
                }
            }
        }
        if (!tempFile.renameTo(file)) {
            tempFile.inputStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.delete()
        }
        return file
    } catch (error: Throwable) {
        tempFile.delete()
        file.delete()
        throw error
    }
}

@Composable
internal fun GroupSettingsContent(
    nickname: String,
    onSaveNickname: (String) -> Unit,
    inCall: Boolean,
    cloudVoiceActive: Boolean = false,
    themeMode: MotoComThemeMode,
    onSelectTheme: (MotoComThemeMode) -> Unit,
    route: AudioRouteSelection,
    onRoute: (AudioRouteSelection) -> Unit,
    voxEnabled: Boolean,
    onVox: (Boolean) -> Unit,
    voxSensitivity: Int,
    onVoxSensitivityChange: (Int) -> Unit,
    onVoxSensitivityCommit: (Int) -> Unit = {},
    rnnoiseEnabled: Boolean,
    onOpenLogs: () -> Unit,
    onOpenSystemPermissions: () -> Unit,
    allowCustomServer: Boolean,
    onOpenDeveloperSettings: () -> Unit,
    onBack: () -> Unit
) {
    val colors = GroupGlassPalette.current
    var nicknameDraft by remember(nickname) { mutableStateOf(nickname) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_screen"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassIconCircleButton(
                icon = GlassIconKind.BACK,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.testTag("settings_back")
            )
            Text(
                text = "系统设置",
                style = GroupGlassTypography.pageTitle,
                color = colors.textPrimary
            )
        }
        GlassPillChip(
            label = if (themeMode == MotoComThemeMode.NIGHTRIDE) "夜航" else "白昼",
            containerColor = colors.card,
            contentColor = colors.textSecondary,
            borderColor = colors.glassBorder
        )
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "骑手昵称",
                style = GroupGlassTypography.seatTitle,
                color = colors.textPrimary
            )
            if (inCall) {
                GlassPillChip(
                    label = "通话中锁定",
                    containerColor = colors.warningSoft,
                    contentColor = colors.textWarning
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = nicknameDraft,
                onValueChange = { nicknameDraft = it.take(16) },
                enabled = !inCall,
                singleLine = true,
                placeholder = { Text("输入车队显示昵称") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_nickname_input")
            )
            GlassActionButton(
                label = "保存",
                onClick = { onSaveNickname(nicknameDraft.trim()) },
                enabled = !inCall && nicknameDraft.trim().isNotEmpty() && nicknameDraft.trim() != nickname.trim(),
                variant = GlassCtaVariant.OBSIDIAN,
                minHeight = 48.dp,
                modifier = Modifier.testTag("settings_nickname_save")
            )
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "外观主题",
            style = GroupGlassTypography.seatTitle,
            color = colors.textPrimary
        )
        GlassSegmentedControl(
            options = listOf(
                GlassSegmentOption(
                    value = MotoComThemeMode.DAYLIGHT,
                    label = "白昼日光",
                    testTag = "theme_option_daylight",
                    dotColor = colors.brandLimeDot
                ),
                GlassSegmentOption(
                    value = MotoComThemeMode.NIGHTRIDE,
                    label = "夜航深空",
                    testTag = "theme_option_nightride",
                    dotColor = colors.brandLimeDot
                )
            ),
            selected = themeMode,
            onSelect = onSelectTheme
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassCard(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(14.dp)
        ) {
            Text(
                text = "音频通路",
                style = GroupGlassTypography.badge,
                color = colors.textSecondary
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioRouteSelection.entries.forEach { selection ->
                    val selected = route == selection
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onRoute(selection) }
                            .testTag("settings_route_${selection.name}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) colors.brandObsidian else colors.chipNeutral,
                        border = if (selected && !colors.isLight) BorderStroke(1.dp, colors.limeActiveBorder) else null
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GlassVectorIcon(
                                kind = when (selection) {
                                    AudioRouteSelection.BLUETOOTH -> GlassIconKind.HEADSET
                                    AudioRouteSelection.EARPIECE -> GlassIconKind.EARPIECE
                                    AudioRouteSelection.SPEAKER -> GlassIconKind.SPEAKER
                                },
                                tint = if (selected) colors.brandLime else colors.textSecondary,
                                size = 16.dp
                            )
                            Text(
                                text = when (selection) {
                                    AudioRouteSelection.BLUETOOTH -> "蓝牙耳机"
                                    AudioRouteSelection.EARPIECE -> "听筒有线"
                                    AudioRouteSelection.SPEAKER -> "扬声器"
                                },
                                style = GroupGlassTypography.badge,
                                color = if (selected) colors.textOnObsidian else colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        GlassCard(
            modifier = Modifier.weight(1f),
            containerColor = if (voxEnabled) colors.speakingSurface else colors.card,
            borderColor = if (voxEnabled) colors.limeActiveBorder else colors.glassBorder,
            contentPadding = PaddingValues(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VOX 声控",
                    style = GroupGlassTypography.badge,
                    color = colors.textSecondary
                )
                GlassSwitch(
                    checked = voxEnabled,
                    onCheckedChange = onVox,
                    modifier = Modifier.testTag("settings_vox_switch")
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = voxSensitivity.toString(),
                    style = GroupGlassTypography.metric,
                    color = if (voxEnabled) colors.textLimeAccent else colors.textMuted
                )
                Text(
                    text = when {
                        !voxEnabled -> "已关闭"
                        inCall && cloudVoiceActive -> "灵敏度 · 本地直连生效"
                        else -> "灵敏度"
                    },
                    style = GroupGlassTypography.badge,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            var latestVoxSensitivity by remember { mutableIntStateOf(voxSensitivity) }
            latestVoxSensitivity = voxSensitivity
            Slider(
                value = voxSensitivity.toFloat(),
                onValueChange = { value ->
                    val next = value.roundToInt()
                    latestVoxSensitivity = next
                    onVoxSensitivityChange(next)
                },
                onValueChangeFinished = { onVoxSensitivityCommit(latestVoxSensitivity) },
                valueRange = MIN_VOX_SENSITIVITY.toFloat()..MAX_VOX_SENSITIVITY.toFloat(),
                enabled = voxEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = colors.switchThumb,
                    activeTrackColor = colors.sliderFill,
                    inactiveTrackColor = colors.sliderTrack
                ),
                modifier = Modifier.testTag("settings_vox_slider")
            )
        }
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "AI 降噪引擎（RNNoise）",
                    style = GroupGlassTypography.seatTitle,
                    color = colors.textPrimary
                )
                Text(
                    text = if (BuildConfig.RNNOISE_BETA) {
                        if (rnnoiseEnabled) "当前测试构建已启用降噪" else "当前测试构建未启用降噪"
                    } else {
                        "群组预览版使用系统回声消除与语音门限"
                    },
                    style = GroupGlassTypography.badge,
                    color = colors.textSecondary
                )
            }
            GlassPillChip(
                label = if (BuildConfig.RNNOISE_BETA && rnnoiseEnabled) "已启用" else "标准模式",
                containerColor = if (BuildConfig.RNNOISE_BETA && rnnoiseEnabled) colors.speakingSurface else colors.chipNeutral,
                contentColor = if (BuildConfig.RNNOISE_BETA && rnnoiseEnabled) colors.textLimeAccent else colors.textSecondary
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsToolTile(
                title = "日志诊断",
                subtitle = "查看与导出脱敏日志",
                icon = GlassIconKind.LOGS,
                onClick = onOpenLogs,
                modifier = Modifier.weight(1f).testTag("settings_open_logs")
            )
            SettingsToolTile(
                title = "系统权限",
                subtitle = "蓝牙 / 附近设备 / 麦克风",
                icon = GlassIconKind.SHIELD,
                onClick = onOpenSystemPermissions,
                modifier = Modifier.weight(1f).testTag("settings_open_permissions")
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsToolTile(
                title = "使用指南",
                subtitle = "本地直连与云端兜底",
                icon = GlassIconKind.HELP,
                onClick = { showGuideDialog = true },
                modifier = Modifier.weight(1f).testTag("settings_open_guide")
            )
            SettingsToolTile(
                title = "关于摩声",
                subtitle = BuildConfig.VERSION_NAME,
                icon = GlassIconKind.INFO,
                onClick = { showAboutDialog = true },
                modifier = Modifier.weight(1f).testTag("settings_open_about")
            )
        }
    }

    if (showDeveloperCloudSettings(allowCustomServer, GroupMode.HYBRID)) {
        GlassActionButton(
            label = "开发者设置（调试云端地址）",
            onClick = onOpenDeveloperSettings,
            variant = GlassCtaVariant.SECONDARY,
            leadingIcon = GlassIconKind.CODE,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_developer_override")
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "本地直连 + 云端混合引擎",
            style = GroupGlassTypography.badge,
            color = colors.textMuted
        )
        Text(
            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = GroupGlassTypography.monoLog,
            color = colors.textMuted
        )
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("摩声 R5 使用说明") },
            text = {
                Text(
                    "1. 本地 4 人：无需蜂窝网络，通过 Wi-Fi Direct 直连组队。\n" +
                        "2. 双模 4 人：默认优先本地直连，拉远断开时整组切至云端兜底，靠近后静默预连回切本地。\n" +
                        "3. 网络 50 人：通过云端公共频道大群对讲，群主可审批入房与转让房主。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showGuideDialog = false }) { Text("知道了") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于摩声 MotoCom") },
            text = {
                Text(
                    "版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n" +
                        "包名：${BuildConfig.APPLICATION_ID}\n" +
                        "云端地址：${GroupCloudPreferences.DEFAULT_SERVER_URL}"
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SettingsToolTile(
    title: String,
    subtitle: String,
    icon: GlassIconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = GroupGlassPalette.current
    Surface(
        modifier = modifier
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassVectorIcon(kind = icon, tint = colors.textPrimary, size = 19.dp)
            Text(
                text = title,
                style = GroupGlassTypography.seatTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = GroupGlassTypography.badge,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun GroupLogsContent(
    logLines: List<String>,
    statusHint: String?,
    onExportLogs: () -> Unit,
    onCopyLogs: () -> Unit,
    onBack: () -> Unit
) {
    val colors = GroupGlassPalette.current
    val redactedLines = remember(logLines) { logLines.map(::redactDiagnosticLine) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("logs_screen"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassIconCircleButton(
            icon = GlassIconKind.BACK,
            contentDescription = "返回设置",
            onClick = onBack,
            modifier = Modifier.testTag("logs_back")
        )
        Text(
            text = "日志诊断",
            style = GroupGlassTypography.pageTitle,
            color = colors.textPrimary
        )
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlassVectorIcon(kind = GlassIconKind.INFO, tint = colors.statusCloud, size = 16.dp)
            Text(
                text = "仅记录本机链路、切云屏障与音频事件；邀请码与凭证自动脱敏，不含通话内容。",
                style = GroupGlassTypography.compact,
                color = colors.textSecondary
            )
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp, max = 420.dp),
        shape = RoundedCornerShape(22.dp),
        color = colors.terminalSurface,
        border = BorderStroke(1.dp, NightRideGroupGlassColors.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "motocom-r5.log",
                    style = GroupGlassTypography.monoLog,
                    color = NightRideGroupGlassColors.textSecondary
                )
                GlassPillChip(
                    label = "2s 自动刷新",
                    containerColor = NightRideGroupGlassColors.speakingSurface,
                    contentColor = NightRideGroupGlassColors.textLimeAccent,
                    dotColor = NightRideGroupGlassColors.statusOnline
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (redactedLines.isEmpty()) {
                    Text(
                        text = "暂无诊断事件记录",
                        style = GroupGlassTypography.monoLog,
                        color = NightRideGroupGlassColors.textMuted
                    )
                } else {
                    redactedLines.forEach { line ->
                        val lineColor = when {
                            line.contains(" E ") || line.contains("FAILED") || line.contains("ERROR") -> NightRideGroupGlassColors.textDanger
                            line.contains(" W ") || line.contains("WARN") -> NightRideGroupGlassColors.textWarning
                            line.contains("CLOUD") || line.contains("RTC") -> NightRideGroupGlassColors.textCloud
                            line.contains("VOX") || line.contains("LOCAL") -> NightRideGroupGlassColors.textLimeAccent
                            else -> NightRideGroupGlassColors.textTerminal
                        }
                        Text(
                            text = line,
                            style = GroupGlassTypography.monoLog,
                            color = lineColor
                        )
                    }
                }
            }
        }
    }

    if (!statusHint.isNullOrBlank()) {
        Text(
            text = statusHint,
            style = GroupGlassTypography.compact,
            color = colors.textLimeAccent
        )
    }

    GlassActionButton(
        label = "导出全部日志",
        onClick = onExportLogs,
        variant = GlassCtaVariant.LIME,
        leadingIcon = GlassIconKind.EXPORT_SHARE,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("logs_export")
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassActionButton(
            label = "复制日志",
            onClick = onCopyLogs,
            variant = GlassCtaVariant.SECONDARY,
            leadingIcon = GlassIconKind.COPY,
            modifier = Modifier
                .weight(1f)
                .testTag("logs_copy")
        )
        GlassActionButton(
            label = "返回设置",
            onClick = onBack,
            variant = GlassCtaVariant.SECONDARY,
            modifier = Modifier
                .weight(1f)
                .testTag("logs_return_settings")
        )
    }
}
