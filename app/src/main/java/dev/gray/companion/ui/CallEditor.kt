package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.ui.components.ui.CardGroup

internal data class PromptEditRequest(val blocks: List<PromptBlock>, val references: List<PromptReferenceChoice>, val save: (List<PromptBlock>) -> Unit)
internal data class CallDetailDraft(val baseline: Workflow, val value: Workflow)

@Composable
internal fun DraftLeaveDialog(onCancel: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text("有未保存的修改") },
        text = { Text("保存当前草稿，或放弃修改后返回。") },
        confirmButton = { TextButton(onClick = onSave) { Text("保存并返回") } },
        dismissButton = { Row { TextButton(onClick = onCancel) { Text("继续编辑") }; TextButton(onClick = onDiscard) { Text("放弃修改") } } },
    )
}

/** Reuses the existing native editor while exposing its draft guard to Navigation3 back. */
@Composable
internal fun CallEditorPage(
    title: String, dirty: Boolean, onBack: () -> Unit, onSave: () -> Unit, saving: Boolean = false,
    onRegisterBack: ((() -> Unit) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmBack by remember { mutableStateOf(false) }
    fun requestBack() { if (!saving) { if (dirty) confirmBack = true else onBack() } }
    SideEffect { onRegisterBack?.invoke(::requestBack) }
    // EditorPage retains the original native scaffold and input blocking during saves.
    EditorPage(title, ::requestBack, onSave, saving = saving, contentSpacing = 8.dp) {
        if (dirty) Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
    BackHandler { requestBack() }
    if (confirmBack) DraftLeaveDialog({ confirmBack = false }, { confirmBack = false; onSave() }, { confirmBack = false; onBack() })
}

@Composable
internal fun EditorRenameDialog(label: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(label) },
        text = { OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("名称") }) },
        confirmButton = { TextButton(onClick = { onSave(title) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EditorTitleRow(title: String, onRename: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRename) { Icon(HugeIcons.PencilEdit01, "重命名", Modifier.size(20.dp)) }
        trailing()
    }
}

@Composable
internal fun EditorDestinationRow(title: String, summary: String? = null, onClick: () -> Unit) {
    CardGroup { item(onClick = onClick, headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Icon(HugeIcons.ArrowRight01, "打开$title") }) }
}

