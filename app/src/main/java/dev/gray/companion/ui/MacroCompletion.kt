package dev.gray.companion.ui

import dev.gray.companion.core.MacroTemplateTokens
import dev.gray.companion.core.HistoryViews

/** An editable macro at the caret. End includes its remaining text and any closing braces. */
internal data class MacroCompletionToken(val start: Int, val end: Int, val query: String, val shortcut: Boolean = false)

internal data class MacroCompletionEdit(val text: String, val caret: Int)

/** Pure string offsets, so completion does not depend on Compose selection or IME state. */
internal fun macroCompletionToken(text: String, selectionStart: Int, selectionEnd: Int = selectionStart): MacroCompletionToken? {
    if (selectionStart != selectionEnd) return null
    val caret = selectionStart.coerceIn(0, text.length)
    if (caret < 2) return null
    val slash = text.lastIndexOf("//", caret - 2)
    val wordStart = text.take(caret).indexOfLast { it.isWhitespace() } + 1
    if (slash >= 0 && (slash == 0 || text[slash - 1] != ':') && "://" !in text.substring(wordStart, caret)) {
        val query = text.substring(slash + 2, caret)
        if (query.none { it.isWhitespace() || it in "{}/" }) {
            var end = caret
            while (end < text.length && !text[end].isWhitespace() && text[end] !in "{}/") end++
            return MacroCompletionToken(slash, end, query, shortcut = true)
        }
    }
    val outer = MacroTemplateTokens.scan(text).tokens.lastOrNull { token ->
        caret >= token.start + 2 && caret <= (if (token.closed) token.endExclusive - 2 else token.endExclusive)
    } ?: return null
    val history = if (outer.closed) runCatching { HistoryViews.selection(outer.expression) != null }.getOrDefault(false)
        else outer.expression.startsWith("history") || outer.expression.startsWith("in:history") ||
            outer.expression.startsWith("type:builtin.in:history") || ".in:history" in outer.expression
    val query = if (history) "history" else text.substring(outer.start + 2, caret).also {
        if (it.any { char -> char.isWhitespace() || char == '{' || char == '}' }) return null
    }
    if (outer.closed || history) return MacroCompletionToken(outer.start, outer.endExclusive, query)
    // The scanner owns an incomplete token through end-of-input for diagnostics. Completion of
    // ordinary macros stops at the next whitespace or brace, so adjacent prose is never erased.
    var end = caret
    while (end < text.length && !text[end].isWhitespace() && text[end] != '{' && text[end] != '}') end++
    if (text.startsWith("}}", end)) end += 2
    else if (end < text.length && text[end] == '}') end++
    return MacroCompletionToken(outer.start, end, query)
}

internal fun replaceMacroCompletion(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    macro: String,
): MacroCompletionEdit {
    val token = macroCompletionToken(text, selectionStart, selectionEnd)
    val start = token?.start ?: minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = token?.end ?: maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
    return MacroCompletionEdit(text.replaceRange(start, end, macro), start + macro.length)
}
