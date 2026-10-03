package dev.gray.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.UsageBucket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Native bars and scrolling share the original statistics palette and typography. */
@Composable
internal fun DailyUsageChart(buckets: List<UsageBucket>, selectedStart: Long, zone: ZoneId, onSelect: (Long) -> Unit) {
    val maximum = buckets.mapNotNull { it.totalTokens }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val plotHeight = 160.dp
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.surfaceVariant
    val unknown = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fontScale = LocalDensity.current.fontScale
    val scroll = rememberLazyListState()
    LaunchedEffect(buckets.first().startedAt, buckets.size) { scroll.scrollToItem(buckets.lastIndex) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(48.dp * fontScale).height(plotHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Text(formatUsageTokens(if (maximum == 1L && buckets.none { (it.totalTokens ?: 0L) > 0 }) 0L else maximum),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(Modifier.weight(1f), state = scroll) {
            items(buckets, key = { it.startedAt }) { bucket ->
                val isSelected = selectedStart == bucket.startedAt
                val day = Instant.ofEpochMilli(bucket.startedAt).atZone(zone).toLocalDate()
                val total = bucket.totalTokens
                val level = ((total ?: 0L).toDouble() / maximum).toFloat().coerceIn(0f, 1f)
                Column(Modifier.width(48.dp * fontScale).clickable(role = Role.Button) { onSelect(bucket.startedAt) }
                    .semantics(mergeDescendants = true) {
                        selected = isSelected
                        contentDescription = "$day，${if (total == null) "用量未报告" else "$total token"}，${bucket.callCount}次请求，${bucket.unknownCalls}次未报告用量"
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth().height(plotHeight).drawBehind {
                        for (part in 0..2) {
                            val y = if (part == 0) 0f else size.height * part / 2f - 1.dp.toPx()
                            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                    }, contentAlignment = Alignment.BottomCenter) {
                        if (total == null) Text("?", style = MaterialTheme.typography.titleMedium, color = unknown)
                        else Box(Modifier.width(24.dp).height((plotHeight * level).coerceAtLeast(if (total > 0L) 2.dp else 1.dp))
                            .background(if (total == 0L) muted else if (isSelected) primary else primary.copy(alpha = 0.45f), MaterialTheme.shapes.extraSmall))
                    }
                    Text(day.format(DateTimeFormatter.ofPattern("M/d")), style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Box(Modifier.size(4.dp).background(if (isSelected) primary else MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraSmall))
                }
            }
        }
    }
}
