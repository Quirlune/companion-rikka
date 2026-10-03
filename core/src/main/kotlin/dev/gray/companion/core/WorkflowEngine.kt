package dev.gray.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class WorkflowEngine(
    private val actions: ActionRegistry = ActionRegistry.defaults(),
    private val maxConcurrentNodes: Int = 4
) {
    init { require(maxConcurrentNodes > 0) }

    private data class Prepared(val ref: PlannedNode, val context: ActionContext)
    private data class Completed(val ref: PlannedNode, val outcome: ActionOutcome, val replay: Boolean = false,
                                 val oldReply: ChatMessage? = null)
    private data class RenderedInjection(val input: String, val arguments: Map<String, String>, val issues: List<Issue>)
    private class NodeExecutionFailure(val ref: PlannedNode, val detail: String = "",
                                       val kind: RecoveryFailureKind = RecoveryFailureKind.ACTION,
                                       val source: String? = null, val retryable: Boolean = true) : RuntimeException()
    private data class InjectionTarget(val workflowId: String, val nodeId: String) {
        val key: String get() = "$workflowId/$nodeId"
    }

    private fun injectionTarget(main: MainCall, actions: Map<String, RegisteredAction>,
                                workflowId: String?, nodeId: String?): InjectionTarget? {
        val workflows = if (workflowId.isNullOrBlank()) main.workflows.filter { it.enabled }
            else main.workflows.filter { it.id == workflowId && it.enabled }
        if (!nodeId.isNullOrBlank()) {
            val matches = workflows.flatMap { workflow -> workflow.nodes.filter { it.id == nodeId && it.enabled && actions[it.actionId]?.descriptor?.usesModel == true }
                .map { InjectionTarget(workflow.id, it.id) } }
            return matches.singleOrNull()
        }
        val workflow = if (!workflowId.isNullOrBlank()) workflows.singleOrNull()
            else if (main.primaryWorkflowId != null) workflows.firstOrNull { it.id == main.primaryWorkflowId }
            else workflows.firstOrNull()
        val node = workflow?.nodes?.lastOrNull { it.enabled && actions[it.actionId]?.descriptor?.usesModel == true }
        return node?.let { InjectionTarget(workflow.id, it.id) }
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
        if (decision.nodeKey !in recovery.failures.map { it.nodeKey } ||
            currentSession.pendingRun?.id != recovery.id ||
            (currentSession.id != recovery.sessionId && !recovery.regenerating))
            return conflict(recovery, "恢复断点与当前会话不一致")
        val revised = repairedResources(recovery.frozenResources, library, decision.nodeKey)
            ?: return conflict(recovery, "调用结构或环境已变化；只能编辑失败调用的提示词块")
        if (decision.kind != RecoveryDecisionKind.SKIP) {
            val target = (revised.mainCalls.flatMap { it.workflows + it.history.processor.workflow } +
                revised.groups.flatMap { it.workflows })
                .firstOrNull { it.id == decision.nodeKey.substringBefore('/') }
                ?.nodes?.firstOrNull { it.id == decision.nodeKey.substringAfter('/') }
            val addresses = target?.let { node ->
                setOf("result.${node.outputKey}") + listOfNotNull(node.declaredWriteAddress(
                    actions.snapshot()[node.actionId]?.descriptor))
            }.orEmpty()
            if (recovery.accepted.any { receipt -> receipt.publication.macroAppends.keys.any { it in addresses } })
                return conflict(recovery, "失败调用与已接受结果写入同一宏地址，不能安全原位续跑")
        }
        return execute(revised, recovery.initialSession, recovery.input, gateway, recovery.copy(frozenResources = revised), decision, onUpdate)
    }

    private fun conflict(recovery: RunRecovery, message: String): RunResult {
        val failure = recovery.failures.firstOrNull()
        val trace = RunTrace(failure?.workflowId.orEmpty(), failure?.nodeId.orEmpty(), failure?.title.orEmpty(), "error", message)
        val conflicts = if (failure == null) recovery.failures else recovery.failures.map { current ->
            if (current.nodeKey == failure.nodeKey) current.copy(kind = RecoveryFailureKind.CONFLICT,
                reason = message, retryable = false) else current
        }
        return RunResult(recovery.state, recovery.results, recovery.replies, recovery.traces + trace,
            RunStatus.PAUSED, recovery.resultOrigins, recovery.macroHistory, recovery.promptInjections,
            recovery.historyDigests, recovery.copy(traces = recovery.traces + trace, failures = conflicts),
            recovery.unavailableKeys)
    }

    private fun freezeResources(library: Library, session: Session): Library {
        val main = library.mainCalls.first { it.id == session.mainCallId }
        val groups = library.loadedCallGroups(session, main)
        val packages = library.loadedPromptPackages(session, main)
        val referencedProfiles = (main.workflows + listOf(main.history.processor.workflow) +
            groups.flatMap { it.workflows }).flatMap { flow ->
            flow.nodes.map { it.generation.profileId ?: flow.modelProfileId ?: library.selectedProfileId }
        }.filterNotNull().toSet() + listOfNotNull(library.selectedProfileId)
        val profiles = library.profiles.filter { it.id in referencedProfiles }
        val connectionIds = profiles.mapNotNull { it.apiConnectionId }.toSet()
        return library.copy(
            characters = library.characters.filter { it.id == session.characterId },
            mainCalls = listOf(main), packages = packages, groups = groups, profiles = profiles,
            connections = library.connections.filter { it.id in connectionIds },
            sessions = emptyList(), selectedSessionId = null,
            chatFolders = emptyList(), savedChats = emptyList(),
            personas = library.personas.filter { it.id == session.personaId || it.id == library.selectedPersonaId }
        )
    }

    /** Only the target's saved prompt blocks may differ from the captured resource graph. */
    private fun repairedResources(frozen: Library, live: Library, nodeKey: String): Library? {
        val workflowId = nodeKey.substringBefore('/')
        val nodeId = nodeKey.substringAfter('/', "")
        if (nodeId.isEmpty()) return null
        val frozenMain = frozen.mainCalls.singleOrNull() ?: return null
        val liveMain = live.mainCalls.firstOrNull { it.id == frozenMain.id } ?: return null
        var found = false
        fun patch(workflow: Workflow, source: Workflow?): Workflow {
            if (workflow.id != workflowId || source == null) return workflow
            return workflow.copy(nodes = workflow.nodes.map { node ->
                if (node.id != nodeId) node else {
                    found = true
                    node.copy(blocks = source.nodes.firstOrNull { it.id == nodeId }?.blocks ?: node.blocks)
                }
            })
        }
        val patchedMain = frozenMain.copy(workflows = frozenMain.workflows.map { old ->
            patch(old, liveMain.workflows.firstOrNull { it.id == old.id })
        }, history = frozenMain.history.copy(processor = frozenMain.history.processor.copy(
            workflow = patch(frozenMain.history.processor.workflow,
                liveMain.history.processor.workflow.takeIf { it.id == workflowId }))))
        val patchedGroups = frozen.groups.map { old ->
            val source = live.groups.firstOrNull { it.id == old.id } ?: return null
            old.copy(workflows = old.workflows.map { flow ->
                patch(flow, source.workflows.firstOrNull { it.id == flow.id })
            }).also { if (it != source) return null }
        }
        val updatedConnections = frozen.connections.map { old ->
            val source = live.connections.firstOrNull { it.id == old.id } ?: return null
            if (old.copy(maxConcurrentRequests = source.maxConcurrentRequests) != source) return null
            source
        }
        if (!found || patchedMain != liveMain) return null
        if (frozen.characters.any { old -> live.characters.firstOrNull { it.id == old.id } != old } ||
            frozen.packages.any { old -> live.packages.firstOrNull { it.id == old.id } != old } ||
            frozen.profiles.any { old -> live.profiles.firstOrNull { it.id == old.id } != old } ||
            frozen.personas.any { old -> live.personas.firstOrNull { it.id == old.id } != old } ||
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
        val updateMutex = Mutex()
        suspend fun trace(ref: PlannedNode?, status: String, detail: String) {
            val item = RunTrace(ref?.workflow?.id.orEmpty(), ref?.node?.id.orEmpty(), ref?.node?.title.orEmpty(), status, detail.take(160))
            traces += item
            updateMutex.withLock { onUpdate(RunUpdate(trace = item, workflowId = item.workflowId)) }
        }
        fun failed() = RunResult(session.state, session.results, emptyList(), traces.toList(), RunStatus.FAILED,
            session.resultOrigins, session.macroHistory, session.promptInjections, session.historyDigests,
            unavailableResultKeys = session.unavailableResultKeys)

        val validation = LibraryValidator.validate(library).firstOrNull { it.error }
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
        if (main.workflows.none { it.enabled && it.trigger == "user.message" && it.nodes.any { node -> node.enabled } }) {
            trace(null, "error", "主调用缺少 user.message 入口")
            return failed()
        }
        val stagedState = session.state.toMutableMap()
        val groups = library.loadedCallGroups(session, main)
        groups.forEach { group -> group.stateDefaults.forEach { (key, value) -> stagedState.putIfAbsent(key, value) } }
        val processor = main.history.processor
        val processingCandidate = HistoryContext(library, session, main).pendingCandidate()
        val workflows = main.workflows + groups.flatMap { it.workflows } +
            listOfNotNull(processor.workflow.takeIf { it.enabled })
        val availableActions = actions.snapshot()
        val (plan, planningIssue) = RunPlan.create(workflows, availableActions)
        if (planningIssue != null || plan == null) {
            trace(null, "error", planningIssue?.message ?: "运行计划无法创建")
            return failed()
        }
        fun resultKeys(node: WorkflowNode): Set<String> = buildSet {
            add(node.outputKey)
            node.declaredWriteAddress(availableActions[node.actionId]?.descriptor)
                ?.takeIf { it.startsWith("result.") }?.substringAfter("result.")?.let(::add)
        }
        val activeKeys = workflows.flatMap { it.nodes }.flatMap(::resultKeys).toSet()
        val inactiveGroupKeys = library.groups.filter { group -> groups.none { it.id == group.id } }
            .flatMap { it.workflows }.flatMap { it.nodes }.flatMap(::resultKeys).toSet() - activeKeys
        val stagedResults = session.results.toMutableMap()
        val stagedOrigins = session.resultOrigins.toMutableMap()
        val stagedHistory = session.macroHistory.toMutableMap()
        val stagedDigests = session.historyDigests.toMutableMap()
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
        if (processingCandidate == null) processor.workflow.nodes.forEach { maskedKeys += resultKeys(it) }
        workflows.filter { !it.enabled }.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
        workflows.filter { it.enabled }.flatMap { it.nodes.filter { node -> !node.enabled } }
            .forEach { maskedKeys += resultKeys(it) }
        val resourceByWorkflow = buildMap {
            main.workflows.forEach { put(it.id, main.id) }
            groups.forEach { group -> group.workflows.forEach { put(it.id, group.id) } }
            if (processor.workflow.enabled) put(processor.workflow.id, main.id)
        }
        val activeOwnerIds = setOf(main.id) + groups.map { it.id }
        fun visibleResults(): Map<String, String> = stagedResults.filter { (key, _) ->
            key !in maskedKeys && (stagedOrigins[key]?.resourceId?.let { it in activeOwnerIds } != false)
        }
        fun snapshot(state: Map<String, String>, results: Map<String, String>): Session = session.copy(
            state = state, results = results, resultOrigins = stagedOrigins.filterKeys { it in results },
            macroHistory = stagedHistory.toMap(), promptInjections = stagedInjections.toList(),
            historyDigests = stagedDigests.toMap(), unavailableResultKeys = maskedKeys.toSet()
        )
        val replies = mutableListOf<ChatMessage>()
        val replyRank = mutableMapOf<String, Int>()
        var nextReplyRank = 0
        fun orderReplies() {
            replies.sortBy { reply -> replyRank["${reply.workflowId}/${reply.nodeId}"] ?: Int.MAX_VALUE }
        }
        val recoveryBase = previous ?: RunRecovery(
            id = newId(), sessionId = session.id, turnId = currentUserMessageId.orEmpty(), input = input,
            checkpoint = MessageOperations.captureCheckpoint(session), initialSession = session.copy(pendingRun = null),
            frozenResources = freezeResources(library, session)
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
            replies = if (replayIncomplete()) previous!!.replies else replies.toList(), traces = traces.toList()
        )
        fun partialResult(): RunResult {
            val checkpoint = recoverySnapshot()
            return RunResult(checkpoint.state, checkpoint.results, checkpoint.replies,
                checkpoint.traces, RunStatus.PAUSED, checkpoint.resultOrigins, checkpoint.macroHistory,
                checkpoint.promptInjections, checkpoint.historyDigests, checkpoint, checkpoint.unavailableKeys)
        }
        suspend fun progress() { updateMutex.withLock { onUpdate(RunUpdate(progress = partialResult())) } }
        val executedOutput = mutableMapOf<String, String>()
        val compiledModelTargets = mutableSetOf<String>()
        val semaphore = Semaphore(maxConcurrentNodes)
        val visited = mutableSetOf<String>()
        var eventCount = 0
        val emittedEvents = ArrayDeque<String>()

        fun checkedOutcome(item: Prepared, outcome: ActionOutcome): ActionOutcome {
            val ref = item.ref
            val processed = if (ref.node.outputProcessing.isEmpty()) outcome else try {
                require(outcome.stateWrites.isEmpty() && outcome.macroWrites.isEmpty() &&
                    outcome.activationWrites.isEmpty())
                val resolver = MacroResolver(library, requireNotNull(item.context.session), item.context.input)
                outcome.copy(text = TextProcessing.apply(ref.node.outputProcessing,
                    outcome.text, item.context.state, item.context.results) { resolver.read(it) })
            } catch (failure: OutputFormatException) {
                throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.OUTPUT_FORMAT)
            } catch (_: Exception) {
                throw NodeExecutionFailure(ref, "文本处理失败", RecoveryFailureKind.OUTPUT_FORMAT)
            }
            val values = linkedMapOf("result.${ref.node.outputKey}" to processed.text)
            processed.macroWrites.forEach { (address, value) ->
                val canonical = canonicalWriteAddress(address)
                    ?: throw NodeExecutionFailure(ref, "宏写入地址无效", RecoveryFailureKind.CONFIGURATION, retryable = false)
                values[canonical] = value
            }
            processed.stateWrites.forEach { (key, value) -> values["state.$key"] = value }
            val mainTiles = library.mainCalls.firstOrNull { it.id == session.mainCallId }?.tiles.orEmpty()
            val tiles = mainTiles + library.loadedCallGroups(session).flatMap { it.tiles }
            for (tile in tiles.filter { it.enabled }) {
                val source = MacroOutputContracts.sourceAddress(tile) ?: continue
                val text = values[source] ?: continue
                val problem = MacroOutputContracts.validate(tile, text) ?: continue
                val kind = if (problem.startsWith("磁贴正则配置无效")) RecoveryFailureKind.CONFIGURATION
                    else RecoveryFailureKind.OUTPUT_FORMAT
                throw NodeExecutionFailure(ref, problem, kind, "${tile.title} (${tile.id})", kind != RecoveryFailureKind.CONFIGURATION)
            }
            return processed
        }

        suspend fun perform(item: Prepared): Completed {
            val ref = item.ref
            acceptedReceipts[ref.key]?.let { return Completed(ref, it.outcome, true, it.reply) }
            if (ref.key in lockedFailures && ref.key != decision?.nodeKey) {
                val saved = priorFailures.getValue(ref.key)
                throw NodeExecutionFailure(ref, saved.reason, saved.kind, saved.source, saved.retryable)
            }
            if (ref.key == decision?.nodeKey && decision?.kind == RecoveryDecisionKind.MANUAL) {
                if (ref.node.actionId != "model.generate" || decision.manualText == null)
                    throw NodeExecutionFailure(ref, "此调用不能手动提供候选文本", RecoveryFailureKind.CONFIGURATION)
                return Completed(ref, checkedOutcome(item, ActionOutcome(decision.manualText)))
            }
            if (ref.node.actionId != "model.generate") {
                val outcome = semaphore.withPermit { ref.action!!.handler.execute(item.context) }
                return Completed(ref, checkedOutcome(item, outcome))
            }
            val profile = item.context.profile ?: throw NodeExecutionFailure(ref, "未选择模型", RecoveryFailureKind.CONFIGURATION, retryable = false)
            val capacity = gateway.maxParallelCandidates(profile).coerceIn(1, 2)
            val sourceAddress = "result.${ref.node.outputKey}"
            val hasDirectContract = (library.mainCalls.firstOrNull { it.id == session.mainCallId }?.tiles.orEmpty() +
                library.loadedCallGroups(session).flatMap { it.tiles }).any {
                it.enabled && MacroOutputContracts.sourceAddress(it) == sourceAddress
            }
            val streamFirst = ref.node.visible && ref.node.outputProcessing.isEmpty() && !hasDirectContract
            var serialFallback = false
            var lastFailure = NodeExecutionFailure(ref, "模型调用失败", RecoveryFailureKind.MODEL_REQUEST)
            for (round in 0..2) {
                val width = if (round == 0 || serialFallback) 1 else capacity
                var streamed = false
                val received = Channel<Pair<ActionOutcome?, NodeExecutionFailure?>>(width)
                val winner = supervisorScope {
                    val tasks = (0 until width).map { async {
                        val candidate = try {
                            val correctionMessages = buildList {
                                if (ref.key == decision?.nodeKey && !decision?.correction.isNullOrBlank())
                                    add(ChatMessage(role = Role.SYSTEM, text = decision.correction))
                                if (round > 0 && lastFailure.kind == RecoveryFailureKind.OUTPUT_FORMAT)
                                    add(ChatMessage(role = Role.SYSTEM,
                                        text = "上次结果未通过格式校验：${lastFailure.detail.take(120)}。按既定要求重新生成。"))
                            }
                            val requestContext = item.context.copy(
                                messages = item.context.messages + correctionMessages,
                                onDelta = { delta ->
                                    if (round == 0 && streamFirst) {
                                        streamed = true
                                        item.context.onDelta(delta)
                                    }
                                }
                            )
                            val raw = semaphore.withPermit { ref.action!!.handler.execute(requestContext) }
                            checkedOutcome(item, raw) to null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: NodeExecutionFailure) {
                            null to failure
                        } catch (failure: ModelRequestFailure) {
                            null to NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.MODEL_REQUEST,
                                retryable = failure.retryable).also { if (failure.serialFallback) serialFallback = true }
                        } catch (_: Exception) {
                            null to NodeExecutionFailure(ref, "模型调用失败", RecoveryFailureKind.MODEL_REQUEST)
                        }
                        received.send(candidate)
                    } }
                    var valid: ActionOutcome? = null
                    for (candidateIndex in 0 until width) {
                        val (outcome, failure) = received.receive()
                        if (outcome != null) {
                            valid = outcome
                            tasks.forEach { it.cancel() }
                            break
                        }
                        if (failure != null) lastFailure = failure
                    }
                    valid
                }
                received.close()
                if (winner != null) return Completed(ref, winner)
                if (streamed) updateMutex.withLock { onUpdate(RunUpdate(nodeId = ref.node.id,
                    text = "", visible = true, workflowId = ref.workflow.id,
                    outputSlot = ref.node.outputSlot, turnId = currentUserMessageId, replace = true)) }
                if (!lastFailure.retryable) break
            }
            throw lastFailure
        }

        suspend fun executeEvent(event: String): Boolean {
            if (++eventCount > 32) {
                trace(null, "error", "事件链超过上限")
                return false
            }
            val eventPlan = plan.events[event] ?: return true
            val processingEvent = event == "history.process"
            val eventInput = if (processingEvent) processingCandidate?.text.orEmpty() else input
            val freshWorkflows = eventPlan.waves.flatten().map { it.workflow }.distinctBy { it.id }
                .filter { visited.add(it.id) }
            val freshIds = freshWorkflows.mapTo(mutableSetOf()) { it.id }
            val admittedWorkflows = mutableSetOf<String>()
            val evaluatedWorkflows = mutableSetOf<String>()
            // Mask every scheduled output before evaluating any trigger. Historical macro.*
            // versions remain an explicit, independent read path.
            freshWorkflows.flatMap { it.nodes }.forEach { maskedKeys += resultKeys(it) }
            val skipped = mutableSetOf<String>()
            val outcomes = mutableMapOf<String, ActionOutcome>()
            for ((waveIndex, wave) in eventPlan.waves.withIndex()) {
                val waveState = stagedState.toMap()
                val waveResults = visibleResults()
                val waveOrigins = stagedOrigins.filterKeys { it in waveResults }
                val waveSession = snapshot(waveState, waveResults).copy(messages = session.messages + replies)
                val environmentId = "$event:$waveIndex"
                environments.putIfAbsent(environmentId, RecoveryEnvironment(environmentId, event,
                    waveSession.copy(pendingRun = null), eventInput))
                val waveHistory = HistoryContext(library, waveSession, main, processingEvent, processingCandidate)
                val waveIssues = mutableListOf<Issue>()
                val waveRenderer = TemplateRenderer(
                    loadedResources(library, waveSession, waveState, waveResults, eventInput, waveIssues, waveOrigins, waveHistory),
                    eventInput, waveResults, waveState, waveIssues, waveOrigins, waveSession, waveHistory
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
                    buildSet<String> {
                        add("result.${ref.node.outputKey}")
                        ref.action?.descriptor?.stateWriteKeyField?.let { field ->
                            ref.node.arguments[field]?.trim()?.takeIf { it.isNotBlank() && !it.contains("{{") }
                                ?.let { add("state.$it") }
                        }
                        ref.node.declaredWriteAddress(ref.action?.descriptor)?.let(::add)
                    }
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
                val renderedInjections = mutableMapOf<String, RenderedInjection>()
                suspend fun preparationFailure(ref: PlannedNode, reason: String) {
                    priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, ref.node.id,
                        ref.node.title, RecoveryFailureKind.CONFIGURATION, reason, retryable = false)
                    skipped += ref.key
                    trace(ref, "error", reason)
                }
                // Resolve injection targets before compiling any model prompt in this wave.
                for (ref in active) {
                    if (ref.key in acceptedReceipts) continue
                    val descriptor = ref.action?.descriptor ?: continue
                    val idField = descriptor.injectionIdField ?: continue
                    val issues = mutableListOf<Issue>()
                    val renderer = TemplateRenderer(loadedResources(library, waveSession, waveState, waveResults, eventInput, issues, waveOrigins, waveHistory),
                        eventInput, waveResults, waveState, issues, waveOrigins, waveSession, waveHistory)
                    val renderedInput = try {
                        TextProcessing.apply(ref.node.inputProcessing,
                            renderer.render(ref.node.inputTemplate, "node.${ref.node.id}.input"), waveState, waveResults) {
                            renderer.read(it, "node.${ref.node.id}.inputProcessing")
                        }
                    } catch (_: Exception) {
                        preparationFailure(ref, "输入处理失败")
                        continue
                    }
                    val arguments = descriptor.withDefaults(ref.node.arguments).mapValues { (key, value) ->
                        renderer.render(value, "node.${ref.node.id}.argument.$key")
                    }
                    issues += descriptor.validate(arguments, "node.${ref.node.id}")
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
                    renderedInjections[ref.key] = RenderedInjection(renderedInput, arguments, issues)
                }
                for (ref in active) {
                    if (ref.key in skipped) continue
                    val node = ref.node
                    val action = requireNotNull(ref.action)
                    if (ref.key in acceptedReceipts) {
                        prepared += Prepared(ref, ActionContext(node, eventInput, emptyList(), emptyMap(),
                            waveState, waveResults, null, gateway, {}, session = waveSession, library = library))
                        continue
                    }
                    val issues = mutableListOf<Issue>()
                    val frozenPreparation = if (ref.key == decision?.nodeKey) preparedRecords[ref.key] else null
                    val frozenEnvironment = frozenPreparation?.let { environments[it.environmentId] }
                    val contextSession = frozenEnvironment?.session ?: waveSession
                    val contextState = contextSession.state
                    val contextResults = contextSession.results
                    val contextOrigins = contextSession.resultOrigins
                    val contextHistory = if (frozenEnvironment == null) waveHistory else
                        HistoryContext(library, contextSession, main, processingEvent, processingCandidate)
                    val early = renderedInjections[ref.key]
                    val renderedInput: String
                    val arguments: Map<String, String>
                    if (frozenPreparation != null) {
                        renderedInput = frozenPreparation.renderedInput
                        arguments = frozenPreparation.arguments
                    } else if (early != null) {
                        renderedInput = early.input
                        arguments = early.arguments
                        issues += early.issues
                    } else {
                        val renderer = TemplateRenderer(loadedResources(library, waveSession, waveState, waveResults, eventInput, issues, waveOrigins, waveHistory),
                            eventInput, waveResults, waveState, issues, waveOrigins, waveSession, waveHistory)
                        renderedInput = try {
                            TextProcessing.apply(node.inputProcessing,
                                renderer.render(node.inputTemplate, "node.${node.id}.input"), waveState, waveResults) {
                                renderer.read(it, "node.${node.id}.inputProcessing")
                            }
                        } catch (_: Exception) {
                            preparationFailure(ref, "输入处理失败")
                            continue
                        }
                        val descriptor = action.descriptor
                        val macroFields = descriptor.fields.filter { it.type == ActionFieldType.MACRO }.mapTo(mutableSetOf()) { it.key }
                        arguments = descriptor.withDefaults(node.arguments).mapValues { (key, value) ->
                            if (key in macroFields) value else renderer.render(value, "node.${node.id}.argument.$key")
                        }
                    }
                    val preview = PromptCompiler().preview(
                        library, contextSession,
                        node, frozenEnvironment?.input ?: eventInput, contextResults, contextState,
                        currentUserMessageId, contextOrigins, contextHistory,
                        renderedInput
                    )
                    issues += preview.issues
                    issues += action.descriptor.validate(arguments, "node.${node.id}")
                    if (node.actionId == "event.emit" && arguments["event"]?.trim() in setOf("history.process", "turn.prepare", "user.message", "reply.completed")) {
                        issues += Issue("node.${node.id}.argument.event", "此事件由运行时派发")
                    }
                    val error = issues.firstOrNull { it.error }
                    if (error != null) {
                        priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, node.id,
                            node.title, RecoveryFailureKind.PROMPT, error.message, retryable = false)
                        skipped += ref.key
                        trace(ref, "error", error.message)
                        continue
                    }
                    val dependencyOutputs = node.dependsOn.associateWith { dependency ->
                        outcomes.getValue("${ref.workflow.id}/$dependency")
                    }
                    val profile = if (action.descriptor.usesModel) {
                        frozenPreparation?.profile?.let { captured -> captured.copy(
                            resolvedHeaders = ModelCatalog.resolveProfile(library, captured.id)?.resolvedHeaders.orEmpty()
                        ) } ?: node.generation.resolve(library, ref.workflow)
                    } else null
                    if (action.descriptor.usesModel && (profile == null || profile.model.isBlank())) {
                        priorFailures[ref.key] = RecoveryFailure(ref.key, ref.workflow.id, node.id,
                            node.title, RecoveryFailureKind.CONFIGURATION, "请先选择并填写模型", retryable = false)
                        skipped += ref.key
                        trace(ref, "error", "请先选择并填写模型")
                        continue
                    }
                    val context = ActionContext(
                        node, renderedInput, preview.messages, arguments, contextState, contextResults,
                        profile, gateway, { delta ->
                            if (node.visible && !processingEvent && node.outputProcessing.isEmpty()) updateMutex.withLock {
                                onUpdate(RunUpdate(nodeId = node.id, text = delta, visible = true,
                                    workflowId = ref.workflow.id, outputSlot = node.outputSlot, turnId = currentUserMessageId))
                            }
                        }, dependencyOutputs, contextSession, library
                    )
                    prepared += Prepared(ref, context)
                    if (ref.key !in acceptedReceipts && ref.key !in preparedRecords) preparedRecords[ref.key] =
                        RecoveryPrepared(ref.key, environmentId, renderedInput, arguments, profile)
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
                        .mapNotNull(::canonicalWriteAddress).distinct()
                    val patchIds = item.outcome.injectionWrites.map { patch ->
                        InjectionIdentity(resourceByWorkflow.getValue(item.ref.workflow.id), patch.id)
                    }
                    val invalid = addresses.any { it in usedAddresses } ||
                        patchIds.size != patchIds.distinct().size || patchIds.any { it in usedPatches } ||
                        item.outcome.emittedEvents.any { it.isBlank() || it in setOf("history.process", "turn.prepare", "user.message", "reply.completed") } ||
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
                for (item in validCompleted) {
                    if (item.replay) {
                        val receipt = acceptedReceipts.getValue(item.ref.key)
                        val publication = receipt.publication
                        val incompatible = publication.macroAppends.any { (address, appends) ->
                            val old = stagedHistory[address].orEmpty()
                            old.isNotEmpty() && appends.isNotEmpty() && appends.first().version <= old.last().version
                        }
                        if (incompatible) {
                            priorFailures[item.ref.key] = RecoveryFailure(item.ref.key, item.ref.workflow.id,
                                item.ref.node.id, item.ref.node.title, RecoveryFailureKind.CONFLICT,
                                "已接受宏版本与修复后的写入冲突", retryable = false)
                            skipped += item.ref.key
                            trace(item.ref, "error", "已接受宏版本与修复后的写入冲突")
                            continue
                        }
                        for ((address, appends) in publication.macroAppends) {
                            val old = stagedHistory[address].orEmpty()
                            stagedHistory[address] = old + appends
                        }
                        stagedState.putAll(publication.stateWrites)
                        stagedResults.putAll(publication.resultWrites)
                        stagedOrigins.putAll(publication.resultOrigins)
                        publication.resultWrites.keys.forEach { maskedKeys -= it }
                        publication.injectionRemovals.forEach { identity -> stagedInjections.removeAll {
                            it.id == identity.id && it.origin.resourceId == identity.resourceId
                        } }
                        stagedInjections += publication.injectionAdds
                        outcomes[item.ref.key] = receipt.outcome
                        executedOutput[item.ref.key] = stagedResults[item.ref.node.outputKey].orEmpty()
                        receipt.reply?.let { replies += it; orderReplies() }
                        replayedOldKeys += item.ref.key
                        if (!replayIncomplete()) priorFailures.keys.removeAll(acceptedReceipts.keys)
                        traces += RunTrace(item.ref.workflow.id, item.ref.node.id, item.ref.node.title, "done", "")
                        continue
                    }
                    val origin = ResultOrigin(
                        resourceByWorkflow.getValue(item.ref.workflow.id), item.ref.workflow.id,
                        item.ref.node.id, item.ref.node.resultCategory
                    )
                    val patches = item.outcome.injectionWrites.map { patch ->
                        val owner = origin.copy(category = "")
                        val target = if (patch.block == null) null else injectionTarget(
                            main, availableActions, patch.targetWorkflowId, patch.targetNodeId
                        )
                        Triple(patch, owner, target)
                    }
                    if (patches.any { (patch, owner, target) ->
                            val prior = stagedInjections.firstOrNull { it.id == patch.id &&
                                it.origin.resourceId == owner.resourceId }
                            (patch.block != null && target == null) ||
                            (prior != null && "${prior.targetWorkflowId}/${prior.targetNodeId}" in compiledModelTargets) ||
                            (target != null && target.key in compiledModelTargets)
                        }) {
                        priorFailures[item.ref.key] = RecoveryFailure(item.ref.key, item.ref.workflow.id,
                            item.ref.node.id, item.ref.node.title, RecoveryFailureKind.CONFIGURATION,
                            "提示词注入目标无效或目标模型已编译", retryable = false)
                        skipped += item.ref.key
                        trace(item.ref, "error", "提示词注入目标无效或目标模型已编译")
                        continue
                    }
                    val beforeResults = stagedResults.toMap()
                    val beforeOrigins = stagedOrigins.toMap()
                    val beforeHistoryLengths = stagedHistory.mapValues { it.value.size }
                    val ownAddress = "result.${item.ref.node.outputKey}"
                    val values = linkedMapOf<String, String>()
                    item.outcome.macroWrites.forEach { (address, value) ->
                        values[canonicalWriteAddress(address)!!] = value
                    }
                    if (item.ref.node.actionId == "macro.process") item.outcome.stateWrites.forEach { (key, value) ->
                        values["state.$key"] = value
                    }
                    val activations = item.outcome.activationWrites.mapKeys { (address, _) ->
                        canonicalWriteAddress(address)!!
                    }
                    // An activation of the action's own output changes its shape, not its text.
                    if (ownAddress !in activations) values.putIfAbsent(ownAddress, item.outcome.text)
                    for (address in (values.keys + activations.keys).distinct()) {
                        if (address !in stagedHistory && address.startsWith("result.")) {
                            val key = address.substringAfter("result.")
                            val oldValue = stagedResults[key]
                            if (oldValue != null) stagedHistory[address] = listOf(MacroVersion(
                                version = 0, text = oldValue, origin = stagedOrigins[key] ?: origin
                            ))
                        }
                        val readSession = snapshot(stagedState, visibleResults())
                        val current = MacroReferences.currentVersion(library, readSession, address)
                        val value = values[address] ?: (if (address.startsWith("state."))
                            stagedState[address.substring(6)] ?: current?.text
                        else current?.text ?: MacroReferences.baseValue(library, readSession, address)).orEmpty()
                        val enabled = activations[address] ?: current?.enabled ?: true
                        val priorContentOrigin = if (address.startsWith("result.")) {
                            current?.valueOrigin ?: current?.origin ?: stagedOrigins[address.substringAfter("result.")]
                                ?.takeIf { MacroReferences.originVisible(library, readSession, it) }
                        } else null
                        val contentOrigin = if (address in values) null else priorContentOrigin
                        MacroReferences.publish(stagedHistory, address, value, enabled, origin,
                            currentTurn, currentUserMessageId, contentOrigin)
                        if (address.startsWith("result.")) {
                            val key = address.substringAfter("result.")
                            stagedResults[key] = value
                            stagedOrigins[key] = contentOrigin ?: origin
                            maskedKeys -= key
                        } else if (address.startsWith("state.") && address in values) {
                            stagedState[address.substring(6)] = value
                        }
                    }
                    for ((patch, owner, target) in patches) {
                        stagedInjections.removeAll { it.id == patch.id &&
                            it.origin.resourceId == owner.resourceId }
                        if (patch.block != null) stagedInjections += PromptInjection(
                            patch.id, patch.block, owner, target!!.workflowId, target.nodeId,
                            patch.scope, if (patch.scope == InjectionScope.TURN) currentUserMessageId else null
                        )
                    }
                    stagedState.putAll(item.outcome.stateWrites)
                    val publishedText = stagedResults[item.ref.node.outputKey].orEmpty()
                    executedOutput[item.ref.key] = publishedText
                    var publishedReply: ChatMessage? = null
                    if (item.ref.node.visible && !processingEvent && publishedText.isNotBlank()) {
                        publishedReply = item.oldReply ?: ChatMessage(role = Role.ASSISTANT, text = publishedText,
                            outputSlot = item.ref.node.outputSlot, turnId = currentUserMessageId,
                            workflowId = item.ref.workflow.id, nodeId = item.ref.node.id,
                            speakerName = library.characters.firstOrNull { it.id == session.characterId }?.name)
                        replies += publishedReply
                        orderReplies()
                    }
                    val publication = RecoveryPublication(
                        stateWrites = item.outcome.stateWrites,
                        resultWrites = stagedResults.filter { (key, value) -> beforeResults[key] != value ||
                            "result.$key" in stagedHistory && stagedHistory["result.$key"].orEmpty().size > (beforeHistoryLengths["result.$key"] ?: 0) },
                        resultOrigins = stagedOrigins.filter { (key, value) -> beforeOrigins[key] != value ||
                            "result.$key" in stagedHistory && stagedHistory["result.$key"].orEmpty().size > (beforeHistoryLengths["result.$key"] ?: 0) },
                        macroAppends = stagedHistory.mapNotNull { (address, values) ->
                            val added = values.drop(beforeHistoryLengths[address] ?: 0)
                            if (added.isEmpty()) null else address to added
                        }.toMap(),
                        injectionRemovals = patches.map { (patch, owner, _) -> InjectionIdentity(owner.resourceId, patch.id) },
                        injectionAdds = patches.mapNotNull { (patch, owner, _) -> stagedInjections.firstOrNull {
                            it.id == patch.id && it.origin.resourceId == owner.resourceId
                        } }
                    )
                    outcomes[item.ref.key] = item.outcome
                    if (!item.replay) {
                        acceptedReceipts[item.ref.key] = RecoveryReceipt(item.ref.key, item.outcome, publishedReply, publication)
                        priorFailures.remove(item.ref.key)
                        lockedFailures.remove(item.ref.key)
                        if (replayIncomplete()) priorFailures[item.ref.key] = RecoveryFailure(item.ref.key,
                            item.ref.workflow.id, item.ref.node.id, item.ref.node.title,
                            RecoveryFailureKind.ACTION, "已接受，正在恢复后续步骤", provisional = true)
                    } else replayedOldKeys += item.ref.key
                    if (!replayIncomplete()) priorFailures.keys.removeAll(acceptedReceipts.keys)
                    if (!item.replay) progress()
                    trace(item.ref, "done", "")
                }
                validCompleted.flatMap { it.outcome.emittedEvents }.forEach { emittedEvents.addLast(it) }
                }
                suspend fun attempt(item: Prepared): Pair<Completed?, NodeExecutionFailure?> = try {
                    perform(item) to null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: NodeExecutionFailure) {
                    null to failure
                } catch (_: Exception) {
                    null to NodeExecutionFailure(item.ref)
                }
                // Pure model nodes have a predeclared, unique result address. Publish each
                // completed sibling before a slower peer finishes its retry budget.
                val earlyPublish = prepared.size > 1 && prepared.all {
                    it.ref.node.actionId == "model.generate" && it.ref.node.dependsOn.isEmpty() &&
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
            if (!drain("history.process")) return failed()
            val selected = processor.workflow.nodes.lastOrNull { it.enabled && it.outputKey == processor.outputKey &&
                "${processor.workflow.id}/${it.id}" in executedOutput }
            if (selected != null) {
                val text = executedOutput["${processor.workflow.id}/${selected.id}"].orEmpty()
                if (text.isNotBlank()) stagedDigests[main.id] = HistoryDigest(text,
                    processingCandidate.throughMessageId, processingCandidate.coveredFloors, processor.workflow.id)
                else trace(null, "skipped", "历史处理输出为空，保留原摘要")
            }
        }
        // Preparation finishes before user dispatch; reply.completed runs once after the interaction chain.
        if (!drain("turn.prepare")) return failed()
        if (!drain("user.message")) return failed()
        if (replies.isNotEmpty() && !drain("reply.completed")) return failed()
        currentCoroutineContext().ensureActive()
        if (priorFailures.isNotEmpty()) {
            val paused = partialResult()
            progress()
            return paused
        }
        return RunResult(stagedState.toMap(), stagedResults.toMap(), replies.toList(), traces.toList(),
            RunStatus.SUCCESS, stagedOrigins.toMap(), stagedHistory.toMap(), stagedInjections.toList(), stagedDigests.toMap(),
            unavailableResultKeys = maskedKeys.toSet())
    }
}
