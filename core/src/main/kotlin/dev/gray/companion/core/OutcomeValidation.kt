package dev.gray.companion.core

import kotlinx.coroutines.CancellationException

internal class NodeExecutionFailure(
    val ref: PlannedNode,
    val detail: String = "",
    val kind: RecoveryFailureKind = RecoveryFailureKind.ACTION,
    val source: String? = null,
    val retryable: Boolean = true
) : RuntimeException()

/** Provenance is captured by the engine, never supplied as a handler effect. */
internal data class ValidatedCandidate(val outcome: ActionOutcome, val rawModelText: String? = null,
    val fullResponseText: String? = null, val reasoningSnapshot: ReasoningSnapshot? = null,
    val provenanceNote: String? = null, val modelFinishReason: String? = null)

/** Pure candidate checks. No state, versions, replies or events are published here. */
internal class OutcomeValidator(private val library: Library, session: Session) {
    private val initialSession = session
    private fun tiles(resources: Library, session: Session) =
        (resources.mainCalls.firstOrNull { it.id == session.mainCallId }?.tiles.orEmpty() +
            resources.loadedCallGroups(session).flatMap { it.tiles }).filter { it.enabled }

    fun hasDirectContract(address: String, context: ActionContext? = null): Boolean =
        ExecutionSemantics(context?.executionVersion ?: ExecutionSemantics.CURRENT).legacyTiles &&
        tiles(context?.library ?: library, context?.session ?: initialSession).any {
            MacroOutputContracts.sourceAddress(it) == address
        }