@Composable
internal fun CallListEditor(
    title: String, workflows: List<Workflow>, onBack: () -> Unit,
    onOpen: (String) -> Unit, onAdd: () -> Unit, onToggle: (Workflow, Boolean) -> Unit,
    onRegisterBack: ((() -> Unit) -> Unit)? = null,
) {
    CallEditorPage(title, false, onBack, onBack, onRegisterBack = onRegisterBack) {
        TextButton(onClick = onAdd) { Icon(HugeIcons.Add01, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加调用") }
        val sorted = workflows.sortedBy { !it.enabled }
        if (sorted.isEmpty()) Text("暂无其他调用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardGroup {
            sorted.forEach { workflow -> item(onClick = { onOpen(workflow.id) },
                headlineContent = { Text(workflow.title.ifBlank { "未命名调用" }) },
                supportingContent = { Text(workflowScheduleLabel(workflow)) },
                trailingContent = { Switch(workflow.enabled, { onToggle(workflow, it) }) }) }
        }
    }
}

internal fun workflowScheduleLabel(workflow: Workflow): String {
    val phase = when (workflow.trigger) { RuntimeEvents.USER_MESSAGE -> "用户消息后"; RuntimeEvents.REPLY_COMPLETED -> "正文完成后"; RuntimeEvents.TURN_PREPARE -> "发送前准备"; RuntimeEvents.HISTORY_PROCESS -> "历史处理"; else -> workflow.trigger }
    return "$phase · ${if (workflow.queued) "排队 ${workflow.priority} 级" else "并行"}"
}

@Composable
internal fun CallDetailsEditor(
    initial: Workflow, workflow: Workflow, onDraftChange: (Workflow) -> Unit,
    actions: List<ActionDescriptor>, profiles: List<ModelProfile>,
    usedOutputKeys: Set<String>, referenceChoices: (Workflow) -> List<PromptReferenceChoice>,
    onSave: (Workflow) -> Unit, onBack: () -> Unit,
    onPrompt: (PromptEditRequest) -> Unit,
    onDelete: (() -> Unit)? = null,
    onRegisterBack: ((() -> Unit) -> Unit)? = null,
) {
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val references = referenceChoices(workflow)
    CallEditorPage("调用", workflow != initial, onBack, { onSave(workflow) }, onRegisterBack = onRegisterBack) {
        EditorTitleRow(workflow.title, { rename = true }) { Switch(workflow.enabled, { onDraftChange(workflow.copy(enabled = it)) }) }
        WorkflowEditorContent(workflow, actions, profiles, usedOutputKeys, references, onDraftChange, onPrompt)
        if (onDelete != null) {
            HorizontalDivider()
            TextButton(onClick = { delete = true }) { Text("删除调用", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (rename) EditorRenameDialog("调用名称", workflow.title, { rename = false }) { onDraftChange(workflow.copy(title = it)); rename = false }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除调用？") }, text = { Text("该调用及节点将从资源草稿移除。") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete?.invoke() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable
internal fun WorkflowEditorContent(
    workflow: Workflow, actions: List<ActionDescriptor>, profiles: List<ModelProfile>,
    usedOutputKeys: Set<String>, references: List<PromptReferenceChoice>,
    onChange: (Workflow) -> Unit, onPrompt: (PromptEditRequest) -> Unit,
    fixedTrigger: Boolean = false,
) {
    Text("触发", style = MaterialTheme.typography.titleSmall)
    if (fixedTrigger) Text("历史处理", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else WorkflowScheduleEditor(workflow, onChange)
    TriggerEditor(workflow.recognition, workflow.condition, references) { recognition, condition -> onChange(workflow.copy(recognition = recognition, condition = condition)) }
    HorizontalDivider()
    Text("行为", style = MaterialTheme.typography.titleSmall)
    if (workflow.nodes.any { node -> actions.firstOrNull { it.id == node.actionId }?.usesModel == true }) {
        CallModelChoice(workflow.modelProfileId, profiles) { onChange(workflow.copy(modelProfileId = it)) }
    }
    workflow.nodes.forEachIndexed { index, node -> key(node.id) {
        val change: (WorkflowNode) -> Unit = { changed -> onChange(workflow.copy(nodes = workflow.nodes.map { if (it.id == node.id) changed else it })) }
        if (workflow.nodes.size == 1) CallNodeActions(node, workflow.nodes, actions, profiles, references, change, onPrompt, !fixedTrigger)
        else EditorSection(node.title.ifBlank { "节点 ${index + 1}" }, actions.firstOrNull { it.id == node.actionId }?.title ?: node.actionId, initiallyExpanded = index == 0) {
            CallNodeActions(node, workflow.nodes, actions, profiles, references, change, onPrompt, !fixedTrigger)
            MoveButtons(index, workflow.nodes.size,
                { onChange(workflow.copy(nodes = workflow.nodes.moved(index, index - 1))) },
                { onChange(workflow.copy(nodes = workflow.nodes.moved(index, index + 1))) },
                { onChange(workflow.copy(nodes = workflow.nodes.filterNot { it.id == node.id })) })
        }
    } }
    EditorSection("复杂流程", "${workflow.nodes.size} 个节点 · 可配置依赖") {
        Text("新节点自动拥有独立结果宏；依赖在各节点的可选设置中配置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            val used = usedOutputKeys + workflow.nodes.map { it.outputKey }
            val outputKey = nextCallOutputKey(used, "step")
            onChange(workflow.copy(nodes = workflow.nodes + WorkflowNode(title = "节点 ${workflow.nodes.size + 1}", outputKey = outputKey)))
        }) { Text("添加节点") }
        if (workflow.nodes.size == 1) TextButton(onClick = { onChange(workflow.copy(nodes = emptyList())) }) { Text("移除当前节点") }
    }
}

@Composable
internal fun CallModelChoice(selected: String?, profiles: List<ModelProfile>, onChange: (String?) -> Unit) {
    val choices = listOf<Pair<String?, String>>(null to "使用对话默认模型") + profiles.map { it.id to it.name.ifBlank { it.model.ifBlank { it.id } } } +
        if (selected != null && profiles.none { it.id == selected }) listOf(selected to "未找到模型：$selected") else emptyList()
    ChoiceLine("调用默认模型", selected, choices, onChange)
}

@Composable
private fun WorkflowScheduleEditor(workflow: Workflow, onChange: (Workflow) -> Unit) {
    val choices = listOf(
        (RuntimeEvents.USER_MESSAGE to false) to "用户消息后 · 并行", (RuntimeEvents.USER_MESSAGE to true) to "用户消息后 · 排队",
        (RuntimeEvents.REPLY_COMPLETED to false) to "正文完成后 · 并行", (RuntimeEvents.REPLY_COMPLETED to true) to "正文完成后 · 排队",
    )
    val selected = workflow.trigger to workflow.queued
    ChoiceLine("时机", selected, choices + if (choices.none { it.first == selected }) listOf(selected to workflowScheduleLabel(workflow)) else emptyList()) { (trigger, queued) ->
        onChange(workflow.copy(trigger = trigger, queued = queued))
    }
    if (workflow.queued) {
        var priority by remember(workflow.id, workflow.priority) { mutableStateOf(workflow.priority.toString()) }
        OutlinedTextField(priority, { value -> priority = value; value.toIntOrNull()?.let { onChange(workflow.copy(priority = it)) } }, label = { Text("排队级别") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("数字越小越先完成；同级独立节点并行。可填负数，普通并行与默认调用为 0 级。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    EditorSection("其他触发事件", if (workflow.trigger !in listOf(RuntimeEvents.USER_MESSAGE, RuntimeEvents.REPLY_COMPLETED)) workflow.trigger else "发送前准备或自定义事件") {
        TextFieldLine("事件名称", workflow.trigger, { onChange(workflow.copy(trigger = it)) })
        TextButton(onClick = { onChange(workflow.copy(trigger = RuntimeEvents.TURN_PREPARE)) }) { Text("使用发送前准备（turn.prepare）") }
    }
}

@Composable
private fun CallNodeActions(
    node: WorkflowNode, nodes: List<WorkflowNode>, actions: List<ActionDescriptor>, profiles: List<ModelProfile>, references: List<PromptReferenceChoice>,
    onChange: (WorkflowNode) -> Unit, onPrompt: (PromptEditRequest) -> Unit, allowChatOutput: Boolean,
) {
    var rename by remember(node.id) { mutableStateOf(false) }
    val action = actions.firstOrNull { it.id == node.actionId }
    val nodeReferences = referencesForWritingNode(references, node.id)
    val usesModel = action?.usesModel == true
    EditorTitleRow(node.title, { rename = true }) { Switch(node.enabled, { onChange(node.copy(enabled = it)) }) }
    val primaryActions = actions.filter { it.presentation == ActionPresentation.STANDARD }
    val choices = primaryActions.map { it.id to (when (it.id) { "macro.process" -> "宏处理"; "prompt.inject" -> "管理提示词 · 插入/更新"; "prompt.remove" -> "管理提示词 · 移除"; else -> it.title }) }.distinctBy { it.first } +
        if (node.actionId !in primaryActions.map { it.id }) listOf(node.actionId to "已有动作：${action?.title ?: node.actionId}") else emptyList()
    ChoiceLine("动作", node.actionId, choices) { selected ->
        val args = if (selected.startsWith("prompt.") && node.arguments["id"].isNullOrBlank()) node.arguments + ("id" to node.id) else node.arguments
        onChange(node.copy(actionId = selected, arguments = args))
    }
    if (usesModel) EditorDestinationRow("提示词", "${node.blocks.size} 个提示词块") {
        onPrompt(PromptEditRequest(node.blocks, nodeReferences) { onChange(node.copy(blocks = it)) })
    }
    Text(action?.description ?: "此动作未注册；原有参数已保留，运行前需要恢复相应动作。", style = MaterialTheme.typography.bodySmall, color = if (action == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (usesModel && node.actionId != "model.generate")
        NodeModelOptionsEditor(node, profiles, onChange, nodeReferences)
    when (node.actionId) {
        "macro.process" -> MacroProcessEditor(node, onChange, nodeReferences)
        "prompt.inject", "prompt.remove" -> PromptManagementEditor(node, nodes, onChange, nodeReferences)
        "model.generate" -> {
            NodeModelOptionsEditor(node, profiles, onChange, nodeReferences)
            if (node.arguments.isNotEmpty()) ActionArgumentForm(node, action, onChange, nodeReferences)
        }
        "text.render" -> {
            PromptTemplateField("组合内容", node.inputTemplate, nodeReferences, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
            TextProcessingEditor("输入内容处理", node.inputProcessing, nodeReferences) { onChange(node.copy(inputProcessing = it)) }
            TextProcessingEditor("输出内容处理", node.outputProcessing, nodeReferences) { onChange(node.copy(outputProcessing = it)) }
            if (node.arguments.isNotEmpty()) ActionArgumentForm(node, action, onChange, nodeReferences)
        }
        else -> ActionArgumentForm(node, action, onChange, nodeReferences)
    }
    LegacyConversionOffer(node, onChange)
    if (allowChatOutput) {
        ToggleLine("显示到聊天", node.visible) { onChange(node.copy(visible = it)) }
        if (node.visible) ChoiceLine("显示位置", node.outputSlot, listOf(OutputSlot.BEFORE_BODY to "正文之前", OutputSlot.BODY to "正文", OutputSlot.AFTER_BODY to "正文之后")) { onChange(node.copy(outputSlot = it)) }
    }
    EditorSection("可选设置", "节点触发、输入、依赖与结果宏") {
        TriggerEditor(node.recognition, node.condition, nodeReferences) { recognition, condition -> onChange(node.copy(recognition = recognition, condition = condition)) }
        HorizontalDivider()
        if (usesModel) {
            ToggleLine("包含对话历史", node.includeHistory) { onChange(node.copy(includeHistory = it)) }
            ToggleLine("由模板插入历史", node.explicitHistory) { onChange(node.copy(explicitHistory = it)) }
        } else if (node.blocks.isNotEmpty()) EditorDestinationRow("保留的提示词", "${node.blocks.size} 个提示词块") {
            onPrompt(PromptEditRequest(node.blocks, nodeReferences) { onChange(node.copy(blocks = it)) })
        }
        if (node.actionId !in setOf("model.generate", "prompt.inject", "macro.process", "text.render"))
            PromptTemplateField("输入模板", node.inputTemplate, nodeReferences, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
        if (nodes.size > 1 || node.dependsOn.isNotEmpty()) {
            Text("依赖节点", style = MaterialTheme.typography.labelLarge)
            nodes.filter { it.id != node.id }.forEach { other -> ToggleLine(other.title.ifBlank { other.id.take(8) }, other.id in node.dependsOn) { checked -> onChange(node.copy(dependsOn = if (checked) (node.dependsOn + other.id).distinct() else node.dependsOn - other.id)) } }
            node.dependsOn.filter { id -> nodes.none { it.id == id } }.forEach { id -> ToggleLine("未找到节点：$id", true) { if (!it) onChange(node.copy(dependsOn = node.dependsOn - id)) } }
        }
        Text("结果宏自动归本节点所有。已有地址保持稳定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextFieldLine("宏地址：result. 后的键", node.outputKey, { onChange(node.copy(outputKey = it)) })
        TextFieldLine("结果分类（可选）", node.resultCategory, { onChange(node.copy(resultCategory = it)) })
    }
    if (rename) EditorRenameDialog("节点名称", node.title, { rename = false }) { onChange(node.copy(title = it)); rename = false }
}

internal fun nextCallOutputKey(used: Set<String>, stem: String): String = generateSequence(1) { it + 1 }.map { "${stem}_$it" }.first { it !in used }

/** A node cannot read its pending current output, but may read its own published macro history. */
internal fun referencesForWritingNode(references: List<PromptReferenceChoice>, nodeId: String): List<PromptReferenceChoice> =
    references.filterNot { it.producerNodeId == nodeId && !it.macro.startsWith("{{macro.") }

internal fun outputKeysForNewNodes(library: Library?, draftWorkflows: List<Workflow>): Set<String> = buildSet {
    library?.mainCalls?.forEach { main -> (main.workflows + main.history.processor.workflow).forEach { call -> call.nodes.forEach { add(it.outputKey) } } }
    library?.groups?.forEach { group -> group.workflows.forEach { call -> call.nodes.forEach { add(it.outputKey) } } }
    draftWorkflows.forEach { call -> call.nodes.forEach { add(it.outputKey) } }
}
