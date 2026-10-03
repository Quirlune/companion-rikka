package dev.gray.companion.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.gray.companion.core.ChatTitleConfig
import dev.gray.companion.core.DEFAULT_CHAT_TITLE_PROMPT
import dev.gray.companion.core.Library
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.pages.setting.PromptSettingItem

private data class TitleModelChoice(val id: String?, val label: String)

/** Original settings controls backed by Companion's persisted title configuration. */
@Composable
internal fun ChatTitleSettingsPage(
    library: Library,
    onSave: (ChatTitleConfig) -> Unit,
    onBack: () -> Unit,
    saving: Boolean,
) {
    var draft by remember(library.titleConfig) { mutableStateOf(library.titleConfig) }
    val modelChoices = remember(library.profiles) {
        listOf(TitleModelChoice(null, "默认沿正文调用")) +
            library.profiles.map { TitleModelChoice(it.id, it.name.ifBlank { it.model }) }
    }
    val selectedModel = modelChoices.firstOrNull { it.id == draft.modelProfileId }
        ?: TitleModelChoice(draft.modelProfileId, "已移除的模型")

    EditorPage(
        title = "聊天基础功能",
        onBack = onBack,
        onSave = { onSave(draft) },
        dirty = draft != library.titleConfig,
        saving = saving,
    ) {
        CardGroup(title = { Text("自动标题") }) {
            item(
                headlineContent = { Text("生成对话标题") },
                supportingContent = { Text("首轮回复完成后自动生成；也可在对话菜单中重新生成") },
                trailingContent = {
                    Switch(
                        checked = draft.enabled,
                        onCheckedChange = { draft = draft.copy(enabled = it) },
                    )
                },
            )
        }
        CardGroup(title = { Text("标题模型") }) {
            item(
                headlineContent = { Text("使用的模型") },
                supportingContent = { Text("默认沿正文调用；也可以指定独立模型") },
            )
        }
        Select(
            options = modelChoices,
            selectedOption = selectedModel,
            onOptionSelected = { draft = draft.copy(modelProfileId = it.id) },
            modifier = Modifier.fillMaxWidth(),
            optionToString = { it.label },
        )
        PromptSettingItem(
            title = "标题提示词",
            promptDescription = "可使用 {content} 表示对话内容，{locale} 表示当前语言。",
            promptValue = draft.prompt,
            onPromptChange = { draft = draft.copy(prompt = it) },
            onResetPrompt = { draft = draft.copy(prompt = DEFAULT_CHAT_TITLE_PROMPT) },
        )
    }
}
