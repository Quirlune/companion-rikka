package me.rerere.rikkahub.companion

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material3.DrawerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gray.companion.AppController
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel
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
import dev.gray.companion.ui.HtmlRenderMemory
import dev.gray.companion.ui.LocalHtmlRenderMemory
import dev.gray.companion.ui.IllustrationBodyImage
import dev.gray.companion.ui.menuCards
import dev.gray.companion.ui.CardRegionDisplay
import dev.gray.companion.ui.RecoveryDialog
import dev.gray.companion.ui.CrashDiagnosticsDialog
import dev.gray.companion.ui.userMacroCompletionProvider
import dev.gray.companion.ui.userMacroOutputTransformation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.message.CompanionToolbarAction
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.ListRestart
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.TopBar
import me.rerere.rikkahub.ui.pages.chat.OriginalChatPageLayout

/** The copied RikkaHub chat surface backed only by Companion's session controller. */
@OptIn(ExperimentalFoundationApi::class)
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
    onOpenSavedChats: () -> Unit = {},
) {
    val library by controller.library.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val controllerState by controller.state.collectAsStateWithLifecycle()
    val illustrationActivity by controller.illustrationActivity.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val errorSerial by AppEventLog.errorSerial.collectAsStateWithLifecycle()
    val session = library.sessions.firstOrNull { it.id == sessionId }
    val active = sessionId == library.selectedSessionId
    val activeBusy = active && busy
    val activeError = if (active) error else null
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showLogs by remember(sessionId) { mutableStateOf(false) }
    fun logUiIssue(message: String, source: String = "聊天", level: AppLogLevel = AppLogLevel.ERROR) {
        AppEventLog.record(source, message, level)
    }
    val appContext = LocalContext.current
    var messageOutline by remember { mutableStateOf(false) }
    var pendingGenerationDeletion by remember(sessionId) { mutableStateOf<CompanionBranchChoice.Generation?>(null) }
    var pendingMessageDeletion by remember(sessionId) { mutableStateOf<CompanionBranchChoice?>(null) }
    var drafts by rememberSaveable(stateSaver = listSaver<Map<String, String>, String>(
        save = { values -> values.flatMap { (id, text) -> listOf(id, text) } },
        restore = { values -> values.chunked(2).associate { it[0] to it[1] } },
    )) { mutableStateOf(emptyMap()) }

    LaunchedEffect(activeError) {
        activeError?.takeIf { it.isNotBlank() }?.let {
            val retryAvailable = session?.messages?.lastOrNull()?.role == Role.USER
            if (retryAvailable && snackbar.showSnackbar(it, actionLabel = "重试") == SnackbarResult.ActionPerformed) {
                controller.retry()
            }
        }
    }
    val displayIssue = if (active && !activeBusy)
        controllerState.traces.lastOrNull { it.status == "display" }?.detail else null
    LaunchedEffect(displayIssue) {
        displayIssue?.let { logUiIssue("正文显示未完成：$it", "正文显示", AppLogLevel.ERROR) }
    }
    if (session == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) { Text("请选择会话") }
        }
        return
    }
    val pendingRecovery = session.pendingRun
    pendingGenerationDeletion?.let { choice ->
        AlertDialog(onDismissRequest = { pendingGenerationDeletion = null },
            title = { Text("删除此候选？") },
            text = { Text("此候选及沿用它的后续分支会被删除，其他提示词版本和候选仍保留。") },
            confirmButton = { TextButton(onClick = {
                pendingGenerationDeletion = null
                scope.launch {
                    controller.deleteGenerationVariant(session.id, choice.turnId, choice.variantId)
                        .firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                }
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingGenerationDeletion = null }) { Text("取消") } })
    }
    pendingMessageDeletion?.let { choice ->
        val messageId = when (choice) {
            is CompanionBranchChoice.User -> choice.messageId
            is CompanionBranchChoice.Text -> choice.messageId
            is CompanionBranchChoice.Opening -> choice.messageId
            is CompanionBranchChoice.Generation -> null
        }
        if (messageId != null) {
            val rollsBackPendingTurn = choice is CompanionBranchChoice.User &&
                pendingRecovery?.takeUnless { it.regenerating }?.turnId == messageId
            AlertDialog(onDismissRequest = { pendingMessageDeletion = null },
                title = { Text(if (rollsBackPendingTurn) "删除并撤回本轮？" else "删除当前消息版本？") },
                text = { Text(if (rollsBackPendingTurn)
                    "删除触发本轮的用户消息会撤销本轮已保存结果，并回到发送前。"
                else "若有其他文字版本，会切换到剩余版本；若这是唯一版本，消息会被删除。此操作不会撤回已保存的生成结果。") },
                confirmButton = { TextButton(onClick = {
                    pendingMessageDeletion = null
                    scope.launch {
                        controller.deleteMessage(session.id, messageId)
                            .firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                    }
                }) { Text("删除") } },
                dismissButton = { TextButton(onClick = { pendingMessageDeletion = null }) { Text("取消") } })
        }
    }
    val appearanceSettings = LocalSettings.current
    val displaySession = if (activeBusy) controllerState.generationPreview ?: session else session
    val menuAvailable = remember(library, displaySession) {
        menuCards(library, displaySession).isNotEmpty()
    }
    val history = remember(displaySession.messages, displaySession.turnRecords, displaySession.inactivePaths,
        library.illustration.enabled, library.illustrationImages) {
        projectCompanionHistory(displaySession, library.illustrationImages, library.illustration.enabled)
    }

    // Visual preferences stay live; execution receipts and model history are never rewritten.
    val bodyTemplate = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        ?.bodyAppearanceTemplate ?: BodyAppearanceTemplates.DEFAULT
    val pendingOutputs = if (active) controllerState.pendingOutputs else emptyList()
    val illustrationRoot = remember(appContext.filesDir) { File(appContext.filesDir, "illustrations").canonicalFile }
    val bodyIllustrations = remember(history.inlineIllustrations, illustrationRoot) {
        history.inlineIllustrations.mapValues { (presentationId, annotations) ->
            annotations.mapIndexedNotNull { index, image ->
                val file = runCatching { File(image.localPath).canonicalFile }.getOrNull() ?: return@mapIndexedNotNull null
                val safeRoot = illustrationRoot.path + File.separator
                if (!illustrationRoot.isDirectory || !file.isFile || !file.path.startsWith(safeRoot) ||
                    file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp")) return@mapIndexedNotNull null
                IllustrationBodyImage(image.afterParagraphId, file,
                    "https://companion.invalid/illustration/$presentationId/$index", illustrationRoot)
            }
        }.filterValues { it.isNotEmpty() }
    }
    val bodyContent = remember(history, displaySession.messages, bodyTemplate, pendingOutputs, bodyIllustrations) {
        val content = history.bodyDisplays.filterValues { it.html != null }.mapValues { (id, display) ->
            @Composable { FrozenHtmlBody(display, history.presentationIds[id]?.toString() ?: id.toString()) }
        }.toMutableMap()
        val projectedTexts = mutableMapOf<kotlin.uuid.Uuid, String>()
        history.nodes.forEach { node -> node.messages.forEach { message ->
            if (message.id in bodyIllustrations) {
                projectedTexts[message.id] = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }
            }
        } }
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
        bodyIllustrations.forEach { (id, images) ->
            val markdown = projectedTexts[id] ?: return@forEach
            content[id] = @Composable {
                StyledBody(bodyTemplate, markdown, history.presentationIds[id]?.toString() ?: id.toString(), images)
            }
        }
        content
    }
    val selectableIllustrationBodies = remember(bodyIllustrations, bodyTemplate) {
        if (BodyAppearanceTemplates.isCustom(bodyTemplate)) emptySet() else bodyIllustrations.keys
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
    var recoveryActionSerial by remember(session.id) { mutableStateOf(0) }
    var recoveryDialogIntent by remember(session.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var continuingRunId by remember(session.id) { mutableStateOf<String?>(null) }
    fun invalidateRecoveryDialog() {
        recoveryActionSerial++
        recoveryDialogIntent = null
    }
    LaunchedEffect(session.id, pendingRecovery?.id, active, input.isEditing(), input.textContent.text.toString()) {
        if (!active) recoveryActionSerial++
        if (!active || pendingRecovery?.id != recoveryDialogIntent?.first || input.isEditing() || !input.isEmpty())
            recoveryDialogIntent = null
    }
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
    val chatListState = key(session.id) {
        rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(aheadFraction = 2f, behindFraction = 2f))
    }
    val htmlRenderMemory = remember(session.id) { HtmlRenderMemory() }
    var chatListInitialized by remember(session.id) { mutableStateOf(false) }
    var regenerationAnchor by remember(session.id) { mutableStateOf<String?>(null) }
    var pendingRegenerationAnchor by remember(session.id) { mutableStateOf<String?>(null) }
    // Regeneration temporarily removes the old answer (and any continuation). Re-anchor
    // only once after its preview projection contains the stable USER row.
    LaunchedEffect(session.id, pendingRegenerationAnchor, activeBusy,
        controllerState.generationPreview, history.nodes) {
        val anchor = pendingRegenerationAnchor ?: return@LaunchedEffect
        val preview = controllerState.generationPreview ?: displaySession
        if (!activeBusy || preview.messages.none { it.id == anchor && it.role == Role.USER && !it.deleted })
            return@LaunchedEffect
        val index = history.nodes.indexOfFirst { node ->
            node.messages.any { history.logicalMessageIds[it.id] == anchor }
        }
        if (index >= 0) {
            chatListState.requestScrollToItem(index)
            pendingRegenerationAnchor = null
        }
    }
    LaunchedEffect(session.id, targetMessageId, history.nodes, conversation.messageNodes.size) {
        if (targetMessageId != null) {
            val index = history.nodes.indexOfFirst { node ->
                node.messages.any { history.logicalMessageIds[it.id] == targetMessageId }
            }
            if (index >= 0) chatListState.scrollToItem(index)
            else logUiIssue("消息已不在当前会话中")
            chatListInitialized = true
            onTargetConsumed()
        } else if (!chatListInitialized && regenerationAnchor == null && conversation.messageNodes.isNotEmpty()) {
            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            chatListInitialized = true
        }
    }
    fun submit(answer: Boolean) {
        val raw = input.textContent.text.toString()
        val text = if (input.isEditing()) raw.trim() else raw
        if (text.isBlank()) return
        invalidateRecoveryDialog()
        regenerationAnchor = null
        pendingRegenerationAnchor = null
        scope.launch {
            if (activeBusy) {
                logUiIssue("请等待当前回复完成，或先停止生成")
            } else if (input.isEditing()) {
                val messageId = editingCoreMessageId
                if (messageId == null) {
                    logUiIssue("编辑目标已失效，请重新选择消息")
                } else {
                    val original = session.messages.firstOrNull { it.id == messageId }
                    val failure = if (original?.role == Role.USER) {
                        regenerationAnchor = messageId
                        pendingRegenerationAnchor = messageId
                        if (controller.editUserAndSend(session.id, messageId, text)) null
                        else {
                            regenerationAnchor = null
                            pendingRegenerationAnchor = null
                            controller.error.value ?: "消息未发送，请重试"
                        }
                    } else controller.editMessage(session.id, messageId, text).firstOrNull { it.error }?.message
                    if (failure == null) {
                        editingCoreMessageId = null
                        input.clearInput()
                        drafts = drafts - session.id
                    } else if (failure != controller.error.value) logUiIssue(failure, level = AppLogLevel.ERROR)
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
                } else if (failure != controller.error.value) logUiIssue(failure, level = AppLogLevel.ERROR)
            }
        }
    }
    LaunchedEffect(currentOptions?.error) {
        currentOptions?.error?.takeIf { it.isNotBlank() }?.let { logUiIssue(it, "自动选项", AppLogLevel.ERROR) }
    }
    dev.gray.companion.ui.ChatImmersiveMode()
    CompositionLocalProvider(LocalSettings provides settings,
        LocalHtmlRenderMemory provides htmlRenderMemory) {
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
                        companionMenuEnabled = true,
                        companionErrorSerial = errorSerial,
                        companionMenuContent = { close ->
                            if (menuAvailable) {
                                CardRegionDisplay(library, displaySession, CardRegion.MENU)
                                HorizontalDivider()
                            }
                            DropdownMenuItem(text = { Text("收藏当前副本") }, enabled = !activeBusy, onClick = {
                                close()
                                scope.launch {
                                    val issues = controller.saveCurrentChatCopy(session.id)
                                    if (issues.any { it.error }) issues.filter { it.error }.forEach { logUiIssue(it.message, "收藏") }
                                    else AppEventLog.record("收藏", "当前对话的独立副本已保存")
                                }
                            })
                            DropdownMenuItem(text = { Text("收藏夹") }, onClick = { close(); onOpenSavedChats() })
                            DropdownMenuItem(text = { Text("日志") }, onClick = { close(); showLogs = true })
                        },
                        companionSubtitle = listOfNotNull(
                            library.mainCalls.firstOrNull { it.id == session.mainCallId }?.name,
                            library.characters.firstOrNull { it.id == session.characterId }?.name,
                        ).joinToString(" / "),
                        onNewChat = onNewSession,
                        onUpdateTitle = { title ->
                            scope.launch {
                                val issues = controller.renameSession(session.id, title)
                                issues.firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                            }
                        },
                        onOpenDrawer = onOpenDrawer,
                    )
                },
                bottomBar = { hazeState ->
                    Column {
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
                        showModelSelector = false,
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
                                    issues.firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                                }
                            }
                        },
                        onUpdateAssistant = {},
                        onUpdateSearchService = {},
                        onMoreClick = null,
                        onCancelClick = { invalidateRecoveryDialog(); controller.stop() },
                        onSendClick = { submit(answer = true) },
                        onLongSendClick = { submit(answer = false) },
                        onContinueClick = if (active && pendingRecovery != null) ({
                            val sourceRunId = pendingRecovery.id
                            val sourceSessionId = session.id
                            val sourceSerial = recoveryActionSerial
                            val current = controller.library.value
                            if (continuingRunId == null && !controller.busy.value &&
                                current.selectedSessionId == sourceSessionId &&
                                current.sessions.firstOrNull { it.id == sourceSessionId }?.pendingRun?.id == sourceRunId) {
                                recoveryDialogIntent = null
                                continuingRunId = sourceRunId
                                scope.launch {
                                    try {
                                        controller.continueRecovery(sourceSessionId, sourceRunId)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        logUiIssue(failure.message ?: "继续运行失败，请稍后重试", "恢复", AppLogLevel.ERROR)
                                    } finally {
                                        val latest = controller.library.value
                                        if (recoveryActionSerial == sourceSerial && !input.isEditing() && input.isEmpty() &&
                                            latest.selectedSessionId == sourceSessionId &&
                                            latest.sessions.firstOrNull { it.id == sourceSessionId }?.pendingRun?.id == sourceRunId)
                                            recoveryDialogIntent = sourceRunId to sourceSerial
                                        continuingRunId = null
                                    }
                                }
                            }
                        }) else null,
                        continueEnabled = continuingRunId == null,
                        textOnly = true,
                        translucentBackground = true,
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
                    autoFollowGeneration = regenerationAnchor == null,
                    processingStatus = if (active) controllerState.traces.lastOrNull()?.title else null,
                    responseStarted = pendingOutputs.any {
                        it.outputSlot == OutputSlot.BODY && (it.reasoningSnapshot != null || it.text.isNotBlank())
                    },
                    activeResponseIds = pendingOutputs.mapTo(mutableSetOf()) { pendingOutputId(session.id, it) },
                    previewMode = messageOutline,
                    settings = settings,
                    hazeState = hazeState,
                    onJumpToMessage = { index ->
                        messageOutline = false
                        scope.launch { chatListState.requestScrollToItem(index) }
                    },
                    onEdit = { uiMessage ->
                        invalidateRecoveryDialog()
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        val original = session.messages.firstOrNull { it.id == messageId && !it.deleted && it.role in setOf(Role.USER, Role.ASSISTANT) && it.outputSlot == OutputSlot.BODY }
                        if (original == null) logUiIssue("消息已失效，请重试")
                        else if (original.authoredOpening && (activeBusy || !MessageOperations.canSelectOpening(session, original.id)))
                            logUiIssue("对话开始后请新建聊天修改开场白")
                        else {
                            editingCoreMessageId = original.id
                            input.editingMessage = uiMessage.id
                            input.setContents(listOf(UIMessagePart.Text(original.text)))
                        }
                    },
                    onRegenerate = { uiMessage ->
                        invalidateRecoveryDialog()
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        if (messageId == null) logUiIssue("消息已失效，请重试")
                        else if (session.messages.any { it.id == messageId && it.authoredOpening })
                            logUiIssue("开场白来自角色卡；请用左右箭头选择，或新建聊天")
                        else scope.launch {
                            val target = session.messages.firstOrNull { it.id == messageId }
                            val anchor = if (target?.role == Role.USER) target.id else target?.turnId
                            if (anchor == null) {
                                logUiIssue("这条回复没有用户轮次来源")
                                return@launch
                            }
                            regenerationAnchor = anchor
                            pendingRegenerationAnchor = anchor
                            if (!controller.regenerateMessage(session.id, messageId)) {
                                if (regenerationAnchor == anchor) regenerationAnchor = null
                                if (pendingRegenerationAnchor == anchor) pendingRegenerationAnchor = null
                                if (controller.error.value == null) logUiIssue("重新生成失败", level = AppLogLevel.ERROR)
                            }
                        }
                    },
                    onForkMessage = { uiMessage ->
                        invalidateRecoveryDialog()
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        if (messageId == null) logUiIssue("消息已失效，请重试")
                        else scope.launch {
                            controller.forkMessage(session.id, messageId)
                                .firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                        }
                    },
                    onDelete = { uiMessage ->
                        invalidateRecoveryDialog()
                        val node = history.nodes.firstOrNull { it.currentMessage.id == uiMessage.id }
                        val choice = node?.let { history.branchChoices[it.id]?.getOrNull(it.selectIndex) }
                        scope.launch {
                            if (choice is CompanionBranchChoice.Opening && (activeBusy || !MessageOperations.canSelectOpening(session, choice.messageId))) {
                                logUiIssue("对话开始后请新建聊天修改开场白")
                                return@launch
                            }
                            when (choice) {
                                is CompanionBranchChoice.Generation -> {
                                    pendingGenerationDeletion = choice
                                }
                                is CompanionBranchChoice.User,
                                is CompanionBranchChoice.Text,
                                is CompanionBranchChoice.Opening -> {
                                    pendingMessageDeletion = choice
                                }
                                null -> logUiIssue("消息已失效，请重试")
                            }
                        }
                    },
                    onUpdateMessage = { changed ->
                        invalidateRecoveryDialog()
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
                                    logUiIssue("对话开始后请新建聊天选择开场白")
                                    return@launch
                                } else controller.selectOpeningRevision(session.id, choice.messageId, choice.revisionId)
                                null -> null
                            }
                            if (issues == null) logUiIssue("消息分支已失效，请重试")
                            else issues.firstOrNull { it.error }?.let { logUiIssue(it.message, level = AppLogLevel.ERROR) }
                        }
                    },
                    readOnly = !active || activeBusy,
                    compactBranchActions = true,
                    rerollActions = { uiMessage ->
                        val messageId = history.logicalMessageIds[uiMessage.id]
                        val message = session.messages.firstOrNull { it.id == messageId && !it.deleted }
                        buildList {
                            if (message?.role == Role.ASSISTANT && message.outputSlot == OutputSlot.BODY) {
                                if (library.automaticOptions.enabled &&
                                    AutomaticOptions.source(library, session)?.turns?.lastOrNull()?.assistants?.any { it.id == message.id } == true)
                                    add(CompanionToolbarAction(Lucide.ListRestart,
                                        if (currentOptions?.options.isNullOrEmpty()) "生成选项" else "重新生成选项",
                                        !activeBusy && currentOptions?.loading != true) {
                                        if (library.automaticOptions.blocks != null || library.automaticOptions.prompt.isNotBlank())
                                            onGenerateOptions()
                                        else onOpenOptionsSettings()
                                    })
                                if (library.illustration.enabled) add(CompanionToolbarAction(Lucide.Image, "重新生成插图", !activeBusy && !illustrationActivity.uncertainDelivery) {
                                    if (!controller.regenerateIllustration(session.id, message.id))
                                        logUiIssue("插图暂时无法重绘：请检查来源、设置或等待当前任务结束", "插图", AppLogLevel.ERROR)
                                })
                            }
                        }
                    },
                    messageActionsEnabled = { !input.isEditing() },
                    enableTts = false,
                    assistantTextContent = bodyContent,
                    assistantTextContentSelectable = selectableIllustrationBodies,
                    // HTML bodies and cards contain WebViews. Replaying them through Haze's
                    // RenderEffect capture can blank their entire surface (haze issues #99/#745).
                    // Keep the page background as the blur source; draw chat content only once.
                    captureForBlur = false,
                    branchSelectionEnabled = { node ->
                        val opening = history.branchChoices[node.id]?.firstOrNull() as? CompanionBranchChoice.Opening
                        opening == null || (!activeBusy && MessageOperations.canSelectOpening(session, opening.messageId))
                    },
                    onBranchSelectionDisabled = {
                        logUiIssue("对话开始后请新建聊天选择开场白")
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
                    footer = {},
                    )
                    // ChatList fills its parent. Keep the option controls in the same Box so
                    // they overlay the list instead of receiving zero height below it.
                        if (active && library.automaticOptions.enabled && !input.isEditing()) AutomaticOptionsStrip(
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .offset(x = (-8).dp, y = -(padding.calculateBottomPadding() + 8.dp))
                                .size(48.dp),
                            options = currentOptions?.options.orEmpty(),
                            loading = currentOptions?.loading == true,
                            onSelect = { option ->
                                if (optionStillCurrent(option)) {
                                    if (input.textContent.text.isBlank()) fillOption(option) else pendingOption = option
                                }
                            },
                        )
                    }
                },
            )
        if (showLogs) CrashDiagnosticsDialog { showLogs = false }
        pendingOption?.takeIf { active && it in currentOptions?.options.orEmpty() }?.let { text ->
            AlertDialog(onDismissRequest = { pendingOption = null }, title = { Text("输入框已有草稿") },
                text = { Text("用这个选项替换草稿，还是追加到草稿后？") },
                confirmButton = { TextButton(onClick = { fillOption(text) }, enabled = !input.isEditing()) { Text("替换") } },
                dismissButton = { Row {
                    TextButton(onClick = { fillOption(text, append = true) }, enabled = !input.isEditing()) { Text("追加") }
                    TextButton(onClick = { pendingOption = null }) { Text("取消") }
                } })
        }
        val dialogRecovery = pendingRecovery?.takeIf {
            active && recoveryDialogIntent == (it.id to recoveryActionSerial) && !input.isEditing() && input.isEmpty()
        }
        dialogRecovery?.let { recovery ->
            RecoveryDialog(
                recovery = recovery,
                busy = activeBusy,
                onDismiss = { recoveryDialogIntent = null },
                onRetry = { failure, correction ->
                    controller.recover(session.id, RecoveryDecision(
                        kind = RecoveryDecisionKind.RETRY, nodeKey = failure.nodeKey, correction = correction))
                },
                onSkip = { failure ->
                    controller.recover(session.id, RecoveryDecision(
                        kind = RecoveryDecisionKind.SKIP, nodeKey = failure.nodeKey))
                },
                onManual = { failure, manualText, correction ->
                    controller.recover(session.id, RecoveryDecision(
                        kind = RecoveryDecisionKind.MANUAL, nodeKey = failure.nodeKey,
                        manualText = manualText, correction = correction))
                },
                onEditPrompt = onRecoveryEditPrompt,
                onFinish = { controller.finishRecovery(session.id, recovery.id) },
                onRollbackUser = {
                    controller.rollbackPendingUserTurn(session.id, recovery.id, recovery.turnId)
                        .firstOrNull { it.error }?.message
                },
            )
        }
    }
}

@Composable
private fun AutomaticOptionsStrip(
    options: List<AutomaticOption>, loading: Boolean, onSelect: (AutomaticOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty() && !loading) return
    var menuOpen by remember(options) { mutableStateOf(false) }
    var expandedOption by remember(options) { mutableStateOf<AutomaticOption?>(null) }
    Box(modifier) {
        IconButton(onClick = { menuOpen = true }, enabled = options.isNotEmpty(), modifier = Modifier.size(48.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Lucide.ListRestart, contentDescription = "查看自动选项")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; expandedOption = null },
            modifier = Modifier.widthIn(max = 300.dp).heightIn(max = 320.dp)) {
            options.forEach { option ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    TextButton(onClick = {
                        if (option.title == null) { onSelect(option); menuOpen = false }
                        else expandedOption = if (expandedOption == option) null else option
                    }) { Text(option.title ?: option.content, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    if (expandedOption == option) {
                        Text(option.content, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                        TextButton(onClick = { onSelect(option); menuOpen = false; expandedOption = null }) {
                            Text("填入输入框")
                        }
                    }
                }
            }
        }
    }
}
