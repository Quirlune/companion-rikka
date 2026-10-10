package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
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
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun ModelsScreen(
    library: Library,
    onEdit: (Editing) -> Unit,
    onUpdate: ((Library) -> Library) -> Unit,
    modifier: Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteProfile by remember { mutableStateOf<ModelProfile?>(null) }
    var deleteConnection by remember { mutableStateOf<ApiConnection?>(null) }
    val normalizedQuery = query.trim()
    val connectionsById = remember(library.connections) { library.connections.associateBy { it.id } }
    val linkedModelCounts = remember(library.profiles) {
        library.profiles.mapNotNull { it.apiConnectionId }.groupingBy { it }.eachCount()
    }
    val assignedWorkflowCounts = remember(library.mainCalls, library.groups, library.automaticOptions) {
        buildMap<String, Int> {
            fun add(workflow: Workflow) {
                workflow.modelProfileId?.let { id -> put(id, (get(id) ?: 0) + 1) }
                workflow.nodes.forEach { node ->
                    node.generation.profileId?.let { id -> put(id, (get(id) ?: 0) + 1) }
                }
            }
            library.mainCalls.forEach { main -> main.configuredWorkflows().forEach(::add) }
            library.groups.forEach { group -> group.workflows.forEach(::add) }
            listOfNotNull(if (library.automaticOptions.blocks == null) library.automaticOptions.modelProfileId
                else library.automaticOptions.generation.profileId).forEach { id ->
                put(id, (get(id) ?: 0) + 1)
            }
        }
    }
    val visibleConnections = remember(library.connections, normalizedQuery) {
        library.connections.filter { connection ->
            normalizedQuery.isBlank() || listOf(connection.name, connection.protocol.name, connection.baseUrl)
                .any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val visibleProfiles = remember(library.profiles, connectionsById, normalizedQuery) {
        library.profiles.filter { profile ->
            val connectionName = profile.apiConnectionId?.let { connectionsById[it]?.name }.orEmpty()
            normalizedQuery.isBlank() || listOf(profile.name, profile.model, profile.protocol.name, profile.baseUrl, connectionName)
                .any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索连接或模型") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("API 连接", style = MaterialTheme.typography.titleLarge)
                    Text("凭据由本机凭据存储管理；导出的 JSON 不包含 API Key。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onEdit(Editing(EditorKind.CONNECTION, newId())) }) { Text("新建连接") }
            }
        }
        if (visibleConnections.isEmpty()) {
            item {
                EmptyModelSectionText(
                    if (library.connections.isEmpty()) "还没有 API 连接。创建连接后，模型可以共用它。" else "没有找到匹配的 API 连接。",
                )
            }
        } else {
            items(visibleConnections, key = { "connection:${it.id}" }) { connection ->
                val linkedModels = linkedModelCounts[connection.id] ?: 0
                CardGroup {
                    item(
                        onClick = { onEdit(Editing(EditorKind.CONNECTION, connection.id)) },
                        headlineContent = {
                            Text(connection.name.ifBlank { "API 连接" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                "${connection.protocol} · ${connection.baseUrl} · ${if (connection.enabled) "已启用" else "已停用"} · 被 $linkedModels 个模型使用",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            ModelRowMenu(
                                deleteEnabled = linkedModels == 0,
                                deleteDisabledReason = "仍有模型使用此连接",
                                onEdit = { onEdit(Editing(EditorKind.CONNECTION, connection.id)) },
                                onDelete = { deleteConnection = connection },
                            )
                        },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模型", style = MaterialTheme.typography.titleLarge)
                    Text("节点模型优先于调用模型；调用模型优先于全局默认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = {
                    onEdit(Editing(EditorKind.PROFILE, newId()))
                }) { Text("新建模型") }
            }
        }
        if (visibleProfiles.isEmpty()) {
            item {
                EmptyModelSectionText(
                    if (library.profiles.isEmpty()) "还没有模型。填写模型 ID，再选择对应的 API 连接。" else "没有找到匹配的模型。",
                )
            }
        } else {
            items(visibleProfiles, key = { "profile:${it.id}" }) { profile ->
                val connectionName = profile.apiConnectionId?.let { connectionsById[it]?.name }
                    ?: if (profile.apiConnectionId == null) "旧配置" else "连接缺失"
                val assignedWorkflows = assignedWorkflowCounts[profile.id] ?: 0
                CardGroup {
                    item(
                        onClick = { onEdit(Editing(EditorKind.PROFILE, profile.id)) },
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(profile.name.ifBlank { "未命名模型" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (library.selectedProfileId == profile.id) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ) {
                                        Text("默认", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                "${profile.model.ifBlank { "未填写模型 ID" }} · $connectionName" +
                                    if (profile.apiConnectionId == null) " · ${profile.protocol} · ${profile.baseUrl}" else "",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            ModelRowMenu(
                                defaultAction = { onUpdate { it.copy(selectedProfileId = profile.id) } },
                                defaultEnabled = library.selectedProfileId != profile.id,
                                defaultLabel = if (library.selectedProfileId == profile.id) "当前默认" else "设为默认",
                                deleteEnabled = assignedWorkflows == 0,
                                deleteDisabledReason = "被 $assignedWorkflows 处调用、节点或内置功能使用",
                                onEdit = { onEdit(Editing(EditorKind.PROFILE, profile.id)) },
                                onDelete = { deleteProfile = profile },
                            )
                        },
                    )
                }
            }
        }
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text("删除模型？") },
            text = { Text("确定删除“${profile.name.ifBlank { "未命名模型" }}”吗？被调用、节点或内置功能指定的模型不能删除。") },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate { lib ->
                        lib.copy(
                            profiles = lib.profiles.filterNot { it.id == profile.id },
                            selectedProfileId = lib.selectedProfileId.takeUnless { it == profile.id },
                        )
                    }
                    deleteProfile = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("取消") } },
        )
    }
    deleteConnection?.let { connection ->
        val canDelete = library.profiles.none { it.apiConnectionId == connection.id }
        AlertDialog(
            onDismissRequest = { deleteConnection = null },
            title = { Text("删除 API 连接？") },
            text = { Text("确定删除“${connection.name.ifBlank { "API 连接" }}”吗？仍有模型使用此连接时，删除会被阻止。") },
            confirmButton = {
                TextButton(enabled = canDelete, onClick = {
                    onUpdate { lib -> lib.copy(connections = lib.connections.filterNot { it.id == connection.id }) }
                    deleteConnection = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteConnection = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyModelSectionText(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
}

@Composable
private fun ModelRowMenu(
    defaultAction: (() -> Unit)? = null,
    defaultEnabled: Boolean = false,
    defaultLabel: String = "设为默认",
    deleteEnabled: Boolean = true,
    deleteDisabledReason: String? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(HugeIcons.MoreVertical, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            defaultAction?.let { action ->
                DropdownMenuItem(
                    text = { Text(defaultLabel) },
                    enabled = defaultEnabled,
                    onClick = { expanded = false; action() },
                )
            }
            DropdownMenuItem(text = { Text("编辑") }, onClick = { expanded = false; onEdit() })
            DropdownMenuItem(
                text = { Text(if (deleteEnabled) "删除" else deleteDisabledReason ?: "无法删除") },
                enabled = deleteEnabled,
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}
