package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** Session settings are a local draft; typing neither writes SQLite nor cancels generation. */
@Composable
internal fun SessionConfigScreen(
    library: Library,
    onBack: () -> Unit,
    onSave: ((Library) -> Library) -> Unit,
    saving: Boolean = false,
    onManagePersonas: (() -> Unit)? = null,
) {
    val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    if (session == null) {
        Column { SettingsHeader("当前对话配置", onBack); Text("请先创建对话。", Modifier.padding(16.dp)) }
        return
    }
    val initial = remember(session.id) { session }
    var draft by remember(session.id) { mutableStateOf(initial) }
    var newKey by remember(session.id) { mutableStateOf("") }
    val character = library.characters.firstOrNull { it.id == draft.characterId }
    val loadedPackages = remember(library, draft) { library.loadedPromptPackages(draft) }
    val loadedGroups = remember(library, draft) { library.loadedCallGroups(draft) }

    EditorPage("当前对话配置", onBack, onSave = {
        val changedState = draft.state.filter { (key, value) -> initial.state[key] != value }
        val removedState = initial.state.keys - draft.state.keys
        val personaChanged = draft.personaId != initial.personaId
        onSave { current ->
            val withPersona = if (personaChanged && current.sessions.any { it.id == draft.id && it.personaId != draft.personaId })
                current.selectPersonaForSession(draft.id, draft.personaId) else current
            withPersona.copy(sessions = withPersona.sessions.map { latest ->
                if (latest.id != draft.id) latest else latest.copy(
                    title = draft.title.ifBlank { "新对话" },
                    mainCallId = draft.mainCallId,
                    packageIds = draft.packageIds,
                    groupIds = draft.groupIds,
                    personaId = if (personaChanged) draft.personaId else latest.personaId,
                    state = (latest.state - removedState) + changedState,
                    updatedAt = System.currentTimeMillis(),
                )
            })
        }
    }, dirty = draft != initial, saving = saving) {
        TextFieldLine("标题", draft.title, { draft = draft.copy(title = it) })
        CardGroup {
            item(
                headlineContent = { Text(character?.name?.ifBlank { "未命名角色" } ?: "未选择角色") },
            )
        }
        val personaChoices = listOf<String?>(null).map { it to "旧版用户资料 · ${library.user.name}" } +
            library.personas.map { it.id to it.name.ifBlank { "未命名身份" } } +
            if (draft.personaId != null && library.personas.none { it.id == draft.personaId })
                listOf(draft.personaId to "身份已不可用：${draft.personaId}") else emptyList()
        ChoiceLine("本段对话的用户身份", draft.personaId, personaChoices) { draft = draft.copy(personaId = it) }
        onManagePersonas?.let { manage -> TextButton(onClick = manage) { Text("管理用户身份") } }
        ChoiceLine("主调用", draft.mainCallId, listOf("" to "无") + library.mainCalls.map { it.id to it.name.ifBlank { "未命名主调用" } }) {
            draft = draft.copy(mainCallId = it)
        }
        EditorSection("额外提示词包", "额外选择 ${draft.packageIds.size} 项 · 当前可用 ${loadedPackages.size} 项") {
            ResourceBindingList(library.packages.map { it.id to it.name.ifBlank { it.namespace } }, draft.packageIds) {
                draft = draft.copy(packageIds = it)
            }
        }
        EditorSection("额外调用组", "额外选择 ${draft.groupIds.size} 项 · 当前可用 ${loadedGroups.size} 项") {
            Text("主调用绑定的组保持加载；这里增加可选的组。", style = MaterialTheme.typography.bodySmall)
            ResourceBindingList(library.groups.map { it.id to it.name.ifBlank { "未命名组" } }, draft.groupIds) {
                draft = draft.copy(groupIds = it)
            }
        }
        EditorSection("会话状态", "${draft.state.size} 项") {
            draft.state.forEach { (name, value) -> key(name) {
                TextFieldLine(name, value, { draft = draft.copy(state = draft.state + (name to it)) })
                TextButton(onClick = { draft = draft.copy(state = draft.state - name) }) { Text("删除 $name") }
            } }
            TextFieldLine("新状态名称", newKey, { newKey = it })
            val canAdd = newKey.isNotBlank() && newKey.trim() !in draft.state
            OutlinedButton(onClick = {
                draft = draft.copy(state = draft.state + (newKey.trim() to ""))
                newKey = ""
            }, enabled = canAdd) { Text("添加状态") }
            if (newKey.trim() in draft.state) Text("这个状态已存在，可在上方编辑。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
