package dev.gray.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Executes candidates only. Admission, receipt replay and accepted publication stay in the engine. */
internal class NodeCandidateExecutor(
    private val library: Library,
    private val gateway: ModelGateway,
    private val outcomeValidator: OutcomeValidator,
    private val semaphore: Semaphore,
    private val semantics: ExecutionSemantics
) {
    suspend fun execute(
        ref: PlannedNode,
        invocation: ActionContext,
        correction: String?,
        onReasoningUpdate: suspend (ReasoningSnapshot) -> Unit,
        onClearStream: suspend () -> Unit
    ): ValidatedCandidate {
        val pureModel = ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL
        suspend fun executeCandidate(context: ActionContext,
                                     onReasoning: suspend (ReasoningSnapshot) -> Unit = {}): ValidatedCandidate {
            val model = ref.action?.descriptor?.usesModel == true
            if (!semantics.reasoningArchives || !model) {
                val outcome = semaphore.withPermit { ref.action!!.handler.execute(context) }
                return ValidatedCandidate(outcomeValidator.validate(ref, invocation, outcome),
                    outcome.text.takeIf { model && (semantics.retainModelResults || pureModel && ref.node.outputSlot == OutputSlot.BODY) })
            }
            val parsing = ref.node.reasoningParsing ?: library.reasoningParsing
            val captured = CandidateModelGateway(context.gateway, parsing, onReasoning)
            try {
                val outcome = semaphore.withPermit { ref.action!!.handler.execute(context.copy(gateway = captured)) }
                fun requireCanonicalBody(text: String) {
                    if (pureModel && text.isBlank()) throw ReasoningFormatFailure("模型响应缺少正文内容")
                }
                val capture = captured.singleCapture
                if (capture != null) {
                    requireCanonicalBody(capture.body)
                    return ValidatedCandidate(outcomeValidator.validate(ref, invocation, outcome),
                        capture.body, capture.fullText, capture.reasoning)
                }
                if (captured.requestCount == 0) {
                    val parsed = ReasoningTextParser.parse(outcome.text, parsing)
                    requireCanonicalBody(parsed.text)
                    return ValidatedCandidate(outcomeValidator.validate(ref, invocation, outcome.copy(text = parsed.text)),
                        parsed.text, reasoningSnapshot = parsed.reasoning,
                        provenanceNote = "动作声明模型结果，但未经过模型网关；完整响应来源未经核验")
                }
                requireCanonicalBody(outcome.text)
                return ValidatedCandidate(outcomeValidator.validate(ref, invocation, outcome),
                    provenanceNote = if (captured.requestCount > 1) "动作组合了多个模型请求；不推定单一原文和思考来源"
                        else "动作未返回完整模型响应；不推定原文和思考来源")
            } catch (failure: ReasoningFormatFailure) {
                throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
            }
        }
        if (!pureModel) return executeCandidate(
            if (semantics.provisionalProcessedOutput) invocation.copy(onDelta = {}) else invocation)
        val profile = invocation.profile ?: throw NodeExecutionFailure(ref, "未选择模型", RecoveryFailureKind.CONFIGURATION, retryable = false)
        val capacity = gateway.maxParallelCandidates(profile).coerceIn(1, 2)
        val sourceAddress = "result.${ref.node.outputKey}"
        val hasDirectContract = outcomeValidator.hasDirectContract(sourceAddress)
        val streamFirst = ref.node.visible && !hasDirectContract &&
            (semantics.provisionalProcessedOutput || ref.node.outputProcessing.isEmpty() &&
                !(ref.node.regexOutputCopy.enabled && ref.node.regexOutputCopy.display && ref.node.outputSlot == OutputSlot.BODY))
        var serialFallback = false
        var lastFailure = NodeExecutionFailure(ref, "模型调用失败", RecoveryFailureKind.MODEL_REQUEST)
        for (round in 0..2) {
            val width = if (round == 0 || serialFallback) 1 else capacity
            var streamed = false
            val received = Channel<Pair<ValidatedCandidate?, NodeExecutionFailure?>>(width)
            val winner = supervisorScope {
                val tasks = (0 until width).map { async {
                    val candidate = try {
                        val correctionMessages = buildList {
                            if (!correction.isNullOrBlank())
                                add(ChatMessage(role = Role.SYSTEM, text = correction))
                            if (round > 0 && lastFailure.kind == RecoveryFailureKind.OUTPUT_FORMAT)
                                add(ChatMessage(role = Role.SYSTEM,
                                    text = "上次结果未通过格式校验：${lastFailure.detail.take(120)}。按既定要求重新生成。"))
                        }
                        val requestContext = invocation.copy(
                            messages = invocation.messages + correctionMessages,
                            onDelta = { delta ->
                                if (round == 0 && streamFirst) {
                                    streamed = true
                                    invocation.onDelta(delta)
                                }
                            }
                        )
                        executeCandidate(requestContext) { reasoning ->
                            if (round == 0 && streamFirst && ref.workflow.effectiveTrigger() != RuntimeEvents.HISTORY_PROCESS) {
                                streamed = true
                                onReasoningUpdate(reasoning)
                            }
                        } to null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: NodeExecutionFailure) {
                        null to failure
                    } catch (failure: ModelRequestFailure) {
                        null to NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.MODEL_REQUEST,
                            retryable = failure.retryable).also { if (failure.serialFallback) serialFallback = true }
                    } catch (_: Exception) {
                        null to NodeExecutionFailure(ref, "纯模型动作执行失败", RecoveryFailureKind.ACTION, retryable = false)
                    }
                    received.send(candidate)
                } }
                var valid: ValidatedCandidate? = null
                for (candidateIndex in 0 until width) {
                    val (outcome, failure) = received.receive()
                    if (outcome != null) {
                        valid = outcome
                        tasks.forEach { it.cancel() }
                        break
                    }
                    if (failure != null) {
                        lastFailure = failure
                        if (!failure.retryable) {
                            tasks.forEach { it.cancel() }
                            break
                        }
                    }
                }
                valid
            }
            received.close()
            if (winner != null) return winner
            if (streamed) onClearStream()
            if (!lastFailure.retryable || lastFailure.kind !in
                setOf(RecoveryFailureKind.MODEL_REQUEST, RecoveryFailureKind.OUTPUT_FORMAT)) break
        }
        throw lastFailure
    }
}
