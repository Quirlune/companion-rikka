package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

data class IllustrationActivity(
    val planning: Int = 0,
    val queued: Int = 0,
    val running: Int = 0,
    val congested: Boolean = false,
    val error: String? = null,
    val uncertainDelivery: Boolean = false
)

/** Post-commit native image queue. It never owns a session transaction or the main run lock. */
class IllustrationController(
    private val scope: CoroutineScope,
    private val library: StateFlow<Library>,
    private val gateway: suspend (Library, Session, ModelProfile) -> ModelGateway,
    private val keyProvider: suspend () -> String,
    private val client: IllustrationService,
    private val publish: suspend (IllustrationConfig, IllustrationImage, Boolean) -> Boolean
) {
    private val gate = Any()
    private val mutable = MutableStateFlow(IllustrationActivity())
    val state: StateFlow<IllustrationActivity> = mutable.asStateFlow()
    private val plannedSources = linkedSetOf<Pair<String, String>>()
    private val planning = mutableMapOf<Pair<String, String>, Planning>()
    private val pending = ArrayDeque<Queued>()
    private val running = mutableMapOf<Long, Running>()
    private var serial = 0L
    private var error: String? = null
    /** True when a paid POST may still have an unresolved remote task. */
    private var uncertainDelivery = false
    private var activeConfig = library.value.illustration

    /** A manual redraw clears prior images once, at its first successful publication. */
    private class RedrawBatch(val publishMutex: Mutex = Mutex(), var firstPublished: Boolean = false)
    private data class Planning(val plan: IllustrationPlan, val job: Job)
    private data class Queued(val plan: IllustrationPlan, val prompt: IllustrationPrompt, val redraw: RedrawBatch?)
    private data class Running(val queued: Queued, val job: Job, var phase: BizyAirPhase? = null)

    init {
        scope.launch {
            library.collect { snapshot -> reconcile(snapshot) }
        }
    }

    /** Invoked after the accepted BODY and its source message are already durable. */
    fun bodyCommitted(sessionId: String, accepted: RunResult) {
        val snapshot = library.value
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return
        val acceptedMessage = accepted.replies.asReversed().firstOrNull { reply ->
            reply.role == Role.ASSISTANT && reply.outputSlot == OutputSlot.BODY &&
                session.messages.any { it.id == reply.id && it.text == reply.text &&
                    it.activeTextRevisionId == reply.activeTextRevisionId }
        } ?: return
        val plan = try { IllustrationPlanner.plan(snapshot, session, acceptedMessage) }
            catch (_: Exception) {
                synchronized(gate) { if (snapshot.illustration.enabled) setErrorLocked("插图设置或规划来源无效") }
                return
            } ?: run {
            synchronized(gate) { if (snapshot.illustration.enabled)
                setErrorLocked("插图规划不可用，请检查规划模型与当前正文") }
            return
        }
        schedule(snapshot, session, plan, manual = false)
    }

    /** Explicit user action on an already saved BODY. An earlier active message is planned
     * against only its preceding completed turns; the live source is rechecked before publish. */
    fun regenerate(sessionId: String, messageId: String): Boolean {
        val snapshot = library.value
        if (!snapshot.illustration.enabled) return false
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return false
        val targetIndex = session.messages.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) return false
        val message = session.messages[targetIndex]
        if (message.deleted || message.outputSlot != OutputSlot.BODY || message.role != Role.ASSISTANT ||
            message.text.isBlank() || message.authoredOpening) return false
        val historicSession = session.copy(messages = session.messages.take(targetIndex + 1), pendingRun = null)
        val plan = try { IllustrationPlanner.plan(snapshot, historicSession, message) }
            catch (_: Exception) { null } ?: return false
        return schedule(snapshot, historicSession, plan, manual = true)
    }

    private fun schedule(snapshot: Library, session: Session, plan: IllustrationPlan, manual: Boolean): Boolean = synchronized(gate) {
        if (snapshot.illustration != activeConfig) reconcile(snapshot)
        val sourceId = plan.source.sessionId to plan.source.key
        val busy = sourceId in planning || pending.any { (it.plan.source.sessionId to it.plan.source.key) == sourceId } ||
            running.values.any { (it.queued.plan.source.sessionId to it.queued.plan.source.key) == sourceId }
        if (uncertainDelivery || busy || !current(library.value, plan) || !manual && sourceId in plannedSources) return@synchronized false
        plannedSources += sourceId
        if (plannedSources.size > 256) plannedSources.remove(plannedSources.first())
        error = null
        val redraw = if (manual) RedrawBatch() else null
        val job = scope.launch(start = CoroutineStart.LAZY) { planImages(snapshot, session, plan, redraw) }
        planning[sourceId] = Planning(plan, job)
        AppEventLog.record("插图", if (manual) "手动重绘规划开始" else "自动规划开始")
        updateStateLocked()
        job.start()
        true
    }

    /** Only call after the person has checked uncertain tasks in BizyAir. */
    fun acknowledgeRemoteStatus() = synchronized(gate) {
        uncertainDelivery = false
        error = null
        drainLocked()
    }

    private suspend fun planImages(snapshot: Library, session: Session, plan: IllustrationPlan, redraw: RedrawBatch?) {
        try {
            val keyReady = try { keyProvider().isNotBlank() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            if (!keyReady) throw BizyAirFailure("CREDENTIAL_UNAVAILABLE", "请先配置 BizyAir 密钥")
            val text = gateway(snapshot, session, plan.profile).generate(
                plan.profile, ModelRequestMessages.prepare(plan.messages)
            ) {}
            val prompts = IllustrationPlanner.parseXml(text, plan.paragraphs, plan.config.maxImages)
            synchronized(gate) {
                if (current(library.value, plan)) {
                    prompts.forEach { pending.addLast(Queued(plan, it, redraw)) }
                    AppEventLog.record("插图", "规划完成：${prompts.size} 张待生成")
                    drainLocked()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BizyAirFailure) {
            synchronized(gate) { if (current(library.value, plan)) setErrorLocked(failure.message ?: "插图设置不完整") }
        } catch (_: Exception) {
            synchronized(gate) { if (current(library.value, plan)) setErrorLocked("插图规划失败，请检查提示词或模型输出") }
        } finally {
            val finishingJob = currentCoroutineContext().job
            synchronized(gate) {
                val sourceId = plan.source.sessionId to plan.source.key
                if (planning[sourceId]?.job == finishingJob)
                    planning.remove(sourceId)
                updateStateLocked()
            }
        }
    }

    private fun reconcile(snapshot: Library) = synchronized(gate) {
        if (!snapshot.illustration.enabled || snapshot.illustration != activeConfig) {
            activeConfig = snapshot.illustration
            planning.values.forEach { it.job.cancel() }
            planning.clear()
            // Submitted remote work can keep running after a local source or config change.
            // Keep its slot until terminal status; never publish it if now stale.
            pending.clear()
            plannedSources.clear()
            if (!uncertainDelivery) error = null
        } else {
            val stalePlanning = planning.filterValues { !current(snapshot, it.plan) }.keys
            stalePlanning.forEach { key -> planning.remove(key)?.job?.cancel() }
            pending.removeAll { !current(snapshot, it.plan) }
            drainLocked()
        }
        updateStateLocked()
    }

    private fun current(snapshot: Library, plan: IllustrationPlan): Boolean =
        snapshot.illustration.enabled && snapshot.illustration == plan.config &&
            snapshot.sessions.firstOrNull { it.id == plan.source.sessionId }
                ?.let { IllustrationPlanner.isCurrentSource(it, plan.source) } == true

    private fun drainLocked() {
        if (uncertainDelivery) {
            updateStateLocked()
            return
        }
        while (pending.isNotEmpty() && running.size < activeConfig.concurrency.coerceIn(1, 2)) {
            val next = pending.removeFirst()
            if (!current(library.value, next.plan)) continue
            val id = ++serial
            AppEventLog.record("插图", "图片生成开始")
            val job = scope.launch(start = CoroutineStart.LAZY) { runImage(id, next) }
            running[id] = Running(next, job)
            job.start()
        }
        updateStateLocked()
    }

    private suspend fun runImage(id: Long, queued: Queued) {
        var generated: BizyAirGeneratedImage? = null
        try {
            if (!current(library.value, queued.plan)) return
            val payload = payload(queued.plan.config, queued.prompt.prompt)
            val key = keyProvider()
            if (key.isBlank()) throw BizyAirFailure("CREDENTIAL_UNAVAILABLE", "请先配置 BizyAir 密钥")
            generated = client.generate(payload, key) { phase ->
                synchronized(gate) {
                    running[id]?.phase = phase
                    updateStateLocked()
                }
            }
            if (!current(library.value, queued.plan)) return
            val image = IllustrationImage(
                queued.plan.source.sessionId, queued.plan.source.messageId,
                queued.plan.source.key, queued.prompt.afterParagraphId,
                generated.localPath, generated.width, generated.height
            )
            val redraw = queued.redraw
            val saved = if (redraw != null) redraw.publishMutex.withLock {
                publish(queued.plan.config, image, !redraw.firstPublished).also { success ->
                    if (success) redraw.firstPublished = true
                }
            } else publish(queued.plan.config, image, false)
            if (saved) {
                generated = null
                AppEventLog.record("插图", "图片已保存")
            }
            else throw BizyAirFailure("SOURCE_STALE", "插图来源已变化，结果未保存")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BizyAirFailure) {
            AppEventLog.record("插图", "服务状态：${failure.code}", AppLogLevel.WARNING)
            synchronized(gate) {
                if (failure.uncertainDelivery) {
                    uncertainDelivery = true
                    error = failure.message ?: "远端任务状态不明；请先核对 BizyAir"
                    updateStateLocked()
                } else if (current(library.value, queued.plan)) {
                    error = failure.message ?: "插图生成失败"
                    updateStateLocked()
                }
            }
        } catch (failure: Exception) {
            // Class and call sites only: never log requests, prompts, credentials or response bodies.
            android.util.Log.e("VeluneIllustration", failure.javaClass.name + " at " +
                failure.stackTrace.take(8).joinToString("; "))
            synchronized(gate) { if (current(library.value, queued.plan)) setErrorLocked("插图生成或保存失败") }
        } finally {
            generated?.let { java.io.File(it.localPath).delete() }
            synchronized(gate) {
                running.remove(id)
                drainLocked()
            }
        }
    }

    private fun setErrorLocked(message: String) {
        error = message
        updateStateLocked()
    }

    private fun updateStateLocked() {
        if (error != null && error != mutable.value.error) {
            val reason = error?.takeIf { it.length <= 200 && '\n' !in it &&
                !it.trimStart().startsWith("{") && !it.trimStart().startsWith("[") }
                ?: "服务返回内容已略去"
            AppEventLog.record("插图", if (uncertainDelivery) "远端任务状态不明：$reason" else "插图操作失败：$reason", AppLogLevel.ERROR)
        }
        mutable.value = IllustrationActivity(
            planning = planning.size, queued = pending.size, running = running.size,
            congested = uncertainDelivery ||
                (pending.isNotEmpty() && running.size >= activeConfig.concurrency.coerceIn(1, 2)) ||
                running.values.any { it.phase == BizyAirPhase.QUEUED },
            error = error, uncertainDelivery = uncertainDelivery
        )
    }

    companion object {
        private val random = SecureRandom()

        /** The private app's typed input values; only declared fields enter this payload. */
        internal fun payload(config: IllustrationConfig, prompt: String): JsonObject {
            require(prompt.isNotBlank() && prompt.length <= 8_192)
            val prefix = config.positiveInput.value.trim().trimEnd(',')
            val positive = listOf(prefix, prompt.trim()).filter { it.isNotBlank() }.joinToString(", ")
            val seed = parseLong(config.seedInput.value).let { if (it == -1L) random.nextLong().ushr(1) else it }
            require(seed >= 0)
            val values = linkedMapOf(
                config.positiveInput.name to JsonPrimitive(positive),
                config.negativeInput.name to JsonPrimitive(config.negativeInput.value),
                config.seedInput.name to JsonPrimitive(seed),
                config.stepsInput.name to JsonPrimitive(parseLong(config.stepsInput.value)),
                config.cfgInput.name to JsonPrimitive(parseDouble(config.cfgInput.value)),
                config.widthInput.name to JsonPrimitive(parseLong(config.widthInput.value)),
                config.heightInput.name to JsonPrimitive(parseLong(config.heightInput.value))
            )
            require(values.size == 7) { "BizyAir 输入字段重复" }
            return buildJsonObject {
                put("web_app_id", config.appId)
                put("suppress_preview_output", true)
                put("input_values", JsonObject(values))
            }
        }

        private fun parseLong(raw: String): Long = raw.trim().toLongOrNull()
            ?: throw IllegalArgumentException("插图数值参数无效")
        private fun parseDouble(raw: String): Double = raw.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("插图数值参数无效")
    }
}
