package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.HistorySelection
import dev.gray.companion.core.HistoryViews
import dev.gray.companion.core.MacroTemplateTokens
import dev.gray.companion.core.PromptReferenceLanguage
import dev.gray.companion.core.ResourceLocation

/** A UI-only occurrence, frozen against the exact template that supplied its UTF-16 span. */
internal data class PromptReferenceOccurrence(
    val source: String,
    val start: Int,
    val endExclusive: Int,
    val title: String,
    val history: HistorySelection?,
    val locations: List<ResourceLocation> = emptyList(),
) {
    val text: String get() = source.substring(start, endExclusive)
    fun matches(current: String): Boolean = current == source
}

internal data class PromptReferenceProblem(val start: Int, val endExclusive: Int, val message: String)

internal fun promptReferenceOccurrences(
    source: String,
    scan: MacroTemplateTokens.Scan,
    references: List<PromptReferenceChoice>,
): List<PromptReferenceOccurrence> = scan.tokens.filter { it.closed }.mapIndexed { index, token ->
    val expression = token.expression.trim()
    val choices = references.filter { it.macro.removeSurrounding("{{", "}}").trim() == expression }
    val history = runCatching { HistoryViews.selection(expression) }.getOrNull()
    val name = choices.map { it.title }.distinct().singleOrNull() ?: if (history != null) "历史上下文" else expression.ifBlank { "空引用" }
    PromptReferenceOccurrence(source, token.start, token.endExclusive,
        "${index + 1} · $name", history, choices.flatMap { it.locations }.distinct())
}

/** Diagnostic offsets come from the existing scanner/compiler, never a second template grammar. */
internal fun promptReferenceProblems(source: String, scan: MacroTemplateTokens.Scan): List<PromptReferenceProblem> =
    (scan.diagnostics.map { PromptReferenceProblem(it.start, it.endExclusive, it.message) } +
        scan.tokens.filter { it.closed }.flatMap { token ->
            PromptReferenceLanguage.compile(source.substring(token.start, token.endExclusive)).diagnostics.map { diagnostic ->
                PromptReferenceProblem(token.start + diagnostic.span.start, token.start + diagnostic.span.endExclusive, diagnostic.message)
            }
        }).distinct()

@Composable
internal fun PromptReferenceChips(
    occurrences: List<PromptReferenceOccurrence>,
    onSelect: (PromptReferenceOccurrence) -> Unit,
) {
    if (occurrences.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        occurrences.forEach { occurrence -> key(occurrence.start, occurrence.endExclusive) {
            InputChip(selected = false, onClick = { onSelect(occurrence) },
                label = { Text(occurrence.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        } }
    }
}

@Composable
internal fun PromptReferenceActions(
    occurrence: PromptReferenceOccurrence,
    preview: PromptMacroPreview,
    onReplace: () -> Unit,
    onSource: (() -> Unit)? = null,
    onSeparate: (() -> Unit)? = null,
    onLiteral: (() -> Unit)? = null,
    onHistory: (() -> Unit)?,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var more by remember(occurrence.source, occurrence.start) { mutableStateOf(false) }
    var showExpression by remember(occurrence.source, occurrence.start) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preview.sourceLabel ?: occurrence.title, style = MaterialTheme.typography.titleLarge)
            if (isTextHistoryReference(occurrence.text)) Text(
                "此引用以一段文本展开；Assistant、User 等发言者前缀属于正文，不是独立的 API 消息角色。可在下方调整历史范围或切换输出形式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("当前内容", style = MaterialTheme.typography.labelLarge)
            Surface(color = androidx.compose.ui.graphics.Color(0xFFDCEAFF), contentColor = androidx.compose.ui.graphics.Color(0xFF173C70),
                shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(preview.unavailableReason ?: preview.issues.firstOrNull { it.error }?.message
                            ?: preview.text.ifBlank { "暂无内容" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            onSource?.let { action ->
                TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text("编辑来源") }
            }
            if (onSource == null && occurrence.history == null)
                Text("此处内容由当前上下文提供。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            onHistory?.let { action -> TextButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text("调整历史范围与输出形式") } }
            TextButton(onClick = onEditText, modifier = Modifier.fillMaxWidth()) { Text("编辑这处宏表达式") }
            TextButton(onClick = onReplace, modifier = Modifier.fillMaxWidth()) { Text("替换为其他宏…") }
            TextButton(onClick = { showExpression = !showExpression }) { Text(if (showExpression) "收起宏代码" else "查看宏代码") }
            if (showExpression) SelectionContainer {
                Text(occurrence.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider()
            TextButton(onClick = { more = !more }) { Text(if (more) "收起更多操作" else "更多操作") }
            if (more) {
                onLiteral?.let { action ->
                    TextButton(onClick = action) { Text("将这一处转为普通文字") }
                    Text("保留当前内容，之后不再随来源更新。", style = MaterialTheme.typography.bodySmall)
                }
                onSeparate?.let { action -> TextButton(onClick = action) { Text("创建独立副本…") } }
                TextButton(onClick = onDelete) { Text("移除这一处", color = MaterialTheme.colorScheme.error) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
