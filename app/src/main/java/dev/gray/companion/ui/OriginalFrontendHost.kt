package dev.gray.companion.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import dev.gray.companion.AppController
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel
import dev.gray.companion.core.*
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.RikkaNavDisplay
import me.rerere.rikkahub.rikkaChatTransitions
import me.rerere.rikkahub.ui.pages.setting.SettingPageLayout
import me.rerere.rikkahub.companion.CompanionChatPage
import me.rerere.rikkahub.companion.CompanionSearchPage
import me.rerere.rikkahub.companion.CompanionDrawer as OriginalDrawer
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.*
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/** Original frontend/navigation, with Companion as the only resource and conversation authority. */
@Composable
fun OriginalFrontendHost(controller: AppController, onExport: () -> Unit, onImport: () -> Unit, externalError: String?) {
    val library by controller.library.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val automaticOptions by controller.automaticOptions.collectAsStateWithLifecycle()
    val illustrationActivity by controller.illustrationActivity.collectAsStateWithLifecycle()
    val importPreview by controller.configurationImport.collectAsStateWithLifecycle()
    val usage by controller.usage.collectAsStateWithLifecycle()
    val usageError by controller.usageError.collectAsStateWithLifecycle()
    val settings by rememberUserSettingsState()
    val stack = rememberNavBackStack(Screen.Chat(library.selectedSessionId ?: "active"))
    val navigator = remember(stack) { Navigator(stack) }
    LaunchedEffect(stack.lastOrNull()) {
        AppEventLog.record("导航", "打开 ${stack.lastOrNull()?.javaClass?.simpleName ?: "页面"}")
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val toaster = rememberToasterState()
    val notices = remember(toaster) { UiToaster(toaster, logNotices = true) }
    var usageDays by remember { mutableIntStateOf(7) }
    var usageRefreshedAt by remember { mutableLongStateOf(0L) }
    val usageAsOf = remember(usage, usageRefreshedAt) { System.currentTimeMillis() }
    var saving by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var consumedSearchTarget by remember { mutableStateOf<String?>(null) }
    var configurationIssues by remember { mutableStateOf<List<Issue>>(emptyList()) }

    suspend fun report(issues: List<Issue>): Boolean {
        if (issues.any { it.error && ResourceLocations.resolveIssue(library, it) != null } && stack.lastOrNull() !is Screen.CompanionEditor && stack.lastOrNull() !is Screen.CompanionMemory) configurationIssues = issues
        issues.forEach { AppEventLog.record("配置", it.message, if (it.error) AppLogLevel.ERROR else AppLogLevel.WARNING) }
        return issues.none { it.error }
    }
    fun update(transform: (Library) -> Library) { scope.launch { report(controller.updateLibrary(transform)) } }
    fun open(screen: Screen) { scope.launch { drawer.close(); navigator.navigate(screen) } }
    fun edit(target: Editing) { open(Screen.CompanionEditor(target.kind.route, target.id)) }
    fun openLocation(location: ResourceLocation) { open(sourceEditorScreen(location, library)) }
    fun applyCommand(command: (Library) -> ExtensionEditResult) {
        if (saving) return
        saving = true
        scope.launch {
            try { report(controller.updateResources(command)) }
            finally { saving = false }
        }
    }
    fun applySourceEdit(before: Library, candidate: Library, onResult: (Boolean) -> Unit) {
        if (saving) { onResult(false); return }
        saving = true
        scope.launch {
            var accepted = false
            try {
                val preview = ConfigurationCommands.previewEdit(before, candidate, "来源编辑")
                val plan = preview.plan
                accepted = if (plan == null) {
                    report(preview.issues.ifEmpty { listOf(Issue("configuration", "来源修改未应用")) })
                    false
                }
                else report(controller.updateResources { current ->
                    val result = ConfigurationCommands.apply(plan, current)
                    ExtensionEditResult(result.library, result.issues, preview.changes.map { it.id }, receipt = result.receipt)
                })
            } finally { saving = false; onResult(accepted) }
        }
    }
    suspend fun commitProviders(plan: dev.gray.companion.CompanionProviderPlan): List<Issue> {
        if (saving) return listOf(Issue("providers", "正在保存，请稍后操作"))
        saving = true
        return try {
            val issues = controller.updateProviders(plan)
            report(issues)
            issues
        } finally { saving = false }
    }
    fun save(transform: (Library) -> Library) {
        if (saving) return
        saving = true
        scope.launch {
            try { if (report(controller.updateLibrary(transform))) navigator.popBackStack() }
            finally { saving = false }
        }
    }
    fun applyMemoryCommand(command: (Library) -> ExtensionEditResult, onResult: (Boolean) -> Unit) {
        if (saving) { onResult(false); return }
        saving = true
        scope.launch {
            var accepted = false
            try {
                accepted = report(controller.updateMemoryConfiguration(command))
            } finally { saving = false; onResult(accepted) }
        }
    }
    fun setMemoryEnabled(enabled: Boolean) {
        if (saving) return
        val expected = library.memoryConfig ?: return
        saving = true
        scope.launch {
            try { report(controller.setMemoryEnabled(expected, enabled)) }
            finally { saving = false }
        }
    }
    fun setExtensionEnabled(definition: ConfigurationExtension, enabled: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try { report(controller.setExtensionEnabled(definition, enabled)) }
            finally { saving = false }
        }
    }
    fun setAutomaticOptionsEnabled(enabled: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try { report(controller.saveAutomaticOptions(library.automaticOptions.copy(enabled = enabled))) }
            finally { saving = false }
        }
    }
    fun saveAutomaticOptions(config: AutomaticOptionsConfig) {
        if (saving) return
        saving = true
        scope.launch {
            try { if (report(controller.saveAutomaticOptions(config))) navigator.popBackStack() }
            finally { saving = false }
        }
    }
    fun setIllustrationEnabled(enabled: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            try { report(controller.saveIllustration(library.illustration.copy(enabled = enabled))) }
            finally { saving = false }
        }
    }
    fun saveIllustration(config: IllustrationConfig) {
        if (saving) return
        saving = true
        scope.launch {
            try { if (report(controller.saveIllustration(config))) navigator.popBackStack() }
            finally { saving = false }
        }
    }
    fun selectCharacter(id: String) { scope.launch {
        if (report(if (library.sessions.firstOrNull { it.id == library.selectedSessionId }?.characterId == id) controller.newSession() else controller.selectCharacter(id))) { drawer.close(); navigator.clearAndNavigate(Screen.Chat(controller.library.value.selectedSessionId ?: "active")) }
    } }
    fun newChat() { scope.launch {
        if (report(controller.newSession())) { drawer.close(); navigator.clearAndNavigate(Screen.Chat(controller.library.value.selectedSessionId ?: "active")) }
    } }
    LaunchedEffect(library.selectedSessionId, stack.lastOrNull()) {
        val sessionId = library.selectedSessionId ?: return@LaunchedEffect
        val current = stack.lastOrNull()
        if (current is Screen.Chat && current.id != sessionId) navigator.clearAndNavigate(Screen.Chat(sessionId))
    }
    LaunchedEffect(drawer.isOpen) { if (drawer.isOpen) {
        controller.refreshUsage(days = 3)
        usageRefreshedAt = System.currentTimeMillis()
    } }
    LaunchedEffect(stack.lastOrNull(), usageDays) {
        if (stack.lastOrNull() == Screen.CompanionUsage) {
            controller.refreshUsage(usageDays)
            usageRefreshedAt = System.currentTimeMillis()
        }
    }
    var cardLayoutOpen by remember { mutableStateOf(false) }
    var cardLayoutSaving by remember { mutableStateOf(false) }
    LaunchedEffect(externalError) { externalError?.let { AppEventLog.record("资源文件", it, AppLogLevel.ERROR) } }

    @Composable
    fun CurrentDrawer() {
                        OriginalDrawer(
                            library = library, usage = usage, usageError = usageError, usageAsOf = usageAsOf,
                            settings = settings,
                            onSelectSession = { id -> scope.launch { if (report(controller.selectSession(id))) { drawer.close(); navigator.clearAndNavigate(Screen.Chat(controller.library.value.selectedSessionId ?: "active")) } } },
                            onNewChat = ::newChat,
                            onSelectCharacter = ::selectCharacter,
                            onCreateCharacter = { edit(Editing(EditorKind.CHARACTER, newId())) },
                            onEditCharacter = { id -> library.characters.firstOrNull { it.id == id }?.let { edit(Editing(EditorKind.CHARACTER, it.id)) } },
                            onSettings = { open(Screen.Setting) },
                            onMainCalls = { open(Screen.CompanionResources(ResourceKind.MAIN.routeIndex)) },
                            onUsage = { usageDays = 7; open(Screen.CompanionUsage) },
                            onEditUser = { open(Screen.CompanionPersonas) },
                            onRenameSession = { id, title -> scope.launch { report(controller.renameSession(id, title)) } },
                            onCopySession = { id -> scope.launch { report(controller.copySession(id)) } },
                            onMoveSession = { id, folder -> scope.launch { report(controller.moveSession(id, folder)) } },
                            onCreateFolder = { name -> scope.launch { report(controller.createFolder(name)) } },
                            onRenameFolder = { id, name -> scope.launch { report(controller.renameFolder(id, name)) } },
                            onDeleteFolder = { id -> scope.launch { report(controller.deleteFolder(id)) } },
                            onDeleteSession = { id -> update { lib ->
                                val remaining = lib.sessions.filterNot { it.id == id }
                                val next = lib.copy(sessions = remaining, selectedSessionId = lib.selectedSessionId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
                                if (remaining.isEmpty()) next.startNewConversation() else next
                            } },
                        )
    }

    if (cardLayoutOpen) library.sessions.firstOrNull { it.id == library.selectedSessionId }?.let { current ->
        CardLayoutPanel(library, current, canEdit = !state.busy && !cardLayoutSaving,
            onChange = { next ->
                if (!cardLayoutSaving) {
                    cardLayoutSaving = true
                    scope.launch {
                        try { report(controller.updateCardLayout(current.id, current.cardPlacements, next)) }
                        finally { cardLayoutSaving = false }
                    }
                }
            }, onDismiss = { cardLayoutOpen = false })
    }

    // No screen in this host uses heroAnimation. SharedTransitionLayout creates a
    // LookaheadScope around the entire chat LazyColumn, including ordinary scrolls.
    CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalSettings provides settings,
            LocalToaster provides notices,
        ) {
            Toaster(state = toaster, darkTheme = LocalDarkMode.current, richColors = true, alignment = Alignment.TopCenter, showCloseButton = true)
            Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(MaterialTheme.colorScheme.background)) {
                if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } else if (state.error?.startsWith("资源库无法打开") == true) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error) }
                } else RikkaNavDisplay(
                        backStack = stack,
                        onBack = { navigator.popBackStack() },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(metadata = rikkaChatTransitions()) { key ->
                                CompanionChatPage(controller, key.id, { scope.launch { drawer.open() } }, ::newChat,
                                    { open(Screen.SettingProvider) }, drawerState = drawer, drawerContent = { CurrentDrawer() },
                                    targetMessageId = key.nodeId?.takeUnless { consumedSearchTarget == "${key.id}:$it" },
                                    onTargetConsumed = {
                                        consumedSearchTarget = "${key.id}:${key.nodeId}"
                                    }, onRecoveryEditPrompt = { failure ->
                                        val pending = library.sessions.firstOrNull { it.id == key.id }?.pendingRun
                                        if (pending != null) open(Screen.CompanionRecoveryPrompt(key.id, pending.id, failure.nodeKey))
                                    }, optionsState = automaticOptions,
                                    onGenerateOptions = controller::generateAutomaticOptions,
                                    onOpenOptionsSettings = { open(Screen.CompanionAutomaticOptions) },
                                    onOpenSavedChats = { open(Screen.History) })
                            }
                            entry<Screen.MessageSearch> {
                                CompanionSearchPage(controller) { sessionId, messageId -> scope.launch {
                                    if (report(controller.selectSession(sessionId))) {
                                        consumedSearchTarget = null
                                        navigator.clearAndNavigate(Screen.Chat(sessionId, nodeId = messageId))
                                    }
                                } }
                            }
                            entry<Screen.Setting> { CompanionSettingsPage(onExport, onImport, library.configurationEdits.size,
                                onOpenMainCall = {
                                    val activeId = library.sessions.firstOrNull { it.id == library.selectedSessionId }?.mainCallId
                                    val main = library.mainCalls.firstOrNull { it.id == activeId } ?: library.mainCalls.firstOrNull()
                                    edit(Editing(EditorKind.MAIN, main?.id ?: newId()))
                                }) }
                            entry<Screen.CompanionImportHistory> {
                                ConfigurationUndoPage(library.configurationEdits, importBusy,
                                    { receiptId ->
                                        if (!importBusy) { importBusy = true; scope.launch {
                                            try {
                                                val issues = controller.undoConfigurationImport(receiptId)
                                                if (issues.any { it.error }) AppEventLog.record("配置", issues.first { it.error }.message, AppLogLevel.ERROR)
                                                else AppEventLog.record("配置", "配置已撤回")
                                            } finally { importBusy = false }
                                        } }
                                    }, { navigator.popBackStack() })
                            }
                            entry<Screen.CompanionConfigurationWorkspace> {
                                ConfigurationWorkspacePage(controller) { navigator.popBackStack() }
                            }
                            entry<Screen.CompanionSession> { SessionConfigScreen(library, { navigator.popBackStack() }, ::save, saving,
                                onManagePersonas = { navigator.navigate(Screen.CompanionPersonas) }) }
                            entry<Screen.CompanionBodyColors> { BodyColorsSettingsPage() }
                            entry<Screen.CompanionChatDisplay> { CompanionChatDisplaySettingsPage { cardLayoutOpen = true } }
                            entry<Screen.CompanionAutomaticOptions> {
                                AutomaticOptionsSettingsPage(library, ::saveAutomaticOptions, { navigator.popBackStack() }, saving)
                            }
                            entry<Screen.CompanionAgent> {
                                AgentPage(controller.agent, onBack = { navigator.popBackStack() }, onOpenWorkbench = { open(Screen.CompanionConfigurationWorkspace) })
                            }
                            entry<Screen.CompanionIllustration> {
                                IllustrationSettingsPage(library, illustrationActivity, controller.bizyAirConfigured(),
                                    onSave = ::saveIllustration, onSetBizyAirCredential = controller::setBizyAirCredential,
                                    onAcknowledgeRemoteStatus = controller::acknowledgeIllustrationRemoteStatus,
                                    onBack = { navigator.popBackStack() }, saving = saving)
                            }
                            entry<Screen.CompanionMemory> { memoryKey ->
                                LaunchedEffect(library.memoryConfig) {
                                    if (library.memoryConfig?.extensionId == null)
                                        report(controller.updateMemoryConfiguration(GlobalMemory::ensureGlobal))
                                }
                                SourceNavigationProvider(library, onOpenLocation = { location ->
                                    val destination = sourceEditorScreen(location, library)
                                    if (destination is Screen.CompanionMemory) navigator.navigate(destination) { popUpTo(memoryKey) { inclusive = true } }
                                    else {
                                        navigator.navigate(Screen.CompanionMemory(visitId = newId(), focus = memoryKey.focus?.let { ResourceLocation(it.resource) })) { popUpTo(memoryKey) { inclusive = true } }
                                        navigator.navigate(destination)
                                    }
                                }, onApplyExternal = ::applySourceEdit, focus = memoryKey.focus, issues = state.issues) {
                                MemorySettingsPage(library, ::applyMemoryCommand, { navigator.popBackStack() }, saving)
                                }
                            }
                            entry<Screen.CompanionReasoning> {
                                ReasoningParsingSettingsPage(library.reasoningParsing,
                                    { parsing -> save { it.copy(reasoningParsing = parsing) } },
                                    { navigator.popBackStack() }, saving)
                            }
                            entry<Screen.CompanionPersonas> {
                                Column(Modifier.fillMaxSize()) {
                                    SettingsHeader("用户身份", { navigator.popBackStack() })
                                    PersonasScreen(library, ::edit, ::update, Modifier.weight(1f))
                                }
                            }
                            entry<Screen.CompanionTags> {
                                TagSettingsPage(library, { tags -> save { it.copy(promptTags = tags) } }, { navigator.popBackStack() }, saving)
                            }
                            entry<Screen.CompanionPromptPreview> { PromptPreviewPage(controller) }
                            entry<Screen.History> { ChatArchiveScreen(library, { navigator.popBackStack() }, ::update, { id -> scope.launch {
                                if (report(controller.restoreSavedChat(id))) navigator.clearAndNavigate(Screen.Chat(controller.library.value.selectedSessionId ?: "active"))
                            } }) }
                            entry<Screen.CompanionUsage> { UsageDetailScreen(usage, usageAsOf, usageDays, { usageDays = it }, usageError) }
                            entry<Screen.CompanionResources> { key ->
                                Column(Modifier.fillMaxSize()) {
                                    val kind = ResourceKind.fromRouteIndex(key.kind)
                                    SettingsHeader(kind?.title ?: "资源", { navigator.popBackStack() })
                                    if (kind != null) ResourceScreen(library, kind, ::edit, ::update, Modifier.weight(1f),
                                        onManageTags = if (kind == ResourceKind.PACKAGE) ({ navigator.navigate(Screen.CompanionTags) }) else null,
                                        onSelectMainCall = if (kind == ResourceKind.MAIN) ({ id ->
                                            val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
                                            if (session == null) scope.launch { AppEventLog.record("配置", "请先创建对话。", AppLogLevel.WARNING) }
                                            else if (session.mainCallId == id) navigator.popBackStack()
                                            else save { current -> current.copy(sessions = current.sessions.map { latest ->
                                                if (latest.id == session.id) latest.copy(mainCallId = id, updatedAt = System.currentTimeMillis()) else latest
                                            }) }
                                        }) else null,
                                        selectionBusy = saving, onApplyCommand = ::applyCommand, onOpenLocation = ::openLocation)
                                }
                            }
                            entry<Screen.SettingProvider> {
                                CompanionProvidersPage(library, saving, ::commitProviders,
                                    onOpenProvider = { navigator.navigate(Screen.SettingProviderDetail(it.toString())) },
                                    onBack = { navigator.popBackStack() })
                            }
                            entry<Screen.SettingProviderDetail> { key ->
                                CompanionProviderDetailPage(key.providerId, library, saving, controller::getCredential, ::commitProviders,
                                    onSelectDefault = { id -> update { it.copy(selectedProfileId = id) } },
                                    onBack = { navigator.popBackStack() })
                            }
                            entry<Screen.SettingTheme> { SettingThemePage() }
                            entry<Screen.SettingPreferencesTheme> { SettingPreferencesThemePage() }
                            entry<Screen.SettingPreferencesUI> { SettingPreferencesUIPage() }
                            entry<Screen.SettingPreferencesGeneral> { SettingPreferencesGeneralPage(textOnly = true) }
                            entry<Screen.SettingPreferencesNetwork> { SettingPreferencesNetworkPage() }
                            entry<Screen.QuickMessages> { QuickMessagesPage() }
                            entry<Screen.WebView> { WebViewPage(it.url, it.contentId) }
                            entry<Screen.CompanionExtensions> {
                                ConfigurationExtensionsPage(library, saving,
                                    onApply = ::applyCommand,
                                    onOpenResource = { resource ->
                                        val memory = GlobalMemory.extension(library)?.takeIf { it.memory?.groupId == resource.id && resource.kind == ExtensionResourceKind.GROUP }
                                        val kind = when (resource.kind) {
                                            ExtensionResourceKind.CHARACTER -> EditorKind.CHARACTER
                                            ExtensionResourceKind.MAIN -> EditorKind.MAIN
                                            ExtensionResourceKind.PACKAGE -> EditorKind.PACKAGE
                                            ExtensionResourceKind.GROUP -> EditorKind.GROUP
                                        }
                                        if (memory != null) navigator.navigate(Screen.CompanionMemory(focus = ResourceLocation(resource), visitId = newId()))
                                        else edit(Editing(kind, resource.id))
                                    },
                                    onBack = { navigator.popBackStack() },
                                    onOpenMemory = { navigator.navigate(Screen.CompanionMemory()) }, onOpenLocation = ::openLocation,
                                    onSetMemoryEnabled = ::setMemoryEnabled, onOpenAutomaticOptions = { open(Screen.CompanionAutomaticOptions) },
                                    onSetAutomaticOptionsEnabled = ::setAutomaticOptionsEnabled,
                                    onOpenIllustration = { open(Screen.CompanionIllustration) },
                                    onSetIllustrationEnabled = ::setIllustrationEnabled,
                                    onSetExtensionEnabled = ::setExtensionEnabled,
                                    pluginAccessStatus = controller::pluginAccessStatus,
                                    onGrantPluginAccess = controller::grantPluginAccess,
                                    onRevokePluginAccess = controller::revokePluginAccess,
                                    onSetPluginCredential = controller::setPluginCredential)
                            }
                            entry<Screen.CompanionRecoveryPrompt> { key ->
                                val node = controller.recoveryNode(key.sessionId, key.runId, key.nodeKey)
                                if (node == null) {
                                    Column(Modifier.fillMaxSize()) {
                                        SettingsHeader("断点提示词", { navigator.popBackStack() })
                                        Text("断点或对应调用已改变，请返回聊天查看当前状态。", Modifier.padding(16.dp))
                                    }
                                } else androidx.compose.runtime.key(key.runId, key.nodeKey) {
                                    val capturedBlocks = remember(key.runId, key.nodeKey) { node.blocks }
                                    val referenceLibrary = controller.recoveryPromptLibrary(key.sessionId, key.runId, key.nodeKey)
                                    val session = referenceLibrary.sessions.firstOrNull { it.id == referenceLibrary.selectedSessionId }
                                    val main = referenceLibrary.mainCalls.firstOrNull { it.id == session?.mainCallId }
                                    val workflows = main?.configuredWorkflows().orEmpty() +
                                        session?.let { referenceLibrary.loadedCallGroups(it).flatMap { group -> group.workflows } }.orEmpty()
                                    val references = referencesForWritingNode(promptReferenceChoices(workflows, referenceLibrary, main), node.id)
                                    PromptBlocksEditor(node.blocks, references, { blocks ->
                                        if (!saving) {
                                            saving = true
                                            scope.launch {
                                                try {
                                                    if (report(controller.saveRecoveryPrompt(key.sessionId, key.runId, key.nodeKey, blocks, capturedBlocks))) navigator.popBackStack()
                                                } finally { saving = false }
                                            }
                                        }
                                    }, { navigator.popBackStack() }, title = "${node.title} · 断点提示词", saving = saving, tags = referenceLibrary.promptTags)
                                }
                            }
                            entry<Screen.CompanionEditor> { key ->
                                val kind = EditorKind.fromRoute(key.kind)
                                val back: () -> Unit = { navigator.popBackStack() }
                                SourceNavigationProvider(library, onOpenLocation = { location ->
                                    val destination = sourceEditorScreen(location, library)
                                    val sameResource = destination is Screen.CompanionEditor && destination.kind == key.kind && destination.id == key.id
                                    if (sameResource) navigator.navigate(destination) { popUpTo(key) { inclusive = true } }
                                    else {
                                        navigator.navigate(Screen.CompanionEditor(key.kind, key.id, visitId = newId())) {
                                            popUpTo(key) { inclusive = true }
                                        }
                                        navigator.navigate(destination)
                                    }
                                }, onApplyExternal = ::applySourceEdit, focus = key.focus, issues = state.issues) {
                                when (kind) {
                                    EditorKind.CHARACTER -> {
                                        val initial = remember(key) { library.characters.firstOrNull { it.id == key.id } ?: CharacterCard(id = key.id, name = "新角色", namespace = "character_${key.id.take(8)}") }
                                        CharacterEditor(initial, library.packages, { value -> save { it.copy(characters = it.characters.replaceOrAdd(value) { it.id }) } }, back, saving, library = library)
                                    }
                                    EditorKind.USER -> {
                                        val initial = remember(key) { library.user }
                                        UserIdentityEditor(initial, { value -> save { it.copy(user = value) } }, back, saving)
                                    }
                                    EditorKind.PERSONA -> {
                                        val initial = remember(key) { library.personas.firstOrNull { it.id == key.id } ?: UserPersona(id = key.id, name = "新用户身份") }
                                        PersonaEditor(initial, library, { value -> save { it.copy(personas = it.personas.replaceOrAdd(value) { it.id }) } }, back, saving)
                                    }
                                    EditorKind.MAIN -> {
                                        val initial = remember(key) { library.mainCalls.firstOrNull { it.id == key.id } ?: newEditorMainCall(key.id) }
                                        MainCallEditor(initial, { value -> save { it.copy(mainCalls = it.mainCalls.replaceOrAdd(value) { it.id }) } }, back, saving, controller.actionDescriptors, library.profiles, library,
                                         onSwitchResource = { draft, targetId ->
                                            if (!saving) {
                                                saving = true
                                                scope.launch {
                                                    try {
                                                        val accepted = draft == null || report(controller.updateLibrary { current -> current.copy(mainCalls = current.mainCalls.replaceOrAdd(draft) { it.id }) })
                                                        if (accepted) navigator.navigate(Screen.CompanionEditor(EditorKind.MAIN.route, targetId ?: newId())) { popUpTo(key) { inclusive = true } }
                                                    } finally { saving = false }
                                                }
                                            }
                                         })
                                    }
                                    EditorKind.PACKAGE -> {
                                        val initial = remember(key) { library.packages.firstOrNull { it.id == key.id } ?: PromptPackage(id = key.id) }
                                        PackageEditor(initial, library, { value, cards, mains -> save { lib -> lib.copy(
                                        packages = lib.packages.replaceOrAdd(value) { it.id },
                                        characters = lib.characters.map { it.copy(boundPackageIds = it.boundPackageIds.withPackageBinding(value.id, it.id in cards)) },
                                        mainCalls = lib.mainCalls.map { it.copy(boundPackageIds = it.boundPackageIds.withPackageBinding(value.id, it.id in mains)) },
                                    ) } }, back, saving)
                                    }
                                    EditorKind.GROUP -> {
                                        val initial = remember(key) { library.groups.firstOrNull { it.id == key.id } ?: CallGroup(id = key.id) }
                                        GroupEditor(initial, library.packages, { value -> save { it.copy(groups = it.groups.replaceOrAdd(value) { it.id }) } }, back, saving, controller.actionDescriptors, library.profiles, library)
                                    }
                                    EditorKind.PROFILE -> {
                                        val connectionId = library.profiles.firstOrNull { it.id == key.id }?.apiConnectionId
                                        if (connectionId != null) CompanionProviderDetailPage(connectionId, library, saving,
                                            controller::getCredential, ::commitProviders,
                                            onSelectDefault = { id -> update { it.copy(selectedProfileId = id) } }, onBack = back)
                                        else Column { SettingsHeader("模型", back); Text("请在模型与 API 中选择连接和模型。", Modifier.padding(16.dp)) }
                                    }
                                    EditorKind.CONNECTION -> {
                                        if (library.connections.any { it.id == key.id })
                                            CompanionProviderDetailPage(key.id, library, saving, controller::getCredential, ::commitProviders,
                                                onSelectDefault = { id -> update { it.copy(selectedProfileId = id) } }, onBack = back)
                                        else CompanionProvidersPage(library, saving, ::commitProviders,
                                            onOpenProvider = { navigator.navigate(Screen.SettingProviderDetail(it.toString())) }, onBack = back)
                                    }
                                    null -> Text("此编辑页不可用")
                                }
                                }
                            }
                        }
                )

            }
            if (configurationIssues.isNotEmpty()) SourceIssuesSheet(library, configurationIssues, ::openLocation) { configurationIssues = emptyList() }
            importPreview?.let { preview ->
                ConfigurationImportSheet(preview, importBusy,
                    onApply = {
                        if (!importBusy) { importBusy = true; scope.launch {
                            try {
                                val issues = controller.applyConfigurationImport(preview.id)
                                if (issues.any { it.error }) AppEventLog.record("配置", issues.first { it.error }.message, AppLogLevel.ERROR)
                                else AppEventLog.record("资源文件", "导入完成")
                            } finally { importBusy = false }
                        } }
                    },
                    onCancel = { if (!importBusy) controller.dismissConfigurationImport() })
            }
        }
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value

