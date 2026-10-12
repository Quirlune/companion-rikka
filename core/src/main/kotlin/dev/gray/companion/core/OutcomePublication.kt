package dev.gray.companion.core

/** Forms an exact receipt in isolation. The scheduler is the only writer of accepted run state. */
internal class OutcomePublisher(
    private val library: Library,
    private val main: MainCall,
    private val actions: Map<String, RegisteredAction>,
    private val resourceByWorkflow: Map<String, String>,
    private val activeOwnerIds: Set<String>,
    private val turn: Int,
    private val userMessageId: String?,
    private val executionVersion: Int
) {
    private val semantics = ExecutionSemantics(executionVersion)

    fun prepare(
        ref: PlannedNode,
        outcome: ActionOutcome,
        current: Session,
        compiledModelTargets: Set<String>,
        processingHistory: Boolean,
        automaticSegment: HistoryMemorySegment? = null,
        rawModelText: String? = null,
        fullResponseText: String? = null, reasoningSnapshot: ReasoningSnapshot? = null,
        modelFinishReason: String? = null
    ): RecoveryReceipt {
        val library = ExecutionResources.forWorkflow(library, ref.workflow.id, executionVersion)
        val origin = ResultOrigin(resourceByWorkflow.getValue(ref.workflow.id), ref.workflow.id,
            ref.node.id, ref.node.resultCategory)
        if (semantics.independentContextOrder && outcome.injectionWrites.any { it.block != null && (it.contextOrder ?: 1) < 1 })
            throw NodeExecutionFailure(ref, "上下文顺序须为正整数", RecoveryFailureKind.CONFIGURATION, retryable = false)
        val patches = outcome.injectionWrites.map { patch ->
            val target = if (patch.block == null) null else injectionTarget(
                main, actions, patch.targetWorkflowId, patch.targetNodeId, patch.targetBlockId, executionVersion)
            patch to target
        }
        if (patches.any { (patch, target) ->
                val prior = current.promptInjections.firstOrNull {
                    it.id == patch.id && it.origin.resourceId == origin.resourceId
                }
                (patch.block != null && target == null) ||
                    (prior != null && "${prior.targetWorkflowId}/${prior.targetNodeId}" in compiledModelTargets) ||
                    (target != null && target.key in compiledModelTargets)
            }) {
            throw NodeExecutionFailure(ref, "提示词注入目标无效或目标模型已编译",
                RecoveryFailureKind.CONFIGURATION, retryable = false)
        }

        val state = current.state.toMutableMap()
        val results = current.results.toMutableMap()
        val origins = current.resultOrigins.toMutableMap()
        val history = current.macroHistory.toMutableMap()
        val heads = current.currentOnlyMacros.toMutableMap()
        val unavailable = current.unavailableResultKeys.toMutableSet()
        fun readSession(includeMasked: Boolean = false): Session {
            val visible = results.filter { (key, _) -> (includeMasked || key !in unavailable) &&
                (origins[key]?.resourceId?.let { it in activeOwnerIds } != false) }
            return current.copy(state = state, results = visible, resultOrigins = origins.filterKeys { it in visible },
                macroHistory = history.toMap(), unavailableResultKeys = if (includeMasked) emptySet() else unavailable.toSet(),
                pendingRun = if (includeMasked) null else current.pendingRun, currentOnlyMacros = heads.toMap())
        }

        val ownAddress = "result.${ref.node.outputKey}"
        val values = linkedMapOf<String, String>()
        outcome.macroWrites.forEach { (address, value) -> values[canonicalWriteAddress(address)!!] = value }
        if (ref.node.actionId == "macro.process" || semantics.currentOnlyMacros)
            outcome.stateWrites.forEach { (key, value) -> values["state.$key"] = value }
        val activations = outcome.activationWrites.mapKeys { (address, _) -> canonicalWriteAddress(address)!! }
        // Activating the action's own output preserves its existing text.
        if (ref.node.actionId != "user.macro.create" && ownAddress !in activations)
            values.putIfAbsent(ownAddress, outcome.text)
        for (address in (values.keys + activations.keys).distinct()) {
            val copy = ref.node.regexOutputCopy
            val modelOwnResult = semantics.retainModelResults && address == ownAddress && ref.action?.descriptor?.usesModel == true
            val record = !semantics.currentOnlyMacros || modelOwnResult || if (copy.enabled && address == "result.${copy.outputKey}")
                copy.recordTrajectory else ref.node.recordTrajectory
            val head = heads[address]
            if (record && head == null && address !in history && address.startsWith("result.")) {
                val key = address.substringAfter("result.")
                results[key]?.let { old -> history[address] = listOf(MacroVersion(
                    version = 0, text = old, origin = origins[key] ?: origin)) }
            }
            // Producer masks govern reads by a new invocation, not the accepted target's enabled flag.
            val snapshot = readSession(includeMasked = semantics.currentOnlyMacros)
            val previous = MacroReferences.currentVersion(library, snapshot, address)
            val value = values[address] ?: (if (address.startsWith("state."))
                state[address.substring(6)] ?: previous?.text
            else previous?.text ?: MacroReferences.baseValue(library, snapshot, address)).orEmpty()
            val enabled = activations[address] ?: previous?.enabled ?: true
            val priorContentOrigin = if (address.startsWith("result.")) {
                previous?.valueOrigin ?: previous?.origin ?: origins[address.substringAfter("result.")]
                    ?.takeIf { MacroReferences.originVisible(library, snapshot, it) }
            } else null
            val contentOrigin = if (address in values) null else priorContentOrigin
            if (record) {
                MacroReferences.publish(history, address, value, enabled, origin, turn, userMessageId, contentOrigin,
                    minimumVersion = head?.version ?: 0, precedingGap = head?.gap,
                    rawText = rawModelText.takeIf { modelOwnResult },
                    fullResponseText = BodyOnlyArchives.fullResponse(fullResponseText, executionVersion).takeIf { modelOwnResult },
                    reasoningSnapshot = BodyOnlyArchives.reasoning(reasoningSnapshot, executionVersion).takeIf { modelOwnResult })
                heads.remove(address)
            } else {
                val number = Math.addExact(maxOf(head?.version ?: 0, history[address]?.lastOrNull()?.version ?: 0), 1)
                heads[address] = MacroHead(number, origin, enabled, turn, userMessageId, contentOrigin,
                    head?.gap ?: MacroGap(turn), value.takeUnless { address.startsWith("result.") || address.startsWith("state.") })
            }
            if (address.startsWith("result.")) {
                val key = address.substringAfter("result.")
                results[key] = value
                origins[key] = contentOrigin ?: origin
                unavailable -= key
            } else if (address.startsWith("state.") && address in values) {
                state[address.substring(6)] = value
            }
        }
        val appends = history.mapNotNull { (address, versions) ->
            val added = versions.drop(current.macroHistory[address].orEmpty().size)
            if (added.isEmpty()) null else address to added
        }.toMap()
        val headWrites = heads.filter { (address, head) -> current.currentOnlyMacros[address] != head }
        val headRemovals = current.currentOnlyMacros.keys - heads.keys
        val publishedAddresses = appends.keys + headWrites.keys
        val historyPatch = if (automaticSegment == null) outcome.historyWrites else
            outcome.historyWrites.copy(segments = outcome.historyWrites.segments + automaticSegment)
        try { if (!historyPatch.isEmpty()) {
            val index = HistoryMemory.sourceIndex(current)
            val nextMemory = HistoryMemory.apply(current.historyMemory, historyPatch)
            val references = historyPatch.segments.flatMap { it.sources } +
                historyPatch.captures.keys.flatMap { HistoryMemory.captureSources(nextMemory, it) }
            require(references.all { HistoryMemory.resolve(it, index) != null }) { "历史写入引用的原文修订不存在" }
        } }
        catch (failure: IllegalArgumentException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false)
        } catch (failure: IllegalStateException) {
            throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false)
        }
        val publication = RecoveryPublication(
            stateWrites = outcome.stateWrites,
            resultWrites = results.filter { (key, value) -> current.results[key] != value || "result.$key" in publishedAddresses },
            resultOrigins = origins.filter { (key, value) -> current.resultOrigins[key] != value || "result.$key" in publishedAddresses },
            macroAppends = appends,
            injectionRemovals = patches.map { (patch, _) -> InjectionIdentity(origin.resourceId, patch.id) },
            injectionAdds = patches.mapNotNull { (patch, target) -> patch.block?.let { block ->
                PromptInjection(patch.id, block, origin.copy(category = ""), target!!.workflowId, target.nodeId,
                    patch.scope, if (patch.scope == InjectionScope.TURN) userMessageId else null,
                    pluginOwnerIds = if (semantics.bodyOnlyArchives)
                        PluginParticipation.injectionOwners(library, current, origin.resourceId) else emptyList(),
                    targetBlockId = target.blockId,
                    queueOrder = if (executionVersion == 10) ref.priority else 0,
                    contextOrder = if (semantics.independentContextOrder) patch.contextOrder ?: 1 else null)
            } },
            historyWrites = historyPatch, headWrites = headWrites, headRemovals = headRemovals,
            userMacroWrites = outcome.userMacroWrites
        )
        val displayKey = ref.node.regexOutputCopy.takeIf { it.enabled && it.display && ref.node.outputSlot == OutputSlot.BODY }
            ?.outputKey ?: ref.node.outputKey
        val text = results[displayKey].orEmpty()
        val reply = if (ref.node.actionId != "user.macro.create" && ref.node.visible && !processingHistory && text.isNotBlank())
            ChatMessage(role = Role.ASSISTANT, text = text, outputSlot = ref.node.outputSlot,
                turnId = userMessageId, workflowId = ref.workflow.id, nodeId = ref.node.id,
                speakerName = library.characters.firstOrNull { it.id == current.characterId }?.name,
                rawText = rawModelText.takeIf { ref.node.outputSlot == OutputSlot.BODY },
                reasoningSnapshot = BodyOnlyArchives.displayReasoning(reasoningSnapshot, executionVersion),
                modelFinishReason = boundedModelFinishReason(modelFinishReason).takeIf(::modelResponseWasTruncated))
        else null
        return RecoveryReceipt(ref.key, outcome, reply, publication)
    }
}
