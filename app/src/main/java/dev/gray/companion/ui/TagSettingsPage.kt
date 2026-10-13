package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

@Composable
internal fun TagSettingsPage(library: Library, onSave: (List<PromptTag>) -> Unit, onBack: () -> Unit, saving: Boolean = false) {
    var tags by remember(library.promptTags) { mutableStateOf(library.promptTags) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var pendingTag by remember { mutableStateOf<PromptTag?>(null) }
    EditorPage("标签", onBack, { onSave(tags) }, dirty = tags != library.promptTags, saving = saving) {
        tags.forEach { tag -> key(tag.id) {
            EditorTitleRow("${tag.name} / ${tag.slug}", { renameId = tag.id })
        } }
        OutlinedButton(onClick = {
            val name = nextDisplayName(tags.map { it.name }, "标签")
            val slug = nextEditorSlug(tags.map { it.slug }, "tag")
            pendingTag = PromptTag(name = name, slug = slug)
        }) { Text("创建标签") }
    }
    pendingTag?.let { tag ->
        NamingDialog("创建标签", tag.name, tag.slug, tags.map { it.name }, tags.map { it.slug },
            { pendingTag = null }) { name, slug ->
            tags = tags + tag.copy(name = name, slug = slug)
            pendingTag = null
        }
    }
    renameId?.let { id -> tags.firstOrNull { it.id == id }?.let { tag ->
        NamingDialog("标签名称", tag.name, tag.slug,
            tags.filterNot { it.id == id }.map { it.name }, tags.filterNot { it.id == id }.map { it.slug },
            { renameId = null }) { name, slug ->
            tags = tags.map { if (it.id == id) it.copy(name = name, slug = slug) else it }
            renameId = null
        }
    } }
}
