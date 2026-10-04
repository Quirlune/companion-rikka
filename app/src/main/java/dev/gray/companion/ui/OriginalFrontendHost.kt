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
    val importPreview by controller.configurationImport.collectAsStateWithLifecycle()
    val usage by controller.usage.collectAsStateWithLifecycle()
    val usageError by controller.usageError.collectAsStateWithLifecycle()
    val settings by rememberUserSettingsState()
    val stack = rememberNavBackStack(Screen.Chat(library.selectedSessionId ?: "active"))
    val navigator = remember(stack) { Navigator(stack) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val toaster = rememberToasterState()
    var saveSessionId by remember { mutableStateOf<String?>(null) }
    var usageDays by remember { mutableIntStateOf(7) }
    var usageRefreshedAt by remember { mutableLongStateOf(0L) }
    val usageAsOf = remember(usage, usageRefreshedAt) { System.currentTimeMillis() }
    var saving by remember { mutableStateOf(false) }
    var importBusy by remember { mutableStateOf(false) }
    var consumedSearchTarget by remember { mutableStateOf<String?>(null) }

    suspend fun report(issues: List<Issue>): Boolean {
        issues.firstOrNull()?.let { snackbar.showSnackbar(it.message) }
        return issues.none { it.error }
    }
    fun update(transform: (Library) -> Library) { scope.launch { report(controller.updateLibrary(transform)) } }
    fun open(screen: Screen) { scope.launch { drawer.close(); navigator.navigate(screen) } }
    fun edit(target: Editing) { open(Screen.CompanionEditor(target.kind.route, target.id)) }
    fun save(transform: (Library) -> Library) {
        if (saving) return
        saving = true
        scope.launch {
            try { if (report(controller.updateLibrary(transform))) navigator.popBackStack() }
            finally { saving = false }
        }
    }
    fun saveMemory(ownerId: String, processor: HistoryProcessor, onSaved: () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (report(controller.updateLibrary { current -> current.copy(mainCalls = current.mainCalls.map { main ->
                    if (main.id == ownerId) main.copy(history = main.history.copy(processor = processor)) else main
                }) })) onSaved()
            } finally { saving = false }
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
    LaunchedEffect(externalError) { externalError?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.titleError) { state.titleError?.let { snackbar.showSnackbar(it) } }

    @Composable
    fun CurrentDrawer() {
                        OriginalDrawer(
                            library = library, usage = usage, usageError = usageError, usageAsOf = usageAsOf,
                            settings = settings,
                            onPinSession = { id -> scope.launch { report(controller.toggleSessionPin(id)) } },
                            onSelectSession = { id -> scope.launch { if (report(controller.selectSession(id))) { drawer.close(); navigator.clearAndNavigate(Screen.Chat(controller.library.value.selectedSessionId ?: "active")) } } },
                            onNewChat = ::newChat,
                            onOpenFavorites = { open(Screen.History) },
                            onOpenSearch = { open(Screen.MessageSearch) },
                            onSelectCharacter = ::selectCharacter,
                            onCreateCharacter = { edit(Editing(EditorKind.CHARACTER, newId())) },
                            onEditCharacter = { id -> library.characters.firstOrNull { it.id == id }?.let { edit(Editing(EditorKind.CHARACTER, it.id)) } },
                            onSettings = { open(Screen.Setting) },
                            onMainCalls = { open(Screen.CompanionResources(ResourceKind.MAIN.routeIndex)) },
                            onUsage = { usageDays = 7; open(Screen.CompanionUsage) },
                            onEditUser = { open(Screen.CompanionPersonas) },
                            onSaveChat = { saveSessionId = it },
                            onRenameSession = { id, title -> scope.launch { report(controller.renameSession(id, title)) } },
                            onRegenerateTitle = { id -> scope.launch { report(controller.regenerateTitle(id)) } },
                            onDeleteSession = { id -> update { lib ->
                                val remaining = lib.sessions.filterNot { it.id == id }
                                val next = lib.copy(sessions = remaining, selectedSessionId = lib.selectedSessionId.takeUnless { it == id } ?: remaining.firstOrNull()?.id)
                                if (remaining.isEmpty()) next.startNewConversation() else next
                            } },
                        )
    }

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalSharedTransitionScope provides this,
            LocalSettings provides settings,
            LocalToaster provides toaster,
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
                                    })
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
                                                if (issues.any { it.error }) snackbar.showSnackbar(issues.first { it.error }.message)
                                                else snackbar.showSnackbar("配置已撤回")
                                            } finally { importBusy = false }
                                        } }
                                    }, { navigator.popBackStack() })
                            }
                            entry<Screen.CompanionSession> { SessionConfigScreen(library, { navigator.popBackStack() }, ::save, saving,
                                onManagePersonas = { navigator.navigate(Screen.CompanionPersonas) }) }
                            entry<Screen.CompanionMemory> {
                                MemorySettingsPage(library, controller.actionDescriptors, ::saveMemory, { navigator.popBackStack() }, saving)
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
                            entry<Screen.CompanionTiles> { TileSettingsPage(library) { kind, id -> navigator.navigate(Screen.CompanionTileEditor(kind, id)) } }
                            entry<Screen.CompanionTileEditor> { key -> TileOwnerEditor(key.kind, key.id, library, { tiles -> save { current ->
                                if (key.kind == "main") current.copy(mainCalls = current.mainCalls.map { if (it.id == key.id) it.copy(tiles = tiles) else it })
                                else current.copy(groups = current.groups.map { if (it.id == key.id) it.copy(tiles = tiles) else it })
                            } }, { navigator.popBackStack() }, saving) }
                            entry<Screen.CompanionResources> { key ->
                                Column(Modifier.fillMaxSize()) {
                                    val kind = ResourceKind.fromRouteIndex(key.kind)
                                    SettingsHeader(kind?.title ?: "资源", { navigator.popBackStack() })
                                    if (kind != null) ResourceScreen(library, kind, ::edit, ::update, Modifier.weight(1f),
                                        onManageTags = if (kind == ResourceKind.PACKAGE) ({ navigator.navigate(Screen.CompanionTags) }) else null,
                                        onSelectMainCall = if (kind == ResourceKind.MAIN) ({ id ->
                                            val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
                                            if (session == null) scope.launch { snackbar.showSnackbar("请先创建对话。") }
                                            else if (session.mainCallId == id) navigator.popBackStack()
                                            else save { current -> current.copy(sessions = current.sessions.map { latest ->
                                                if (latest.id == session.id) latest.copy(mainCallId = id, updatedAt = System.currentTimeMillis()) else latest
                                            }) }
                                        }) else null,
                                        selectionBusy = saving)
                                }
                            }
                            entry<Screen.SettingProvider> {
                                Column(Modifier.fillMaxSize()) {
                                    SettingsHeader("模型与 API", { navigator.popBackStack() })
                                    ModelsScreen(library, ::edit, ::update, Modifier.weight(1f))
                                }
                            }
                            entry<Screen.SettingModels> {
                                ChatTitleSettingsPage(library, { config -> save { it.copy(titleConfig = config) } }, { navigator.popBackStack() }, saving)
                            }
                            entry<Screen.SettingTheme> { SettingThemePage() }
                            entry<Screen.SettingPreferencesTheme> { SettingPreferencesThemePage() }
                            entry<Screen.SettingPreferencesUI> { SettingPreferencesUIPage() }
                            entry<Screen.SettingPreferencesGeneral> { SettingPreferencesGeneralPage(textOnly = true) }
                            entry<Screen.SettingPreferencesNetwork> { SettingPreferencesNetworkPage() }
                            entry<Screen.QuickMessages> { QuickMessagesPage() }
                            entry<Screen.WebView> { WebViewPage(it.url, it.contentId) }
                            entry<Screen.CompanionRecoveryPrompt> { key ->
                                val node = controller.recoveryNode(key.sessionId, key.runId, key.nodeKey)
                                if (node == null) {
                                    Column(Modifier.fillMaxSize()) {
                                        SettingsHeader("断点提示词", { navigator.popBackStack() })
                                        Text("断点或对应调用已改变，请返回聊天查看当前状态。", Modifier.padding(16.dp))
                                    }
                                } else androidx.compose.runtime.key(key.runId, key.nodeKey) {
                                    val referenceLibrary = library.copy(selectedSessionId = key.sessionId)
                                    val session = referenceLibrary.sessions.firstOrNull { it.id == key.sessionId }
                                    val main = referenceLibrary.mainCalls.firstOrNull { it.id == session?.mainCallId }
                                    val workflows = main?.configuredWorkflows().orEmpty() +
                                        session?.let { referenceLibrary.loadedCallGroups(it).flatMap { group -> group.workflows } }.orEmpty()
                                    val references = referencesForWritingNode(promptReferenceChoices(workflows, referenceLibrary, main), node.id)
                                    PromptBlocksEditor(node.blocks, references, { blocks ->
                                        if (!saving) {
                                            saving = true
                                            scope.launch {
                                                try {
                                                    if (report(controller.saveRecoveryPrompt(key.sessionId, key.runId, key.nodeKey, blocks))) navigator.popBackStack()
                                                } finally { saving = false }
                                            }
                                        }
                                    }, { navigator.popBackStack() }, title = "${node.title} · 提示词", saving = saving, tags = library.promptTags)
                                }
                            }
                            entry<Screen.CompanionEditor> { key ->
                                val kind = EditorKind.fromRoute(key.kind)
                                val back: () -> Unit = { navigator.popBackStack() }
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
                                        val initial = remember(key) { library.profiles.firstOrNull { it.id == key.id } ?: ModelProfile(id = key.id, apiConnectionId = library.connections.firstOrNull()?.id) }
                                        ProfileEditor(initial, library.connections, { value -> save { it.copy(profiles = it.profiles.replaceOrAdd(value) { it.id }, selectedProfileId = it.selectedProfileId ?: value.id) } }, back, saving)
                                    }
                                    EditorKind.CONNECTION -> {
                                        val initial = remember(key) { library.connections.firstOrNull { it.id == key.id } ?: ApiConnection(id = key.id) }
                                        ConnectionEditor(initial, controller.getApiKey(key.id), { value, secret ->
                                        if (!saving) { saving = true; scope.launch {
                                            try {
                                                if (report(controller.updateLibrary { it.copy(connections = it.connections.replaceOrAdd(value) { it.id }) }) && report(controller.setApiKey(value.id, secret))) navigator.popBackStack()
                                            } finally { saving = false }
                                        } }
                                        }, back, saving)
                                    }
                                    null -> Text("此编辑页不可用")
                                }
                            }
                        }
                )
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            saveSessionId?.let { id -> SaveChatDialog(library, id, { session, folder -> scope.launch { report(controller.saveChat(session, folder)) } }, { open(Screen.History) }, { saveSessionId = null }) }
            importPreview?.let { preview ->
                ConfigurationImportSheet(preview, importBusy,
                    onApply = {
                        if (!importBusy) { importBusy = true; scope.launch {
                            try {
                                val issues = controller.applyConfigurationImport(preview.id)
                                if (issues.any { it.error }) snackbar.showSnackbar(issues.first { it.error }.message)
                                else snackbar.showSnackbar("导入完成")
                            } finally { importBusy = false }
                        } }
                    },
                    onCancel = { if (!importBusy) controller.dismissConfigurationImport() })
            }
        }
    }
}

