package dev.gray.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gray.companion.AppController
import dev.gray.companion.ConfigurationToolGateway
import dev.gray.companion.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private data class WorkspaceFieldDraftKey(val workspaceId: String, val field: ExtensionFieldRef)
private data class WorkspaceFieldDraft(
    val input: String,
    val setNull: Boolean,
    val sourceRevision: Long,
    val sourceValue: String?,
)

@Composable
internal fun ConfigurationWorkspacePage(
    controller: AppController,
    onBack: () -> Unit,
) {
    val workspaces by controller.configurationWorkspaces.collectAsStateWithLifecycle()
    val openWorkspaces = workspaces.filter { it.status == ConfigurationWorkspaceStatus.OPEN }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val gateway = remember(controller) { ConfigurationToolGateway(controller) }

    var selectedId by remember { mutableStateOf<String?>(null) }
    var newLabel by remember { mutableStateOf("") }
    var commandInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var fieldDrafts by remember { mutableStateOf<Map<WorkspaceFieldDraftKey, WorkspaceFieldDraft>>(emptyMap()) }
    var confirmLeaveFields by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    var operationMessage by remember { mutableStateOf("") }
    var operationIssues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var toolExpanded by remember { mutableStateOf(false) }
    var toolRequest by remember { mutableStateOf("{\"op\":\"describe\"}") }
    var toolResult by remember { mutableStateOf("") }
    var toolBusy by remember { mutableStateOf(false) }

    LaunchedEffect(openWorkspaces) {
        val openIds = openWorkspaces.mapTo(mutableSetOf()) { it.id }
        fieldDrafts = fieldDrafts.filterKeys { it.workspaceId in openIds }
        if (openWorkspaces.none { it.id == selectedId }) selectedId = openWorkspaces.lastOrNull()?.id
    }
    val workspace = openWorkspaces.firstOrNull { it.id == selectedId }

    androidx.activity.compose.BackHandler(enabled = fieldDrafts.isNotEmpty()) {
        confirmLeaveFields = true
    }

    Column(Modifier.fillMaxSize()) {
        SettingsHeader("配置工作台") {
            if (fieldDrafts.isEmpty()) onBack() else confirmLeaveFields = true
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "草稿只保存在当前应用进程中，重启后不会保留。创建、暂存和检查可以在运行期间继续；应用遇到 BUSY 时草稿会保留，稍后可重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = newLabel,
                onValueChange = { newLabel = it },
                label = { Text("新草稿名称（可选）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                enabled = operation == null,
                onClick = {
                    scope.launch {
                        operation = "open"
                        operationIssues = emptyList()
                        try {
                            val result = controller.openConfigurationWorkspace(newLabel.trim().ifBlank { "配置工作台" })
                            operationIssues = result.issues
                            operationMessage = workspaceResultMessage("新建", result.status, result.retryable)
                            result.workspace?.let { created ->
                                selectedId = created.id
                                commandInputs = commandInputs + (created.id to "")
                                newLabel = ""
                            }
                        } finally {
                            operation = null
                        }
                    }
                },
            ) { Text(if (operation == "open") "正在新建…" else "新建隔离草稿") }

            if (openWorkspaces.isNotEmpty()) {
                Text("草稿列表", style = MaterialTheme.typography.titleSmall)
                openWorkspaces.forEach { item ->
                    OutlinedCard(
                        onClick = { selectedId = item.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.label.ifBlank { "配置工作台" }, style = MaterialTheme.typography.titleSmall)
                            Text("版本 ${item.revision} · ${if (item.fixedPreview?.revision == item.revision) "已检查" else "尚未检查"}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (item.id == selectedId) Text("当前草稿", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else {
                Text("还没有草稿。新建后可粘贴一批配置 JSON，在隔离副本中暂存并检查。", style = MaterialTheme.typography.bodyMedium)
            }

            if (operationMessage.isNotBlank()) Text(
                operationMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (operationIssues.any { it.error }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (operationIssues.isNotEmpty()) IssueList(operationIssues)

            if (workspace != null) {
                HorizontalRule()
                WorkspaceEditor(
                    workspace = workspace,
                    commandText = commandInputs[workspace.id].orEmpty(),
                    onCommandTextChange = { text -> commandInputs = commandInputs + (workspace.id to text) },
                    fieldDrafts = fieldDrafts,
                    onFieldDraftChange = { key, draft ->
                        fieldDrafts = if (draft == null) fieldDrafts - key else fieldDrafts + (key to draft)
                    },
                    onFieldDraftStaged = { key, submitted ->
                        if (fieldDrafts[key] == submitted) fieldDrafts = fieldDrafts - key
                    },
                    onClearFieldDrafts = {
                        fieldDrafts = fieldDrafts.filterKeys { it.workspaceId != workspace.id }
                    },
                    controller = controller,
                    operation = operation,
                    onOperation = { name, block, onResult ->
                        if (operation == null) scope.launch {
                            operation = name
                            operationIssues = emptyList()
                            try {
                                val result = block()
                                operationIssues = result.issues
                                operationMessage = workspaceResultMessage(name, result.status, result.retryable)
                                if (name == "暂存" && result.status == "OK")
                                    commandInputs = commandInputs + (workspace.id to "")
                                if (name == "应用" && result.status == "OK")
                                    fieldDrafts = fieldDrafts.filterKeys { it.workspaceId != workspace.id }
                                onResult(result)
                            } finally {
                                operation = null
                            }
                        }
                    },
                    onCopy = { text, message ->
                        operationMessage = if (copyWorkspaceText(context, text)) message else "复制失败。"
                        operationIssues = emptyList()
                    },
                    onDiscard = { confirmDiscard = true },
                    onRunTool = { request ->
                        scope.launch {
                            toolBusy = true
                            toolResult = ""
                            try {
                                toolResult = gateway.execute(request)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                toolResult = "工具请求执行失败。请检查 JSON 与请求内容后重试。"
                            } finally {
                                toolBusy = false
                            }
                        }
                    },
                    toolExpanded = toolExpanded,
                    onToolExpandedChange = { toolExpanded = it },
                    toolRequest = toolRequest,
                    onToolRequestChange = { toolRequest = it },
                    toolResult = toolResult,
                    toolBusy = toolBusy,
                )
            }
        }
    }

    if (confirmDiscard && workspace != null) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("放弃这个草稿？") },
        text = { Text("草稿及其累计修改会从当前进程中的工作台移除，不会写入资源库。") },
        confirmButton = {
            TextButton(onClick = {
                confirmDiscard = false
                scope.launch {
                    operation = "放弃"
                    operationIssues = emptyList()
                    try {
                        val result = controller.discardConfigurationWorkspace(workspace.id, workspace.revision)
                        operationIssues = result.issues
                        operationMessage = workspaceResultMessage("放弃", result.status, result.retryable)
                        if (result.status == "OK") {
                            fieldDrafts = fieldDrafts.filterKeys { it.workspaceId != workspace.id }
                            selectedId = null
                        }
                    } finally {
                        operation = null
                    }
                }
            }, enabled = operation == null) { Text("放弃草稿") }
        },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
    )
    if (confirmLeaveFields) AlertDialog(
        onDismissRequest = { confirmLeaveFields = false },
        title = { Text("离开配置工作台？") },
        text = { Text("还有 ${fieldDrafts.size} 个未暂存字段修改。离开页面会丢失这些输入。") },
        confirmButton = { TextButton(onClick = { confirmLeaveFields = false; onBack() }) { Text("离开并丢弃输入") } },
        dismissButton = { TextButton(onClick = { confirmLeaveFields = false }) { Text("继续编辑") } },
    )
}

@Composable
private fun WorkspaceEditor(
    workspace: ConfigurationWorkspace,
    commandText: String,
    onCommandTextChange: (String) -> Unit,
    fieldDrafts: Map<WorkspaceFieldDraftKey, WorkspaceFieldDraft>,
    onFieldDraftChange: (WorkspaceFieldDraftKey, WorkspaceFieldDraft?) -> Unit,
    onFieldDraftStaged: (WorkspaceFieldDraftKey, WorkspaceFieldDraft) -> Unit,
    onClearFieldDrafts: () -> Unit,
    controller: AppController,
    operation: String?,
    onOperation: (String, suspend () -> dev.gray.companion.WorkspaceOperationResult,
        (dev.gray.companion.WorkspaceOperationResult) -> Unit) -> Unit,
    onCopy: (String, String) -> Unit,
    onDiscard: () -> Unit,
    onRunTool: (String) -> Unit,
    toolExpanded: Boolean,
    onToolExpandedChange: (Boolean) -> Unit,
    toolRequest: String,
    onToolRequestChange: (String) -> Unit,
    toolResult: String,
    toolBusy: Boolean,
) {
    val liveLibrary by controller.library.collectAsStateWithLifecycle()
    val runtimeBusy by controller.busy.collectAsStateWithLifecycle()
    var resourceQuery by remember(workspace.id) { mutableStateOf("") }
    var resourceKind by remember(workspace.id) { mutableStateOf<String?>(null) }
    var readKind by remember(workspace.id) { mutableStateOf<String?>(null) }
    var readId by remember(workspace.id) { mutableStateOf<String?>(null) }
    var contextSessionId by remember(workspace.id) { mutableStateOf<String?>(null) }
    var includeSelectedResource by remember(workspace.id) { mutableStateOf(false) }
    var showDiff by remember(workspace.id) { mutableStateOf(true) }
    var confirmClearFieldDrafts by remember(workspace.id) { mutableStateOf(false) }
    var selectedFieldTarget by remember(workspace.id) { mutableStateOf<ExtensionFieldRef?>(null) }

    val sessions = remember(liveLibrary) { ConfigurationContext.sessions(liveLibrary) }
    val selectedSessionId = contextSessionId?.takeIf { id -> sessions.any { it.id == id } }
    val resourceLibrary = selectedSessionId?.let { id ->
        val session = liveLibrary.sessions.firstOrNull { it.id == id }
        session?.pendingRun?.frozenResources ?: session?.executableWorld?.library ?: liveLibrary
    } ?: workspace.draft
    val resources = remember(resourceLibrary, resourceKind, resourceQuery) {
        ConfigurationContext.listResources(resourceLibrary, resourceKind, resourceQuery)
    }
    val draftResource = remember(workspace.draft, readKind, readId) { readKind?.let { kind ->
        ConfigurationContext.listResources(workspace.draft, kind)
            .firstOrNull { it.id == readId }
    } }
    val editableFields = remember(workspace.draft, draftResource?.fieldResource) {
        draftResource?.fieldResource?.let { ConfigurationContext.fields(workspace.draft, it) }.orEmpty()
    }
    val selectedField = editableFields.firstOrNull { it.target == selectedFieldTarget }
    LaunchedEffect(workspace.id, draftResource?.fieldResource) {
        selectedFieldTarget = editableFields.firstOrNull()?.target
    }
    val selectedResource = readKind?.let { kind -> readId?.let { id ->
        if (selectedSessionId == null) ConfigurationContext.readResource(resourceLibrary, kind, id)
        else ConfigurationContext.readSessionResource(liveLibrary, selectedSessionId, kind, id)
    } }
    val preview = workspace.fixedPreview?.takeIf { it.revision == workspace.revision }
    val previewPlan = preview?.plan
    val pendingCount = fieldDrafts.keys.count { it.workspaceId == workspace.id }
    val canCommit = previewPlan != null && preview.issues.none { it.error } && operation == null &&
        !runtimeBusy && pendingCount == 0
    val exportedCommands = remember(workspace.id, workspace.revision) { ConfigurationWorkspaces.exportCommands(workspace) }

    Text("${workspace.label} · 草稿版本 ${workspace.revision}", style = MaterialTheme.typography.titleMedium)
    if (pendingCount > 0) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$pendingCount 个未暂存字段修改", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { confirmClearFieldDrafts = true }) { Text("放弃这些输入") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onOperation("暂存",
                { controller.stageConfigurationWorkspace(workspace.id, workspace.revision, commandText) }, {}) },
            enabled = operation == null && commandText.isNotBlank(),
        ) { Text(if (operation == "暂存") "正在暂存…" else "暂存 JSON") }
        TextButton(
            onClick = { onOperation("检查",
                { controller.previewConfigurationWorkspace(workspace.id, workspace.revision) }, {}) },
            enabled = operation == null,
        ) { Text(if (operation == "检查") "检查中…" else "检查累计修改") }
        TextButton(onClick = { onCommandTextChange(ConfigurationContext.exampleCommands()) }) {
            Text("填入示例")
        }
    }
    OutlinedTextField(
        value = commandText,
        onValueChange = onCommandTextChange,
        label = { Text("配置 JSON 命令") },
        placeholder = { Text("粘贴配置命令 JSON；暂存后仍可继续修改") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 8,
        maxLines = 16,
    )
    Text("暂存只更新此草稿。无效 JSON 或校验错误会保留在输入框中；累计差异从打开草稿时的基线计算。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("草稿目录与读取", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = resourceQuery,
        onValueChange = { resourceQuery = it },
        label = { Text("搜索资源名称、ID 或 slug") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    ChoiceLine("资源类型", resourceKind, listOf(null to "全部") + workspaceKinds.map { it.first to it.second }) {
        resourceKind = it
    }
    ChoiceLine(
        "聊天上下文（可选）",
        selectedSessionId,
        listOf(null to "不包含聊天上下文") + sessions.map { summary ->
            summary.id to "${summary.title}${if (summary.frozen) " · 已冻结" else ""}"
        },
    ) { contextSessionId = it }
    Text("聊天目录仅提供 BODY 文本与资源名称摘要；选择对话后，资源读取优先使用该对话实际冻结的资源世界。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("${resources.size} 个匹配资源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(resources.take(100), key = { "${it.kind}:${it.id}" }) { resource ->
            OutlinedCard(
                onClick = { readKind = resource.kind; readId = resource.id },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${workspaceKindLabel(resource.kind)} · ${resource.name.ifBlank { "未命名" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("${resource.id}${resource.slug?.let { " · $it" }.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (readKind == resource.kind && readId == resource.id) Text("当前读取", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (resources.size > 100) Text("列表显示前 100 项；可继续缩小搜索条件。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (readKind != null && readId != null) {
        Text("资源内容 · ${workspaceKindLabel(readKind.orEmpty())} · $readId", style = MaterialTheme.typography.titleSmall)
        if (selectedResource == null) Text("此资源在所选草稿或对话资源世界中不可用。", style = MaterialTheme.typography.bodySmall)
        else {
            val resourceJson = prettyWorkspaceJson(selectedResource)
            OutlinedTextField(
                value = resourceJson,
                onValueChange = {},
                readOnly = true,
                label = { Text("只读 JSON") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
            )
            TextButton(onClick = { onCopy(resourceJson, "资源 JSON 已复制。") }) { Text("复制资源 JSON") }
        }

        if (draftResource?.fieldResource != null) {
            HorizontalRule()
            Text("字段小编辑 · 当前草稿", style = MaterialTheme.typography.titleSmall)
            if (pendingCount > 0) Text("切换资源或字段后，可以回来继续编辑未暂存内容。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (editableFields.isEmpty()) {
                Text("此资源没有可通过字段接口编辑的项目。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ChoiceLine(
                    "选择字段",
                    selectedFieldTarget,
                    listOf(null to "选择一个字段") + editableFields.map { it.target to it.title },
                ) { selectedFieldTarget = it }
                selectedField?.let { field ->
                    val draftKey = WorkspaceFieldDraftKey(workspace.id, field.target)
                    val pending = fieldDrafts[draftKey]
                    val fieldInput = pending?.input ?: field.value.orEmpty()
                    val fieldSetNull = pending?.setNull ?: (field.nullable && field.value == null)
                    val sourceChanged = pending != null &&
                        (pending.sourceRevision != workspace.revision || pending.sourceValue != field.value)
                    fun editField(input: String, setNull: Boolean) {
                        val changed = if (setNull) field.value != null else field.value == null || input != field.value
                        onFieldDraftChange(draftKey, if (!changed) null else WorkspaceFieldDraft(
                            input, setNull, pending?.sourceRevision ?: workspace.revision,
                            if (pending != null) pending.sourceValue else field.value))
                    }
                    val fieldChanged = if (fieldSetNull) field.value != null
                    else field.value == null || fieldInput != field.value
                    Text("当前值：${field.value ?: "null"}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sourceChanged) {
                        Text("草稿版本或字段原值已变化。未暂存内容仍保留，请核对当前值后再继续。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { pending?.let { onFieldDraftChange(draftKey,
                            it.copy(sourceRevision = workspace.revision, sourceValue = field.value)) } }) {
                            Text("按当前版本继续编辑")
                        }
                    }
                    if (field.choices.isNotEmpty()) {
                        ChoiceLine(
                            field.title,
                            fieldInput,
                            field.choices.map { it.value to it.title },
                        ) {
                            editField(it, false)
                        }
                    } else {
                        OutlinedTextField(
                            value = fieldInput,
                            onValueChange = { editField(it, false) },
                            label = { Text(field.title) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = field.type in listOf(ActionFieldType.NUMBER, ActionFieldType.BOOLEAN),
                            minLines = if (field.type in listOf(ActionFieldType.NUMBER, ActionFieldType.BOOLEAN)) 1 else 3,
                            maxLines = 8,
                            enabled = !fieldSetNull,
                        )
                    }
                    if (field.nullable) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fieldSetNull, onCheckedChange = { editField(fieldInput, it) })
                        Text("将字段设为 null", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            val submitted = requireNotNull(pending)
                            onOperation("暂存字段", {
                                controller.stageConfigurationField(workspace.id, workspace.revision,
                                    field.target, field.value, if (submitted.setNull) null else submitted.input)
                            }, { result -> if (result.status == "OK") onFieldDraftStaged(draftKey, submitted) })
                        },
                        enabled = operation == null && fieldChanged && !sourceChanged && pending != null,
                    ) { Text(if (operation == "暂存字段") "正在暂存…" else "暂存字段修改") }
                    Text("字段修改会进入同一隔离草稿，并更新版本；请检查累计差异后再应用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = includeSelectedResource, onCheckedChange = { includeSelectedResource = it })
        Text("复制安全上下文时附上当前读取资源的完整 JSON", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            val safeContext = safeWorkspaceContext(
                workspace = workspace,
                contextLibrary = liveLibrary,
                resources = ConfigurationContext.listResources(resourceLibrary),
                selectedSessionId = selectedSessionId,
                selectedResource = if (includeSelectedResource) selectedResource else null,
                selectedResourceKind = readKind,
                selectedResourceId = readId,
            )
            onCopy(safeContext, "安全上下文与能力说明已复制。")
        }) { Text("复制安全上下文与能力说明") }
        TextButton(
            onClick = { exportedCommands?.let { onCopy(it, "累计命令已复制。") } },
            enabled = exportedCommands != null,
        ) { Text("复制累计命令") }
    }

    HorizontalRule()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("累计差异与诊断", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { showDiff = !showDiff }) { Text(if (showDiff) "收起" else "展开") }
    }
    if (!showDiff) Text("当前累计修改 ${workspace.fixedPreview?.plan?.changes?.size ?: 0} 项资源；请展开查看完整前后内容。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else {
        if (preview?.issues?.isNotEmpty() == true) IssueList(preview.issues)
        when {
            preview == null -> Text("尚未检查当前版本的累计修改。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            previewPlan == null -> Text("没有可应用的有效计划。", style = MaterialTheme.typography.bodySmall)
            previewPlan.changes.isEmpty() -> Text("无资源差异。", style = MaterialTheme.typography.bodySmall)
            else -> previewPlan.changes.forEach { change -> WorkspaceChangeCard(change) }
        }
    }
    if (runtimeBusy) Text("主调用正在运行，仍可编辑草稿；结束后可应用。", style = MaterialTheme.typography.bodySmall)
    if (pendingCount > 0) Text("应用前请暂存或放弃未暂存字段输入。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (previewPlan != null && preview.issues.none { it.error }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onOperation("应用",
            { controller.commitConfigurationWorkspace(workspace.id, workspace.revision) }, {}) }, enabled = canCommit) {
            Text(if (operation == "应用") "应用中…" else "应用此草稿")
        }
        TextButton(onClick = onDiscard, enabled = operation == null) { Text("放弃草稿") }
    } else TextButton(onClick = onDiscard, enabled = operation == null) { Text("放弃草稿") }

    HorizontalRule()
    TextButton(onClick = { onToolExpandedChange(!toolExpanded) }) {
        Text(if (toolExpanded) "收起工具请求 JSON" else "展开工具请求 JSON")
    }
    if (toolExpanded) {
        Text("默认请求为 describe；只有点击执行后才会运行你填写的操作。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = toolRequest,
            onValueChange = onToolRequestChange,
            label = { Text("工具请求 JSON") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 10,
        )
        Button(onClick = { onRunTool(toolRequest) }, enabled = !toolBusy && toolRequest.isNotBlank()) {
            Text(if (toolBusy) "执行中…" else "执行请求")
        }
        if (toolResult.isNotBlank()) OutlinedTextField(
            value = toolResult,
            onValueChange = {},
            readOnly = true,
            label = { Text("工具结果") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 12,
        )
    }

    if (confirmClearFieldDrafts) AlertDialog(
        onDismissRequest = { confirmClearFieldDrafts = false },
        title = { Text("放弃未暂存字段输入？") },
        text = { Text("此草稿的 $pendingCount 个字段输入会清除；已经暂存的修改仍在隔离草稿中。") },
        confirmButton = { TextButton(onClick = {
            confirmClearFieldDrafts = false
            onClearFieldDrafts()
        }) { Text("放弃输入") } },
        dismissButton = { TextButton(onClick = { confirmClearFieldDrafts = false }) { Text("继续编辑") } },
    )

}

@Composable
private fun WorkspaceChangeCard(change: ConfigurationChange) {
    var expanded by remember(change.kind, change.id) { mutableStateOf(false) }
    val before = change.before as? JsonObject
    val after = change.after as? JsonObject
    val name = ((after?.get("name") ?: before?.get("name")) as? JsonPrimitive)?.content.orEmpty()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${workspaceKindLabel(change.kind)} · ${when (change.operation) { "create" -> "新增"; "remove" -> "删除"; else -> "更新" }}",
                style = MaterialTheme.typography.titleSmall)
            if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(change.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (change.changedFields.isNotEmpty()) Text("字段：${change.changedFields.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起前后内容" else "查看前后内容") }
            if (expanded) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        change.changedFields.forEach { field ->
                            Text(field, style = MaterialTheme.typography.labelLarge)
                            Text("原值：${prettyWorkspaceJson(before?.get(field))}", style = MaterialTheme.typography.bodySmall)
                            Text("新值：${prettyWorkspaceJson(after?.get(field))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueList(issues: List<Issue>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        issues.forEach { issue ->
            Text(
                "${issue.path}: ${issue.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (issue.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HorizontalRule() {
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun workspaceResultMessage(action: String, status: String, retryable: Boolean): String = when (status) {
    "OK" -> when (action) { "暂存" -> "已暂存到隔离草稿。"; "检查" -> "检查完成。"; "应用" -> "配置已应用。"; "放弃" -> "草稿已放弃。"; else -> "${action}完成。" }
    "BUSY" -> "当前操作仍在进行；草稿已保留，稍后可重试应用。"
    "STALE" -> "草稿版本已变化，请重新查看后再操作。"
    "INVALID" -> "配置未通过检查；请修正诊断后重新暂存或检查。"
    "CONFLICT_OR_INVALID" -> "当前资源与预览不一致；草稿保留，请重新检查并处理冲突。"
    "SAVE_FAILED" -> "保存失败；草稿保留，可稍后重试。"
    "NOT_READY" -> "资源库尚未就绪，请稍后重试。"
    else -> "${action}失败：$status${if (retryable) "（可重试）" else ""}"
}

private val workspaceKinds = listOf(
    "character" to "角色",
    "mainCall" to "主调用",
    "package" to "提示词包",
    "group" to "调用组",
    "persona" to "用户身份",
    "tag" to "标签",
    "extension" to "扩展",
)

private fun workspaceKindLabel(kind: String): String = workspaceKinds.firstOrNull { it.first == kind }?.second ?: kind

private fun copyWorkspaceText(context: Context, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("配置工作台", text))
}.isSuccess

private fun safeWorkspaceContext(
    workspace: ConfigurationWorkspace,
    contextLibrary: Library,
    resources: List<ConfigurationResourceSummary>,
    selectedSessionId: String?,
    selectedResource: JsonObject?,
    selectedResourceKind: String?,
    selectedResourceId: String?,
): String {
    val json = LibraryFormat.json
    val body = buildJsonObject {
        put("manifest", json.encodeToJsonElement(ConfigurationWorkspaceManifest.serializer(), ConfigurationContext.manifest()))
        put("workspace", buildJsonObject {
            put("id", workspace.id)
            put("revision", workspace.revision)
        })
        put("resourceDirectory", buildJsonArray {
            resources.take(200).forEach { resource ->
                add(json.encodeToJsonElement(ConfigurationResourceSummary.serializer(), resource))
            }
        })
        if (resources.size > 200) put("resourceDirectoryTruncated", true)
        put("actions", buildJsonArray {
            ConfigurationContext.actions().forEach { action ->
                add(json.encodeToJsonElement(ActionDescriptor.serializer(), action))
            }
        })
        put("models", buildJsonArray {
            ConfigurationContext.models(workspace.draft).forEach { model ->
                add(json.encodeToJsonElement(ConfigurationModelSummary.serializer(), model))
            }
        })
        if (selectedSessionId != null) ConfigurationContext.sessionContext(contextLibrary, selectedSessionId)?.let { session ->
            put("sessionContext", json.encodeToJsonElement(ConfigurationSessionContext.serializer(), session))
        }
        if (selectedResource != null && selectedResourceKind != null && selectedResourceId != null) {
            put("selectedResource", buildJsonObject {
                put("kind", selectedResourceKind)
                put("id", selectedResourceId)
                put("value", selectedResource)
            })
        }
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), body)
}

private val workspacePrettyJson = Json { prettyPrint = true }
private fun prettyWorkspaceJson(value: JsonElement?): String = when {
    value == null -> "无"
    value is JsonPrimitive && value.isString -> value.content
    else -> workspacePrettyJson.encodeToString(JsonElement.serializer(), value)
}
