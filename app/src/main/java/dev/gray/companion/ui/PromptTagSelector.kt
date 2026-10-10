package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.MacroNaming
import dev.gray.companion.core.PromptTag
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tag01

/** The menu remains open while toggling several stable tag IDs. */
@Composable
internal fun PromptTagSelector(catalog: List<PromptTag>, tagIds: List<String>, compact: Boolean = false, onChange: (List<String>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = tagIds.mapNotNull { id -> catalog.firstOrNull { it.id == id } }
    val missing = tagIds.filter { id -> catalog.none { it.id == id } }
    Column(if (compact) Modifier else Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) Text("标签", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            if (compact) IconButton(onClick = { expanded = true }) {
                Icon(HugeIcons.Tag01, contentDescription = "选择标签，已选 ${tagIds.size} 项",
                    modifier = Modifier.size(20.dp), tint = when {
                        missing.isNotEmpty() -> MaterialTheme.colorScheme.error
                        tagIds.isNotEmpty() -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
            } else {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text((selected.joinToString("、") { "${it.name} / ${it.slug}" } +
                    if (missing.isNotEmpty()) " · ${missing.size} 个标签未找到" else "").ifBlank { "选择标签" })
            }
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                if (compact && missing.isNotEmpty()) DropdownMenuItem(
                    text = { Text("${missing.size} 个标签未找到，原关联保留", color = MaterialTheme.colorScheme.error) },
                    enabled = false, onClick = {})
                if (catalog.isEmpty()) DropdownMenuItem(text = { Text("暂无标签，请在设置中创建") }, enabled = false, onClick = {})
                catalog.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text("${tag.name} / ${tag.slug}") },
                        leadingIcon = { Checkbox(checked = tag.id in tagIds, onCheckedChange = null) },
                        onClick = { onChange(MacroNaming.toggleTag(tagIds, tag.id)) },
                    )
                }
            }
        }
        if (!compact && missing.isNotEmpty()) Text("未找到的标签仍保留在此草稿中：${missing.joinToString("、")}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
