package dev.gray.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

class WorkflowEngine(
    private val actions: ActionRegistry = ActionRegistry.defaults(),
    private val maxConcurrentNodes: Int = 4
) {
    init { require(maxConcurrentNodes > 0) }

    private data class Prepared(val ref: PlannedNode, val context: ActionContext)
    private data class Completed(val ref: PlannedNode, val candidate: ValidatedCandidate, val replay: Boolean = false) {
        val outcome: ActionOutcome get() = candidate.outcome
    }

    suspend fun run(
        library: Library,
        session: Session,
        input: String,
        gateway: ModelGateway,
        onUpdate: suspend (RunUpdate) -> Unit = {}
    ): RunResult = execute(library, session, input, gateway, null, null, onUpdate)

    suspend fun resume(
        library: Library,
        currentSession: Session,
        gateway: ModelGateway,
        recovery: RunRecovery,
        decision: RecoveryDecision,
        onUpdate: suspend (RunUpdate) -> Unit = {}
    ): RunResult {
        if (recovery.formatVersion != 1 || !ExecutionSemantics.supports(recovery.executionVersion))
            return conflict(recovery, "恢复断点版本不受支持；已接受的数据可保留，请结束此断点后开始新一轮")
        if (decision.nodeKey !in recovery.failures.map { it.nodeKey } ||
            currentSession.pendingRun?.id != recovery.id ||
            (currentSession.id != recovery.sessionId && !recovery.regenerating))
            return conflict(recovery, "恢复断点与当前会话不一致")
        val revised = repairedResources(recovery.frozenResources, library, decision.nodeKey)
            ?: return conflict(recovery, "调用结构或环境已变化；只能编辑失败调用的提示词块")
        if (decision.kind != RecoveryDecisionKind.SKIP) {
            val target = (revised.mainCalls.flatMap { it.configuredWorkflows() } +
                revised.groups.flatMap { it.workflows })
                .firstOrNull { it.id == decision.nodeKey.substringBefore('/') }
                ?.nodes?.firstOrNull { it.id == decision.nodeKey.substringAfter('/') }
            val addresses = target?.let { node ->
                node.declaredOutputAddresses(actions.snapshot()[node.actionId]?.descriptor)
            }.orEmpty()
            if (recovery.accepted.any { receipt -> (receipt.publication.macroAppends.keys +
                    receipt.publication.headWrites.keys + receipt.publication.headRemovals).any { it in addresses } })
                return conflict(recovery, "失败调用与已接受结果写入同一宏地址，不能安全原位续跑")
        }
        return execute(revised, recovery.initialSession, recovery.input, gateway, recovery.copy(frozenResources = revised), decision, onUpdate)
    }

    private fun conflict(recovery: RunRecovery, message: String): RunResult {
        val failure = recovery.failures.firstOrNull()
        val trace = RunTrace(failure?.workflowId.orEmpty(), failure?.nodeId.orEmpty(), failure?.title.orEmpty(), "error", message)
        val conflicts = if (failure == null) listOf(RecoveryFailure("", "", "", "恢复断点",
            RecoveryFailureKind.CONFLICT, message, retryable = false)) else recovery.failures.map { current ->
            if (current.nodeKey == failure.nodeKey) current.copy(kind = RecoveryFailureKind.CONFLICT,
                reason = message, retryable = false) else current
        }
        return RunResult(recovery.state, recovery.results, recovery.replies, recovery.traces + trace,
            RunStatus.PAUSED, recovery.resultOrigins, recovery.macroHistory, recovery.promptInjections,
            recovery.historyDigests, recovery.copy(traces = recovery.traces + trace, failures = conflicts),
            recovery.unavailableKeys, recovery.historyMemory, recovery.currentOnlyMacros)
    }

    private fun freezeResources(library: Library, session: Session): Library {
        val main = library.mainCalls.first { it.id == session.mainCallId }
        val groups = library.loadedCallGroups(session, main)
        val packages = library.loadedPromptPackages(session, main)
        val referencedProfiles = (main.configuredWorkflows() +
            groups.flatMap { it.workflows }).flatMap { flow ->
            flow.nodes.map { it.generation.profileId ?: flow.modelProfileId ?: library.selectedProfileId }
        }.filterNotNull().toSet() + listOfNotNull(library.selectedProfileId)
        val profiles = library.profiles.filter { it.id in referencedProfiles }
        val connectionIds = profiles.mapNotNull { it.apiConnectionId }.toSet()
        return library.copy(
            characters = library.characters.filter { it.id == session.characterId },
            mainCalls = listOf(ExtensionCommands.withoutMetadata(main)), extensions = emptyList(), packages = packages, groups = groups, profiles = profiles,
            connections = library.connections.filter { it.id in connectionIds },
            sessions = emptyList(), selectedSessionId = null,
            chatFolders = emptyList(), savedChats = emptyList(), configurationEdits = emptyList(),
            personas = library.personas.filter { it.id == session.personaId || it.id == library.selectedPersonaId }
        )
    }

    /** Only target prompt blocks may change execution inputs; editor metadata is not an input. */
    private fun repairedResources(frozen: Library, live: Library, nodeKey: String): Library? {
        val workflowId = nodeKey.substringBefore('/')
        val nodeId = nodeKey.substringAfter('/', "")
        if (nodeId.isEmpty()) return null
        val frozenMain = frozen.mainCalls.singleOrNull() ?: return null
        val liveMain = live.mainCalls.firstOrNull { it.id == frozenMain.id } ?: return null
        var found = false
        fun capturedMetadata(block: PromptBlock, captured: PromptBlock?): PromptBlock = block.copy(
            slug = captured?.slug.orEmpty(), tagIds = captured?.tagIds.orEmpty()
        )
        fun patch(workflow: Workflow, source: Workflow?): Workflow {
            if (workflow.id != workflowId || source == null) return workflow
            return workflow.copy(nodes = workflow.nodes.map { node ->
                if (node.id != nodeId) node else {
                    found = true
                    // New tags may not exist in the captured catalogue. Keep editor-only metadata
                    // captured (or absent for a new block), while applying all saved runtime fields.
                    node.copy(blocks = source.nodes.firstOrNull { it.id == nodeId }?.blocks?.map { block ->
                        capturedMetadata(block, node.blocks.firstOrNull { it.id == block.id })
                    } ?: node.blocks)
                }
            })
        }
        val patchedMain = frozenMain.copy(workflows = frozenMain.workflows.map { old ->
            patch(old, liveMain.workflows.firstOrNull { it.id == old.id })
        }, history = frozenMain.history.copy(processor = frozenMain.history.processor.copy(
            workflow = patch(frozenMain.history.processor.workflow,
                liveMain.history.processor.workflow.takeIf { it.id == workflowId }),
            aggregationWorkflow = frozenMain.history.processor.aggregationWorkflow?.let { old ->
                patch(old, liveMain.history.processor.aggregationWorkflow?.takeIf { it.id == workflowId })
            })))
        val patchedGroups = frozen.groups.map { old ->
            val source = live.groups.firstOrNull { it.id == old.id } ?: return null
            old.copy(workflows = old.workflows.map { flow ->
                patch(flow, source.workflows.firstOrNull { it.id == flow.id })
            }).also { if (MacroNaming.runtimeShape(it) != MacroNaming.runtimeShape(source)) return null }
        }
        val updatedConnections = frozen.connections.map { old ->
            val source = live.connections.firstOrNull { it.id == old.id } ?: return null
            if (old.copy(maxConcurrentRequests = source.maxConcurrentRequests) != source) return null
            source
        }
        if (!found || MacroNaming.runtimeShape(patchedMain) != MacroNaming.runtimeShape(liveMain)) return null
        if (frozen.characters.any { old ->
                live.characters.firstOrNull { it.id == old.id }?.let { MacroNaming.runtimeShape(it) } != MacroNaming.runtimeShape(old)
            } || frozen.packages.any { old ->
                live.packages.firstOrNull { it.id == old.id }?.let { MacroNaming.runtimeShape(it) } != MacroNaming.runtimeShape(old)
            } ||
            frozen.profiles.any { old -> live.profiles.firstOrNull { it.id == old.id } != old } ||
            frozen.personas.any { old ->
                live.personas.firstOrNull { it.id == old.id }?.let { MacroNaming.runtimeShape(it) } != MacroNaming.runtimeShape(old)
            } ||
            frozen.user != live.user || frozen.selectedPersonaId != live.selectedPersonaId ||
            frozen.selectedProfileId != live.selectedProfileId) return null
        return frozen.copy(mainCalls = listOf(patchedMain), groups = patchedGroups,
            connections = updatedConnections)
    }

    private suspend fun execute(
        library: Library,
        session: Session,
        input: String,
        gateway: ModelGateway,
        previous: RunRecovery?,
        decision: RecoveryDecision?,
        onUpdate: suspend (RunUpdate) -> Unit
    ): RunResult {
        val traces = mutableListOf<RunTrace>()
        val executionVersion = previous?.executionVersion ?: ExecutionSemantics.CURRENT
        val semantics = ExecutionSemantics(executionVersion)
        val legacy = semantics.legacyProcessing
        val updateMutex = Mutex()
        suspend fun trace(ref: PlannedNode?, status: String, detail: String) {
            val item = RunTrace(ref?.workflow?.id.orEmpty(), ref?.node?.id.orEmpty(), ref?.node?.title.orEmpty(), status, detail.take(160))
            traces += item
            updateMutex.withLock { onUpdate(RunUpdate(trace = item, workflowId = item.workflowId)) }
        }
        fun failed() = RunResult(session.state, session.results, emptyList(), traces.toList(), RunStatus.FAILED,
            session.resultOrigins, session.macroHistory, session.promptInjections, session.historyDigests,
            unavailableResultKeys = session.unavailableResultKeys, historyMemory = session.historyMemory,
            currentOnlyMacros = session.currentOnlyMacros)

        val validation = LibraryValidator.validate(library, executionVersion).firstOrNull { it.error }
        if (validation != null) {
            trace(null, "error", validation.message)
            return failed()
        }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        if (main == null) {
            trace(null, "error", "主调用不存在")
            return failed()
        }
        if (library.characters.none { it.id == session.characterId }) {
            trace(null, "error", "角色卡不存在")
            return failed()
        }
        if (main.workflows.none { it.enabled && it.effectiveTrigger() == RuntimeEvents.USER_MESSAGE && it.nodes.any { node -> node.enabled } }) {
            trace(null, "error", "主调用缺少 user.message 入口")
            return failed()
        }
        val stagedState = session.state.toMutableMap()
        val groups = library.loadedCallGroups(session, main)
        groups.forEach { group -> group.stateDefaults.forEach { (key, value) -> stagedState.putIfAbsent(key, value) } }
        val processor = main.history.processor
        val initialHistory = HistoryContext(library, session, main, legacyProcessing = legacy, executionVersion = executionVersion)
        val processingCandidate = initialHistory.pendingCandidate()
        initialHistory.issues.forEach { trace(null, "history", it.message) }
        val workflows = main.configuredWorkflows().filter {
            semantics.hierarchicalMemory || it.id != processor.aggregationWorkflow?.id
        } + groups.flatMap { it.workflows }
        val availableActions = actions.snapshot()
        val (plan, planningIssue) = RunPlan.create(workflows, availableActions, executionVersion)
        if (planningIssue != null || plan == null) {
            trace(null, "error", planningIssue?.message ?: "运行计划无法创建")
            return failed()
        }
        fun resultKeys(node: WorkflowNode): Set<String> = buildSet {
            add(node.outputKey)
            if (node.regexOutputCopy.enabled) add(node.regexOutputCopy.outputKey)
            node.declaredWriteAddress(availableActions[node.actionId]?.descriptor)
                ?.takeIf { it.startsWith("result.") }?.substringAfter("result.")?.let(::add)
        }
        val activeKeys = workflows.flatMap { it.nodes }.flatMap(::resultKeys).toSet()
        val inactiveGroupKeys = library.groups.filter { group -> groups.none { it.id == group.id } }
            .flatMap { it.workflows }.flatMap { it.nodes }.flatMap(::resultKeys).toSet() - activeKeys
        val stagedResults = session.results.toMutableMap()
        val stagedOrigins = session.resultOrigins.toMutableMap()
        val stagedHistory = session.macroHistory.toMutableMap()
        val stagedHeads = session.currentOnlyMacros.toMutableMap()
        val stagedDigests = session.historyDigests.toMutableMap()
        var stagedMemory = session.historyMemory
        val currentUserMessageId = session.messages.lastOrNull { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }?.id
        val currentTurn = MacroReferences.currentTurn(session)
        val acceptedReceipts = previous?.accepted?.associateByTo(linkedMapOf()) { it.nodeKey } ?: linkedMapOf()
        val oldAcceptedKeys = previous?.accepted?.mapTo(mutableSetOf()) { it.nodeKey } ?: mutableSetOf()
        val replayedOldKeys = mutableSetOf<String>()
        val priorFailures = previous?.failures?.associateByTo(linkedMapOf()) { it.nodeKey } ?: linkedMapOf()
        val lockedFailures = previous?.failures?.mapTo(mutableSetOf()) { it.nodeKey } ?: mutableSetOf()
        val environments = previous?.environments?.associateByTo(linkedMapOf()) { it.id } ?: linkedMapOf()
        val preparedRecords = previous?.prepared?.associateByTo(linkedMapOf()) { it.nodeKey } ?: linkedMapOf()
        val explicitSkips = previous?.skippedKeys?.toMutableSet() ?: mutableSetOf()
        val inactiveKeys = previous?.inactiveKeys?.toMutableSet() ?: mutableSetOf()
        val admittedIds = previous?.admittedWorkflowIds?.toMutableSet() ?: mutableSetOf()
        val declinedIds = previous?.declinedWorkflowIds?.toMutableSet() ?: mutableSetOf()
        val completedWorkflowIds = previous?.completedWorkflowIds?.toMutableSet() ?: mutableSetOf()
        val dispatchedCompletions = mutableSetOf<String>()
        if (decision?.kind == RecoveryDecisionKind.SKIP) {
            explicitSkips += decision.nodeKey
            priorFailures.remove(decision.nodeKey)
        }
        val stagedInjections = session.promptInjections.filter {
            it.scope == InjectionScope.SESSION || it.turnId == currentUserMessageId
        }.toMutableList()
        // Pending and skipped producers cannot lend their previous-turn text to this run.
        // The saved result remains intact if that producer is not executed.
        val maskedKeys = (inactiveGroupKeys + session.unavailableResultKeys).toMutableSet()
        if (processingCandidate == null) (processor.workflow.nodes + processor.aggregationWorkflow?.nodes.orEmpty())
            .forEach { maskedKeys += resultKeys(it) }
        workflows.filter { !it.enabled }.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
        workflows.filter { it.enabled }.flatMap { it.nodes.filter { node -> !node.enabled } }
            .forEach { maskedKeys += resultKeys(it) }
        fun maskViews(nodes: List<WorkflowNode>) {
            val ids = nodes.filter { it.actionId == "history.view.copy" }.mapNotNull {
                it.arguments["targetId"]?.trim()?.takeIf { id -> id.isNotBlank() }
            }
            if (ids.isNotEmpty()) stagedMemory = stagedMemory.copy(unavailableViewIds = stagedMemory.unavailableViewIds + ids)
        }
        maskViews(workflows.filter { !it.enabled }.flatMap { it.nodes } +
            workflows.filter { it.enabled }.flatMap { it.nodes.filter { node -> !node.enabled } } +
            library.groups.filter { group -> groups.none { it.id == group.id } }.flatMap { it.workflows }.flatMap { it.nodes })
        val resourceByWorkflow = buildMap {
            main.configuredWorkflows().forEach { put(it.id, main.id) }
            groups.forEach { group -> group.workflows.forEach { put(it.id, group.id) } }
            if (processor.workflow.enabled) put(processor.workflow.id, main.id)
        }
        val activeOwnerIds = setOf(main.id) + groups.map { it.id }
        val invocationPreparer = InvocationPreparer(library)
        val outcomeValidator = OutcomeValidator(library, session)
        val outcomePublisher = OutcomePublisher(library, main, availableActions, resourceByWorkflow,
            activeOwnerIds, currentTurn, currentUserMessageId, executionVersion)
        fun visibleResults(): Map<String, String> = stagedResults.filter { (key, _) ->
            key !in maskedKeys && (stagedOrigins[key]?.resourceId?.let { it in activeOwnerIds } != false)
        }
        fun snapshot(state: Map<String, String>, results: Map<String, String>): Session = session.copy(
            state = state, results = results, resultOrigins = stagedOrigins.filterKeys { it in results },
            macroHistory = stagedHistory.toMap(), promptInjections = stagedInjections.toList(),
            historyDigests = stagedDigests.toMap(), unavailableResultKeys = maskedKeys.toSet(), historyMemory = stagedMemory,
            currentOnlyMacros = stagedHeads.toMap()
        )
        val replies = mutableListOf<ChatMessage>()
        var bodyDisplayReceipt = previous?.bodyDisplayReceipt
        fun publishedReplies(): List<ChatMessage> = replies.map { reply ->
            bodyDisplayReceipt?.takeIf { it.id == reply.id } ?: reply
        }
        val replyRank = mutableMapOf<String, Int>()
        var nextReplyRank = 0
        fun orderReplies() {
            replies.sortBy { reply -> replyRank["${reply.workflowId}/${reply.nodeId}"] ?: Int.MAX_VALUE }
        }
        val recoveryBase = previous ?: RunRecovery(
            id = newId(), sessionId = session.id, turnId = currentUserMessageId.orEmpty(), input = input,
            checkpoint = MessageOperations.captureCheckpoint(session), initialSession = session.copy(pendingRun = null),
            frozenResources = freezeResources(library, session), executionVersion = ExecutionSemantics.CURRENT
        )
        fun replayIncomplete(): Boolean = previous != null && !replayedOldKeys.containsAll(oldAcceptedKeys)
        fun recoverySnapshot(): RunRecovery = recoveryBase.copy(
            accepted = acceptedReceipts.values.toList(), environments = environments.values.toList(),
            prepared = preparedRecords.values.toList(),
            failures = priorFailures.values.toList(), skippedKeys = explicitSkips.toSet(),
            inactiveKeys = inactiveKeys.toSet(), admittedWorkflowIds = admittedIds.toSet(),
            declinedWorkflowIds = declinedIds.toSet(),
            unavailableKeys = if (replayIncomplete()) previous!!.unavailableKeys else maskedKeys.toSet(),
            state = if (replayIncomplete()) previous!!.state else stagedState.toMap(),
            results = if (replayIncomplete()) previous!!.results else stagedResults.toMap(),
            resultOrigins = if (replayIncomplete()) previous!!.resultOrigins else stagedOrigins.toMap(),
            macroHistory = if (replayIncomplete()) previous!!.macroHistory else stagedHistory.toMap(),
            promptInjections = if (replayIncomplete()) previous!!.promptInjections else stagedInjections.toList(),
            historyDigests = if (replayIncomplete()) previous!!.historyDigests else stagedDigests.toMap(),
            replies = if (replayIncomplete()) previous!!.replies else publishedReplies(), traces = traces.toList(),
            historyMemory = if (replayIncomplete()) previous!!.historyMemory else stagedMemory,
            completedWorkflowIds = completedWorkflowIds.toSet(),
            currentOnlyMacros = if (replayIncomplete()) previous!!.currentOnlyMacros else stagedHeads.toMap(),
            bodyDisplayReceipt = bodyDisplayReceipt
        )
        fun partialResult(): RunResult {
            val checkpoint = recoverySnapshot()
            return RunResult(checkpoint.state, checkpoint.results, checkpoint.replies,
                checkpoint.traces, RunStatus.PAUSED, checkpoint.resultOrigins, checkpoint.macroHistory,
                checkpoint.promptInjections, checkpoint.historyDigests, checkpoint, checkpoint.unavailableKeys,
                checkpoint.historyMemory, checkpoint.currentOnlyMacros)
        }
        suspend fun progress() { updateMutex.withLock { onUpdate(RunUpdate(progress = partialResult())) } }
        val executedOutput = mutableMapOf<String, String>()
        val compiledModelTargets = mutableSetOf<String>()
        val candidateExecutor = NodeCandidateExecutor(library, gateway, outcomeValidator,
            Semaphore(maxConcurrentNodes), semantics)
        val visited = mutableSetOf<String>()
        var eventCount = 0
        val emittedEvents = ArrayDeque<String>()

        suspend fun perform(item: Prepared): Completed {
            val ref = item.ref
            val pureModel = ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL
            acceptedReceipts[ref.key]?.let { return Completed(ref, ValidatedCandidate(it.outcome, it.reply?.rawText), true) }
            if (ref.key in lockedFailures && ref.key != decision?.nodeKey) {
                val saved = priorFailures.getValue(ref.key)
                throw NodeExecutionFailure(ref, saved.reason, saved.kind, saved.source, saved.retryable)
            }
            if (ref.key == decision?.nodeKey && decision?.kind == RecoveryDecisionKind.MANUAL) {
                if (!pureModel || decision.manualText == null)
                    throw NodeExecutionFailure(ref, "此调用不能手动提供候选文本", RecoveryFailureKind.CONFIGURATION, retryable = false)
                return Completed(ref, ValidatedCandidate(outcomeValidator.validate(ref, item.context, ActionOutcome(decision.manualText))))
            }
            val candidate = candidateExecutor.execute(ref, item.context,
                correction = decision?.takeIf { it.nodeKey == ref.key }?.correction,
                onReasoningUpdate = { reasoning ->
                    updateMutex.withLock { onUpdate(RunUpdate(nodeId = ref.node.id, visible = true,
                        workflowId = ref.workflow.id, outputSlot = ref.node.outputSlot,
                        turnId = currentUserMessageId, reasoningSnapshot = reasoning)) }
                },
                onClearStream = {
                    updateMutex.withLock { onUpdate(RunUpdate(nodeId = ref.node.id,
                        text = "", visible = true, workflowId = ref.workflow.id,
                        outputSlot = ref.node.outputSlot, turnId = currentUserMessageId, replace = true)) }
                })
            return Completed(ref, candidate)
        }

        suspend fun executeEvent(event: String): Boolean {
            if (++eventCount > 32) {
                trace(null, "error", "事件链超过上限")
                return false
            }
            val eventPlan = plan.events[event] ?: return true
            val processingEvent = event == RuntimeEvents.HISTORY_PROCESS
            val eventInput = if (processingEvent) processingCandidate?.text.orEmpty() else input
            val freshWorkflows = eventPlan.waves.flatten().map { it.workflow }.distinctBy { it.id }
                .filter { visited.add(it.id) }
            val freshIds = freshWorkflows.mapTo(mutableSetOf()) { it.id }
            val admittedWorkflows = mutableSetOf<String>()
            val evaluatedWorkflows = mutableSetOf<String>()
            // Mask every scheduled output before evaluating any trigger. Historical macro.*
            // versions remain an explicit, independent read path.
            freshWorkflows.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
            maskViews(freshWorkflows.flatMap { it.nodes })
            val skipped = mutableSetOf<String>()
            val outcomes = mutableMapOf<String, ActionOutcome>()
            for ((waveIndex, wave) in eventPlan.waves.withIndex()) {
                val waveState = stagedState.toMap()
                val waveResults = visibleResults()
                val waveOrigins = stagedOrigins.filterKeys { it in waveResults }
                val waveSession = snapshot(waveState, waveResults).copy(messages = session.messages + replies)
                // An UPDATE edits its own prior accepted value; other pending producers stay masked.
                val targetWorld = if (semantics.capturedMacroTargets) snapshot(waveState, stagedResults.toMap())
                    .copy(unavailableResultKeys = emptySet(), pendingRun = null) else null
                fun captureMacroTarget(ref: PlannedNode): MacroTargetSnapshot? {
                    if (targetWorld == null || ref.node.actionId != "macro.process") return null
                    val address = ref.node.declaredWriteAddress(ref.action?.descriptor) ?: return null
                    val version = MacroReferences.currentVersion(library, targetWorld, address)
                    val hiddenHead = address in targetWorld.currentOnlyMacros && version == null &&
                        (address.startsWith("result.") || address.startsWith("state."))
                    val text = when {
                        hiddenHead -> null
                        version != null -> version.text
                        address.startsWith("state.") -> targetWorld.state[address.substring(6)]
                        else -> MacroReferences.baseValue(library, targetWorld, address)
                    }
                    return MacroTargetSnapshot(address, text, version?.enabled != false)
                }
                val environmentId = "$event:$waveIndex"
                environments.putIfAbsent(environmentId, RecoveryEnvironment(environmentId, event,
                    waveSession.copy(pendingRun = null), eventInput))
                val waveHistory = HistoryContext(library, waveSession, main, processingEvent, processingCandidate, legacy, executionVersion = executionVersion)
                val waveEnvironment = InvocationEnvironment(waveSession, eventInput, waveHistory, currentUserMessageId, executionVersion)
                val waveIssues = mutableListOf<Issue>()
                val waveRenderer = TemplateRenderer(
                    loadedResources(library, waveSession, waveState, waveResults, eventInput, waveIssues, waveOrigins, waveHistory, executionVersion),
                    eventInput, waveResults, waveState, waveIssues, waveOrigins, waveSession, waveHistory, executionVersion
                )
                // Admit once at the first ready wave of this level, using one shared snapshot
                // for peers. Higher levels can observe the completed lower levels.
                wave.map { it.workflow }.distinctBy { it.id }.filter {
                    it.id in freshIds && evaluatedWorkflows.add(it.id)
                }.forEach { workflow ->
                    if (workflow.id in admittedIds) admittedWorkflows += workflow.id
                    else if (workflow.id in declinedIds) Unit
                    else if (checkedCondition(workflow.condition, "workflow.${workflow.id}", waveState, waveResults,
                        eventInput, waveIssues) { waveRenderer.read(it, "workflow.${workflow.id}.condition.expression") } &&
                        RecognitionEvaluator.matches(workflow.recognition) { template ->
                            waveRenderer.render(template, "workflow.${workflow.id}.recognition")
                        }) {
                        admittedWorkflows += workflow.id
                        admittedIds += workflow.id
                    } else declinedIds += workflow.id
                }
                val active = mutableListOf<PlannedNode>()
                for (ref in wave) {
                    if (ref.workflow.id !in admittedWorkflows) continue
                    if (ref.key !in replyRank) replyRank[ref.key] = nextReplyRank++
                    if (ref.key in acceptedReceipts) active += ref
                    else if (ref.key in explicitSkips) {
                        skipped += ref.key
                        trace(ref, "skipped", "已明确跳过此调用")
                    } else if (ref.key in inactiveKeys) {
                        skipped += ref.key
                        trace(ref, "skipped", "节点条件不满足")
                    } else if (ref.node.dependsOn.any { "${ref.workflow.id}/$it" in skipped }) {
                        skipped += ref.key
                        if (ref.node.dependsOn.any { "${ref.workflow.id}/$it" in inactiveKeys } &&
                            ref.node.dependsOn.none { "${ref.workflow.id}/$it" in explicitSkips || "${ref.workflow.id}/$it" in priorFailures })
                            inactiveKeys += ref.key
                        trace(ref, "skipped", "依赖节点未执行")
                    } else if (!ref.node.enabled || !checkedCondition(ref.node.condition,
                        "workflow.${ref.workflow.id}.node.${ref.node.id}", waveState, waveResults, eventInput, waveIssues) {
                            waveRenderer.read(it, "workflow.${ref.workflow.id}.node.${ref.node.id}.condition.expression")
                        } ||
                        (ref.node.recognition != null && !RecognitionEvaluator.matches(ref.node.recognition) { template ->
                            waveRenderer.render(template, "workflow.${ref.workflow.id}.node.${ref.node.id}.recognition")
                        })) {
                        skipped += ref.key
                        inactiveKeys += ref.key
                        trace(ref, "skipped", "节点未启用或条件不满足")
                    } else active += ref
                }
                waveIssues.firstOrNull { it.error }?.let { trace(null, "error", it.message); return false }
                if (active.isEmpty()) continue
                val addressesByRef = active.associateWith { ref ->
                    ref.node.declaredOutputAddresses(ref.action?.descriptor)
                }
                val conflictingAddresses = addressesByRef.values.flatten().groupingBy { it }.eachCount()
                    .filterValues { it > 1 }.keys
                if (conflictingAddresses.isNotEmpty()) {
                    val conflicting = addressesByRef.filterValues { addresses -> addresses.any { it in conflictingAddresses } }.keys
                    conflicting.forEach { ref ->
                        priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                            ref.node.title, RecoveryFailureKind.CONFIGURATION, "并行节点写入同一宏地址", retryable = false)
                        skipped += ref.key
                        trace(ref, "error", "并行节点写入同一宏地址")
                    }
                    active.removeAll(conflicting)
                }
                if (active.isEmpty()) continue
                val prepared = mutableListOf<Prepared>()
                val activeModelTargets = active.filter { it.action?.descriptor?.usesModel == true }.mapTo(mutableSetOf()) { it.key }
                val injectionKeys = mutableSetOf<Pair<String, String>>()
                val renderedInjections = mutableMapOf<String, RenderedInvocationInput>()
                suspend fun preparationFailure(ref: PlannedNode, reason: String,
                                               kind: RecoveryFailureKind = RecoveryFailureKind.CONFIGURATION) {
                    priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                        ref.node.title, kind, reason, retryable = false)
                    skipped += ref.key
                    trace(ref, "error", reason)
                }
                // Resolve injection targets before compiling any model prompt in this wave.
                for (ref in active) {
                    if (ref.key in acceptedReceipts) continue
                    val descriptor = ref.action?.descriptor ?: continue
                    val idField = descriptor.injectionIdField ?: continue
                    val rendered = try {
                        invocationPreparer.renderInput(ref, waveEnvironment)
                    } catch (failure: NodeExecutionFailure) {
                        preparationFailure(ref, failure.detail, failure.kind)
                        continue
                    }
                    val arguments = rendered.arguments
                    val issues = rendered.issues + descriptor.validate(arguments, "node.${ref.node.id}")
                    issues.firstOrNull { it.error }?.let { preparationFailure(ref, it.message) }
                    if (ref.key in skipped) continue
                    val id = arguments[idField]?.trim().orEmpty()
                    val resourceId = resourceByWorkflow.getValue(ref.workflow.id)
                    if (!injectionKeys.add(resourceId to id)) {
                        preparationFailure(ref, "并行节点修改同一提示词注入")
                        continue
                    }
                    val existing = stagedInjections.firstOrNull { it.id == id && it.origin.resourceId == resourceId }
                    val targets = mutableListOf<InjectionTarget>()
                    if (existing != null) targets += InjectionTarget(existing.targetWorkflowId.orEmpty(), existing.targetNodeId.orEmpty())
                    if (!descriptor.removesInjection) {
                        val target = injectionTarget(main, availableActions,
                            descriptor.injectionTargetWorkflowField?.let(arguments::get),
                            descriptor.injectionTargetNodeField?.let(arguments::get))
                        if (target == null) {
                            preparationFailure(ref, "提示词注入目标不存在或不是主调用模型节点")
                            continue
                        }
                        targets += target
                    }
                    if (targets.any { it.key in activeModelTargets || it.key in compiledModelTargets }) {
                        preparationFailure(ref, "提示词注入必须早于目标模型编译")
                        continue
                    }
                    renderedInjections[ref.key] = rendered.copy(issues = issues)
                }
                for (ref in active) {
                    if (ref.key in skipped) continue
                    val node = ref.node
                    if (ref.key in acceptedReceipts) {
                        prepared += Prepared(ref, ActionContext(node, eventInput, emptyList(), emptyMap(),
                            waveState, waveResults, null, gateway, {}, session = waveSession, library = library,
                            workflowId = ref.workflow.id, executionVersion = executionVersion))
                        continue
                    }
                    val frozenPreparation = if (ref.key == decision?.nodeKey) preparedRecords[ref.key] else null
                    val macroTarget = frozenPreparation?.macroTarget ?: captureMacroTarget(ref)
                    val frozenEnvironment = frozenPreparation?.let { environments[it.environmentId] }
                    val contextSession = frozenEnvironment?.session ?: waveSession
                    val contextState = contextSession.state
                    val contextResults = contextSession.results
                    val contextHistory = if (frozenEnvironment == null) waveHistory else
                        HistoryContext(library, contextSession, main, processingEvent, processingCandidate, legacy, executionVersion = executionVersion)
                    val preparation = try {
                        invocationPreparer.prepare(ref, InvocationEnvironment(contextSession,
                            frozenEnvironment?.input ?: eventInput, contextHistory, currentUserMessageId, executionVersion),
                            frozenPreparation, renderedInjections[ref.key])
                    } catch (failure: NodeExecutionFailure) {
                        preparationFailure(ref, failure.detail, failure.kind)
                        continue
                    }
                    val dependencyOutputs = node.dependsOn.associateWith { dependency ->
                        outcomes.getValue("${ref.workflow.id}/$dependency")
                    }
                    val context = ActionContext(
                        node, preparation.input, preparation.messages, preparation.arguments, contextState, contextResults,
                        preparation.profile, gateway, { delta ->
                            if (node.visible && !processingEvent && (semantics.provisionalProcessedOutput || node.outputProcessing.isEmpty() &&
                                !(node.regexOutputCopy.enabled && node.regexOutputCopy.display && node.outputSlot == OutputSlot.BODY))) updateMutex.withLock {
                                onUpdate(RunUpdate(nodeId = node.id, text = delta, visible = true,
                                    workflowId = ref.workflow.id, outputSlot = node.outputSlot, turnId = currentUserMessageId))
                            }
                        }, dependencyOutputs, contextSession, library, ref.workflow.id, executionVersion, macroTarget
                    )
                    prepared += Prepared(ref, context)
                    if (ref.key !in acceptedReceipts && ref.key !in preparedRecords) preparedRecords[ref.key] =
                        RecoveryPrepared(ref.key, environmentId, preparation.input, preparation.arguments, preparation.profile, macroTarget)
                }
                compiledModelTargets += activeModelTargets
                // Persist the pre-request world and exact inputs before a cancellable model call.
                prepared.filter { it.ref.key !in acceptedReceipts && it.ref.key !in priorFailures }.forEach { item ->
                    priorFailures[item.ref.key] = RecoveryFailure(item.ref.key, item.ref.workflow.id,
                        item.ref.node.id, item.ref.node.title, RecoveryFailureKind.ACTION, "调用已暂停，等待继续",
                        provisional = true)
                }
                if (prepared.any { it.ref.key !in acceptedReceipts }) progress()
                suspend fun recordFailure(failure: NodeExecutionFailure) {
                    val category = failure.detail.ifEmpty {
                        if (failure.ref.action?.descriptor?.usesModel == true) "模型调用失败" else "动作执行失败"
                    }
                    priorFailures[failure.ref.key] = RecoveryFailure(failure.ref.key, failure.ref.workflow.id,
                        failure.ref.node.id, failure.ref.node.title, failure.kind, category, failure.source, failure.retryable)
                    skipped += failure.ref.key
                    trace(failure.ref, "error", category)
                }
                val usedAddresses = mutableSetOf<String>()
                val usedPatches = mutableSetOf<InjectionIdentity>()
                suspend fun publishCompleted(completed: List<Completed>) {
                    currentCoroutineContext().ensureActive()
                    val validCompleted = mutableListOf<Completed>()
                    for (item in completed) {
                        val addresses = (listOf("result.${item.ref.node.outputKey}") + item.outcome.macroWrites.keys +
                            item.outcome.activationWrites.keys + item.outcome.stateWrites.keys.map { "state.$it" })
                            .mapNotNull(::canonicalWriteAddress).distinct() + item.outcome.historyWrites.views.map { "history-view:${it.viewId}" }
                        val patchIds = item.outcome.injectionWrites.map { patch ->
                            InjectionIdentity(resourceByWorkflow.getValue(item.ref.workflow.id), patch.id)
                        }
                        val invalid = addresses.any { it in usedAddresses } ||
                            patchIds.size != patchIds.distinct().size || patchIds.any { it in usedPatches } ||
                            item.outcome.emittedEvents.any { it.isBlank() || (if (legacy) it in RuntimeEvents.reserved else RuntimeEvents.isReserved(it)) } ||
                            (item.outcome.macroWrites.keys + item.outcome.activationWrites.keys).any {
                                (if (item.ref.node.actionId == "macro.process") canonicalWriteAddress(it)
                                 else MacroReferences.canonicalAddress(it)) == null
                            }
                        if (invalid) {
                            priorFailures[item.ref.key] = RecoveryFailure(item.ref.key, item.ref.workflow.id,
                                item.ref.node.id, item.ref.node.title, RecoveryFailureKind.CONFIGURATION,
                                "并行节点写入冲突或结果地址无效", retryable = false)
                            skipped += item.ref.key
                            trace(item.ref, "error", "并行节点写入冲突或结果地址无效")
                        } else {
                            usedAddresses += addresses
                            usedPatches += patchIds
                            validCompleted += item
                        }
                    }
                    val published = mutableListOf<RecoveryReceipt>()
                    for (item in validCompleted) {
                        currentCoroutineContext().ensureActive()
                        val receipt = if (item.replay) acceptedReceipts.getValue(item.ref.key) else try {
                            val automaticSegment = if (!legacy && processingEvent && processingCandidate != null &&
                                item.ref.workflow.id == processor.workflow.id && item.ref.node.id ==
                                    processor.workflow.nodes.lastOrNull { it.enabled && it.outputKey == processor.outputKey }?.id &&
                                item.outcome.text.isNotBlank()) HistoryMemory.segmentFromSources("历史摘要", item.outcome.text,
                                    processingCandidate.sources, processor.workflow.id) else null
                            outcomePublisher.prepare(item.ref, item.outcome,
                                snapshot(stagedState.toMap(), stagedResults.toMap()).copy(messages = session.messages + replies),
                                compiledModelTargets, processingEvent, automaticSegment, item.candidate.rawModelText,
                                item.candidate.fullResponseText, item.candidate.reasoningSnapshot)
                        } catch (failure: NodeExecutionFailure) {
                            recordFailure(failure)
                            continue
                        }
                        if (!item.replay) item.candidate.provenanceNote?.let { trace(item.ref, "warning", it) }
                        val publication = receipt.publication
                        val nextMemory = try { HistoryMemory.apply(stagedMemory, publication.historyWrites) }
                        catch (failure: IllegalArgumentException) {
                            recordFailure(NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        } catch (failure: IllegalStateException) {
                            recordFailure(NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        }
                        fun currentVersion(address: String): Int = maxOf(stagedHeads[address]?.version ?: 0,
                            stagedHistory[address]?.lastOrNull()?.version ?: 0)
                        if (item.replay && (publication.macroAppends.any { (address, appends) ->
                                // A legacy synthetic version zero may precede the first accepted publication.
                                appends.isNotEmpty() && (address in stagedHeads || stagedHistory[address].orEmpty().isNotEmpty()) &&
                                    appends.first().version <= currentVersion(address)
                            } || publication.headWrites.any { (address, head) -> head.version <= currentVersion(address) })) {
                            recordFailure(NodeExecutionFailure(item.ref, "已接受宏版本与修复后的写入冲突",
                                RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        }
                        val publicationContext = currentCoroutineContext()
                        publicationContext.ensureActive()
                        // Once accepted, its in-memory receipt and durable progress form one short
                        // cancellation-safe boundary. Model requests and preparation stay cancellable.
                        withContext(NonCancellable) {
                            publicationContext.ensureActive()
                            for ((address, appends) in publication.macroAppends) {
                                stagedHistory[address] = stagedHistory[address].orEmpty() + appends
                            }
                            publication.headRemovals.forEach(stagedHeads::remove)
                            stagedHeads.putAll(publication.headWrites)
                            stagedState.putAll(publication.stateWrites)
                            stagedResults.putAll(publication.resultWrites)
                            stagedOrigins.putAll(publication.resultOrigins)
                            publication.resultWrites.keys.forEach { maskedKeys -= it }
                            publication.injectionRemovals.forEach { identity -> stagedInjections.removeAll {
                                it.id == identity.id && it.origin.resourceId == identity.resourceId
                            } }
                            stagedInjections += publication.injectionAdds
                            stagedMemory = nextMemory
                            outcomes[item.ref.key] = receipt.outcome
                            executedOutput[item.ref.key] = stagedResults[item.ref.node.outputKey].orEmpty()
                            receipt.reply?.let { replies += it; orderReplies() }
                            published += receipt
                            if (item.replay) {
                                replayedOldKeys += item.ref.key
                                if (!replayIncomplete()) priorFailures.keys.removeAll(acceptedReceipts.keys)
                                traces += RunTrace(item.ref.workflow.id, item.ref.node.id, item.ref.node.title, "done", "")
                            } else {
                                acceptedReceipts[item.ref.key] = receipt
                                priorFailures.remove(item.ref.key)
                                lockedFailures.remove(item.ref.key)
                                if (replayIncomplete()) priorFailures[item.ref.key] = RecoveryFailure(item.ref.key,
                                    item.ref.workflow.id, item.ref.node.id, item.ref.node.title,
                                    RecoveryFailureKind.ACTION, "已接受，正在恢复后续步骤", provisional = true)
                                if (!replayIncomplete()) priorFailures.keys.removeAll(acceptedReceipts.keys)
                                progress()
                                if (semantics.provisionalProcessedOutput) receipt.reply?.let { reply ->
                                    updateMutex.withLock { onUpdate(RunUpdate(nodeId = item.ref.node.id, text = reply.text,
                                        visible = true, workflowId = item.ref.workflow.id, outputSlot = reply.outputSlot,
                                        turnId = currentUserMessageId, replace = true, reasoningSnapshot = reply.reasoningSnapshot)) }
                                }
                            }
                        }
                        if (!item.replay) trace(item.ref, "done", "")
                    }
                    published.flatMap { it.outcome.emittedEvents }.forEach { emittedEvents.addLast(it) }
                }
                suspend fun attempt(item: Prepared): Pair<Completed?, NodeExecutionFailure?> = try {
                    perform(item) to null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: NodeExecutionFailure) {
                    null to failure
                } catch (failure: OutputFormatException) {
                    null to NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
                } catch (failure: IllegalArgumentException) {
                    null to NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.CONFIGURATION, retryable = false)
                } catch (failure: IllegalStateException) {
                    null to NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.ACTION, retryable = false)
                } catch (_: Exception) {
                    null to NodeExecutionFailure(item.ref)
                }
                // Pure model nodes have a predeclared, unique result address. Publish each
                // completed sibling before a slower peer finishes its retry budget.
                val earlyPublish = prepared.size > 1 && prepared.all {
                    it.ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL && it.ref.node.dependsOn.isEmpty() &&
                        it.ref.node.outputKey.isNotBlank()
                }
                if (earlyPublish) supervisorScope {
                    val received = Channel<Pair<Completed?, NodeExecutionFailure?>>(prepared.size)
                    val tasks = prepared.map { item -> async { received.send(attempt(item)) } }
                    repeat(prepared.size) {
                        val (completed, failure) = received.receive()
                        if (failure != null) recordFailure(failure)
                        if (completed != null) publishCompleted(listOf(completed))
                    }
                    tasks.awaitAll()
                    received.close()
                } else {
                    val attempted = supervisorScope { prepared.map { item -> async { attempt(item) } }.awaitAll() }
                    attempted.mapNotNull { it.second }.forEach { recordFailure(it) }
                    publishCompleted(attempted.mapNotNull { it.first })
                }
            }
            if (!legacy) for (workflow in freshWorkflows) {
                val enabled = workflow.nodes.filter { it.enabled }.map { "${workflow.id}/${it.id}" }
                val complete = workflow.id in admittedIds && enabled.any { it in outcomes } &&
                    enabled.all { it in outcomes || it in inactiveKeys } &&
                    enabled.none { it in explicitSkips || it in priorFailures }
                if (complete && dispatchedCompletions.add(workflow.id)) {
                    if (workflow.id !in completedWorkflowIds) {
                        val completionContext = currentCoroutineContext()
                        completionContext.ensureActive()
                        withContext(NonCancellable) {
                            completionContext.ensureActive()
                            completedWorkflowIds += workflow.id
                            progress()
                        }
                    }
                    val completedEvent = RuntimeEvents.workflowCompleted(workflow.id)
                    if (completedEvent in plan.events) emittedEvents.addLast(completedEvent)
                }
            }
            return true
        }

        suspend fun drain(initialEvent: String): Boolean {
            emittedEvents.addLast(initialEvent)
            while (emittedEvents.isNotEmpty()) {
                val event = emittedEvents.removeFirst()
                if (!executeEvent(event)) return false
            }
            return true
        }

        // History processing is a fixed pre-stage, using a captured eligible source.
        if (processingCandidate != null) {
            if (!drain(RuntimeEvents.HISTORY_PROCESS)) return failed()
            val selected = processor.workflow.nodes.lastOrNull { it.enabled && it.outputKey == processor.outputKey &&
                "${processor.workflow.id}/${it.id}" in executedOutput }
            if (legacy && selected != null) {
                val text = executedOutput["${processor.workflow.id}/${selected.id}"].orEmpty()
                if (text.isNotBlank()) stagedDigests[main.id] = HistoryDigest(text,
                    processingCandidate.throughMessageId, processingCandidate.coveredFloors, processor.workflow.id)
                else trace(null, "skipped", "历史处理输出为空，保留原摘要")
            }
        }
        // Preparation finishes before user dispatch; reply.completed runs once after the interaction chain.
        if (!drain(RuntimeEvents.TURN_PREPARE)) return failed()
        if (!drain(RuntimeEvents.USER_MESSAGE)) return failed()
        if (replies.isNotEmpty() && !drain(RuntimeEvents.REPLY_COMPLETED)) return failed()
        currentCoroutineContext().ensureActive()
        if (priorFailures.isNotEmpty()) {
            val paused = partialResult()
            progress()
            return paused
        }
        if (semantics.bodyDisplayTemplates && main.bodySourceTileId != null && bodyDisplayReceipt == null) {
            val primaryWorkflowId = main.primaryWorkflowId ?: main.workflows.firstOrNull()?.id
            val target = replies.lastOrNull { it.workflowId == primaryWorkflowId &&
                it.outputSlot == OutputSlot.BODY && !it.deleted && it.text.isNotBlank() }
            if (target != null) {
                val finalSession = snapshot(stagedState.toMap(), visibleResults()).copy(messages = session.messages + replies)
                val frozen = MacroDisplay.freezeBody(library, finalSession, main, target, executionVersion)
                if (frozen.error != null) trace(null, "display", frozen.error)
                else {
                    val publicationContext = currentCoroutineContext()
                    withContext(NonCancellable) {
                        publicationContext.ensureActive()
                        bodyDisplayReceipt = frozen.message
                        progress()
                    }
                }
            } else trace(null, "display", "主交互链没有可替换的正文回复")
        }
        return RunResult(stagedState.toMap(), stagedResults.toMap(), publishedReplies(), traces.toList(),
            RunStatus.SUCCESS, stagedOrigins.toMap(), stagedHistory.toMap(), stagedInjections.toList(), stagedDigests.toMap(),
            unavailableResultKeys = maskedKeys.toSet(), historyMemory = stagedMemory, currentOnlyMacros = stagedHeads.toMap())
    }
}
