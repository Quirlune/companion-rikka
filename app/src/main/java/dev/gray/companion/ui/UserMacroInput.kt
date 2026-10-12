package dev.gray.companion.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.ui.text.TextRange
import dev.gray.companion.core.UserInputMacros
import dev.gray.companion.core.UserMacroDefinition
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider

/** Shows a registered shortcut as a compact tag while leaving its ASCII trigger space untouched. */
internal fun userMacroOutputTransformation(
    definitions: Map<String, UserMacroDefinition>,
): OutputTransformation {
    val snapshot = definitions.toMap()
    return OutputTransformation {
        val raw = toString()
        UserInputMacros.armedSpans(raw, snapshot).asReversed().forEach { span ->
            val displayEnd = span.endExclusive - 1
            if (displayEnd > span.start && raw.getOrNull(displayEnd) == ' ') {
                replace(span.start, displayEnd, "【${span.shortcut}】")
            }
        }
    }
}

/** Suggests only the registered slash shortcuts; selecting one never expands its value. */
internal fun userMacroCompletionProvider(
    definitions: Map<String, UserMacroDefinition>,
): ChatCompletionProvider {
    val shortcuts = definitions.values.map(UserMacroDefinition::shortcut).distinct().sorted()
    return object : ChatCompletionProvider {
        override val id: String = "user-input-macros"

        override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
            if (shortcuts.isEmpty() || context.hasSelection) return null
            val cursor = context.cursor.coerceIn(0, context.text.length)
            val beforeCursor = context.text.substring(0, cursor)
            val slash = beforeCursor.lastIndexOf('/')
            if (slash < 0 || (slash > 0 && !beforeCursor[slash - 1].isWhitespace())) return null

            val partial = beforeCursor.substring(slash)
            if (!shortcutPrefix.matches(partial)) return null
            val items = shortcuts.asSequence()
                .filter { it.startsWith(partial) }
                .map { shortcut ->
                    ChatCompletionItem(
                        label = shortcut,
                        insertText = shortcut,
                        detail = "发送时展开",
                    )
                }
                .toList()
            if (items.isEmpty()) return null
            return ChatCompletionList(
                providerId = id,
                replacementRange = TextRange(slash, cursor),
                items = items,
            )
        }
    }
}

private val shortcutPrefix = Regex("/[\\p{L}\\p{N}_-]*")
