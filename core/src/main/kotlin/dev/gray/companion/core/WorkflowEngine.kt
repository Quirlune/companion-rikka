package dev.gray.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

class WorkflowEngine(
    private val actions: ActionRegistry = ActionRegistry.defaults(),
    private val maxConcurrentNodes: Int = 4
) {
    init { require(maxConcurrentNodes > 0) }

    private data class Prepared(val ref: PlannedNode, val context: ActionContext)
    private data class Completed(val ref: PlannedNode, val candidate: ValidatedCandidate, val replay: Boolean = false) {
        val outcome: ActionOutcome get() = candidate.outcome
    }
    private sealed interface SchedulerSignal {
        data class Event(val name: String) : SchedulerSignal
        data class Finished(val success: Boolean) : SchedulerSignal
    }
    private enum class PreparationStatus { ACCEPTED, INACTIVE, FAILED }
    private data class WriterStamp(val event: String, val workflowId: String, val nodeKey: String, val queued: Boolean, val priority: Int)

    suspend fun run(
        library: Library,
        session: Session,
        input: String,
        gateway: ModelGateway,
        onUpdate: suspend (RunUpdate) -> Unit = {}
    ): RunResult = execute(CompletedWorlds.executionLibrary(library, session), session, input, gateway, null, null, onUpdate)

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
        val revised = if (ExecutionSemantics(recovery.executionVersion).scopedMemoryResources) recovery.frozenResources
            else repairedResources(recovery.frozenResources, library, decision.nodeKey, recovery.initialSession.mainCallId)
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
                    receipt.publication.headWrites.keys + receipt.publication.headRemovals +
                    receipt.publication.userMacroWrites.keys.map { "user-macro:$it" }).any { it in addresses } })
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
            recovery.unavailableKeys, recovery.historyMemory, recovery.currentOnlyMacros,
            userMacros = recovery.userMacros)
    }

    private fun freezeResources(library: Library, session: Session, executionVersion: Int): Library {
        if (ExecutionSemantics(executionVersion).completedPureReplay) return CapturedResourceClosure.capture(library, session, executionVersion = executionVersion)
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
            mainCalls = listOf(ExtensionCommands.withoutMetadata(main)), extensions = emptyList(), memoryConfig = null, packages = packages, groups = groups, profiles = profiles,
            connections = library.connections.filter { it.id in connectionIds },
            sessions = emptyList(), selectedSessionId = null,
            chatFolders = emptyList(), savedChats = emptyList(), configurationEdits = emptyList(),
            personas = library.personas.filter { persona ->
                persona.id == session.personaId || persona.id == library.selectedPersonaId ||
                    library.characters.any { card -> card.id == session.characterId && card.boundPersonaId == persona.id }
            }
        )
    }

    /** Only target prompt blocks may change execution inputs; editor metadata is not an input. */
    private fun repairedResources(frozen: Library, live: Library, nodeKey: String, mainCallId: String): Library? {
        val workflowId = nodeKey.substringBefore('/')
        val nodeId = nodeKey.substringAfter('/', "")
        if (nodeId.isEmpty()) return null
        val frozenMain = frozen.mainCalls.firstOrNull { it.id == mainCallId } ?: return null
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
        if (frozen.mainCalls.filterNot { it.id == mainCallId }.any { old ->
                live.mainCalls.firstOrNull { it.id == old.id }?.let { MacroNaming.runtimeShape(it) } != MacroNaming.runtimeShape(old)
            } || frozen.extensions != live.extensions.filter { candidate -> frozen.extensions.any { it.id == candidate.id } }) return null
        return frozen.copy(mainCalls = frozen.mainCalls.map { if (it.id == mainCallId) patchedMain else it }, groups = patchedGroups,
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
        val executionVersion = previous?.executionVersion ?: session.executableWorld?.executionVersion ?: ExecutionSemantics.CURRENT
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
            currentOnlyMacros = session.currentOnlyMacros, userMacros = session.userMacros)

        val validation = ExecutionResources.views(library).flatMap { LibraryValidator.validate(it, executionVersion) }.firstOrNull { it.error }
        if (validation != null) {
            trace(null, "error", validation.message)
            return failed()
        }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        if (main == null) {
            trace(null, "error", "主调用不存在")
            return failed()
        }
        if (session.executableWorld?.recipes.orEmpty().flatMap { it.targets }.any {
                it.key in session.unavailableResultKeys || it.key !in session.results
            }) {
            trace(null, "error", "旧分支廉价结果尚未重建，请先完成分支恢复")
            return failed()
        }
        if (library.characters.none { it.id == session.characterId }) {
            trace(null, "error", "角色卡不存在")
            return failed()
        }
        if ((!semantics.completedPureReplay || !main.specialDefault) && main.workflows.none {
                it.enabled && it.effectiveTrigger() == RuntimeEvents.USER_MESSAGE && it.nodes.any { node -> node.enabled }
            }) {
            trace(null, "error", "主调用缺少 user.message 入口")
            return failed()
        }
        val stagedState = session.state.toMutableMap()
        val groups = library.loadedCallGroups(session, main)
        groups.forEach { group -> group.stateDefaults.forEach { (key, value) -> stagedState.putIfAbsent(key, value) } }
        val memoryBinding = MemoryPlugins.binding(library, session, main, executionVersion)
        val processor = memoryBinding?.processor
        val initialHistory = HistoryContext(library, session, main, legacyProcessing = legacy, executionVersion = executionVersion)
        val processingCandidate = initialHistory.pendingCandidate()
        initialHistory.issues.forEach { trace(null, "history", it.message) }
        val workflows = (main.configuredWorkflows().filter {
            semantics.hierarchicalMemory || it.id != main.history.processor.aggregationWorkflow?.id
        } + groups.flatMap { it.workflows }).map { workflow ->
            if (MemoryPlugins.workflowActive(library, session, workflow.id, executionVersion)) workflow
            else workflow.copy(enabled = false)
        }
        val availableActions = actions.snapshot()
        val capturedWorld = if (semantics.completedPureReplay) CompletedWorlds.capture(library, session, input, executionVersion) else null
        val completedCapture = capturedWorld?.let(::CompletedWorldCapture)
        // Participation affects this plan only; disabling a plugin never mutates shared nodes.
        val participatingWorkflows = workflows.map { workflow -> workflow.copy(nodes = workflow.nodes.map { node ->
            if (PluginParticipation.nodeActive(ExecutionResources.forWorkflow(library, workflow.id, executionVersion), session, node)) node else node.copy(enabled = false)
        }) }
        val (plan, planningIssue) = RunPlan.create(participatingWorkflows, availableActions, executionVersion)
        if (planningIssue != null || plan == null) {
            trace(null, "error", planningIssue?.message ?: "运行计划无法创建")
            return failed()
        }
        fun resultKeys(node: WorkflowNode): Set<String> = buildSet {
            if (node.actionId == "user.macro.create") return@buildSet
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
        val stagedUserMacros = session.userMacros.toMutableMap()
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
        if (processingCandidate == null) (processor?.workflow?.nodes.orEmpty() + processor?.aggregationWorkflow?.nodes.orEmpty())
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
            memoryBinding?.let { if (it.processor.workflow.enabled) put(it.processor.workflow.id, it.ownerId) }
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
            currentOnlyMacros = stagedHeads.toMap(), userMacros = stagedUserMacros.toMap(), inactivePaths = emptyList()
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
            checkpoint = MessageOperations.captureCheckpoint(session).let { checkpoint -> checkpoint.copy(
                runtime = checkpoint.runtime.copy(completedWorld = CompletedWorlds.checkpointWorld(capturedWorld))) },
            initialSession = session.copy(pendingRun = null),
            frozenResources = freezeResources(library, session, executionVersion), executionVersion = executionVersion
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
            userMacros = if (replayIncomplete()) previous!!.userMacros else stagedUserMacros.toMap(),
            bodyDisplayReceipt = bodyDisplayReceipt
        )
        fun partialResult(): RunResult {
            val checkpoint = recoverySnapshot()
            return RunResult(checkpoint.state, checkpoint.results, checkpoint.replies,
                checkpoint.traces, RunStatus.PAUSED, checkpoint.resultOrigins, checkpoint.macroHistory,
                checkpoint.promptInjections, checkpoint.historyDigests, checkpoint, checkpoint.unavailableKeys,
                checkpoint.historyMemory, checkpoint.currentOnlyMacros,
                completedWorld = CompletedWorlds.checkpointWorld(capturedWorld), userMacros = checkpoint.userMacros)
        }
        suspend fun progress() { updateMutex.withLock { onUpdate(RunUpdate(progress = partialResult())) } }
        val executedOutput = mutableMapOf<String, String>()
        val compiledModelTargets = mutableSetOf<String>()
        val candidateExecutor = NodeCandidateExecutor(library, gateway, outcomeValidator,
            Semaphore(maxConcurrentNodes), semantics)
        val queuedCandidateExecutor = if (semantics.independentEventLanes)
            NodeCandidateExecutor(library, gateway, outcomeValidator, Semaphore(maxConcurrentNodes), semantics)
        else candidateExecutor
        val visited = mutableSetOf<String>()
        var eventCount = 0
        val emittedEvents = ArrayDeque<String>()
        // Only this actor owns stage, admission, preparation records and accepted receipts.
        // Workers receive immutable contexts and never hold the writer while requesting a model.
        val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        var writerSender: CoroutineContext? = null
        val writerJob = if (semantics.independentEventLanes) CoroutineScope(currentCoroutineContext()).launch {
            for (command in commands) command()
        } else null
        suspend fun <T> write(block: suspend () -> T): T {
            if (writerJob == null) return block()
            val sender = currentCoroutineContext()
            val result = CompletableDeferred<T>()
            commands.send {
                try { sender.ensureActive(); writerSender = sender; result.complete(block()) }
                catch (failure: Throwable) { result.completeExceptionally(failure) }
                finally { writerSender = null }
            }
            return result.await()
        }
        var signals: Channel<SchedulerSignal>? = null
        fun emit(event: String) {
            if (semantics.independentEventLanes) check(signals?.trySend(SchedulerSignal.Event(event))?.isSuccess == true)
            else emittedEvents.addLast(event)
        }
        val preparationDependencies = if (semantics.independentEventLanes)
            plan.preparationDependencies(library, session, main) else emptyMap()
        val preparationCycles = mutableSetOf<String>()
        if (semantics.independentEventLanes) {
            val refs = plan.events.values.flatMap { it.waves.flatten() }.associateBy { it.key }
            val edges = refs.mapValues { (key, ref) -> preparationDependencies[key].orEmpty() +
                ref.node.dependsOn.map { "${ref.workflow.id}/$it" } }
            val finished = mutableSetOf<String>()
            val path = mutableListOf<String>()
            fun visit(key: String) {
                val index = path.indexOf(key)
                if (index >= 0) { preparationCycles += path.drop(index); return }
                if (!finished.add(key)) return
                path += key
                edges[key].orEmpty().forEach(::visit)
                path.removeAt(path.lastIndex)
            }
            edges.keys.forEach(::visit)
        }
        val preparationTerminals = plan.events[RuntimeEvents.TURN_PREPARE]?.waves.orEmpty().flatten()
            .associate { it.key to CompletableDeferred<PreparationStatus>() }
        fun preparationTerminal(key: String, status: PreparationStatus) {
            preparationTerminals[key]?.complete(status)
        }
        val addressWriters = mutableMapOf<String, MutableList<WriterStamp>>()
        val candidateWindows = mutableMapOf<String, WriterStamp>()
        val overlappingCandidates = mutableSetOf<Pair<String, String>>()
        fun pair(a: String, b: String): Pair<String, String> = if (a < b) a to b else b to a
        fun simultaneous(a: WriterStamp, b: WriterStamp): Boolean = a.workflowId != b.workflowId &&
            (pair(a.nodeKey, b.nodeKey) in overlappingCandidates || a.event == b.event &&
                (!a.queued || !b.queued || a.priority == b.priority))

        suspend fun perform(item: Prepared): Completed {
            val ref = item.ref
            val pureModel = ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL
            fun withReads(candidate: ValidatedCandidate): ValidatedCandidate {
                val reads = if (semantics.globalMemory && library.memoryConfig != null) item.context.historyRead
                    ?: item.context.dependencyOutputs.values.mapNotNull { it.historyRead }.takeIf { it.isNotEmpty() }?.let { inherited ->
                        HistoryPromptRead(inherited.flatMap { it.sources }.distinct(), inherited.flatMap { it.childSegmentIds }.distinct())
                    } else null
                return candidate.copy(outcome = candidate.outcome.copy(historyRead = reads))
            }
            val savedReceipt = write { acceptedReceipts[ref.key] }
            savedReceipt?.let { return Completed(ref, ValidatedCandidate(it.outcome, it.reply?.rawText), true) }
            val savedFailure = write { if (ref.key in lockedFailures && ref.key != decision?.nodeKey) priorFailures[ref.key] else null }
            if (savedFailure != null) {
                val saved = savedFailure
                throw NodeExecutionFailure(ref, saved.reason, saved.kind, saved.source, saved.retryable)
            }
            if (ref.key == decision?.nodeKey && decision?.kind == RecoveryDecisionKind.MANUAL) {
                if (!pureModel || decision.manualText == null)
                    throw NodeExecutionFailure(ref, "此调用不能手动提供候选文本", RecoveryFailureKind.CONFIGURATION, retryable = false)
                return Completed(ref, withReads(ValidatedCandidate(outcomeValidator.validateManual(ref, item.context, decision.manualText))))
            }
            val executor = if (ref.workflow.queued) queuedCandidateExecutor else candidateExecutor
            val candidate = executor.execute(ref, item.context,
                correction = decision?.takeIf { it.nodeKey == ref.key }?.correction,
                onProcessedPreview = { preview, reasoning ->
                    updateMutex.withLock { onUpdate(RunUpdate(nodeId = ref.node.id, text = preview,
                        visible = true, workflowId = ref.workflow.id, outputSlot = ref.node.outputSlot,
                        turnId = currentUserMessageId, replace = true, reasoningSnapshot = reasoning)) }
                },
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
            return Completed(ref, withReads(candidate))
        }

        suspend fun executeEvent(event: String, frame: EventPlan? = null, laneTag: String? = null,
                                 dependenciesFailed: Set<String> = emptySet()): Boolean {
            val eventPlan = frame ?: plan.events[event] ?: return true
            if (frame == null && !write {
                    if (++eventCount > 32) { trace(null, "error", "事件链超过上限"); false } else true
                }) return false
            val processingEvent = event == RuntimeEvents.HISTORY_PROCESS
            val eventInput = if (processingEvent) processingCandidate?.text.orEmpty() else input
            val freshWorkflows = write {
                val fresh = eventPlan.waves.flatten().map { it.workflow }.distinctBy { it.id }
                    .filter { visited.add(it.id) }
                fresh.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
                maskViews(fresh.flatMap { it.nodes })
                fresh
            }
            val freshIds = freshWorkflows.mapTo(mutableSetOf()) { it.id }
            val admittedWorkflows = mutableSetOf<String>()
            val evaluatedWorkflows = mutableSetOf<String>()
            val skipped = mutableSetOf<String>()
            val outcomes = mutableMapOf<String, ActionOutcome>()
            for ((waveIndex, wave) in eventPlan.waves.withIndex()) {
                val prepared = write {
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
                    val resources = ExecutionResources.forWorkflow(library, ref.workflow.id, executionVersion)
                    val version = MacroReferences.currentVersion(resources, targetWorld, address)
                    val hiddenHead = address in targetWorld.currentOnlyMacros && version == null &&
                        (address.startsWith("result.") || address.startsWith("state."))
                    val text = when {
                        hiddenHead -> null
                        version != null -> version.text
                        address.startsWith("state.") -> targetWorld.state[address.substring(6)]
                        else -> MacroReferences.baseValue(resources, targetWorld, address)
                    }
                    return MacroTargetSnapshot(address, text, version?.enabled != false)
                }
                val environmentId = if (laneTag == null) "$event:$waveIndex" else "$event:$laneTag:$waveIndex"
                environments.putIfAbsent(environmentId, RecoveryEnvironment(environmentId, event,
                    waveSession.copy(pendingRun = null), eventInput))
                val waveHistory = HistoryContext(library, waveSession, main, processingEvent, processingCandidate, legacy, executionVersion = executionVersion)
                val waveEnvironment = InvocationEnvironment(waveSession, eventInput, waveHistory, currentUserMessageId, executionVersion)
                val waveIssues = mutableListOf<Issue>()
                val renderers = mutableMapOf<String, TemplateRenderer>()
                fun rendererFor(workflowId: String): TemplateRenderer = renderers.getOrPut(workflowId) {
                    val resources = ExecutionResources.forWorkflow(library, workflowId, executionVersion)
                    val history = waveHistory.withResources(resources)
                    TemplateRenderer(
                        loadedResources(resources, waveSession, waveState, waveResults, eventInput, waveIssues, waveOrigins, history, executionVersion),
                        eventInput, waveResults, waveState, waveIssues, waveOrigins, waveSession, history, executionVersion)
                }
                // Admit once at the first ready wave of this level, using one shared snapshot
                // for peers. Higher levels can observe the completed lower levels.
                wave.map { it.workflow }.distinctBy { it.id }.filter {
                    it.id in freshIds && evaluatedWorkflows.add(it.id)
                }.forEach { workflow ->
                    if (workflow.id in admittedIds) admittedWorkflows += workflow.id
                    else if (workflow.id in declinedIds) Unit
                    else if (checkedCondition(workflow.condition, "workflow.${workflow.id}", waveState, waveResults,
                        eventInput, waveIssues) { rendererFor(workflow.id).read(it, "workflow.${workflow.id}.condition.expression") } &&
                        RecognitionEvaluator.matches(workflow.recognition) { template ->
                            rendererFor(workflow.id).render(template, "workflow.${workflow.id}.recognition")
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
                    else if (ref.key in dependenciesFailed) {
                        priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                            ref.node.title, RecoveryFailureKind.CONFIGURATION, "必要的提示词准备未成功，目标模型未执行", retryable = false)
                        skipped += ref.key
                        trace(ref, "skipped", "必要的提示词准备未成功，目标模型未执行")
                    } else if (ref.key in explicitSkips) {
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
                    } else if (!ref.node.enabled || !PluginParticipation.nodeActive(ExecutionResources.forWorkflow(library, ref.workflow.id, executionVersion), waveSession, ref.node) || !checkedCondition(ref.node.condition,
                        "workflow.${ref.workflow.id}.node.${ref.node.id}", waveState, waveResults, eventInput, waveIssues) {
                            rendererFor(ref.workflow.id).read(it, "workflow.${ref.workflow.id}.node.${ref.node.id}.condition.expression")
                        } ||
                        (ref.node.recognition != null && !RecognitionEvaluator.matches(ref.node.recognition) { template ->
                            rendererFor(ref.workflow.id).render(template, "workflow.${ref.workflow.id}.node.${ref.node.id}.recognition")
                        })) {
                        skipped += ref.key
                        inactiveKeys += ref.key
                        trace(ref, "skipped", "节点未启用或条件不满足")
                    } else active += ref
                }
                if (semantics.independentEventLanes) wave.forEach { ref ->
                    if (ref.workflow.id !in admittedWorkflows || ref.key in inactiveKeys)
                        preparationTerminal(ref.key, PreparationStatus.INACTIVE)
                    else if (ref.key in skipped) preparationTerminal(ref.key, PreparationStatus.FAILED)
                }
                waveIssues.firstOrNull { it.error }?.let { issue ->
                    trace(null, "error", issue.message)
                    if (semantics.independentEventLanes) wave.firstOrNull { it.key !in acceptedReceipts }?.let { ref ->
                        priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id, ref.node.title,
                            RecoveryFailureKind.CONFIGURATION, issue.message, retryable = false)
                    }
                    return@write null
                }
                if (active.isEmpty()) return@write emptyList<Prepared>()
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
                        preparationTerminal(ref.key, PreparationStatus.FAILED)
                        trace(ref, "error", "并行节点写入同一宏地址")
                    }
                    active.removeAll(conflicting)
                }
                if (active.isEmpty()) return@write emptyList<Prepared>()
                val prepared = mutableListOf<Prepared>()
                val activeModelTargets = active.filter { it.action?.descriptor?.usesModel == true }.mapTo(mutableSetOf()) { it.key }
                val injectionKeys = mutableSetOf<Pair<String, String>>()
                val renderedInjections = mutableMapOf<String, RenderedInvocationInput>()
                suspend fun preparationFailure(ref: PlannedNode, reason: String,
                                               kind: RecoveryFailureKind = RecoveryFailureKind.CONFIGURATION) {
                    priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                        ref.node.title, kind, reason, retryable = false)
                    skipped += ref.key
                    preparationTerminal(ref.key, PreparationStatus.FAILED)
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
                    if (!semantics.anchoredPromptSlots && descriptor.injectionTargetBlockField?.let(arguments::get)?.isNotBlank() == true) {
                        preparationFailure(ref, "此恢复版本不支持指定注入槽")
                        continue
                    }
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
                            descriptor.injectionTargetNodeField?.let(arguments::get),
                            descriptor.injectionTargetBlockField?.let(arguments::get), executionVersion)
                        if (target == null) {
                            preparationFailure(ref, if (descriptor.injectionTargetBlockField?.let(arguments::get)?.isNotBlank() == true)
                                "注入槽不存在、已停用或不属于目标模型节点" else "提示词注入目标不存在或不是主调用模型节点")
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
                    val resources = ExecutionResources.forWorkflow(library, ref.workflow.id, executionVersion)
                    if (ref.key in acceptedReceipts) {
                        prepared += Prepared(ref, ActionContext(node, eventInput, emptyList(), emptyMap(),
                            waveState, waveResults, null, gateway, {}, session = waveSession, library = resources,
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
                    val dependencyReads = node.dependsOn.mapNotNull { id ->
                        val producer = ref.workflow.nodes.firstOrNull { it.id == id } ?: return@mapNotNull null
                        outcomes["${ref.workflow.id}/$id"]?.historyRead?.let { producer.outputKey to it }
                    }.toMap()
                    val preparation = try {
                        invocationPreparer.prepare(ref, InvocationEnvironment(contextSession,
                            frozenEnvironment?.input ?: eventInput, contextHistory, currentUserMessageId, executionVersion, dependencyReads, macroTarget),
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
                        }, dependencyOutputs, contextSession, resources, ref.workflow.id, executionVersion, macroTarget, preparation.historyRead
                    )
                    prepared += Prepared(ref, context)
                    if (ref.key !in acceptedReceipts && ref.key !in preparedRecords) preparedRecords[ref.key] =
                        RecoveryPrepared(ref.key, environmentId, preparation.input, preparation.arguments, preparation.profile, macroTarget,
                            inputSourceIds = preparation.inputSourceIds)
                }
                compiledModelTargets += activeModelTargets
                // Persist the pre-request world and exact inputs before a cancellable model call.
                prepared.filter { it.ref.key !in acceptedReceipts && it.ref.key !in priorFailures }.forEach { item ->
                    priorFailures[item.ref.key] = RecoveryFailure(item.ref.key, item.ref.workflow.id,
                        item.ref.node.id, item.ref.node.title, RecoveryFailureKind.ACTION, "调用已暂停，等待继续",
                        provisional = true)
                }
                if (prepared.any { it.ref.key !in acceptedReceipts }) progress()
                if (semantics.independentEventLanes) prepared.filter { it.ref.key !in acceptedReceipts }.forEach { item ->
                    val stamp = WriterStamp(event, item.ref.workflow.id, item.ref.key, item.ref.workflow.queued, item.ref.priority)
                    candidateWindows.values.filter { it.workflowId != stamp.workflowId }.forEach { active ->
                        overlappingCandidates += pair(active.nodeKey, stamp.nodeKey)
                    }
                    candidateWindows[item.ref.key] = stamp
                }
                prepared.toList()
                } ?: return false
                if (prepared.isEmpty()) continue
                suspend fun recordFailureOwned(failure: NodeExecutionFailure) {
                    val category = failure.detail.ifEmpty {
                        if (failure.ref.action?.descriptor?.usesModel == true) "模型调用失败" else "动作执行失败"
                    }
                    priorFailures[failure.ref.key] = RecoveryFailure(failure.ref.key, failure.ref.workflow.id,
                        failure.ref.node.id, failure.ref.node.title, failure.kind, category, failure.source, failure.retryable)
                    skipped += failure.ref.key
                    candidateWindows.remove(failure.ref.key)
                    preparationTerminal(failure.ref.key, PreparationStatus.FAILED)
                    trace(failure.ref, "error", category)
                }
                suspend fun recordFailure(failure: NodeExecutionFailure) = write { recordFailureOwned(failure) }
                val usedAddresses = mutableSetOf<String>()
                val usedPatches = mutableSetOf<InjectionIdentity>()
                suspend fun publishCompleted(completed: List<Completed>) = write {
                    currentCoroutineContext().ensureActive()
                    val validCompleted = mutableListOf<Completed>()
                    for (item in completed) {
                        val ownAddress = if (item.ref.node.actionId == "user.macro.create") emptyList()
                            else listOf("result.${item.ref.node.outputKey}")
                        val addresses = (ownAddress + item.outcome.macroWrites.keys +
                            item.outcome.activationWrites.keys + item.outcome.stateWrites.keys.map { "state.$it" })
                            .mapNotNull(::canonicalWriteAddress).distinct() + item.outcome.historyWrites.views.map { "history-view:${it.viewId}" }
                        val userMacroAddresses = item.outcome.userMacroWrites.keys.map { "user-macro:$it" }
                        val patchIds = item.outcome.injectionWrites.map { patch ->
                            InjectionIdentity(resourceByWorkflow.getValue(item.ref.workflow.id), patch.id)
                        }
                        val stamp = WriterStamp(event, item.ref.workflow.id, item.ref.key, item.ref.workflow.queued, item.ref.priority)
                        val actualKeys = addresses + userMacroAddresses + patchIds.map { "injection:${it.resourceId}/${it.id}" }
                        val concurrentConflict = semantics.independentEventLanes && !item.replay && actualKeys.any { address ->
                            addressWriters[address].orEmpty().any { simultaneous(it, stamp) }
                        }
                        val invalid = concurrentConflict || (addresses + userMacroAddresses).any { it in usedAddresses } ||
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
                            candidateWindows.remove(item.ref.key)
                            preparationTerminal(item.ref.key, PreparationStatus.FAILED)
                            trace(item.ref, "error", "并行节点写入冲突或结果地址无效")
                        } else {
                            usedAddresses += addresses + userMacroAddresses
                            usedPatches += patchIds
                            validCompleted += item
                        }
                    }
                    val published = mutableListOf<RecoveryReceipt>()
                    for (item in validCompleted) {
                        currentCoroutineContext().ensureActive()
                        val receipt = if (item.replay) acceptedReceipts.getValue(item.ref.key) else try {
                            val automaticSegment = if (!legacy && processingEvent && processingCandidate != null && processor != null &&
                                item.ref.workflow.id == processor.workflow.id && item.ref.node.id ==
                                    processor.workflow.nodes.lastOrNull { it.enabled && it.outputKey == processor.outputKey }?.id &&
                                item.outcome.text.isNotBlank()) {
                                val sources = if (semantics.globalMemory && library.memoryConfig != null) {
                                    val actual = item.outcome.historyRead?.sources.orEmpty()
                                    if (actual.isEmpty() || actual.any { it !in processingCandidate.sources })
                                        throw NodeExecutionFailure(item.ref, "摘要提示词没有引用可处理的正文楼层，请使用原生 History 引用",
                                            RecoveryFailureKind.PROMPT, retryable = false)
                                    processingCandidate.sources.filter { it in actual }
                                } else processingCandidate.sources
                                HistoryMemory.segmentFromSources("历史摘要", item.outcome.text, sources, processor.workflow.id)
                            } else null
                            outcomePublisher.prepare(item.ref, item.outcome,
                                snapshot(stagedState.toMap(), stagedResults.toMap()).copy(messages = session.messages + replies),
                                compiledModelTargets, processingEvent, automaticSegment, item.candidate.rawModelText,
                                item.candidate.fullResponseText, item.candidate.reasoningSnapshot, item.candidate.modelFinishReason)
                        } catch (failure: NodeExecutionFailure) {
                            recordFailureOwned(failure)
                            continue
                        }
                        if (!item.replay) item.candidate.provenanceNote?.let { trace(item.ref, "warning", it) }
                        if (!item.replay && modelResponseWasTruncated(item.candidate.modelFinishReason))
                            trace(item.ref, "warning", "模型输出已截断（${item.candidate.modelFinishReason}）；已保留正文，请检查后续结果")
                        val publication = receipt.publication
                        val nextMemory = try { HistoryMemory.apply(stagedMemory, publication.historyWrites) }
                        catch (failure: IllegalArgumentException) {
                            recordFailureOwned(NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        } catch (failure: IllegalStateException) {
                            recordFailureOwned(NodeExecutionFailure(item.ref, failure.message.orEmpty(), RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        }
                        fun currentVersion(address: String): Int = maxOf(stagedHeads[address]?.version ?: 0,
                            stagedHistory[address]?.lastOrNull()?.version ?: 0)
                        if (item.replay && (publication.macroAppends.any { (address, appends) ->
                                // A legacy synthetic version zero may precede the first accepted publication.
                                appends.isNotEmpty() && (address in stagedHeads || stagedHistory[address].orEmpty().isNotEmpty()) &&
                                    appends.first().version <= currentVersion(address)
                            } || publication.headWrites.any { (address, head) -> head.version <= currentVersion(address) })) {
                            recordFailureOwned(NodeExecutionFailure(item.ref, "已接受宏版本与修复后的写入冲突",
                                RecoveryFailureKind.CONFLICT, retryable = false))
                            continue
                        }
                        val publicationContext = writerSender ?: currentCoroutineContext()
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
                            stagedUserMacros.putAll(publication.userMacroWrites)
                            stagedResults.putAll(publication.resultWrites)
                            stagedOrigins.putAll(publication.resultOrigins)
                            publication.resultWrites.keys.forEach { maskedKeys -= it }
                            publication.injectionRemovals.forEach { identity -> stagedInjections.removeAll {
                                it.id == identity.id && it.origin.resourceId == identity.resourceId
                            } }
                            stagedInjections += publication.injectionAdds
                            stagedMemory = nextMemory
                            // Recipes are metadata for this accepted receipt. They never publish
                            // effects or remove a value from the live run's results.
                            try {
                                completedCapture?.accepted(item.ref,
                                    prepared.firstOrNull { it.ref.key == item.ref.key }?.context?.takeUnless { item.replay },
                                    receipt.outcome, publication)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                // Capture failure retains the already accepted value in its branch.
                            }
                            if (semantics.independentEventLanes) {
                                val stamp = WriterStamp(event, item.ref.workflow.id, item.ref.key, item.ref.workflow.queued, item.ref.priority)
                                val keys = publication.macroAppends.keys + publication.headWrites.keys +
                                    publication.headRemovals + publication.resultWrites.keys.map { "result.$it" } +
                                    publication.stateWrites.keys.map { "state.$it" } +
                                    publication.userMacroWrites.keys.map { "user-macro:$it" } +
                                    publication.historyWrites.views.map { "history-view:${it.viewId}" } +
                                    (publication.injectionAdds.map { InjectionIdentity(it.origin.resourceId, it.id) } +
                                        publication.injectionRemovals).map { "injection:${it.resourceId}/${it.id}" }
                                keys.forEach { addressWriters.getOrPut(it) { mutableListOf() }.add(stamp) }
                            }
                            preparationTerminal(item.ref.key, PreparationStatus.ACCEPTED)
                            candidateWindows.remove(item.ref.key)
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
                    published.flatMap { it.outcome.emittedEvents }.forEach(::emit)
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
                // A special default has one final BODY producer. Its inputs are already frozen
                // for this wave; unrelated handlers need not delay its durable acceptance.
                val earlyDefault = if (semantics.earlyDefaultBodyPublication && prepared.size > 1 &&
                    main.specialDefault && (!semantics.legacyTiles || main.bodySourceTileId == null) && !processingEvent) {
                    val primary = DefaultCallRules.primary(main)
                    prepared.singleOrNull { item -> item.ref.workflow.id == primary?.id &&
                        item.ref.node.id == primary?.nodes?.singleOrNull()?.id &&
                        item.ref.action?.descriptor?.execution == ActionExecution.PURE_MODEL &&
                        item.ref.node.visible && item.ref.node.outputSlot == OutputSlot.BODY &&
                        item.ref.node.dependsOn.isEmpty() && item.ref.node.outputKey.isNotBlank()
                    }?.takeIf { candidate ->
                        val writes = candidate.ref.node.declaredOutputAddresses(candidate.ref.action?.descriptor)
                        prepared.filter { it !== candidate }.none { peer ->
                            peer.ref.node.declaredOutputAddresses(peer.ref.action?.descriptor).any { it in writes }
                        }
                    }
                } else null
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
                } else if (earlyDefault != null) supervisorScope {
                    val received = Channel<Pair<Prepared, Pair<Completed?, NodeExecutionFailure?>>>(prepared.size)
                    val tasks = prepared.map { item -> async { received.send(item to attempt(item)) } }
                    val buffered = mutableMapOf<String, Pair<Completed?, NodeExecutionFailure?>>()
                    repeat(prepared.size) {
                        val (item, attempted) = received.receive()
                        if (item.ref.key == earlyDefault.ref.key) {
                            attempted.second?.let { recordFailure(it) }
                            attempted.first?.let { publishCompleted(listOf(it)) }
                            // usedAddresses now protects the accepted default from undeclared
                            // writes returned later by a handler. Such a handler must fail.
                        } else buffered[item.ref.key] = attempted
                    }
                    tasks.awaitAll()
                    received.close()
                    val remaining = prepared.filter { it.ref.key != earlyDefault.ref.key }.map { buffered.getValue(it.ref.key) }
                    remaining.mapNotNull { it.second }.forEach { recordFailure(it) }
                    publishCompleted(remaining.mapNotNull { it.first })
                } else {
                    val attempted = supervisorScope { prepared.map { item -> async { attempt(item) } }.awaitAll() }
                    attempted.mapNotNull { it.second }.forEach { recordFailure(it) }
                    publishCompleted(attempted.mapNotNull { it.first })
                }
            }
            write {
            if (!legacy) for (workflow in freshWorkflows) {
                val enabled = workflow.nodes.filter { it.enabled }.map { "${workflow.id}/${it.id}" }
                val primary = workflow.id == DefaultCallRules.primary(main)?.id
                val complete = workflow.id in admittedIds && enabled.any { it in outcomes } &&
                    enabled.all { it in outcomes || (!semantics.primaryReplyCompletion || !primary) && it in inactiveKeys } &&
                    enabled.none { it in explicitSkips || it in priorFailures }
                if (complete && dispatchedCompletions.add(workflow.id)) {
                    if (workflow.id !in completedWorkflowIds) {
                        val completionContext = writerSender ?: currentCoroutineContext()
                        completionContext.ensureActive()
                        withContext(NonCancellable) {
                            completionContext.ensureActive()
                            completedWorkflowIds += workflow.id
                            progress()
                        }
                    }
                    val completedEvent = RuntimeEvents.workflowCompleted(workflow.id)
                    if (completedEvent in plan.events) emit(completedEvent)
                    if (semantics.primaryReplyCompletion && primary && replies.any {
                            it.workflowId == workflow.id && it.outputSlot == OutputSlot.BODY && !it.deleted && it.text.isNotBlank()
                        }) emit(RuntimeEvents.REPLY_COMPLETED)
                }
            }
            if (semantics.independentEventLanes) freshWorkflows.forEach { workflow -> workflow.nodes.forEach { node ->
                val key = "${workflow.id}/${node.id}"
                preparationTerminal(key, when {
                    key in outcomes -> PreparationStatus.ACCEPTED
                    key in priorFailures || key in explicitSkips -> PreparationStatus.FAILED
                    else -> PreparationStatus.INACTIVE
                })
            } }
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

        // A frame is one workflow's causal chain. Queue cohorts share a global gate;
        // parallel frames never acquire it. All acceptance still goes through write().
        val scheduledWorkflowIds = mutableSetOf<String>()
        val queuedWaiting = mutableListOf<Pair<Int, CompletableDeferred<Unit>>>()
        var queuedPriority: Int? = null
        var queuedActive = 0
        fun pumpQueue() {
            if (queuedActive == 0) queuedPriority = queuedWaiting.minOfOrNull { it.first }
            val ready = queuedWaiting.filter { it.first == queuedPriority }
            queuedWaiting.removeAll(ready.toSet())
            queuedActive += ready.size
            ready.forEach { it.second.complete(Unit) }
        }
        suspend fun drainLanes(initialEvents: List<String>): Boolean = supervisorScope {
            val received = Channel<SchedulerSignal>(Channel.UNLIMITED)
            signals = received
            var running = 0
            var successful = true
            write {
                val initialNodes = initialEvents.flatMap { plan.events[it]?.waves.orEmpty().flatten() }.map { it.node }
                initialNodes.forEach { maskedKeys += resultKeys(it) }
                maskViews(initialNodes)
            }
            suspend fun activate(event: String) {
                val frames = write {
                    if (++eventCount > 32) {
                        trace(null, "error", "事件链超过上限")
                        successful = false
                        emptyList()
                    } else {
                        val eventPlan = plan.events[event]
                        val refs = eventPlan?.waves.orEmpty().flatten()
                        val fresh = refs.map { it.workflow }.distinctBy { it.id }
                            .filter { scheduledWorkflowIds.add(it.id) }
                        // Mask the entire activation, including queued frames that have not
                        // acquired a permit yet, before any peer freezes its prompt input.
                        fresh.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
                        maskViews(fresh.flatMap { it.nodes })
                        fresh.map { workflow ->
                            val lane = if (workflow.queued) eventPlan!!.queuedWaves else eventPlan!!.parallelWaves
                            val waves = lane.map { wave -> wave.filter { it.workflow.id == workflow.id } }.filter { it.isNotEmpty() }
                            workflow to EventPlan(event, waves)
                        }
                    }
                }
                // Register the whole ready cohort together. Models waiting for real preparation
                // prerequisites join only afterwards, so they cannot hold the injector's gate.
                val entries = write {
                    frames.associate { (workflow, frame) ->
                        val waitKeys = frame.waves.flatten().filter { it.action?.descriptor?.usesModel == true }
                            .filter { it.key !in preparationCycles }
                            .flatMap { preparationDependencies[it.key].orEmpty() }
                            .filterNot { it.startsWith("${workflow.id}/") }.toSet()
                        val gate = CompletableDeferred<Unit>()
                        if (!workflow.queued) gate.complete(Unit)
                        else if (waitKeys.isEmpty()) queuedWaiting += workflow.priority to gate
                        workflow.id to (waitKeys to gate)
                    }.also { pumpQueue() }
                }
                for ((workflow, frame) in frames) {
                    running++
                    launch {
                        var acquired = false
                        var success = false
                        try {
                            val (waitKeys, gate) = entries.getValue(workflow.id)
                            val failed = waitKeys.filter { key ->
                                preparationTerminals[key]?.await() == PreparationStatus.FAILED
                            }.toSet()
                            if (workflow.queued && waitKeys.isNotEmpty()) write {
                                queuedWaiting += workflow.priority to gate
                                pumpQueue()
                            }
                            gate.await()
                            acquired = workflow.queued
                            val failedModels = frame.waves.flatten().filter { ref ->
                                val own = frame.waves.flatten().associateBy { it.key }
                                fun ancestors(key: String, seen: MutableSet<String> = mutableSetOf()): Set<String> {
                                    val node = own[key]?.node ?: return emptySet()
                                    return node.dependsOn.flatMap { id ->
                                        val parent = "${workflow.id}/$id"
                                        if (seen.add(parent)) listOf(parent) + ancestors(parent, seen) else emptyList()
                                    }.toSet()
                                }
                                preparationDependencies[ref.key].orEmpty().any {
                                    it in failed || it.startsWith("${workflow.id}/") && it !in ancestors(ref.key)
                                } || ref.key in preparationCycles
                            }.mapTo(mutableSetOf()) { it.key }
                            success = executeEvent(event, frame,
                                "${if (workflow.queued) "queued" else "parallel"}:${workflow.id}", failedModels)
                        } catch (cancelled: CancellationException) {
                            this@supervisorScope.cancel("调用已取消", cancelled)
                            throw cancelled
                        } catch (failure: Exception) {
                            write { frame.waves.flatten().firstOrNull { it.key !in acceptedReceipts }?.let { ref ->
                                val reason = failure.message?.take(160).orEmpty().ifEmpty { "调用准备失败" }
                                priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                                    ref.node.title, RecoveryFailureKind.CONFIGURATION, reason, retryable = false)
                                trace(ref, "error", reason)
                            } }
                        } finally {
                            write { frame.waves.flatten().forEach { ref ->
                                preparationTerminal(ref.key, if (success) PreparationStatus.INACTIVE else PreparationStatus.FAILED)
                            } }
                            if (acquired) write { queuedActive--; pumpQueue() }
                            received.trySend(SchedulerSignal.Finished(success))
                        }
                    }
                }
            }
            // Both activations are registered and masked before their workers start.
            initialEvents.forEach { received.send(SchedulerSignal.Event(it)) }
            while (running > 0 || !received.isEmpty) {
                when (val signal = received.receive()) {
                    is SchedulerSignal.Event -> activate(signal.name)
                    is SchedulerSignal.Finished -> { running--; successful = successful && signal.success }
                }
            }
            signals = null
            received.close()
            successful
        }

        try {
        suspend fun abortDispatch(): RunResult {
            if (!semantics.independentEventLanes) return failed()
            val paused = partialResult()
            progress()
            return paused
        }
        // History processing is a fixed pre-stage, using a captured eligible source.
        if (processingCandidate != null && processor != null) {
            if (!(if (semantics.independentEventLanes) drainLanes(listOf(RuntimeEvents.HISTORY_PROCESS))
                else drain(RuntimeEvents.HISTORY_PROCESS))) return abortDispatch()
            val selected = processor.workflow.nodes.lastOrNull { it.enabled && it.outputKey == processor.outputKey &&
                "${processor.workflow.id}/${it.id}" in executedOutput }
            if (legacy && selected != null) {
                val text = executedOutput["${processor.workflow.id}/${selected.id}"].orEmpty()
                if (text.isNotBlank()) stagedDigests[main.id] = HistoryDigest(text,
                    processingCandidate.throughMessageId, processingCandidate.coveredFloors, processor.workflow.id)
                else trace(null, "skipped", "历史处理输出为空，保留原摘要")
            }
        }
        // New lanes wait only actual preparation prerequisites; old versions keep their phase barrier.
        if (semantics.independentEventLanes) {
            if (!drainLanes(listOf(RuntimeEvents.TURN_PREPARE, RuntimeEvents.USER_MESSAGE))) return abortDispatch()
        } else {
            if (!drain(RuntimeEvents.TURN_PREPARE)) return failed()
            if (!drain(RuntimeEvents.USER_MESSAGE)) return failed()
            if (replies.isNotEmpty() && !drain(RuntimeEvents.REPLY_COMPLETED)) return failed()
        }
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
            unavailableResultKeys = maskedKeys.toSet(), historyMemory = stagedMemory, currentOnlyMacros = stagedHeads.toMap(),
            completedWorld = completedCapture?.completed(snapshot(stagedState.toMap(), stagedResults.toMap()).runtimeSnapshot()),
            userMacros = stagedUserMacros.toMap())
        } finally {
            commands.close()
            withContext(NonCancellable) { writerJob?.join() }
        }
    }
}
