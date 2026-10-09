package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.ui.components.ui.CardGroup

private data class ResourceRow(
    val id: String,
    val name: String,
    val searchText: String,
    val supportingText: String,
    val avatar: AvatarImage? = null,
)

private data class ResourceAssociationCounts(
    val characters: Map<String, Int>,
    val mainCalls: Map<String, Int>,
    val packages: Map<String, Int>,
    val groups: Map<String, Int>,
)

private fun Library.resourceAssociationCounts(): ResourceAssociationCounts {
    val characterCounts = characters.associate { it.id to it.boundPackageIds.size }.toMutableMap()
    val mainCounts = mainCalls.associate { it.id to it.boundPackageIds.size + it.boundGroupIds.size }.toMutableMap()
    val packageCounts = packages.associate { it.id to 0 }.toMutableMap()
    val groupCounts = groups.associate { it.id to it.boundPackageIds.size }.toMutableMap()

    fun increment(counts: MutableMap<String, Int>, id: String) {
        counts[id] = (counts[id] ?: 0) + 1
    }

    characters.forEach { card -> card.boundPackageIds.distinct().forEach { increment(packageCounts, it) } }
    mainCalls.forEach { main ->
        main.boundPackageIds.distinct().forEach { increment(packageCounts, it) }
        main.boundGroupIds.distinct().forEach { increment(groupCounts, it) }
    }
    groups.forEach { group -> group.boundPackageIds.distinct().forEach { increment(packageCounts, it) } }
    sessions.forEach { session ->
        if (session.characterId.isNotBlank()) increment(characterCounts, session.characterId)
        if (session.mainCallId.isNotBlank()) increment(mainCounts, session.mainCallId)
        session.packageIds.distinct().forEach { increment(packageCounts, it) }
        session.groupIds.distinct().forEach { increment(groupCounts, it) }
    }
    savedChats.forEach { saved ->
        val session = saved.snapshot
        if (session.characterId.isNotBlank()) increment(characterCounts, session.characterId)
        if (session.mainCallId.isNotBlank()) increment(mainCounts, session.mainCallId)
        session.packageIds.distinct().forEach { increment(packageCounts, it) }
        session.groupIds.distinct().forEach { increment(groupCounts, it) }
    }
    return ResourceAssociationCounts(characterCounts, mainCounts, packageCounts, groupCounts)
}

