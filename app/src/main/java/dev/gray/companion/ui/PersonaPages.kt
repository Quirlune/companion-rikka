package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar

/** Identity management stays separate from explicitly switching the active chat. */
@Composable
internal fun PersonasScreen(
    library: Library,
    onEdit: (Editing) -> Unit,
    onUpdate: ((Library) -> Library) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    var chatPersonaId by remember(session?.id, session?.personaId) { mutableStateOf(session?.personaId) }
    var pendingDelete by remember { mutableStateOf<UserPersona?>(null) }
    val references = remember(library) { library.personas.associate { it.id to library.personaReferences(it.id).size } }
    val chatChoices = listOf<String?>(null).map { it to "旧版用户资料 · ${library.user.name}" } +
        library.personas.map { it.id to it.name.ifBlank { "未命名身份" } }
    val defaultChoices = listOf<String?>(null).map { it to "跟随当前聊天身份（无聊天时使用旧版资料）" } +
        library.personas.map { it.id to it.name.ifBlank { "未命名身份" } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("用户身份", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { onEdit(Editing(EditorKind.PERSONA, newId())) }) { Text("新建身份") }
            }
        }
        item {
            Text("点选身份可编辑名称、头像与设定。角色绑定的身份优先作为新聊天身份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ChoiceLine("未绑定角色时的新聊天默认", library.selectedPersonaId, defaultChoices) { id ->
                onUpdate { it.copy(selectedPersonaId = id) }
            }
        }
        if (session != null) item {
            Text("当前聊天身份", style = MaterialTheme.typography.titleMedium)
            ChoiceLine("选择身份", chatPersonaId, chatChoices) { chatPersonaId = it }
            Button(onClick = { onUpdate { current -> current.selectPersonaForSession(session.id, chatPersonaId) } },
                enabled = chatPersonaId != session.personaId) { Text("用于本聊天") }
            Text("只切换这段聊天；已有消息保留原称呼。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            CardGroup {
                item(
                    onClick = { onEdit(Editing(EditorKind.USER, "user")) },
                    leadingContent = { PersonaAvatar(library.user.name, library.user.avatar) },
                    headlineContent = { Text("旧版用户资料 · ${library.user.name}") },
                    supportingContent = { Text("点击编辑原有昵称与头像") },
                )
            }
        }
        if (library.personas.isEmpty()) item {
            Text("创建身份后，可分别填写名称、头像与身份设定。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp))
        }
        items(library.personas, key = { it.id }) { persona ->
            val useCount = references[persona.id] ?: 0
            CardGroup {
                item(
                    onClick = { onEdit(Editing(EditorKind.PERSONA, persona.id)) },
                    leadingContent = { PersonaAvatar(persona.name, persona.avatar) },
                    headlineContent = { Text(persona.name.ifBlank { "未命名身份" }) },
                    supportingContent = {
                        Text(listOfNotNull(
                            if (session?.personaId == persona.id) "本聊天使用中" else null,
                            if (library.selectedPersonaId == persona.id) "新聊天默认" else null,
                            if (useCount > 0) "$useCount 处引用" else null,
                        ).joinToString(" · ").ifBlank { if (persona.prompt.isBlank()) "未填写设定" else "已填写设定" },
                            style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        TextButton(onClick = { pendingDelete = persona }, enabled = useCount == 0 && library.selectedPersonaId != persona.id) {
                            Text("删除")
                        }
                    },
                )
            }
        }
    }
    pendingDelete?.let { persona ->
        val canDelete = (references[persona.id] ?: 0) == 0 && library.selectedPersonaId != persona.id
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除用户身份？") },
            text = { Text("删除“${persona.name}”？被角色、对话或收藏引用时，需要先解除关联。") },
            confirmButton = {
                TextButton(enabled = canDelete, onClick = {
                    onUpdate { current ->
                        if (current.personaReferences(persona.id).isNotEmpty()) current
                        else current.copy(personas = current.personas.filterNot { it.id == persona.id })
                    }
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
    val baseline = remember(initial.id) { MacroNaming.normalize(initial, library.personas.filterNot { it.id == initial.id }.map { it.slug }).let { value ->
        if (value.name.isBlank() || library.personas.none { it.id == value.id })
            value.copy(name = nextDisplayName(library.personas.filterNot { it.id == value.id }.map { it.name }, "用户身份"))
        else value
    } }
    var persona by remember(initial.id) { mutableStateOf(baseline) }
    var avatarOpen by remember(initial.id) { mutableStateOf(false) }
    var avatarBusy by remember(initial.id) { mutableStateOf(false) }
    val useCount = library.personaReferences(initial.id).size
    EditorPage("用户身份", onBack, { onSave(persona.copy(name = persona.name.trim())) },
        dirty = persona != baseline, saving = saving || avatarBusy) {
        TextFieldLine("名称（{{user.name}}）", persona.name, { persona = persona.copy(name = it) })
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
        if (useCount > 0) Text("$useCount 处角色、对话或收藏引用此身份；修改后的资料供后续读取，已有消息的称呼保留。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PersonaAvatar(name: String, avatar: AvatarImage?) {
    UIAvatar(name = name, value = avatar?.let { Avatar.Image("data:${it.mediaType};base64,${it.base64}") } ?: Avatar.Dummy,
        modifier = Modifier.size(40.dp))
}
