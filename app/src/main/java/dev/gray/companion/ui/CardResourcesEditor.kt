package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

/** Resource drafts are owned by the main/group editor's existing save transaction. */
@Composable
internal fun CardResourcesEditor(cards: List<CardResource>, references: List<PromptReferenceChoice>, onChange: (List<CardResource>) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val card = cards.firstOrNull { it.id == selected }
    BackHandler(enabled = card != null) { selected = null }
    if (card == null) {
        cards.forEach { value ->
            EditorDestinationRow(value.definition.title, "${value.definition.fields.size} 个字段 · ${value.definition.elements.size} 个显示元素${if (value.enabled) "" else " · 已停用"}") { selected = value.id }
        }
        OutlinedButton(onClick = {
            val value = CardResource(definition = defaultCardDefinition().copy(defaultRegion = CardRegion.CHAT))
            onChange(cards + value); selected = value.id
        }) { Text("添加卡片") }
    } else {
        fun update(value: CardResource) = onChange(cards.map { if (it.id == value.id) value else it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { selected = null }) { Text("返回卡片列表") }
            TextButton(onClick = { onChange(cards.filterNot { it.id == card.id }); selected = null }) { Text("删除卡片") }
        }
        Row { Text("启用卡片", Modifier.weight(1f)); Switch(card.enabled, { update(card.copy(enabled = it)) }) }
        CardDefinitionEditor(WorkflowNode(id = card.id, inputTemplate = card.sourceTemplate, cardDefinition = card.definition), references, independent = true) { changed ->
            update(card.copy(sourceTemplate = changed.inputTemplate, definition = changed.cardDefinition ?: card.definition))
        }
    }
}