@Composable
internal fun ResourceScreen(
    library: Library,
    selected: ResourceKind,
    onEdit: (Editing) -> Unit,
    onUpdate: ((Library) -> Library) -> Unit,
    modifier: Modifier,
    onManageTags: (() -> Unit)? = null,
    onSelectMainCall: ((String) -> Unit)? = null,
    selectionBusy: Boolean = false,
    onApplyCommand: (((Library) -> ExtensionEditResult) -> Unit)? = null,
    onOpenLocation: (ResourceLocation) -> Unit = {},
) {
    val activeMainId = library.sessions.firstOrNull { it.id == library.selectedSessionId }?.mainCallId
    val selectingMain = selected == ResourceKind.MAIN && onSelectMainCall != null
    val associationCounts = remember(library) { library.resourceAssociationCounts() }
    val rows = remember(library, selected, associationCounts) {
        when (selected) {
            ResourceKind.CHARACTER -> library.characters.map { card ->
                ResourceRow(
                    id = card.id, name = card.name,
                    searchText = listOf(card.name, card.description, card.namespace).joinToString(" "),
                    supportingText = listOfNotNull(
                        card.description.takeIf { it.isNotBlank() },
                        "${card.entries.size} 个条目 · ${associationCounts.characters[card.id] ?: 0} 个关联",
                        card.namespace.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
            ResourceKind.MAIN -> library.mainCalls.map { main ->
                ResourceRow(
                    id = main.id, name = main.name,
                    avatar = main.avatar,
                    searchText = listOf(main.name, main.description).joinToString(" "),
                    supportingText = listOfNotNull(
                        main.description.takeIf { it.isNotBlank() },
                        "${main.workflows.size} 个工作流 · ${associationCounts.mainCalls[main.id] ?: 0} 个关联",
                    ).joinToString(" · "),
                )
            }
            ResourceKind.PACKAGE -> library.packages.map { pack ->
                ResourceRow(
                    id = pack.id, name = pack.name,
                    searchText = listOf(pack.name, pack.namespace).joinToString(" "),
                    supportingText = listOfNotNull(
                        "${pack.entries.size} 个条目 · ${associationCounts.packages[pack.id] ?: 0} 个关联",
                        pack.namespace.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                )
            }
            ResourceKind.GROUP -> library.groups.map { group ->
                ResourceRow(
                    id = group.id, name = group.name,
                    searchText = listOf(group.name, group.description).joinToString(" "),
                    supportingText = listOfNotNull(
                        group.description.takeIf { it.isNotBlank() },
                        "${group.workflows.size} 个工作流 · ${associationCounts.groups[group.id] ?: 0} 个关联",
                    ).joinToString(" · "),
                )
            }
        }
    }
    var query by rememberSaveable(selected) { mutableStateOf("") }
    val filtered = remember(rows, query) {
        if (query.isBlank()) rows else rows.filter { it.searchText.contains(query.trim(), ignoreCase = true) }
    }
    var actionRow by remember(selected) { mutableStateOf<ResourceRow?>(null) }
    var operation by remember(selected) { mutableStateOf<ResourceOperationPreview?>(null) }

    fun location(row: ResourceRow) = ResourceLocation(ExtensionResourceRef(when (selected) {
        ResourceKind.CHARACTER -> ExtensionResourceKind.CHARACTER
        ResourceKind.MAIN -> ExtensionResourceKind.MAIN
        ResourceKind.PACKAGE -> ExtensionResourceKind.PACKAGE
        ResourceKind.GROUP -> ExtensionResourceKind.GROUP
    }, row.id))
    fun preview(row: ResourceRow, delete: Boolean) {
        val target = location(row)
        operation = ResourceOperationPreview(target, row.name, delete,
            if (delete) ResourceEditCommands.previewDelete(library, target)
            else ResourceEditCommands.previewCopy(library, target))
        actionRow = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
            if (selected == ResourceKind.PACKAGE && onManageTags != null) item {
                EditorDestinationRow("提示词标签", "管理全局标签目录") { onManageTags?.invoke() }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rows.size} 项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Button(onClick = { onEdit(newResource(selected)) }, enabled = !selectionBusy) { Text("新建${selected.title}") }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索${selected.title}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }
            if (rows.isNotEmpty()) item {
                Text(if (selectingMain) "轻点选择当前主调用，长按查看资源操作。" else "轻点编辑，长按查看复制、删除等操作。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (rows.isEmpty()) "还没有${selected.title}，点上方按钮创建第一项。" else "没有找到匹配的${selected.title}。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                }
            } else {
                items(filtered, key = { it.id }) { row ->
                    CardGroup {
                        item(
                            modifier = Modifier.combinedClickable(enabled = !selectionBusy, onLongClick = { actionRow = row }, onClick = {
                                if (!selectionBusy) {
                                    if (selectingMain) onSelectMainCall?.invoke(row.id)
                                    else onEdit(Editing(selected.editor, row.id))
                                }
                            }),
                            headlineContent = {
                                Text(row.name.ifBlank { "未命名" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = if (selected == ResourceKind.MAIN) ({
                                ResourceAvatarBadge(row.name.ifBlank { "主调用" }, row.avatar, 40.dp, onClick = {
                                    if (!selectionBusy) {
                                        if (selectingMain) onSelectMainCall?.invoke(row.id)
                                        else onEdit(Editing(selected.editor, row.id))
                                    }
                                })
                            }) else null,
                            supportingContent = {
                                Column {
                                    if (selectingMain && row.id == activeMainId) Text("当前使用", color = MaterialTheme.colorScheme.primary)
                                    Text(row.supportingText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingContent = {
                                Row {
                                    if (selectingMain && row.id == activeMainId) IconButton(onClick = { onEdit(Editing(selected.editor, row.id)) }, enabled = !selectionBusy) {
                                        Icon(HugeIcons.PencilEdit01, contentDescription = "编辑${row.name.ifBlank { "主调用" }}")
                                    }
                                    if (selectingMain && row.id == activeMainId) IconButton(onClick = { actionRow = row }, enabled = !selectionBusy) {
                                        Icon(HugeIcons.MoreVertical, contentDescription = "当前主调用操作")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

    actionRow?.let { row ->
        ModalBottomSheet(onDismissRequest = { actionRow = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name.ifBlank { "未命名" }, style = MaterialTheme.typography.titleLarge)
                CardGroup {
                    item(onClick = { actionRow = null; onEdit(Editing(selected.editor, row.id)) }, headlineContent = { Text("编辑") })
                    item(onClick = { if (!selectionBusy) preview(row, false) }, headlineContent = { Text("复制…") })
                    item(onClick = { if (!selectionBusy) preview(row, true) }, headlineContent = { Text("删除…") })
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    operation?.let { current ->
        ResourceOperationSheet(library, current, selectionBusy, onApplyCommand,
            onOpenLocation = onOpenLocation, onChange = { operation = it }, onDismiss = { operation = null })
    }
}

private fun newResource(selected: ResourceKind): Editing = when (selected) {
    ResourceKind.CHARACTER -> Editing(EditorKind.CHARACTER, newId())
    ResourceKind.MAIN -> Editing(EditorKind.MAIN, newId())
    ResourceKind.PACKAGE -> Editing(EditorKind.PACKAGE, newId())
    ResourceKind.GROUP -> Editing(EditorKind.GROUP, newId())
}
