package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.Library
import dev.gray.companion.core.MacroTile
import dev.gray.companion.core.MacroTilePlacement
import dev.gray.companion.core.MacroTileKind
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.stats.StatsPageLayout
import me.rerere.rikkahub.utils.plus

/** Display configuration remains independent of the producer's trigger and action editor. */
@Composable
internal fun TileSettingsPage(library: Library, onEdit: (String, String) -> Unit) {
    StatsPageLayout("宏显示") { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("选择所属的运行方案，设置聊天正文下方的当前状态或独立宏侧栏。显示只读取宏，不调用模型。", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall) }
            item { Text("主调用", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall) }
            items(library.mainCalls, key = { "main:${it.id}" }) { main -> CardGroup(Modifier.padding(horizontal = 8.dp)) {
                item(onClick = { onEdit("main", main.id) },
                    headlineContent = { Text(main.name.ifBlank { "未命名主调用" }) },
                    supportingContent = { Text(tileSummary(main.tiles)) })
            } }
            item { Text("调用组", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall) }
            items(library.groups, key = { "group:${it.id}" }) { group -> CardGroup(Modifier.padding(horizontal = 8.dp)) {
                item(onClick = { onEdit("group", group.id) },
                    headlineContent = { Text(group.name.ifBlank { "未命名调用组" }) },
                    supportingContent = { Text(tileSummary(group.tiles)) })
            } }
            if (library.mainCalls.isEmpty() && library.groups.isEmpty()) item { Text("先创建主调用或调用组。", Modifier.padding(8.dp)) }
        }
    }
}

@Composable
internal fun TileOwnerEditor(kind: String, id: String, library: Library, onSave: (List<MacroTile>) -> Unit, onBack: () -> Unit, saving: Boolean) {
    val main = library.mainCalls.firstOrNull { it.id == id }.takeIf { kind == "main" }
    val group = library.groups.firstOrNull { it.id == id }.takeIf { kind == "group" }
    val original = remember(kind, id) { main?.tiles ?: group?.tiles.orEmpty() }
    var tiles by remember(kind, id) { mutableStateOf(original) }
    val references = remember(main, group, library) { promptReferenceChoices(main?.workflows ?: group?.workflows.orEmpty(), library, main, group) }
    EditorPage("宏显示 · ${main?.name ?: group?.name.orEmpty()}", onBack, { onSave(tiles) }, dirty = tiles != original, saving = saving) {
        MacroTilesEditor(tiles, references) { tiles = it }
    }
}

private fun tileSummary(tiles: List<MacroTile>): String {
    val chat = tiles.count { it.placement == MacroTilePlacement.CHAT }
    val sidebar = tiles.count { it.placement == MacroTilePlacement.SIDEBAR }
    val status = tiles.count { it.kind == MacroTileKind.STATUS }
    return "聊天 $chat · 侧栏 $sidebar · 状态 $status"
}
