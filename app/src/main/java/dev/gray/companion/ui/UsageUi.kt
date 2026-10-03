package dev.gray.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.core.UsageAggregation
import dev.gray.companion.core.UsageStatus
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import me.rerere.rikkahub.ui.pages.stats.HeatmapCell
import me.rerere.rikkahub.ui.pages.stats.StatsPageLayout
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

private const val heatmapMaximumTokens = 10_000_000L

@Composable
internal fun ThreeHourlyHeatmap(records: List<ModelUsageRecord>, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val buckets = remember(records, now) { UsageAggregation.rolling48Hours(records, now) }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.minimumInteractiveComponentSize().semantics {
            contentDescription = "最近48小时的模型用量，每格3小时，由淡到深对应0至10M token。点击查看每日用量。"
        }) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("48h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repeat(2) { row -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(8) { column ->
                    val bucket = buckets[row * 8 + column]
                    val total = bucket.totalTokens
                    val alpha = if (bucket.callCount == 0 || total == null || total == 0L) 0f
                        else 0.18f + 0.82f * (total.toDouble() / heatmapMaximumTokens).coerceIn(0.0, 1.0).toFloat()
                    Box(contentAlignment = Alignment.Center) {
                        HeatmapCell(alpha = alpha, sizeDp = 8)
                        if (bucket.unknownCalls > 0)
                            Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraSmall))
                    }
                }
            } }
        }
    }
}

@Composable
internal fun UsageDetailScreen(records: List<ModelUsageRecord>, now: Long, days: Int, onDays: (Int) -> Unit, error: String?) {
    val zone = ZoneId.systemDefault()
    val daily = remember(records, now, days, zone) { UsageAggregation.daily(records, now, days, zone) }
    val visible = remember(records, daily, now) { records.filter { it.startedAt in daily.first().startedAt..now } }
    var selectedStart by rememberSaveable(days) { mutableStateOf<Long?>(null) }
    val selected = daily.firstOrNull { it.startedAt == selectedStart } ?: daily.last()
    val nextDay = daily.getOrNull(daily.indexOf(selected) + 1)?.startedAt
    val selectedCalls = remember(visible, selected.startedAt, nextDay) {
        visible.filter { it.startedAt >= selected.startedAt && (nextDay == null || it.startedAt < nextDay) }.sortedByDescending { it.startedAt }
    }
    val modelCalls = remember(selectedCalls) { selectedCalls.groupBy { it.profileId to it.model } }
    val dateLabel = Instant.ofEpochMilli(selected.startedAt).atZone(zone).toLocalDate().format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    StatsPageLayout(title = "模型用量") { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7, 30, 90).forEach { option ->
                    FilterChip(selected = days == option, onClick = { onDays(option) }, label = { Text("$option 天") })
                }
            } }
            if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
            else {
                item { UsageCard {
                    Text("近 $days 天", style = MaterialTheme.typography.titleMedium)
                    Text(reportedTokens(visible), style = MaterialTheme.typography.headlineMedium)
                    Text("${visible.size} 次请求 · 总 token", style = MaterialTheme.typography.bodySmall)
                    UnknownUsageNote(visible)
                } }
                item { UsageCard {
                    Text("每日用量", style = MaterialTheme.typography.titleMedium)
                    Text("点击柱形查看当天，左右滑动查看其他日期。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DailyUsageChart(daily, selected.startedAt, zone) { selectedStart = it }
                    if (visible.isEmpty()) Text("这个期间还没有请求记录。", style = MaterialTheme.typography.bodySmall)
                } }
                item { UsageCard {
                    Text(dateLabel, style = MaterialTheme.typography.titleMedium)
                    Text(reportedTokens(selectedCalls), style = MaterialTheme.typography.headlineSmall)
                    Text("输入 ${reportedTokens(selectedCalls) { it.promptTokens }} · 输出 ${reportedTokens(selectedCalls) { it.completionTokens }}")
                    Text("${selectedCalls.size} 次请求 · 成功 ${selectedCalls.count { it.status == UsageStatus.SUCCESS }} · 失败 ${selectedCalls.count { it.status == UsageStatus.FAILED }} · 取消 ${selectedCalls.count { it.status == UsageStatus.CANCELLED }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    UnknownUsageNote(selectedCalls)
                } }
                if (modelCalls.isNotEmpty()) {
                    item { Text("当天按模型", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
                    modelCalls.forEach { (identity, calls) -> item { UsageCard {
                        Text(identity.second.ifBlank { identity.first }, style = MaterialTheme.typography.titleSmall)
                        Text("${calls.size} 次 · ${reportedTokens(calls)} token", style = MaterialTheme.typography.bodySmall)
                        UnknownUsageNote(calls)
                    } } }
                    item { Text("当天请求", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
                    items(selectedCalls, key = { it.id }) { call -> UsageCard {
                        Text("${call.model.ifBlank { call.profileId }} · ${statusLabel(call.status)}", style = MaterialTheme.typography.titleSmall)
                        Text("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(call.startedAt))} · ${((call.finishedAt - call.startedAt).coerceAtLeast(0L) / 1000.0)} 秒",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("输入 ${call.promptTokens?.toString() ?: "未报告"} · 输出 ${call.completionTokens?.toString() ?: "未报告"} · 总计 ${call.totalTokens?.toString() ?: "未报告"}", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                item { Text("本机请求的已报告用量，包含正文、分支、历史处理、标题和重试。未报告的 token 不估算，也不代表提供商账户账单。",
                    Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun UsageCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun UnknownUsageNote(records: List<ModelUsageRecord>) {
    val unknown = records.count { it.totalTokens == null }
    if (unknown > 0) Text("$unknown 次请求未报告总 token，显示值为已报告部分。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun reportedTokens(records: List<ModelUsageRecord>, value: (ModelUsageRecord) -> Long? = { it.totalTokens }): String {
    val known = records.mapNotNull(value)
    return if (records.isNotEmpty() && known.isEmpty()) "未报告" else formatUsageTokens(known.sum())
}

internal fun formatUsageTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun statusLabel(status: UsageStatus): String = when (status) {
    UsageStatus.SUCCESS -> "成功"
    UsageStatus.FAILED -> "失败"
    UsageStatus.CANCELLED -> "已取消"
}