@Composable
internal fun CardPresentationEditor(definition: CardDefinition, update: (CardDefinition) -> Unit, placement: Boolean) {
    if (!placement) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("显示元素与 CSS", style = MaterialTheme.typography.titleSmall)
        TextFieldLine("卡片标题", definition.title, { update(definition.copy(title = it)) })
        CardSectionsEditor(definition, update)
        var selectedElement by remember { mutableStateOf<Int?>(null) }
        val selectedIndex = selectedElement?.takeIf { it in definition.elements.indices }
        BackHandler(enabled = selectedIndex != null) { selectedElement = null }
        if (selectedIndex == null) {
            definition.elements.forEachIndexed { index, element ->
                EditorDestinationRow(element.title.ifBlank { definition.fields.firstOrNull { it.key == element.fieldKey }?.title ?: element.fieldKey },
                    "#${element.id} → ${element.fieldKey}") { selectedElement = index }
            }
            OutlinedButton(enabled = definition.fields.isNotEmpty() && definition.elements.size < 128, onClick = {
                val id = generateSequence(1) { it + 1 }.map { "element_$it" }.first { candidate -> definition.elements.none { it.id == candidate } && definition.sections.none { it.id == candidate } }
                update(definition.copy(elements = definition.elements + CardElement(id = id, fieldKey = definition.fields.first().key)))
                selectedElement = definition.elements.size
            }) { Text("添加显示元素") }
        } else {
            val element = definition.elements[selectedIndex]
            fun change(value: CardElement) = update(definition.copy(elements = definition.elements.mapIndexed { i, old -> if (i == selectedIndex) value else old }))
            TextButton(onClick = { selectedElement = null }) { Text("返回元素列表") }
            TextFieldLine("元素标识（CSS 选择器）", element.id, { change(element.copy(id = it)) })
            if (!Regex("[A-Za-z_][A-Za-z0-9_-]{0,127}").matches(element.id) || definition.elements.count { it.id == element.id } > 1)
                Text("标识须唯一，以字母或下划线开头，只用字母、数字、下划线或连字符。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            ChoiceLine("引用字段", element.fieldKey, definition.fields.map { it.key to "${it.title} · ${it.key}" } +
                if (definition.fields.none { it.key == element.fieldKey }) listOf(element.fieldKey to "字段已移除：${element.fieldKey}") else emptyList()) { change(element.copy(fieldKey = it)) }
            TextFieldLine("显示标题（留空沿用字段名）", element.title, { change(element.copy(title = it)) })
            ChoiceLine("所属分区", element.sectionId, listOf("" to "始终显示") + definition.sections.map { it.id to it.title } +
                if (element.sectionId.isNotEmpty() && definition.sections.none { it.id == element.sectionId })
                    listOf(element.sectionId to "分区已移除：${element.sectionId}") else emptyList()) { change(element.copy(sectionId = it)) }
            Row {
                TextButton(enabled = selectedIndex > 0, onClick = {
                    val elements = definition.elements.toMutableList(); elements.add(selectedIndex - 1, elements.removeAt(selectedIndex))
                    update(definition.copy(elements = elements)); selectedElement = selectedIndex - 1
                }) { Text("上移") }
                TextButton(enabled = selectedIndex < definition.elements.lastIndex, onClick = {
                    val elements = definition.elements.toMutableList(); elements.add(selectedIndex + 1, elements.removeAt(selectedIndex))
                    update(definition.copy(elements = elements)); selectedElement = selectedIndex + 1
                }) { Text("下移") }
                TextButton(onClick = { update(definition.copy(elements = definition.elements.filterIndexed { i, _ -> i != selectedIndex })); selectedElement = null }) { Text("删除元素") }
            }
        }
        HorizontalDivider()
        OutlinedTextField(definition.css, { update(definition.copy(css = it)) }, label = { Text("CSS 样式") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
        CardMarkup.validate(definition.css)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (placement) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("位置与可见性", style = MaterialTheme.typography.titleSmall)
        val allowed = CardDisplays.allowedRegions(definition.presentation)
        ChoiceLine<CardRegion?>("默认位置", definition.defaultRegion, listOf(null to "未放置") + allowed.map { it to when (it) { CardRegion.CHAT -> "正文下方"; CardRegion.SIDEBAR -> "侧栏"; CardRegion.INPUT -> "输入框上方"; CardRegion.MENU -> "顶部菜单" } }) { update(definition.copy(defaultRegion = it)) }
        Row { Text("允许使用者调整布局", Modifier.weight(1f)); Switch(definition.userAdjustable, { update(definition.copy(userAdjustable = it)) }) }
        Row { Text("自动适配屏幕", Modifier.weight(1f)); Switch(definition.autoAdapt, { update(definition.copy(autoAdapt = it)) }) }
        ChoiceLine("正文楼层", definition.floorScope, listOf(CardFloorScope.LATEST to "仅最新正文", CardFloorScope.EACH to "每层正文")) { update(definition.copy(floorScope = it)) }
        Row { Text("无内容时隐藏", Modifier.weight(1f)); Switch(definition.hideWhenEmpty, { update(definition.copy(hideWhenEmpty = it)) }) }
        ConditionForm(definition.condition) { update(definition.copy(condition = it)) }
    }
}

@Composable
private fun CardSectionsEditor(definition: CardDefinition, update: (CardDefinition) -> Unit) {
    var selected by remember { mutableStateOf<Int?>(null) }
    val index = selected?.takeIf { it in definition.sections.indices }
    BackHandler(enabled = index != null) { selected = null }
    Text("折叠分区", style = MaterialTheme.typography.titleSmall)
    Text("未分配分区的元素始终显示；分区按下方顺序排列在其后。CSS 可使用 .card-section、.card-section-title、.card-section-content 和 #分区标识。", style = MaterialTheme.typography.bodySmall)
    if (index == null) {
        definition.sections.forEachIndexed { i, section ->
            EditorDestinationRow(section.title, "#${section.id} · ${if (section.initiallyExpanded) "默认展开" else "默认收起"}") { selected = i }
        }
        OutlinedButton(enabled = definition.sections.size < 32, onClick = {
            val used = definition.sections.map { it.id } + definition.elements.map { it.id } + definition.fields.map { it.key }
            val id = generateSequence(1) { it + 1 }.map { "section_$it" }.first { it !in used }
            update(definition.copy(sections = definition.sections + CardSection(id, "详细信息")))
            selected = definition.sections.size
        }) { Text("添加折叠分区") }
    } else {
        val section = definition.sections[index]
        fun change(value: CardSection) = update(definition.copy(
            sections = definition.sections.mapIndexed { i, old -> if (i == index) value else old },
            elements = definition.elements.map { if (it.sectionId == section.id) it.copy(sectionId = value.id) else it }))
        TextButton(onClick = { selected = null }) { Text("返回分区列表") }
        TextFieldLine("分区标识（CSS 选择器）", section.id, { change(section.copy(id = it)) })
        if (!Regex("[A-Za-z_][A-Za-z0-9_-]{0,127}").matches(section.id) ||
            definition.sections.count { it.id == section.id } > 1 || definition.elements.any { it.id == section.id })
            Text("分区标识须唯一，且不能与显示元素重复。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextFieldLine("分区标题", section.title, { change(section.copy(title = it)) })
        Row { Text("默认展开", Modifier.weight(1f)); Switch(section.initiallyExpanded, { change(section.copy(initiallyExpanded = it)) }) }
        Row {
            TextButton(enabled = index > 0, onClick = {
                val sections = definition.sections.toMutableList(); sections.add(index - 1, sections.removeAt(index))
                update(definition.copy(sections = sections)); selected = index - 1
            }) { Text("上移") }
            TextButton(enabled = index < definition.sections.lastIndex, onClick = {
                val sections = definition.sections.toMutableList(); sections.add(index + 1, sections.removeAt(index))
                update(definition.copy(sections = sections)); selected = index + 1
            }) { Text("下移") }
            TextButton(onClick = {
                update(definition.copy(sections = definition.sections.filterIndexed { i, _ -> i != index },
                    elements = definition.elements.map { if (it.sectionId == section.id) it.copy(sectionId = "") else it }))
                selected = null
            }) { Text("删除分区") }
        }
        Text("删除分区会将其中元素改为始终显示。", style = MaterialTheme.typography.bodySmall)
    }
}

/** Local sample text uses the production extractor; never writes runtime values or invokes a model. */
@Composable
internal fun CardLocalPreview(definition: CardDefinition, source: String, onSourceChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("本地预览", style = MaterialTheme.typography.titleSmall)
        if (definition.dataMode == CardDataMode.XML) TextButton(onClick = { onSourceChange(definition.xmlSample) }) { Text("使用标准 XML 示例") }
        OutlinedTextField(source, onSourceChange, label = { Text("示例来源文本") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Text(if (definition.dataMode == CardDataMode.XML) "按标准示例生成的路径提取完整 XML，忽略额外字段；预览不读取聊天状态。"
            else "示例文本替代默认来源；单独指定来源的字段也使用这份示例，预览不读取聊天状态。", style = MaterialTheme.typography.bodySmall)
        val result = remember(source, definition) { runCatching {
            val fields = CardDisplays.extractFields(definition, source) { source }
            CardMarkup.render(CardSnapshot(formatVersion = 3, nodeId = "preview", title = definition.title,
                fields = fields, css = definition.css, elements = definition.elements, sections = definition.sections,
                presentation = definition.presentation, autoAdapt = definition.autoAdapt, minimumWidthDp = definition.minimumWidthDp))
        } }
        result.getOrNull()?.let { InertHtmlView(it, MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.surfaceContainerLow, 14, "card-preview") }
        result.exceptionOrNull()?.let { Text(it.message ?: "示例无法提取", color = MaterialTheme.colorScheme.error) }
    }
}
