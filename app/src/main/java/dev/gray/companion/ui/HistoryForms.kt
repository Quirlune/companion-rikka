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
internal fun NumberLine(label: String, value: Int, minimum: Int = 0, onChange: (Int) -> Unit) {
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
    history: HistoryConfig, library: Library?, references: List<PromptReferenceChoice>, onChange: (HistoryConfig) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    HorizontalDivider()
    TextButton(onClick = { open = !open }) { Text(if (open) "收起历史配置" else "历史配置 · 范围、角色与包装") }
    if (!open) return
    Text("这里设置主调用的默认历史投影；提示词中的每条结构化历史引用也可独立选择范围。原聊天记录始终保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val session = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }
    val messages = session?.messages.orEmpty().filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
    val currentById = messages.associateBy { it.id }
    val memoryByFloor = session?.historyMemory?.segments.orEmpty().values.flatMap { segment -> segment.sources.map { source ->
        val current = currentById[source.messageId]?.let { runCatching { HistoryMemory.source(it, source.sourceKind) == source }.getOrDefault(false) } == true
        val sourceLabel = if (source.sourceKind == HistorySourceKind.MODEL_RAW) "模型原文 · " else ""
        source.messageId to (sourceLabel + segment.title.ifBlank { segment.id.take(12) } + if (current) "" else "（旧版本）")
    } }.groupBy({ it.first }, { it.second })
    val choices = messages.map { message ->
        val marker = memoryByFloor[message.id].orEmpty().distinct().takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · 记忆：") ?: ""
        message.id to "${roleTitle(message.role)} · ${message.text.replace('\n', ' ').take(32)}$marker · ${message.id.take(8)}"
    }
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

}

@Composable
internal fun HistoryMemoryInspector(session: Session?) {
    val state = session?.historyMemory
    EditorSection("记忆与历史副本", if (session == null) "尚未选择聊天" else "${state?.segments?.size ?: 0} 段记忆 · ${state?.views?.size ?: 0} 个副本") {
        if (session == null) {
            Text("选择一段聊天后，可查看摘要与原楼层的对应关系。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@EditorSection
        }
        val original = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
        val floorNumbers = original.withIndex().associate { it.value.id to it.index + 1 }
        val segments = state?.segments.orEmpty().values.toList()
        if (segments.isEmpty()) Text("尚无来源记忆；历史处理或记忆写入动作成功后会显示来源映射。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        segments.forEach { segment ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(segment.title.ifBlank { "记忆段 ${segment.id.take(8)}" }, style = MaterialTheme.typography.titleSmall)
                Text(segment.summary, style = MaterialTheme.typography.bodyMedium)
                val sourceKinds = segment.sources.map { it.sourceKind }.distinct()
                val sourceLabel = sourceKinds.joinToString("、") {
                    if (it == HistorySourceKind.MODEL_RAW) "模型初始原文" else "最终展示正文"
                }.ifBlank { "无来源楼层" }
                Text("来源：$sourceLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (segment.childSegmentIds.isNotEmpty()) Text(
                    "子记忆（${segment.childSegmentIds.size}）： " + segment.childSegmentIds.joinToString("、") { childId ->
                        val child = state?.segments?.get(childId)
                        "${child?.title?.ifBlank { "未命名记忆" } ?: "未找到记忆"} · $childId"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("来源楼层：" + segment.sources.joinToString("、") { source ->
                    floorNumbers[source.messageId]?.let { floor ->
                        "第 ${floor} 层" + if (original.firstOrNull { it.id == source.messageId }?.let { runCatching { HistoryMemory.source(it, source.sourceKind) == source }.getOrDefault(false) } == true) "" else "（旧版本）"
                    } ?: "已不在当前聊天 · ${source.messageId.take(8)}"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("记忆标识：${segment.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        Text("历史副本", style = MaterialTheme.typography.titleSmall)
        if (state?.views.isNullOrEmpty()) Text("尚无副本；在调用中使用“复制历史视图”动作创建。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state?.views.orEmpty().forEach { (id, revisions) ->
            revisions.lastOrNull()?.let { latest ->
                val captured = state?.let { HistoryMemory.captureSources(it, latest.captureId) }.orEmpty()
                val source = if (latest.sourceKind == HistorySourceKind.MODEL_RAW) ".source:model" else ""
                Text("${latest.name} · $id · 第 ${latest.revision} 版 · 投影规则 v${latest.projectionVersion} · ${captured.size} 层 · ${if (source.isNotEmpty()) "模型初始原文" else "最终展示正文"}", style = MaterialTheme.typography.bodyMedium)
                Text("{{type:builtin.in:history.name:context$source.view:$id}}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text("楼层上的记忆标记由来源映射生成；主调用的历史配置可管理该楼层的角色与包装。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
