package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

private val apiRoles = Role.entries.map { it to "${roleTitle(it)}（${it.name}）" }
private val globalRoleChoices = listOf<Pair<Role?, String>>(null to "保留原说话方角色") + apiRoles
private val speakerRoleChoices = listOf<Pair<Role?, String>>(null to "跟随通用配置") + apiRoles
private val floorRoleChoices = listOf<Pair<Role?, String>>(null to "跟随通用／说话方配置") + apiRoles
private fun roleTitle(role: Role) = when (role) { Role.SYSTEM -> "系统"; Role.USER -> "用户"; Role.ASSISTANT -> "助手" }

@Composable
private fun NumberLine(label: String, value: Int, minimum: Int = 0, onChange: (Int) -> Unit) {
    var draft by remember(label) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (draft.toIntOrNull() != value) draft = value.toString() }
    OutlinedTextField(draft, { text ->
        if (text.isEmpty() || text.all(Char::isDigit)) { draft = text; text.toIntOrNull()?.takeIf { it >= minimum }?.let(onChange) }
    }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        supportingText = { if ((draft.toIntOrNull() ?: -1) < minimum) Text("请输入不小于 $minimum 的整数") })
}

@Composable
private fun HistoryPointForm(label: String, point: HistoryPoint, onChange: (HistoryPoint) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    ChoiceLine("位置", point.anchor, listOf(HistoryAnchor.START to "从开头", HistoryAnchor.END to "从末尾", HistoryAnchor.PERCENT to "百分比")) {
        onChange(point.copy(anchor = it, value = if (it == HistoryAnchor.PERCENT) 0.0 else 1.0))
    }
    var draft by remember(label, point.anchor) { mutableStateOf(point.value.toString()) }
    OutlinedTextField(draft, { text ->
        draft = text
        val parsed = text.toDoubleOrNull()
        if (parsed != null && parsed.isFinite() && parsed >= 0.0 && (point.anchor != HistoryAnchor.PERCENT || parsed <= 100.0)) onChange(point.copy(value = parsed))
    }, label = { Text(if (point.anchor == HistoryAnchor.PERCENT) "百分比（0–100）" else "条数／间隔数") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        supportingText = { Text(if (point.anchor == HistoryAnchor.PERCENT) "0 为开头，100 为末尾" else "从 1 开始定位历史楼层；插入位置可用 0") })
}

@Composable
internal fun HistoryConfigEditor(
    history: HistoryConfig, library: Library?, actions: List<ActionDescriptor>, profiles: List<ModelProfile>,
    references: List<PromptReferenceChoice>, usedOutputKeys: Set<String>, onChange: (HistoryConfig) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider()
    TextButton(onClick = { open = !open }) { Text(if (open) "收起历史配置" else "历史配置 · 范围、包装与处理") }
    if (!open) return
    Text("历史仅改变发给模型的上下文，原聊天记录始终保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HistoryPointForm("范围起点", history.range.from) { onChange(history.copy(range = history.range.copy(from = it))) }
    HistoryPointForm("范围终点（包含）", history.range.through) { onChange(history.copy(range = history.range.copy(through = it))) }
    ChoiceLine("整个历史的 API 角色", history.apiRole, globalRoleChoices) { onChange(history.copy(apiRole = it)) }
    ToggleLine("将历史合并为一条消息", history.mergeMessages) { onChange(history.copy(mergeMessages = it)) }
    TextFieldLine("整个历史前缀（原样文本）", history.prefix, { onChange(history.copy(prefix = it)) }, minLines = 2)
    TextFieldLine("整个历史后缀（原样文本）", history.suffix, { onChange(history.copy(suffix = it)) }, minLines = 2)
    TextFieldLine("每层 BODY 消息前缀", history.floorPrefix, { onChange(history.copy(floorPrefix = it)) })
    TextFieldLine("每层 BODY 消息后缀", history.floorSuffix, { onChange(history.copy(floorSuffix = it)) })
    Text("楼层包装可用局部标签 {{speaker}}、{{speaker.name}} 与 {{floor}}；一层对应一条 BODY 消息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("按说话方覆盖", style = MaterialTheme.typography.titleMedium)
    Role.entries.forEach { role ->
        val rule = history.speakers.firstOrNull { it.speaker == role }
        ToggleLine(roleTitle(role), rule != null) { enabled -> onChange(history.copy(speakers = if (enabled) history.speakers + HistorySpeakerRule(role) else history.speakers.filterNot { it.speaker == role })) }
        rule?.let {
            ChoiceLine("${roleTitle(role)} API 角色", it.apiRole, speakerRoleChoices) { changed -> onChange(history.copy(speakers = history.speakers.map { rule -> if (rule.speaker == role) rule.copy(apiRole = changed) else rule })) }
            ToggleLine("继承通用前缀", it.prefix == null) { inherit -> onChange(history.copy(speakers = history.speakers.map { rule -> if (rule.speaker == role) rule.copy(prefix = if (inherit) null else "") else rule })) }
            it.prefix?.let { prefix -> TextFieldLine("${roleTitle(role)}前缀（可留空）", prefix, { changed -> onChange(history.copy(speakers = history.speakers.map { rule -> if (rule.speaker == role) rule.copy(prefix = changed) else rule })) }) }
            ToggleLine("继承通用后缀", it.suffix == null) { inherit -> onChange(history.copy(speakers = history.speakers.map { rule -> if (rule.speaker == role) rule.copy(suffix = if (inherit) null else "") else rule })) }
            it.suffix?.let { suffix -> TextFieldLine("${roleTitle(role)}后缀（可留空）", suffix, { changed -> onChange(history.copy(speakers = history.speakers.map { rule -> if (rule.speaker == role) rule.copy(suffix = changed) else rule })) }) }
        }
    }

    Text("指定消息覆盖", style = MaterialTheme.typography.titleMedium)
    val messages = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }?.messages.orEmpty().filter { it.outputSlot == OutputSlot.BODY }
    val choices = messages.map { message -> message.id to "${roleTitle(message.role)} · ${message.text.replace('\n', ' ').take(32)} · ${message.id.take(8)}" }
    var selectedMessage by remember(choices) { mutableStateOf(choices.firstOrNull()?.first.orEmpty()) }
    ChoiceLine("聊天消息", selectedMessage, choices) { selectedMessage = it }
    OutlinedButton(onClick = { onChange(history.copy(floors = history.floors + HistoryFloorRule(selectedMessage))) }, enabled = selectedMessage.isNotBlank() && history.floors.none { it.messageId == selectedMessage }) { Text("添加消息规则") }
    history.floors.forEach { rule ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(choices.firstOrNull { it.first == rule.messageId }?.second ?: "历史消息 · ${rule.messageId.take(8)}")
            ChoiceLine("API 角色", rule.apiRole, floorRoleChoices) { value -> onChange(history.copy(floors = history.floors.map { if (it.messageId == rule.messageId) it.copy(apiRole = value) else it })) }
            ToggleLine("继承通用／说话方前缀", rule.prefix == null) { inherit -> onChange(history.copy(floors = history.floors.map { if (it.messageId == rule.messageId) it.copy(prefix = if (inherit) null else "") else it })) }
            rule.prefix?.let { prefix -> TextFieldLine("前缀（可留空）", prefix, { value -> onChange(history.copy(floors = history.floors.map { if (it.messageId == rule.messageId) it.copy(prefix = value) else it })) }) }
            ToggleLine("继承通用／说话方后缀", rule.suffix == null) { inherit -> onChange(history.copy(floors = history.floors.map { if (it.messageId == rule.messageId) it.copy(suffix = if (inherit) null else "") else it })) }
            rule.suffix?.let { suffix -> TextFieldLine("后缀（可留空）", suffix, { value -> onChange(history.copy(floors = history.floors.map { if (it.messageId == rule.messageId) it.copy(suffix = value) else it })) }) }
            TextButton(onClick = { onChange(history.copy(floors = history.floors.filterNot { it.messageId == rule.messageId })) }) { Text("删除规则") }
        } }
    }

    Text("历史插入项", style = MaterialTheme.typography.titleMedium)
    history.insertions.forEachIndexed { index, insertion -> key(insertion.id) {
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fun update(value: HistoryInsertion) = onChange(history.copy(insertions = history.insertions.toMutableList().apply { set(index, value) }))
            TextFieldLine("名称", insertion.title, { update(insertion.copy(title = it)) })
            ToggleLine("启用", insertion.enabled) { update(insertion.copy(enabled = it)) }
            ChoiceLine("API 角色", insertion.role, Role.entries.map { it to "${roleTitle(it)}（${it.name}）" }) { update(insertion.copy(role = it)) }
            HistoryPointForm("插入位置（位于楼层之间）", insertion.at) { update(insertion.copy(at = it)) }
            PromptTemplateField("插入内容", insertion.template, references, { update(insertion.copy(template = it)) }, minLines = 2)
            MoveButtons(index, history.insertions.size,
                { onChange(history.copy(insertions = history.insertions.moved(index, index - 1))) },
                { onChange(history.copy(insertions = history.insertions.moved(index, index + 1))) },
                { onChange(history.copy(insertions = history.insertions.filterNot { it.id == insertion.id })) })
        } }
    } }
    OutlinedButton(onClick = { onChange(history.copy(insertions = history.insertions + HistoryInsertion())) }) { Text("添加历史插入项") }

    Text("历史上下文处理", style = MaterialTheme.typography.titleMedium)
    Text("积累足够新楼层时运行独立调用，结果作为摘要参与之后的历史投影。默认关闭。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("处理结果用于上下文；需要查看时可把结果宏绑定到展示磁贴。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val processor = history.processor
    NumberLine("触发所需新增楼层", processor.minimumNewFloors, 1) { onChange(history.copy(processor = processor.copy(minimumNewFloors = it))) }
    NumberLine("保留最近楼层", processor.keepRecentFloors) { onChange(history.copy(processor = processor.copy(keepRecentFloors = it))) }
    ChoiceLine("摘要来自节点", processor.outputKey, processor.workflow.nodes.map { it.outputKey to "${it.title} · ${it.outputKey}" }) { onChange(history.copy(processor = processor.copy(outputKey = it))) }
    TextFieldLine("摘要输出键", processor.outputKey, { onChange(history.copy(processor = processor.copy(outputKey = it))) })
    val processorReferences = references + listOf(
        PromptReferenceChoice("待处理历史", "{{history.pending}}", "历史处理"),
        PromptReferenceChoice("待处理楼层数量", "{{history.pending.count}}", "历史处理"),
        PromptReferenceChoice("原始历史", "{{history.raw}}", "历史处理"),
        PromptReferenceChoice("已有摘要", "{{history.summary}}", "历史处理"),
        PromptReferenceChoice("楼层数量", "{{history.count}}", "历史处理")
    )
    WorkflowForm(processor.workflow, 0, 1, actions, profiles, usedOutputKeys, processorReferences,
        { changed -> onChange(history.copy(processor = processor.copy(workflow = changed.copy(trigger = "history.process")))) },
        {}, {}, {}, fixedTrigger = true)
}
