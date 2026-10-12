package dev.gray.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.gray.companion.core.BodyAppearanceTemplates
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
internal fun StyledBody(template: String, markdown: String, messageId: String) {
    val streamRequest = LocalStreamPresentationRequest.current
    val streamFrame = rememberStreamPresentation(markdown, streamRequest)
    val presentedMarkdown = streamFrame?.text ?: markdown
    val issue = remember(template) { BodyAppearanceMarkup.issue(template) }
    if (!BodyAppearanceTemplates.isCustom(template) || issue != null) {
        Column {
            if (issue != null) Text("正文外观：$issue", color = MaterialTheme.colorScheme.error)
            BodyMarkdownBlock(markdown)
        }
        return
    }
    val colors = LocalSettings.current.displaySetting
    val shell = remember(template, colors.companionBoldColor, colors.companionItalicColor, colors.companionQuoteColor) {
        runCatching { BodyAppearanceMarkup.incrementalShell(template, colors) }
    }
    val content = remember(presentedMarkdown) { runCatching { BodyAppearanceMarkup.markdownFragment(presentedMarkdown) } }
    if (shell.isFailure || content.isFailure) {
        Column {
            Text("正文外观无法呈现，已恢复普通正文。", color = MaterialTheme.colorScheme.error)
            BodyMarkdownBlock(markdown)
        }
        return
    }
    val style = LocalTextStyle.current
    InertHtmlView(
        fragment = content.getOrThrow(),
        foreground = style.color.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
        background = Color.Transparent,
        fontSize = style.fontSize.value.takeIf { it.isFinite() }?.toInt() ?: 16,
        tileId = messageId,
        incremental = true,
        incrementalShell = shell.getOrThrow(),
        incrementalTargetId = BodyAppearanceMarkup.incrementalContentId,
        presentationKey = streamFrame?.identity?.stableKey,
        presentationStreaming = streamFrame?.isStreaming == true,
    )
}
