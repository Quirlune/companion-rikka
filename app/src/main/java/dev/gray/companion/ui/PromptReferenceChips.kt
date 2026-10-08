package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.HistorySelection
import dev.gray.companion.core.HistoryViews
import dev.gray.companion.core.MacroTemplateTokens
import dev.gray.companion.core.PromptReferenceLanguage

/** A UI-only occurrence, frozen against the exact template that supplied its UTF-16 span. */
internal data class PromptReferenceOccurrence(
    val source: String,
    val start: Int,
    val endExclusive: Int,
    val title: String,
    val history: HistorySelection?,
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
    val choice = references.firstOrNull { it.macro.removeSurrounding("{{", "}}").trim() == expression }
    val history = runCatching { HistoryViews.selection(expression) }.getOrNull()
    val name = choice?.title ?: if (history != null) "历史上下文" else expression.ifBlank { "空引用" }
    PromptReferenceOccurrence(source, token.start, token.endExclusive,
        "${index + 1} · $name", history)
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
    onReplace: () -> Unit,
    onHistory: (() -> Unit)?,
    onEditText: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
            .imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(occurrence.title, style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(occurrence.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            onHistory?.let { action -> TextButton(onClick = action) { Text("编辑这一处历史引用") } }
            TextButton(onClick = onReplace) { Text("替换来源…") }
            TextButton(onClick = onEditText) { Text("在原文中编辑") }
            TextButton(onClick = onDelete) { Text("移除这一处") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
