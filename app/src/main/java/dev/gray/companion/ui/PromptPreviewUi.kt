package dev.gray.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.AppController
import dev.gray.companion.core.PromptPreview
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.pages.setting.SettingPageLayout
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** Compile an input against the active session without sending a model request. */
@Composable
internal fun PromptPreviewPage(controller: AppController) {
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<PromptPreview?>(null) }
    var compiling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingPageLayout(title = "提示词预览") {
        item {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextFieldLine("本轮输入", input, { input = it }, minLines = 3)
                Button(onClick = {
                    compiling = true
                    scope.launch { try { result = controller.preview(input) } finally { compiling = false } }
                }, enabled = !compiling) { Text(if (compiling) "组装中" else "查看提示词") }
            }
        }
        result?.let { preview ->
            itemsIndexed(preview.messages) { index, message ->
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = { Text("${index + 1} · ${message.role.name}") },
                        supportingContent = { SelectionContainer { Text(message.text, Modifier.fillMaxWidth()) } },
                    )
                }
            }
            if (preview.messages.isEmpty()) item { Text("没有可预览的消息", Modifier.padding(16.dp)) }
            itemsIndexed(preview.issues) { _, issue ->
                Text(issue.message, Modifier.padding(horizontal = 16.dp),
                    color = if (issue.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
