package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.BodyAppearanceTemplates

@Composable
internal fun BodyAppearanceEditor(
    original: String,
    template: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onRegisterBack: ((() -> Unit) -> Unit)? = null,
) {
    val issue = remember(template) { BodyAppearanceMarkup.issue(template) }
    var saveIssue by remember { mutableStateOf<String?>(null) }
    CallEditorPage("正文外观", template != original, onBack,
        onSave = { if (issue == null) onSave() else saveIssue = issue },
        onRegisterBack = onRegisterBack) {
        Text("在同一个模板中编写 HTML 与 <style> CSS，将 {{正文}} 放在正文显示的位置，仅保留一处。",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = template,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("正文模板") },
            placeholder = { Text(BodyAppearanceTemplates.DEFAULT) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            minLines = 8,
            maxLines = 18,
            isError = issue != null,
            supportingText = { Text(issue ?: "仅写 {{正文}} 或留空，使用默认正文样式。") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(BodyAppearanceTemplates.DEFAULT) }) { Text("恢复默认") }
            TextButton(onClick = { onChange(bodyAppearanceExample) }) { Text("填入示例") }
        }
        Text("只调整助手正文的显示，不改动消息内容。此处的 {{正文}} 是本地占位符，不解析项目宏。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("预览", style = MaterialTheme.typography.titleSmall)
        Text("使用示例正文；保存本页后，再保存主调用以应用。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (issue == null) {
            Box(Modifier.fillMaxWidth()) {
                StyledBody(template = template, markdown = bodyAppearanceSampleMarkdown, messageId = "body-appearance-preview")
            }
        } else Text("修正模板后显示预览。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    saveIssue?.let { message ->
        AlertDialog(onDismissRequest = { saveIssue = null }, title = { Text("模板需要修正") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveIssue = null }) { Text("继续编辑") } })
    }
}

private val bodyAppearanceExample = """
    <style>
      .body-note {
        box-sizing: border-box;
        width: 92%;
        max-width: 680px;
        margin: 0 auto;
        padding: 16px;
        border: 1px solid #a8a1b3;
        border-radius: 16px;
        line-height: 1.8;
      }
      .body-note p:first-child { margin-top: 0; }
      .body-note p:last-child { margin-bottom: 0; }
      .story-heading { text-align: center; font-weight: bold; }
      .story-ending { text-align: center; font-size: 12px; opacity: .6; }
      .body-note hr { border: 0; border-top: 1px solid #a8a1b3; margin: 16px 0; }
    </style>
    <article class="body-note">
      <header class="story-heading">故事正文</header>
      <hr>
      {{正文}}
      <hr>
      <footer class="story-ending">未完待续</footer>
    </article>
""".trimIndent()

private val bodyAppearanceSampleMarkdown = """
    今天也慢慢来吧。这里是**正文外观**的预览。

    - 一段普通文字
    - *轻轻强调*，还有 `行内代码`

    > 留一点空白，让文字更好读。
""".trimIndent()
