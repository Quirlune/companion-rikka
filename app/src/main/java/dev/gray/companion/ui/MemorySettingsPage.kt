package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.gray.companion.core.*

/** Edits one global definition; saved summaries remain in each chat. */
@Composable
internal fun MemorySettingsPage(
    library: Library,
    onApply: ((Library) -> ExtensionEditResult, (Boolean) -> Unit) -> Unit,
    onBack: () -> Unit,
    saving: Boolean,
) {
    val baseline = remember(library.memoryConfig) { GlobalMemory.draft(library) }
    if (baseline == null) {
        CallEditorPage("记忆", false, onBack, onBack, saving) {
            Text("全局记忆配置暂不可用。请返回扩展页重试。", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    var draft by remember(baseline) { mutableStateOf(baseline) }
    val sourceFocus = LocalSourceNavigation.current?.focus
    var editingPrompts by remember(baseline, sourceFocus) { mutableStateOf(when {
        sourceFocus?.resource == ExtensionResourceRef(ExtensionResourceKind.GROUP, baseline.group.id) &&
            sourceFocus.workflowId == baseline.processor.workflow.id && sourceFocus.nodeId == baseline.smallNode.id -> MemoryPromptStage.SMALL
        sourceFocus?.resource == ExtensionResourceRef(ExtensionResourceKind.GROUP, baseline.group.id) &&
            sourceFocus.workflowId == baseline.processor.aggregationWorkflow?.id && sourceFocus.nodeId == baseline.largeNode.id -> MemoryPromptStage.LARGE
        else -> null
    }) }
    var section by remember(baseline) { mutableStateOf(0) }
    val previewLibrary = remember(library, draft) { GlobalMemory.withDraft(library, draft) }
    val previewGroup = previewLibrary.groups.firstOrNull { it.id == draft.group.id } ?: draft.group
    val workflows = previewGroup.workflows
    val references = remember(previewLibrary, workflows) {
        promptReferenceChoices(workflows, previewLibrary, callGroup = previewGroup) + listOf(
            PromptReferenceChoice("摘要 XML 标签", "{{state.memory_summary_xml_tag}}", "记忆", "当前阶段的必需标签名，仅在摘要请求中可用"),
            PromptReferenceChoice("本批待处理内容", "{{history.pending}}", "记忆", "小摘要为待处理原始正文楼层；大摘要为对应的小摘要"),
            PromptReferenceChoice("本批条目数量", "{{history.pending.count}}", "记忆"),
        )
    }
    val sourceResource = ExtensionResourceRef(ExtensionResourceKind.GROUP, draft.group.id)
    SourceDraftBoundary(sourceResource,
        overlay = { GlobalMemory.withDraft(it, draft) },
        restore = { candidate, parent -> candidate.copy(
            groups = candidate.groups.map { if (it.id == draft.group.id) parent.groups.first { old -> old.id == it.id } else it },
            extensions = candidate.extensions.map { if (it.id == draft.extension.id) parent.extensions.first { old -> old.id == it.id } else it },
            memoryConfig = parent.memoryConfig) },
        accept = { candidate -> GlobalMemory.adoptSourceDraft(draft, candidate)?.let { draft = it } },
        ownedLocation = { location -> location.resource == sourceResource &&
            location.workflowId in listOfNotNull(draft.processor.workflow.id, draft.processor.aggregationWorkflow?.id) }) {
    val stage = editingPrompts
    if (stage != null) {
        val node = if (stage == MemoryPromptStage.SMALL) draft.smallNode else draft.largeNode
        val workflow = if (stage == MemoryPromptStage.SMALL) draft.processor.workflow else draft.processor.aggregationWorkflow
        val owner = workflow?.let { ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, draft.group.id),
            workflowId = it.id, nodeId = node.id) }
        PromptBlocksEditor(initial = node.blocks, references = references,
            onSave = { blocks ->
                draft = if (stage == MemoryPromptStage.SMALL) draft.copy(smallNode = draft.smallNode.copy(blocks = blocks))
                    else draft.copy(largeNode = draft.largeNode.copy(blocks = blocks))
                section = if (stage == MemoryPromptStage.SMALL) 1 else 2
                editingPrompts = null
            }, onBack = { section = if (stage == MemoryPromptStage.SMALL) 1 else 2; editingPrompts = null }, title = if (stage == MemoryPromptStage.SMALL) "小摘要提示词" else "大摘要提示词",
            saving = saving, tags = previewLibrary.promptTags, owner = owner)
    } else {
    fun save() = onApply({ latest -> GlobalMemory.update(latest, baseline, draft) }) { accepted -> if (accepted) onBack() }
    CallEditorPage("记忆", draft != baseline, onBack, ::save, saving, scrollKey = section,
        navigationContent = { EditorSectionTabs(listOf("模型", "小摘要", "大摘要", "已保存"), section) { section = it } }) {
        when (section) {
        0 -> {
        Text("这一套设置用于所有角色和聊天。摘要及其来源映射仍分别保存在各自聊天中。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("共用模型与参数", style = MaterialTheme.typography.titleMedium)
        SharedModelGenerationEditor(draft.generation, draft.reasoningParsing, library.profiles,
            modelLabel = "小摘要与大摘要共用模型") { generation, parsing ->
            draft = draft.copy(generation = generation, reasoningParsing = parsing)
        }
        }
        1 -> {
        MemoryStageSection("小摘要", "选取待处理的展示正文楼层，生成摘要并记录实际引用的来源楼层。",
            draft.processor.protectFirstFloors, draft.processor.minimumNewFloors, draft.processor.keepRecentFloors,
            draft.processor.maximumBatchFloors, draft.config.smallSummaryXmlTag,
            onRules = { protect, trigger, retain, cap ->
                draft = draft.copy(processor = draft.processor.copy(protectFirstFloors = protect,
                    minimumNewFloors = trigger, keepRecentFloors = retain, maximumBatchFloors = cap))
            }, onTag = { draft = draft.copy(config = draft.config.copy(smallSummaryXmlTag = it)) },
            onPrompts = { editingPrompts = MemoryPromptStage.SMALL })
        }
        2 -> {
        MemoryStageSection("大摘要", "读取对应的小摘要，按它们覆盖的原始正文楼层计算范围；至少两段小摘要才会合并。跨边界的小摘要保持整段，不拆分。",
            draft.largeRules.protectFirstFloors, draft.largeRules.minimumNewFloors, draft.largeRules.keepRecentFloors,
            draft.largeRules.maximumBatchFloors, draft.config.largeSummaryXmlTag,
            onRules = { protect, trigger, retain, cap ->
                draft = draft.copy(largeRules = draft.largeRules.copy(protectFirstFloors = protect,
                    minimumNewFloors = trigger, keepRecentFloors = retain, maximumBatchFloors = cap))
            }, onTag = { draft = draft.copy(config = draft.config.copy(largeSummaryXmlTag = it)) },
            onPrompts = { editingPrompts = MemoryPromptStage.LARGE })
        }
        3 -> {
        val currentSession = library.sessions.firstOrNull { it.id == library.selectedSessionId }
        val memoryLibrary = currentSession?.executableWorld?.library ?: library
        val main = memoryLibrary.mainCalls.firstOrNull { it.id == currentSession?.mainCallId }
        HistoryMemoryInspector(currentSession, main)
        }
        }
    }
    }
    }
}

private enum class MemoryPromptStage { SMALL, LARGE }

@Composable
private fun MemoryStageSection(
    title: String, detail: String,
    protect: Int, trigger: Int, retain: Int, cap: Int?,
    xmlTag: String,
    onRules: (Int, Int, Int, Int?) -> Unit,
    onTag: (String) -> Unit,
    onPrompts: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    NumberLine("保护最早楼层", protect) { onRules(it, trigger, retain, cap) }
    NumberLine("触发所需楼层", trigger, 1) { onRules(protect, it, retain, cap) }
    NumberLine("触发时保留最近楼层", retain) { onRules(protect, trigger, it, cap) }
    ToggleLine("限制每批最多楼层", cap != null) { enabled ->
        onRules(protect, trigger, retain, if (enabled) (cap ?: trigger) else null)
    }
    cap?.let { NumberLine("每批最多楼层", it, 1) { next -> onRules(protect, trigger, retain, next) } }
    EditorDestinationRow("提示词", "完整编辑内容、角色、顺序及历史来源与楼层范围") { onPrompts() }
    Text("提示词中的历史引用可再缩小本批范围；只标记实际纳入摘要的楼层。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("验证", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = xmlTag, onValueChange = onTag, label = { Text("必需的 XML 标签") },
        singleLine = true, isError = !SummaryXml.validTag(xmlTag))
    Text("标签名以文字或下划线开头，可含数字及连字符，最多 64 字符。只保存标签内的非空正文。缺失、重复、嵌套或非法 XML 会报错，不保存摘要。新摘要不再使用旧输出正则。标签提供给提示词中的 {{state.memory_summary_xml_tag}}，输出协议可在提示词中编辑。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
