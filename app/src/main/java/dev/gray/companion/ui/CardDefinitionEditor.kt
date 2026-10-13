package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

internal fun defaultCardDefinition() = XmlCardData.defaultDefinition()

/** Both independent resources and card.define share this data/presentation editor. */
@Composable
internal fun CardDefinitionEditor(node: WorkflowNode, references: List<PromptReferenceChoice>, independent: Boolean = false, onChange: (WorkflowNode) -> Unit) {
    val definition = node.cardDefinition ?: defaultCardDefinition()
    fun update(value: CardDefinition) = onChange(node.copy(cardDefinition = value))
    var selectedIndex by remember(node.id) { mutableStateOf<Int?>(null) }
    var section by remember(node.id) { mutableStateOf(0) }
    var previewSource by remember(node.id) { mutableStateOf<String?>(null) }
    var xmlMessage by remember(node.id) { mutableStateOf<String?>(null) }
    var xmlPending by remember(node.id) { mutableStateOf(false) }
    val isXml = definition.dataMode == CardDataMode.XML
    val xmlSchemaProblem = remember(definition) {
        if (isXml) XmlCardData.schemaProblem(definition) else null
    }
    val focus = LocalFocusManager.current
    val index = selectedIndex?.takeIf { it in definition.fields.indices }
    val fieldHeading = remember { BringIntoViewRequester() }
    LaunchedEffect(index) { if (index != null) fieldHeading.bringIntoView() }
    BackHandler(enabled = index != null) { focus.clearFocus(); selectedIndex = null }
    if (index == null) {
        EditorSectionTabs(listOf("数据", "外观", "放置", "预览"), section) { focus.clearFocus(); section = it }
        if (section == 0) {
            PromptTemplateField("卡片来源", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
            if (!independent) TextProcessingEditor("来源处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
            ChoiceLine("解析方式", definition.dataMode, listOf(CardDataMode.XML to "XML 自动生成", CardDataMode.MANUAL to "手动字段 / 正则")) { mode ->
                xmlMessage = null
                xmlPending = mode == CardDataMode.XML
                update(definition.copy(dataMode = mode))
            }
            if (isXml) {
                TextFieldLine("XML 根标签", definition.xmlRoot, {
                    xmlPending = true; xmlMessage = null; update(definition.copy(xmlRoot = it))
                })
                OutlinedTextField(definition.xmlSample, {
                    xmlPending = true; xmlMessage = null; update(definition.copy(xmlSample = it))
                }, label = { Text("标准 XML 示例") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                Button(onClick = {
                    runCatching { XmlCardData.infer(definition) }.onSuccess {
                        update(it); xmlPending = false; xmlMessage = null; previewSource = null
                    }.onFailure { xmlMessage = it.message ?: "XML 示例无法生成字段" }
                }) { Text("生成 / 更新字段与显示元素") }
                if (xmlPending) Text("示例或解析方式已修改，请生成 / 更新后保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                (xmlMessage ?: xmlSchemaProblem)?.let { CardFieldIssue(it) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("字段 · ${definition.fields.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!isXml) TextButton(onClick = {
                    val nextKey = generateSequence(1) { it + 1 }.map { "field_$it" }.first { candidate -> definition.fields.none { it.key == candidate } }
                    update(definition.copy(fields = definition.fields + CardFieldDefinition(key = nextKey, title = "新字段", kind = CardFieldKind.TEXT)))
                    focus.clearFocus(); selectedIndex = definition.fields.size
                }, enabled = definition.fields.size < 32) { Text("添加字段") }
            }
            definition.fields.forEachIndexed { fieldIndex, field ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    ListItem(
                        headlineContent = { Text(field.title.ifBlank { "字段 ${fieldIndex + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column {
                                Text("${field.key} · ${cardFieldKindLabel(field.kind)}")
                                if (isXml) Text(definition.elements.filter { it.fieldKey == field.key }.joinToString(" · ") { "#${it.id}" }.ifBlank { "暂无显示元素，可在外观中添加" })
                            }
                        },
                        trailingContent = { Text("编辑", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { focus.clearFocus(); selectedIndex = fieldIndex },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    )
                }
            }
            if (definition.fields.isEmpty()) CardFieldIssue("至少需要一个字段。")
        }
        if (section == 1) {
            CardPresentationEditor(definition, ::update, placement = false)
        }
        if (section == 3) {
            if (isXml && (xmlPending || xmlSchemaProblem != null)) Text("标准示例尚未重新生成；当前预览仍使用已有字段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            CardLocalPreview(definition, previewSource ?: if (isXml) definition.xmlSample else "") { previewSource = it }
        }
        if (section == 2) {
            CardPresentationEditor(definition, ::update, placement = true)
            ChoiceLine("显示品类", definition.presentation, listOf(CardPresentation.CARD to "卡片", CardPresentation.SIDE_PANEL to "侧栏面板", CardPresentation.STRIP to "紧凑条")) { presentation ->
                update(definition.copy(presentation = presentation, defaultRegion = definition.defaultRegion?.let { if (it in CardDisplays.allowedRegions(presentation)) it else CardDisplays.allowedRegions(presentation).first() }))
            }
            CardIntegerField("适配宽度参考（dp）", definition.minimumWidthDp, 120..1200) { update(definition.copy(minimumWidthDp = it)) }
        }
    } else key(node.id, index) {
        val field = definition.fields[index]
        fun change(value: CardFieldDefinition) = update(definition.copy(fields = definition.fields.mapIndexed { i, old -> if (i == index) value else old }))
        Row(Modifier.fillMaxWidth().bringIntoViewRequester(fieldHeading), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { focus.clearFocus(); selectedIndex = null }) { Text("返回字段列表") }
            Text(field.title.ifBlank { "字段 ${index + 1}" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!isXml) TextButton(onClick = {
                focus.clearFocus()
                update(definition.copy(fields = definition.fields.filterIndexed { i, _ -> i != index }, elements = definition.elements.filterNot { it.fieldKey == field.key }))
                selectedIndex = null
            }, enabled = definition.fields.size > 1) { Text("删除") }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("字段显示", style = MaterialTheme.typography.titleSmall)
                if (isXml) {
                    Text("XML 路径：${field.xmlPath}", style = MaterialTheme.typography.bodySmall)
                    Text("字段标识：${field.key}", style = MaterialTheme.typography.bodySmall)
                } else TextFieldLine("字段标识", field.key, { newKey ->
                    update(definition.copy(fields = definition.fields.mapIndexed { i, old -> if (i == index) field.copy(key = newKey) else old },
                        elements = definition.elements.map { if (it.fieldKey == field.key) it.copy(fieldKey = newKey) else it }))
                })
                if (!isXml && !XmlCardData.validPath(field.key)) CardFieldIssue("标识可用点分层；每段以字母或 _ 开头，只用字母、数字、_ 或 -，每段最多 64 字，总长最多 256 字。")
                if (definition.fields.count { it.key == field.key } > 1) CardFieldIssue("字段标识重复。")
                TextFieldLine("字段名称", field.title, { change(field.copy(title = it)) })
                if (field.title.isBlank() || field.title.length > 128) CardFieldIssue("字段名称需要 1–128 个字符。")
                if (isXml && field.kind == CardFieldKind.LIST) Text("呈现方式：列表（由重复路径生成）", style = MaterialTheme.typography.bodySmall)
                else ChoiceLine("呈现方式", field.kind, CardFieldKind.entries.filter { !isXml || it != CardFieldKind.LIST }.map { it to cardFieldKindLabel(it) }) { change(field.copy(kind = it)) }
                when (field.kind) {
                    CardFieldKind.PROGRESS -> {
                        CardDecimalField("最小值", field.minimum) { change(field.copy(minimum = it)) }
                        CardDecimalField("最大值", field.maximum) { change(field.copy(maximum = it)) }
                        if (field.maximum <= field.minimum) CardFieldIssue("最大值需大于最小值。")
                    }
                    CardFieldKind.LIST -> CardIntegerField("紧凑时显示条数", field.compactLimit, 1..32) { change(field.copy(compactLimit = it)) }
                    else -> Unit
                }
            }
        }
        if (!isXml) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("取值来源", style = MaterialTheme.typography.titleSmall)
                PromptTemplateField("宏来源（留空使用默认来源）", field.source, references, { change(field.copy(source = it)) }, minLines = 2)
                TextFieldLine("提取正则（可选）", field.pattern, { change(field.copy(pattern = it)) })
                if (field.pattern.isNotBlank()) {
                    CardIntegerField("捕获组 · 0 为完整匹配", field.captureGroup, 0..Int.MAX_VALUE) { change(field.copy(captureGroup = it)) }
                    SafePatterns.validate(field.pattern, field.captureGroup)?.let { CardFieldIssue(it) }
                }
                if (field.kind == CardFieldKind.LIST) Text("按每次正则匹配组成列表；留空时把整段来源作为一项。逐项显示请配置提取正则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardFieldIssue(message: String) { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

@Composable
private fun CardIntegerField(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(draft, { draft = it; it.toIntOrNull()?.takeIf { number -> number in range }?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = draft.toIntOrNull()?.let { it in range } != true)
    if (draft.toIntOrNull()?.let { it in range } != true) CardFieldIssue("请输入 ${range.first}–${range.last} 的整数。")
}

@Composable
private fun CardDecimalField(label: String, value: Double, onChange: (Double) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(draft, { draft = it; it.toDoubleOrNull()?.takeIf { number -> number.isFinite() }?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = draft.toDoubleOrNull()?.isFinite() != true)
}

private fun cardFieldKindLabel(kind: CardFieldKind) = when (kind) {
    CardFieldKind.TEXT -> "文本"
    CardFieldKind.NUMBER -> "数值"
    CardFieldKind.PROGRESS -> "进度"
    CardFieldKind.LIST -> "列表"
}