private fun sourceEditorScreen(location: ResourceLocation, library: Library): Screen {
    if (location.resource.kind == ExtensionResourceKind.GROUP && GlobalMemory.extension(library)?.memory?.groupId == location.resource.id)
        return Screen.CompanionMemory(visitId = newId(), focus = location)
    val kind = when (location.resource.kind) {
        ExtensionResourceKind.CHARACTER -> EditorKind.CHARACTER
        ExtensionResourceKind.MAIN -> EditorKind.MAIN
        ExtensionResourceKind.PACKAGE -> EditorKind.PACKAGE
        ExtensionResourceKind.GROUP -> EditorKind.GROUP
    }
    return Screen.CompanionEditor(kind.route, location.resource.id, focus = location, visitId = newId())
}

@Composable
private fun CompanionSettingsPage(onExport: () -> Unit, onImport: () -> Unit, undoCount: Int, onOpenMainCall: () -> Unit) {
    val navigator = LocalNavController.current
    var showCrashDiagnostics by remember { mutableStateOf(false) }
    if (showCrashDiagnostics) CrashDiagnosticsDialog { showCrashDiagnostics = false }
    SettingPageLayout {
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("运行方案") }) {
                item(onClick = { navigator.navigate(Screen.CompanionSession) }, headlineContent = { Text("当前对话配置") }, leadingContent = { Icon(CompanionIcons.Session, null) })
                item(onClick = onOpenMainCall, headlineContent = { Text("主调用") }, leadingContent = { Icon(CompanionIcons.MainCall, null) })
                item(onClick = { navigator.navigate(Screen.CompanionResources(ResourceKind.PACKAGE.routeIndex)) }, headlineContent = { Text("提示词包") }, leadingContent = { Icon(CompanionIcons.PromptPackage, null) })
                item(onClick = { navigator.navigate(Screen.CompanionResources(ResourceKind.GROUP.routeIndex)) }, headlineContent = { Text("调用组") }, leadingContent = { Icon(CompanionIcons.CallGroup, null) })
                item(onClick = { navigator.navigate(Screen.CompanionExtensions()) }, headlineContent = { Text("扩展") }, leadingContent = { Icon(CompanionIcons.Extensions, null) })
            } }
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("配置工具") }) {
                item(onClick = { navigator.navigate(Screen.CompanionAgent) }, headlineContent = { Text("Agent") }, leadingContent = { Icon(CompanionIcons.Agent, null) })
                item(onClick = { navigator.navigate(Screen.CompanionConfigurationWorkspace) },
                    headlineContent = { Text("配置工作台") },
                    leadingContent = { Icon(CompanionIcons.Workspace, null) })
            } }
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("连接与界面") }) {
                item(onClick = { navigator.navigate(Screen.SettingProvider) }, headlineContent = { Text("模型与 API") }, leadingContent = { Icon(CompanionIcons.Models, null) })
                item(onClick = { navigator.navigate(Screen.CompanionChatDisplay) }, headlineContent = { Text("聊天与显示") }, leadingContent = { Icon(CompanionIcons.ChatDisplay, null) })
                item(onClick = { showCrashDiagnostics = true }, headlineContent = { Text("日志") }, leadingContent = { Icon(CompanionIcons.Logs, null) })
            } }
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("资源备份") }) {
                item(onClick = onImport, headlineContent = { Text("导入资源") }, leadingContent = { Icon(CompanionIcons.Import, null) })
                item(onClick = { navigator.navigate(Screen.CompanionImportHistory) }, headlineContent = { Text("配置撤回") },
                    supportingContent = { Text("$undoCount 次可检查的配置导入") }, leadingContent = { Icon(CompanionIcons.Undo, null) })
                item(onClick = onExport, headlineContent = { Text("导出 JSON") }, leadingContent = { Icon(CompanionIcons.Export, null) })
            } }
    }
}

