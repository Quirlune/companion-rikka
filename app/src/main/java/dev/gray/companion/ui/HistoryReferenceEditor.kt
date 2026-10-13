package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

internal val historySourceRoleChoices = listOf<Pair<Role?, String>>(
    null to "全部原始发言方", Role.USER to "原始用户（User）", Role.ASSISTANT to "原始助手（Assistant）", Role.SYSTEM to "原始系统（System）")

/** Changes one History token in a prompt. This form never writes chat floors or main-call history settings. */
@Composable
internal fun HistoryReferenceEditor(
    initial: HistorySelection,
    references: List<PromptReferenceChoice>,
    soleStructuredBlock: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selection by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val views = references.mapNotNull { choice ->
        runCatching { HistoryViews.selection(choice.macro) }.getOrNull()?.takeIf { it.viewId != null && it.sourceKind == selection.sourceKind }
            ?.viewId?.let { it to choice.title.substringBefore(" · 第") }
    }.distinctBy { it.first }
    val selectedView = selection.viewId
    val viewOptions = listOf<Pair<String?, String>>(null to "当前历史") + views.map { (id, title) -> (id as String?) to title } +
        if (selectedView != null && views.none { it.first == selectedView }) listOf(selectedView to "未找到副本：$selectedView") else emptyList()
    val hasRange = selection.rangeExplicit || selection.range != HistoryRange()
    val textMode = selection.readMode == HistoryReadMode.TEXT

    // Keep the sheet's measured height stable while moving between partial and full expansion.
    // Material3 consumes the top inset according to the sheet offset; placing that inset on the
    // sheet itself changes the expanded anchor as it reaches the top of the window.
    val topSafePadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
            .imePadding().padding(start = 16.dp, end = 16.dp, top = topSafePadding + 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("历史上下文", style = MaterialTheme.typography.titleLarge)
            ChoiceLine("读取内容", selection.compressed, listOf(false to "原始楼层", true to "历史规则与摘要处理后")) {
                selection = selection.copy(compressed = it)
            }
            ChoiceLine("输出形式", selection.readMode, listOf(
                HistoryReadMode.MESSAGES to "结构化消息", HistoryReadMode.TEXT to "单段纯文本")) {
                selection = selection.copy(readMode = it)
            }
            Text(if (textMode) "文本模式会把所选楼层写成一段带发言者前缀的正文，例如 Assistant: …\nUser: …。这些前缀只是普通文本；整段仍使用外层提示词块的 API 角色。"
                else "结构化模式按消息分别输出，并保留每条消息的 API 角色。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (textMode) {
                Text("发言方文本前缀", style = MaterialTheme.typography.titleSmall)
                val prefixes = selection.textPrefixes
                TextFieldLine("Assistant 前缀（留空：Assistant: ）", prefixes.assistant, {
                    selection = selection.copy(textPrefixes = prefixes.copy(assistant = it))
                })
                TextFieldLine("User 前缀（留空：User: ）", prefixes.user, {
                    selection = selection.copy(textPrefixes = prefixes.copy(user = it))
                })
                TextFieldLine("System 前缀（留空：System: ）", prefixes.system, {
                    selection = selection.copy(textPrefixes = prefixes.copy(system = it))
                })
                Text("按原始发言方替换完整前缀。留空保持原样；自定义时请包含需要的冒号、空格或换行，例如「角色名：」。只影响本引用的纯文本输出。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ChoiceLine("正文来源", selection.sourceKind, listOf(
                HistorySourceKind.DISPLAY to "最终展示正文", HistorySourceKind.MODEL_RAW to "模型初始原文")) {
                selection = selection.copy(sourceKind = it, viewId = null, revision = null)
            }
            if (selection.sourceKind == HistorySourceKind.MODEL_RAW) Text("早期楼层若没有归档模型原文，会报告缺失。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceLine("来源发言方", selection.sourceRole, historySourceRoleChoices) {
                selection = selection.copy(sourceRole = it)
            }
            Text(if (textMode) "按原聊天发言方筛选，再从匹配楼层中选择范围；文本中的发言者标签不会成为独立 API 角色。"
                else "按原聊天发言方筛选，再从匹配楼层中选择范围；下方发送 API 角色只改变本次投影，原楼层与发言者不变。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChoiceLine("历史副本", selectedView, viewOptions) { selection = selection.copy(viewId = it, revision = null) }
            if (selectedView != null) {
                ToggleLine("固定副本版本", selection.revision != null) {
                    selection = selection.copy(revision = if (it) (selection.revision ?: 1) else null)
                }
                selection.revision?.let { revision -> NumberLine("副本版本号", revision, 1) { selection = selection.copy(revision = it) } }
            }
            HorizontalDivider()
            Text(if (selection.sourceRole == null) "楼层范围" else "匹配来源中的楼层范围", style = MaterialTheme.typography.titleMedium)
            if (selection.sourceRole != null) Text("从开头／末尾计算的是筛选后第几条；每楼格式里的 {{floor}} 仍使用原历史楼层编号。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestionChip(onClick = { selection = selection.copy(range = HistoryRange(
                    HistoryPoint(HistoryAnchor.START, 1.0), HistoryPoint(HistoryAnchor.START, 3.0)), rangeExplicit = true) },
                    label = { Text("最早三层") })
                SuggestionChip(onClick = { selection = selection.copy(range = HistoryRange(
                    HistoryPoint(HistoryAnchor.END, 3.0), HistoryPoint(HistoryAnchor.END, 1.0)), rangeExplicit = true) },
                    label = { Text("最近三层") })
                SuggestionChip(onClick = { selection = selection.copy(range = HistoryRange(
                    HistoryPoint(HistoryAnchor.PERCENT, 34.0), HistoryPoint(HistoryAnchor.PERCENT, 66.0)), rangeExplicit = true) },
                    label = { Text("中间区段") })
            }
            ToggleLine("明确指定范围", hasRange) {
                selection = selection.copy(range = if (it) selection.range else HistoryRange(), rangeExplicit = it)
            }
            if (hasRange) {
                HistoryPointForm("范围起点", selection.range.from) { selection = selection.copy(
                    range = selection.range.copy(from = it), rangeExplicit = true) }
                HistoryPointForm("范围终点（包含）", selection.range.through) { selection = selection.copy(
                    range = selection.range.copy(through = it), rangeExplicit = true) }
            } else Text(if (selection.sourceRole != null) "不指定范围时读取全部匹配楼层，不套用主调用的默认范围。" else "不指定时，处理后当前历史沿用主调用的默认范围。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text(if (textMode) "本引用的文本格式" else "本引用的每楼格式", style = MaterialTheme.typography.titleMedium)
            ToggleLine("单独设置本引用", selection.format != null) {
                selection = selection.copy(format = if (it) (selection.format ?: HistoryReferenceFormat()) else null)
            }
            selection.format?.let { format ->
                Text(if (textMode) "独立投影使用这里的逐楼包装；文本模式保留原发言方用于标签、忽略 API 角色映射，且不会继承主调用的整体前后缀、消息合并、指定消息覆盖或历史插入项；楼层范围仍由上方选择。"
                    else "独立投影使用这里的每楼与发言方规则，不再继承主调用的整体前后缀、消息合并、指定消息覆盖或历史插入项；楼层范围仍由上方选择。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (textMode) "每楼前后缀默认留空；发言方标签使用上方前缀映射，留空时保留原样。"
                    else "前后缀默认留空；原始发言者与发给模型的 API 角色分别设置。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!textMode) ChoiceLine("通用发送 API 角色", format.apiRole, listOf<Pair<Role?, String>>(
                    null to "保留各楼原角色", Role.SYSTEM to "系统", Role.USER to "用户", Role.ASSISTANT to "助手")) {
                    selection = selection.copy(format = format.copy(apiRole = it))
                }
                TextFieldLine("每楼前缀", format.floorPrefix, { selection = selection.copy(format = format.copy(floorPrefix = it)) })
                TextButton(onClick = { selection = selection.copy(format = format.copy(floorPrefix = format.floorPrefix + "{{speaker.name}}")) }) {
                    Text("前缀插入原始发言者姓名")
                }
                TextFieldLine("每楼后缀", format.floorSuffix, { selection = selection.copy(format = format.copy(floorSuffix = it)) })
                TextButton(onClick = { selection = selection.copy(format = format.copy(floorSuffix = format.floorSuffix + "{{speaker.name}}")) }) {
                    Text("后缀插入原始发言者姓名")
                }
                HorizontalDivider()
                Text("按原始发言方覆盖", style = MaterialTheme.typography.titleSmall)
                HistoryReferenceSpeakerEditor(Role.SYSTEM, "系统", format, textMode) { selection = selection.copy(format = it) }
                HistoryReferenceSpeakerEditor(Role.USER, "用户", format, textMode) { selection = selection.copy(format = it) }
                HistoryReferenceSpeakerEditor(Role.ASSISTANT, "助手（原始 ASSISTANT）", format, textMode) { selection = selection.copy(format = it) }
                TextButton(onClick = { selection = selection.copy(format = null) }) { Text("移除本引用设置，沿用主调用") }
            }
            Text(when {
                textMode -> "文本模式始终作为一段内容插入；Assistant、User 等前缀只是正文文字，不会拆成多条带角色的 API 消息。"
                soleStructuredBlock -> "作为单独结构化 History 提示词块时，会按每楼 API 角色分别发送。"
                else -> "与其他文字混合时，由外层消息决定发送角色；每楼 API 角色不会分别发送。"
            },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    runCatching { HistoryReferences.expression(selection) }
                        .onSuccess(onSave)
                        .onFailure { error = it.message ?: "历史引用设置无效" }
                }) { Text("保存当前引用") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HistoryReferenceSpeakerEditor(
    speaker: Role, label: String, format: HistoryReferenceFormat, textMode: Boolean, onChange: (HistoryReferenceFormat) -> Unit,
) {
    val rule = format.speakers.firstOrNull { it.speaker == speaker }
    fun update(changed: HistorySpeakerRule) = onChange(format.copy(speakers = format.speakers.map {
        if (it.speaker == speaker) changed else it
    }))
    ToggleLine(if (textMode) "原始$label 楼层包装单独设置" else "原始$label 楼层单独设置", rule != null) { enabled ->
        onChange(format.copy(speakers = if (enabled) format.speakers + HistorySpeakerRule(speaker)
            else format.speakers.filterNot { it.speaker == speaker }))
    }
    if (rule == null) return
    if (!textMode) ChoiceLine("$label 的发送 API 角色", rule.apiRole, listOf<Pair<Role?, String>>(
        null to "继承通用设置", Role.SYSTEM to "系统", Role.USER to "用户", Role.ASSISTANT to "助手")) {
        update(rule.copy(apiRole = it))
    }
    ToggleLine("$label 前缀继承通用设置", rule.prefix == null) { update(rule.copy(prefix = if (it) null else "")) }
    rule.prefix?.let { prefix ->
        TextFieldLine("$label 前缀（可留空）", prefix, { update(rule.copy(prefix = it)) })
        TextButton(onClick = { update(rule.copy(prefix = prefix + "{{speaker.name}}")) }) { Text("前缀插入原始发言者姓名") }
    }
    ToggleLine("$label 后缀继承通用设置", rule.suffix == null) { update(rule.copy(suffix = if (it) null else "")) }
    rule.suffix?.let { suffix ->
        TextFieldLine("$label 后缀（可留空）", suffix, { update(rule.copy(suffix = it)) })
        TextButton(onClick = { update(rule.copy(suffix = suffix + "{{speaker.name}}")) }) { Text("后缀插入原始发言者姓名") }
    }
}
