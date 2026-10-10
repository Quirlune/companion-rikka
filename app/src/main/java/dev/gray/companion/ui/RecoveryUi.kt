package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.RecoveryFailure
import dev.gray.companion.core.RecoveryFailureKind
import dev.gray.companion.core.RunRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Small footer for the latest saved run. The retry, skip, and manual decisions stay in the host. */
@Composable
internal fun RecoveryFooter(
    recovery: RunRecovery?,
    busy: Boolean,
    onRetry: suspend (RecoveryFailure, String?) -> Boolean,
    onSkip: suspend (RecoveryFailure) -> Boolean,
    onManual: suspend (RecoveryFailure, String, String?) -> Boolean,
    onEditPrompt: (RecoveryFailure) -> Unit,
    onFinish: suspend () -> Boolean,
) {
    val failures = recovery?.failures.orEmpty().filterNot { busy && it.provisional }
    if (recovery == null) return
    if (failures.isEmpty()) {
        if (!busy) PausedRunExit(recovery, onFinish)
        return
    }

    var showDetails by remember(recovery.id) { mutableStateOf(false) }
    var selectedKey by remember(recovery.id) { mutableStateOf(failures.first().nodeKey) }
    val selected = failures.firstOrNull { it.nodeKey == selectedKey } ?: failures.first()
    var candidate by remember(recovery.id, selected.nodeKey) { mutableStateOf("") }
    var correction by remember(recovery.id, selected.nodeKey) { mutableStateOf("") }
    var showManual by remember(recovery.id, selected.nodeKey) { mutableStateOf(false) }
    var showCorrection by remember(recovery.id, selected.nodeKey) { mutableStateOf(false) }
    var actionPending by remember(recovery.id) { mutableStateOf(false) }
    var actionError by remember(recovery.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runAction(message: String, action: suspend () -> Boolean) {
        if (busy || actionPending) return
        scope.launch {
            actionPending = true
            actionError = null
            try {
                if (action()) showDetails = false else actionError = message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                actionError = failure.message ?: message
            } finally { actionPending = false }
        }
    }

    OutlinedCard(onClick = { showDetails = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${if (busy) "正在处理" else "运行已暂停"} · ${recovery.acceptedCount} 项已保存 · ${failures.size} 项待处理", style = MaterialTheme.typography.labelLarge)
            Text(failures.first().title.ifBlank { recoveryFailureKindLabel(failures.first().kind) } + "：" + failures.first().reason,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("查看恢复选项", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showDetails) AlertDialog(
        onDismissRequest = { if (!actionPending) showDetails = false },
        title = { Text("恢复未完成的运行") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${recovery.acceptedCount} 项结果已保存；${failures.size} 个失败路径等待处理。", style = MaterialTheme.typography.bodyMedium)
                if (failures.size > 1) {
                    failures.forEach { failure ->
                        val selectedCard = failure.nodeKey == selected.nodeKey
                        OutlinedCard(
                            onClick = { selectedKey = failure.nodeKey },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (selectedCard) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(failure.title.ifBlank { "未命名调用" }, style = MaterialTheme.typography.labelLarge)
                                Text("${recoveryFailureKindLabel(failure.kind)} · ${failure.reason}",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected.title.ifBlank { "未命名调用" }, style = MaterialTheme.typography.titleSmall)
                    Text(recoveryFailureKindLabel(selected.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(selected.reason, style = MaterialTheme.typography.bodyMedium)
                            selected.source?.takeIf(String::isNotBlank)?.let { Text("来源：${it.take(800)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("调用 ${selected.workflowId} · 节点 ${selected.nodeId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { showCorrection = !showCorrection }) {
                    Text(if (showCorrection) "收起修正说明" else "添加修正说明（可选）")
                }
                if (showCorrection) OutlinedTextField(
                    value = correction,
                    onValueChange = { correction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("告诉下一次尝试需要修正什么") },
                    minLines = 2,
                )
                if (showManual) {
                    OutlinedTextField(
                        value = candidate,
                        onValueChange = { candidate = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手动候选结果") },
                        supportingText = { Text("提交后会按同一输出格式与显示规则校验。") },
                        minLines = 4,
                    )
                } else TextButton(onClick = { showManual = true }) { Text("输入手动候选") }
                if (actionError != null) Text(actionError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { runAction("重试未完成，请检查提示词或稍后再试。") { onRetry(selected, correction.trim().takeIf(String::isNotEmpty)) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !actionPending,
                ) { Text(if (actionPending) "处理中…" else "重试此调用") }
                if (showManual) Button(
                    onClick = { runAction("候选内容未通过校验，请按提示修正后再提交。") {
                        onManual(selected, candidate, correction.trim().takeIf(String::isNotEmpty))
                    } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = candidate.isNotBlank() && !busy && !actionPending,
                ) { Text("校验候选并继续") }
                TextButton(onClick = { showDetails = false; onEditPrompt(selected) }, enabled = !busy && !actionPending) {
                    Text("编辑此调用的提示词")
                }
                TextButton(
                    onClick = { runAction("跳过未完成，请重试。") { onSkip(selected) } },
                    enabled = !busy && !actionPending,
                ) { Text("跳过失败路径并继续") }
                if (selected.kind == RecoveryFailureKind.CONFLICT) {
                    Text("只会放弃尚未完成的部分，已保存结果会保留。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = { runAction("无法结束本轮，请稍后重试。", onFinish) },
                        enabled = !busy && !actionPending,
                    ) { Text("结束本轮，保留已保存结果") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (!actionPending) showDetails = false }) { Text("关闭") } },
    )
}

/** An interrupted final publication can leave saved receipts without a failed node to retry. */
@Composable
private fun PausedRunExit(recovery: RunRecovery, onFinish: suspend () -> Boolean) {
    var showDialog by remember(recovery.id) { mutableStateOf(false) }
    var pending by remember(recovery.id) { mutableStateOf(false) }
    var error by remember(recovery.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    OutlinedCard(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("运行已暂停 · 等待结束", style = MaterialTheme.typography.labelLarge)
            Text("没有可重试的失败调用，点此查看结束选项。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDialog) AlertDialog(
        onDismissRequest = { if (!pending) showDialog = false },
        title = { Text("结束暂停的运行？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (recovery.regenerating)
                    "这次重新生成尚未发布的新候选及其运行结果会被放弃，原回答与原状态会保留。结束后可以重新生成。"
                else "结束后会清除本轮断点，已经保存到会话的结果会保留，未完成的部分不再继续。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !pending, onClick = {
                if (!pending) {
                    pending = true
                    error = null
                    scope.launch {
                        try {
                            if (onFinish()) showDialog = false else error = "无法结束本轮，请稍后重试。"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            error = "无法结束本轮，请稍后重试。"
                        } finally { pending = false }
                    }
                }
            }) { Text(if (pending) "处理中…" else if (recovery.regenerating) "放弃新候选，保留原回答" else "结束本轮") }
        },
        dismissButton = { TextButton(enabled = !pending, onClick = { showDialog = false }) { Text("暂不结束") } },
    )
}

private fun recoveryFailureKindLabel(kind: RecoveryFailureKind): String = when (kind) {
    RecoveryFailureKind.MODEL_REQUEST -> "模型调用失败"
    RecoveryFailureKind.OUTPUT_FORMAT -> "输出格式失败"
    RecoveryFailureKind.CONFIGURATION -> "配置错误"
    RecoveryFailureKind.ACTION -> "动作执行失败"
    RecoveryFailureKind.PROMPT -> "提示词错误"
    RecoveryFailureKind.CONFLICT -> "恢复冲突"
}
