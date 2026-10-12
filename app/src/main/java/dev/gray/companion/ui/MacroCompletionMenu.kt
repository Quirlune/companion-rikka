package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

private class ReferenceBranch(val key: String, val source: PromptReferenceSource) {
    val children = linkedMapOf<String, ReferenceBranch>()
    val choices = mutableListOf<PromptReferenceChoice>()
}

private data class CompletionRow(
    val key: String,
    val depth: Int,
    val branch: ReferenceBranch? = null,
    val choice: PromptReferenceChoice? = null,
)

private fun referenceBranches(choices: List<PromptReferenceChoice>, query: String): List<ReferenceBranch> {
    val roots = linkedMapOf<String, ReferenceBranch>()
    choices.filter { reference ->
        query.isBlank() || (listOf(reference.title, reference.macro, reference.detail, reference.origin,
            reference.sourceTitle, reference.sourceDetail) + reference.owners.flatMap { listOf(it.title, it.detail) })
            .any { it.contains(query, ignoreCase = true) }
    }.forEach { reference ->
        val path = listOf(PromptReferenceSource(reference.sourceId, reference.sourceTitle, reference.sourceDetail)) + reference.owners.filter { it.inMenu }
        var children = roots
        var key = ""
        var leaf: ReferenceBranch? = null
        path.forEach { source ->
            // Include ID lengths, so identical display labels or '/' in a resource ID cannot merge sources.
            key += "${source.id.length}:${source.id}"
            val branchKey = key
            val branch = children.getOrPut(source.id) { ReferenceBranch(branchKey, source) }
            leaf = branch
            children = branch.children
        }
        leaf?.choices?.add(reference)
    }
    return roots.values.toList()
}

private fun allBranchKeys(branches: List<ReferenceBranch>): Set<String> = buildSet {
    fun collect(branch: ReferenceBranch) {
        add(branch.key)
        branch.children.values.forEach(::collect)
    }
    branches.forEach(::collect)
}

private fun completionRows(branches: List<ReferenceBranch>, expanded: Set<String>): List<CompletionRow> = buildList {
    fun append(branch: ReferenceBranch, depth: Int) {
        add(CompletionRow("branch:${branch.key}", depth, branch = branch))
        if (branch.key in expanded) {
            branch.children.values.forEach { append(it, depth + 1) }
            branch.choices.forEachIndexed { index, choice ->
                add(CompletionRow("choice:${branch.key}:${choice.macro}:$index", depth + 1, choice = choice))
            }
        }
    }
    branches.forEach { append(it, 0) }
}

/** The editor keeps focus; all expansion stays inside this floating Material menu. */
@Composable
internal fun MacroCompletionMenu(
    choices: List<PromptReferenceChoice>,
    query: String,
    width: Dp,
    maxHeight: Dp,
    onInsert: (String) -> Unit,
    onConfigureHistory: () -> Unit,
    onOpenBrowser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val addressQuery = query.substringBefore('@').substringBefore('#')
    val hasHistory = choices.any { it.macro.startsWith("{{type:builtin.in:history.name:") } || query.startsWith("history")
    val branches = remember(choices, addressQuery) { referenceBranches(choices, addressQuery) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(branches, addressQuery) {
        if (addressQuery.isNotBlank()) expanded = allBranchKeys(branches)
    }
    val rows = remember(branches, expanded) { completionRows(branches, expanded) }
    Surface(
        modifier = Modifier.width(width).heightIn(max = maxHeight),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "hint") {
                Text(
                    if (query.isBlank()) "展开来源，选取宏" else "筛选：$query",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (rows.isEmpty()) item(key = "empty") {
                Text("没有匹配的宏", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            items(rows, key = { it.key }) { row ->
                val inset = (row.depth.coerceAtMost(3) * 8).dp
                row.branch?.let { branch ->
                    val isExpanded = branch.key in expanded
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(branch.source.menuTitle, style = MaterialTheme.typography.labelLarge)
                            }
                        },
                        trailingIcon = {
                            Icon(if (isExpanded) Lucide.ChevronDown else Lucide.ChevronRight, contentDescription = null)
                        },
                        onClick = { expanded = if (isExpanded) expanded - branch.key else expanded + branch.key },
                        contentPadding = PaddingValues(start = 12.dp + inset, end = 12.dp),
                        modifier = Modifier.focusProperties { canFocus = false }.semantics {
                            stateDescription = if (isExpanded) "已展开" else "已收起"
                        },
                    )
                }
                row.choice?.let { reference ->
                    DropdownMenuItem(
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(reference.title, style = MaterialTheme.typography.bodyMedium)
                                Text(reference.detail.ifBlank { reference.origin }, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onInsert(reference.macro) },
                        contentPadding = PaddingValues(start = 12.dp + inset, end = 12.dp),
                        modifier = Modifier.focusProperties { canFocus = false },
                    )
                }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            if (hasHistory) item(key = "history-context") {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("历史上下文…")
                            Text("设置当前一处引用的范围、来源与每楼格式",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = onConfigureHistory,
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
            item(key = "browser") {
                DropdownMenuItem(
                    text = { Text("搜索全部宏…") },
                    onClick = onOpenBrowser,
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            }
            item(key = "close") {
                DropdownMenuItem(text = { Text("关闭") }, onClick = onDismiss,
                    modifier = Modifier.focusProperties { canFocus = false })
            }
        }
    }
}
