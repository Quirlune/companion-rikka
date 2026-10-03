package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gray.companion.core.Library
import dev.gray.companion.core.HtmlTemplate
import dev.gray.companion.core.MacroHtmlBinding
import dev.gray.companion.core.MacroHtmlValueKind
import dev.gray.companion.core.MacroDisplay
import dev.gray.companion.core.ResolvedMacroDisplay
import dev.gray.companion.core.MacroTile
import dev.gray.companion.core.MacroTileKind
import dev.gray.companion.core.MacroTilePlacement
import dev.gray.companion.core.MacroStatusField
import dev.gray.companion.core.MacroTileStyle
import dev.gray.companion.core.OutputSlot
import dev.gray.companion.core.SafePatterns
import dev.gray.companion.core.StatusFields
import dev.gray.companion.core.Session
import dev.gray.companion.core.TileCss
import kotlinx.serialization.json.Json

@Composable
internal fun MacroTilesEditor(tiles: List<MacroTile>, references: List<PromptReferenceChoice>, onChange: (List<MacroTile>) -> Unit) {
    Text("显示当前宏资料，不执行动作，也不会进入模型历史。聊天显示在当前正文下方；侧栏可单独打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    tiles.forEachIndexed { index, tile -> key(tile.id) {
        MacroTileForm(tile, references,
            onChange = { changed -> onChange(tiles.toMutableList().apply { set(index, changed) }) },
            onRemove = { onChange(tiles.filterNot { it.id == tile.id }) })
    } }
    OutlinedButton(onClick = { onChange(tiles + MacroTile()) }) { Text("添加宏显示") }
}