    /** A manual recovery candidate bypasses the model gateway, so parse it once before processing. */
    fun validateManual(ref: PlannedNode, context: ActionContext, text: String): ActionOutcome {
        val library = context.library ?: ExecutionResources.forWorkflow(library, ref.workflow.id, context.executionVersion)
        val body = if (ExecutionSemantics(context.executionVersion).bodyOnlyArchives &&
            ref.action?.descriptor?.usesModel == true) try {
            ReasoningTextParser.parse(text, ref.node.reasoningParsing ?: library.reasoningParsing).text
        } catch (failure: ReasoningFormatFailure) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
        } else text
        if (ExecutionSemantics(context.executionVersion).bodyOnlyArchives &&
            ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL && body.isBlank())
            throw NodeExecutionFailure(ref, "模型候选缺少正文内容", RecoveryFailureKind.OUTPUT_FORMAT)
        return validate(ref, context, ActionOutcome(body))
    }

    fun validate(ref: PlannedNode, context: ActionContext, outcome: ActionOutcome): ActionOutcome {
        val library = context.library ?: ExecutionResources.forWorkflow(library, ref.workflow.id, context.executionVersion)
        if (ref.node.actionId == "card.define") {
            CardDefinitions.validateNode(ref.node, "node.${ref.node.id}", context.executionVersion)
                .firstOrNull { it.error }?.let {
                    throw NodeExecutionFailure(ref, it.message, RecoveryFailureKind.CONFIGURATION, retryable = false)
                }
            if (outcome.hasEffects()) throw NodeExecutionFailure(ref, "卡片行为只能发布自身结果",
                RecoveryFailureKind.CONFIGURATION, retryable = false)
            CardDisplays.validateOutcome(ref.node, outcome.text, context)?.let {
                throw NodeExecutionFailure(ref, it, RecoveryFailureKind.OUTPUT_FORMAT)
            }
        }
        if (ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL && outcome.hasEffects())
            throw NodeExecutionFailure(ref, "纯模型动作返回了状态、宏、注入或事件副作用",
                RecoveryFailureKind.CONFIGURATION, retryable = false)
        if (ref.node.actionId == "user.macro.create" && (outcome.text.isNotEmpty() ||
                outcome.stateWrites.isNotEmpty() || outcome.macroWrites.isNotEmpty() ||
                outcome.activationWrites.isNotEmpty() || outcome.injectionWrites.isNotEmpty() ||
                outcome.emittedEvents.isNotEmpty() || !outcome.historyWrites.isEmpty() ||
                ref.node.regexOutputCopy.enabled))
            throw NodeExecutionFailure(ref, "创建用户宏行为只能登记定义", RecoveryFailureKind.CONFIGURATION, retryable = false)
        if (ref.node.actionId == "user.macro.create" || outcome.userMacroWrites.isNotEmpty()) {
            val definition = ref.node.userMacro
            if (ref.node.actionId != "user.macro.create" || definition == null ||
                outcome.userMacroWrites != mapOf(definition.shortcut to definition) ||
                UserInputMacros.validate(definition).any { it.error })
                throw NodeExecutionFailure(ref, "用户宏定义写入无效", RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val summaryTag = GlobalMemory.summaryXmlTag(library, ref.workflow.id, ref.node.id, context.executionVersion)
        val processedText = if (summaryTag != null) {
            if (!SummaryXml.validTag(summaryTag)) throw NodeExecutionFailure(ref, "摘要 XML 标签名无效",
                RecoveryFailureKind.CONFIGURATION, retryable = false)
            try { outcome.copy(text = SummaryXml.extract(outcome.text, summaryTag)) }
            catch (failure: IllegalArgumentException) {
                throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
            }
        } else if (ref.node.outputProcessing.isEmpty()) outcome else try {
            require(outcome.stateWrites.isEmpty() && outcome.macroWrites.isEmpty() &&
                outcome.activationWrites.isEmpty() && outcome.userMacroWrites.isEmpty())
            val resolver = MacroResolver(library, requireNotNull(context.session), context.input, context.executionVersion)
            outcome.copy(text = TextProcessing.apply(ref.node.outputProcessing,
                outcome.text, context.state, context.results, context.executionVersion) { resolver.read(it) })
        } catch (failure: OutputFormatException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
        } catch (_: Exception) {
            throw NodeExecutionFailure(ref, "文本处理失败", RecoveryFailureKind.OUTPUT_FORMAT)
        }
        val processed = try { if (summaryTag != null) processedText else RegexOutputCopies.derive(ref.node, processedText) }
        catch (failure: OutputFormatException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
        } catch (failure: IllegalArgumentException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val values = linkedMapOf<String, String>()
        if (ref.node.actionId != "user.macro.create") values["result.${ref.node.outputKey}"] = processed.text
        processed.macroWrites.forEach { (address, value) ->
            val canonical = canonicalWriteAddress(address)
                ?: throw NodeExecutionFailure(ref, "宏写入地址无效", RecoveryFailureKind.CONFIGURATION, retryable = false)
            values[canonical] = value
        }
        processed.stateWrites.forEach { (key, value) -> values["state.$key"] = value }
        for (tile in if (ExecutionSemantics(context.executionVersion).legacyTiles)
            tiles(library, context.session ?: initialSession) else emptyList()) {
            val source = MacroOutputContracts.sourceAddress(tile) ?: continue
            val text = values[source] ?: continue
            val problem = MacroOutputContracts.validate(tile, text) ?: continue
            val kind = if (problem.startsWith("磁贴正则配置无效") || problem.startsWith("HTML 模板配置无效")) RecoveryFailureKind.CONFIGURATION
                else RecoveryFailureKind.OUTPUT_FORMAT
            throw NodeExecutionFailure(ref, problem, kind, "${tile.title} (${tile.id})", kind != RecoveryFailureKind.CONFIGURATION)
        }
        return processed
    }

    /** A visible draft only. The accepted candidate still passes strict full-output validation. */
    fun previewBody(ref: PlannedNode, context: ActionContext, rawBody: String): String? {
        if (rawBody.length > MAX_PREVIEW_BODY_LENGTH) return null
        val processed = try { validate(ref, context, ActionOutcome(rawBody)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        val copy = ref.node.regexOutputCopy
        return if (copy.enabled && copy.display && ref.node.outputSlot == OutputSlot.BODY)
            processed.macroWrites["result.${copy.outputKey}"] else processed.text
    }

    private companion object { const val MAX_PREVIEW_BODY_LENGTH = 1_000_000 }
}
