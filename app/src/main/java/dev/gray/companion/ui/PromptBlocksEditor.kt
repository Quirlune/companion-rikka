package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.ui.theme.CustomColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The prompt page owns its draft; Save is its only write to the enclosing call. */
@Composable
internal fun PromptBlocksEditor(
    initial: List<PromptBlock>, references: List<PromptReferenceChoice>,
    onSave: (List<PromptBlock>) -> Unit, onBack: () -> Unit,
    onRegisterBack: ((() -> Unit) -> Unit)? = null,
    title: String = "提示词", saving: Boolean = false,
    tags: List<PromptTag> = emptyList(),
) {
    val baseline = remember(initial) {
        val used = initial.map { it.slug }.filter { it.isNotEmpty() }.toMutableSet()
        initial.map { block -> MacroNaming.normalize(block, used).also { used += it.slug } }
    }
    var blocks by remember(initial) { mutableStateOf(baseline) }
    val undo = remember { mutableStateListOf<List<PromptBlock>>() }
    var undoGroup by remember { mutableStateOf<String?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    fun edit(value: List<PromptBlock>, group: String? = null) {
        if (value != blocks) {
            if (group == null || undoGroup != group) {
                undo.add(blocks)
                if (undo.size > 100) undo.removeAt(0)
            }
            undoGroup = group
            blocks = value
        }
    }
    fun requestBack() { focus.clearFocus(); if (blocks != baseline) confirmBack = true else onBack() }
    SideEffect { onRegisterBack?.invoke(::requestBack) }
    BackHandler { requestBack() }
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = blocks.indexOfFirst { it.id == from.key }
        val toIndex = blocks.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) edit(blocks.moved(fromIndex, toIndex))
    }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) }, navigationIcon = { CompanionBackButton(::requestBack) },
                actions = {
                    if (blocks != baseline) Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { focus.clearFocus(); onSave(blocks) }, enabled = !saving) { Icon(HugeIcons.FloppyDisk, "保存提示词") }
                }, scrollBehavior = scrollBehavior, colors = CustomColors.topBarColors,
            )
        }, modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val block = PromptBlock(title = nextDisplayName(blocks.map { it.title }, "提示词"),
                        slug = nextEditorSlug(blocks.map { it.slug }, "prompt"))
                    edit(blocks + block); expanded[block.id] = true
                }) { Icon(HugeIcons.Add01, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加") }
                TextButton(onClick = { if (undo.isNotEmpty()) { blocks = undo.removeAt(undo.lastIndex); undoGroup = null } }, enabled = undo.isNotEmpty()) { Text("撤销") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (blocks != baseline) confirmBack = true else onBack() }) { Text("放弃") }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocks.isEmpty()) item { Text("添加提示词，或保持空白。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(blocks, key = { it.id }) { block ->
                    ReorderableItem(reorderable, key = block.id) { _ ->
                        Surface(shape = MaterialTheme.shapes.large, color = CustomColors.listItemColors.containerColor) {
                            Column {
                                // The entire header is the original reorderable library's long-press handle.
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(block.title.ifBlank { "提示词" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).clickable { focus.clearFocus(); expanded[block.id] = expanded[block.id] != true }.padding(vertical = 14.dp))
                                    IconButton(onClick = { renameId = block.id }, modifier = Modifier.size(36.dp)) { Icon(HugeIcons.PencilEdit01, "重命名提示词", Modifier.size(18.dp)) }
                                    CompactRoleSelector(block.role) { role -> edit(blocks.map { if (it.id == block.id) it.copy(role = role) else it }) }
                                    Switch(block.enabled, { enabled -> edit(blocks.map { if (it.id == block.id) it.copy(enabled = enabled) else it }) })
                                    IconButton(onClick = { focus.clearFocus(); expanded[block.id] = expanded[block.id] != true }, modifier = Modifier.size(36.dp)) {
                                        Icon(if (expanded[block.id] == true) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, "展开或收起提示词", Modifier.size(18.dp))
                                    }
                                }
                                if (expanded[block.id] == true) Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    fun update(value: PromptBlock, group: String? = null) = edit(blocks.map { if (it.id == block.id) value else it }, group)
                                    PromptTagSelector(tags, block.tagIds) { update(block.copy(tagIds = it)) }
                                    PromptTemplateField("内容", block.template, references, { update(block.copy(template = it), "template:${block.id}") }, minLines = 3)
                                    TextProcessingEditor("内容处理", block.processing, references) { update(block.copy(processing = it)) }
                                    EditorSection("插入位置与触发", promptPositionLabel(block)) {
                                        ChoiceLine("插入位置", block.position, listOf(PromptPosition.BEFORE_HISTORY to "历史之前", PromptPosition.IN_HISTORY to "历史中", PromptPosition.AFTER_HISTORY to "历史之后")) { update(block.copy(position = it)) }
                                        if (block.position == PromptPosition.IN_HISTORY) {
                                            var depth by remember(block.id, block.depth) { mutableStateOf(block.depth.toString()) }
                                            OutlinedTextField(depth, { text -> depth = text; text.toIntOrNull()?.takeIf { it >= 0 }?.let { update(block.copy(depth = it)) } }, label = { Text("距历史末尾的消息数") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            Text("0 位于最后一条之后。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        ToggleLine("引用全空时隐藏本块", block.hideWhenReferencesEmpty) { update(block.copy(hideWhenReferencesEmpty = it)) }
                                        HorizontalDivider()
                                        TriggerEditor(block.recognition, block.condition, references) { recognition, condition -> update(block.copy(recognition = recognition, condition = condition)) }
                                    }
                                    TextButton(onClick = { deleteId = block.id }) { Icon(HugeIcons.Delete01, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("删除提示词") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renameId?.let { id -> blocks.firstOrNull { it.id == id }?.let { block ->
        NamingDialog("提示词名称", block.title, block.slug,
            blocks.filterNot { it.id == id }.map { it.title }, blocks.filterNot { it.id == id }.map { it.slug },
            { renameId = null }) { name, slug ->
            edit(blocks.map { if (it.id == id) it.copy(title = name, slug = slug) else it }); renameId = null
        }
    } }
    deleteId?.let { id -> AlertDialog(
        onDismissRequest = { deleteId = null }, title = { Text("删除提示词？") }, text = { Text("删除后可以在此页撤销。") },
        confirmButton = { TextButton(onClick = { edit(blocks.filterNot { it.id == id }); deleteId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
    ) }
    if (confirmBack) DraftLeaveDialog({ confirmBack = false }, { confirmBack = false; onSave(blocks) }, { confirmBack = false; onBack() })
}

@Composable
private fun CompactRoleSelector(role: Role, onChange: (Role) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(role.name.lowercase(), style = MaterialTheme.typography.bodySmall) }
        DropdownMenu(open, { open = false }) {
            Role.entries.forEach { option -> DropdownMenuItem(text = { Text(option.name.lowercase(), style = MaterialTheme.typography.bodyMedium) }, onClick = { onChange(option); open = false }) }
        }
    }
}

internal fun promptPositionLabel(block: PromptBlock) = when (block.position) {
    PromptPosition.BEFORE_HISTORY -> "历史之前"
    PromptPosition.AFTER_HISTORY -> "历史之后"
    PromptPosition.IN_HISTORY -> "历史中 · 深度 ${block.depth}"
}
