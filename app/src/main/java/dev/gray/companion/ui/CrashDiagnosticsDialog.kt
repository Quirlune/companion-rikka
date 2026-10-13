package dev.gray.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.CrashHandler
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CrashDiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries by AppEventLog.entries.collectAsStateWithLifecycle()
    var crashReport by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        crashReport = withContext(Dispatchers.IO) { CrashHandler.diagnosticReport(context.applicationContext) }
    }
    val report = remember(entries, crashReport) {
        buildString {
            append("运行日志\n")
            if (entries.isEmpty()) append("暂无记录\n")
            else entries.asReversed().forEach { append(formatEntry(it)).append('\n') }
            append("\n崩溃诊断\n")
            append(crashReport ?: "正在读取…")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("日志") },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entries.isEmpty()) Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                    entries.asReversed().forEach { entry ->
                        Text(formatEntry(entry), style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                    Text("崩溃诊断", style = MaterialTheme.typography.titleSmall)
                    Text(crashReport ?: "正在读取…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { AppEventLog.clear(); copied = false }, enabled = entries.isNotEmpty()) {
                    Text("清空运行日志")
                }
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("velune 日志", report))
                    copied = true
                }) { Text(if (copied) "已复制" else "复制") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun formatEntry(entry: AppLogEntry): String {
    val level = when (entry.level.name) {
        "ERROR" -> "错误"
        "WARNING" -> "提醒"
        else -> "信息"
    }
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(entry.timeMillis))
    return "$time · $level · ${entry.source}\n${entry.message}"
}
