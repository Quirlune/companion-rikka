package dev.gray.companion.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings

private val LocalBodyColorsEnabled = staticCompositionLocalOf { false }

@Composable
internal fun BodyMarkdownBlock(content: String, modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current, onClickCitation: (String) -> Unit = {}) {
    val request = LocalStreamPresentationRequest.current
    val frame = rememberStreamPresentation(content, request)
    CompositionLocalProvider(
        LocalBodyColorsEnabled provides true,
        LocalStreamPresentationFrame provides frame,
    ) {
        MarkdownBlock(frame?.text ?: content, modifier, style, onClickCitation)
    }
}

internal fun normalizedBodyColor(value: String, fallback: String): String =
    value.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: fallback

internal fun bodyColor(value: String, fallback: String): Color =
    Color(0xFF000000L or normalizedBodyColor(value, fallback).drop(1).toLong(16))

/** Paired double quotes only. Never interpret apostrophes, measurements or escaped quotes as dialogue. */
internal fun bodyQuoteRanges(text: String): List<IntRange> {
    val result = mutableListOf<IntRange>()
    var opening = -1
    var closer = ' '
    for (i in text.indices) {
        val c = text[i]
        if (c == '\n' || c == '\u0000') { opening = -1; continue }
        var slashes = 0
        var previous = i - 1
        while (previous >= 0 && text[previous--] == '\\') slashes++
        if (slashes % 2 != 0) continue
        if (opening >= 0) {
            if (c == closer) {
                if (i > opening + 1) result += opening..i
                opening = -1
            }
        } else if (c == '“' || (c == '"' && (i == 0 || !text[i - 1].isLetterOrDigit()))) {
            opening = i
            closer = if (c == '“') '”' else '"'
        }
    }
    return result
}

@Composable
internal fun bodyColoredText(text: AnnotatedString): AnnotatedString {
    val colored = if (!LocalBodyColorsEnabled.current) text else {
        val settings = LocalSettings.current.displaySetting
        remember(text, settings.companionBoldColor, settings.companionItalicColor, settings.companionQuoteColor) {
            colorBodyText(text, settings)
        }
    }
    return streamFadeText(colored)
}

internal fun colorBodyText(text: AnnotatedString, settings: DisplaySetting): AnnotatedString {
    val code = BooleanArray(text.length)
    text.spanStyles.filter { it.item.fontFeatureSettings?.contains("'calt' 0") == true }.forEach {
        for (i in it.start until it.end) code[i] = true
    }
    val result = AnnotatedString.Builder(text)
    fun add(color: Color, start: Int, end: Int) {
        var from = start
        for (i in start..end) {
            if (i == end || code[i]) {
                if (from < i) result.addStyle(SpanStyle(color = color), from, i)
                from = i + 1
            }
        }
    }
    text.spanStyles.forEach { span ->
        val color = when {
            span.item.fontWeight == FontWeight.Bold -> bodyColor(settings.companionBoldColor, "#767676")
            span.item.fontStyle == FontStyle.Italic -> bodyColor(settings.companionItalicColor, "#767676")
            else -> null
        }
        if (color != null) add(color, span.start, span.end)
    }
    val visible = text.text.toCharArray()
    code.indices.filter { code[it] }.forEach { visible[it] = '\u0000' }
    bodyQuoteRanges(String(visible)).forEach {
        add(bodyColor(settings.companionQuoteColor, "#A0712F"), it.first, it.last + 1)
    }
    return result.toAnnotatedString()
}
