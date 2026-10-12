package dev.gray.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import dev.gray.companion.core.MacroTemplateTokens

/** Display only: the editable buffer and all source-navigation spans remain raw template offsets. */
internal class PromptMacroVisualTransformation(
    private val previews: Map<Int, String>,
    private val activeStart: Int?,
    private val background: Color,
    private val foreground: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        data class Span(val start: Int, val end: Int, val shownStart: Int, val shownEnd: Int)
        val spans = mutableListOf<Span>()
        val shown = buildAnnotatedString {
            var end = 0
            MacroTemplateTokens.scan(text.text).tokens.filter { it.closed }.forEach { token ->
                append(text.text.substring(end, token.start))
                val start = length
                val preview = previews[token.start]
                if (token.start == activeStart || preview == null) append(text.text.substring(token.start, token.endExclusive))
                else {
                    append(preview.ifBlank { "暂无内容" })
                    addStyle(SpanStyle(background = background, color = foreground), start, length)
                }
                spans += Span(token.start, token.endExclusive, start, length)
                end = token.endExclusive
            }
            append(text.text.substring(end))
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val point = offset.coerceIn(0, text.length)
                var delta = 0
                for (span in spans) {
                    if (point < span.start) break
                    if (point <= span.end) return span.shownStart +
                        ((point - span.start).toLong() * (span.shownEnd - span.shownStart) / (span.end - span.start)).toInt()
                    delta = span.shownEnd - span.end
                }
                return (point + delta).coerceIn(0, shown.length)
            }
            override fun transformedToOriginal(offset: Int): Int {
                val point = offset.coerceIn(0, shown.length)
                var delta = 0
                for (span in spans) {
                    if (point < span.shownStart) break
                    if (point <= span.shownEnd) return span.start +
                        ((point - span.shownStart).toLong() * (span.end - span.start) / (span.shownEnd - span.shownStart).coerceAtLeast(1)).toInt()
                    delta = span.end - span.shownEnd
                }
                return (point + delta).coerceIn(0, text.length)
            }
        }
        return TransformedText(shown, mapping)
    }
}

internal fun compactMacroPreview(text: String): String {
    val lines = text.lineSequence().take(4).joinToString("\n")
    var limit = minOf(lines.length, 240)
    if (limit > 0 && limit < lines.length && lines[limit - 1].isHighSurrogate()) limit--
    return lines.take(limit) + if (lines.length < text.length || limit < lines.length) " …" else ""
}
