package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.agent.AgentController
import dev.gray.companion.agent.AgentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private data class AgentWorkspaceSummary(
    val id: String,
    val label: String,
    val revision: Long,
    val state: String,
    val previewRevision: Long?,
    val valid: Boolean,
    val changes: String,
    val issues: String,
)

@Composable
internal fun AgentWorkbenchTab(
    agent: AgentController,
    selected: AgentSession?,
    onOpenWorkbench: () -> Unit,
    canOpenWorkbench: Boolean,
    onNotice: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var workspaces by remember(selected?.id) { mutableStateOf<List<AgentWorkspaceSummary>>(emptyList()) }
    var listLoaded by remember(selected?.id) { mutableStateOf(false) }
    var request by remember(selected?.id) { mutableStateOf("{\"op\":\"describe\"}") }
    var output by remember(selected?.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<JsonObject?>(null) }
    val pretty = remember { Json { prettyPrint = true } }

    fun show(reply: JsonObject) { output = pretty.encodeToString(JsonObject.serializer(), reply) }
    fun reload() {
        if (selected == null || busy) return
        scope.launch {
            busy = true
            try {
                val reply = agent.configuration(buildJsonObject { put("op", "list") })
                if (reply.text("status") == "OK") {
                    workspaces = (reply["data"] as? JsonArray).orEmpty().mapNotNull { entry ->
                        val item = entry as? JsonObject ?: return@mapNotNull null
                        AgentWorkspaceSummary(
                            id = item.text("id") ?: return@mapNotNull null,
                            label = item.text("label").orEmpty(),
                            revision = (item["revision"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0,
                            state = item.text("state").orEmpty(),
                            previewRevision = (item["previewRevision"] as? JsonPrimitive)?.content?.toLongOrNull(),
                            valid = (item["valid"] as? JsonPrimitive)?.content == "true",
                            changes = item["changes"]?.toString().orEmpty(),
                            issues = item["issues"]?.toString().orEmpty(),
                        )
                    }
                    listLoaded = true
                } else { listLoaded = false; show(reply) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { listLoaded = false; onNotice("工作台列表暂时不可用。") }
            finally { busy = false }
        }
    }
    fun execute(command: JsonObject) {
        if (busy) return
        scope.launch {
            busy = true
            if (command.text("op") in listOf("open", "stage", "field", "preview", "commit", "discard")) listLoaded = false
            try {
                val reply = agent.configuration(command)
                show(reply)
                if (command.text("op") in listOf("open", "stage", "field", "preview", "commit", "discard")) {
                    busy = false
                    reload()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onNotice("工作台操作未完成，请重读草稿状态。") }
            finally { busy = false }
        }
    }
    LaunchedEffect(selected?.id) { reload() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Agent 工作台", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = ::reload, enabled = selected != null && !busy) { Text("刷新") }
            TextButton(onClick = onOpenWorkbench, enabled = canOpenWorkbench) { Text("打开原生工作台") }
        }
        if (selected == null) Text("先选择 Agent 聊天。")
        else {
            if (workspaces.isEmpty()) Text("暂无关联草稿")
            workspaces.forEach { workspace ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(workspace.label.ifBlank { "配置草稿" }, style = MaterialTheme.typography.titleSmall)
                        Text("${workspace.state} · 版本 ${workspace.revision} · ${if (workspace.state == "COMMITTED") "已应用" else if (workspace.state == "DISCARDED") "已放弃" else if (workspace.previewRevision == workspace.revision && workspace.valid) "预览通过" else "待预览或需修正"}",
                            style = MaterialTheme.typography.bodySmall)
                        if (workspace.changes.isNotBlank()) SelectionContainer { Text("累计差异：${workspace.changes}", style = MaterialTheme.typography.bodySmall) }
                        if (workspace.issues.isNotBlank()) SelectionContainer { Text("检查：${workspace.issues}", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { execute(configCommand("preview", workspace)) }, enabled = !busy && workspace.state == "OPEN") { Text("预览") }
                            TextButton(onClick = { confirm = configCommand("commit", workspace) },
                                enabled = !busy && workspace.state == "OPEN" && workspace.valid && workspace.previewRevision == workspace.revision) { Text("应用…") }
                            TextButton(onClick = { confirm = configCommand("discard", workspace) }, enabled = !busy && workspace.state == "OPEN") { Text("放弃…") }
                        }
                    }
                }
            }
            val restorable = if (listLoaded && !busy) selected.savedDrafts.filter { saved ->
                workspaces.none { it.id == saved.workspaceId }
            } else emptyList()
            if (restorable.isNotEmpty()) {
                HorizontalDivider()
                Text("可恢复草稿", style = MaterialTheme.typography.titleSmall)
                restorable.forEach { saved -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(saved.label.ifBlank { "已保存草稿" }, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (!busy) scope.launch {
                                busy = true
                                listLoaded = false
                                try {
                                    show(agent.restoreDraft(saved.workspaceId))
                                    busy = false; reload()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { onNotice("草稿恢复未完成，请检查资源版本。") }
                                finally { busy = false }
                            }
                        }, enabled = !busy) { Text("恢复") }
                    }
                } }
            }
            HorizontalDivider()
            Text("配置工具", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(request, { request = it }, label = { Text("JSON 请求") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10)
            Button(onClick = {
                val parsed = try { Json.parseToJsonElement(request) as? JsonObject } catch (_: Exception) { null }
                if (parsed == null) onNotice("请输入 JSON 对象。")
                else if (parsed.text("op") in listOf("commit", "discard")) confirm = parsed
                else execute(parsed)
            }, enabled = !busy) { Text("执行工具") }
            if (output.isNotBlank()) SelectionContainer { Text(output, style = MaterialTheme.typography.bodySmall) }
        }
    }
    confirm?.let { command -> AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(if (command.text("op") == "commit") "应用配置草稿？" else "放弃配置草稿？") },
        text = { Text(if (command.text("op") == "commit") "通过当前版本预览的累计修改将写入资源库。若前台聊天忙，草稿会保留。" else "草稿及其累计修改将被移除。") },
        confirmButton = { TextButton(onClick = { confirm = null; execute(command) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
    ) }
}

private fun configCommand(op: String, workspace: AgentWorkspaceSummary) = buildJsonObject {
    put("op", op); put("workspaceId", workspace.id); put("expectedRevision", workspace.revision)
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
