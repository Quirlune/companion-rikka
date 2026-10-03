package dev.gray.companion.data

import android.content.Context
import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.CredentialStore
import dev.gray.companion.core.ModelGateway
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.ModelCatalog
import dev.gray.companion.core.ModelRequestFailure
import dev.gray.companion.core.ModelReasoning
import dev.gray.companion.core.Protocol
import dev.gray.companion.core.Role
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.core.UsageStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException

class GatewayFailure(message: String, cause: Throwable? = null, retryable: Boolean = true,
    serialFallback: Boolean = false) : ModelRequestFailure(message, retryable, serialFallback, cause)

class RikkaGateway(
    context: Context, private val credentials: CredentialStore,
    private val sessionId: String = "",
    private val onUsage: suspend (ModelUsageRecord) -> Unit = {},
    connectionLimits: Map<String, Int> = emptyMap(),
) : ModelGateway {
    @Volatile var lastFailureMessage: String? = null
        private set
    fun resetFailure() { lastFailureMessage = null }
    private val applicationContext = context.applicationContext
    private val limits = connectionLimits.mapValues { it.value.coerceIn(1, 2) }
    private val connectionPermits = limits.mapValues { (id, limit) ->
        connectionPools.compute(id) { _, previous ->
            previous?.takeIf { it.limit == limit } ?: ConnectionPool(limit, Semaphore(limit))
        }!!.permits
    }
    override fun maxParallelCandidates(profile: ModelProfile): Int =
        limits[ModelCatalog.credentialId(profile)] ?: 1
    private val manager by lazy {
        val sharedClient = GlobalContext.get().get<OkHttpClient>()
        ProviderManager(sharedClient.newBuilder().callTimeout(120, TimeUnit.SECONDS).build(), applicationContext)
    }

    override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
        var retryCount = 0
        while (true) {
            var sentText = false
            try {
                return generateAttempt(profile, messages) { delta ->
                    sentText = true
                    onDelta(delta)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: GatewayFailure) {
                currentCoroutineContext().ensureActive()
                val enabled = GlobalContext.get().get<SettingsStore>()
                    .settingsFlow.value.networkSetting.enableAutoRetry
                if (!enabled || sentText || retryCount >= 3 ||
                    failure.message != "网络连接失败" || failure.cause !is IOException) throw failure
                delay(1_000L shl retryCount)
                retryCount++
            }
        }
    }

    override suspend fun generateAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
        requestPermits.withPermit {
            connectionPermits[ModelCatalog.credentialId(profile)]?.withPermit {
                request(profile, messages, onDelta)
            } ?: request(profile, messages, onDelta)
        }

    private suspend fun request(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
        lastFailureMessage = null
        if (profile.model.isBlank()) throw GatewayFailure("请先填写模型 ID", retryable = false)
        val secret = credentials.get(ModelCatalog.credentialId(profile))
        if (secret.isBlank()) {
            lastFailureMessage = "所选模型的 API 连接尚未填写密钥"
            throw GatewayFailure(lastFailureMessage!!, retryable = false)
        }
        val model = Model(modelId = profile.model.trim(), displayName = profile.model.trim())
        val setting = when (profile.protocol) {
            Protocol.OPENAI -> ProviderSetting.OpenAI(apiKey = secret, baseUrl = profile.baseUrl.trimEnd('/'), useResponseApi = profile.responseApi, models = listOf(model))
            Protocol.GOOGLE -> ProviderSetting.Google(apiKey = secret, baseUrl = profile.baseUrl.trimEnd('/'), models = listOf(model))
            Protocol.CLAUDE -> ProviderSetting.Claude(apiKey = secret, baseUrl = profile.baseUrl.trimEnd('/'), models = listOf(model))
        }
        val uiMessages = messages.map { message -> when (message.role) {
            Role.SYSTEM -> UIMessage.system(message.text)
            Role.USER -> UIMessage.user(message.text)
            Role.ASSISTANT -> UIMessage.assistant(message.text)
        } }
        val answer = StringBuilder()
        val params = TextGenerationParams(model = model, temperature = profile.temperature,
            topP = profile.topP, maxTokens = profile.maxTokens,
            reasoningLevel = when (profile.reasoning) {
                ModelReasoning.OFF -> ReasoningLevel.OFF
                ModelReasoning.AUTO -> ReasoningLevel.AUTO
                ModelReasoning.LOW -> ReasoningLevel.LOW
                ModelReasoning.MEDIUM -> ReasoningLevel.MEDIUM
                ModelReasoning.HIGH -> ReasoningLevel.HIGH
                ModelReasoning.XHIGH -> ReasoningLevel.XHIGH
                ModelReasoning.MAX -> ReasoningLevel.MAX
            },
            customHeaders = profile.resolvedHeaders.map { CustomHeader(it.name, it.value) },
            customBody = profile.customBody.map { CustomBody(it.key, it.value) })
        val startedAt = System.currentTimeMillis()
        var status = UsageStatus.FAILED
        var promptTokens: Long? = null
        var completionTokens: Long? = null
        var reportedTotal: Long? = null
        try {
            manager.getProviderByType(setting).streamText(setting, uiMessages, params).collect { chunk ->
                if (chunk is StreamChunk.TextDelta) {
                    answer.append(chunk.text)
                    onDelta(chunk.text)
                } else if (chunk is StreamChunk.Usage) {
                    // Provider events are cumulative snapshots, sometimes split by input/output.
                    promptTokens = maxOf(promptTokens ?: 0L, chunk.usage.promptTokens.toLong())
                    completionTokens = maxOf(completionTokens ?: 0L, chunk.usage.completionTokens.toLong())
                    reportedTotal = maxOf(reportedTotal ?: 0L, chunk.usage.totalTokens.toLong())
                }
            }
            if (answer.isEmpty()) {
                lastFailureMessage = "模型没有返回文本"
                throw GatewayFailure("模型没有返回文本")
            }
            status = UsageStatus.SUCCESS
        } catch (cancelled: CancellationException) { status = UsageStatus.CANCELLED; throw cancelled }
        catch (failure: GatewayFailure) { throw failure }
        catch (failure: Exception) {
            val status = generateSequence(failure as Throwable) { it.cause }
                .mapNotNull { (it as? HttpException)?.statusCode }.firstOrNull()?.toString()
                ?: Regex("(?:response|HTTP|status|Unknown error)[^0-9]{0,16}([45][0-9]{2})", RegexOption.IGNORE_CASE)
                    .find(failure.message.orEmpty())?.groupValues?.get(1)
            val safe = when {
                status != null -> "模型服务返回 HTTP $status"
                failure is IOException -> "网络连接失败"
                else -> "模型服务请求失败"
            }
            lastFailureMessage = safe
            throw GatewayFailure(safe, failure, retryable = status !in setOf("400", "401", "403", "404", "422"),
                serialFallback = status == "429")
        } finally {
            val total = reportedTotal?.let { maxOf(it, (promptTokens ?: 0L) + (completionTokens ?: 0L)) }
            withContext(NonCancellable) {
                onUsage(ModelUsageRecord(sessionId = sessionId, profileId = profile.id, model = profile.model,
                    startedAt = startedAt, finishedAt = System.currentTimeMillis(), status = status,
                    promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = total))
            }
        }
        return answer.toString()
    }

    companion object {
        // Shared across chat candidates and title generation; acquire only around actual requests.
        private val requestPermits = Semaphore(4)
        private data class ConnectionPool(val limit: Int, val permits: Semaphore)
        private val connectionPools = ConcurrentHashMap<String, ConnectionPool>()
    }
}
