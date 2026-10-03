package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.gray.companion.core.*

/** Read-only current XML status. No value is attached to or recomputed for a historical message. */
@Composable
internal fun MacroStatusPanel(tile: MacroTile, xml: String, error: String? = null, waiting: Boolean = false) {
    val extracted = remember(tile.fields, xml) { StatusFields.readAll(xml, tile.fields.map { it.key }) }
    val values = tile.fields.map { field -> field to extracted[field.key] }
    val visible = values.filter { (field, value) -> field.key.isNotBlank() && (waiting || !tile.hideWhenEmpty || !value.isNullOrBlank()) }
    if (visible.isEmpty() && error == null && tile.hideWhenEmpty && !waiting) return
    val foreground = tileColor(tile.style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val background = tileColor(tile.style.background) ?: MaterialTheme.colorScheme.surfaceContainerLow
    Surface(shape = RoundedCornerShape(tile.style.cornerRadius.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tile.title.ifBlank { "当前状态" }, style = MaterialTheme.typography.titleSmall, color = foreground)
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (waiting) Text("等待首次生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (visible.isEmpty()) Text("尚无可显示的状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            visible.forEach { (field, value) -> key(field.key) {
                val label = field.title.ifBlank { field.key }
                when (field.kind) {
                    MacroTileKind.PROGRESS -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value.orEmpty().ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = foreground)
                        }
                        val number = value?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        val fraction = number?.takeIf { field.maximum.isFinite() && field.minimum.isFinite() && field.maximum > field.minimum }
                            ?.let { ((it - field.minimum) / (field.maximum - field.minimum)).toFloat().coerceIn(0f, 1f) }
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    MacroTileKind.NUMBER, MacroTileKind.TEXT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        val display = if (field.kind == MacroTileKind.NUMBER && value?.trim()?.toDoubleOrNull()?.isFinite() != true) "—" else value.orEmpty().ifBlank { "—" }
                        Box(Modifier.weight(1.5f)) { SelectionContainer { Text(display, style = MaterialTheme.typography.bodyMedium, color = foreground, fontSize = tile.style.fontSize.sp) } }
                    }
                    MacroTileKind.STATUS, MacroTileKind.HTML -> Unit // Rejected by core validation; old data remains readable.
                }
            } }
        }
    }
}

/** A separate right-side Material panel; the original left history drawer remains available. */
@Composable
internal fun MacroSidebarPanel(library: Library, session: Session, onDismiss: () -> Unit) {
    val items = remember(library, session) { resolveMacroTiles(library, session, MacroTilePlacement.SIDEBAR) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            val panelWidth = minOf(maxWidth * 0.9f, 440.dp)
            Surface(Modifier.width(panelWidth).fillMaxHeight(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("宏侧栏", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (items.isEmpty()) Text("当前没有可显示的宏值。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        items.forEach { item -> key(item.tile.id) {
                            when (item.tile.kind) {
                                MacroTileKind.STATUS -> MacroStatusPanel(item.tile, item.value, item.error, item.waiting)
                                MacroTileKind.HTML -> HtmlMacroDisplay(item)
                                else -> CurrentTile(item)
                            }
                        } }
                    }
                }
            }
        }
    }
}
