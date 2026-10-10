package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Composer state only: never a chat message, accepted action or branch checkpoint. */
data class AutomaticOptionsUiState(
    val sessionId: String? = null,
    val sourceKey: String? = null,
    val options: List<AutomaticOption> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

internal class AutomaticOptionsController(
    private val scope: CoroutineScope,
    private val library: StateFlow<Library>,
    private val gateway: suspend (Library, Session, ModelProfile) -> ModelGateway,
) {
    private val mutable = MutableStateFlow(AutomaticOptionsUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var activeConfig: AutomaticOptionsConfig? = null
    private var activeProfile: ModelProfile? = null
    private var activeContextKey: String? = null
    private val automaticSources = linkedSetOf<String>()

    init {
        scope.launch {
            library.collect { snapshot ->
                val session = snapshot.sessions.firstOrNull { it.id == snapshot.selectedSessionId }
                val source = session?.let { AutomaticOptions.source(snapshot, it) }
                val currentRequest = if (activeProfile != null && session != null)
                    runCatching { AutomaticOptions.request(snapshot, session) }.getOrNull() else null
                if (!snapshot.automaticOptions.enabled || session?.id != mutable.value.sessionId ||
                    source?.key != mutable.value.sourceKey ||
                    activeConfig != null && activeConfig != snapshot.automaticOptions ||
                    activeProfile != null && (currentRequest?.profile != activeProfile ||
                        currentRequest?.contextKey != activeContextKey)) clear()
            }
        }
    }

    private fun clear() {
        generation++
        job?.cancel()
        job = null
        activeConfig = null
        activeProfile = null
        activeContextKey = null
        mutable.value = AutomaticOptionsUiState()
    }

    /** Called only after an accepted main BODY was committed by the existing publisher. */
    fun bodyCommitted(sessionId: String, accepted: RunResult) {
        val snapshot = library.value
        if (snapshot.selectedSessionId != sessionId || !snapshot.automaticOptions.enabled ||
            snapshot.automaticOptions.blocks == null && snapshot.automaticOptions.prompt.isBlank()) return
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return
        val source = AutomaticOptions.source(snapshot, session) ?: return
        val committedId = source.turns.last().assistants.last().id
        val committed = session.messages.firstOrNull { it.id == committedId } ?: return
        if (accepted.replies.none { it.id == committed.id && it.text == committed.text &&
                it.activeTextRevisionId == committed.activeTextRevisionId }) return
        if (!automaticSources.add(source.key)) return
        while (automaticSources.size > 64) automaticSources.remove(automaticSources.first())
        generate()
    }

    fun generate() {
        val snapshot = library.value
        if (!snapshot.automaticOptions.enabled) return
        val session = snapshot.sessions.firstOrNull { it.id == snapshot.selectedSessionId } ?: return
        val source = AutomaticOptions.source(snapshot, session) ?: return
        if (mutable.value.loading && mutable.value.sourceKey == source.key) return
        clear()
        activeConfig = snapshot.automaticOptions
        val request = try { AutomaticOptions.request(snapshot, session) }
        catch (failure: Exception) {
            mutable.value = AutomaticOptionsUiState(session.id, source.key,
                error = failure.message ?: "自动选项提示词无法编译")
            return
        }
        if (request == null) {
            mutable.value = AutomaticOptionsUiState(session.id, source.key, error =
                if (snapshot.automaticOptions.blocks == null && snapshot.automaticOptions.prompt.isBlank()) "请先在自动选项设置中填写提示词"
                else "请先为自动选项或正文调用配置可用模型")
            return
        }
        activeProfile = request.profile
        activeContextKey = request.contextKey
        val token = generation
        mutable.value = AutomaticOptionsUiState(session.id, source.key, loading = true)
        job = scope.launch {
            try {
                val output = gateway(snapshot, session, request.profile).generate(request.profile,
                    ModelRequestMessages.prepare(request.messages)) {}
                val text = request.processOutput(output)
                val options = AutomaticOptions.parse(text, snapshot.automaticOptions)
                if (current(token, request.source.key, session.id, snapshot.automaticOptions, request.profile, request.contextKey))
                    mutable.value = AutomaticOptionsUiState(session.id, source.key, options)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current(token, request.source.key, session.id, snapshot.automaticOptions, request.profile, request.contextKey))
                    mutable.value = AutomaticOptionsUiState(session.id, source.key,
                        error = failure.message ?: "选项生成失败，点一下可重试")
            } finally {
                if (token == generation) job = null
            }
        }
    }

    private fun current(token: Long, sourceKey: String, sessionId: String,
                        config: AutomaticOptionsConfig, profile: ModelProfile, contextKey: String): Boolean {
        val latest = library.value
        val session = latest.sessions.firstOrNull { it.id == sessionId } ?: return false
        val request = runCatching { AutomaticOptions.request(latest, session) }.getOrNull()
        return token == generation && latest.selectedSessionId == sessionId &&
            latest.automaticOptions == config && config.enabled &&
            AutomaticOptions.source(latest, session)?.key == sourceKey &&
            request?.profile == profile && request?.contextKey == contextKey
    }
}
