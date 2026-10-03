package dev.gray.companion.ui

/** An editable macro at the caret. End includes its remaining text and any closing braces. */
internal data class MacroCompletionToken(val start: Int, val end: Int, val query: String)

internal data class MacroCompletionEdit(val text: String, val caret: Int)

/** Pure string offsets, so completion does not depend on Compose selection or IME state. */
internal fun macroCompletionToken(text: String, selectionStart: Int, selectionEnd: Int = selectionStart): MacroCompletionToken? {
    if (selectionStart != selectionEnd) return null
    val caret = selectionStart.coerceIn(0, text.length)
    if (caret < 2) return null
    val start = text.lastIndexOf("{{", startIndex = caret - 2)
    if (start < 0) return null
    val query = text.substring(start + 2, caret)
    if (query.any { it.isWhitespace() || it == '{' || it == '}' }) return null
    var end = caret
    while (end < text.length && !text[end].isWhitespace() && text[end] != '{' && text[end] != '}') end++
    // Consume an existing full or half-written closing delimiter only once.
    if (text.startsWith("}}", end)) end += 2
    else if (end < text.length && text[end] == '}') end++
    return MacroCompletionToken(start, end, query)
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
