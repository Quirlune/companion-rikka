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
    owner: ResourceLocation? = null,
) {
    val baseline = initial
    // Legacy placement is converted in this draft only; cancel leaves the saved call intact.
    var blocks by remember(initial) { mutableStateOf(initial.map { it.copy(position = PromptPosition.BEFORE_HISTORY, depth = 0) }) }
    val undo = remember { mutableStateListOf<List<PromptBlock>>() }
    var undoGroup by remember { mutableStateOf<String?>(null) }
    var confirmBack by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var selectedId by remember(initial) { mutableStateOf<String?>(null) }
    val selectedBlock = blocks.firstOrNull { it.id == selectedId }
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
    fun requestBack() {
        focus.clearFocus()
        if (selectedBlock != null) { selectedId = null; undoGroup = null }
        else if (blocks != baseline) confirmBack = true else onBack()
    }
    SideEffect { onRegisterBack?.invoke(::requestBack) }
    BackHandler { requestBack() }
    val reorderable = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = blocks.indexOfFirst { it.id == from.key }
        val toIndex = blocks.indexOfFirst { it.id == to.key }
        if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) edit(blocks.moved(fromIndex, toIndex))
    }
    BlocksSourceBoundary(owner, blocks, { edit(it) }) {
    val sourceNavigation = LocalSourceNavigation.current
    val sourceIssues = LocalSourceIssues.current
    val sourceFocus = sourceNavigation?.focus
    LaunchedEffect(sourceFocus) {
        val id = sourceFocus?.blockId
        val index = blocks.indexOfFirst { it.id == id }
        if (index >= 0) selectedId = blocks[index].id
    }
    var showIssues by remember { mutableStateOf(false) }
    if (showIssues && sourceNavigation != null) SourceIssuesSheet(sourceNavigation.library, sourceIssues, { location -> if (location.field == null) sourceNavigation.open(location) else sourceNavigation.edit(location) }, { showIssues = false })
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(selectedBlock?.title?.ifBlank { "提示词" } ?: title) }, navigationIcon = { CompanionBackButton(::requestBack) },
                actions = {
                    if (sourceIssues.isNotEmpty()) TextButton(onClick = { showIssues = true }) { Text("问题 ${sourceIssues.size}") }
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
                    val block = PromptBlock(title = nextDisplayName(blocks.map { it.title }, "提示词"))
                    edit(blocks + block); selectedId = block.id; focus.clearFocus()
                }) { Icon(HugeIcons.Add01, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加") }
                TextButton(onClick = { if (undo.isNotEmpty()) { blocks = undo.removeAt(undo.lastIndex); undoGroup = null } }, enabled = undo.isNotEmpty()) { Text("撤销") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (blocks != baseline) confirmBack = true else onBack() }) { Text("放弃") }
            }
            if (selectedBlock == null) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (blocks.isEmpty()) item { Text("添加提示词，或保持空白。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(blocks, key = { it.id }) { block ->
                        ReorderableItem(reorderable, key = block.id) { _ ->
                            Surface(shape = MaterialTheme.shapes.large, color = CustomColors.listItemColors.containerColor) {
                                Row(Modifier.fillMaxWidth().longPressDraggableHandle().padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { focus.clearFocus(); selectedId = block.id; undoGroup = null }.padding(vertical = 14.dp)) {
                                        Text(block.title.ifBlank { "提示词" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${block.role.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(block.enabled, { enabled -> edit(blocks.map { if (it.id == block.id) it.copy(enabled = enabled) else it }) })
                                    TextButton(onClick = { focus.clearFocus(); selectedId = block.id; undoGroup = null }) { Text("编辑") }
                                }
                            }
                        }
                    }
                }
            } else key(selectedBlock.id) {
                val block = selectedBlock
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { focus.clearFocus(); selectedId = null; undoGroup = null }) { Text("返回列表") }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { renameId = block.id }) { Icon(HugeIcons.PencilEdit01, "重命名提示词", Modifier.size(18.dp)) }
                            CompactRoleSelector(block.role) { role -> edit(blocks.map { if (it.id == block.id) it.copy(role = role) else it }) }
                            Switch(block.enabled, { enabled -> edit(blocks.map { if (it.id == block.id) it.copy(enabled = enabled) else it }) })
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            fun update(value: PromptBlock, group: String? = null) = edit(blocks.map { if (it.id == block.id) value else it }, group)
                            PromptTagSelector(tags, block.tagIds) { update(block.copy(tagIds = it)) }
                            if (block.injectionSlot) {
                                Text("提示词接收槽 · 只接收宏来源和指定到此槽的注入，不编辑字面内容或内容处理。来源先按下列顺序展开，再按独立上下文顺序填入注入内容；角色与位置由此槽决定。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PromptSlotReferencesEditor(block, references) { update(block.copy(slotReferences = it)) }
                            } else {
                                PromptTemplateField("内容", block.template, references, { update(block.copy(template = it), "template:${block.id}") }, minLines = 3, owner = owner?.let { ExtensionFieldRef(it.resource.kind, it.resource.id, ExtensionField.BLOCK_TEMPLATE, workflowId = it.workflowId, nodeId = it.nodeId, blockId = block.id) })
                                TextProcessingEditor("内容处理", block.processing, references) { update(block.copy(processing = it)) }
                            }
                            EditorSection("触发条件", "位置由提示词列表顺序决定") {
                                ToggleLine("引用均为空或不可用时隐藏整条", block.hideWhenReferencesEmpty) { update(block.copy(hideWhenReferencesEmpty = it)) }
                                Text(if (block.injectionSlot) "槽停用或条件不满足时，槽内来源和注入均不发送；来源语法错误仍会报告。" else "只对含引用的提示词生效；纯文字照常保留，语法错误仍会报告。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider()
                                TriggerEditor(block.recognition, block.condition, references) { recognition, condition -> update(block.copy(recognition = recognition, condition = condition)) }
                            }
                            EditorSection("其他设置", if (block.mergeAdjacentMessages) "允许合并相邻同角色消息" else "保留独立消息边界") {
                                ToggleLine("合并相邻同角色消息", block.mergeAdjacentMessages) {
                                    update(block.copy(mergeAdjacentMessages = it))
                                }
                                Text("关闭后，本条目产生的消息不与前后同角色消息拼接，便于保留独立 Assistant 段落。可在提示词预览检查实际消息顺序。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            OutlinedButton(onClick = { deleteId = block.id },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(HugeIcons.Delete01, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("删除提示词")
                            }
                        }
                    }
                }
            }
        }
    }
    }
    renameId?.let { id -> blocks.firstOrNull { it.id == id }?.let { block ->
        PromptBlockNameDialog(block.title, blocks.filterNot { it.id == id }.map { it.title },
            { renameId = null }) { name ->
            edit(blocks.map { if (it.id == id) it.copy(title = name) else it }); renameId = null
        }
    } }
    deleteId?.let { id -> AlertDialog(
        onDismissRequest = { deleteId = null }, title = { Text("删除提示词？") }, text = { Text("删除后可以在此页撤销。") },
        confirmButton = { TextButton(onClick = { edit(blocks.filterNot { it.id == id }); selectedId = null; deleteId = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
    ) }
    if (confirmBack) DraftLeaveDialog({ confirmBack = false }, { confirmBack = false; onSave(blocks) }, { confirmBack = false; onBack() })
}

@Composable
private fun PromptBlockNameDialog(initialName: String, siblingNames: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val nameError = MacroNaming.nameProblem(name.trim(), siblingNames)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提示词名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true,
                    isError = nameError != null, modifier = Modifier.fillMaxWidth())
                if (nameError != null) Text(nameError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = nameError == null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PromptSlotReferencesEditor(block: PromptBlock, references: List<PromptReferenceChoice>, onChange: (List<String>) -> Unit) {
    var picking by remember(block.id) { mutableStateOf(false) }
    val choices = references.filter { DefaultPromptSlots.referenceError(it.macro) == null }
    if (block.slotReferences.isEmpty()) Text("尚未选择来源；也可由提示词管理节点向此槽注入。", style = MaterialTheme.typography.bodySmall)
    block.slotReferences.forEachIndexed { index, reference ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var expression by remember(block.id, index, reference) { mutableStateOf(reference) }
            val error = DefaultPromptSlots.referenceError(expression)
            Text("${index + 1}. ${references.firstOrNull { it.macro == reference }?.title ?: "已有来源"}", style = MaterialTheme.typography.bodyMedium)
            PromptTemplateField("来源宏 · 只接受完整 {{...}}", expression, choices, { value ->
                expression = value
                if (DefaultPromptSlots.referenceError(value) == null) onChange(block.slotReferences.toMutableList().also { it[index] = value })
            }, minLines = 1)
            error?.let { Text("$it；当前输入尚未应用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onChange(block.slotReferences.moved(index, index - 1)) }, enabled = index > 0) { Text("前移") }
                TextButton(onClick = { onChange(block.slotReferences.moved(index, index + 1)) }, enabled = index < block.slotReferences.lastIndex) { Text("后移") }
                TextButton(onClick = { onChange(block.slotReferences.filterIndexed { i, _ -> i != index }) }) { Text("移除") }
            }
        }
    }
    Text("这里编辑的是来源宏表达式，可用既有包、分类、标签等引用语法；解析出的内容只读。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = { picking = true }) { Text("添加来源宏") }
    if (picking) PromptReferenceSheet(choices, { reference ->
        if (DefaultPromptSlots.referenceError(reference) == null) onChange(block.slotReferences + reference)
    }, { picking = false })
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
