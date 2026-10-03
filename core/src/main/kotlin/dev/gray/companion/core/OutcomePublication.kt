package dev.gray.companion.core

/** Forms an exact receipt in isolation. The scheduler is the only writer of accepted run state. */
internal class OutcomePublisher(
    private val library: Library,
    private val main: MainCall,
    private val actions: Map<String, RegisteredAction>,
    private val resourceByWorkflow: Map<String, String>,
    private val activeOwnerIds: Set<String>,
    private val turn: Int,
    private val userMessageId: String?
) {
    fun prepare(
        ref: PlannedNode,
        outcome: ActionOutcome,
        current: Session,
        compiledModelTargets: Set<String>,
        processingHistory: Boolean
    ): RecoveryReceipt {
        val origin = ResultOrigin(resourceByWorkflow.getValue(ref.workflow.id), ref.workflow.id,
            ref.node.id, ref.node.resultCategory)
        val patches = outcome.injectionWrites.map { patch ->
            val target = if (patch.block == null) null else injectionTarget(
                main, actions, patch.targetWorkflowId, patch.targetNodeId)
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
        val unavailable = current.unavailableResultKeys.toMutableSet()
        fun readSession(): Session {
            val visible = results.filter { (key, _) -> key !in unavailable &&
                (origins[key]?.resourceId?.let { it in activeOwnerIds } != false) }
            return current.copy(state = state, results = visible, resultOrigins = origins.filterKeys { it in visible },
                macroHistory = history.toMap(), unavailableResultKeys = unavailable.toSet())
        }

        val ownAddress = "result.${ref.node.outputKey}"
        val values = linkedMapOf<String, String>()
        outcome.macroWrites.forEach { (address, value) -> values[canonicalWriteAddress(address)!!] = value }
        if (ref.node.actionId == "macro.process") outcome.stateWrites.forEach { (key, value) -> values["state.$key"] = value }
        val activations = outcome.activationWrites.mapKeys { (address, _) -> canonicalWriteAddress(address)!! }
        // Activating the action's own output preserves its existing text.
        if (ownAddress !in activations) values.putIfAbsent(ownAddress, outcome.text)
        for (address in (values.keys + activations.keys).distinct()) {
            if (address !in history && address.startsWith("result.")) {
                val key = address.substringAfter("result.")
                results[key]?.let { old -> history[address] = listOf(MacroVersion(
                    version = 0, text = old, origin = origins[key] ?: origin)) }
            }
            val snapshot = readSession()
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
            MacroReferences.publish(history, address, value, enabled, origin, turn, userMessageId, contentOrigin)
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
        val publication = RecoveryPublication(
            stateWrites = outcome.stateWrites,
            resultWrites = results.filter { (key, value) -> current.results[key] != value || "result.$key" in appends },
            resultOrigins = origins.filter { (key, value) -> current.resultOrigins[key] != value || "result.$key" in appends },
            macroAppends = appends,
            injectionRemovals = patches.map { (patch, _) -> InjectionIdentity(origin.resourceId, patch.id) },
            injectionAdds = patches.mapNotNull { (patch, target) -> patch.block?.let { block ->
                PromptInjection(patch.id, block, origin.copy(category = ""), target!!.workflowId, target.nodeId,
                    patch.scope, if (patch.scope == InjectionScope.TURN) userMessageId else null)
            } }
        )
        val text = results[ref.node.outputKey].orEmpty()
        val reply = if (ref.node.visible && !processingHistory && text.isNotBlank())
            ChatMessage(role = Role.ASSISTANT, text = text, outputSlot = ref.node.outputSlot,
                turnId = userMessageId, workflowId = ref.workflow.id, nodeId = ref.node.id,
                speakerName = library.characters.firstOrNull { it.id == current.characterId }?.name)
        else null
        return RecoveryReceipt(ref.key, outcome, reply, publication)
    }
}
