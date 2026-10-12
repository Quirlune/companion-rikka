package me.rerere.rikkahub.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import dev.gray.companion.AutomaticOptionsUiState
import dev.gray.companion.core.AutomaticOption
import dev.gray.companion.core.AutomaticOptions
import dev.gray.companion.core.RecoveryDecision
import dev.gray.companion.core.RecoveryDecisionKind
import dev.gray.companion.core.RecoveryFailure
import dev.gray.companion.core.Role
import dev.gray.companion.core.OutputSlot
import dev.gray.companion.core.MessageOperations
import dev.gray.companion.core.CardDisplays
import dev.gray.companion.core.CardRegion
import me.rerere.ai.ui.UIMessagePart
import dev.gray.companion.core.BodyAppearanceTemplates
import dev.gray.companion.ui.StyledBody
import dev.gray.companion.ui.FrozenHtmlBody
import dev.gray.companion.ui.CardMenuItems
import dev.gray.companion.ui.CardMenuDialog
import dev.gray.companion.ui.menuCards
import dev.gray.companion.ui.CardRegionDisplay
import dev.gray.companion.ui.RecoveryFooter
import dev.gray.companion.ui.userMacroCompletionProvider
import dev.gray.companion.ui.userMacroOutputTransformation
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
    optionsState: AutomaticOptionsUiState? = null,
    onGenerateOptions: () -> Unit = {},
    onOpenOptionsSettings: () -> Unit = {},
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
    var pendingGenerationDeletion by remember(sessionId) { mutableStateOf<CompanionBranchChoice.Generation?>(null) }
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
    val displayIssue = if (active && !activeBusy)
        controllerState.traces.lastOrNull { it.status == "display" }?.detail else null
    LaunchedEffect(displayIssue) {
        displayIssue?.let { snackbar.showSnackbar("正文显示未完成：$it；已保留原正文") }
    }
    if (session == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) { Text("请选择会话") }
        }
        return
    }
    pendingGenerationDeletion?.let { choice ->
        AlertDialog(onDismissRequest = { pendingGenerationDeletion = null },
            title = { Text("删除此候选？") },
            text = { Text("此候选及沿用它的后续分支会被删除，其他提示词版本和候选仍保留。") },
            confirmButton = { TextButton(onClick = {
                pendingGenerationDeletion = null
                scope.launch {
                    controller.deleteGenerationVariant(session.id, choice.turnId, choice.variantId)
                        .firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingGenerationDeletion = null }) { Text("取消") } })
    }
    val pendingRecovery = session.pendingRun

    val appearanceSettings = LocalSettings.current
    val displaySession = if (activeBusy) controllerState.generationPreview ?: session else session
    val menuAvailable = remember(library, displaySession) {
        menuCards(library, displaySession).isNotEmpty()
    }
    var openedCardId by remember(session.id) { mutableStateOf<String?>(null) }
    val history = remember(displaySession.messages, displaySession.turnRecords, displaySession.inactivePaths) { projectCompanionHistory(displaySession) }

    // Visual preferences stay live; execution receipts and model history are never rewritten.
    val bodyTemplate = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        ?.bodyAppearanceTemplate ?: BodyAppearanceTemplates.DEFAULT
    val pendingOutputs = if (active) controllerState.pendingOutputs else emptyList()
    val bodyContent = remember(history, displaySession.messages, bodyTemplate, pendingOutputs) {
        val content = history.bodyDisplays.filterValues { it.html != null }.mapValues { (id, display) ->
            @Composable { FrozenHtmlBody(display, history.presentationIds[id]?.toString() ?: id.toString()) }
        }.toMutableMap()
        if (BodyAppearanceTemplates.isCustom(bodyTemplate)) {
            val styledBodies = mutableMapOf<kotlin.uuid.Uuid, Pair<String, String>>()
            val bodies = displaySession.messages.filter {
                !it.deleted && it.role == Role.ASSISTANT && it.outputSlot == OutputSlot.BODY
            }.map { it.id }.toSet()
            history.nodes.forEach { node ->
                node.messages.forEach { message ->
                    if (history.logicalMessageIds[message.id] in bodies) {
                        val markdown = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }
                        styledBodies[message.id] = (history.presentationIds[message.id]?.toString() ?: message.id.toString()) to markdown
                    }
                }
            }
            pendingOutputs.filter { it.outputSlot == OutputSlot.BODY && it.text.isNotEmpty() }.forEach { output ->
                val id = pendingOutputId(session.id, output)
                styledBodies[id] = id.toString() to output.text
            }
            styledBodies.forEach { (id, body) ->
                content[id] = @Composable { StyledBody(bodyTemplate, body.second, body.first) }
            }
        }
        content
    }
    val presentation = remember(appearanceSettings, library, session.characterId, session.mainCallId) {
        projectCompanionSettings(appearanceSettings, library, session)
    }
    val projection = remember(presentation, displaySession, pendingOutputs, history) {
        projectCompanionChat(appearanceSettings, library, displaySession, pendingOutputs, history.nodes, presentation)
    }
    val settings = projection.settings
    val conversation = projection.conversation
    val input = remember(session.id) {
        ChatInputState().also { it.setMessageText(drafts[session.id].orEmpty()) }
    }
    var editingCoreMessageId by remember(session.id) { mutableStateOf<String?>(null) }
    val acceptedOptionsSourceKey = remember(library, session) { AutomaticOptions.source(library, session)?.key }
    val optionsSourceKey = acceptedOptionsSourceKey.takeUnless { activeBusy && controllerState.generationPreview != null }
    val currentOptions = optionsState?.takeIf {
        active && optionsSourceKey != null && it.sessionId == session.id && it.sourceKey == optionsSourceKey
    }
    var pendingOption by remember(session.id, optionsSourceKey, library.automaticOptions) { mutableStateOf<AutomaticOption?>(null) }
    fun optionStillCurrent(text: AutomaticOption): Boolean {
        val latest = controller.library.value
        val latestSession = latest.sessions.firstOrNull { it.id == session.id } ?: return false
        val latestOptions = controller.automaticOptions.value
        return !input.isEditing() && latest.automaticOptions.enabled && latest.selectedSessionId == session.id &&
            latest.automaticOptions == library.automaticOptions && latestOptions.sessionId == session.id &&
            latestOptions.sourceKey == optionsSourceKey && text in latestOptions.options &&
            optionsSourceKey != null && AutomaticOptions.source(latest, latestSession)?.key == optionsSourceKey &&
            text in currentOptions?.options.orEmpty()
    }
    fun fillOption(text: AutomaticOption, append: Boolean = false) {
        if (!optionStillCurrent(text)) return
        if (append) input.appendText((if (input.textContent.text.isBlank()) "" else "\n") + text.content)
        else input.setMessageText(text.content)
        pendingOption = null
    }
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
        val raw = input.textContent.text.toString()
        val text = if (input.isEditing()) raw.trim() else raw
        if (text.isBlank()) return
        scope.launch {
            if (activeBusy) {
                snackbar.showSnackbar("请等待当前回复完成，或先停止生成")
            } else if (input.isEditing()) {
                val messageId = editingCoreMessageId
                if (messageId == null) {
                    snackbar.showSnackbar("编辑目标已失效，请重新选择消息")
                } else {
                    val original = session.messages.firstOrNull { it.id == messageId }
                    val failure = if (original?.role == Role.USER) {
                        if (controller.editUserAndSend(session.id, messageId, text)) null
                        else controller.error.value ?: "消息未发送，请重试"
                    } else controller.editMessage(session.id, messageId, text).firstOrNull { it.error }?.message
                    if (failure == null) {
                        editingCoreMessageId = null
                        input.clearInput()
                        drafts = drafts - session.id
                    } else snackbar.showSnackbar(failure)
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
    LaunchedEffect(currentOptions?.error) {
        currentOptions?.error?.takeIf { it.isNotBlank() }?.let { snackbar.showSnackbar(it) }
    }
    dev.gray.companion.ui.ChatImmersiveMode()
    CompositionLocalProvider(LocalSettings provides settings) {
        OriginalChatPageLayout(
                compactTopEdge = true,
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
                        onClickMenu = {},
                        companionMenu = true,
                        companionMenuEnabled = menuAvailable,
                        companionMenuContent = { close ->
                            CardMenuItems(library, displaySession, { openedCardId = it }, close)
                        },
                        companionSubtitle = listOfNotNull(
                            library.mainCalls.firstOrNull { it.id == session.mainCallId }?.name,
                            library.characters.firstOrNull { it.id == session.characterId }?.name,
                        ).joinToString(" / "),
                        onNewChat = onNewSession,
                        onUpdateTitle = { title ->
                            scope.launch {
                                val issues = controller.renameSession(session.id, title)
                                issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                            }
                        },
                        onOpenDrawer = onOpenDrawer,
                    )
                },
                bottomBar = { hazeState ->
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                    CardRegionDisplay(library, displaySession, CardRegion.INPUT)
                    ChatInput(
                        modifier = Modifier.fillMaxWidth(),
                        state = input,
                        completionProviders = if (input.isEditing()) emptyList() else remember(session.userMacros) {
                            listOf(userMacroCompletionProvider(session.userMacros))
                        },
                        outputTransformation = if (input.isEditing()) null else remember(session.userMacros) {
                            userMacroOutputTransformation(session.userMacros)
                        },
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
                        editDescription = if (session.messages.firstOrNull { it.id == editingCoreMessageId }?.role == Role.USER)
                            "发送后生成新版本，原提示词与回复仍保留"
                        else "修改回复文本，不重算世界状态",
                    )
                    }
                },
                chatList = { padding, hazeState ->
                    Box(Modifier.fillMaxSize()) {
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
                        val original = session.messages.firstOrNull { it.id == messageId && !it.deleted && it.role in setOf(Role.USER, Role.ASSISTANT) && it.outputSlot == OutputSlot.BODY }
                        if (original == null) scope.launch { snackbar.showSnackbar("消息已失效，请重试") }
                        else if (original.authoredOpening && (activeBusy || !MessageOperations.canSelectOpening(session, original.id)))
                            scope.launch { snackbar.showSnackbar("对话开始后请新建聊天修改开场白") }
                        else {
                            editingCoreMessageId = original.id
                            input.editingMessage = uiMessage.id
                            input.setContents(listOf(UIMessagePart.Text(original.text)))
                        }
                    },
                    onRegenerate = { uiMessage ->
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        if (messageId == null) scope.launch { snackbar.showSnackbar("消息已失效，请重试") }
                        else if (session.messages.any { it.id == messageId && it.authoredOpening })
                            scope.launch { snackbar.showSnackbar("开场白来自角色卡；请用左右箭头选择，或新建聊天") }
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
                            if (choice is CompanionBranchChoice.Opening && (activeBusy || !MessageOperations.canSelectOpening(session, choice.messageId))) {
                                snackbar.showSnackbar("对话开始后请新建聊天修改开场白")
                                return@launch
                            }
                            val issues = when (choice) {
                                is CompanionBranchChoice.Generation -> {
                                    pendingGenerationDeletion = choice
                                    emptyList()
                                }
                                is CompanionBranchChoice.User -> controller.deleteMessage(session.id, choice.messageId)
                                is CompanionBranchChoice.Text -> controller.deleteMessage(session.id, choice.messageId)
                                is CompanionBranchChoice.Opening -> controller.deleteMessage(session.id, choice.messageId)
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
                                is CompanionBranchChoice.User -> controller.selectUserVersion(
                                    session.id, choice.messageId, choice.revisionId)
                                is CompanionBranchChoice.Text -> controller.selectTextRevision(
                                    session.id, choice.messageId, choice.revisionId)
                                is CompanionBranchChoice.Opening -> if (activeBusy || !MessageOperations.canSelectOpening(session, choice.messageId)) {
                                    snackbar.showSnackbar("对话开始后请新建聊天选择开场白")
                                    return@launch
                                } else controller.selectOpeningRevision(session.id, choice.messageId, choice.revisionId)
                                null -> null
                            }
                            if (issues == null) snackbar.showSnackbar("消息分支已失效，请重试")
                            else issues.firstOrNull { it.error }?.let { snackbar.showSnackbar(it.message) }
                        }
                    },
                    readOnly = !active || activeBusy || pendingRecovery != null,
                    compactBranchActions = true,
                    messageActionsEnabled = { !input.isEditing() },
                    enableTts = false,
                    assistantTextContent = bodyContent,
                    branchSelectionEnabled = { node ->
                        val opening = history.branchChoices[node.id]?.firstOrNull() as? CompanionBranchChoice.Opening
                        opening == null || (!activeBusy && MessageOperations.canSelectOpening(session, opening.messageId))
                    },
                    onBranchSelectionDisabled = {
                        scope.launch { snackbar.showSnackbar("对话开始后请新建聊天选择开场白") }
                    },
                    afterMessage = { node ->
                        val messageId = node.messages.getOrNull(node.selectIndex)?.id?.let { history.logicalMessageIds[it] }
                        val body = displaySession.messages.firstOrNull { it.id == messageId && !it.deleted && it.role == Role.ASSISTANT && it.outputSlot == OutputSlot.BODY }
                        if (body != null) {
                            if (dev.gray.companion.core.modelResponseWasTruncated(body.modelFinishReason))
                                Text("输出已达到模型长度限制，正文可能未完结", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            CardRegionDisplay(library, displaySession, CardRegion.CHAT,
                                body, allowLegacyFloor = !activeBusy)
                        }
                    },
                    footer = {
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
                            onRollbackUser = {
                                val turnId = pendingRecovery?.turnId
                                val runId = pendingRecovery?.id
                                if (turnId == null || runId == null) "断点已改变，请重新打开恢复选项"
                                else controller.rollbackPendingUserTurn(session.id, runId, turnId).firstOrNull { it.error }?.message
                            },
                        )
                    },
                    )
                    // Overlay only the option chips; do not grow the Scaffold's bottom bar.
                    BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(end = 8.dp, bottom = 8.dp)) {
                        if (active && library.automaticOptions.enabled && !input.isEditing()) AutomaticOptionsStrip(
                            modifier = Modifier.align(Alignment.BottomEnd).widthIn(max = 240.dp)
                                .heightIn(max = minOf(320.dp, maxHeight)),
                            options = currentOptions?.options.orEmpty(),
                            loading = currentOptions?.loading == true,
                            error = currentOptions?.error,
                            repairAvailable = currentOptions?.repairAvailable == true,
                            sourceReady = optionsSourceKey != null,
                            promptReady = library.automaticOptions.blocks != null || library.automaticOptions.prompt.isNotBlank(),
                            onSelect = { option ->
                                if (optionStillCurrent(option)) {
                                    if (input.textContent.text.isBlank()) fillOption(option) else pendingOption = option
                                }
                            },
                            onGenerate = onGenerateOptions,
                            onSettings = onOpenOptionsSettings,
                        )
                    }
                    }
                },
            )
        openedCardId?.let { id ->
            CardMenuDialog(library, displaySession, id) { openedCardId = null }
        }
        pendingOption?.takeIf { active && it in currentOptions?.options.orEmpty() }?.let { text ->
            AlertDialog(onDismissRequest = { pendingOption = null }, title = { Text("输入框已有草稿") },
                text = { Text("用这个选项替换草稿，还是追加到草稿后？") },
                confirmButton = { TextButton(onClick = { fillOption(text) }, enabled = !input.isEditing()) { Text("替换") } },
                dismissButton = { Row {
                    TextButton(onClick = { fillOption(text, append = true) }, enabled = !input.isEditing()) { Text("追加") }
                    TextButton(onClick = { pendingOption = null }) { Text("取消") }
                } })
        }
    }
}

