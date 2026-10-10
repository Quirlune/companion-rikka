package dev.gray.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.gray.companion.core.BodyAppearanceTemplates
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

@Composable
internal fun StyledBody(template: String, markdown: String, messageId: String) {
    val issue = remember(template) { BodyAppearanceMarkup.issue(template) }
    if (!BodyAppearanceTemplates.isCustom(template) || issue != null) {
        Column {
            if (issue != null) Text("正文外观：$issue", color = MaterialTheme.colorScheme.error)
            MarkdownBlock(markdown)
        }
        return
    }
    val rendered = remember(template, markdown) { runCatching { BodyAppearanceMarkup.render(template, markdown) } }
    if (rendered.isFailure) {
        Column {
            Text("正文外观无法呈现，已恢复普通正文。", color = MaterialTheme.colorScheme.error)
            MarkdownBlock(markdown)
        }
        return
    }
    val style = LocalTextStyle.current
    InertHtmlView(
        fragment = rendered.getOrThrow(),
        foreground = style.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
        background = Color.Transparent,
        fontSize = style.fontSize.value.takeIf { it.isFinite() }?.toInt() ?: 16,
        tileId = messageId,
        incremental = true,
    )
}
