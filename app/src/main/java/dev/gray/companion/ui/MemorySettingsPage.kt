package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.rikkahub.ui.components.ui.Select

/** Edits the selected main call's memory workflows. Memory data stays on its session. */
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
            Text("请先创建一个主调用，再设置其记忆处理。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val baseline = owner.history.processor
    var processor by remember(owner.id) { mutableStateOf(baseline) }
    var pendingOwner by remember { mutableStateOf<String?>(null) }
    val dirty = processor != baseline
    val normalized = processor.copy(
        workflow = processor.workflow.copy(trigger = RuntimeEvents.HISTORY_PROCESS),
        aggregationWorkflow = processor.aggregationWorkflow?.copy(afterWorkflowId = processor.workflow.id),
    )
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

        val draftOwner = owner.copy(history = owner.history.copy(processor = processor))
        val configured = draftOwner.configuredWorkflows()
        val references = promptReferenceChoices(configured, library, mainCall = draftOwner) + listOf(
            PromptReferenceChoice("待处理历史", "{{history.pending}}", "历史处理"),
            PromptReferenceChoice("待处理楼层数量", "{{history.pending.count}}", "历史处理"),
        )
        val outputKeys = outputKeysForNewNodes(library, configured)

        Text("小摘要", style = MaterialTheme.typography.titleMedium)
        Text("积累足够楼层后，独立处理一批历史并保存来源映射。默认关闭。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceLine("记忆正文来源", processor.sourceKind, listOf(
            HistorySourceKind.DISPLAY to "最终展示正文",
            HistorySourceKind.MODEL_RAW to "模型初始原文",
        )) { processor = processor.copy(sourceKind = it) }
        Text("待处理历史会使用这里选定的来源；缺少旧模型原文时会报告缺失。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceLine("触发阈值依据", processor.thresholdBasis, listOf(
            HistoryThresholdBasis.ELIGIBLE_BLOCK to "可处理的连续楼层",
            HistoryThresholdBasis.UNCOMPRESSED_FLOORS to "尚未压缩的楼层总数",
        )) { processor = processor.copy(thresholdBasis = it) }
        Text(
            if (processor.thresholdBasis == HistoryThresholdBasis.ELIGIBLE_BLOCK)
                "达到阈值后，只处理保留范围之外、连续且尚未归入有效记忆的楼层。"
            else "本次用户输入计入未压缩楼层阈值，但不会写入本批摘要。达到阈值后，每次最多处理一批，首尾保护范围不参与；展开历史副本不会取消有效摘要的楼层标记。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberLine(if (processor.thresholdBasis == HistoryThresholdBasis.ELIGIBLE_BLOCK) "触发所需连续楼层" else "触发所需未压缩楼层", processor.minimumNewFloors, 1) {
            processor = processor.copy(minimumNewFloors = it)
        }
        NumberLine("保护最早楼层", processor.protectFirstFloors) { processor = processor.copy(protectFirstFloors = it) }
        NumberLine("保留最近楼层", processor.keepRecentFloors) { processor = processor.copy(keepRecentFloors = it) }
        ToggleLine("限制每批处理楼层", processor.maximumBatchFloors != null) { enabled ->
            processor = processor.copy(maximumBatchFloors = if (enabled) (processor.maximumBatchFloors ?: processor.minimumNewFloors) else null)
        }
        processor.maximumBatchFloors?.let { maximum ->
            NumberLine("每批最多楼层", maximum, 1) { processor = processor.copy(maximumBatchFloors = it) }
        }
        ChoiceLine("摘要来自节点", processor.outputKey, processor.workflow.nodes.map { it.outputKey to "${it.title} · ${it.outputKey}" }) {
            processor = processor.copy(outputKey = it)
        }
        TextFieldLine("摘要输出键", processor.outputKey, { processor = processor.copy(outputKey = it) })
        WorkflowForm(processor.workflow, 0, 1, actions, library.profiles, outputKeys, references,
            { changed -> processor = processor.copy(workflow = changed.copy(trigger = RuntimeEvents.HISTORY_PROCESS)) },
            {}, {}, {}, fixedTrigger = true, tags = library.promptTags, siblings = configured)

        HorizontalDivider()
        Text("大摘要", style = MaterialTheme.typography.titleMedium)
        Text("在小摘要完成后，选择未合并的小摘要，读取并汇总，再保存带子记忆标识的大摘要。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (processor.aggregationWorkflow == null) {
            OutlinedButton(onClick = { processor = processor.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(processor)) }) {
                Text("添加大摘要调用")
            }
        } else {
            val aggregate = processor.aggregationWorkflow!!
            val selectNode = aggregate.nodes.firstOrNull { it.actionId == "history.memory.select" }
            selectNode?.let { node ->
                NumberLine("每组小摘要数量", node.arguments["count"]?.toIntOrNull() ?: 5, 2) { count ->
                    processor = processor.copy(aggregationWorkflow = aggregate.copy(nodes = aggregate.nodes.map {
                        if (it.id == node.id) it.copy(arguments = it.arguments + ("count" to count.toString())) else it
                    }))
                }
            }
            val expectedSource = if (processor.sourceKind == HistorySourceKind.MODEL_RAW) "{{history.model}}" else "{{history.display}}"
            val configuredSources = aggregate.nodes.filter { it.actionId == "history.memory.select" || it.actionId == "history.memory.write" }
                .mapNotNull { it.arguments["source"] }.distinct()
            if (configuredSources.any { it != expectedSource }) Text(
                "大摘要调用的来源与当前小摘要来源不同。若需同步，请在下方调用节点中调整来源参数。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
            WorkflowForm(aggregate, 0, 1, actions, library.profiles, outputKeys, references,
                { changed -> processor = processor.copy(aggregationWorkflow = changed.copy(afterWorkflowId = processor.workflow.id)) },
                {}, {}, {}, fixedTrigger = true, tags = library.promptTags, siblings = configured)
            TextButton(onClick = { processor = processor.copy(aggregationWorkflow = null) }) { Text("移除大摘要调用") }
        }
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
