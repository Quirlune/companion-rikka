package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.rikkahub.ui.components.ui.Select

/** Edits the selected main call's existing history processor. Memory data stays on its session. */
@Composable
internal fun MemorySettingsPage(
    library: Library,
    actions: List<ActionDescriptor>,
    onSave: (String, HistoryProcessor, () -> Unit) -> Unit,
    onBack: () -> Unit,
    saving: Boolean,
) {
    val currentSession = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    var ownerId by remember { mutableStateOf(currentSession?.mainCallId?.takeIf { id -> library.mainCalls.any { it.id == id } }
        ?: library.mainCalls.firstOrNull()?.id) }
    val owner = library.mainCalls.firstOrNull { it.id == ownerId }
    if (owner == null) {
        CallEditorPage("记忆", false, onBack, onBack, saving) {
            Text("请先创建一个主调用，再设置其历史处理。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val baseline = owner.history.processor
    var processor by remember(owner.id) { mutableStateOf(baseline) }
    var pendingOwner by remember { mutableStateOf<String?>(null) }
    val dirty = processor != baseline
    val normalized = processor.copy(workflow = processor.workflow.copy(trigger = RuntimeEvents.HISTORY_PROCESS))
    fun switchOwner(id: String) {
        if (id == owner.id || saving) return
        if (dirty) pendingOwner = id else ownerId = id
    }

    CallEditorPage("记忆", dirty, onBack, { onSave(owner.id, normalized, onBack) }, saving) {
        val choices = library.mainCalls.map { it.id to it.name.ifBlank { "未命名主调用" } }
        Text("所属主调用", style = MaterialTheme.typography.titleSmall)
        Select(choices, choices.first { it.first == owner.id }, { switchOwner(it.first) },
            modifier = Modifier.fillMaxWidth(), optionToString = { it.second })
        Text("记忆处理属于这项主调用；下方只展示当前对话与它关联的记忆。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("历史上下文处理", style = MaterialTheme.typography.titleMedium)
        Text("积累足够新楼层时运行独立调用，结果作为摘要参与之后的历史投影。默认关闭。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceLine("记忆正文来源", processor.sourceKind, listOf(
            HistorySourceKind.DISPLAY to "最终展示正文",
            HistorySourceKind.MODEL_RAW to "模型初始原文",
        )) { processor = processor.copy(sourceKind = it) }
        Text("待处理历史会使用这里选定的来源；缺少旧模型原文时会报告缺失。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberLine("触发所需新增楼层", processor.minimumNewFloors, 1) { processor = processor.copy(minimumNewFloors = it) }
        NumberLine("保留最近楼层", processor.keepRecentFloors) { processor = processor.copy(keepRecentFloors = it) }
        ChoiceLine("摘要来自节点", processor.outputKey, processor.workflow.nodes.map { it.outputKey to "${it.title} · ${it.outputKey}" }) {
            processor = processor.copy(outputKey = it)
        }
        TextFieldLine("摘要输出键", processor.outputKey, { processor = processor.copy(outputKey = it) })
        val references = promptReferenceChoices(owner.workflows + processor.workflow, library, mainCall = owner) + listOf(
            PromptReferenceChoice("待处理历史", "{{history.pending}}", "历史处理"),
            PromptReferenceChoice("待处理楼层数量", "{{history.pending.count}}", "历史处理"),
            PromptReferenceChoice("原始历史", "{{history.raw}}", "历史处理"),
            PromptReferenceChoice("已有摘要", "{{history.summary}}", "历史处理"),
            PromptReferenceChoice("楼层数量", "{{history.count}}", "历史处理"),
        )
        WorkflowForm(processor.workflow, 0, 1, actions, library.profiles,
            outputKeysForNewNodes(library, owner.workflows + processor.workflow), references,
            { changed -> processor = processor.copy(workflow = changed.copy(trigger = RuntimeEvents.HISTORY_PROCESS)) },
            {}, {}, {}, fixedTrigger = true, tags = library.promptTags, siblings = owner.workflows)
        HorizontalDivider()
        if (currentSession != null && currentSession.mainCallId != owner.id) Text(
            "当前对话属于另一项主调用；切换到它后才能查看对应记忆。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HistoryMemoryInspector(currentSession?.takeIf { it.mainCallId == owner.id })
    }
    pendingOwner?.let { target ->
        AlertDialog(onDismissRequest = { pendingOwner = null }, title = { Text("切换主调用？") },
            text = { Text("当前记忆设置有未保存的修改。") },
            confirmButton = { TextButton(onClick = {
                pendingOwner = null
                onSave(owner.id, normalized) { ownerId = target }
            }, enabled = !saving) { Text("保存并切换") } },
            dismissButton = { Row {
                TextButton(onClick = { pendingOwner = null }) { Text("继续编辑") }
                TextButton(onClick = { pendingOwner = null; ownerId = target }, enabled = !saving) { Text("放弃并切换") }
            } },
        )
    }
}
