package me.rerere.rikkahub.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.ChatFolder
import dev.gray.companion.core.AvatarImage
import dev.gray.companion.core.Library
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.core.Session
import dev.gray.companion.core.userName
import dev.gray.companion.core.effectivePersona
import dev.gray.companion.ui.ThreeHourlyHeatmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.components.ai.AssistantPickerDisplay
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.pages.chat.CompanionConversationEntry
import me.rerere.rikkahub.ui.pages.chat.CompanionConversationList
import me.rerere.rikkahub.ui.pages.chat.FolderBar
import me.rerere.rikkahub.ui.pages.chat.DrawerAction
import me.rerere.rikkahub.ui.pages.chat.DrawerUserHeader
import me.rerere.rikkahub.ui.pages.chat.OriginalDrawerFrame
import kotlin.uuid.Uuid

/** Adapts Companion records and callbacks to the original drawer components. */
@Composable
fun CompanionDrawer(
    library: Library,
    usage: List<ModelUsageRecord>,
    usageAsOf: Long,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onSettings: () -> Unit,
    onMainCalls: () -> Unit,
    onUsage: () -> Unit,
    onEditUser: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCopySession: (String) -> Unit,
    onMoveSession: (String, String?) -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    settings: Settings,
    usageError: String? = null,
) {
    var renameSession by remember { mutableStateOf<Session?>(null) }
    var deleteSession by remember { mutableStateOf<Session?>(null) }
    var moveSession by remember { mutableStateOf<Session?>(null) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var createFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<ChatFolder?>(null) }
    val foldersByUuid = remember(library.chatFolders) { library.chatFolders.associateBy { stableUuid(it.id) } }
    val folders = remember(library.chatFolders) {
        library.chatFolders.map { Folder(id = stableUuid(it.id), assistantId = stableUuid("__companion_folders__"), name = it.name) }
    }
    val effectiveFolderId = selectedFolderId?.takeIf { id -> library.chatFolders.any { it.id == id } }
    val listState = rememberLazyListState()
    val selected = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    val userName = selected?.let(library::userName) ?: library.user.name
    val userAvatar = selected?.let(library::effectivePersona)?.avatar ?: library.user.avatar
    val currentCharacter = library.characters.firstOrNull { it.id == selected?.characterId }
    val currentMain = library.mainCalls.firstOrNull { it.id == selected?.mainCallId }
    val currentMainName = currentMain?.name?.ifBlank { "未命名主调用" }
        ?: if (selected?.mainCallId?.isNotBlank() == true) "主调用不可用" else "选择主调用"
    val characterAssistants = remember(library.characters) {
        library.characters.map { card ->
            Assistant(id = stableUuid(card.id), name = card.name, avatar = card.avatar.toOriginalAvatar())
        }
    }
    val characterByUuid = remember(library.characters) {
        library.characters.associateBy { stableUuid(it.id) }
    }
    val currentAssistant = characterAssistants.firstOrNull { it.id == currentCharacter?.id?.let(::stableUuid) }
        ?: Assistant(id = stableUuid("__companion_empty_character__"))
    val pickerSettings = remember(settings, characterAssistants, currentAssistant.id) {
        settings.copy(
            assistantId = currentAssistant.id,
            assistants = characterAssistants,
            assistantTags = emptyList(),
        )
    }
    val visibleSessions = remember(library.sessions, effectiveFolderId) {
        library.sessions.filter { effectiveFolderId == null || it.folderId == effectiveFolderId }.sortedByDescending { it.updatedAt }
    }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val zone = ZoneId.systemDefault()
    val entries = remember(visibleSessions, zone) {
        visibleSessions.map { session ->
            CompanionConversationEntry(
                conversation = Conversation.ofId(stableUuid(session.id)).copy(
                    assistantId = stableUuid(session.characterId.orEmpty()),
                    title = session.title,
                    isPinned = session.isPinned,
                    updateAt = Instant.ofEpochMilli(session.updatedAt),
                ),
                timeLabel = Instant.ofEpochMilli(session.updatedAt).atZone(zone).format(timeFormat),
            )
        }
    }
    val sessionByConversationId = remember(library.sessions) {
        library.sessions.associateBy { stableUuid(it.id) }
    }
    OriginalDrawerFrame {
        DrawerUserHeader(
            name = userName,
            avatar = userAvatar.toOriginalAvatar(),
            onAvatarUpdate = {},
            onEditName = onEditUser,
            onOpenUserHeader = onEditUser,
        )
        NavigationDrawerItem(
            label = { Text("创建新聊天") },
            icon = { Icon(HugeIcons.MessageAdd01, null) },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        FolderBar(
            folders = folders,
            selectedFolderId = effectiveFolderId?.let(::stableUuid),
            onSelect = { selectedFolderId = it?.let { id -> foldersByUuid[id]?.id } },
            onCreate = { createFolder = true },
            onRename = { renameFolder = foldersByUuid[it.id] },
            onDelete = { deleteFolder = foldersByUuid[it.id] },
        )
        CompanionConversationList(
            entries = entries,
            currentId = selected?.id?.let(::stableUuid),
            listState = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onClick = { sessionByConversationId[it.id]?.let { session -> onSelectSession(session.id) } },
            onRename = { sessionByConversationId[it.id]?.let { session -> renameSession = session } },
            onCopy = { sessionByConversationId[it.id]?.let { session -> onCopySession(session.id) } },
            onMove = { sessionByConversationId[it.id]?.let { session -> moveSession = session } },
            onDelete = { sessionByConversationId[it.id]?.let { session -> deleteSession = session } },
        )
        AssistantPickerDisplay(
            settings = pickerSettings,
            currentAssistant = currentAssistant,
            onAssistantSelected = { assistant ->
                characterByUuid[assistant.id]?.let { onSelectCharacter(it.id) }
            },
            onEditAssistant = { currentCharacter?.let { onEditCharacter(it.id) } },
            onEditSheetAssistant = { assistant ->
                characterByUuid[assistant.id]?.let { onEditCharacter(it.id) }
            },
            onCreateAssistant = onCreateCharacter,
            showLeadingIcon = false,
            modifier = Modifier.fillMaxWidth(),
        )
        NavigationDrawerItem(
            // Match AssistantPickerDisplay(showLeadingIcon = false) exactly.
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentMainName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    UIAvatar(name = currentMainName, value = currentMain?.avatar.toOriginalAvatar(), onClick = onMainCalls)
                }
            },
            onClick = onMainCalls,
            selected = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            DrawerAction(
                icon = { Icon(HugeIcons.Settings03, contentDescription = null) },
                label = { Text(androidx.compose.ui.res.stringResource(R.string.settings)) },
                onClick = onSettings,
            )
            Column(horizontalAlignment = Alignment.End) {
                if (usageError != null) Text(
                    usageError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                ThreeHourlyHeatmap(records = usage, now = usageAsOf, onClick = onUsage)
            }
        }
    }
    renameSession?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameSession = null },
            title = { Text("重命名对话") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                onRenameSession(session.id, title.trim())
                renameSession = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameSession = null }) { Text("取消") } },
        )
    }
    deleteSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteSession = null },
            title = { Text("删除对话") },
            text = { Text("确定删除“" + session.title + "”？") },
            confirmButton = { TextButton(onClick = { onDeleteSession(session.id); deleteSession = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteSession = null }) { Text("取消") } },
        )
    }
    moveSession?.let { session ->
        AlertDialog(
            onDismissRequest = { moveSession = null },
            title = { Text("移动到文件夹") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { onMoveSession(session.id, null); moveSession = null }) {
                        Text("移出文件夹")
                    }
                    library.chatFolders.forEach { folder ->
                        NavigationDrawerItem(
                            label = { Text(folder.name) },
                            selected = session.folderId == folder.id,
                            onClick = { onMoveSession(session.id, folder.id); moveSession = null },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { createFolder = true }) { Text("新建文件夹") } },
            dismissButton = { TextButton(onClick = { moveSession = null }) { Text("取消") } },
        )
    }
    if (createFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createFolder = false },
            title = { Text("新建文件夹") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                onCreateFolder(name.trim())
                createFolder = false
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { createFolder = false }) { Text("取消") } },
        )
    }
    renameFolder?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { renameFolder = null },
            title = { Text("命名文件夹") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                onRenameFolder(folder.id, name.trim())
                renameFolder = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameFolder = null }) { Text("取消") } },
        )
    }
    deleteFolder?.let { folder ->
        val hasSavedChats = library.savedChats.any { it.folderId == folder.id }
        AlertDialog(
            onDismissRequest = { deleteFolder = null },
            title = { Text("删除文件夹") },
            text = { Text(if (hasSavedChats) "此文件夹含有收藏，请先在收藏夹中移走或删除收藏，再删除文件夹。" else "删除“${folder.name}”后，聊天会移回全部聊天。") },
            confirmButton = { TextButton(onClick = {
                if (!hasSavedChats) onDeleteFolder(folder.id)
                deleteFolder = null
            }) { Text(if (hasSavedChats) "知道了" else "删除") } },
            dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("取消") } },
        )
    }
}

private fun AvatarImage?.toOriginalAvatar(): Avatar = when (this) {
    null -> Avatar.Dummy
    else -> Avatar.Image("data:" + mediaType + ";base64," + base64)
}