@Composable
private fun MacroTileForm(tile: MacroTile, references: List<PromptReferenceChoice>, onChange: (MacroTile) -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextFieldLine("标题", tile.title, { onChange(tile.copy(title = it)) })
        PromptTemplateField(when (tile.kind) {
            MacroTileKind.STATUS -> "状态宏（XML）"
            MacroTileKind.HTML -> "HTML 数据源宏"
            else -> "显示的宏或模板"
        }, tile.template, references, { onChange(tile.copy(template = it)) }, minLines = 1)
        ChoiceLine("显示形式", tile.kind, listOf(MacroTileKind.TEXT to "文本", MacroTileKind.NUMBER to "数值", MacroTileKind.PROGRESS to "进度", MacroTileKind.STATUS to "状态栏", MacroTileKind.HTML to "HTML 模板")) { onChange(tile.copy(kind = it)) }
        ChoiceLine("显示位置", tile.placement, listOf(MacroTilePlacement.CHAT to "聊天正文下方", MacroTilePlacement.SIDEBAR to "独立侧栏")) { onChange(tile.copy(placement = it)) }
        if (tile.placement == MacroTilePlacement.CHAT && tile.kind != MacroTileKind.STATUS && tile.kind != MacroTileKind.HTML) ChoiceLine("聊天面板区域", tile.slot, listOf(
            OutputSlot.BEFORE_BODY to "正文之前", OutputSlot.BODY to "正文区域", OutputSlot.AFTER_BODY to "正文之后"
        )) { onChange(tile.copy(slot = it)) }
        if (tile.kind == MacroTileKind.STATUS) {
            Text("从 XML 中选取标量字段；状态只呈现当前值，不回写聊天消息。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tile.fields.isEmpty()) Text("请添加至少一个状态字段。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            tile.fields.forEachIndexed { fieldIndex, field -> key(fieldIndex) {
                HorizontalDivider()
                TextFieldLine("字段名称", field.title, { changed -> onChange(tile.copy(fields = tile.fields.toMutableList().also { it[fieldIndex] = field.copy(title = changed) })) })
                TextFieldLine("XML 字段路径", field.key, { changed -> onChange(tile.copy(fields = tile.fields.toMutableList().also { it[fieldIndex] = field.copy(key = changed) })) })
                if (field.key.isNotBlank() && !StatusFields.validKey(field.key)) Text("请填写 XML 元素名或用 / 分隔的路径。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ChoiceLine("字段呈现", field.kind, listOf(MacroTileKind.TEXT to "文本", MacroTileKind.NUMBER to "数值", MacroTileKind.PROGRESS to "进度")) { kind ->
                    onChange(tile.copy(fields = tile.fields.toMutableList().also { it[fieldIndex] = field.copy(kind = kind) }))
                }
                if (field.kind == MacroTileKind.PROGRESS) {
                    var minField by remember(tile.id, fieldIndex, field.minimum) { mutableStateOf(field.minimum.toString()) }
                    var maxField by remember(tile.id, fieldIndex, field.maximum) { mutableStateOf(field.maximum.toString()) }
                    OutlinedTextField(minField, { input -> minField = input; input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { minimum -> onChange(tile.copy(fields = tile.fields.toMutableList().also { it[fieldIndex] = field.copy(minimum = minimum) })) } }, label = { Text("进度下限") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(maxField, { input -> maxField = input; input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { maximum -> onChange(tile.copy(fields = tile.fields.toMutableList().also { it[fieldIndex] = field.copy(maximum = maximum) })) } }, label = { Text("进度上限") }, modifier = Modifier.fillMaxWidth())
                    if (minField.toDoubleOrNull()?.isFinite() != true || maxField.toDoubleOrNull()?.isFinite() != true) Text("请输入有效数字；当前输入尚未应用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (field.maximum <= field.minimum) Text("上限须大于下限。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onChange(tile.copy(fields = tile.fields.filterIndexed { i, _ -> i != fieldIndex })) }) { Text("删除字段") }
            } }
            OutlinedButton(onClick = { onChange(tile.copy(fields = tile.fields + MacroStatusField())) }) { Text("添加状态字段") }
        }
        if (tile.kind == MacroTileKind.HTML) {
            Text("数据源先按下方正则提取整块，再用各值的正则提取。HTML/CSS 是静态模板；文字值只可放在元素正文，数值可用于 CSS 或数值属性。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextFieldLine("HTML 正文片段", tile.htmlTemplate, { onChange(tile.copy(htmlTemplate = it)) }, minLines = 6)
            TextFieldLine("CSS 样式", tile.htmlCss, { onChange(tile.copy(htmlCss = it)) }, minLines = 5)
            Text("示例：<span>{{text.place}}</span>；进度宽度：style=\"width:{{number.level}}%\"。不加载外部资源。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            tile.htmlBindings.forEachIndexed { index, binding -> key(index) {
                HorizontalDivider()
                Text("提取值 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                fun update(changed: MacroHtmlBinding) = onChange(tile.copy(htmlBindings = tile.htmlBindings.toMutableList().also { it[index] = changed }))
                TextFieldLine("变量名", binding.name, { update(binding.copy(name = it)) })
                ChoiceLine("值类型", binding.kind, listOf(MacroHtmlValueKind.TEXT to "文字", MacroHtmlValueKind.NUMBER to "数值")) { update(binding.copy(kind = it)) }
                TextFieldLine("提取正则（留空使用整个数据源）", binding.pattern, { update(binding.copy(pattern = it)) })
                var groupDraft by remember(tile.id, index) { mutableStateOf(binding.captureGroup.toString()) }
                OutlinedTextField(groupDraft, { input ->
                    if (input.isEmpty() || (input.all(Char::isDigit) && input.toIntOrNull() != null)) {
                        groupDraft = input
                        update(binding.copy(captureGroup = input.toIntOrNull() ?: 0))
                    }
                }, label = { Text("捕获组（0 为完整匹配）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                SafePatterns.validate(binding.pattern, binding.captureGroup)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onChange(tile.copy(htmlBindings = tile.htmlBindings.filterIndexed { i, _ -> i != index })) }) { Text("删除值") }
            } }
            OutlinedButton(onClick = {
                val next = generateSequence(1) { it + 1 }.map { "value_$it" }.first { name -> tile.htmlBindings.none { it.name == name } }
                onChange(tile.copy(htmlBindings = tile.htmlBindings + MacroHtmlBinding(name = next)))
            }) { Text("添加提取值") }
            HtmlTemplate.validate(tile).take(3).forEach { issue -> Text(issue.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (tile.kind == MacroTileKind.PROGRESS) {
            var minDraft by remember(tile.id) { mutableStateOf(tile.minimum.toString()) }
            var maxDraft by remember(tile.id) { mutableStateOf(tile.maximum.toString()) }
            OutlinedTextField(minDraft, { input -> minDraft = input; input.toDoubleOrNull()?.let { onChange(tile.copy(minimum = it)) } }, label = { Text("进度下限") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(maxDraft, { input -> maxDraft = input; input.toDoubleOrNull()?.let { onChange(tile.copy(maximum = it)) } }, label = { Text("进度上限") }, modifier = Modifier.fillMaxWidth())
            if (tile.maximum <= tile.minimum) Text("上限须大于下限。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        ToggleLine("启用磁贴", tile.enabled) { onChange(tile.copy(enabled = it)) }
        ToggleLine("值为空时隐藏", tile.hideWhenEmpty) { onChange(tile.copy(hideWhenEmpty = it)) }
        var advanced by remember(tile.id) { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起提取与样式" else "提取与样式（可选）") }
        if (advanced) {
            TextFieldLine("正则提取", tile.pattern, { onChange(tile.copy(pattern = it)) })
            var groupDraft by remember(tile.id) { mutableStateOf(tile.captureGroup.toString()) }
            OutlinedTextField(groupDraft, { input ->
                if (input.isEmpty() || (input.all(Char::isDigit) && input.toIntOrNull() != null)) {
                    groupDraft = input
                    onChange(tile.copy(captureGroup = input.toIntOrNull() ?: 0))
                }
            }, label = { Text("捕获组（0 为完整匹配）") }, modifier = Modifier.fillMaxWidth())
            SafePatterns.validate(tile.pattern, tile.captureGroup)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            TextFieldLine("文字颜色（#RRGGBB 或 #AARRGGBB）", tile.style.foreground, { onChange(tile.copy(style = tile.style.copy(foreground = it))) })
            if (!validTileColor(tile.style.foreground)) Text("文字颜色格式无效。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextFieldLine("背景颜色（#RRGGBB 或 #AARRGGBB）", tile.style.background, { onChange(tile.copy(style = tile.style.copy(background = it))) })
            if (!validTileColor(tile.style.background)) Text("背景颜色格式无效。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("字号 ${tile.style.fontSize} sp", style = MaterialTheme.typography.bodySmall)
            Slider(value = tile.style.fontSize.toFloat().coerceIn(8f, 32f), onValueChange = { onChange(tile.copy(style = tile.style.copy(fontSize = it.toInt()))) }, valueRange = 8f..32f, steps = 23)
            Text("圆角 ${tile.style.cornerRadius} dp", style = MaterialTheme.typography.bodySmall)
            Slider(value = tile.style.cornerRadius.toFloat().coerceIn(0f, 32f), onValueChange = { onChange(tile.copy(style = tile.style.copy(cornerRadius = it.toInt()))) }, valueRange = 0f..32f, steps = 31)
            if (tile.kind == MacroTileKind.STATUS || tile.kind == MacroTileKind.HTML) Text("状态栏和 HTML 模板始终使用整行宽度。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else ChoiceLine("磁贴宽度", tile.style.widthPercent, listOf(50 to "半行", 100 to "整行")) { onChange(tile.copy(style = tile.style.copy(widthPercent = it))) }
            if (tile.kind == MacroTileKind.STATUS || tile.kind == MacroTileKind.HTML) Text("状态栏与 HTML 模板的外层容器可用上方颜色、字号与圆角设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                StyleCssEditor(tile.style) { onChange(tile.copy(style = it)) }
                StyleJsonEditor(tile.style) { onChange(tile.copy(style = it)) }
            }
        }
        TextButton(onClick = onRemove) { Text("删除磁贴") }
    } }
}

@Composable
private fun StyleCssEditor(style: MacroTileStyle, onApply: (MacroTileStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { draft = TileCss.format(style); error = null; open = true }) { Text("CSS 样式") }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text("磁贴样式") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("支持 color、background-color、font-size、border-radius、width。宽度为 50% 或 100%，原生网格自动排列；字体随系统字号缩放。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft, { draft = it; error = null }, label = { Text("CSS 声明") }, modifier = Modifier.fillMaxWidth(), minLines = 5)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            try { onApply(TileCss.parse(draft, style)); open = false }
            catch (invalid: IllegalArgumentException) { error = invalid.message }
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
    )
}

@Composable
private fun StyleJsonEditor(style: MacroTileStyle, onApply: (MacroTileStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { draft = Json.encodeToString(MacroTileStyle.serializer(), style); error = null; open = true }) { Text("JSON 样式（高级）") }
    if (open) AlertDialog(
        onDismissRequest = { open = false }, title = { Text("编辑磁贴样式") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it; error = null }, label = { Text("MacroTileStyle JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val parsed = runCatching { Json.decodeFromString(MacroTileStyle.serializer(), draft) }.getOrNull()
            error = when {
                parsed == null -> "JSON 格式或字段无效。"
                !validTileColor(parsed.foreground) || !validTileColor(parsed.background) -> "颜色须为 #RRGGBB 或 #AARRGGBB。"
                parsed.fontSize !in 8..72 || parsed.cornerRadius !in 0..64 || parsed.widthPercent !in listOf(50, 100) -> "字号须为 8–72，圆角须为 0–64，宽度须为 50 或 100。"
                else -> null
            }
            if (error == null && parsed != null) { onApply(parsed); open = false }
        }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }
    )
}

private val TILE_COLOR = Regex("^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")
private fun validTileColor(value: String): Boolean = value.isEmpty() || TILE_COLOR.matches(value)
internal fun tileColor(value: String): Color? = if (validTileColor(value) && value.isNotEmpty()) Color(android.graphics.Color.parseColor(value)) else null

internal typealias ResolvedTile = ResolvedMacroDisplay

internal fun configuredMacroTiles(library: Library, session: Session): List<MacroTile> = MacroDisplay.configuredTiles(library, session)

internal fun hasMacroSidebar(library: Library, session: Session): Boolean =
    configuredMacroTiles(library, session).any { it.enabled && it.placement == MacroTilePlacement.SIDEBAR }

internal fun resolveMacroTiles(library: Library, session: Session, placement: MacroTilePlacement): List<ResolvedTile> {
    val bodySourceId = library.mainCalls.firstOrNull { it.id == session.mainCallId }?.bodySourceTileId
    return MacroDisplay.resolve(library, session, placement).filterNot { it.tile.id == bodySourceId }
}

/** Current dashboard only. Historical message regions are not rebound to later macro values. */
@Composable
internal fun MacroTileDashboard(library: Library, session: Session) {
    val resolved = remember(library, session) { resolveMacroTiles(library, session, MacroTilePlacement.CHAT) }
    if (resolved.isEmpty()) return
    val statuses = resolved.filter { it.tile.kind == MacroTileKind.STATUS || it.tile.kind == MacroTileKind.HTML }
    val bodyIndex = session.messages.indexOfLast { !it.deleted && it.role == dev.gray.companion.core.Role.ASSISTANT && it.outputSlot == OutputSlot.BODY }
    val userIndex = session.messages.indexOfLast { !it.deleted && it.role == dev.gray.companion.core.Role.USER }
    val showStatus = bodyIndex >= 0 && bodyIndex > userIndex
    if (showStatus && statuses.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        statuses.forEach { item -> key(item.tile.id) {
            if (item.tile.kind == MacroTileKind.HTML) HtmlMacroDisplay(item) else MacroStatusPanel(item.tile, item.value, item.error, item.waiting)
        } }
    }
    val legacyTiles = resolved.filter { it.tile.kind != MacroTileKind.STATUS && it.tile.kind != MacroTileKind.HTML }
    if (legacyTiles.isEmpty()) return
    var expanded by remember(session.id) { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起当前磁贴" else "查看当前磁贴（${legacyTiles.size}）") }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(OutputSlot.BEFORE_BODY, OutputSlot.BODY, OutputSlot.AFTER_BODY).forEach { slot ->
                val section = legacyTiles.filter { it.tile.slot == slot }
                if (section.isNotEmpty()) {
                    Text(when (slot) { OutputSlot.BEFORE_BODY -> "正文之前"; OutputSlot.BODY -> "正文区域"; OutputSlot.AFTER_BODY -> "正文之后" }, style = MaterialTheme.typography.labelMedium)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val twoColumns = maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.5f
                        val halfWidth = (maxWidth - 8.dp) / 2
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            section.forEach { item -> key(item.tile.id) {
                                Box(if (twoColumns && item.tile.style.widthPercent == 50) Modifier.width(halfWidth) else Modifier.fillMaxWidth()) { CurrentTile(item) }
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CurrentTile(item: ResolvedTile) {
    val tile = item.tile
    val error = item.error
    val style = tile.style
    val fg = tileColor(style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val bg = tileColor(style.background) ?: MaterialTheme.colorScheme.surfaceContainer
    OutlinedCard(shape = RoundedCornerShape(style.cornerRadius.dp), colors = CardDefaults.outlinedCardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tile.title, color = fg, style = MaterialTheme.typography.labelMedium)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else when (tile.kind) {
                MacroTileKind.TEXT -> SelectionContainer { Text(item.value, color = fg, fontSize = style.fontSize.sp) }
                MacroTileKind.NUMBER -> {
                    val number = item.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                    Text(if (number != null) item.value else "无有效数字", color = fg, fontSize = style.fontSize.sp)
                }
                MacroTileKind.PROGRESS -> {
                    val number = item.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                    val fraction = number?.takeIf { tile.maximum > tile.minimum }
                        ?.let { ((it - tile.minimum) / (tile.maximum - tile.minimum)).takeIf { ratio -> ratio.isFinite() } }
                    if (fraction == null) Text("无有效进度数值", color = fg, style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(item.value, color = fg, fontSize = style.fontSize.sp)
                        LinearProgressIndicator(progress = { fraction.coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                MacroTileKind.STATUS -> MacroStatusPanel(tile, item.value, item.error, item.waiting)
                MacroTileKind.HTML -> HtmlMacroDisplay(item)
            }
        }
    }
}
