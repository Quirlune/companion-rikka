package dev.gray.companion.data

import android.content.Context
import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.CredentialStore
import dev.gray.companion.core.ModelGateway
import dev.gray.companion.core.ModelDelta
import dev.gray.companion.core.ModelResponse
import dev.gray.companion.core.ReasoningSnapshot
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.ModelCatalog
import dev.gray.companion.core.ModelRequestFailure
import dev.gray.companion.core.ModelReasoning
import dev.gray.companion.core.Protocol
import dev.gray.companion.core.Role
import dev.gray.companion.core.ModelUsageRecord
import dev.gray.companion.NativeProviderCodec
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel
import dev.gray.companion.core.UsageStatus
import dev.gray.companion.core.boundedModelFinishReason
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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.HttpException
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class GatewayFailure(message: String, cause: Throwable? = null, retryable: Boolean = true,
    serialFallback: Boolean = false) : ModelRequestFailure(message, retryable, serialFallback, cause)

/** A short provider diagnostic for UI. Do not echo arbitrary response JSON, credentials or prompts. */
internal fun safeHttpDiagnostic(failure: Throwable): String? {
    val http = generateSequence(failure) { it.cause }.filterIsInstance<HttpException>().lastOrNull() ?: return null
    val message = http.message?.takeIf { it.isNotBlank() && it.length <= 2_048 &&
        !it.trimStart().startsWith('{') && !it.trimStart().startsWith('[') }
        ?.replace(Regex("(?i)\\bBearer\\s+[^\\s,;]+"), "Bearer [已隐藏]")
        ?.replace(Regex("(?i)\\b(?:sk|or)-[A-Za-z0-9_-]{8,}"), "[已隐藏]")
        ?.replace(Regex("(?i)\\b(?:api[_ -]?key|authorization)\\s*[:=]\\s*[^\\s,;]+"), "[已隐藏]")
        ?.replace(Regex("https?://[^\\s,;]+"), "[链接已隐藏]")
        ?.replace(Regex("\\b[A-Za-z0-9_-]{40,}\\b"), "[已隐藏]")
        ?.replace(Regex("[\\p{Cntrl}]+"), " ")
        ?.trim()?.take(240)?.takeIf { it.isNotBlank() }
    fun label(value: String?): String? = value?.takeIf { it.length in 1..40 &&
        it.all { char -> char.isLetterOrDigit() || char in "._- " } }
    return listOfNotNull(label(http.providerName)?.let { "提供商 $it" },
        label(http.providerCode)?.let { "代码 $it" }, message).joinToString(" · ").ifBlank { null }
}

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
        // Streaming may remain active for longer than two minutes; the shared client's
        // read timeout limits idle network reads without imposing a total SSE lifetime.
        ProviderManager(sharedClient.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build(), applicationContext)
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
        generateResponseAttempt(profile, messages) { delta ->
            if (delta is ModelDelta.Text) onDelta(delta.text)
        }.text

    override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
        val requestId = requestSerial.incrementAndGet()
        val label = "请求 #$requestId · profile=${profile.id.take(64)} · model=${profile.model.take(80)}"
        AppEventLog.record("模型调用", "$label · 开始")
        return try {
            val response = requestPermits.withPermit {
                connectionPermits[ModelCatalog.credentialId(profile)]?.withPermit {
                    request(profile, messages, onDelta)
                } ?: request(profile, messages, onDelta)
            }
            AppEventLog.record("模型调用", "$label · 完成")
            response
        } catch (cancelled: CancellationException) {
            AppEventLog.record("模型调用", "$label · 取消", AppLogLevel.WARNING)
            throw cancelled
        } catch (failure: Exception) {
            val safeMessage = (failure as? GatewayFailure)?.message?.takeIf {
                it.length <= 240 && '\n' !in it && !it.trimStart().startsWith("{") && !it.trimStart().startsWith("[")
            }
            AppEventLog.record("模型调用", "$label · 失败：${failure.javaClass.simpleName}${safeMessage?.let { " · $it" }.orEmpty()}", AppLogLevel.WARNING)
            throw failure
        }
    }

    private suspend fun request(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
        lastFailureMessage = null
        if (profile.model.isBlank()) throw GatewayFailure("请先填写模型 ID", retryable = false)
        val native = try { NativeProviderCodec.request(profile, credentials::get) }
        catch (failure: IllegalArgumentException) {
            lastFailureMessage = failure.message ?: "模型连接配置无效"
            throw GatewayFailure(lastFailureMessage!!, retryable = false)
        }
        val model = native.model
        val setting = native.provider
        if (model.type != me.rerere.ai.provider.ModelType.CHAT ||
            model.outputModalities != listOf(me.rerere.ai.provider.Modality.TEXT))
            throw GatewayFailure("当前主调用接收文本回复，请选择纯文本输出的聊天模型", retryable = false)
        val uiMessages = messages.map { message ->
            val archived = message.reasoningSnapshot?.nativeMessage?.let {
                runCatching { me.rerere.rikkahub.utils.JsonInstant.decodeFromJsonElement(UIMessage.serializer(), it) }.getOrNull()
            }?.takeIf { original -> message.role == Role.ASSISTANT && original.role == MessageRole.ASSISTANT &&
                original.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text } == message.text }
            archived ?: when (message.role) {
            Role.SYSTEM -> UIMessage.system(message.text)
            Role.USER -> UIMessage.user(message.text)
            Role.ASSISTANT -> UIMessage.assistant(message.text)
        } }
        val nativeHandler = StreamChunkHandler(model)
        var nativeMessages = listOf(UIMessage.assistant(""))
        val answer = StringBuilder()
        val reasoningText = StringBuilder()
        var reasoningStartedAt: Long? = null
        var reasoningFinishedAt: Long? = null
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
                null -> null
            },
            customHeaders = profile.resolvedHeaders.map { CustomHeader(it.name, it.value) } + model.customHeaders,
            customBody = model.customBodies, legacyGeneration = profile.legacyGeneration)
        val startedAt = System.currentTimeMillis()
        var status = UsageStatus.FAILED
        var promptTokens: Long? = null
        var completionTokens: Long? = null
        var reportedTotal: Long? = null
        var finishReason: String? = null
        try {
            manager.getProviderByType(setting).streamText(setting, uiMessages, params).collect { chunk ->
                if (profile.nativeModel != null || profile.resolvedProvider != null)
                    nativeMessages = nativeHandler.handle(nativeMessages, chunk)
                when (chunk) {
                    is StreamChunk.TextDelta -> {
                        answer.append(chunk.text)
                        onDelta(ModelDelta.Text(chunk.text))
                    }
                    is StreamChunk.ReasoningStart -> {
                        if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
                        reasoningFinishedAt = null
                        onDelta(ModelDelta.ReasoningStart(reasoningStartedAt!!))
                    }
                    is StreamChunk.ReasoningDelta -> {
                        if (reasoningStartedAt == null || reasoningFinishedAt != null) {
                            if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
                            reasoningFinishedAt = null
                            onDelta(ModelDelta.ReasoningStart(reasoningStartedAt!!))
                        }
                        reasoningText.append(chunk.text)
                        onDelta(ModelDelta.Reasoning(chunk.text))
                    }
                    is StreamChunk.ReasoningEnd -> {
                        if (reasoningStartedAt != null) {
                            reasoningFinishedAt = System.currentTimeMillis()
                            onDelta(ModelDelta.ReasoningEnd(reasoningFinishedAt!!))
                        }
                    }
                    is StreamChunk.Usage -> {
                        // Provider events are cumulative snapshots, sometimes split by input/output.
                        promptTokens = maxOf(promptTokens ?: 0L, chunk.usage.promptTokens.toLong())
                        completionTokens = maxOf(completionTokens ?: 0L, chunk.usage.completionTokens.toLong())
                        reportedTotal = maxOf(reportedTotal ?: 0L, chunk.usage.totalTokens.toLong())
                    }
                    is StreamChunk.Finish -> finishReason = boundedModelFinishReason(chunk.finishReason)
                    is StreamChunk.ToolCallStart -> throw GatewayFailure("当前调用尚未配置客户端工具执行器", retryable = false)
                    else -> Unit
                }
            }
            if (reasoningStartedAt != null && reasoningFinishedAt == null) {
                reasoningFinishedAt = System.currentTimeMillis()
                onDelta(ModelDelta.ReasoningEnd(reasoningFinishedAt!!))
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
                status != null -> "模型服务返回 HTTP $status" +
                    (safeHttpDiagnostic(failure)?.let { "：$it" } ?: "")
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
        val nativeMessage = if (profile.nativeModel != null || profile.resolvedProvider != null)
            me.rerere.rikkahub.utils.JsonInstant.encodeToJsonElement(UIMessage.serializer(), nativeMessages.last()).jsonObject else null
        return ModelResponse(answer.toString(), if (reasoningStartedAt != null || nativeMessage != null)
            ReasoningSnapshot(reasoningText.toString(), reasoningStartedAt ?: startedAt,
                reasoningFinishedAt ?: System.currentTimeMillis(), nativeMessage) else null,
            finishReason)
    }

    companion object {
        // Shared across chat candidates and title generation; acquire only around actual requests.
        private val requestPermits = Semaphore(4)
        private val requestSerial = AtomicLong()
        private data class ConnectionPool(val limit: Int, val permits: Semaphore)
        private val connectionPools = ConcurrentHashMap<String, ConnectionPool>()
    }
}