@Composable
private fun AutomaticOptionsStrip(
    options: List<AutomaticOption>, loading: Boolean, error: String?, repairAvailable: Boolean, sourceReady: Boolean,
    promptReady: Boolean, onSelect: (AutomaticOption) -> Unit,
    onGenerate: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(options) { mutableStateOf<AutomaticOption?>(null) }
    Column(modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { option ->
            AssistChip(onClick = {
                if (option.title != null) expanded = if (expanded == option) null else option else onSelect(option)
            }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                label = { Text(option.title ?: option.content, maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
            if (expanded == option) Surface(shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(12.dp)) {
                    Text(option.content, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    Row {
                        TextButton(onClick = { onSelect(option) }) { Text("填入输入框") }
                        TextButton(onClick = { expanded = null }) { Text("收起") }
                    }
                }
            }
        }
        if (loading) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            CircularProgressIndicator(Modifier.padding(10.dp).size(16.dp), strokeWidth = 2.dp)
        } else if (options.isEmpty() || error != null) {
            AssistChip(onClick = if (!promptReady) onSettings else onGenerate,
                enabled = !promptReady || sourceReady,
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                label = { Text(if (error != null && repairAvailable) "修复并重试" else if (error != null) "重试选项" else "生成选项") })
        }
    }
}
