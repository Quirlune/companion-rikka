package dev.gray.companion.core

internal class NodeExecutionFailure(
    val ref: PlannedNode,
    val detail: String = "",
    val kind: RecoveryFailureKind = RecoveryFailureKind.ACTION,
    val source: String? = null,
    val retryable: Boolean = true
) : RuntimeException()

/** Provenance is captured by the engine, never supplied as a handler effect. */
internal data class ValidatedCandidate(val outcome: ActionOutcome, val rawModelText: String? = null)

/** Pure candidate checks. No state, versions, replies or events are published here. */
internal class OutcomeValidator(private val library: Library, session: Session) {
    private val tiles = (library.mainCalls.firstOrNull { it.id == session.mainCallId }?.tiles.orEmpty() +
        library.loadedCallGroups(session).flatMap { it.tiles }).filter { it.enabled }

    fun hasDirectContract(address: String): Boolean = tiles.any {
        MacroOutputContracts.sourceAddress(it) == address
    }

    fun validate(ref: PlannedNode, context: ActionContext, outcome: ActionOutcome): ActionOutcome {
        if (ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL && outcome.hasEffects())
            throw NodeExecutionFailure(ref, "纯模型动作返回了状态、宏、注入或事件副作用",
                RecoveryFailureKind.CONFIGURATION, retryable = false)
        val processedText = if (ref.node.outputProcessing.isEmpty()) outcome else try {
            require(outcome.stateWrites.isEmpty() && outcome.macroWrites.isEmpty() &&
                outcome.activationWrites.isEmpty())
            val resolver = MacroResolver(library, requireNotNull(context.session), context.input, context.executionVersion)
            outcome.copy(text = TextProcessing.apply(ref.node.outputProcessing,
                outcome.text, context.state, context.results) { resolver.read(it) })
        } catch (failure: OutputFormatException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
        } catch (_: Exception) {
            throw NodeExecutionFailure(ref, "文本处理失败", RecoveryFailureKind.OUTPUT_FORMAT)
        }
        val processed = try { RegexOutputCopies.derive(ref.node, processedText) }
        catch (failure: OutputFormatException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
        } catch (failure: IllegalArgumentException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val values = linkedMapOf("result.${ref.node.outputKey}" to processed.text)
        processed.macroWrites.forEach { (address, value) ->
            val canonical = canonicalWriteAddress(address)
                ?: throw NodeExecutionFailure(ref, "宏写入地址无效", RecoveryFailureKind.CONFIGURATION, retryable = false)
            values[canonical] = value
        }
        processed.stateWrites.forEach { (key, value) -> values["state.$key"] = value }
        for (tile in tiles) {
            val source = MacroOutputContracts.sourceAddress(tile) ?: continue
            val text = values[source] ?: continue
            val problem = MacroOutputContracts.validate(tile, text) ?: continue
            val kind = if (problem.startsWith("磁贴正则配置无效") || problem.startsWith("HTML 模板配置无效")) RecoveryFailureKind.CONFIGURATION
                else RecoveryFailureKind.OUTPUT_FORMAT
            throw NodeExecutionFailure(ref, problem, kind, "${tile.title} (${tile.id})", kind != RecoveryFailureKind.CONFIGURATION)
        }
        return processed
    }
}
