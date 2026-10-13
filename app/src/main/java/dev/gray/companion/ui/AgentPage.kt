package dev.gray.companion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
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
import dev.gray.companion.agent.AgentController
import dev.gray.companion.agent.AgentEvent
import dev.gray.companion.agent.AgentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class AgentTab(val title: String) { CHAT("聊天"), FILES("文件"), WORKBENCH("工作台"), SETTINGS("设置") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPage(
    agent: AgentController,
    onBack: () -> Unit,
    onOpenWorkbench: () -> Unit,
) {
    val state by agent.state.collectAsStateWithLifecycle()
    val auth by agent.authState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(AgentTab.CHAT) }
    var notice by remember { mutableStateOf<String?>(null) }
    val fileDrafts = rememberAgentFileDrafts()
    val fileDraft = state.selectedSessionId?.let { id -> fileDrafts.getOrPut(id) { AgentFileDraft() } }
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = {
        when {
            fileDrafts.values.any { it.busy } -> notice = "文件操作还在完成，请稍候。"
            fileDrafts.values.any { it.hasPendingWork } -> confirmLeave = true
            else -> onBack()
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        SettingsHeader("资源 Agent", leave)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            AgentTab.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = tab == choice,
                    onClick = { tab = choice },
                    shape = SegmentedButtonDefaults.itemShape(index, AgentTab.entries.size),
                    label = { Text(choice.title) },
                )
            }
        }
        if (state.error != null) {
            Text(state.error.orEmpty(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = agent::dismissError, modifier = Modifier.padding(start = 8.dp)) { Text("关闭提示") }
        }
        notice?.let { message ->
            Text(message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { notice = null }, modifier = Modifier.padding(start = 8.dp)) { Text("知道了") }
        }
        when (tab) {
            AgentTab.CHAT -> AgentChatTab(agent, state.sessions, state.selected, state.runningSessionId,
                state.streaming, state.phase, auth.signedIn && auth.planUsageAllowed,
                canSwitchSession = fileDrafts.values.none { it.busy },
                canDeleteSession = fileDraft?.hasPendingWork != true,
                onNotice = { notice = it })
            AgentTab.FILES -> AgentFilePage(agent, state.selected?.id, fileDraft, scope, onNotice = { notice = it })
            AgentTab.WORKBENCH -> AgentWorkbenchTab(agent, state.selected, onOpenWorkbench,
                canOpenWorkbench = fileDrafts.values.none { it.hasPendingWork }, onNotice = { notice = it })
            AgentTab.SETTINGS -> AgentSettingsTab(agent, state.selected, state.models, state.modelLoading,
                auth.loading, auth.signingIn, auth.signedIn, auth.email, auth.planUsageAllowed,
                auth.error, auth.loginUrl, state.runningSessionId == state.selectedSessionId,
                onNotice = { notice = it })
        }
    }
    androidx.activity.compose.BackHandler(enabled = fileDrafts.values.any { it.hasPendingWork }) { leave() }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("离开 Agent 页面？") },
        text = { Text("文件里还有未保存的编辑或尚未写入的导入文本。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("放弃编辑并离开") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; tab = AgentTab.FILES }) { Text("继续编辑") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentChatTab(
    agent: AgentController,
    sessions: List<AgentSession>,
    selected: AgentSession?,
    runningSessionId: String?,
    streaming: String,
    phase: String,
    canSend: Boolean,
    canSwitchSession: Boolean,
    canDeleteSession: Boolean,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var title by remember(selected?.id) { mutableStateOf(selected?.title.orEmpty()) }
    var input by remember(selected?.id) { mutableStateOf("") }
    val list = rememberLazyListState()
    val visibleStreaming = selected?.id == runningSessionId && streaming.isNotEmpty()
    LaunchedEffect(selected?.id, selected?.events?.size, visibleStreaming) {
        val count = (selected?.events?.size ?: 0) + if (visibleStreaming) 1 else 0
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { menu = true }, enabled = canSwitchSession, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.title ?: "选择聊天")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                sessions.forEach { session -> DropdownMenuItem(
                    text = { Text(session.title) }, onClick = { menu = false; agent.selectSession(session.id) },
                ) }
            }
        }
        TextButton(onClick = agent::newSession, enabled = canSwitchSession) { Text("新建") }
    }
    if (selected != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { title = selected.title; rename = true }) { Text("重命名") }
            TextButton(onClick = { delete = true }, enabled = canDeleteSession && selected.id != runningSessionId) { Text("删除") }
        }
    }
    val isRunning = selected?.id == runningSessionId
    if (runningSessionId != null) {
        Text(if (isRunning) "正在运行 · ${phase.ifBlank { "处理中" }}" else "另一场 Agent 聊天正在运行",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(), state = list,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected == null) item { Text("暂无 Agent 聊天") }
        else {
            items(selected.events, key = { it.id }) { event -> AgentEventCard(event) { copyAgentText(context, it); onNotice("已复制") } }
            if (visibleStreaming) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Agent · 正在回复", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(streaming) }
                    }
                }
            }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = input, onValueChange = { input = it }, label = { Text("交给 Agent 的任务") },
            modifier = Modifier.weight(1f), minLines = 2, maxLines = 5,
        )
        if (isRunning) OutlinedButton(onClick = agent::stop) { Text("停止") }
        else Button(onClick = { agent.send(input.trim()); input = "" },
            enabled = selected != null && selected.model.isNotBlank() && runningSessionId == null && canSend && input.isNotBlank()) { Text("发送") }
    }
    if (!canSend) Text("登录并获得套餐模型权限后可发送。", Modifier.padding(start = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall)
    else if (selected?.model.isNullOrBlank()) Text("请先在设置中选择模型。", Modifier.padding(start = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.bodySmall)
    }

    if (rename && selected != null) AlertDialog(
        onDismissRequest = { rename = false }, title = { Text("重命名聊天") },
        text = { OutlinedTextField(title, { title = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { agent.renameSession(selected.id, title.trim()); rename = false }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } },
    )
    if (delete && selected != null) AlertDialog(
        onDismissRequest = { delete = false }, title = { Text("删除这场 Agent 聊天？") },
        text = { Text("聊天记录和专属文件会一起删除。") },
        confirmButton = { TextButton(onClick = { agent.deleteSession(selected.id); delete = false }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } },
    )
}


@Composable
private fun AgentEventCard(event: AgentEvent, onCopy: (String) -> Unit) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val tool = event.operation != null || event.arguments != null || event.result != null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (event.kind.lowercase()) {
                "user" -> "我"; "assistant" -> "Agent"; "tool" -> "工具"; else -> event.kind
            }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (event.text.isNotBlank()) SelectionContainer { Text(event.text) }
            if (tool) {
                TextButton(onClick = { expanded = !expanded }) { Text((event.operation ?: "工具详情") + if (expanded) " · 收起" else " · 展开") }
                if (expanded) {
                    event.arguments?.let { SelectionContainer { Text("参数\n$it", style = MaterialTheme.typography.bodySmall) } }
                    event.result?.let { SelectionContainer { Text("结果\n$it", style = MaterialTheme.typography.bodySmall) } }
                }
            }
            TextButton(onClick = { onCopy(event.text.ifBlank { listOfNotNull(event.arguments, event.result).joinToString("\n") }) }) { Text("复制") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSettingsTab(
    agent: AgentController,
    selected: AgentSession?,
    models: List<dev.gray.companion.agent.AgentModel>,
    modelLoading: Boolean,
    authLoading: Boolean,
    signingIn: Boolean,
    signedIn: Boolean,
    email: String?,
    planUsageAllowed: Boolean,
    authError: String?,
    loginUrl: String?,
    sessionRunning: Boolean,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember(selected?.id) { mutableStateOf(selected?.model.orEmpty()) }
    var effort by remember(selected?.id) { mutableStateOf(selected?.effort.orEmpty()) }
    var instructions by remember(selected?.id) { mutableStateOf(selected?.instructions.orEmpty()) }
    var autoApply by remember(selected?.id) { mutableStateOf(selected?.autoApply ?: false) }
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var signOutConfirm by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var loginBusy by remember { mutableStateOf(false) }

    fun launchLogin(newAccount: Boolean) {
        if (loginBusy) return
        scope.launch {
            loginBusy = true
            try {
                val url = agent.beginSignIn(newAccount)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onNotice("未能打开登录页面，请稍后重试。") }
            finally { loginBusy = false }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ChatGPT 套餐登录", style = MaterialTheme.typography.titleMedium)
        Text(when {
            authLoading -> "正在检查登录状态…"
            signingIn -> "等待浏览器完成授权…"
            signedIn -> "已登录${email?.let { " · $it" }.orEmpty()}${if (planUsageAllowed) " · 可使用套餐模型" else " · 暂无套餐模型权限"}"
            else -> "尚未登录"
        })
        authError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launchLogin(false) }, enabled = !loginBusy && !signingIn) { Text("Continue with ChatGPT") }
            if (signingIn) TextButton(onClick = agent::cancelSignIn) { Text("取消登录") }
        }
        if (loginUrl != null && signingIn) TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("重新打开授权页面") }
        if (signedIn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { launchLogin(true) }, enabled = !loginBusy && !signingIn) { Text("切换账号") }
            TextButton(onClick = { signOutConfirm = true }) { Text("退出登录") }
        }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
        }) { Text("查看套餐用量") }
        HorizontalDivider()
        Text("当前聊天设置", style = MaterialTheme.typography.titleMedium)
        if (selected == null) Text("先新建一场 Agent 聊天。")
        else {
            Box {
                OutlinedButton(onClick = { modelMenu = true }, enabled = signedIn && models.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text("模型 · " + (models.firstOrNull { it.id == model }?.name ?: model.ifBlank { "请选择" })) }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    models.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = {
                        model = item.id
                        if (item.efforts.isNotEmpty() && effort !in item.efforts) effort = item.efforts.first()
                        modelMenu = false
                    }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (modelLoading) "正在读取可用模型…" else "模型来自当前登录账号", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = agent::refreshModels, enabled = signedIn && !modelLoading) { Text("刷新") }
            }
            val choices = models.firstOrNull { it.id == model }?.efforts.orEmpty().ifEmpty { listOf("", "low", "medium", "high") }
            Box {
                OutlinedButton(onClick = { effortMenu = true }, enabled = signedIn && model.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()) { Text("思考强度 · ${effort.ifBlank { "模型默认" }}") }
                DropdownMenu(effortMenu, { effortMenu = false }) {
                    choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.ifBlank { "模型默认（不发送参数）" }) }, onClick = { effort = choice; effortMenu = false }) }
                }
            }
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Agent 工作指令") },
                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 18)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoApply, { autoApply = it })
                Text("预览通过后允许 Agent 自动应用配置")
            }
            Button(onClick = { agent.updateSettings(model, effort, instructions, autoApply); onNotice("设置已提交保存") },
                enabled = !sessionRunning && (model.isBlank() || !signedIn || models.any { it.id == model })) { Text("保存设置") }
            TextButton(onClick = { clearConfirm = true }, enabled = !sessionRunning) { Text("清除当前聊天上下文") }
        }
    }
    if (signOutConfirm) AlertDialog(onDismissRequest = { signOutConfirm = false },
        title = { Text("退出 Agent 登录？") }, text = { Text("这会结束独立 Agent 的登录状态。") },
        confirmButton = { TextButton(onClick = { agent.signOut(); signOutConfirm = false }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { signOutConfirm = false }) { Text("取消") } })
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false },
        title = { Text("清除上下文？") }, text = { Text("后续请求将不再带上此聊天的旧上下文。") },
        confirmButton = { TextButton(onClick = { agent.clearContext(); clearConfirm = false }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
}

private fun copyAgentText(context: Context, value: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("Agent", value))
}