private fun <T> List<T>.replaceOrAdd(value: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(value) }) map { if (id(it) == id(value)) value else it } else this + value

@Composable
private fun CompanionSettingsPage(onExport: () -> Unit, onImport: () -> Unit, undoCount: Int, onOpenMainCall: () -> Unit) {
    val navigator = LocalNavController.current
    SettingPageLayout {
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("运行方案") }) {
                item(onClick = { navigator.navigate(Screen.CompanionSession) }, headlineContent = { Text("当前对话配置") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = { navigator.navigate(Screen.CompanionPromptPreview) }, headlineContent = { Text("提示词预览") }, leadingContent = { Icon(HugeIcons.Search01, null) })
                item(onClick = onOpenMainCall, headlineContent = { Text("主调用") }, leadingContent = { Icon(HugeIcons.Brain02, null) })
                item(onClick = { navigator.navigate(Screen.CompanionResources(ResourceKind.PACKAGE.routeIndex)) }, headlineContent = { Text("提示词包") }, leadingContent = { Icon(HugeIcons.Book03, null) })
                item(onClick = { navigator.navigate(Screen.CompanionResources(ResourceKind.GROUP.routeIndex)) }, headlineContent = { Text("调用组") }, leadingContent = { Icon(HugeIcons.Package, null) })
                item(onClick = { navigator.navigate(Screen.CompanionMemory) }, headlineContent = { Text("记忆") }, leadingContent = { Icon(HugeIcons.Brain02, null) })
            } }
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("连接与界面") }) {
                item(onClick = { navigator.navigate(Screen.SettingProvider) }, headlineContent = { Text("模型与 API") }, leadingContent = { Icon(HugeIcons.ServerStack01, null) })
                item(onClick = { navigator.navigate(Screen.SettingModels) }, headlineContent = { Text("自动标题") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = { navigator.navigate(Screen.CompanionReasoning) }, headlineContent = { Text("思考解析") }, leadingContent = { Icon(HugeIcons.Brain02, null) })
                item(onClick = { navigator.navigate(Screen.QuickMessages) }, headlineContent = { Text("快捷输入") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = { navigator.navigate(Screen.CompanionTiles) }, headlineContent = { Text("显示磁贴") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = { navigator.navigate(Screen.SettingPreferencesGeneral) }, headlineContent = { Text("通用偏好") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = { navigator.navigate(Screen.SettingPreferencesNetwork) }, headlineContent = { Text("网络") }, leadingContent = { Icon(HugeIcons.ServerStack01, null) })
                item(onClick = { navigator.navigate(Screen.SettingPreferencesTheme) }, headlineContent = { Text("主题") }, leadingContent = { Icon(HugeIcons.Sun01, null) })
                item(onClick = { navigator.navigate(Screen.SettingPreferencesUI) }, headlineContent = { Text("界面显示") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
            } }
            item { CardGroup(modifier = Modifier.padding(horizontal = 8.dp), title = { Text("资源备份") }) {
                item(onClick = onImport, headlineContent = { Text("导入 JSON") }, leadingContent = { Icon(HugeIcons.ImageUpload, null) })
                item(onClick = { navigator.navigate(Screen.CompanionImportHistory) }, headlineContent = { Text("配置撤回") },
                    supportingContent = { Text("$undoCount 次可检查的配置导入") }, leadingContent = { Icon(HugeIcons.Settings03, null) })
                item(onClick = onExport, headlineContent = { Text("导出 JSON") }, leadingContent = { Icon(HugeIcons.Share04, null) })
            } }
    }
}

