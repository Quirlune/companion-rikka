package me.rerere.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

/** Core sessions are projected to these display rows; the original row renderer remains authoritative. */
data class CompanionConversationEntry(val conversation: Conversation, val timeLabel: String)

@Composable
fun ColumnScope.CompanionConversationList(
    entries: List<CompanionConversationEntry>,
    currentId: Uuid?,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit,
    onRegenerateTitle: (Conversation) -> Unit,
    onSave: (Conversation) -> Unit,
    onRename: (Conversation) -> Unit,
    onPin: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy/M/d") }
    val pinned = remember(entries) { entries.filter { it.conversation.isPinned } }
    val grouped = remember(entries, today, zone) {
        entries.filterNot { it.conversation.isPinned }
            .groupBy { it.conversation.updateAt.atZone(zone).toLocalDate() }
    }
    var hasScrolledToCurrent by remember(currentId) { mutableStateOf(false) }
    LaunchedEffect(currentId, entries, hasScrolledToCurrent) {
        if (hasScrolledToCurrent || currentId == null) return@LaunchedEffect
        val pinnedIndex = pinned.indexOfFirst { it.conversation.id == currentId }
        var currentIndex = if (pinned.isEmpty()) 0 else pinned.size + 1
        var found = pinnedIndex >= 0
        if (found) {
            currentIndex = pinnedIndex + 1
        } else {
            for (rows in grouped.values) {
                val index = rows.indexOfFirst { it.conversation.id == currentId }
                if (index >= 0) {
                    currentIndex += index + 1
                    found = true
                    break
                }
                currentIndex += rows.size + 1
            }
        }
        if (found) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentIndex }) listState.scrollToItem(currentIndex)
            hasScrolledToCurrent = true
        }
    }
    LazyColumn(state = listState, modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entries.isEmpty()) item {
            Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Text(stringResource(R.string.chat_page_no_conversations), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(16.dp))
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "pinned_header") { PinnedHeader(modifier = Modifier.animateItem()) }
            items(pinned, key = { it.conversation.id.toString() }) { row ->
                ConversationItem(
                    conversation = row.conversation,
                    selected = row.conversation.id == currentId,
                    loading = false,
                    timeLabel = row.timeLabel,
                    onClick = onClick,
                    onDelete = onDelete,
                    companionActions = CompanionConversationActions(onSave, onRename, onPin, onRegenerateTitle),
                    modifier = Modifier.animateItem(),
                )
            }
        }
        grouped.forEach { (date, rows) ->
            item(key = "date_$date") {
                DateHeaderItem(label = when (date) {
                    today -> "今天"
                    today.minusDays(1) -> "昨天"
                    else -> date.format(dateFormat)
                }, modifier = Modifier.animateItem())
            }
            items(rows, key = { it.conversation.id.toString() }) { row ->
                ConversationItem(
                    conversation = row.conversation,
                    selected = row.conversation.id == currentId,
                    loading = false,
                    timeLabel = row.timeLabel,
                    onClick = onClick,
                    onDelete = onDelete,
                    companionActions = CompanionConversationActions(onSave, onRename, onPin, onRegenerateTitle),
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

private class CompanionConversationActions(
    val onSave: (Conversation) -> Unit,
    val onRename: (Conversation) -> Unit,
    val onPin: (Conversation) -> Unit,
    val onRegenerateTitle: (Conversation) -> Unit,
)

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {}
) {
    var hasScrolledToCurrent by remember(current.id) { mutableStateOf(false) }

    LaunchedEffect(current.id, conversations.itemCount, hasScrolledToCurrent) {
        if (hasScrolledToCurrent) return@LaunchedEffect
        val currentIndex = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == current.id
        }
        if (currentIndex >= 0) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
            if (!isVisible) {
                listState.scrollToItem(currentIndex)
            }
            hasScrolledToCurrent = true
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversations.itemCount == 0) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = stringResource(id = R.string.chat_page_no_conversations),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { item ->
                when (item) {
                    is ConversationListItem.DateHeader -> "date_${item.date}"
                    is ConversationListItem.PinnedHeader -> "pinned_header"
                    is ConversationListItem.Item -> item.conversation.id.toString()
                }
            }
        ) { index ->
            when (val item = conversations[index]) {
                is ConversationListItem.DateHeader -> {
                    DateHeaderItem(
                        label = item.label,
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.PinnedHeader -> {
                    PinnedHeader(
                        modifier = Modifier.animateItem()
                    )
                }

                is ConversationListItem.Item -> {
                    ConversationItem(
                        conversation = item.conversation,
                        selected = item.conversation.id == current.id,
                        loading = item.conversation.id in conversationJobs,
                        onClick = onClick,
                        onDelete = onDelete,
                        onRegenerateTitle = onRegenerateTitle,
                        onPin = onPin,
                        onMoveToAssistant = onMoveToAssistant,
                        onMoveToFolder = onMoveToFolder,
                        modifier = Modifier.animateItem()
                    )
                }

                null -> {
                    // Placeholder for loading state
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = HugeIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    timeLabel: String? = null,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onMoveToAssistant: (Conversation) -> Unit = {},
    onMoveToFolder: (Conversation) -> Unit = {},
    companionActions: CompanionConversationActions? = null,
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    } else {
        Color.Transparent
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50f))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { onClick(conversation) },
                onLongClick = {
                    // Also clear chat input focus when the drawer is permanently visible.
                    focusManager.clearFocus(force = true)
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (timeLabel == null) Modifier else Modifier.weight(1f),
            )
            if (timeLabel == null) Spacer(Modifier.weight(1f))

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = HugeIcons.Pin,
                    contentDescription = "Pinned",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics {
                            contentDescription = "Loading"
                        }
                )
            }
            if (timeLabel != null) {
                Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat))
                    },
                    onClick = {
                        if (companionActions == null) onPin(conversation)
                        else companionActions.onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin, null)
                    },
                )
                if (companionActions != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_regenerate_title)) },
                        leadingIcon = { Icon(HugeIcons.Refresh01, null) },
                        onClick = {
                            showDropdownMenu = false
                            companionActions.onRegenerateTitle(conversation)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("收藏到文件夹") },
                        leadingIcon = { Icon(HugeIcons.Folder01, null) },
                        onClick = { showDropdownMenu = false; companionActions.onSave(conversation) },
                    )
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = { showDropdownMenu = false; companionActions.onRename(conversation) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = { showDropdownMenu = false; onDelete(conversation) },
                    )
                } else {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Refresh01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_assistant))
                    },
                    onClick = {
                        onMoveToAssistant(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Forward02, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.chat_page_move_to_folder))
                    },
                    onClick = {
                        onMoveToFolder(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Folder01, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(HugeIcons.Delete01, null)
                    }
                )
                }
            }
        }
    }
}
