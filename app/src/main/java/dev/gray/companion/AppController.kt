package dev.gray.companion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.gray.companion.core.*
import dev.gray.companion.agent.AgentPromptRecorder
import dev.gray.companion.data.GatewayFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.ui.hooks.readBooleanPreference

data class PendingOutput(val workflowId: String, val nodeId: String, val text: String, val outputSlot: OutputSlot = OutputSlot.BODY, val turnId: String? = null, val reasoningSnapshot: ReasoningSnapshot? = null)

data class ConfigurationImportPreview(
    val id: String,
    val label: String,
    val changes: List<ConfigurationChange>,
    val settings: List<ConfigurationSettingChange>,
    val issues: List<Issue>,
    val undoable: Boolean,
)

data class ControllerState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val streaming: String = "",
    val traces: List<RunTrace> = emptyList(),
    val issues: List<Issue> = emptyList(),
    val error: String? = null,
    val pendingOutputs: List<PendingOutput> = emptyList(),
    val generationPreview: Session? = null,
)

class AppController internal constructor(
    context: Context,
    private val actions: ActionRegistry,
    dependencies: ControllerDependencies,
) : ViewModel() {
    constructor(context: Context, actions: ActionRegistry = defaultPluginActions(context)) :
        this(context, actions, ControllerDependencies.android(context))

    val actionDescriptors: List<ActionDescriptor> get() = actions.descriptors()
    private val repository = dependencies.library
    private val usageRepository = dependencies.usage
    private val gatewayFactory = dependencies.gateway
    private val agentPromptRecorder = AgentPromptRecorder()
    private val usageMutex = Mutex()
    private var usageFrom = Long.MAX_VALUE
    private val _usage = MutableStateFlow<List<ModelUsageRecord>>(emptyList())
    val usage: StateFlow<List<ModelUsageRecord>> = _usage.asStateFlow()
    private val _usageError = MutableStateFlow<String?>(null)
    val usageError: StateFlow<String?> = _usageError.asStateFlow()
    private val credentials = dependencies.credentials
    private val applicationContext = context.applicationContext
    val agent by lazy { dev.gray.companion.agent.AgentController(applicationContext, viewModelScope, this) }
    private val pluginAccess by lazy { PluginAccessStore(applicationContext, credentials) }
    private data class PreparedTurn(
        val library: Library,
        val session: Session,
        val revision: Long,
        val secrets: Map<CredentialSlot, String>,
        val input: String,
        val regenerationBase: Session?,
        val beforeUserSession: Session?,
        val sourceSessionId: String,
        val recovery: RunRecovery? = null,
        val decision: RecoveryDecision? = null,
        val completion: CompletableDeferred<Unit>,
        val regenerationAdmission: RegenerationAdmission? = null,
    )
    private val mutex = Mutex()
    @Volatile private var runJob: Job? = null
    @Volatile private var stopAfterCheckpoint = false
    @Volatile private var runCompletion: CompletableDeferred<Unit>? = null
    @Volatile private var runningExtensionIds: Set<String> = emptySet()
    @Volatile private var revision = 0L
    private var lastRetry: Pair<String, String>? = null // session ID to input
    @Volatile private var storageReady = false
    private var editingCount = 0
    private var importDraft: ConfigurationImportDraft? = null
    private val importGeneration = AtomicLong()
    private data class ImportPreparation(val id: String?, val issues: List<Issue>)
    private val _configurationImport = MutableStateFlow<ConfigurationImportPreview?>(null)
    val configurationImport: StateFlow<ConfigurationImportPreview?> = _configurationImport.asStateFlow()
    private val workspaceMutex = Mutex()
    private val _configurationWorkspaces = MutableStateFlow<List<ConfigurationWorkspace>>(emptyList())
    val configurationWorkspaces: StateFlow<List<ConfigurationWorkspace>> = _configurationWorkspaces.asStateFlow()

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()
    private val _state = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val optionsController = AutomaticOptionsController(viewModelScope, library) { snapshot, session, profile ->
        val secrets = withContext(Dispatchers.IO) {
            NativeProviderCodec.requiredCredentialSlots(profile).associateWith { credentials.get(it) }
        }
        NativeProviderCodec.request(profile) { secrets[it].orEmpty() }
        agentPromptRecorder.wrap(session.id, gatewayFactory(credentialSnapshot(secrets), session.id,
            snapshot.connections.associate { it.id to it.maxConcurrentRequests }, ::recordUsage))
    }
    val automaticOptions: StateFlow<AutomaticOptionsUiState> = optionsController.state

    private val illustrationCredential = CredentialSlot("builtin.bizyair.illustration", CredentialKind.PLUGIN_SECRET)
    private val illustrationController = IllustrationController(viewModelScope, library,
        gateway = { snapshot, session, profile ->
            val secrets = withContext(Dispatchers.IO) {
                NativeProviderCodec.requiredCredentialSlots(profile).associateWith { credentials.get(it) }
            }
            NativeProviderCodec.request(profile) { secrets[it].orEmpty() }
            agentPromptRecorder.wrap(session.id, gatewayFactory(credentialSnapshot(secrets), session.id,
                snapshot.connections.associate { it.id to it.maxConcurrentRequests }, ::recordUsage))
        },
        keyProvider = { withContext(Dispatchers.IO) { credentials.get(illustrationCredential) } },
        client = BizyAirClient(File(applicationContext.filesDir, "illustrations")),
        publish = { config, image, replaceSource -> mutex.withLock {
            val current = _library.value
            val source = current.sessions.firstOrNull { it.id == image.sessionId }
            if (!storageReady || current.illustration != config || !config.enabled || source == null ||
                !IllustrationCore.isCurrentSource(source, image.messageId, image.sourceKey)) false
            else {
                val next = current.copy(illustrationImages = current.illustrationImages.filterNot {
                    it.sessionId == image.sessionId && it.messageId == image.messageId &&
                        it.sourceKey == image.sourceKey && (replaceSource || it.afterParagraphId == image.afterParagraphId)
                } + image)
                repository.save(next)
                _library.value = next
                true
            }
        } })
    val illustrationActivity: StateFlow<IllustrationActivity> = illustrationController.state
    fun acknowledgeIllustrationRemoteStatus() = illustrationController.acknowledgeRemoteStatus()

    fun regenerateIllustration(sessionId: String, messageId: String): Boolean {
        if (!storageReady || _busy.value || _library.value.selectedSessionId != sessionId) return false
        return illustrationController.regenerate(sessionId, messageId)
    }

    fun bizyAirConfigured(): Boolean = runCatching { credentials.get(illustrationCredential).isNotBlank() }.getOrDefault(false)

    fun setBizyAirCredential(secret: String): List<Issue> = try {
        val value = secret.trim()
        require(value.length <= 16384 && value.none { it == '\r' || it == '\n' || it == '\u0000' })
        if (value.isEmpty()) credentials.remove(illustrationCredential) else credentials.put(illustrationCredential, value)
        emptyList()
    } catch (_: Exception) { listOf(Issue("illustration.credential", "BizyAir 密钥保存失败")) }

    suspend fun saveIllustration(config: IllustrationConfig): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("illustration", "资源库尚未就绪"))
        val issues = IllustrationCore.validate(config)
        if (issues.any { it.error }) return@withLock issues
        if (config.enabled && ModelCatalog.resolveProfile(_library.value,
                config.modelProfileId ?: config.generation.profileId.orEmpty()) == null)
            return@withLock listOf(Issue("illustration.modelProfileId", "插图规划模型不存在，请重新选择"))
        try {
            val next = _library.value.copy(illustration = config)
            repository.save(next)
            _library.value = next
            emptyList()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { listOf(Issue("illustration", "插图设置保存失败，原设置仍保留")) }
    }

    fun generateAutomaticOptions() {
        val requested = automaticOptions.value
        if (!requested.repairAvailable) {
            optionsController.generate()
            return
        }
        viewModelScope.launch {
            val ready = mutex.withLock {
                if (!storageReady) { setError("资源库尚未就绪"); return@withLock false }
                val current = _library.value
                val session = current.sessions.firstOrNull { it.id == current.selectedSessionId }
                    ?: return@withLock false
                if (session.id != requested.sessionId ||
                    AutomaticOptions.source(current, session)?.key != requested.sourceKey) return@withLock false
                try {
                    // Recheck current settings: a stale error must not modify a newer configuration.
                    AutomaticOptions.request(current, session)
                } catch (_: AutomaticOptionsAssistantPrefill) {
                    val next = current.copy(automaticOptions = AutomaticOptions.withGenerationRequest(current.automaticOptions))
                    val issues = AutomaticOptions.validate(next)
                    if (issues.any { it.error }) {
                        setError(issues.first { it.error }.message)
                        return@withLock false
                    }
                    try {
                        require(AutomaticOptions.request(next, session)?.messages?.lastOrNull()?.role == Role.USER)
                        repository.save(next)
                        _library.value = next
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { setError("自动选项修复未能保存；原配置仍保留"); return@withLock false }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    setError(failure.message ?: "自动选项提示词无法编译")
                    return@withLock false
                }
                true
            }
            if (ready) optionsController.generate()
        }
    }

    /** Auxiliary preferences can change independently of a running main call. */
    suspend fun saveAutomaticOptions(config: AutomaticOptionsConfig): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("automaticOptions", "资源库尚未就绪"))
        val next = _library.value.copy(automaticOptions = config)
        val issues = AutomaticOptions.validate(next)
        if (issues.any { it.error }) return@withLock issues
        try {
            repository.save(next)
            _library.value = next
            emptyList()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { listOf(Issue("automaticOptions", "选项设置保存失败，原设置仍保留")) }
    }

    init {
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                var initial = CompanionProviderAdapter.withBuiltInProviders(
                    LibraryFormat.upgrade(ModelCatalog.normalize(if (loaded == Library()) LibraryDefaults.create() else loaded).withStandardCharacterFields()))
                val memoryInitialization = GlobalMemory.ensureGlobal(initial)
                initial = memoryInitialization.library ?: initial
                val issues = ConfigurationAdmission.validate(initial)
                if (issues.any { it.error }) {
                    _library.value = initial
                    _state.value = _state.value.copy(loading = false, issues = issues)
                    setError("资源库含无效数据；请先导出备份")
                    return@launch
                }
                if (loaded != Library() && applicationContext.readBooleanPreference("create_new_conversation_on_start", true)) {
                    val current = initial.sessions.firstOrNull { it.id == initial.selectedSessionId }
                    if (current != null && current.pendingRun == null && (current.messages.isNotEmpty() || current.state.isNotEmpty() ||
                        current.results.isNotEmpty() || current.macroHistory.isNotEmpty() || current.promptInjections.isNotEmpty() ||
                        current.historyMemory != HistoryMemoryState() || current.currentOnlyMacros.isNotEmpty() || current.userMacros.isNotEmpty())) {
                        initial = initial.startNewConversation()
                    }
                }
                if (initial !== loaded || repository.requiresUpgradeSave) {
                    // Copy secrets first; retain old encrypted keys if saving fails or migration is interrupted.
                    withContext(Dispatchers.IO) {
                        loaded.profiles.filter { it.apiConnectionId == null }.forEach { profile ->
                            val target = ModelCatalog.legacyConnectionId(profile.id)
                            val secret = credentials.get(profile.id)
                            if (secret.isNotBlank() && credentials.get(target).isBlank()) credentials.put(target, secret)
                        }
                    }
                    repository.save(initial)
                }
                mutex.withLock {
                    _library.value = initial
                    storageReady = true
                    _state.value = _state.value.copy(loading = false, issues = issues + memoryInitialization.issues)
                    if (memoryInitialization.library == null)
                        setError(memoryInitialization.issues.firstOrNull()?.message ?: "旧记忆配置需要调整，其他资源仍可使用")
                }
            } catch (failure: Exception) {
                if (applicationContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    // Log locations only: exception messages can contain persisted content or credentials.
                    android.util.Log.e("CompanionStartup", "${failure.javaClass.simpleName}\n${failure.stackTrace.take(8).joinToString("\n")}")
                }
                setError("资源库无法打开；数据未被清除")
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    /** Invalid edits are reported and never committed or shown as saved state. */
    suspend fun updateLibrary(transform: (Library) -> Library): List<Issue> = updateConfiguration { current ->
        try {
            val edited = transform(current)
            val issues = LibraryEditPreparation.extensionMetadataIssues(current, edited)
            ExtensionEditResult(edited.takeIf { issues.isEmpty() }, issues)
        }
        catch (_: Exception) { ExtensionEditResult(null, listOf(Issue("library", "编辑操作失败"))) }
    }

    /** Extension edits share the original admission, validation and durable publication boundary. */
    suspend fun updateExtensions(command: (Library) -> ExtensionEditResult): List<Issue> = updateConfiguration(command)

    /** A global off switch cancels only a run that captured this extension. */
    suspend fun setExtensionEnabled(expected: ConfigurationExtension, enabled: Boolean): List<Issue> {
        val stopAffected = mutex.withLock {
            if (!storageReady) return listOf(Issue("extensions", "资源库尚未就绪"))
            if (_library.value.extensions.firstOrNull { it.id == expected.id } != expected)
                return listOf(Issue("extensions", "扩展定义已改变，请刷新后切换"))
            editingCount++
            !enabled && expected.id in runningExtensionIds
        }
        try {
            if (stopAffected) stopAndJoin()
            return mutex.withLock {
                val current = _library.value
                val result = ExtensionCommands.setEnabled(current, expected, enabled)
                val proposed = result.library ?: return@withLock result.issues
                val next = LibraryEditPreparation.prepareRecoveryEdits(current, proposed)
                val issues = ConfigurationAdmission.validate(next).filter { it.error }
                if (issues.isNotEmpty()) return@withLock issues
                try {
                    repository.save(next)
                    revision++
                    _library.value = next
                    if (stopAffected) lastRetry = null
                    emptyList()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { listOf(Issue("extensions", "开关保存失败，原设置保留")) }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { editingCount-- } }
        }
    }

    /** Memory definitions affect future turns; accepted summaries and frozen recovery are untouched. */
    suspend fun updateMemoryConfiguration(command: (Library) -> ExtensionEditResult): List<Issue> {
        mutex.withLock { editingCount++ }
        try {
            stopAndJoin()
            return mutex.withLock {
                if (!storageReady) return@withLock listOf(Issue("memoryConfig", "资源库尚未就绪"))
                val result = try { command(_library.value) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withLock listOf(Issue("memoryConfig", "记忆设置保存失败，原设置仍保留")) }
                val proposed = result.library
                if (proposed == null || result.issues.any { it.error })
                    return@withLock reportConfigurationIssues(result.issues.ifEmpty { listOf(Issue("memoryConfig", "记忆配置修改未应用")) })
                val next = LibraryEditPreparation.prepareRecoveryEdits(_library.value, proposed)
                val issues = ConfigurationAdmission.validate(next).filter { it.error }
                if (issues.isNotEmpty()) return@withLock reportConfigurationIssues(issues)
                saveConfiguration(next, result.issues)
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { editingCount-- } }
        }
    }

    /** Global policy changes preserve each chat's accepted memory and old branch records. */
    suspend fun setMemoryEnabled(expected: GlobalMemoryConfig, enabled: Boolean): List<Issue> {
        mutex.withLock {
            if (!storageReady) return listOf(Issue("memoryConfig", "资源库尚未就绪"))
            if (_library.value.memoryConfig != expected)
                return listOf(Issue("memoryConfig", "记忆设置已改变，请刷新后切换"))
            if (enabled && (_busy.value || runCompletion?.isCompleted == false))
                return listOf(Issue("memoryConfig", "请先完成当前轮，再开启记忆"))
            editingCount++
        }
        try {
            if (!enabled) stopAndJoin()
            return mutex.withLock {
                val current = _library.value
                if (current.memoryConfig != expected)
                    return@withLock listOf(Issue("memoryConfig", "记忆设置已改变，请刷新后切换"))
                val result = GlobalMemory.setEnabled(current, expected, enabled)
                val proposed = result.library ?: return@withLock result.issues
                val next = LibraryEditPreparation.prepareRecoveryEdits(current, proposed)
                val issues = ConfigurationAdmission.validate(next).filter { it.error }
                if (issues.isNotEmpty()) return@withLock issues
                val saved = saveConfiguration(next, result.issues)
                if (saved.none { it.error } && !enabled) lastRetry = null
                saved
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { editingCount-- } }
        }
    }

    /** Resource plans and source-field edits use the same admission and publication path. */
    suspend fun updateResources(command: (Library) -> ExtensionEditResult): List<Issue> = updateConfiguration(command)

    /** Draft operations have a separate lock and never stop or modify the active conversation. */
    suspend fun openConfigurationWorkspace(label: String = "配置工作台"): WorkspaceOperationResult = workspaceMutex.withLock {
        if (!storageReady) return@withLock WorkspaceOperationResult(status = "NOT_READY",
            issues = listOf(Issue("workspace", "资源库尚未就绪")), retryable = true)
        val open = _configurationWorkspaces.value.filter { it.status == ConfigurationWorkspaceStatus.OPEN }
        if (open.size >= 8) return@withLock WorkspaceOperationResult(status = "LIMIT",
            issues = listOf(Issue("workspace", "最多保留8个草稿，请先应用或放弃其中一个")))
        val workspace = ConfigurationWorkspaces.open(_library.value, label.ifBlank { "配置工作台" })
        _configurationWorkspaces.value = open + workspace
        WorkspaceOperationResult(workspace)
    }

    suspend fun stageConfigurationWorkspace(id: String, expectedRevision: Long, text: String): WorkspaceOperationResult =
        editConfigurationWorkspace(id) { workspace ->
            ConfigurationWorkspaces.stageCommands(workspace, expectedRevision, text, ConfigurationAdmission::validateNative)
        }

    suspend fun stageConfigurationField(id: String, expectedRevision: Long, target: ExtensionFieldRef,
        expectedValue: String?, value: String?): WorkspaceOperationResult = editConfigurationWorkspace(id) { workspace ->
        ConfigurationWorkspaces.stageField(workspace, expectedRevision, target, expectedValue, value,
            ConfigurationAdmission::validateNative)
    }

    suspend fun previewConfigurationWorkspace(id: String, expectedRevision: Long): WorkspaceOperationResult =
        editConfigurationWorkspace(id) { workspace ->
            ConfigurationWorkspaces.preview(workspace, expectedRevision, ConfigurationAdmission::validateNative)
        }

    private suspend fun editConfigurationWorkspace(id: String,
        edit: (ConfigurationWorkspace) -> ConfigurationWorkspaceResult): WorkspaceOperationResult = workspaceMutex.withLock {
        val workspace = _configurationWorkspaces.value.firstOrNull { it.id == id }
            ?: return@withLock WorkspaceOperationResult(status = "NOT_FOUND", issues = listOf(Issue("workspace", "草稿已不存在")))
        val result = withContext(Dispatchers.Default) { edit(workspace) }
        replaceConfigurationWorkspace(result.workspace)
        WorkspaceOperationResult(result.workspace, result.status.name, result.issues, result.retryable)
    }

    suspend fun discardConfigurationWorkspace(id: String, expectedRevision: Long): WorkspaceOperationResult = workspaceMutex.withLock {
        val workspace = _configurationWorkspaces.value.firstOrNull { it.id == id }
            ?: return@withLock WorkspaceOperationResult(status = "NOT_FOUND", issues = listOf(Issue("workspace", "草稿已不存在")))
        if (workspace.revision != expectedRevision) return@withLock WorkspaceOperationResult(workspace, "STALE",
            listOf(Issue("workspace.revision", "草稿已改变，请重新查看后操作")), true)
        val discarded = ConfigurationWorkspaces.discard(workspace)
        replaceConfigurationWorkspace(discarded)
        WorkspaceOperationResult(discarded)
    }

    /** Reuses resource CAS and the existing SQLite transaction, including its undo receipt. */
    suspend fun commitConfigurationWorkspace(id: String, expectedRevision: Long): WorkspaceOperationResult = workspaceMutex.withLock workspaceLock@{
        val workspace = _configurationWorkspaces.value.firstOrNull { it.id == id }
            ?: return@workspaceLock WorkspaceOperationResult(status = "NOT_FOUND", issues = listOf(Issue("workspace", "草稿已不存在")))
        val prepared = ConfigurationWorkspaces.commitPlan(workspace, expectedRevision)
        val plan = prepared.plan ?: return@workspaceLock WorkspaceOperationResult(workspace,
            prepared.status.name, prepared.issues, prepared.status == ConfigurationWorkspaceResultStatus.STALE)
        mutex.withLock liveLock@{
            if (!storageReady) return@liveLock WorkspaceOperationResult(workspace, "NOT_READY",
                listOf(Issue("workspace", "资源库尚未就绪")), true)
            if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
                return@liveLock WorkspaceOperationResult(workspace, "BUSY",
                    listOf(Issue("workspace", "当前操作仍在进行；草稿已保留，结束后可再次应用")), true)
            val current = _library.value
            val applied = withContext(Dispatchers.Default) {
                ConfigurationCommands.apply(plan, current, ConfigurationAdmission::validateNative)
            }
            val changed = applied.library ?: return@liveLock WorkspaceOperationResult(workspace, "CONFLICT_OR_INVALID", applied.issues)
            val next = applied.receipt?.let { receipt -> changed.copy(
                configurationEdits = ConfigurationReceiptHistory.append(current.configurationEdits, receipt)) } ?: changed
            val issues = saveConfiguration(next, applied.issues)
            if (issues.any { it.error }) return@liveLock WorkspaceOperationResult(workspace, "SAVE_FAILED", issues, true)
            val committed = ConfigurationWorkspaces.committed(workspace, applied.receipt?.id)
            replaceConfigurationWorkspace(committed)
            WorkspaceOperationResult(committed, issues = issues)
        }
    }

    private fun replaceConfigurationWorkspace(workspace: ConfigurationWorkspace) {
        _configurationWorkspaces.value = _configurationWorkspaces.value.map { if (it.id == workspace.id) workspace else it }
    }

    /** Layout is a session preference: reject active work atomically instead of stopping a run. */
    suspend fun updateCardLayout(sessionId: String, expected: List<CardPlacement>, next: List<CardPlacement>): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("session", "资源库尚未就绪"))
        if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
            return@withLock listOf(Issue("session", "请先等待当前操作完成，再调整卡片布局"))
        val current = _library.value
        val result = CardLayoutCommands.update(current, sessionId, expected, next)
        val candidate = result.library ?: return@withLock result.issues
        if (candidate == current) return@withLock result.issues
        val issues = ConfigurationAdmission.validate(candidate).filter { it.error }
        if (issues.isNotEmpty()) return@withLock issues
        saveConfiguration(candidate, result.issues)
    }

    /** Original provider presentation commits to the same resource and credential authority. */
    internal suspend fun updateProviders(plan: CompanionProviderPlan): List<Issue> =
        updateConfiguration(plan.credentialChanges) { current ->
            val prepared = CompanionProviderAdapter.apply(plan, current)
            ExtensionEditResult(prepared.library, prepared.issues)
        }

    private suspend fun updateConfiguration(prepare: (Library) -> ExtensionEditResult): List<Issue> =
        updateConfiguration(emptyList(), prepare)

    private suspend fun updateConfiguration(credentialWrites: List<CredentialWrite>, prepare: (Library) -> ExtensionEditResult): List<Issue> {
        mutex.withLock { editingCount++ }
        try {
            stopAndJoin()
            return mutex.withLock {
            if (!storageReady) return@withLock listOf(Issue("library", "资源库尚未就绪；不会覆盖原数据"))
            val before = _library.value
            val proposal = try { prepare(before) } catch (_: Exception) {
                AppEventLog.record("配置", "编辑操作失败", AppLogLevel.ERROR)
                return@withLock listOf(Issue("library", "编辑操作失败"))
            }
            val edited = proposal.library?.let { candidate ->
                proposal.receipt?.let { receipt -> candidate.copy(configurationEdits = ConfigurationReceiptHistory.append(before.configurationEdits, receipt)) }
                    ?: candidate
            }
            if (edited == null || proposal.issues.any { it.error }) {
                val failures = proposal.issues.ifEmpty { listOf(Issue("library", "配置修改未应用")) }
                return@withLock reportConfigurationIssues(failures)
            }
            val preparedEdit = LibraryEditPreparation.prepareRecoveryEdits(before, edited)
            val rawIssues = ConfigurationAdmission.validate(preparedEdit).filter { it.error }
            if (rawIssues.isNotEmpty()) {
                return@withLock reportConfigurationIssues(rawIssues)
            }
            val next = LibraryEditPreparation.normalize(before, preparedEdit)
            val issues = LibraryValidator.validate(next).filter { it.error } + LibraryEditPreparation.removalIssues(before, next)
            if (issues.isNotEmpty()) {
                return@withLock reportConfigurationIssues(issues)
            }
            try {
                if (credentialWrites.isEmpty()) repository.save(next)
                else {
                    val commitIssues = ConfigurationCredentialCommit.save(repository, credentials, next, credentialWrites)
                    if (commitIssues.isNotEmpty()) {
                        _state.value = _state.value.copy(issues = commitIssues)
                        return@withLock commitIssues
                    }
                }
                revision++
                _library.value = next
                _state.value = _state.value.copy(issues = emptyList(), error = null, traces = emptyList(), streaming = "", pendingOutputs = emptyList())
                _error.value = null
                _streaming.value = ""
                AppEventLog.record("配置", "配置已保存")
                val removedConnections = before.connections.map { it.id }.toSet() - next.connections.map { it.id }.toSet()
                val removedLegacy = before.profiles.filter { it.apiConnectionId == null && next.profiles.none { other -> other.id == it.id } }.map { it.id }
                val removedOverrides = before.profiles.filter { previous ->
                    val following = next.profiles.firstOrNull { it.id == previous.id }
                    following == null || (previous.nativeModel?.get("providerOverwrite") is kotlinx.serialization.json.JsonObject &&
                        following.nativeModel?.get("providerOverwrite") !is kotlinx.serialization.json.JsonObject)
                }.flatMap { listOf(CredentialSlot(it.id, CredentialKind.MODEL_API_KEY),
                    CredentialSlot(it.id, CredentialKind.MODEL_PRIVATE_KEY)) }
                val removedSlots = removedConnections.flatMap { listOf(CredentialSlot(it), CredentialSlot(it, CredentialKind.PRIVATE_KEY)) } +
                    removedLegacy.map { CredentialSlot(it) } + removedOverrides
                val cleanupFailed = removedSlots.map { runCatching { credentials.remove(it) }.isFailure }.any { it }
                if (cleanupFailed) {
                    val warning = Issue("connection.apiKey", "配置已保存，但旧密钥清理失败", error = false)
                    _state.value = _state.value.copy(issues = listOf(warning))
                    proposal.issues + warning
                } else proposal.issues
            } catch (_: Exception) {
                AppEventLog.record("配置", "配置保存失败", AppLogLevel.ERROR)
                val failure = listOf(Issue("library", "保存失败；原数据仍保留"))
                _state.value = _state.value.copy(issues = failure)
                failure
            }
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { editingCount-- } }
        }
    }

    suspend fun selectSession(id: String): List<Issue> = updateLibrary { it.copy(selectedSessionId = id) }
    suspend fun newSession(): List<Issue> = updateLibrary { it.startNewConversation() }

    /** Presentation metadata does not invalidate an in-flight world execution. */
    suspend fun renameSession(sessionId: String, title: String): List<Issue> =
        updateSessionMetadata(sessionId) {
            it.copy(title = title, titlePending = false, updatedAt = System.currentTimeMillis())
        }

    suspend fun copySession(sessionId: String): List<Issue> = updateLibrary { ChatArchive.duplicate(it, sessionId) }
    suspend fun moveSession(sessionId: String, folderId: String?): List<Issue> =
        updateLibrary { ConversationMetadata.move(it, sessionId, folderId) }
    suspend fun createFolder(name: String): List<Issue> = updateLibrary { ConversationMetadata.createFolder(it, name) }
    suspend fun renameFolder(id: String, name: String): List<Issue> = updateLibrary { ConversationMetadata.renameFolder(it, id, name) }
    suspend fun deleteFolder(id: String): List<Issue> = updateLibrary { ConversationMetadata.deleteFolder(it, id) }

    private suspend fun updateSessionMetadata(sessionId: String, transform: (Session) -> Session): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("session", "资源库尚未就绪"))
        val current = _library.value
        val session = current.sessions.firstOrNull { it.id == sessionId }
            ?: return@withLock listOf(Issue("session", "会话不存在"))
        val changed = transform(session)
        if (changed == session) return@withLock emptyList()
        val next = current.copy(sessions = current.sessions.map { if (it.id == sessionId) changed else it })
        try {
            repository.save(next)
            _library.value = next
            emptyList()
        } catch (_: Exception) { listOf(Issue("session", "保存失败；原数据仍保留")) }
    }

    suspend fun editMessage(sessionId: String, messageId: String, text: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.editText(it, messageId, text) }

    suspend fun selectTextRevision(sessionId: String, messageId: String, revisionId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.selectTextRevision(it, messageId, revisionId) }

    suspend fun selectUserVersion(sessionId: String, messageId: String, revisionId: String): List<Issue> =
        updateMessage(sessionId) { ConversationPaths.selectUserVersion(it, messageId, revisionId) }

    suspend fun selectOpeningRevision(sessionId: String, messageId: String, revisionId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.selectOpeningRevision(it, messageId, revisionId) }

    suspend fun deleteMessage(sessionId: String, messageId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.deleteMessage(it, messageId) }

    suspend fun rollbackPendingUserTurn(sessionId: String, runId: String, messageId: String): List<Issue> =
        updateMessage(sessionId, expectedPendingRunId = runId) {
            MessageOperations.rollbackPendingUserTurn(it, messageId)
        }

    suspend fun appendUserOnly(sessionId: String, text: String): List<Issue> =
        updateMessage(sessionId) { session ->
            val expanded = UserInputMacros.expand(text, CompletedWorlds.executionLibrary(_library.value, session), session,
                ExecutionSemantics.CURRENT)
            val plain = expanded.text?.trim()
            if (expanded.issues.any { it.error }) MessageOperationResult(issues = expanded.issues)
            else if (plain.isNullOrBlank()) MessageOperationResult(issues = listOf(Issue("message", "用户消息不能为空")))
            else MessageOperationResult(session.copy(
                messages = session.messages + ChatMessage(role = Role.USER, text = plain, speakerName = _library.value.userName(session)),
                updatedAt = System.currentTimeMillis(),
            ))
        }

    suspend fun selectGenerationVariant(sessionId: String, turnId: String, variantId: String, messageId: String? = null, revisionId: String? = null): List<Issue> =
        updateMessage(sessionId) { session ->
            val selected = ConversationPaths.selectGenerationVariant(session, turnId, variantId)
            val changed = selected.session
            if (changed != null && messageId != null && revisionId != null)
                MessageOperations.selectTextRevision(changed, messageId, revisionId)
            else selected
        }

    suspend fun deleteGenerationVariant(sessionId: String, turnId: String, variantId: String): List<Issue> =
        updateMessage(sessionId) { ConversationPaths.deleteGenerationVariant(it, turnId, variantId) }

    suspend fun forkMessage(sessionId: String, messageId: String): List<Issue> =
        updateMessage(sessionId, fork = true) { MessageOperations.forkAt(it, messageId) }

    private suspend fun updateMessage(sessionId: String, fork: Boolean = false,
        expectedPendingRunId: String? = null,
        operation: (Session) -> MessageOperationResult): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("session", "资源库尚未就绪"))
        if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
            return@withLock listOf(Issue("session", "请先等待当前操作完成再修改消息"))
        val current = _library.value
        if (current.selectedSessionId != sessionId) return@withLock listOf(Issue("session", "当前会话已改变"))
        val session = current.sessions.firstOrNull { it.id == sessionId }
            ?: return@withLock listOf(Issue("session", "会话不存在"))
        if (expectedPendingRunId != null && session.pendingRun?.id != expectedPendingRunId)
            return@withLock listOf(Issue("recovery", "断点已改变，请重新打开恢复选项"))
        val result = try {
            operation(session)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withLock listOf(Issue("session", "消息操作准备失败（${failure.javaClass.simpleName}）；原数据仍保留"))
        }
        val candidate = result.session ?: return@withLock result.issues
        val world = candidate.executableWorld
        val checkpointRollback = session.pendingRun?.let { pending ->
            !pending.regenerating && candidate.messages.none { it.id == pending.turnId }
        } == true
        val mustReplay = !checkpointRollback && world != null && (world != session.executableWorld ||
            world.recipes.any { recipe -> recipe.targets.any { it.key in candidate.unavailableResultKeys } })
        val rebuilt = if (mustReplay) withContext(Dispatchers.Default) { BranchReplay.rebuild(candidate, QuickJsPluginExecutor()) } else result
        var changed = rebuilt.session ?: return@withLock rebuilt.issues
        if (changed != session) changed = MessageOperations.discardRecovery(changed)
        if (fork) {
            val prefix = "${session.title} 分支"
            var number = 1
            while (current.sessions.any { it.title == "$prefix$number" }) number++
            changed = changed.copy(title = "$prefix$number", titlePending = false, inactivePaths = emptyList())
        }
        if (changed == session) return@withLock result.issues
        val next = if (fork || changed.id != session.id) current.copy(sessions = current.sessions + changed, selectedSessionId = changed.id)
            else current.copy(sessions = current.sessions.map { if (it.id == sessionId) changed else it })
        try {
            repository.save(next)
            revision++
            _library.value = next
            lastRetry = null
            _error.value = null
            _state.value = _state.value.copy(error = null, issues = emptyList(), traces = emptyList())
            result.issues
        } catch (_: Exception) { listOf(Issue("session", "保存失败；原数据仍保留")) }
    }
    suspend fun saveChat(sessionId: String, folderId: String): List<Issue> = updateLibrary { ChatArchive.save(it, sessionId, folderId) }
    suspend fun saveCurrentChatCopy(sessionId: String): List<Issue> = updateLibrary { ChatArchive.saveCurrentCopy(it, sessionId) }
    suspend fun restoreSavedChat(id: String): List<Issue> = updateLibrary { ChatArchive.restore(it, id) }

    suspend fun refreshUsage(days: Int = 7) {
        require(days in 1..366)
        usageMutex.withLock {
            val now = System.currentTimeMillis()
            val zone = java.time.ZoneId.systemDefault()
            val from = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant().toEpochMilli()
            try {
                val records = usageRepository.query(from, now)
                usageFrom = from
                _usage.value = records
                _usageError.value = null
            } catch (_: Exception) { _usageError.value = "用量记录暂时无法读取" }
        }
    }

    private suspend fun recordUsage(record: ModelUsageRecord) {
        usageMutex.withLock {
            try {
                usageRepository.append(record)
                if (record.startedAt >= usageFrom) _usage.value = (_usage.value + record).sortedByDescending { it.startedAt }
                _usageError.value = null
            } catch (_: Exception) { _usageError.value = "本次用量保存失败，统计可能不完整" }
        }
    }
    suspend fun selectCharacter(id: String): List<Issue> {
        val current = library.value
        if (current.characters.none { it.id == id }) return listOf(Issue("character.$id", "角色卡不存在"))
        if (current.sessions.firstOrNull { it.id == current.selectedSessionId }?.characterId == id) return emptyList()
        return updateLibrary { it.selectCharacter(id) }
    }
    suspend fun resetSessionState(id: String): List<Issue> = updateLibrary { old ->
        old.copy(sessions = old.sessions.map { if (it.id == id) it.withRuntime(RuntimeSnapshot()).copy(macroHistory = emptyMap(), pendingRun = null) else it })
    }

    suspend fun preview(input: String = ""): PromptPreview {
        val snapshot = library.value
        val session = snapshot.sessions.firstOrNull { it.id == snapshot.selectedSessionId }
            ?: return PromptPreview(emptyList(), listOf(Issue("session", "请选择会话")))
        val main = snapshot.mainCalls.firstOrNull { it.id == session.mainCallId }
            ?: return PromptPreview(emptyList(), listOf(Issue("mainCall", "请选择主调用")))
        val defaultWorkflow = main.workflows.firstOrNull { it.id == main.primaryWorkflowId } ?: main.workflows.firstOrNull()
        val nodes = (listOfNotNull(defaultWorkflow) + main.workflows.filter { it.id != defaultWorkflow?.id } + snapshot.loadedCallGroups(session).flatMap { it.workflows })
            .filter { it.enabled }.flatMap { it.nodes }.filter { it.enabled }
        val node = defaultWorkflow?.takeIf { it.enabled }?.nodes?.firstOrNull { it.enabled && it.actionId == "model.generate" }
            ?: nodes.firstOrNull { it.actionId == "model.generate" } ?: nodes.firstOrNull()
            ?: return PromptPreview(emptyList(), listOf(Issue("node", "当前调用没有可预览的节点")))
        val draft = if (input.isNotBlank()) session.copy(messages = session.messages + ChatMessage(role = Role.USER, text = input, speakerName = snapshot.userName(session))) else session
        return PromptCompiler().preview(snapshot, draft, node, input)
    }

    /** A read-only Agent view. "recent" observes this process's real gateway calls; "preview" estimates a next call. */
    suspend fun agentPromptPreview(args: JsonObject): JsonObject {
        fun string(key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun failure(status: String, message: String) = buildJsonObject {
            put("status", status); put("retryable", false); put("message", message)
        }
        val mode = string("mode") ?: "preview"
        if (mode == "recent") {
            val sessionId = string("sessionId")
            if ("sessionId" in args && sessionId == null) return failure("INVALID", "sessionId 须为字符串")
            val limit = if ("limit" in args) {
                (args["limit"] as? JsonPrimitive)?.intOrNull
                    ?: return failure("INVALID", "limit 须为 1 到 10")
            } else 5
            if (limit !in 1..10) return failure("INVALID", "limit 须为 1 到 10")
            return buildJsonObject { put("status", "OK"); put("data", agentPromptRecorder.recent(sessionId, limit)) }
        }
        if (mode != "preview") return failure("INVALID", "mode 只能是 preview 或 recent")
        val sessionId = string("sessionId")?.takeIf { it.isNotBlank() }
            ?: return failure("INVALID", "预览需要 sessionId")
        for (key in listOf("input", "workflowId", "nodeId", "workspaceId"))
            if (key in args && string(key) == null) return failure("INVALID", "$key 须为字符串")
        val input = string("input").orEmpty()
        val workspaceId = string("workspaceId")
        val live = library.value
        val session = live.sessions.firstOrNull { it.id == sessionId }
            ?: return failure("NOT_FOUND", "会话不存在")
        val working = if (workspaceId == null) live else {
            val workspace = configurationWorkspaces.value.firstOrNull { it.id == workspaceId }
                ?: return failure("NOT_FOUND", "工作区不存在")
            if (workspace.status != ConfigurationWorkspaceStatus.OPEN)
                return failure("CLOSED", "工作区已关闭")
            val draft = ConfigurationCommands.previewEdit(workspace.base, workspace.draft, workspace.label,
                ConfigurationAdmission::validateNative)
            val plan = draft.plan ?: return failure("INVALID", draft.issues.firstOrNull()?.message ?: "草稿无法预览")
            val applied = ConfigurationCommands.apply(plan, live, ConfigurationAdmission::validateNative)
            applied.library ?: return failure("CONFLICT_OR_INVALID", applied.issues.firstOrNull()?.message ?: "草稿与当前资源冲突")
        }
        val resources = try { CompletedWorlds.executionLibrary(working, session) }
            catch (_: Exception) { return failure("INVALID", "会话冻结资源不可用") }
        val main = resources.mainCalls.firstOrNull { it.id == session.mainCallId }
            ?: return failure("NOT_FOUND", "会话主调用不可用")
        val primary = main.workflows.firstOrNull { it.id == main.primaryWorkflowId }
        val workflows = (listOfNotNull(primary) + main.workflows.filter { it.id != primary?.id } +
            resources.loadedCallGroups(session).flatMap { it.workflows }).filter { it.enabled }
        val workflowId = string("workflowId")
        val nodeId = string("nodeId")
        val candidates = workflows.flatMap { workflow -> workflow.nodes.filter { it.enabled }
            .map { workflow to it } }.filter { (workflow, node) ->
                (workflowId == null || workflow.id == workflowId) && (nodeId == null || node.id == nodeId)
            }
        if (nodeId != null && workflowId == null && candidates.size > 1)
            return failure("AMBIGUOUS", "节点 ID 对应多个工作流，请指定 workflowId")
        val chosen = candidates.firstOrNull { it.second.actionId == "model.generate" } ?: candidates.firstOrNull()
            ?: return failure("NOT_FOUND", "未找到可预览的已启用节点")
        val draftSession = if (input.isNotBlank()) session.copy(messages = session.messages + ChatMessage(
            role = Role.USER, text = input, speakerName = resources.userName(session))) else session
        val version = session.executableWorld?.executionVersion ?: ExecutionSemantics.CURRENT
        val preview = PromptCompiler().preview(resources, draftSession, chosen.second, input, executionVersion = version)
        return buildJsonObject {
            put("status", "OK")
            put("data", buildJsonObject {
                put("mode", "preview")
                put("scope", "按当前快照估算下一次调用；不代表已发送请求或运行时每阶段的输入处理")
                put("sessionId", sessionId)
                put("workflowId", chosen.first.id)
                put("nodeId", chosen.second.id)
                put("resourceWorld", if (session.executableWorld != null) "frozen" else if (workspaceId != null) "workspace" else "live")
                put("messages", buildJsonArray { preview.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.name); put("text", message.text)
                }) } })
                put("issues", LibraryFormat.json.encodeToJsonElement(preview.issues))
            })
        }
    }

    suspend fun send(text: String, expectedSessionId: String? = null): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        val targetSessionId = expectedSessionId ?: library.value.selectedSessionId
        viewModelScope.launch { run(text, appendUser = true, accepted = accepted, expectedSessionId = targetSessionId) }
        return accepted.await()
    }
    suspend fun retry() {
        val current = library.value.sessions.firstOrNull { it.id == library.value.selectedSessionId }
        current?.pendingRun?.let {
            continueRecovery(current.id, it.id)
            return
        }
        val (sessionId, input) = lastRetry ?: return
        if (sessionId == library.value.selectedSessionId) run(input, appendUser = false, expectedSessionId = sessionId)
    }

    suspend fun recover(sessionId: String, decision: RecoveryDecision): Boolean {
        val pending = library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun ?: return false
        return viewModelScope.async {
            run(pending.input, appendUser = false, expectedSessionId = sessionId,
                recoveryDecision = decision, expectedRecoveryRunId = pending.id)
        }.await()
    }

    /** A single click resumes by the exact journal observed by UI; true means it is fully cleared. */
    suspend fun continueRecovery(sessionId: String, runId: String): Boolean {
        val pending = library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun
            ?.takeIf { it.id == runId } ?: return false
        val initialTargets = pending.failures.map { it.nodeKey }
        if (initialTargets.isEmpty()) {
            viewModelScope.async {
                run(pending.input, appendUser = false, expectedSessionId = sessionId,
                    expectedRecoveryRunId = runId, continueInterrupted = true)
            }.await()
        } else for (key in initialTargets) {
            val current = library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun
                ?.takeIf { it.id == runId } ?: break
            if (current.failures.none { it.nodeKey == key }) continue
            if (!current.failures.first { it.nodeKey == key }.retryable) break
            val advanced = viewModelScope.async {
                run(current.input, appendUser = false, expectedSessionId = sessionId,
                    recoveryDecision = RecoveryDecision(RecoveryDecisionKind.RETRY, key), expectedRecoveryRunId = runId)
            }.await()
            if (!advanced) break
        }
        return library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun == null
    }

    suspend fun finishRecovery(sessionId: String, runId: String): Boolean {
        val issues = updateSessionMetadata(sessionId) { session ->
            if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0) return@updateSessionMetadata session
            session.pendingRun?.takeIf { it.id == runId } ?: return@updateSessionMetadata session
            MessageOperations.discardRecovery(session)
        }
        val finished = issues.none { it.error } && library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun == null
        if (finished && lastRetry?.first == sessionId) lastRetry = null
        return finished
    }

    fun recoveryNode(sessionId: String, runId: String, nodeKey: String): WorkflowNode? {
        val current = library.value
        val pending = current.sessions.firstOrNull { it.id == sessionId }?.pendingRun?.takeIf { it.id == runId } ?: return null
        val failure = pending.failures.firstOrNull { it.nodeKey == nodeKey } ?: return null
        if (ExecutionSemantics(pending.executionVersion).scopedMemoryResources)
            return runCatching { RecoveryPromptEdits.node(pending, nodeKey) }.getOrNull()
        return (current.mainCalls.flatMap { it.configuredWorkflows() } + current.groups.flatMap { it.workflows })
            .filter { it.id == failure.workflowId }.flatMap { it.nodes }.singleOrNull { it.id == failure.nodeId }
    }

    fun recoveryPromptLibrary(sessionId: String, runId: String, nodeKey: String): Library {
        val current = library.value
        val pending = current.sessions.firstOrNull { it.id == sessionId }?.pendingRun?.takeIf { it.id == runId }
        if (pending == null || !ExecutionSemantics(pending.executionVersion).scopedMemoryResources)
            return current.copy(selectedSessionId = sessionId)
        return RecoveryPromptEdits.resources(pending, nodeKey).copy(
            sessions = listOf(pending.initialSession), selectedSessionId = pending.initialSession.id)
    }

    suspend fun saveRecoveryPrompt(sessionId: String, runId: String, nodeKey: String, blocks: List<PromptBlock>, expectedBlocks: List<PromptBlock>): List<Issue> {
        val captured = library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun?.takeIf { it.id == runId }
        if (captured != null && ExecutionSemantics(captured.executionVersion).scopedMemoryResources) return mutex.withLock {
            if (!storageReady || _busy.value || runCompletion?.isCompleted == false || editingCount > 0)
                return@withLock listOf(Issue("recovery", "请等待当前操作结束后编辑断点"))
            val current = _library.value
            val session = current.sessions.firstOrNull { it.id == sessionId }
                ?: return@withLock listOf(Issue("recovery", "会话不存在"))
            val pending = session.pendingRun?.takeIf { it.id == runId }
                ?: return@withLock listOf(Issue("recovery", "断点已改变"))
            if (runCatching { RecoveryPromptEdits.node(pending, nodeKey).blocks }.getOrNull() != expectedBlocks)
                return@withLock listOf(Issue("recovery", "断点提示词已改变，请重新打开后编辑"))
            val repaired = try { RecoveryPromptEdits.apply(pending, nodeKey, blocks) }
            catch (failure: IllegalArgumentException) {
                return@withLock listOf(Issue("recovery", failure.message ?: "断点提示词修改未应用"))
            }
            val next = current.copy(sessions = current.sessions.map {
                if (it.id == sessionId) it.copy(pendingRun = repaired) else it
            })
            try { repository.save(next) }
            catch (_: Exception) { return@withLock listOf(Issue("recovery", "保存失败；原断点仍保留")) }
            _library.value = next
            revision++
            emptyList()
        }
        if (recoveryNode(sessionId, runId, nodeKey) == null) return listOf(Issue("recovery", "断点或对应调用已改变"))
        return updateLibrary { current ->
            val pending = current.sessions.first { it.id == sessionId }.pendingRun?.takeIf { it.id == runId }
                ?: error("断点已改变")
            val failure = pending.failures.first { it.nodeKey == nodeKey }
            fun repair(flow: Workflow): Workflow = if (flow.id != failure.workflowId) flow else
                flow.copy(nodes = flow.nodes.map { if (it.id == failure.nodeId) it.copy(blocks = blocks) else it })
            current.copy(mainCalls = current.mainCalls.map { main ->
                if (main.configuredWorkflows().none { it.id == failure.workflowId }) main
                else main.copy(workflows = main.workflows.map(::repair),
                    history = main.history.copy(processor = main.history.processor.copy(
                        workflow = repair(main.history.processor.workflow),
                        aggregationWorkflow = main.history.processor.aggregationWorkflow?.let(::repair))))
            }, groups = current.groups.map { group ->
                if (group.workflows.none { it.id == failure.workflowId }) group else group.copy(workflows = group.workflows.map(::repair))
            })
        }
    }

    suspend fun editUserAndSend(sessionId: String, messageId: String, text: String): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        viewModelScope.launch {
            try {
                AppEventLog.record("主聊天", "编辑发送准备开始")
                run(text, appendUser = false, accepted = accepted, expectedSessionId = sessionId, editedUserMessageId = messageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                setError("编辑发送准备失败（${failure.javaClass.simpleName}），请查看日志后重试")
            } finally {
                if (!accepted.isCompleted) accepted.complete(false)
            }
        }
        return accepted.await()
    }

    suspend fun regenerateMessage(sessionId: String, messageId: String): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        viewModelScope.launch {
            try {
                run("", appendUser = false, accepted = accepted, expectedSessionId = sessionId, regenerationMessageId = messageId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Admission/replay precedes run's execution guard. A malformed restore boundary
                // must report a failed reroll rather than escape viewModelScope and kill the app.
                setError("重新生成准备失败（${failure.javaClass.simpleName}），原候选仍保留")
            } finally {
                if (!accepted.isCompleted) accepted.complete(false)
            }
        }
        return accepted.await()
    }

    private suspend fun run(input: String, appendUser: Boolean, accepted: CompletableDeferred<Boolean>? = null, expectedSessionId: String? = null, regenerationMessageId: String? = null, editedUserMessageId: String? = null, recoveryDecision: RecoveryDecision? = null, expectedRecoveryRunId: String? = null, continueInterrupted: Boolean = false): Boolean = supervisorScope {
        if (regenerationMessageId == null && input.isBlank() && !continueInterrupted) { accepted?.complete(false); return@supervisorScope false }
        val prepared = mutex.withLock {
            if (!storageReady) { setError("资源库尚未就绪"); return@withLock null }
            if (editingCount > 0) { setError("配置正在保存，请稍后再试"); return@withLock null }
            if (_busy.value || runCompletion?.isCompleted == false) { setError("请先等待当前生成停止"); return@withLock null }
            val old = _library.value
            if (old.selectedSessionId != expectedSessionId) return@withLock null
            val session = old.sessions.firstOrNull { it.id == old.selectedSessionId }
            if (session == null) { setError("请先选择会话"); return@withLock null }
            val recovery = if (recoveryDecision != null || continueInterrupted)
                session.pendingRun?.takeIf { expectedRecoveryRunId == null || it.id == expectedRecoveryRunId }
                    ?: return@withLock null else null
            if (continueInterrupted && recovery?.failures?.isNotEmpty() != false) return@withLock null
            if (session.characterId.isBlank() || session.mainCallId.isBlank()) { setError("请先选择角色卡和主调用"); return@withLock null }
            val regenerationBase = recovery?.regenerationBase ?: if (regenerationMessageId != null) {
                val operation = MessageOperations.regenerationBase(MessageOperations.discardRecovery(session), regenerationMessageId)
                operation.session ?: run {
                    setError(operation.issues.firstOrNull()?.message ?: "当前消息不能重新生成")
                    return@withLock null
                }
            } else null
            val preparedCandidate = recovery?.initialSession ?: if (regenerationBase != null) {
                val operation = MessageOperations.prepareRegeneration(regenerationBase, regenerationMessageId!!)
                operation.session ?: run {
                    setError(operation.issues.firstOrNull()?.message ?: "当前消息不能重新生成")
                    return@withLock null
                }
            } else if (editedUserMessageId != null) {
                val operation = ConversationPaths.editUserAndSend(session, editedUserMessageId, input)
                operation.session ?: run {
                    setError(operation.issues.firstOrNull()?.message ?: "用户消息无法编辑发送")
                    return@withLock null
                }
            } else if (recovery == null) MessageOperations.discardRecovery(session) else session
            val replayed = if (preparedCandidate.executableWorld != null) withContext(Dispatchers.Default) {
                BranchReplay.rebuild(preparedCandidate, QuickJsPluginExecutor())
            } else MessageOperationResult(preparedCandidate)
            val replayedCandidate = replayed.session ?: run {
                setError(replayed.issues.firstOrNull()?.message ?: "旧环境重算失败；原分支仍保留")
                return@withLock null
            }
            var regenerationAdmission: RegenerationAdmission? = null
            val candidate = try {
                val resolved = if (recovery == null && regenerationBase == null)
                    CompletedWorlds.withGlobalMemoryForNewTurn(old, replayedCandidate)
                else if (recovery == null) CompletedWorlds.withExtensionPolicyForNewTurn(old, replayedCandidate)
                else replayedCandidate
                regenerationAdmission = if (regenerationBase == null) null
                    else if (recovery != null) MessageOperations.restoreRegenerationAdmission(recovery)
                    else MessageOperations.admitRegeneration(regenerationBase, resolved, old)
                resolved
            } catch (failure: IllegalArgumentException) {
                setError(failure.message ?: "全局记忆无法用于此分支")
                return@withLock null
            } catch (failure: IllegalStateException) {
                setError(failure.message ?: "全局记忆无法用于此分支")
                return@withLock null
            }
            val runInput = if (regenerationMessageId != null) candidate.messages.lastOrNull { it.role == Role.USER && !it.deleted }?.text.orEmpty()
            else if (appendUser) {
                val expanded = UserInputMacros.expand(input, CompletedWorlds.executionLibrary(old, candidate), candidate,
                    ExecutionSemantics.CURRENT)
                if (expanded.issues.any { it.error } || expanded.text == null) {
                    setError(expanded.issues.firstOrNull { it.error }?.message ?: "用户宏无法展开")
                    return@withLock null
                }
                expanded.text.orEmpty().trim()
            } else input
            if (appendUser && runInput.isBlank()) { setError("用户消息不能为空"); return@withLock null }
            val secrets = try {
                withContext(Dispatchers.IO) {
                    val requestedLibrary = recovery?.frozenResources ?: CompletedWorlds.executionLibrary(old, candidate)
                    WorkflowRequirements.requiredProfiles(requestedLibrary, recovery?.initialSession ?: candidate, actionDescriptors).filterNotNull()
                        .flatMap { NativeProviderCodec.requiredCredentialSlots(it) }.distinct().associateWith { credentials.get(GlobalMemory.credentialSlot(requestedLibrary, it)) }
                }
            } catch (_: Exception) {
                setError("API 密钥无法读取，请检查连接设置")
                return@withLock null
            }
            val executionLibrary = recovery?.frozenResources ?: CompletedWorlds.executionLibrary(old, candidate)
            val staged = if (appendUser) candidate.copy(messages = candidate.messages + ChatMessage(role = Role.USER, text = runInput, speakerName = executionLibrary.userName(candidate)), updatedAt = System.currentTimeMillis()) else candidate
            if (recovery == null && !appendUser && staged.messages.lastOrNull()?.let { !it.deleted && it.role == Role.USER && it.text == runInput } != true) {
                setError("当前会话没有可重试的消息"); return@withLock null
            }
            // Resolve every fallible execution dependency before publishing the path or busy state.
            // Otherwise a preparation exception can leave the chat permanently marked as running.
            val executionOwners = PluginParticipation.executionOwners(executionLibrary, staged)
            val next = if (regenerationMessageId != null && recovery == null)
                old.copy(sessions = old.sessions.map { if (it.id == session.id) MessageOperations.discardRecovery(it) else it })
                else if (staged.id == session.id) old.copy(sessions = old.sessions.map { if (it.id == session.id) staged else it })
                else old.copy(sessions = old.sessions + staged, selectedSessionId = staged.id)
            try { if (recovery == null && (appendUser || editedUserMessageId != null || regenerationMessageId != null || staged != session)) repository.save(next) } catch (_: Exception) { setError("消息保存失败"); return@withLock null }
            revision++
            if (recovery == null) _library.value = next
            _busy.value = true
            stopAfterCheckpoint = false
            _streaming.value = ""
            _error.value = null
            _state.value = _state.value.copy(busy = true, streaming = "", traces = emptyList(), error = null, issues = emptyList(), pendingOutputs = emptyList(), generationPreview = staged.takeIf { regenerationBase != null })
            if (regenerationMessageId == null) lastRetry = session.id to runInput
            val completion = CompletableDeferred<Unit>()
            runCompletion = completion
            runningExtensionIds = executionOwners
            PreparedTurn(next, staged, revision, secrets, runInput, regenerationBase,
                if (appendUser && recovery == null && regenerationBase == null && editedUserMessageId == null)
                    MessageOperations.discardRecovery(session) else null,
                session.id, recovery, recoveryDecision, completion, regenerationAdmission = regenerationAdmission)
        }
        if (prepared == null) { accepted?.complete(false); return@supervisorScope false }
        accepted?.complete(true)
        AppEventLog.record("主聊天", when {
            recoveryDecision != null -> "恢复执行开始"
            continueInterrupted -> "中断后继续开始"
            regenerationMessageId != null -> "重新生成开始"
            else -> "生成开始"
        })
        val (snapshot, session, runRevision, secrets, runInput, regenerationBase, beforeUserSession, sourceSessionId, recovery, decision) = prepared
        val completion = prepared.completion
        var launchedJob: Job? = null
        var recoveryAccepted = false
        try {
            val executionLibrary = recovery?.frozenResources ?: CompletedWorlds.executionLibrary(snapshot, session)
            val gateway = agentPromptRecorder.wrap(session.id, gatewayFactory(credentialSnapshot(secrets), session.id,
                ExecutionResources.views(executionLibrary).flatMap { it.connections }
                    .associate { it.id to it.maxConcurrentRequests }, ::recordUsage))
            val outputOrder = (executionLibrary.mainCalls.firstOrNull { it.id == session.mainCallId }?.workflows.orEmpty() +
                executionLibrary.loadedCallGroups(session).flatMap { it.workflows })
                .flatMap { flow -> flow.nodes.map { flow.id to it.id } }.withIndex().associate { it.value to it.index }
            val publisher = AcceptedTurnPublisher(repository, mutex, _library, _state, _streaming,
                session, sourceSessionId, regenerationBase, beforeUserSession, recovery?.id, prepared.regenerationAdmission)
            val job = async(start = CoroutineStart.LAZY) {
                val onUpdate: suspend (RunUpdate) -> Unit = onUpdate@{ update ->
                    update.progress?.let {
                        publisher.persist(it)
                        if (stopAfterCheckpoint) {
                            stopAfterCheckpoint = false
                            runJob?.cancel()
                        }
                        // Only historical recovery may still replace BODY through a retired display template.
                        val main = executionLibrary.mainCalls.firstOrNull { main -> main.id == session.mainCallId }
                        if (regenerationBase == null && (!ExecutionSemantics(recovery?.executionVersion ?: session.executableWorld?.executionVersion ?: ExecutionSemantics.CURRENT).legacyTiles || main?.bodySourceTileId == null)) {
                            optionsController.bodyCommitted(session.id, it)
                            illustrationController.bodyCommitted(session.id, it)
                        }
                    }
                    if (runRevision != revision) return@onUpdate
                    if (update.replace) {
                        val pending = _state.value.pendingOutputs.filterNot { it.workflowId == update.workflowId && it.nodeId == update.nodeId }
                        _streaming.value = pending.filter { it.outputSlot == OutputSlot.BODY }.joinToString("\n\n") { it.text }
                        _state.value = _state.value.copy(pendingOutputs = pending, streaming = _streaming.value)
                    }
                    if (update.visible && (update.text.isNotEmpty() || update.reasoningSnapshot != null)) {
                        val pending = _state.value.pendingOutputs.toMutableList()
                        val index = pending.indexOfFirst { it.workflowId == update.workflowId && it.nodeId == update.nodeId }
                        if (index < 0) pending += PendingOutput(update.workflowId, update.nodeId, update.text, update.outputSlot, update.turnId, update.reasoningSnapshot)
                        else pending[index] = pending[index].copy(text = pending[index].text + update.text,
                            reasoningSnapshot = update.reasoningSnapshot ?: pending[index].reasoningSnapshot)
                        val ordered = pending.sortedBy { outputOrder[it.workflowId to it.nodeId] ?: Int.MAX_VALUE }
                        _streaming.value = ordered.filter { it.outputSlot == OutputSlot.BODY }.joinToString("\n\n") { it.text }
                        _state.value = _state.value.copy(streaming = _streaming.value, pendingOutputs = ordered)
                    }
                    update.trace?.let { trace ->
                        _state.value = _state.value.copy(traces = _state.value.traces + trace)
                        val detail = trace.detail.takeIf { trace.status == "error" && it.length <= 160 &&
                            '\n' !in it && !it.trimStart().startsWith("{") && !it.trimStart().startsWith("[") }
                            ?.let { " · $it" }.orEmpty()
                        AppEventLog.record("主聊天", "工作流 ${trace.workflowId} / 节点 ${trace.nodeId} ${trace.title}：${trace.status}$detail",
                            if (trace.status == "error") AppLogLevel.WARNING else AppLogLevel.INFO)
                    }
                }
                if (recovery != null && decision != null)
                    WorkflowEngine(actions).resume(snapshot, _library.value.sessions.first { it.id == sourceSessionId }, gateway, recovery, decision, onUpdate)
                else if (recovery != null)
                    WorkflowEngine(actions).continueInterrupted(snapshot, _library.value.sessions.first { it.id == sourceSessionId }, gateway, recovery, onUpdate)
                else WorkflowEngine(actions).run(snapshot, session, runInput, gateway, onUpdate)
            }
            launchedJob = job
            runJob = job
            if (runRevision != revision && !stopAfterCheckpoint) job.cancel() else job.start()
            val result = job.await()
            AppEventLog.record("主聊天", "生成结束：${result.status.name}",
                if (result.status == RunStatus.FAILED) AppLogLevel.WARNING else AppLogLevel.INFO)
            publisher.persist(result)
            optionsController.bodyCommitted(session.id, result)
            illustrationController.bodyCommitted(session.id, result)
            val resultRecovery = result.recovery
            recoveryAccepted = result.status == RunStatus.SUCCESS ||
                (decision != null && resultRecovery != null && resultRecovery.failures.none { it.nodeKey == decision.nodeKey })
            mutex.withLock {
                if (revision != runRevision || _library.value.selectedSessionId !in setOf(sourceSessionId, session.id)) return@withLock
                if (result.status != RunStatus.SUCCESS) {
                    _state.value = _state.value.copy(traces = result.traces)
                    setError(result.recovery?.failures?.firstOrNull()?.reason ?: "工作流执行失败；已接受的结果已保留")
                    return@withLock
                }
                val current = _library.value.sessions.firstOrNull { it.id == session.id } ?: return@withLock
                lastRetry = null
                _state.value = _state.value.copy(traces = result.traces)
            }
        } catch (_: CancellationException) {
            // Accepted node checkpoints have already been persisted; provisional candidates are discarded.
            AppEventLog.record("主聊天", "生成已停止", AppLogLevel.WARNING)
        } catch (failure: GatewayFailure) {
            setError(failure.message ?: "模型服务请求失败")
        } catch (_: Exception) {
            setError("生成失败。检查模型设置或网络后可重试")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (runRevision == revision) {
                        _busy.value = false
                        _streaming.value = ""
                        _state.value = _state.value.copy(busy = false, streaming = "", pendingOutputs = emptyList(), generationPreview = null)
                    }
                    if (runJob === launchedJob) runJob = null
                    stopAfterCheckpoint = false
                }
                completion.complete(Unit)
                if (runCompletion === completion) {
                    runCompletion = null
                    runningExtensionIds = emptySet()
                }
            }
        }
        if (decision == null) true else recoveryAccepted
    }

    fun stop() {
        if (_busy.value) {
            AppEventLog.record("主聊天", "请求停止生成", AppLogLevel.WARNING)
            val needsCheckpoint = _library.value.sessions.firstOrNull { it.id == _library.value.selectedSessionId }
                ?.pendingRun == null
            stopAfterCheckpoint = needsCheckpoint
            revision++
            if (!needsCheckpoint) runJob?.cancel()
            _busy.value = false
            _streaming.value = ""
            _state.value = _state.value.copy(busy = false, streaming = "", pendingOutputs = emptyList(), generationPreview = null)
        }
    }

    private suspend fun stopAndJoin() {
        val completion = runCompletion
        stop()
        completion?.await()
    }

    fun getApiKey(connectionId: String): String = try { credentials.get(connectionId) } catch (_: Exception) { "" }
    fun getCredential(slot: CredentialSlot): String = try { credentials.get(slot) } catch (_: Exception) { "" }

    /** Read-only metadata for the native permission panel and configuration discovery tools. */
    fun pluginAccessStatus(extension: ConfigurationExtension): PluginAccessStatus = pluginAccess.status(extension)

    fun grantPluginAccess(extension: ConfigurationExtension): List<Issue> = pluginAccessChange(extension) {
        pluginAccess.grant(extension)
    }

    fun revokePluginAccess(pluginId: String): List<Issue> = try {
        pluginAccess.revoke(pluginId)
        emptyList()
    } catch (_: Exception) {
        listOf(Issue("plugin.access", "撤销权限未能保存，请重试。"))
    }

    fun setPluginCredential(extension: ConfigurationExtension, alias: String, secret: String): List<Issue> =
        pluginAccessChange(extension) { pluginAccess.setCredential(extension, alias, secret) }

    /** These operations are intentionally not exposed by ConfigurationToolGateway. */
    private fun pluginAccessChange(extension: ConfigurationExtension, change: () -> Unit): List<Issue> {
        if (!storageReady || _library.value.extensions.firstOrNull { it.id == extension.id } != extension)
            return listOf(Issue("plugin.access", "扩展已改变或尚未载入，请重新打开设置。"))
        return try {
            change()
            emptyList()
        } catch (_: Exception) {
            listOf(Issue("plugin.access", "操作未能保存，请检查权限声明、凭据格式后重试。"))
        }
    }

    private fun credentialSnapshot(values: Map<CredentialSlot, String>): CredentialStore = object : CredentialStore {
        override fun get(profileId: String): String = get(CredentialSlot(profileId))
        override fun get(slot: CredentialSlot): String = values[slot].orEmpty()
        override fun put(profileId: String, secret: String) = error("运行凭据快照只读")
        override fun remove(profileId: String) = error("运行凭据快照只读")
    }
    suspend fun setApiKey(connectionId: String, key: String): List<Issue> = mutex.withLock {
        if (!storageReady || library.value.connections.none { it.id == connectionId }) {
            val issue = Issue("connection.$connectionId", "API 连接不存在或资源库尚未就绪")
            _state.value = _state.value.copy(issues = listOf(issue))
            return@withLock listOf(issue)
        }
        try { credentials.put(connectionId, key); emptyList() }
        catch (_: Exception) {
            val issue = Issue("connection.$connectionId.apiKey", "密钥保存失败")
            _state.value = _state.value.copy(issues = listOf(issue))
            setError(issue.message)
            listOf(issue)
        }
    }

    suspend fun export(): String = mutex.withLock {
        withContext(Dispatchers.Default) { LibraryFormat.encode(_library.value) }
    }
    /** The file picker prepares a fixed plan; no resource or conversation changes here. */
    suspend fun previewConfigurationImport(jsonText: String): List<Issue> = prepareImport(jsonText, false).issues

    suspend fun previewResourceImport(fileName: String, source: () -> InputStream): List<Issue> =
        prepareImport(false) { checkCurrent ->
            withContext(Dispatchers.IO) {
                val readContext = kotlin.coroutines.coroutineContext
                ResourceArchiveImport.read(source(), File(applicationContext.cacheDir, "resource-import"), fileName,
                    checkCancelled = { readContext.ensureActive(); checkCurrent() },
                    prepareAvatar = { bytes, _ -> AvatarImportPreparation.encode(bytes) })
            }
        }.issues

    private suspend fun prepareImport(jsonText: String, selectImportedSession: Boolean): ImportPreparation =
        prepareImport(selectImportedSession) { checkCurrent ->
            withContext(Dispatchers.Default) {
                checkCurrent()
                val document = LibraryFormat.json.parseToJsonElement(jsonText) as? JsonObject ?: error("资源文件须为对象")
                ResourceArchiveResult(document, emptyList(), 0, 0L)
            }
        }

    /** File IO stays outside admission; only the latest completed read can publish a fixed preview. */
    private suspend fun prepareImport(selectImportedSession: Boolean,
                                      read: suspend (() -> Unit) -> ResourceArchiveResult): ImportPreparation {
        val generation = mutex.withLock {
            if (!storageReady) return ImportPreparation(null, listOf(Issue("import", "资源库尚未就绪")))
            importDraft = null
            _configurationImport.value = null
            importGeneration.incrementAndGet()
        }
        val document = try {
            read { if (importGeneration.get() != generation) throw CancellationException("Import replaced or cancelled") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ResourceArchiveResult(null, listOf(Issue("import", "资源文件无法读取或格式不受支持")), 0, 0L) }
        return mutex.withLock {
            if (importGeneration.get() != generation) return@withLock ImportPreparation(null, listOf(Issue("import", "导入读取已取消或被替换")))
            val id = newId()
            fun failed(issues: List<Issue>) = ConfigurationImportPreparation(null,
                ConfigurationImportPreview(id, "导入预览", emptyList(), emptyList(), issues, undoable = false), issues)
            val prepared = try {
                withContext(Dispatchers.Default) {
                    document.document?.let { ConfigurationImportPreparer.prepare(it, _library.value, id, selectImportedSession, document.issues) }
                        ?: failed(document.issues.ifEmpty { listOf(Issue("import", "资源文件未产生可导入内容")) })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed(listOf(Issue("import", "资源字段无效或格式不受支持"))) }
            if (importGeneration.get() != generation) return@withLock ImportPreparation(null, listOf(Issue("import", "导入预览已取消或被替换")))
            importDraft = prepared.draft
            _configurationImport.value = prepared.preview
            ImportPreparation(id, prepared.issues)
        }
    }

    fun dismissConfigurationImport() {
        importGeneration.incrementAndGet()
        importDraft = null
        _configurationImport.value = null
    }

    suspend fun applyConfigurationImport(previewId: String): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("import", "资源库尚未就绪"))
        if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
            return@withLock listOf(Issue("import", "请先等待当前操作完成，再应用配置"))
        val draft = importDraft?.takeIf { it.id == previewId }
            ?: return@withLock listOf(Issue("import", "预览已取消或无效，请重新选择文件"))
        val current = _library.value
        val result = withContext(Dispatchers.Default) {
            ConfigurationImportPreparer.apply(draft, current)
        }
        val changed = result.library ?: return@withLock result.issues
        val receipt = result.receipt
        val next = if (receipt == null) changed else changed.copy(
            configurationEdits = ConfigurationReceiptHistory.append(current.configurationEdits, receipt))
        val issues = saveConfiguration(next, result.issues + draft.warnings)
        if (issues.none { it.error }) dismissConfigurationImport()
        issues
    }

    suspend fun undoConfigurationImport(receiptId: String): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("import.undo", "资源库尚未就绪"))
        if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
            return@withLock listOf(Issue("import.undo", "请先等待当前操作完成，再撤回配置"))
        val current = _library.value
        val receipt = current.configurationEdits.firstOrNull { it.id == receiptId }
            ?: return@withLock listOf(Issue("import.undo", "撤回记录已不存在"))
        val result = withContext(Dispatchers.Default) {
            ConfigurationCommands.undo(receipt, current, ConfigurationAdmission::validateNative)
        }
        val changed = result.library ?: return@withLock result.issues
        saveConfiguration(changed.copy(configurationEdits = current.configurationEdits.filterNot { it.id == receiptId }), result.issues)
    }

    private fun reportConfigurationIssues(issues: List<Issue>): List<Issue> {
        if (issues.any { it.error }) AppEventLog.record("配置", "配置校验未通过", AppLogLevel.ERROR)
        _state.value = _state.value.copy(issues = issues)
        return issues
    }

    /** Caller holds the admission mutex; configuration and its undo receipt share one transaction. */
    private suspend fun saveConfiguration(next: Library, issues: List<Issue>): List<Issue> {
        return try {
            withContext(NonCancellable) {
                val prepared = LibraryEditPreparation.prepareRecoveryEdits(_library.value, next)
                repository.save(prepared)
                revision++
                _library.value = prepared
                _state.value = _state.value.copy(issues = issues, error = null)
                _error.value = null
                AppEventLog.record("配置", "配置已应用")
            }
            // Keep encrypted connection credentials so a configuration-only undo can restore access.
            issues
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            AppEventLog.record("配置", "配置应用失败", AppLogLevel.ERROR)
            listOf(Issue("import", "保存失败；原数据仍保留"))
        }
    }

    /** Debug/automation ingestion uses the same validated plan, without opening a UI sheet. */
    suspend fun importBundle(jsonText: String, selectImportedSession: Boolean = false): List<Issue> {
        val prepared = prepareImport(jsonText, selectImportedSession)
        if (prepared.issues.any { it.error }) return prepared.issues
        val id = prepared.id ?: return listOf(Issue("import", "导入预览不存在"))
        return applyConfigurationImport(id)
    }

    private fun setError(message: String) {
        AppEventLog.record("主聊天", message, AppLogLevel.ERROR)
        _error.value = message
        _state.value = _state.value.copy(error = message)
    }

    override fun onCleared() { stop(); super.onCleared() }

    companion object {
        private fun defaultPluginActions(context: Context): ActionRegistry {
            val access = PluginAccessStore(context.applicationContext)
            return ActionRegistry.defaults(QuickJsPluginExecutor(hostRuntimeFactory = { captured ->
                PluginHostRuntime(captured, access)
            }))
        }

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppController(context.applicationContext) as T
        }
    }
}
