package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.MacroNaming

/** A display label and a reference alias are edited together; legacy addresses live elsewhere. */
@Composable
internal fun NamingDialog(
    label: String,
    initialName: String,
    initialSlug: String,
    siblingNames: Collection<String>,
    siblingSlugs: Collection<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName, initialSlug) { mutableStateOf(initialName) }
    var slug by remember(initialName, initialSlug) { mutableStateOf(initialSlug) }
    val nameError = MacroNaming.nameProblem(name.trim(), siblingNames)
    val slugError = MacroNaming.slugProblem(slug.trim(), siblingSlugs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名称") },
                        singleLine = true, isError = nameError != null, modifier = Modifier.weight(1f))
                    Text("/", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(slug, { slug = it }, label = { Text("英文 slug") },
                        singleLine = true, isError = slugError != null, modifier = Modifier.weight(1f))
                }
                if (nameError != null) Text(nameError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (slugError != null) Text(slugError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("英文标识用小写字母开头，只含小写字母、数字、_ 或 -。旧宏地址保持原样。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), slug.trim()) }, enabled = nameError == null && slugError == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun nextDisplayName(existing: Collection<String>, prefix: String): String =
    generateSequence(1) { it + 1 }.map { "$prefix $it" }.first { it !in existing }

internal fun nextEditorSlug(existing: Collection<String>, stem: String): String =
    generateSequence(1) { it + 1 }.map { "${stem}_$it" }.first { it !in existing }

@Composable
internal fun LegacyAddressLine(address: String) {
    SelectionContainer {
        Text("引用地址 · $address", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