@Composable
private fun CompanionChatDisplaySettingsPage(onOpenCardLayout: () -> Unit) {
    val navigator = LocalNavController.current
    SettingPageLayout(title = "聊天与显示") {
        item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("聊天") }) {
            item(onClick = { navigator.navigate(Screen.CompanionReasoning) }, headlineContent = { Text("思考解析") }, leadingContent = { Icon(CompanionIcons.Reasoning, null) })
            item(onClick = { navigator.navigate(Screen.QuickMessages) }, headlineContent = { Text("快捷输入") }, leadingContent = { Icon(CompanionIcons.QuickInput, null) })
            item(onClick = { navigator.navigate(Screen.CompanionAutomaticOptions) }, headlineContent = { Text("自动选项") }, leadingContent = { Icon(CompanionIcons.Options, null) })
        } }
        item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("显示与偏好") }) {
            item(onClick = { navigator.navigate(Screen.SettingPreferencesGeneral) }, headlineContent = { Text("通用偏好") }, leadingContent = { Icon(CompanionIcons.Preferences, null) })
            item(onClick = onOpenCardLayout, headlineContent = { Text("卡片布局") }, leadingContent = { Icon(CompanionIcons.CardLayout, null) })
            item(onClick = { navigator.navigate(Screen.SettingPreferencesUI) }, headlineContent = { Text("界面显示") }, leadingContent = { Icon(CompanionIcons.Display, null) })
            item(onClick = { navigator.navigate(Screen.SettingPreferencesTheme) }, headlineContent = { Text("主题") }, leadingContent = { Icon(CompanionIcons.Theme, null) })
            item(onClick = { navigator.navigate(Screen.SettingPreferencesNetwork) }, headlineContent = { Text("网络") }, leadingContent = { Icon(CompanionIcons.Network, null) })
        } }
    }
}
