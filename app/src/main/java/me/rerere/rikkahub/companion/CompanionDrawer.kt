package me.rerere.rikkahub.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.AvatarImage
import dev.gray.companion.core.Library
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.core.Session
import dev.gray.companion.core.userName
import dev.gray.companion.ui.ThreeHourlyHeatmap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ai.AssistantPickerDisplay
import me.rerere.rikkahub.ui.pages.chat.CompanionConversationEntry
import me.rerere.rikkahub.ui.pages.chat.CompanionConversationList
import me.rerere.rikkahub.ui.pages.chat.DrawerAction
import me.rerere.rikkahub.ui.pages.chat.DrawerActions
import me.rerere.rikkahub.ui.pages.chat.DrawerNicknameDialog
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
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
    onEditCharacter: (String) -> Unit,
    onSettings: () -> Unit,
    onUsage: () -> Unit,
    onEditUser: () -> Unit,
    onSaveChat: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onPinSession: (String) -> Unit,
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    onRenameUser: (String) -> Unit,
    usageError: String? = null,
) {
    var nameEditorOpen by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var renameSession by remember { mutableStateOf<Session?>(null) }
    var deleteSession by remember { mutableStateOf<Session?>(null) }
    val listState = rememberLazyListState()
    val selected = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    val userName = selected?.let(library::userName) ?: library.user.name
    val currentCharacter = library.characters.firstOrNull { it.id == selected?.characterId }
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
    val visibleSessions = remember(library.sessions) {
        library.sessions.sortedByDescending { it.updatedAt }
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
            avatar = settings.displaySetting.userAvatar,
            onAvatarUpdate = { avatar ->
                onUpdateSettings(settings.copy(displaySetting = settings.displaySetting.copy(userAvatar = avatar)))
            },
            onEditName = {
                nameDraft = userName
                nameEditorOpen = true
            },
        )
        DrawerActions(
            onSearch = onOpenSearch,
            onHistory = onOpenFavorites,
            historyLabel = "收藏夹",
        )
        CompanionConversationList(
            entries = entries,
            currentId = selected?.id?.let(::stableUuid),
            listState = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            onClick = { sessionByConversationId[it.id]?.let { session -> onSelectSession(session.id) } },
            onSave = { sessionByConversationId[it.id]?.let { session -> onSaveChat(session.id) } },
            onRename = { sessionByConversationId[it.id]?.let { session -> renameSession = session } },
            onRegenerateTitle = { sessionByConversationId[it.id]?.let { session -> onRegenerateTitle(session.id) } },
            onPin = { sessionByConversationId[it.id]?.let { session -> onPinSession(session.id) } },
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
    if (nameEditorOpen) {
        DrawerNicknameDialog(
            value = nameDraft,
            onValueChange = { nameDraft = it },
            onSave = { onRenameUser(nameDraft); nameEditorOpen = false },
            onDismiss = { nameEditorOpen = false },
        )
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
}

private fun AvatarImage?.toOriginalAvatar(): Avatar = when (this) {
    null -> Avatar.Dummy
    else -> Avatar.Image("data:" + mediaType + ";base64," + base64)
}
