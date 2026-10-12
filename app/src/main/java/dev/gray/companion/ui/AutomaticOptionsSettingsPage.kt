package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.gray.companion.core.*
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select

@Composable
internal fun AutomaticOptionsSettingsPage(
    library: Library,
    onSave: (AutomaticOptionsConfig) -> Unit,
    onBack: () -> Unit,
    saving: Boolean,
) {
    val baseline = remember(library.automaticOptions) { AutomaticOptions.editableConfig(library.automaticOptions) }
    var draft by remember(baseline) { mutableStateOf(baseline) }
    var section by remember { mutableStateOf(0) }
    var editingPrompts by remember { mutableStateOf(false) }
    var protocolDecision by remember { mutableStateOf(false) }
    if (protocolDecision) AlertDialog(onDismissRequest = { protocolDecision = false },
        title = { Text("同步新的 XML 格式") },
        text = { Text("标签或标题设置已改变，当前提示词未引用协议宏。可以明确替换输出格式块；若已手动写好匹配的新格式，也可保留自定义提示词。") },
        confirmButton = { TextButton(onClick = {
            draft = AutomaticOptions.withCurrentProtocol(draft)
            protocolDecision = false
            onSave(draft)
        }) { Text("同步协议并保存") } },
        dismissButton = { TextButton(onClick = { protocolDecision = false; onSave(draft) }) { Text("保留自定义并保存") } })
    val selectedSession = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    val sourceLibrary = selectedSession?.let { CompletedWorlds.executionLibrary(library, it) } ?: library
    val references = promptReferenceChoices(emptyList(), sourceLibrary) + listOf(
        PromptReferenceChoice("当前 XML 输出协议", AutomaticOptions.PROTOCOL_MACRO, "自动选项", "按当前逐项标签和标题开关生成；完整内容见输出页"),
        PromptReferenceChoice("选项数量", AutomaticOptions.COUNT_MACRO, "自动选项", "当前设置的选项数，仅在本次选项请求中可读"),
        PromptReferenceChoice("旧版语气文字", AutomaticOptions.LEGACY_STYLE_MACRO, "自动选项", "下方旧版文字原样插入，内部花括号不展开"),
    )
    var editingMacroSource by remember { mutableStateOf<String?>(null) }
    val previewContext = remember(library, selectedSession, draft) {
        runCatching { AutomaticOptions.previewContext(library, selectedSession, draft) }
    }
    val previewMacro: (String) -> PromptMacroPreview = { token ->
        val address = token.trim().removeSurrounding("{{", "}}").trim()
        val local = address in setOf("state.automatic_options_protocol", "state.automatic_options_count", "state.automatic_options_legacy_style")
        val label = when (address) {
            "state.automatic_options_protocol" -> "编辑 XML 格式"
            "state.automatic_options_count" -> "编辑选项数量"
            "state.automatic_options_legacy_style" -> "编辑旧版语气"
            else -> null
        }
        val context = previewContext.getOrNull()
        val preview = when {
            context == null -> PromptMacroPreview("", unavailableReason = previewContext.exceptionOrNull()?.message ?: "选项预览上下文不可用")
            selectedSession == null && !local -> PromptMacroPreview("", unavailableReason = "尚未选择聊天，无法预览此宏")
            !context.hasCompletedHistory && (address.startsWith("history") || address.contains(".in:history")) ->
                PromptMacroPreview("", unavailableReason = "当前聊天还没有已完成的正文对话")
            else -> resolvePromptMacroPreview(token, context.library, context.session)
        }
        if (local) preview.copy(sourceLabel = label, editSource = { editingMacroSource = address }) else preview
    }
    editingMacroSource?.let { address ->
        AlertDialog(onDismissRequest = { editingMacroSource = null },
            title = { Text(when (address) {
                "state.automatic_options_count" -> "选项数量"
                "state.automatic_options_legacy_style" -> "旧版语气"
                else -> "XML 输出格式"
            }) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("修改保留在当前草稿中，完成后继续编辑提示词。")
                    when (address) {
                        "state.automatic_options_legacy_style" -> OutlinedTextField(draft.prompt,
                            { draft = draft.copy(prompt = it) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text("旧版语气文字") })
                        else -> {
                            Select((1..AutomaticOptions.MAX_COUNT).toList(), draft.count, { draft = draft.copy(count = it) },
                                modifier = Modifier.fillMaxWidth(), optionToString = { "$it 个" })
                            if (address == "state.automatic_options_protocol") {
                                Row { Text("生成选项标题", Modifier.weight(1f)); Switch(draft.titlesEnabled, { draft = draft.copy(titlesEnabled = it) }) }
                                AutomaticOptions.effectiveItems(draft).forEachIndexed { index, spec ->
                                    fun update(value: AutomaticOptionSpec) {
                                        val items = draft.items.toMutableList()
                                        while (items.size <= index) items += AutomaticOptionSpec()
                                        items[index] = value
                                        draft = draft.copy(items = items)
                                    }
                                    OutlinedTextField(spec.contentTag, { update(spec.copy(contentTag = it)) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("选项 ${index + 1} 内容标签") })
                                    if (draft.titlesEnabled) OutlinedTextField(spec.titleTag, { update(spec.copy(titleTag = it)) },
                                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("选项 ${index + 1} 标题标签") })
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { editingMacroSource = null }) { Text("完成") } })
    }
    CompositionLocalProvider(LocalPromptMacroPreview provides previewMacro) {
    if (editingPrompts) {
        PromptBlocksEditor(initial = draft.blocks.orEmpty(), references = references,
            onSave = { draft = draft.copy(blocks = it); editingPrompts = false },
            onBack = { editingPrompts = false }, title = "自动选项提示词", saving = saving, tags = sourceLibrary.promptTags)
        return@CompositionLocalProvider
    }
    val node = AutomaticOptions.editingNode(draft)
    fun changeNode(value: WorkflowNode) { draft = AutomaticOptions.withEditingNode(draft, value) }
    CallEditorPage("自动选项", draft != baseline, onBack, {
        val schemaChanged = draft.items != baseline.items || draft.titlesEnabled != baseline.titlesEnabled
        if (schemaChanged && draft.blocks.orEmpty().none { it.enabled && AutomaticOptions.PROTOCOL_MACRO in it.template }) protocolDecision = true
        else onSave(draft)
    }, saving = saving,
        scrollKey = section, navigationContent = { EditorSectionTabs(listOf("输入", "模型", "输出"), section) { section = it } }) {
        CardGroup {
            item(headlineContent = { Text("自动选项状态") },
                supportingContent = { Text(if (draft.enabled) "已开启 · 在扩展列表切换" else "未开启 · 在扩展列表切换") })
        }
        when (section) {
            0 -> {
                EditorDestinationRow("完整提示词", "${draft.blocks.orEmpty().size} 个提示词块") { editingPrompts = true }
                Text("历史范围", style = MaterialTheme.typography.titleMedium)
                Select((1..50).toList(), draft.historyTurns, { draft = draft.copy(historyTurns = it) },
                    modifier = Modifier.fillMaxWidth(), optionToString = { "最近 $it 个完整对话轮次" })
                PromptTemplateField("输入模板", node.inputTemplate, references, { changeNode(node.copy(inputTemplate = it)) }, minLines = 2)
                TextProcessingEditor("输入处理", node.inputProcessing, references) { changeNode(node.copy(inputProcessing = it)) }
                if (draft.prompt.isNotEmpty() || draft.blocks.orEmpty().any { AutomaticOptions.LEGACY_STYLE_MACRO in it.template }) {
                    EditorSection("旧版语气", "通过旧版语气宏引用") {
                        OutlinedTextField(draft.prompt, { draft = draft.copy(prompt = it) }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("旧版语气文字") })
                    }
                }
            }
            1 -> SharedModelGenerationEditor(node.generation, node.reasoningParsing, library.profiles) { generation, parsing ->
                changeNode(node.copy(generation = generation, reasoningParsing = parsing))
            }
            2 -> {
                Text("选项数量", style = MaterialTheme.typography.titleMedium)
                Select((1..AutomaticOptions.MAX_COUNT).toList(), draft.count, { draft = draft.copy(count = it) },
                    modifier = Modifier.fillMaxWidth(), optionToString = { "$it 个" })
                Text("选项在正文右下方竖排悬浮，仅条目本身带背景；点击标题可展开内容。", style = MaterialTheme.typography.bodySmall)
                CardGroup {
                    item(headlineContent = { Text("生成选项标题") }, supportingContent = { Text("点击标题展开内容，再填入输入框") },
                        trailingContent = { Switch(draft.titlesEnabled, { draft = draft.copy(titlesEnabled = it) }, enabled = !saving) })
                }
                AutomaticOptions.effectiveItems(draft).forEachIndexed { index, spec ->
                    fun changeSpec(value: AutomaticOptionSpec) {
                        val items = draft.items.toMutableList()
                        while (items.size <= index) items += AutomaticOptionSpec()
                        items[index] = value
                        draft = draft.copy(items = items)
                    }
                    EditorSection("选项 ${index + 1}", "按标签匹配；同名标签按出现顺序分配") {
                        OutlinedTextField(spec.contentTag, { changeSpec(spec.copy(contentTag = it)) }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("内容标签（不含尖括号）") })
                        if (draft.titlesEnabled) OutlinedTextField(spec.titleTag, { changeSpec(spec.copy(titleTag = it)) }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("标题标签（不含尖括号）") })
                    }
                }
                TextProcessingEditor("输出处理", node.outputProcessing, references) { changeNode(node.copy(outputProcessing = it)) }
                EditorSection("当前 XML 输出协议", "可通过 ${AutomaticOptions.PROTOCOL_MACRO} 引用") {
                    Text(AutomaticOptions.protocol(draft))
                    Text("修改标签或标题开关后，引用此宏的提示块会自动使用当前协议。自定义固定格式不会被自动覆盖。")
                    if (draft.blocks.orEmpty().none { it.enabled && AutomaticOptions.PROTOCOL_MACRO in it.template })
                        Text("提示词尚未引用当前协议，请使用下方按钮同步，或在完整提示词中手动引用。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { draft = AutomaticOptions.withCurrentProtocol(draft) }, enabled = !saving) {
                        Text("将输出格式块替换为当前 XML 协议")
                    }
                    EditorDestinationRow("编辑完整提示词", "所有提示词均可修改；协议不会隐藏追加") { editingPrompts = true }
                }
            }
        }
    }
}
}
