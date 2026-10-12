package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
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

internal data class PromptEditRequest(val blocks: List<PromptBlock>, val references: List<PromptReferenceChoice>, val location: ResourceLocation? = null, val workflowDraft: Boolean = false, val save: (List<PromptBlock>) -> Unit)
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
    titleContent: (@Composable () -> Unit)? = null,
    scrollKey: Any? = null, navigationContent: (@Composable () -> Unit)? = null,
    onBackWithinEditor: (() -> Boolean)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmBack by remember { mutableStateOf(false) }
    fun requestBack() { if (!saving && onBackWithinEditor?.invoke() != true) { if (dirty) confirmBack = true else onBack() } }
    SideEffect { onRegisterBack?.invoke(::requestBack) }
    // EditorPage retains the original native scaffold and input blocking during saves.
    EditorPage(title, ::requestBack, onSave, saving = saving, contentSpacing = 8.dp, titleContent = titleContent,
        scrollKey = scrollKey, navigationContent = navigationContent) {
        if (dirty) Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
    BackHandler { requestBack() }
    if (confirmBack) DraftLeaveDialog({ confirmBack = false }, { confirmBack = false; onSave() }, { confirmBack = false; onBack() })
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
    leadingContent: @Composable ColumnScope.() -> Unit = {},
) {
    CallEditorPage(title, false, onBack, onBack, onRegisterBack = onRegisterBack) {
        leadingContent()
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
    workflow.afterWorkflowId?.let { return "指定调用完成后 · ${it.take(8)} · ${if (workflow.queued) "排队 ${workflow.priority} 级" else "并行"}" }
    val phase = when (workflow.trigger) { RuntimeEvents.USER_MESSAGE -> "用户消息后"; RuntimeEvents.REPLY_COMPLETED -> "正文完成后"; RuntimeEvents.TURN_PREPARE -> "正文准备前"; RuntimeEvents.HISTORY_PROCESS -> "历史处理"; else -> workflow.trigger }
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
    siblings: List<Workflow> = emptyList(), tags: List<PromptTag> = emptyList(),
    triggerTargets: List<Workflow> = siblings,
    specialDefault: Boolean = false,
) {
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val references = referenceChoices(workflow)
    val sourceFocus = LocalSourceNavigation.current?.focus
    var section by remember(workflow.id, sourceFocus) { mutableStateOf(
        if (sourceFocus?.field?.field in setOf(ExtensionField.WORKFLOW_QUEUED, ExtensionField.WORKFLOW_PRIORITY)) 1 else 0) }
    var selectedBehavior by rememberSaveable(workflow.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(sourceFocus?.nodeId) { sourceFocus?.nodeId?.let { selectedBehavior = it; section = 0 } }
    WorkflowSourceBoundary(workflow, onDraftChange) {
    CallEditorPage(if (specialDefault) "默认模型调用" else "调用", workflow != initial, onBack, { onSave(workflow) }, onRegisterBack = onRegisterBack,
        onBackWithinEditor = { if (!specialDefault && selectedBehavior != null) { selectedBehavior = null; section = 0; true } else false },
        scrollKey = section to selectedBehavior, navigationContent = { EditorSectionTabs(listOf("行为列表", "触发与调度"), section) { section = it } }) {
        EditorTitleRow(workflow.title, { rename = true }) { Switch(workflow.enabled, { onDraftChange(workflow.copy(enabled = it)) }) }
        WorkflowEditorContent(workflow, actions, profiles, usedOutputKeys, references, onDraftChange, onPrompt,
            tags = tags, triggerTargets = triggerTargets, specialDefault = specialDefault, selectedSection = section, selectedBehavior = selectedBehavior, onSelectBehavior = { selectedBehavior = it })
        if (onDelete != null && !specialDefault && selectedBehavior == null) {
            HorizontalDivider()
            TextButton(onClick = { delete = true }) { Text("删除调用", color = MaterialTheme.colorScheme.error) }
        }
    }
    }
    if (rename) NamingDialog("调用名称", workflow.title, workflow.slug,
        siblings.filterNot { it.id == workflow.id }.map { it.title }, siblings.filterNot { it.id == workflow.id }.map { it.slug },
        { rename = false }) { title, slug -> onDraftChange(workflow.copy(title = title, slug = slug)); rename = false }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除调用？") }, text = { Text("该调用及行为将从资源草稿移除。") },
        confirmButton = { TextButton(onClick = { delete = false; onDelete?.invoke() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}

@Composable
internal fun WorkflowEditorContent(
    workflow: Workflow, actions: List<ActionDescriptor>, profiles: List<ModelProfile>,
    usedOutputKeys: Set<String>, references: List<PromptReferenceChoice>,
    onChange: (Workflow) -> Unit, onPrompt: (PromptEditRequest) -> Unit,
    fixedTrigger: Boolean = false, tags: List<PromptTag> = emptyList(), triggerTargets: List<Workflow> = emptyList(),
    specialDefault: Boolean = false,
    selectedSection: Int? = null,
    selectedBehavior: String? = null, onSelectBehavior: ((String?) -> Unit)? = null,
) {
    val sourceNavigation = LocalSourceNavigation.current
    var localSection by remember(workflow.id) { mutableStateOf(0) }
    var localBehavior by rememberSaveable(workflow.id) { mutableStateOf<String?>(null) }
    val selectedId = if (onSelectBehavior == null) localBehavior else selectedBehavior
    fun select(id: String?) { if (onSelectBehavior != null) onSelectBehavior(id) else localBehavior = id }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val section = selectedSection ?: localSection
    if (selectedSection == null) EditorSectionTabs(listOf("行为列表", "触发与调度"), section) { localSection = it }
    val promptWithOwner: (PromptEditRequest) -> Unit = { request ->
        onPrompt(request.copy(location = request.location?.copy(workflowId = workflow.id) ?: sourceNavigation?.resource?.let { ResourceLocation(it, workflowId = workflow.id) }))
    }
    if (section == 1) {
        Text("触发", style = MaterialTheme.typography.titleSmall)
        if (fixedTrigger) Text(if (workflow.afterWorkflowId != null) "小摘要完成后" else "历史处理")
        else WorkflowScheduleEditor(workflow, triggerTargets, onChange)
        TriggerEditor(workflow.recognition, workflow.condition, references) { recognition, condition -> onChange(workflow.copy(recognition = recognition, condition = condition)) }
        return
    }
    val selected = workflow.nodes.firstOrNull { it.id == selectedId }
    if (workflow.nodes.any { it.actionId == "prompt.inject" } && workflow.trigger != RuntimeEvents.TURN_PREPARE && workflow.afterWorkflowId == null && !fixedTrigger) {
        Text("提示词注入需要早于目标模型请求完成。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onChange(workflow.copy(trigger = RuntimeEvents.TURN_PREPARE)) }) { Text("改为正文准备前触发") }
    }
    if (specialDefault || selected != null) {
        if (!specialDefault) TextButton(onClick = { select(null) }) { Text("返回全部行为（${workflow.nodes.size}）") }
        val editingNodes = if (specialDefault) workflow.nodes else listOfNotNull(selected)
        editingNodes.forEach { node -> key(node.id) {
            CallNodeActions(node, workflow.nodes, actions, profiles, references,
                { changed -> onChange(workflow.copy(nodes = workflow.nodes.map { if (it.id == node.id) changed else it })) },
                promptWithOwner, tags, usedOutputKeys, specialDefault)
        } }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${workflow.nodes.size} 个行为", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { adding = true }) { Text("添加行为") }
        }
        Text("一个调用统一触发这些行为。需要先后关系时，在行为中设置依赖。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (workflow.nodes.isEmpty()) Text("还没有行为，点击“添加行为”选择要做的事。")
        if (workflow.nodes.any { node -> actions.firstOrNull { it.id == node.actionId }?.usesModel == true })
            CallModelChoice(workflow.modelProfileId, profiles) { onChange(workflow.copy(modelProfileId = it)) }
        workflow.nodes.forEachIndexed { index, node -> key(node.id) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${node.title.ifBlank { "未命名行为" }}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(node.enabled, { enabled -> onChange(workflow.copy(nodes = workflow.nodes.map { if (it.id == node.id) it.copy(enabled = enabled) else it })) })
                    }
                    CallBehaviorTypeChoice(node, actions) { changed -> onChange(workflow.copy(nodes = workflow.nodes.map { if (it.id == node.id) changed else it })) }
                    Text(callBehaviorSummary(node, references, sourceNavigation?.library), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { select(node.id) }) { Text("编辑行为") }
                        TextButton(onClick = { onChange(workflow.copy(nodes = workflow.nodes.moved(index, index - 1))) }, enabled = index > 0) { Text("上移") }
                        TextButton(onClick = { onChange(workflow.copy(nodes = workflow.nodes.moved(index, index + 1))) }, enabled = index < workflow.nodes.lastIndex) { Text("下移") }
                        TextButton(onClick = { deleting = node.id }) { Text("删除") }
                    }
                }
            }
        } }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加行为") },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            editableCallBehaviorTypes(actions).forEach { action ->
                OutlinedButton(onClick = {
                    val node = newCallBehavior(action, workflow.nodes, usedOutputKeys, actions)
                    onChange(workflow.copy(nodes = workflow.nodes + node)); adding = false; select(node.id)
                }, modifier = Modifier.fillMaxWidth()) { Text(callBehaviorTypeLabel(action.id, actions)) }
            }
        } }, confirmButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这个行为？") },
        text = { Text("同时移除本调用中其他行为对它的执行依赖；已有结果引用需要另行检查。保存调用后生效。") },
        confirmButton = { TextButton(onClick = {
            onChange(workflow.copy(nodes = workflow.nodes.filterNot { it.id == id }.map { it.copy(dependsOn = it.dependsOn - id) })); deleting = null
        }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun CallBehaviorTypeChoice(node: WorkflowNode, actions: List<ActionDescriptor>, onChange: (WorkflowNode) -> Unit) {
    val types = editableCallBehaviorTypes(actions)
    val choices = types.map { it.id to callBehaviorTypeLabel(it.id, actions) } +
        if (types.none { it.id == node.actionId }) listOf(node.actionId to "已有兼容类型：${callBehaviorTypeLabel(node.actionId, actions)}") else emptyList()
    ChoiceLine("行为类型", node.actionId, choices) { selected ->
        actions.firstOrNull { it.id == selected }?.let { onChange(changeCallBehaviorType(node, it, actions)) }
    }
}

@Composable
internal fun CallModelChoice(selected: String?, profiles: List<ModelProfile>, onChange: (String?) -> Unit) {
    val choices = listOf<Pair<String?, String>>(null to "使用对话默认模型") + profiles.map { it.id to it.name.ifBlank { it.model.ifBlank { it.id } } } +
        if (selected != null && profiles.none { it.id == selected }) listOf(selected to "未找到模型：$selected") else emptyList()
    ChoiceLine("调用默认模型", selected, choices, onChange)
}

@Composable
private fun WorkflowScheduleEditor(workflow: Workflow, triggerTargets: List<Workflow>, onChange: (Workflow) -> Unit) {
    val targets = triggerTargets.filter { it.id != workflow.id && it.trigger != RuntimeEvents.HISTORY_PROCESS }.distinctBy { it.id }
    val completionOptions = listOf<Pair<String?, String>>(null to "不跟随其他调用") + targets.map { it.id to it.title.ifBlank { "未命名调用" } }
    val selectedTarget = workflow.afterWorkflowId
    ChoiceLine("指定调用完成后", selectedTarget,
        completionOptions + if (selectedTarget != null && targets.none { it.id == selectedTarget }) listOf(selectedTarget to "未找到调用：$selectedTarget") else emptyList()) { target ->
        onChange(workflow.copy(afterWorkflowId = target))
    }
    if (selectedTarget != null) {
        Text("所选调用成功完成后触发；按稳定调用标识关联，重命名不影响触发。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToggleLine("排队执行", workflow.queued) { onChange(workflow.copy(queued = it)) }
    } else {
        val choices = listOf(
            (RuntimeEvents.TURN_PREPARE to false) to "正文准备前 · 并行", (RuntimeEvents.TURN_PREPARE to true) to "正文准备前 · 排队",
            (RuntimeEvents.USER_MESSAGE to false) to "用户消息后 · 并行", (RuntimeEvents.USER_MESSAGE to true) to "用户消息后 · 排队",
            (RuntimeEvents.REPLY_COMPLETED to false) to "正文完成后 · 并行", (RuntimeEvents.REPLY_COMPLETED to true) to "正文完成后 · 排队",
        )
        val selected = workflow.trigger to workflow.queued
        ChoiceLine("时机", selected, choices + if (choices.none { it.first == selected }) listOf(selected to workflowScheduleLabel(workflow)) else emptyList()) { (trigger, queued) ->
            onChange(workflow.copy(trigger = trigger, queued = queued))
        }
    }
    if (selectedTarget == null && workflow.trigger == RuntimeEvents.REPLY_COMPLETED) Text(
        "默认调用成功产出完整正文并完成后处理后触发；其他调用的可见输出不会触发正文完成后。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("并行调用独立推进，排队积压不会阻塞并行路径；排队级别只用于排队调用。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (workflow.queued) {
        var priority by remember(workflow.id, workflow.priority) { mutableStateOf(workflow.priority.toString()) }
        OutlinedTextField(priority, { value -> priority = value; value.toIntOrNull()?.let { onChange(workflow.copy(priority = it)) } }, label = { Text("排队级别") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("排队路径中，数字越小越先执行；同级无依赖行为仍并行。可填负数。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (workflow.nodes.any { it.actionId == "prompt.inject" || it.actionId == "prompt.remove" }) Text(
        "提示词注入须在目标模型请求开始前完成，可选择正文准备前。排队级别只影响调用执行；同一接收槽的内容位置由注入设置中的上下文顺序单独控制。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (selectedTarget == null) EditorSection("其他触发事件", if (workflow.trigger !in listOf(RuntimeEvents.USER_MESSAGE, RuntimeEvents.REPLY_COMPLETED, RuntimeEvents.TURN_PREPARE)) workflow.trigger else "自定义事件") {
        TextFieldLine("事件名称", workflow.trigger, { onChange(workflow.copy(trigger = it)) })
        TextButton(onClick = { onChange(workflow.copy(trigger = RuntimeEvents.TURN_PREPARE)) }) { Text("使用正文准备前") }
    }
}

@Composable
private fun CallNodeActions(
    node: WorkflowNode, nodes: List<WorkflowNode>, actions: List<ActionDescriptor>, profiles: List<ModelProfile>, references: List<PromptReferenceChoice>,
    onChange: (WorkflowNode) -> Unit, onPrompt: (PromptEditRequest) -> Unit, tags: List<PromptTag>, usedOutputKeys: Set<String>,
    specialDefault: Boolean = false,
) {
    val sourceNavigation = LocalSourceNavigation.current
    val sourceLocation = sourceNavigation?.resource?.let { ResourceLocation(it, workflowId = sourceNavigation.workflowId, nodeId = node.id) }
    var rename by remember(node.id) { mutableStateOf(false) }
    val action = actions.firstOrNull { it.id == node.actionId }
    val nodeReferences = referencesForWritingNode(references, node.id)
    val usesModel = action?.usesModel == true
    if (sourceNavigation?.focus?.let { it.nodeId == node.id && it.field == null } == true) Text("已定位到此行为。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    EditorTitleRow(node.title, { rename = true }) { Switch(node.enabled, { onChange(node.copy(enabled = it)) }) }
    if (specialDefault) Text("行为类型：调用模型 · 主回复正文", style = MaterialTheme.typography.bodySmall)
    else CallBehaviorTypeChoice(node, actions, onChange)
    if (usesModel) EditorDestinationRow("提示词", "${node.blocks.size} 个提示词块") {
        onPrompt(PromptEditRequest(node.blocks, nodeReferences, sourceLocation) { onChange(node.copy(blocks = it)) })
    }
    Text(action?.description ?: "此行为类型未注册；原有参数已保留。", style = MaterialTheme.typography.bodySmall, color = if (action == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (usesModel && node.actionId != "model.generate")
        NodeModelOptionsEditor(node, profiles, onChange, nodeReferences)
    when (node.actionId) {
        "card.define" -> CardDefinitionEditor(node, nodeReferences, onChange = onChange)
        "macro.process" -> MacroProcessEditor(node, onChange, nodeReferences)
        "user.macro.create" -> UserMacroBehaviorEditor(node, nodeReferences, onChange)
        "prompt.inject", "prompt.remove" -> PromptManagementEditor(node, nodes, onChange, nodeReferences, actions)
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
        "text.extract" -> {
            PromptTemplateField("提取来源", node.inputTemplate, nodeReferences, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
            ActionArgumentForm(node, action, onChange, nodeReferences)
            Text("提取结果写入本行为自己的结果宏；来源保持原样。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "history.view.copy", "history.memory.write" -> HistoryActionEditor(node, onChange, nodeReferences)
        else -> ActionArgumentForm(node, action, onChange, nodeReferences)
    }
    if (usesModel) {
        RegexOutputCopyEditor(node, usedOutputKeys) { onChange(node.copy(regexOutputCopy = it)) }
    }
    HorizontalDivider()
    Text("执行条件", style = MaterialTheme.typography.titleSmall)
    TriggerEditor(node.recognition, node.condition, nodeReferences) { recognition, condition -> onChange(node.copy(recognition = recognition, condition = condition)) }
    if (!specialDefault && (nodes.size > 1 || node.dependsOn.isNotEmpty())) {
        Text("先完成的行为", style = MaterialTheme.typography.titleSmall)
        Text("勾选的行为完成后才执行；未勾选表示可独立执行。列表上下位置只用于整理。", style = MaterialTheme.typography.bodySmall)
        nodes.filter { it.id != node.id }.forEach { other -> ToggleLine(other.title.ifBlank { "未命名行为" }, other.id in node.dependsOn) { checked ->
            onChange(node.copy(dependsOn = if (checked) (node.dependsOn + other.id).distinct() else node.dependsOn - other.id))
        } }
        node.dependsOn.filter { id -> nodes.none { it.id == id } }.forEach { id -> ToggleLine("缺失的行为：$id", true) { if (!it) onChange(node.copy(dependsOn = node.dependsOn - id)) } }
    }
    EditorSection("结果与高级配置", "结果引用、记录与保留配置", initiallyExpanded = sourceNavigation?.focus?.nodeId == node.id) {
        NodeTrajectoryControl(node, onChange, usesModel)
        PromptTagSelector(tags, node.tagIds) { onChange(node.copy(tagIds = it)) }
        if (usesModel) {
            ToggleLine("包含对话历史", node.includeHistory) { onChange(node.copy(includeHistory = it)) }
            ToggleLine("由模板插入历史", node.explicitHistory) { onChange(node.copy(explicitHistory = it)) }
        } else if (node.blocks.isNotEmpty()) EditorDestinationRow("保留的提示词", "${node.blocks.size} 个提示词块") {
            onPrompt(PromptEditRequest(node.blocks, nodeReferences, sourceLocation) { onChange(node.copy(blocks = it)) })
        }
        if (node.actionId !in setOf("card.define", "model.generate", "prompt.inject", "macro.process", "text.render", "text.extract", "history.view.copy", "history.memory.write"))
            PromptTemplateField("输入模板", node.inputTemplate, nodeReferences, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
        if (node.actionId == "user.macro.create") {
            Text("该行为登记输入快捷词，不生成结果文本。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("结果宏自动归本行为所有。已有地址保持稳定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LegacyAddressLine("result.${node.outputKey}")
            TextFieldLine("结果分类（可选）", node.resultCategory, { onChange(node.copy(resultCategory = it)) })
        }
    }
    if (rename) NamingDialog("行为名称与结果引用", node.title, node.slug,
        nodes.filterNot { it.id == node.id }.map { it.title }, nodes.filterNot { it.id == node.id }.map { it.slug },
        { rename = false }) { title, slug -> onChange(node.copy(title = title, slug = slug)); rename = false }
}

@Composable
internal fun NodeTrajectoryControl(node: WorkflowNode, onChange: (WorkflowNode) -> Unit, usesModel: Boolean = false) {
    if (usesModel) Text("模型结果自动保存；若有原始输出，也会留档。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        ToggleLine("记录轨迹", node.recordTrajectory) { onChange(node.copy(recordTrajectory = it)) }
        Text("控制此行为写入的结果、状态和宏处理目标。关闭时只更新当前值；每轮分支与恢复数据仍完整保留，已有轨迹不会删除。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun nextCallOutputKey(used: Set<String>, stem: String): String = generateSequence(1) { it + 1 }.map { "${stem}_$it" }.first { it !in used }

/** A node cannot read its pending current output, but may read its own published macro history. */
internal fun referencesForWritingNode(references: List<PromptReferenceChoice>, nodeId: String): List<PromptReferenceChoice> =
    references.filterNot { it.producerNodeId == nodeId && !it.macro.startsWith("{{macro.") }

internal fun outputKeysForNewNodes(library: Library?, draftWorkflows: List<Workflow>): Set<String> = buildSet {
    fun collect(call: Workflow) = call.nodes.forEach { node -> add(node.outputKey); node.regexOutputCopy.outputKey.takeIf { node.regexOutputCopy.enabled && it.isNotBlank() }?.let { add(it) } }
    library?.mainCalls?.forEach { main -> main.configuredWorkflows().forEach(::collect) }
    library?.groups?.forEach { group -> group.workflows.forEach(::collect) }
    draftWorkflows.forEach(::collect)
}

@Composable
private fun HistoryActionEditor(node: WorkflowNode, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice>) {
    val copyView = node.actionId == "history.view.copy"
    val args = node.arguments
    fun set(name: String, value: String) = onChange(node.copy(arguments = args + (name to value)))
    Text("历史来源使用完整引用；在下方选取资料中可选最终正文／模型原文、范围与副本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val sourceExpression = args["source"] ?: "{{history}}"
    TextFieldLine("来源历史引用", sourceExpression, { set("source", it) }, minLines = 2)
    if (HistoryViews.selection(sourceExpression) == null) Text("请输入有效的历史引用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    val historyChoices = references.filter { it.sourceId == "history" && it.macro.startsWith("{{type:builtin.in:history.") }
    if (historyChoices.isNotEmpty()) PromptReferencePicker(historyChoices, { set("source", it) }, versioned = false)
    if (copyView) {
        TextFieldLine("副本英文标识", args["targetId"].orEmpty(), { set("targetId", it) })
        if (args["targetId"]?.let(MacroNaming::isValidSlug) != true) Text("请输入有效的副本英文标识。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("稳定标识只用小写英文字母、数字、_ 或 -，以字母开头。更新同名副本会增加独立版本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextFieldLine("副本名称", args["name"].orEmpty(), { set("name", it) })
        ChoiceLine("处理方式", args["mode"] ?: "COMPRESS", listOf("COMPRESS" to "压缩为摘要", "EXPAND" to "展开原楼层")) { set("mode", it) }
        PromptTemplateField("记忆段标识（可为空）", args["ids"].orEmpty(), references, { set("ids", it) }, minLines = 2)
        Text("可让普通调用读取 {{history.summary}} 中的记忆段标识，再把选择结果宏填到这里；留空表示不指定新的记忆段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var regexOpen by remember(node.id) { mutableStateOf(!args["pattern"].isNullOrBlank()) }
        TextButton(onClick = { regexOpen = !regexOpen }) { Text(if (regexOpen) "收起标识提取" else "正则提取标识（可选）") }
        if (regexOpen) {
            TextFieldLine("正则表达式", args["pattern"].orEmpty(), { set("pattern", it) })
            val groupValue = args["captureGroup"]?.toIntOrNull() ?: 0
            args["pattern"]?.takeIf { it.isNotBlank() }?.let { pattern -> SafePatterns.validate(pattern, groupValue) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            TextFieldLine("捕获组", args["captureGroup"] ?: "0", { set("captureGroup", it) })
        }
        Text("副本是可插入结构化历史的视图；用 view:英文标识 引用，不使用普通文本结果宏读取。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        TextFieldLine("记忆段名称", args["title"].orEmpty(), { set("title", it) })
        PromptTemplateField("摘要内容", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 3)
        Text("摘要会记录来源楼层及其文本版本；聊天原文仍可从来源楼层查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val known = if (copyView) setOf("source", "targetId", "name", "mode", "ids", "pattern", "captureGroup") else setOf("source", "title")
    if (args.keys.any { it !in known }) ActionArgumentForm(node, null, onChange, references)
}

/** The source node keeps its raw result; this configuration publishes one separate copy. */
@Composable
internal fun RegexOutputCopyEditor(node: WorkflowNode, usedOutputKeys: Set<String>, onChange: (RegexOutputCopy) -> Unit) {
    val copy = node.regexOutputCopy
    EditorSection("正则输出副本", if (copy.enabled) "result.${copy.outputKey.ifBlank { "未设置" }}" else "关闭") {
        ToggleLine("创建正则副本", copy.enabled) { enabled ->
            onChange(copy.copy(enabled = enabled,
                recordTrajectory = if (enabled && copy == RegexOutputCopy()) false else copy.recordTrajectory,
                outputKey = if (enabled && copy.outputKey.isBlank()) nextCallOutputKey(usedOutputKeys + node.outputKey, "copy") else copy.outputKey))
        }
        if (copy.enabled) {
            Text("从此行为的结果生成独立副本；原结果宏 result.${node.outputKey} 保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleLine("副本记录轨迹", copy.recordTrajectory) { onChange(copy.copy(recordTrajectory = it)) }
            Text("关闭时只更新副本当前值；每轮分支与恢复数据仍完整保留，已有轨迹不会删除。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextFieldLine("副本结果键", copy.outputKey, { onChange(copy.copy(outputKey = it)) })
            if (copy.outputKey.isBlank() || copy.outputKey == node.outputKey) Text("副本结果键须与原结果键不同。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            ChoiceLine("处理方式", copy.mode, listOf(RegexTransformMode.EXTRACT to "提取匹配文本", RegexTransformMode.REPLACE to "替换匹配文本")) { onChange(copy.copy(mode = it)) }
            TextFieldLine("正则表达式", copy.pattern, { onChange(copy.copy(pattern = it)) })
            SafePatterns.validate(copy.pattern, if (copy.mode == RegexTransformMode.EXTRACT) copy.captureGroup else 0)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (copy.mode == RegexTransformMode.EXTRACT) {
                var group by remember(node.id, copy.captureGroup) { mutableStateOf(copy.captureGroup.toString()) }
                OutlinedTextField(group, { value -> group = value; value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(copy.copy(captureGroup = it)) } },
                    label = { Text("捕获组 · 0 为完整匹配") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            } else TextFieldLine("替换内容", copy.replacement, { onChange(copy.copy(replacement = it)) }, minLines = 2)
            ToggleLine("在聊天中显示处理后的副本", copy.display) { onChange(copy.copy(display = it)) }
            Text("副本始终可用 result.${copy.outputKey.ifBlank { "副本结果键" }} 引用；显示开关只影响正文呈现。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
