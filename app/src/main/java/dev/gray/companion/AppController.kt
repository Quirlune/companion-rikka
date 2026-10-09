package dev.gray.companion

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.gray.companion.core.*
import dev.gray.companion.data.GatewayFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Deferred
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
import java.util.Locale
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
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
    val titleError: String? = null,
)

class AppController internal constructor(
    context: Context,
    private val actions: ActionRegistry,
    dependencies: ControllerDependencies,
) : ViewModel() {
    constructor(context: Context, actions: ActionRegistry = ActionRegistry.defaults()) :
        this(context, actions, ControllerDependencies.android(context))

    val actionDescriptors: List<ActionDescriptor> get() = actions.descriptors()
    private val repository = dependencies.library
    private val usageRepository = dependencies.usage
    private val gatewayFactory = dependencies.gateway
    private val usageMutex = Mutex()
    private var usageFrom = Long.MAX_VALUE
    private val _usage = MutableStateFlow<List<ModelUsageRecord>>(emptyList())
    val usage: StateFlow<List<ModelUsageRecord>> = _usage.asStateFlow()
    private val _usageError = MutableStateFlow<String?>(null)
    val usageError: StateFlow<String?> = _usageError.asStateFlow()
    private val credentials = dependencies.credentials
    private val applicationContext = context.applicationContext
    private data class PreparedTurn(
        val library: Library,
        val session: Session,
        val revision: Long,
        val secrets: Map<CredentialSlot, String>,
        val input: String,
        val regenerationBase: Session?,
        val sourceSessionId: String,
        val recovery: RunRecovery? = null,
        val decision: RecoveryDecision? = null,
        val completion: CompletableDeferred<Unit>,
    )
    private val mutex = Mutex()
    private val titleJobs = mutableMapOf<String, Deferred<List<Issue>>>()
    @Volatile private var runJob: Job? = null
    @Volatile private var runCompletion: CompletableDeferred<Unit>? = null
    @Volatile private var revision = 0L
    private var lastRetry: Pair<String, String>? = null // session ID to input
    @Volatile private var storageReady = false
    private var editingCount = 0
    private var importDraft: ConfigurationImportDraft? = null
    private val importGeneration = AtomicLong()
    private data class ImportPreparation(val id: String?, val issues: List<Issue>)
    private val _configurationImport = MutableStateFlow<ConfigurationImportPreview?>(null)
    val configurationImport: StateFlow<ConfigurationImportPreview?> = _configurationImport.asStateFlow()

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

    init {
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                var initial = CompanionProviderAdapter.withBuiltInProviders(
                    LibraryFormat.upgrade(ModelCatalog.normalize(if (loaded == Library()) LibraryDefaults.create() else loaded).withStandardCharacterFields()))
                val issues = LibraryValidator.validate(initial) + NativeProviderCodec.validate(initial)
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
                        current.historyMemory != HistoryMemoryState() || current.currentOnlyMacros.isNotEmpty())) {
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
                    _state.value = _state.value.copy(loading = false, issues = issues)
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

    /** Resource plans and source-field edits use the same admission and publication path. */
    suspend fun updateResources(command: (Library) -> ExtensionEditResult): List<Issue> = updateConfiguration(command)

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
                return@withLock listOf(Issue("library", "编辑操作失败"))
            }
            val edited = proposal.library?.let { candidate ->
                proposal.receipt?.let { receipt -> candidate.copy(configurationEdits = before.configurationEdits + receipt) }
                    ?: candidate
            }
            if (edited == null || proposal.issues.any { it.error }) {
                val failures = proposal.issues.ifEmpty { listOf(Issue("library", "配置修改未应用")) }
                _state.value = _state.value.copy(issues = failures)
                return@withLock failures
            }
            if (LibraryEditPreparation.changesFrozenSession(before, edited))
                return@withLock listOf(Issue("recovery", "这一轮仍有断点；请先恢复或跳过，再修改会话环境"))
            val rawIssues = (LibraryValidator.validate(edited) + NativeProviderCodec.validate(edited)).filter { it.error }
            if (rawIssues.isNotEmpty()) {
                _state.value = _state.value.copy(issues = rawIssues)
                return@withLock rawIssues
            }
            val next = LibraryEditPreparation.normalize(before, edited)
            val issues = LibraryValidator.validate(next).filter { it.error } + LibraryEditPreparation.removalIssues(before, next)
            if (issues.isNotEmpty()) {
                _state.value = _state.value.copy(issues = issues)
                return@withLock issues
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
            titleJobs.remove(sessionId)?.cancel()
            it.copy(title = title, titlePending = false, updatedAt = System.currentTimeMillis())
        }

    suspend fun toggleSessionPin(sessionId: String): List<Issue> =
        updateSessionMetadata(sessionId) { it.copy(isPinned = !it.isPinned) }

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

    suspend fun regenerateTitle(sessionId: String): List<Issue> = generateTitle(sessionId, force = true)

    private suspend fun generateTitle(sessionId: String, force: Boolean): List<Issue> {
        val task = mutex.withLock {
            titleJobs[sessionId]?.let { return@withLock it }
            val snapshot = _library.value
            val session = snapshot.sessions.firstOrNull { it.id == sessionId }
                ?: return listOf(Issue("title", "会话不存在"))
            if (!storageReady) return listOf(Issue("title", "资源库尚未就绪"))
            if (!force && (!session.titlePending || !snapshot.titleConfig.enabled)) return emptyList()
            val requestLibrary = if (force) snapshot.copy(titleConfig = snapshot.titleConfig.copy(enabled = true)) else snapshot
            val request = ChatTitles.request(requestLibrary, session, Locale.getDefault().displayName)
                ?: return listOf(Issue("title", "请先配置标题模型，并保留至少一条聊天消息"))
            val secrets = try { withContext(Dispatchers.IO) {
                NativeProviderCodec.requiredCredentialSlots(request.profile).associateWith { credentials.get(it) }
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return listOf(Issue("title", "标题模型的 API 密钥无法读取")) }
            try { NativeProviderCodec.request(request.profile) { secrets[it].orEmpty() } }
            catch (failure: IllegalArgumentException) { return listOf(Issue("title", failure.message ?: "标题模型连接配置无效")) }
            val source = titleSource(session)
            _state.value = _state.value.copy(titleError = null)
            viewModelScope.async(start = CoroutineStart.LAZY) {
                try {
                    val gateway = gatewayFactory(credentialSnapshot(secrets), sessionId,
                        snapshot.connections.associate { it.id to it.maxConcurrentRequests }, ::recordUsage)
                    val titleText = gateway.generate(request.profile, request.messages) {}
                    val title = ReasoningTextParser.parse(titleText, snapshot.reasoningParsing).text.trim()
                    if (title.isBlank()) return@async listOf(Issue("title", "模型没有返回标题"))
                    mutex.withLock {
                        val current = _library.value
                        val latest = current.sessions.firstOrNull { it.id == sessionId } ?: return@withLock emptyList()
                        if (latest.title != session.title || latest.titlePending != session.titlePending ||
                            titleSource(latest) != source || current.titleConfig != snapshot.titleConfig) return@withLock emptyList()
                        val changed = latest.copy(title = title, titlePending = false)
                        val next = current.copy(sessions = current.sessions.map { if (it.id == sessionId) changed else it })
                        repository.save(next)
                        _library.value = next
                        emptyList()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: GatewayFailure) { listOf(Issue("title", failure.message ?: "标题生成失败")) }
                catch (_: Exception) { listOf(Issue("title", "标题生成或保存失败；原标题仍保留")) }
            }.also { task ->
                titleJobs[sessionId] = task
                task.invokeOnCompletion {
                    viewModelScope.launch { mutex.withLock { if (titleJobs[sessionId] === task) titleJobs.remove(sessionId) } }
                }
            }
        }
        task.start()
        return task.await()
    }

    private fun titleSource(session: Session): List<Triple<String, String?, String>> = session.messages.asReversed().asSequence()
        .filter { !it.deleted && it.outputSlot == OutputSlot.BODY }.take(4)
        .map { Triple(it.id, it.activeTextRevisionId, it.text) }
        .toList().asReversed()

    private fun queueAutomaticTitle(sessionId: String) {
        viewModelScope.launch {
            val issues = try { generateTitle(sessionId, force = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { listOf(Issue("title", "标题模型的 API 密钥无法读取")) }
            issues.firstOrNull()?.let { _state.value = _state.value.copy(titleError = it.message) }
        }
    }

    suspend fun editMessage(sessionId: String, messageId: String, text: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.editText(it, messageId, text) }

    suspend fun selectTextRevision(sessionId: String, messageId: String, revisionId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.selectTextRevision(it, messageId, revisionId) }

    suspend fun selectOpeningRevision(sessionId: String, messageId: String, revisionId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.selectOpeningRevision(it, messageId, revisionId) }

    suspend fun deleteMessage(sessionId: String, messageId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.deleteMessage(it, messageId) }

    suspend fun appendUserOnly(sessionId: String, text: String): List<Issue> =
        updateMessage(sessionId) { session ->
            if (text.isBlank()) MessageOperationResult(issues = listOf(Issue("message", "用户消息不能为空")))
            else MessageOperationResult(session.copy(
                messages = session.messages + ChatMessage(role = Role.USER, text = text, speakerName = _library.value.userName(session)),
                updatedAt = System.currentTimeMillis(),
            ))
        }

    suspend fun selectGenerationVariant(sessionId: String, turnId: String, variantId: String, messageId: String? = null, revisionId: String? = null): List<Issue> =
        updateMessage(sessionId) { session ->
            val latestTurnId = session.messages.lastOrNull { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }?.id
            val selected = if (latestTurnId == turnId) MessageOperations.applyLatestVariant(session, turnId, variantId)
                else MessageOperations.forkAtVariant(session, turnId, variantId)
            val changed = selected.session
            if (changed != null && messageId != null && revisionId != null)
                MessageOperations.selectTextRevision(changed, messageId, revisionId)
            else selected
        }

    suspend fun deleteGenerationVariant(sessionId: String, turnId: String, variantId: String): List<Issue> =
        updateMessage(sessionId) { MessageOperations.deleteGenerationVariant(it, turnId, variantId) }

    suspend fun forkMessage(sessionId: String, messageId: String): List<Issue> =
        updateMessage(sessionId, fork = true) { MessageOperations.forkAt(it, messageId) }

    private suspend fun updateMessage(sessionId: String, fork: Boolean = false, operation: (Session) -> MessageOperationResult): List<Issue> = mutex.withLock {
        if (!storageReady) return@withLock listOf(Issue("session", "资源库尚未就绪"))
        if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0)
            return@withLock listOf(Issue("session", "请先等待当前操作完成再修改消息"))
        val current = _library.value
        if (current.selectedSessionId != sessionId) return@withLock listOf(Issue("session", "当前会话已改变"))
        val session = current.sessions.firstOrNull { it.id == sessionId }
            ?: return@withLock listOf(Issue("session", "会话不存在"))
        if (session.pendingRun != null) return@withLock listOf(Issue("recovery", "这一轮仍有断点；请先恢复或跳过，再修改消息"))
        val result = operation(session)
        val changed = result.session ?: return@withLock result.issues
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

    suspend fun send(text: String, expectedSessionId: String? = null): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        val targetSessionId = expectedSessionId ?: library.value.selectedSessionId
        viewModelScope.launch { run(text, appendUser = true, accepted = accepted, expectedSessionId = targetSessionId) }
        return accepted.await()
    }
    suspend fun retry() {
        val current = library.value.sessions.firstOrNull { it.id == library.value.selectedSessionId }
        current?.pendingRun?.failures?.firstOrNull()?.let {
            recover(current.id, RecoveryDecision(RecoveryDecisionKind.RETRY, it.nodeKey))
            return
        }
        val (sessionId, input) = lastRetry ?: return
        if (sessionId == library.value.selectedSessionId) run(input, appendUser = false, expectedSessionId = sessionId)
    }

    suspend fun recover(sessionId: String, decision: RecoveryDecision): Boolean {
        val pending = library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun ?: return false
        return viewModelScope.async {
            run(pending.input, appendUser = false, expectedSessionId = sessionId, recoveryDecision = decision)
        }.await()
    }

    suspend fun finishRecovery(sessionId: String, runId: String): Boolean {
        val issues = updateSessionMetadata(sessionId) { session ->
            if (_busy.value || runCompletion?.isCompleted == false || editingCount > 0) return@updateSessionMetadata session
            val pending = session.pendingRun?.takeIf { it.id == runId } ?: return@updateSessionMetadata session
            session.copy(pendingRun = null, unavailableResultKeys = if (pending.regenerating)
                session.unavailableResultKeys else pending.unavailableKeys)
        }
        val finished = issues.none { it.error } && library.value.sessions.firstOrNull { it.id == sessionId }?.pendingRun == null
        if (finished && lastRetry?.first == sessionId) lastRetry = null
        return finished
    }

    fun recoveryNode(sessionId: String, runId: String, nodeKey: String): WorkflowNode? {
        val current = library.value
        val pending = current.sessions.firstOrNull { it.id == sessionId }?.pendingRun?.takeIf { it.id == runId } ?: return null
        val failure = pending.failures.firstOrNull { it.nodeKey == nodeKey } ?: return null
        return (current.mainCalls.flatMap { it.configuredWorkflows() } + current.groups.flatMap { it.workflows })
            .filter { it.id == failure.workflowId }.flatMap { it.nodes }.singleOrNull { it.id == failure.nodeId }
    }

    suspend fun saveRecoveryPrompt(sessionId: String, runId: String, nodeKey: String, blocks: List<PromptBlock>): List<Issue> {
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

    suspend fun regenerateMessage(sessionId: String, messageId: String): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        viewModelScope.launch {
            run("", appendUser = false, accepted = accepted, expectedSessionId = sessionId, regenerationMessageId = messageId)
        }
        return accepted.await()
    }

    private suspend fun run(input: String, appendUser: Boolean, accepted: CompletableDeferred<Boolean>? = null, expectedSessionId: String? = null, regenerationMessageId: String? = null, recoveryDecision: RecoveryDecision? = null): Boolean = supervisorScope {
        if (regenerationMessageId == null && input.isBlank()) { accepted?.complete(false); return@supervisorScope false }
        val prepared = mutex.withLock {
            if (!storageReady) { setError("资源库尚未就绪"); return@withLock null }
            if (editingCount > 0) { setError("配置正在保存，请稍后再试"); return@withLock null }
            if (_busy.value || runCompletion?.isCompleted == false) { setError("请先等待当前生成停止"); return@withLock null }
            val old = _library.value
            if (old.selectedSessionId != expectedSessionId) return@withLock null
            val session = old.sessions.firstOrNull { it.id == old.selectedSessionId }
            if (session == null) { setError("请先选择会话"); return@withLock null }
            if (session.pendingRun != null && recoveryDecision == null) {
                setError("这一轮仍有断点；请先重试或跳过失败路径")
                return@withLock null
            }
            val recovery = if (recoveryDecision != null) session.pendingRun ?: return@withLock null else null
            if (session.characterId.isBlank() || session.mainCallId.isBlank()) { setError("请先选择角色卡和主调用"); return@withLock null }
            val regenerationBase = recovery?.regenerationBase ?: if (regenerationMessageId != null) {
                val operation = MessageOperations.regenerationBase(session, regenerationMessageId)
                operation.session ?: run {
                    setError(operation.issues.firstOrNull()?.message ?: "当前消息不能重新生成")
                    return@withLock null
                }
            } else null
            val candidate = recovery?.initialSession ?: if (regenerationBase != null) {
                val operation = MessageOperations.prepareRegeneration(regenerationBase, regenerationMessageId!!)
                operation.session ?: run {
                    setError(operation.issues.firstOrNull()?.message ?: "当前消息不能重新生成")
                    return@withLock null
                }
            } else session
            val runInput = if (regenerationMessageId != null) candidate.messages.lastOrNull { it.role == Role.USER && !it.deleted }?.text.orEmpty() else input
            if (runInput.isBlank()) { setError("用户消息不能为空"); return@withLock null }
            val secrets = try {
                withContext(Dispatchers.IO) {
                    val requestedLibrary = recovery?.frozenResources ?: old
                    WorkflowRequirements.requiredProfiles(requestedLibrary, recovery?.initialSession ?: session, actionDescriptors).filterNotNull()
                        .flatMap { NativeProviderCodec.requiredCredentialSlots(it) }.distinct().associateWith { credentials.get(it) }
                }
            } catch (_: Exception) {
                setError("API 密钥无法读取，请检查连接设置")
                return@withLock null
            }
            val staged = if (appendUser) candidate.copy(messages = candidate.messages + ChatMessage(role = Role.USER, text = runInput, speakerName = old.userName(candidate)), updatedAt = System.currentTimeMillis()) else candidate
            if (recovery == null && !appendUser && staged.messages.lastOrNull()?.let { !it.deleted && it.role == Role.USER && it.text == runInput } != true) {
                setError("当前会话没有可重试的消息"); return@withLock null
            }
            val next = if (staged.id == session.id) old.copy(sessions = old.sessions.map { if (it.id == session.id) staged else it })
                else old.copy(sessions = old.sessions + staged, selectedSessionId = staged.id)
            try { if (appendUser) repository.save(next) } catch (_: Exception) { setError("消息保存失败"); return@withLock null }
            revision++
            if (regenerationMessageId == null && recovery == null) _library.value = next
            _busy.value = true
            _streaming.value = ""
            _error.value = null
            _state.value = _state.value.copy(busy = true, streaming = "", traces = emptyList(), error = null, issues = emptyList(), pendingOutputs = emptyList(), generationPreview = staged.takeIf { regenerationBase != null })
            if (regenerationMessageId == null) lastRetry = session.id to runInput
            val completion = CompletableDeferred<Unit>()
            runCompletion = completion
            PreparedTurn(next, staged, revision, secrets, runInput, regenerationBase, session.id, recovery, recoveryDecision, completion)
        }
        if (prepared == null) { accepted?.complete(false); return@supervisorScope false }
        accepted?.complete(true)
        val (snapshot, session, runRevision, secrets, runInput, regenerationBase, sourceSessionId, recovery, decision) = prepared
        val completion = prepared.completion
        var launchedJob: Job? = null
        var titleSessionId: String? = null
        var recoveryAccepted = false
        try {
            val gateway = gatewayFactory(credentialSnapshot(secrets), session.id,
                snapshot.connections.associate { it.id to it.maxConcurrentRequests }, ::recordUsage)
            val outputOrder = (snapshot.mainCalls.firstOrNull { it.id == session.mainCallId }?.workflows.orEmpty() +
                snapshot.loadedCallGroups(session).flatMap { it.workflows })
                .flatMap { flow -> flow.nodes.map { flow.id to it.id } }.withIndex().associate { it.value to it.index }
            val publisher = AcceptedTurnPublisher(repository, mutex, _library, _state, _streaming,
                session, sourceSessionId, regenerationBase, recovery?.id)
            val job = async(start = CoroutineStart.LAZY) {
                val onUpdate: suspend (RunUpdate) -> Unit = onUpdate@{ update ->
                    update.progress?.let { publisher.persist(it) }
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
                    update.trace?.let { trace -> _state.value = _state.value.copy(traces = _state.value.traces + trace) }
                }
                if (recovery != null && decision != null)
                    WorkflowEngine(actions).resume(snapshot, _library.value.sessions.first { it.id == sourceSessionId }, gateway, recovery, decision, onUpdate)
                else WorkflowEngine(actions).run(snapshot, session, runInput, gateway, onUpdate)
            }
            launchedJob = job
            runJob = job
            if (runRevision != revision) job.cancel() else job.start()
            val result = job.await()
            publisher.persist(result)
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
                if (current.titlePending && _library.value.titleConfig.enabled) titleSessionId = current.id
            }
        } catch (_: CancellationException) {
            // Accepted node checkpoints have already been persisted; provisional candidates are discarded.
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
                }
                completion.complete(Unit)
                if (runCompletion === completion) runCompletion = null
            }
        }
        titleSessionId?.let(::queueAutomaticTitle)
        if (decision == null) true else recoveryAccepted
    }

    fun stop() {
        if (_busy.value) {
            revision++
            runJob?.cancel()
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
            configurationEdits = (current.configurationEdits + receipt).takeLast(20))
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
        val result = withContext(Dispatchers.Default) { ConfigurationCommands.undo(receipt, current) }
        val changed = result.library ?: return@withLock result.issues
        saveConfiguration(changed.copy(configurationEdits = current.configurationEdits.filterNot { it.id == receiptId }), result.issues)
    }

    /** Caller holds the admission mutex; configuration and its undo receipt share one transaction. */
    private suspend fun saveConfiguration(next: Library, issues: List<Issue>): List<Issue> {
        return try {
            withContext(NonCancellable) {
                repository.save(next)
                revision++
                _library.value = next
                _state.value = _state.value.copy(issues = issues, error = null)
                _error.value = null
            }
            // Keep encrypted connection credentials so a configuration-only undo can restore access.
            issues
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { listOf(Issue("import", "保存失败；原数据仍保留")) }
    }

    /** Debug/automation ingestion uses the same validated plan, without opening a UI sheet. */
    suspend fun importBundle(jsonText: String, selectImportedSession: Boolean = false): List<Issue> {
        val prepared = prepareImport(jsonText, selectImportedSession)
        if (prepared.issues.any { it.error }) return prepared.issues
        val id = prepared.id ?: return listOf(Issue("import", "导入预览不存在"))
        return applyConfigurationImport(id)
    }

    private fun setError(message: String) {
        _error.value = message
        _state.value = _state.value.copy(error = message)
    }

    override fun onCleared() { stop(); super.onCleared() }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppController(context.applicationContext) as T
        }
    }
}
