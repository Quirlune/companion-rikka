package dev.gray.companion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.gray.companion.core.IllustrationCore
import java.io.File

/** A generated file already checked against the app-private illustration directory. */
internal data class IllustrationBodyImage(
    val paragraphId: String,
    val file: File,
    val requestUrl: String,
    val localRoot: File,
)

/** Native Markdown presentation with display-only image anchors between BODY paragraphs. */
@Composable
internal fun IllustratedMarkdownBody(markdown: String, images: List<IllustrationBodyImage>) {
    val paragraphs = remember(markdown) { IllustrationCore.paragraphs(markdown) }
    val imagesByAnchor = remember(images) { images.groupBy { it.paragraphId } }
    if (paragraphs.isEmpty()) {
        BodyMarkdownBlock(markdown)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paragraphs.forEach { paragraph ->
            key(paragraph.id) { BodyMarkdownBlock(paragraph.text) }
            imagesByAnchor[paragraph.id].orEmpty().forEach { image ->
                key(image.requestUrl) {
                    val transitionState = remember(image.requestUrl) {
                        MutableTransitionState(false).apply { targetState = true }
                    }
                    AnimatedVisibility(
                        visibleState = transitionState,
                        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 16 },
                    ) {
                        AsyncImage(
                            model = image.file,
                            contentDescription = "正文插图",
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.08f to Color.Black,
                                            0.92f to Color.Black,
                                            1f to Color.Transparent,
                                        ),
                                        blendMode = BlendMode.DstIn,
                                    )
                                },
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
        }
    }
}
