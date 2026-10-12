package dev.gray.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.gray.companion.core.*

/** Expanded text is display-only; callers must keep the original token as their saved value. */
internal data class PromptMacroPreview(
    val text: String,
    val issues: List<Issue> = emptyList(),
    val unavailableReason: String? = null,
    val sourceLabel: String? = null,
    val editSource: (() -> Unit)? = null,
)

internal val LocalPromptMacroPreview = staticCompositionLocalOf<((String) -> PromptMacroPreview)?> { null }

@Composable
internal fun rememberPromptMacroPreview(): (String) -> PromptMacroPreview {
    val local = LocalPromptMacroPreview.current
    val library = LocalSourceNavigation.current?.library
    val session = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }
    return remember(local, library, session) {
        val cache = mutableMapOf<String, PromptMacroPreview>()
        val preview: (String) -> PromptMacroPreview = { token -> cache.getOrPut(token) {
            local?.invoke(token) ?: resolvePromptMacroPreview(token, library, session)
        } }
        preview
    }
}

internal fun resolvePromptMacroPreview(token: String, library: Library?, session: Session?): PromptMacroPreview {
    val address = token.trim().removeSurrounding("{{", "}}").trim()
    val label = when (address) {
        "user.name", "user.prompt" -> "当前聊天身份 · 在身份设置中编辑"
        "input" -> "本次调用输入"
        else -> null
    }
    if (library == null) return PromptMacroPreview("", unavailableReason = "当前编辑器没有可用来源上下文", sourceLabel = label)
    if (session == null) return PromptMacroPreview("", unavailableReason = "尚未选择聊天，无法预览此宏", sourceLabel = label)
    if (address == "input") return PromptMacroPreview("", unavailableReason = "实际调用时才会提供输入", sourceLabel = label)
    return try {
        val resolver = MacroResolver(library, session)
        val text = resolver.read(address)
        PromptMacroPreview(text, resolver.issues, sourceLabel = label)
    } catch (error: IllegalArgumentException) {
        PromptMacroPreview("", unavailableReason = error.message ?: "当前来源无法展开", sourceLabel = label)
    } catch (error: IllegalStateException) {
        PromptMacroPreview("", unavailableReason = error.message ?: "当前来源无法展开", sourceLabel = label)
    }
}
