package dev.gray.companion.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.ChatFolder
import dev.gray.companion.core.Library
import dev.gray.companion.core.SavedChat
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SaveChatDialog(library: Library, sessionId: String, onSave: (String, String) -> Unit, onOpenFolders: () -> Unit, onDismiss: () -> Unit) {
    var selected by remember(sessionId, library.chatFolders) { mutableStateOf(library.chatFolders.firstOrNull()?.id) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("收藏这段对话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (library.chatFolders.isEmpty()) Text("还没有收藏文件夹，请先创建。")
            else ChoiceLine("保存到", selected, library.chatFolders.map { it.id to it.name }) { selected = it }
        }
    }, confirmButton = {
        if (selected == null) TextButton(onClick = { onDismiss(); onOpenFolders() }) { Text("创建文件夹") }
        else TextButton(onClick = { onSave(sessionId, selected!!); onDismiss() }) { Text("保存独立副本") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun ChatArchiveScreen(library: Library, onBack: () -> Unit, onUpdate: ((Library) -> Library) -> Unit, onRestore: (String) -> Unit) {
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var renameFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var deleteFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var deleteSaved by remember { mutableStateOf<SavedChat?>(null) }
    var selectedSaved by remember { mutableStateOf<SavedChat?>(null) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("收藏夹", onBack)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(folderName, { folderName = it }, label = { Text("新文件夹名称") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = {
                    val name = folderName.trim()
                    if (name.isNotEmpty()) { onUpdate { it.copy(chatFolders = it.chatFolders + ChatFolder(name = name)) }; folderName = "" }
                }, enabled = folderName.isNotBlank()) { Text("新建") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = selectedFolder == null, onClick = { selectedFolder = null }, label = { Text("全部") })
                library.chatFolders.forEach { folder -> FilterChip(selected = selectedFolder == folder.id, onClick = { selectedFolder = folder.id }, label = { Text(folder.name) }) }
            }
            library.chatFolders.firstOrNull { it.id == selectedFolder }?.let { folder -> Row {
                TextButton(onClick = { renameFolder = folder }) { Text("重命名文件夹") }
                TextButton(onClick = { deleteFolder = folder }) { Text("删除文件夹") }
            } }
        }
        val snapshots = library.savedChats.filter { selectedFolder == null || it.folderId == selectedFolder }.sortedByDescending { it.savedAt }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (snapshots.isEmpty()) item { Text("这里还没有收藏的对话。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(snapshots, key = { it.id }) { saved ->
                ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(saved.snapshot.title.ifBlank { "未命名对话" }, style = MaterialTheme.typography.titleMedium)
                    Text("${library.chatFolders.firstOrNull { it.id == saved.folderId }?.name ?: "文件夹已移除"} · ${displayTime(saved.savedAt)} · ${saved.snapshot.messages.size} 条消息", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { selectedSaved = saved }) { Text("查看副本") }
                        TextButton(onClick = { onRestore(saved.id) }) { Text("打开为新对话") }
                        TextButton(onClick = { deleteSaved = saved }) { Text("删除") }
                    }
                } }
            }
        }
    }
    renameFolder?.let { folder ->
        var draft by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(onDismissRequest = { renameFolder = null }, title = { Text("重命名文件夹") }, text = { OutlinedTextField(draft, { draft = it }, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { onUpdate { lib -> lib.copy(chatFolders = lib.chatFolders.map { if (it.id == folder.id) it.copy(name = draft.trim()) else it }) }; renameFolder = null }, enabled = draft.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renameFolder = null }) { Text("取消") } })
    }
    deleteFolder?.let { folder ->
        val count = library.savedChats.count { it.folderId == folder.id }
        AlertDialog(onDismissRequest = { deleteFolder = null }, title = { Text("删除「${folder.name}」？") }, text = { Text("将同时删除其中 $count 个收藏副本，普通聊天不受影响。") },
            confirmButton = { TextButton(onClick = {
                onUpdate { lib -> lib.copy(chatFolders = lib.chatFolders.filterNot { it.id == folder.id }, savedChats = lib.savedChats.filterNot { it.folderId == folder.id }) }
                if (selectedFolder == folder.id) selectedFolder = null
                deleteFolder = null
            }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteFolder = null }) { Text("取消") } })
    }
    deleteSaved?.let { saved -> AlertDialog(onDismissRequest = { deleteSaved = null }, title = { Text("删除收藏副本？") }, text = { Text("只删除此副本，普通聊天不受影响。") },
        confirmButton = { TextButton(onClick = { onUpdate { lib -> lib.copy(savedChats = lib.savedChats.filterNot { it.id == saved.id }) }; deleteSaved = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteSaved = null }) { Text("取消") } }) }
    selectedSaved?.let { saved -> AlertDialog(onDismissRequest = { selectedSaved = null }, title = { Text(saved.snapshot.title.ifBlank { "收藏副本" }) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            saved.snapshot.messages.forEach { message -> Text("${if (message.role == dev.gray.companion.core.Role.USER) "你" else "助手"} · ${displayTime(message.createdAt)}\n${message.text}") }
            if (saved.snapshot.messages.isEmpty()) Text("这个副本没有消息。")
        }
    }, confirmButton = { TextButton(onClick = { selectedSaved = null }) { Text("关闭") } }) }
}

private fun displayTime(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
