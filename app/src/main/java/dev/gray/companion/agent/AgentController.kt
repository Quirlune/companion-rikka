package dev.gray.companion.agent

import android.content.Context
import android.util.AtomicFile
import dev.gray.companion.AppController
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel
import dev.gray.companion.ConfigurationToolGateway
import dev.gray.companion.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Process-scoped task: navigation and the main conversation never own this Job. */
class AgentController(context: Context, private val scope: CoroutineScope, private val app: AppController) {
    private val root = File(context.filesDir, "agent")
    private val store = AtomicFile(File(root, "archive.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val gate = Mutex()
    private val draftGate = Mutex()
    private val seenDrafts = mutableSetOf<String>()
    private val auth = AgentAuth(context, scope)
    val authState = auth.state
    private val client = AgentResponsesClient { force -> auth.accessToken(force) }
    private val gateway = ConfigurationToolGateway(app)
    private val knowledge = AgentKnowledge { file ->
        context.assets.open("agent-knowledge/$file").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val mutable = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutable.asStateFlow()
    private var job: Job? = null
    private var archiveReady = false
    private val fileStores = java.util.concurrent.ConcurrentHashMap<String, AgentFiles>()

    init { scope.launch {
        try {
            val archive = withContext(Dispatchers.IO) {
                root.mkdirs()
                if (store.baseFile.exists()) json.decodeFromString<AgentArchive>(store.openRead().bufferedReader().use { it.readText() }) else AgentArchive()
            }
            archiveReady = true
            mutable.value = AgentUiState(sessions = archive.sessions.map { recover(it) }, selectedSessionId = archive.selectedSessionId, loading = false)
            if (mutable.value.sessions.isEmpty()) createSession() else persist()
            scope.launch { app.configurationWorkspaces.collect {
                mutable.value.sessions.forEach { s ->
                    if (s.workspaceIds.isNotEmpty()) try { captureDrafts(s.id, s.workspaceIds.toSet()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutable.update { it.copy(error = "配置草稿记录未保存，请检查当前状态") } }
                }
            } }
            auth.reload()
        } catch (_: Exception) {
            AppEventLog.record("Agent", "记录读取失败", AppLogLevel.ERROR)
            mutable.value = mutable.value.copy(loading = false, error = "Agent 记录读取失败；原文件保留，请勿继续覆盖")
        }
    } }
    private fun recover(s: AgentSession): AgentSession {
        val results = s.input.filter { it.str("type") == "function_call_output" }.map { it.str("call_id") }.toSet()
        val missing = s.input.filter { it.str("type") == "function_call" && it.str("call_id") !in results }.map {
            buildJsonObject { put("type", "function_call_output"); put("call_id", it.str("call_id")); put("output", "{\"status\":\"INTERRUPTED\",\"message\":\"执行状态不确定；请读取现状，不要自动重放修改\"}") }
        }
        return s.copy(input = s.input + missing, status = if (s.status == "RUNNING") "INTERRUPTED" else s.status)
    }
    private suspend fun persist() = withContext(Dispatchers.IO) {
        root.mkdirs()
        val data = json.encodeToString(AgentArchive(sessions = mutable.value.sessions, selectedSessionId = mutable.value.selectedSessionId)).toByteArray()
        val out = store.startWrite()
        try { out.write(data); store.finishWrite(out) } catch (e: Exception) { store.failWrite(out); throw e }
    }
    private suspend fun change(id: String, block: (AgentSession) -> AgentSession) = gate.withLock {
        mutable.update { state -> state.copy(sessions = state.sessions.map { if (it.id == id) block(it).copy(updatedAt = System.currentTimeMillis()) else it }) }
        persist()
    }
    private fun session(id: String) = mutable.value.sessions.first { it.id == id }
    private fun launchEdit(block: suspend () -> Unit) { if (!archiveReady) return; scope.launch { try { block() } catch (_: Exception) {
        AppEventLog.record("Agent", "记录操作未保存", AppLogLevel.ERROR)
        mutable.value = mutable.value.copy(error = "操作未保存，请重读当前记录")
    } } }
    private suspend fun createSession() = gate.withLock {
        val s = AgentSession()
        mutable.value = mutable.value.copy(sessions = mutable.value.sessions + s, selectedSessionId = s.id)
        persist()
    }
    fun newSession() = launchEdit { createSession() }
    fun selectSession(id: String) = launchEdit { gate.withLock { if (mutable.value.sessions.any { it.id == id }) { mutable.value = mutable.value.copy(selectedSessionId = id); persist() } } }
    fun renameSession(id: String, title: String) = launchEdit { change(id) { it.copy(title = title.take(100).ifBlank { "Agent 聊天" }) } }
    fun deleteSession(id: String) = launchEdit {
        if (mutable.value.runningSessionId == id) return@launchEdit
        gate.withLock {
            mutable.value = mutable.value.copy(sessions = mutable.value.sessions.filterNot { it.id == id }, selectedSessionId = mutable.value.selectedSessionId.takeUnless { it == id })
            persist()
            withContext(Dispatchers.IO) { File(root, "workspaces/$id").deleteRecursively() }
        }
        if (mutable.value.sessions.isEmpty()) createSession() else if (mutable.value.selected == null) selectSession(mutable.value.sessions.first().id)
    }
    fun updateSettings(model: String, effort: String, instructions: String, autoApply: Boolean) {
        val id = mutable.value.selectedSessionId ?: return
        if (mutable.value.runningSessionId == id) return
        launchEdit { change(id) { it.copy(model = model, effort = effort, instructions = instructions, autoApply = autoApply) } }
    }
    fun clearContext() { val id = mutable.value.selectedSessionId ?: return; if (mutable.value.runningSessionId != id) launchEdit { change(id) { it.copy(input = emptyList()) } } }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    fun stop() { job?.cancel() }
    suspend fun beginSignIn(newAccount: Boolean = false): String = if (newAccount) auth.beginSignInNewAccount() else auth.beginSignIn()
    fun cancelSignIn() = auth.cancelSignIn()
    fun signOut() { stop(); launchEdit { auth.signOut() } }
    fun refreshModels() = launchEdit {
        mutable.value = mutable.value.copy(modelLoading = true)
        try { mutable.value = mutable.value.copy(models = client.models()) }
        catch (e: Exception) {
            AppEventLog.record("Agent", "模型列表获取失败", AppLogLevel.ERROR)
            mutable.value = mutable.value.copy(error = safeError(e))
        }
        finally { mutable.value = mutable.value.copy(modelLoading = false) }
    }
    private fun filesFor(id: String) = fileStores.getOrPut(id) { AgentFiles(File(root, "workspaces/$id/files")) }
    suspend fun files(operation: String, args: JsonObject): JsonObject {
        val id = mutable.value.selectedSessionId ?: return failure("NOT_FOUND", "请选择 Agent 聊天")
        return filesFor(id).execute(operation, args)
    }
    private fun host(id: String, owned: MutableSet<String>, human: Boolean = false) = AgentToolHost(filesFor(id), { request ->
        val result = gateway.execute(request)
        try { captureDrafts(id, session(id).workspaceIds.toSet() + owned + listOfNotNull(result.str("workspaceId"))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@AgentToolHost buildJsonObject { put("status", "OUTCOME_UNCERTAIN"); put("message", "配置操作可能已生效，但记录保存失败；请检查当前资源，不要重放"); put("operationResult", result) } }
        result
    }, app::agentPromptPreview, owned, knowledge) { human || session(id).autoApply }
    suspend fun configuration(request: JsonObject): JsonObject {
        val id = mutable.value.selectedSessionId ?: return failure("NOT_FOUND", "请选择 Agent 聊天")
        val owned = session(id).workspaceIds.toMutableSet()
        val result = host(id, owned, true).execute("config", request)
        change(id) { it.copy(workspaceIds = (it.workspaceIds + owned).distinct()) }
        return result
    }
    private fun hash(value: JsonElement?): String? = value?.toString()?.toByteArray()?.let { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    private suspend fun captureDrafts(id: String, owned: Set<String>) = withContext(Dispatchers.Default) { draftGate.withLock {
        val live = app.configurationWorkspaces.value.filter { it.id in owned }
        seenDrafts.addAll(live.map { it.id })
        val saved = live.filter { it.status == ConfigurationWorkspaceStatus.OPEN }.mapNotNull { w ->
            val commands = ConfigurationWorkspaces.exportCommands(w)?.let { json.parseToJsonElement(it).jsonObject } ?: return@mapNotNull null
            val plan = ConfigurationCommands.previewEdit(w.base, w.draft, w.label).plan ?: return@mapNotNull null
            AgentSavedDraft(w.id, w.label, commands, plan.changes.associate { "${it.kind}:${it.id}" to hash(it.before) })
        }
        change(id) { s -> s.copy(savedDrafts = s.savedDrafts.filter { old -> old.workspaceId !in seenDrafts } + saved) }
    } }
    suspend fun restoreDraft(oldId: String): JsonObject {
        val id = mutable.value.selectedSessionId ?: return failure("NOT_FOUND", "请选择聊天")
        val draft = session(id).savedDrafts.firstOrNull { it.workspaceId == oldId } ?: return failure("NOT_FOUND", "未找到保存草稿")
        if (app.configurationWorkspaces.value.any { it.id == oldId }) return failure("EXISTS", "草稿仍在工作台")
        for ((key, expected) in draft.baseline) {
            val kind = key.substringBefore(':'); val resourceId = key.substringAfter(':')
            if (hash(ConfigurationContext.readResource(app.library.value, kind, resourceId)) != expected) return failure("CONFLICT", "原资源已变化；请重新读取并创建草稿")
        }
        val opened = configuration(buildJsonObject { put("op", "open"); put("label", draft.label) })
        val newId = opened.str("workspaceId") ?: return opened
        val result = configuration(buildJsonObject { put("op", "stage"); put("workspaceId", newId); put("expectedRevision", 0); put("commands", draft.commands) })
        if (result.str("status") == "OK") change(id) { it.copy(savedDrafts = it.savedDrafts.filterNot { d -> d.workspaceId == oldId }) }
        return result
    }
    fun send(text: String) {
        val initial = mutable.value.selected ?: return
        if (job?.isActive == true || text.isBlank() || mutable.value.loading) return
        if (initial.model.isBlank()) { mutable.value = mutable.value.copy(error = "登录后刷新模型，并选择账号可用模型"); return }
        job = scope.launch {
            val id = initial.id
            AppEventLog.record("Agent", "任务开始")
            mutable.value = mutable.value.copy(runningSessionId = id, streaming = "", phase = "准备", error = null)
            try {
                change(id) { it.copy(status = "RUNNING", events = it.events + AgentEvent(kind = "user", text = text), input = it.input + buildJsonObject { put("role", "user"); put("content", text) }) }
                val owned = session(id).workspaceIds.toMutableSet()
                val tools = host(id, owned)
                var done = false
                repeat(32) {
                    if (done) return@repeat
                    ensureActive()
                    val current = session(id)
                    check(current.input.sumOf { it.toString().length } < 1_000_000) { "上下文已满；请清空上下文或新建 Agent 聊天" }
                    mutable.value = mutable.value.copy(phase = "思考与回复", streaming = "")
                    AppEventLog.record("Agent", "模型请求开始")
                    val response = client.respond(initial.model, initial.effort, initial.instructions, current.input) { delta -> mutable.update { it.copy(streaming = (it.streaming + delta).takeLast(128_000)) } }
                    AppEventLog.record("Agent", "模型请求完成")
                    val calls = response.output.filter { it.str("type") == "function_call" }
                    check(calls.all { !it.str("call_id").isNullOrBlank() } && calls.map { it.str("call_id") }.distinct().size == calls.size) { "Agent 工具协议无效，已停止执行" }
                    change(id) { it.copy(input = it.input + response.output, events = it.events + if (response.text.isNotBlank()) listOf(AgentEvent(kind = "assistant", text = response.text)) else emptyList(), inputTokens = it.inputTokens + response.inputTokens, outputTokens = it.outputTokens + response.outputTokens) }
                    if (calls.isEmpty()) { done = true; return@repeat }
                    for (call in calls) {
                        ensureActive()
                        val raw = call.str("arguments").orEmpty()
                        val envelope = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                        val operation = envelope?.str("operation").orEmpty()
                        val args = runCatching { json.parseToJsonElement(envelope?.str("arguments").orEmpty()).jsonObject }.getOrNull()
                        mutable.value = mutable.value.copy(phase = operation.ifBlank { "工具" }, streaming = "")
                        val toolName = call.str("name").orEmpty().takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: "unknown"
                        val operationName = operation.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,64}")) } ?: "unknown"
                        AppEventLog.record("Agent", "工具 $toolName / $operationName 开始")
                        val result = if (!((call.str("name") == "invoke" && call.str("namespace") == "velune") || call.str("name") == "velune.invoke") || args == null) failure("INVALID", "工具调用格式无效") else withContext(Dispatchers.Default) { tools.execute(operation, args) }
                        val toolStatus = result.str("status").orEmpty().takeIf { it.matches(Regex("[A-Z_0-9-]{1,32}")) } ?: "UNKNOWN"
                        val toolCode = result.str("code").orEmpty().takeIf { it.matches(Regex("[A-Z_0-9-]{1,32}")) }
                        AppEventLog.record("Agent", "工具 $toolName / $operationName · $toolStatus${toolCode?.let { " / $it" }.orEmpty()}",
                            if (toolStatus == "OK") AppLogLevel.INFO else AppLogLevel.WARNING)
                        withContext(NonCancellable) { change(id) { it.copy(workspaceIds = (it.workspaceIds + owned).distinct(), events = it.events + AgentEvent(kind = "tool", text = operation, operation = operation, arguments = args?.toString() ?: raw, result = result.toString()), input = it.input + buildJsonObject { put("type", "function_call_output"); put("call_id", call.str("call_id")); put("output", result.toString()) }) } }
                        check(result.str("status") != "OUTCOME_UNCERTAIN") { "配置结果需人工核对，已停止自动执行" }
                    }
                }
                change(id) { it.copy(status = if (done) "IDLE" else "LIMIT", events = it.events + if (!done) listOf(AgentEvent(kind = "system", text = "已达本轮 32 次模型调用上限，任务暂停")) else emptyList()) }
                AppEventLog.record("Agent", if (done) "任务完成" else "任务已达模型调用上限", if (done) AppLogLevel.INFO else AppLogLevel.WARNING)
            } catch (e: CancellationException) {
                AppEventLog.record("Agent", "任务已停止", AppLogLevel.WARNING)
                withContext(NonCancellable) { change(id) { recover(it).copy(status = "STOPPED", events = it.events + AgentEvent(kind = "system", text = "已停止；完成的操作保留，未完成工具不自动重放")) } }
            } catch (e: Exception) {
                AppEventLog.record("Agent", "任务失败", AppLogLevel.ERROR)
                withContext(NonCancellable) { change(id) { recover(it).copy(status = "ERROR", events = it.events + AgentEvent(kind = "system", text = safeError(e))) } }
                mutable.value = mutable.value.copy(error = safeError(e))
            } finally { mutable.value = mutable.value.copy(runningSessionId = null, phase = "", streaming = "") }
        }
    }
    private fun safeError(e: Exception): String = when (e) {
        is AgentResponseException -> e.message ?: "ChatGPT 请求未完成"
        is IllegalStateException -> if (e.message in setOf("上下文已满；请清空上下文或新建 Agent 聊天", "Agent 工具协议无效，已停止执行", "配置结果需人工核对，已停止自动执行", "Connect ChatGPT to use Agent", "ChatGPT plan use is not enabled", "ChatGPT connection could not be renewed")) e.message!! else "Agent 操作未完成，请重读现状"
        else -> "网络或记录保存未完成；请检查当前状态，不要自动重放修改"
    }
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun failure(status: String, message: String) = buildJsonObject { put("status", status); put("message", message) }
}
