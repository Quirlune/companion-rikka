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
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar

/** Selecting a persona changes one session; choosing a default changes only future chats. */
@Composable
internal fun PersonasScreen(
    library: Library,
    onEdit: (Editing) -> Unit,
    onUpdate: ((Library) -> Library) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    val defaultId = library.selectedPersonaId ?: session?.personaId
    var query by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<UserPersona?>(null) }
    val personas = remember(library.personas, query) {
        library.personas.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    }
    val references = remember(library.sessions, library.savedChats) {
        (library.sessions.mapNotNull { it.personaId } + library.savedChats.mapNotNull { it.snapshot.personaId })
            .groupingBy { it }.eachCount()
    }
    fun selectPersona(id: String?) {
        if (session == null) {
            onUpdate { it.copy(selectedPersonaId = id) }
        } else if (session.personaId != id) {
            onUpdate { current -> current.selectPersonaForSession(session.id, id) }
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("用户身份", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { onEdit(Editing("persona", UserPersona(name = "新用户身份"))) }) { Text("新建身份") }
            }
        }
        item {
            Text(
                if (session != null) "点选身份，只切换本段对话。其他对话和已有消息的称呼保留。"
                else "点选身份，作为新对话的默认身份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            CardGroup {
                item(
                    headlineContent = { Text("新对话默认") },
                    supportingContent = {
                        Text(if (library.selectedPersonaId == null) {
                            if (session == null) "未指定时使用旧版用户资料" else "跟随当前对话的身份"
                        }
                        else library.personas.firstOrNull { it.id == library.selectedPersonaId }?.name ?: "默认身份已不可用")
                    },
                    trailingContent = {
                        if (library.selectedPersonaId != null) TextButton(onClick = { onUpdate { it.copy(selectedPersonaId = null) } }) {
                            Text("跟随当前对话")
                        }
                    },
                )
            }
        }
        item {
            OutlinedTextField(query, { query = it }, label = { Text("搜索身份名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
        }
        item {
            CardGroup {
                item(
                    onClick = { selectPersona(null) },
                    leadingContent = { PersonaAvatar(library.user.name, library.user.avatar) },
                    headlineContent = { Text("旧版用户资料 · ${library.user.name}") },
                    supportingContent = {
                        Text(if (session?.personaId == null && session != null) "本段对话使用中 · 身份设定为空" else "保留原昵称与头像 · 身份设定为空")
                    },
                    trailingContent = { TextButton(onClick = { onEdit(Editing("user", library.user)) }) { Text("编辑") } },
                )
            }
        }
        if (personas.isEmpty()) item {
            Text(if (library.personas.isEmpty()) "创建身份后，可分别填写名称、头像与身份设定。" else "没有匹配的身份。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        items(personas, key = { it.id }) { persona ->
            val useCount = references[persona.id] ?: 0
            CardGroup {
                item(
                    onClick = { selectPersona(persona.id) },
                    leadingContent = { PersonaAvatar(persona.name, persona.avatar) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(persona.name.ifBlank { "未命名身份" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (session?.personaId == persona.id) PersonaBadge("本段对话")
                            if (defaultId == persona.id) PersonaBadge("新对话默认")
                        }
                    },
                    supportingContent = {
                        Text("${if (persona.prompt.isBlank()) "未填写身份设定" else "已填写身份设定"} · $useCount 个对话或收藏引用",
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        PersonaRowMenu(
                            isDefault = library.selectedPersonaId == persona.id,
                            useCount = useCount,
                            onEdit = { onEdit(Editing("persona", persona)) },
                            onDefault = { onUpdate { it.copy(selectedPersonaId = persona.id) } },
                            onDelete = { pendingDelete = persona },
                        )
                    },
                )
            }
        }
        item {
            Text("{{user.name}} 读取所选身份名称；{{user.prompt}} 读取身份设定。身份设定只在提示词引用时发送。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    pendingDelete?.let { persona ->
        val canDelete = (references[persona.id] ?: 0) == 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除用户身份？") },
            text = { Text("删除“${persona.name}”。仍被对话或收藏使用的身份，需要先解除关联。") },
            confirmButton = {
                TextButton(enabled = canDelete, onClick = {
                    onUpdate { current -> current.copy(
                        personas = current.personas.filterNot { it.id == persona.id },
                        selectedPersonaId = current.selectedPersonaId.takeUnless { it == persona.id },
                    ) }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun PersonaEditor(
    initial: UserPersona,
    library: Library,
    onSave: (UserPersona) -> Unit,
    onBack: () -> Unit,
    saving: Boolean = false,
) {
    var persona by remember(initial.id) { mutableStateOf(initial) }
    var avatarOpen by remember(initial.id) { mutableStateOf(false) }
    var avatarBusy by remember(initial.id) { mutableStateOf(false) }
    val useCount = library.sessions.count { it.personaId == initial.id } + library.savedChats.count { it.snapshot.personaId == initial.id }
    EditorPage("用户身份", onBack, { onSave(persona.copy(name = persona.name.trim().ifBlank { "用户" })) },
        dirty = persona != initial, saving = saving || avatarBusy) {
        TextFieldLine("身份名称（{{user.name}}）", persona.name, { persona = persona.copy(name = it) })
        CardGroup {
            item(
                onClick = { avatarOpen = !avatarOpen },
                leadingContent = { PersonaAvatar(persona.name, persona.avatar) },
                headlineContent = { Text("头像") },
                supportingContent = { Text("点击更换或移除头像") },
            )
        }
        if (avatarOpen) CharacterAvatarEditor(CharacterCard(id = "persona-avatar:${persona.id}", name = persona.name, avatar = persona.avatar),
            { avatarBusy = it }) { persona = persona.copy(avatar = it) }
        TextFieldLine("身份设定（{{user.prompt}}）", persona.prompt, { persona = persona.copy(prompt = it) }, minLines = 5)
        Text("在调用的提示词中引用 {{user.prompt}}，才会把这段设定发送给模型。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (useCount > 0) Text("$useCount 个对话或收藏引用此身份；修改后的资料供这些对话后续读取，已有消息的称呼保留。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonaAvatar(name: String, avatar: AvatarImage?) {
    UIAvatar(name = name, value = avatar?.let { Avatar.Image("data:${it.mediaType};base64,${it.base64}") } ?: Avatar.Dummy,
        modifier = Modifier.size(40.dp))
}

@Composable
private fun PersonaBadge(label: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

@Composable
private fun PersonaRowMenu(isDefault: Boolean, useCount: Int, onEdit: () -> Unit, onDefault: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(HugeIcons.MoreVertical, contentDescription = "身份操作") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("编辑") }, onClick = { open = false; onEdit() })
            DropdownMenuItem(text = { Text(if (isDefault) "已设为新对话默认" else "设为新对话默认") },
                enabled = !isDefault, onClick = { open = false; onDefault() })
            DropdownMenuItem(text = { Text(if (useCount > 0) "被 $useCount 个对话或收藏使用" else "删除") },
                enabled = useCount == 0, onClick = { open = false; onDelete() })
        }
    }
}
