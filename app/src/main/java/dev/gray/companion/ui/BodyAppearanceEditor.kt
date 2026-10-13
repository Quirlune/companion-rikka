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
    var colorsExpanded by remember { mutableStateOf(false) }
    CallEditorPage("正文外观", template != original, onBack,
        onSave = { if (issue == null) onSave() else dev.gray.companion.AppEventLog.record(
            "正文外观", issue, dev.gray.companion.AppLogLevel.ERROR) },
        onRegisterBack = onRegisterBack) {
        EditorDestinationRow("正文颜色 · 全局显示偏好") {
            colorsExpanded = !colorsExpanded
        }
        if (colorsExpanded) {
            BodyColorsSettingsContent()
            HorizontalDivider()
        }
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
            supportingText = issue?.let { message -> { Text(message) } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(BodyAppearanceTemplates.DEFAULT) }) { Text("恢复默认") }
            TextButton(onClick = { onChange(bodyAppearanceExample) }) { Text("填入示例") }
        }
        Text("文字与图片分宽：示例只用 width: 88% 收窄文字；插图保留卡片全宽。要调整文字宽度，修改排除 .companion-generated-illustration 的文字选择器，不要收窄两者共用的外层容器。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("预览", style = MaterialTheme.typography.titleSmall)
        if (issue == null) {
            Box(Modifier.fillMaxWidth()) {
                StyledBody(template = template, markdown = bodyAppearanceSampleMarkdown, messageId = "body-appearance-preview")
            }
        } else Text("修正模板后显示预览。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

internal val bodyAppearanceExample = """
    <style>
      .body-note {
        box-sizing: border-box;
        width: 100%;
        margin: 0 auto;
        padding: 16px 0;
        border: 1px solid #a8a1b3;
        border-radius: 16px;
        line-height: 1.8;
      }
      /* Only text is narrowed. Generated illustrations keep the card width. */
      .body-note .companion-markdown > :not(.companion-generated-illustration),
      .body-note > header, .body-note > footer, .body-note > hr {
        box-sizing: border-box;
        width: 88%;
        max-width: 680px;
        margin-left: auto;
        margin-right: auto;
        overflow-wrap: anywhere;
      }
      .body-note p:first-child { margin-top: 0; }
      .body-note p:last-child { margin-bottom: 0; }
      .story-heading { text-align: center; font-weight: bold; }
      .story-ending { text-align: center; font-size: 12px; opacity: .6; }
      .body-note hr { border: 0; border-top: 1px solid #a8a1b3; margin: 16px auto; }
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

    “慢慢来，我在这里。”

    > 留一点空白，让文字更好读。
""".trimIndent()
