package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.ConfigurationImportPreview
import dev.gray.companion.core.ConfigurationEditReceipt
import dev.gray.companion.core.ConfigurationChange
import dev.gray.companion.core.ConfigurationSettingChange
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConfigurationImportSheet(
    preview: ConfigurationImportPreview,
    busy: Boolean,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(preview.label, style = MaterialTheme.typography.titleLarge) }
            item { Text("请检查将要改变的资源与设置，确认后才会写入。", style = MaterialTheme.typography.bodySmall) }
            if (!preview.undoable) item {
                Text("此文件包含聊天或运行记录；可预览并导入，但配置撤回不能回退聊天。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview.changes.isEmpty() && preview.settings.isEmpty()) item {
                Text("无配置资源差异。", style = MaterialTheme.typography.bodyMedium)
            }
            items(preview.changes, key = { "${it.kind}:${it.id}" }) { change -> ConfigurationChangeCard(change) }
            items(preview.settings, key = { "setting:${it.key}" }) { setting -> ConfigurationSettingCard(setting) }
            if (preview.issues.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("检查结果", style = MaterialTheme.typography.titleSmall)
                    preview.issues.forEach { issue ->
                        Text("${issue.path}: ${issue.message}", style = MaterialTheme.typography.bodySmall,
                            color = if (issue.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
                    Button(onClick = onApply, enabled = !busy && preview.issues.none { it.error }) { Text("应用导入") }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationChangeCard(change: ConfigurationChange) {
    var expanded by remember(change.kind, change.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${change.kind} · ${when (change.operation) { "create" -> "新增"; "remove" -> "删除"; else -> "更新" }}",
                style = MaterialTheme.typography.titleSmall)
            Text(change.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (change.changedFields.isNotEmpty()) Text("字段：${change.changedFields.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起前后内容" else "查看前后内容") }
            if (expanded) {
                Text("原内容：${change.before ?: "无"}", style = MaterialTheme.typography.bodySmall)
                Text("新内容：${change.after ?: "无"}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConfigurationSettingCard(setting: ConfigurationSettingChange) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("设置 · ${setting.key}", style = MaterialTheme.typography.titleSmall)
            Text("原值：${setting.before ?: "无"}", style = MaterialTheme.typography.bodySmall)
            Text("新值：${setting.after ?: "无"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ConfigurationUndoPage(receipts: List<ConfigurationEditReceipt>, busy: Boolean, onUndo: (String) -> Unit, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<ConfigurationEditReceipt?>(null) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader("配置撤回", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("仅撤回对应导入改过的配置；若之后又修改了这些项目，会先报告冲突。聊天记录不会回退。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (receipts.isEmpty()) item { Text("还没有可撤回的配置导入。") }
            items(receipts.asReversed(), key = { it.id }) { receipt ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(receipt.label, style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getDateTimeInstance().format(Date(receipt.createdAt)), style = MaterialTheme.typography.bodySmall)
                        Text("${receipt.changes.size} 项资源 · ${receipt.settings.size} 项设置", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selected = receipt }, enabled = !busy) { Text("撤回这次导入") }
                    }
                }
            }
        }
    }
    selected?.let { receipt ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("撤回配置导入？") },
            text = { Text("将撤回“${receipt.label}”涉及的配置。之后的冲突会阻止撤回，聊天不会回退。") },
            confirmButton = { TextButton(onClick = { selected = null; onUndo(receipt.id) }, enabled = !busy) { Text("撤回") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}
