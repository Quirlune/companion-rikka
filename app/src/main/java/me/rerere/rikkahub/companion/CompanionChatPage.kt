package me.rerere.rikkahub.companion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gray.companion.AppController
import dev.gray.companion.core.RecoveryDecision
import dev.gray.companion.core.RecoveryDecisionKind
import dev.gray.companion.core.RecoveryFailure
import dev.gray.companion.core.Role
import me.rerere.ai.ui.UIMessagePart
import dev.gray.companion.ui.MacroTileDashboard
import dev.gray.companion.ui.MacroSidebarPanel
import dev.gray.companion.ui.RecoveryFooter
import dev.gray.companion.ui.hasMacroSidebar
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.TopBar
import me.rerere.rikkahub.ui.pages.chat.OriginalChatPageLayout

/** The copied RikkaHub chat surface backed only by Companion's session controller. */
@Composable
fun CompanionChatPage(
    controller: AppController,
    sessionId: String,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onOpenModels: () -> Unit,
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    targetMessageId: String? = null,
    onTargetConsumed: () -> Unit = {},
    onRecoveryEditPrompt: (RecoveryFailure) -> Unit = {},
) {
    val library by controller.library.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val controllerState by controller.state.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val session = library.sessions.firstOrNull { it.id == sessionId }
    val active = sessionId == library.selectedSessionId
    val activeBusy = active && busy
    val activeError = if (active) error else null
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var messageOutline by remember { mutableStateOf(false) }
    var drafts by rememberSaveable(stateSaver = listSaver<Map<String, String>, String>(
        save = { values -> values.flatMap { (id, text) -> listOf(id, text) } },
        restore = { values -> values.chunked(2).associate { it[0] to it[1] } },
    )) { mutableStateOf(emptyMap()) }

    LaunchedEffect(activeError) {
        activeError?.takeIf { it.isNotBlank() }?.let {
            val retryAvailable = session?.messages?.lastOrNull()?.role == Role.USER
            if (snackbar.showSnackbar(it, actionLabel = if (retryAvailable) "重试" else null) == SnackbarResult.ActionPerformed) {
                controller.retry()
            }
        }
    }
    if (session == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) { Text("请选择会话") }
        }
        return
    }
    val pendingRecovery = session.pendingRun

    val appearanceSettings = LocalSettings.current
    val displaySession = if (activeBusy) controllerState.generationPreview ?: session else session
    val sidebarAvailable = remember(library, displaySession) { hasMacroSidebar(library, displaySession) }
    var sidebarOpen by remember(session.id) { mutableStateOf(false) }
    val history = remember(displaySession.messages, displaySession.turnRecords) { projectCompanionHistory(displaySession) }
    val presentation = remember(appearanceSettings, library, session.characterId, session.mainCallId) {
        projectCompanionSettings(appearanceSettings, library, session)
    }
    val pendingOutputs = if (active) controllerState.pendingOutputs else emptyList()
    val projection = remember(presentation, displaySession, pendingOutputs, history) {
        projectCompanionChat(appearanceSettings, library, displaySession, pendingOutputs, history.nodes, presentation)
    }
    val settings = projection.settings
    val conversation = projection.conversation
    val input = remember(session.id) {
        ChatInputState().also { it.setMessageText(drafts[session.id].orEmpty()) }
    }
    var editingCoreMessageId by remember(session.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(session.id, input) {
        snapshotFlow { input.textContent.text.toString() }.collect { drafts = drafts + (session.id to it) }
    }
    val chatListState = rememberLazyListState()
    var chatListInitialized by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, targetMessageId, history.nodes, conversation.messageNodes.size) {
        if (targetMessageId != null) {
            val index = history.nodes.indexOfFirst { node ->
                node.messages.any { history.logicalMessageIds[it.id] == targetMessageId }
            }
            if (index >= 0) chatListState.scrollToItem(index)
            else snackbar.showSnackbar("消息已不在当前会话中")
            chatListInitialized = true
            onTargetConsumed()
        } else if (!chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            chatListInitialized = true
        }
    }
    fun submit(answer: Boolean) {
        val text = input.textContent.text.toString().trim()
        if (text.isBlank()) return
        scope.launch {
            if (activeBusy) {
                snackbar.showSnackbar("请等待当前回复完成，或先停止生成")
            } else if (input.isEditing()) {
                val messageId = editingCoreMessageId
                if (messageId == null) {
                    snackbar.showSnackbar("编辑目标已失效，请重新选择消息")
                } else {
                    val failure = controller.editMessage(session.id, messageId, text).firstOrNull { it.error }
                    if (failure == null) {
                        editingCoreMessageId = null
                        input.clearInput()
                        drafts = drafts - session.id
                    } else snackbar.showSnackbar(failure.message)
                }
            } else {
                val failure = if (answer) {
                    if (controller.send(text, expectedSessionId = session.id)) null
                    else controller.error.value ?: "消息未发送，请检查当前会话"
                } else controller.appendUserOnly(session.id, text).firstOrNull { it.error }?.message
                if (failure == null) {
                    drafts = drafts - session.id
                    input.clearInput()
                    delay(100.milliseconds)
                    chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                } else snackbar.showSnackbar(failure)
            }
        }
    }
    CompositionLocalProvider(LocalSettings provides settings) {
        OriginalChatPageLayout(
                settings = settings,
                drawerState = drawerState,
                drawerContent = drawerContent,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = { bigScreen ->
                    TopBar(
                        settings = settings,
                        conversation = conversation,
                        drawerState = drawerState,
                        bigScreen = bigScreen,
                        previewMode = messageOutline,
                        onClickMenu = { messageOutline = !messageOutline },
                        onNewChat = onNewSession,
                        onUpdateTitle = { title ->
                            scope.launch {
                                val issues = controller.renameSession(session.id, title)
                                issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                            }
                        },
                        onOpenDrawer = onOpenDrawer,
                        onOpenMacroSidebar = if (sidebarAvailable) ({ sidebarOpen = true }) else null,
                    )
                },
                bottomBar = { hazeState ->
                    ChatInput(
                        state = input,
                        loading = activeBusy,
                        settings = settings,
                        hazeState = hazeState,
                        enableSearch = false,
                        onUpdateSearchMode = {},
                        onUpdateChatModel = { model ->
                            library.profiles.firstOrNull { stableUuid(it.id) == model.id }?.let { profile ->
                                scope.launch {
                                    val issues = controller.updateLibrary { current ->
                                        if (current.profiles.none { it.id == profile.id }) return@updateLibrary current
                                        val currentSession = current.sessions.firstOrNull { it.id == session.id }
                                        val main = current.mainCalls.firstOrNull { it.id == currentSession?.mainCallId }
                                        val primary = main?.workflows?.firstOrNull { it.id == main.primaryWorkflowId }
                                            ?: main?.workflows?.firstOrNull()
                                        if (main != null && primary?.modelProfileId != null) {
                                            current.copy(mainCalls = current.mainCalls.map { call ->
                                                if (call.id == main.id) call.copy(workflows = call.workflows.map { workflow ->
                                                    if (workflow.id == primary.id) workflow.copy(modelProfileId = profile.id) else workflow
                                                }) else call
                                            })
                                        } else current.copy(selectedProfileId = profile.id)
                                    }
                                    issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                                }
                            }
                        },
                        onUpdateAssistant = {},
                        onUpdateSearchService = {},
                        onMoreClick = null,
                        onCancelClick = controller::stop,
                        onSendClick = { submit(answer = true) },
                        onLongSendClick = { submit(answer = false) },
                        textOnly = true,
                    )
                },
                chatList = { padding, hazeState ->
                    ChatList(
                    innerPadding = padding,
                    conversation = conversation,
                    state = chatListState,
                    loading = activeBusy,
                    processingStatus = if (active) controllerState.traces.lastOrNull()?.title else null,
                    previewMode = messageOutline,
                    settings = settings,
                    hazeState = hazeState,
                    onJumpToMessage = { index ->
                        messageOutline = false
                        scope.launch { chatListState.requestScrollToItem(index) }
                    },
                    onEdit = { uiMessage ->
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        val original = session.messages.firstOrNull { it.id == messageId && !it.deleted }
                        if (original == null) scope.launch { snackbar.showSnackbar("消息已失效，请重试") }
                        else {
                            editingCoreMessageId = original.id
                            input.editingMessage = uiMessage.id
                            input.setContents(listOf(UIMessagePart.Text(original.text)))
                        }
                    },
                    onRegenerate = { uiMessage ->
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        if (messageId == null) scope.launch { snackbar.showSnackbar("消息已失效，请重试") }
                        else scope.launch {
                            if (!controller.regenerateMessage(session.id, messageId))
                                snackbar.showSnackbar(controller.error.value ?: "重新生成失败")
                        }
                    },
                    onForkMessage = { uiMessage ->
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        if (messageId == null) scope.launch { snackbar.showSnackbar("消息已失效，请重试") }
                        else scope.launch {
                            controller.forkMessage(session.id, messageId)
                                .firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                        }
                    },
                    onDelete = { uiMessage ->
                        val node = history.nodes.firstOrNull { it.currentMessage.id == uiMessage.id }
                        val choice = node?.let { history.branchChoices[it.id]?.getOrNull(it.selectIndex) }
                        scope.launch {
                            val issues = when (choice) {
                                is CompanionBranchChoice.Generation -> controller.deleteGenerationVariant(
                                    session.id, choice.turnId, choice.variantId)
                                is CompanionBranchChoice.Text -> controller.deleteMessage(session.id, choice.messageId)
                                null -> null
                            }
                            if (issues == null) snackbar.showSnackbar("消息已失效，请重试")
                            else issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                        }
                    },
                    onUpdateMessage = { changed ->
                        val choice = history.branchChoices[changed.id]?.getOrNull(changed.selectIndex)
                        scope.launch {
                            val issues = when (choice) {
                                is CompanionBranchChoice.Generation -> controller.selectGenerationVariant(
                                    session.id, choice.turnId, choice.variantId, choice.messageId, choice.revisionId)
                                is CompanionBranchChoice.Text -> controller.selectTextRevision(
                                    session.id, choice.messageId, choice.revisionId)
                                null -> null
                            }
                            if (issues == null) snackbar.showSnackbar("消息分支已失效，请重试")
                            else issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                        }
                    },
                    readOnly = activeBusy,
                    enableTts = false,
                    footer = {
                        MacroTileDashboard(library, displaySession)
                        RecoveryFooter(
                            recovery = pendingRecovery,
                            busy = !active || activeBusy,
                            onRetry = { failure, correction ->
                                controller.recover(session.id, RecoveryDecision(
                                    kind = RecoveryDecisionKind.RETRY,
                                    nodeKey = failure.nodeKey,
                                    correction = correction,
                                ))
                            },
                            onSkip = { failure ->
                                controller.recover(session.id, RecoveryDecision(
                                    kind = RecoveryDecisionKind.SKIP,
                                    nodeKey = failure.nodeKey,
                                ))
                            },
                            onManual = { failure, manualText, correction ->
                                controller.recover(session.id, RecoveryDecision(
                                    kind = RecoveryDecisionKind.MANUAL,
                                    nodeKey = failure.nodeKey,
                                    manualText = manualText,
                                    correction = correction,
                                ))
                            },
                            onEditPrompt = onRecoveryEditPrompt,
                            onFinish = {
                                val runId = pendingRecovery?.id
                                if (runId != null) controller.finishRecovery(session.id, runId) else false
                            },
                        )
                    },
                    )
                },
            )
        if (sidebarOpen && sidebarAvailable) MacroSidebarPanel(library, displaySession) { sidebarOpen = false }
    }
}
