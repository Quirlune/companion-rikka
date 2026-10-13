package dev.gray.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A lifting-panel affordance, distinct from the right arrow used for full-page navigation. */
@Composable
internal fun EditorSheetButton(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "打开${title}面板" },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.44f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)) {
                val tint = MaterialTheme.colorScheme.primary
                Canvas(Modifier.padding(9.dp).size(24.dp)) {
                    val unit = size.width / 24f
                    val stroke = 1.5f * unit
                    drawRoundRect(tint, Offset(3f * unit, 11f * unit), Size(18f * unit, 11f * unit),
                        CornerRadius(3f * unit), style = Stroke(stroke))
                    drawLine(tint, Offset(12f * unit, 15f * unit), Offset(12f * unit, 3f * unit), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(8f * unit, 7f * unit), Offset(12f * unit, 3f * unit), stroke, StrokeCap.Round)
                    drawLine(tint, Offset(12f * unit, 3f * unit), Offset(16f * unit, 7f * unit), stroke, StrokeCap.Round)
                }
            }
        }
    }
}

/** The caller owns the resource draft; closing this panel never publishes or discards it. */
@Composable
internal fun EditorModalSheet(
    title: String,
    subtitle: String? = null,
    minimumHeight: Dp = 0.dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Do not feed offset-dependent top inset consumption back into the expanded anchor height.
    val topSafePadding = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = minimumHeight, max = 760.dp).padding(top = topSafePadding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            HorizontalDivider()
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}
