package me.rerere.prompt

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/** Background-only scheduler. Frontend generation never waits for its semaphore. */
class EventRuntime(
    private val store: RuntimeStore,
    private val scope: CoroutineScope,
    parallelism: Int = 3,
    private val maxDepth: Int = 16,
    private val maxPending: Int = 128,
) {
    init {
        require(parallelism > 0 && maxDepth > 0 && maxPending > 0)
    }

    private val semaphore = Semaphore(parallelism)
    private val dispatchMutex = Mutex()
    private val actions = ConcurrentHashMap<String, TaskAction>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val executionStartedNanos = ConcurrentHashMap<String, Long>()
    private val seen = LinkedHashSet<String>()
    private val records = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks = records.asStateFlow()
    private val dispatchError = MutableStateFlow<String?>(null)
    val lastDispatchError = dispatchError.asStateFlow()
    private val dispatchProblemsState = MutableStateFlow<List<DispatchProblem>>(emptyList())
    val dispatchProblems = dispatchProblemsState.asStateFlow()

    fun registerAction(id: String, action: TaskAction) {
        require(id.isNotBlank())
        actions[id] = action
    }

    fun cancel(taskId: String) {
        jobs[taskId]?.cancel()
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
    }

    /** Build an advisory preview from one coherent detached store snapshot. */
    suspend fun preview(eventInput: RuntimeEvent): EventPreview = dispatchMutex.withLock {
        val event = detachedEvent(eventInput)
        val state = store.eventSnapshot()
        val active = records.value.count { !it.status.isTerminal }
        val plan = createPlan(event, state, active)
        plan.toPreview(active)
    }

    /** Returns immediately after enqueueing. A returned Job includes action and publication. */
    suspend fun dispatch(incomingEvent: RuntimeEvent): List<Job> = dispatchMutex.withLock {
        val incoming = detachedEvent(incomingEvent)
        val state = store.eventSnapshot()
        val active = records.value.count { !it.status.isTerminal }
        val plan = createPlan(incoming, state, active)
        // Duplicate and over-depth events are expected causal-chain stops. Preview reports
        // them, while dispatch keeps its historical no-op behavior.
        if (
            plan.issues.any {
                it.code == ProblemCode.DUPLICATE_EVENT || it.code == ProblemCode.DEPTH_LIMIT
            }
        )
            return@withLock emptyList()
        val fatal = plan.issues.firstOrNull { it.severity == ProblemSeverity.ERROR }
        if (fatal != null) throw RuntimeOperationException(fatal)

        // Invalid plans are rejected before this sequence-changing call. A zero order is only
        // materialized once selection, actions, dependency graph and queue capacity are valid.
        val event =
            if (incoming.order == 0L) incoming.copy(order = store.nextEventOrder()) else incoming
        // Keep planning and trigger-input identity/usability checks on the same store snapshot.
        // Execution rechecks current usability before the action and at publication.
        val triggerArtifacts = plan.triggerArtifacts
        scope.coroutineContext.ensureActive()
        val tickets =
            store.beginAll(
                references = plan.executableRules.map { it.output },
                scope = event.scope,
                sources = event.sources,
                eventOrder = event.order,
                expectedConfigurationRevision = state.configurationRevision,
            )
        val outcomes = plan.selectedRules.associate { it.id to CompletableDeferred<Artifact?>() }
        val ticketByReference = tickets.associateBy { it.reference }
        val ticketByRule =
            plan.executableRules
                .mapNotNull { rule ->
                    ticketByReference[rule.output]?.let { rule.id to it }
                }
                .toMap()
        val scheduled = mutableListOf<Job>()
        plan.selectedRules
            .filter { it.id !in ticketByRule }
            .forEach { rule ->
                // Disabled outputs and fences rejected by the store have no artifact in this batch.
                outcomes.getValue(rule.id).complete(null)
            }
        for (rule in plan.executableRules) {
            val ticket = ticketByRule[rule.id] ?: continue
            val action = plan.actions.getValue(rule.id)
            val record =
                TaskRecord(
                    id = ticket.id,
                    eventId = event.id,
                    ruleId = rule.id,
                    scope = event.scope,
                    status = TaskStatus.QUEUED,
                    rootId = event.rootId,
                    queuedAtMillis = System.currentTimeMillis(),
                )
            records.update { trim(it) + record }
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        if (rule.dependsOn.isNotEmpty()) update(ticket.id, TaskStatus.WAITING)
                        val dependencies = awaitDependencies(rule.dependsOn, outcomes)
                        val inputs =
                            (triggerArtifacts + dependencies.values).distinctBy { it.ticket.id }
                        semaphore.withPermit {
                            if (!store.isCurrent(ticket)) {
                                update(
                                    ticket.id,
                                    TaskStatus.SUPERSEDED,
                                    problem =
                                        problem(
                                            ProblemCode.PUBLICATION_OBSOLETE,
                                            "Publication ticket is no longer current",
                                        ),
                                )
                                return@withPermit
                            }
                            if (!store.areUsable(inputs)) {
                                update(
                                    ticket.id,
                                    TaskStatus.SKIPPED,
                                    error = "Dependency was superseded or disabled",
                                    problem =
                                        problem(
                                            ProblemCode.DEPENDENCY_UNAVAILABLE,
                                            "Dependency was superseded or disabled",
                                        ),
                                )
                                return@withPermit
                            }
                            val startedAt = System.currentTimeMillis()
                            val startedNanos = System.nanoTime()
                            executionStartedNanos[ticket.id] = startedNanos
                            update(
                                ticket.id,
                                TaskStatus.RUNNING,
                                startedAtMillis = startedAt,
                                attemptCount = 1,
                            )
                            val text =
                                try {
                                    withTimeout(rule.timeoutMillis) {
                                        action.execute(
                                            TaskInput(event, rule, dependencies, triggerArtifacts)
                                        )
                                    }
                                } catch (error: TimeoutCancellationException) {
                                    throw TaskExecutionFailure(
                                        problem(
                                            ProblemCode.TIMEOUT,
                                            "Task timed out",
                                            "rules.${rule.id}",
                                        ),
                                        TaskStatus.FAILED,
                                        error,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    throw TaskExecutionFailure(
                                        problem(
                                            ProblemCode.ACTION_FAILED,
                                            error.message ?: error.javaClass.simpleName,
                                            "rules.${rule.id}",
                                        ),
                                        TaskStatus.FAILED,
                                        error,
                                    )
                                }
                            currentCoroutineContext().ensureActive()
                            val artifact =
                                try {
                                    store.publish(ticket, text, inputs)
                                } catch (error: RuntimeOperationException) {
                                    throw TaskExecutionFailure(
                                        error.problem,
                                        TaskStatus.FAILED,
                                        error,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    // Publication/storage failures are not model or action
                                    // failures.
                                    throw TaskExecutionFailure(
                                        problem(
                                            ProblemCode.STORAGE_FAILURE,
                                            error.message ?: error.javaClass.simpleName,
                                            "rules.${rule.id}",
                                        ),
                                        TaskStatus.FAILED,
                                        error,
                                    )
                                }
                            val elapsed = elapsedSince(startedNanos)
                            if (artifact == null) {
                                update(
                                    ticket.id,
                                    TaskStatus.SUPERSEDED,
                                    error = "Publication became obsolete",
                                    problem =
                                        problem(
                                            ProblemCode.PUBLICATION_OBSOLETE,
                                            "Publication became obsolete",
                                        ),
                                    finishedAtMillis = System.currentTimeMillis(),
                                    elapsedExecutionMillis = elapsed,
                                )
                            } else {
                                update(
                                    ticket.id,
                                    TaskStatus.SUCCEEDED,
                                    artifactId = artifact.ticket.id,
                                    finishedAtMillis = System.currentTimeMillis(),
                                    elapsedExecutionMillis = elapsed,
                                )
                            }
                            outcomes.getValue(rule.id).complete(artifact)
                            if (artifact != null) {
                                val childEvent =
                                    RuntimeEvent(
                                        type = ARTIFACT_PUBLISHED,
                                        scope = event.scope,
                                        sources = event.sources,
                                        data =
                                            mapOf(
                                                "provider" to rule.output.provider,
                                                "reference" to rule.output.name,
                                                "text" to artifact.text,
                                                "artifactId" to artifact.ticket.id,
                                            ),
                                        rootId = event.rootId,
                                        visitedRules =
                                            event.visitedRules +
                                                plan.ancestors.getValue(rule.id) +
                                                rule.id,
                                        order = event.order,
                                        inputArtifacts = listOf(artifact.ticket.id),
                                    )
                                try {
                                    dispatch(childEvent)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    recordDispatchProblem(
                                        childEvent.id,
                                        event.rootId,
                                        (error as? RuntimeOperationException)?.problem
                                            ?: problem(
                                                ProblemCode.INVALID_PLAN,
                                                error.message ?: error.javaClass.simpleName,
                                            ),
                                    )
                                }
                            }
                        }
                    } catch (error: DependencyUnavailable) {
                        update(
                            ticket.id,
                            TaskStatus.SKIPPED,
                            error = error.message,
                            problem =
                                problem(
                                    ProblemCode.DEPENDENCY_UNAVAILABLE,
                                    error.message ?: "Dependency unavailable",
                                ),
                        )
                    } catch (error: TaskExecutionFailure) {
                        update(
                            ticket.id,
                            error.status,
                            error = error.problem.message,
                            problem = error.problem,
                        )
                    } catch (error: CancellationException) {
                        update(
                            ticket.id,
                            TaskStatus.CANCELLED,
                            error = "Task cancelled",
                            problem = problem(ProblemCode.CANCELLED, "Task cancelled"),
                        )
                        throw error
                    } catch (error: Exception) {
                        val structured =
                            (error as? RuntimeOperationException)?.problem
                                ?: problem(
                                    ProblemCode.STORAGE_FAILURE,
                                    error.message ?: error.javaClass.simpleName,
                                )
                        update(
                            ticket.id,
                            TaskStatus.FAILED,
                            error = structured.message,
                            problem = structured,
                        )
                    } finally {
                        outcomes.getValue(rule.id).complete(null)
                    }
                }
            jobs[ticket.id] = job
            job.invokeOnCompletion { cause ->
                outcomes.getValue(rule.id).complete(null)
                jobs.remove(ticket.id)
                if (cause is CancellationException) {
                    update(
                        ticket.id,
                        TaskStatus.CANCELLED,
                        error = "Task cancelled",
                        problem = problem(ProblemCode.CANCELLED, "Task cancelled"),
                    )
                }
            }
            scheduled += job
        }
        seen.add(event.id)
        if (seen.size > 1024) seen.remove(seen.first())
        scheduled.forEach { it.start() }
        scheduled
    }

    private fun createPlan(event: RuntimeEvent, state: RuntimeState, active: Int) =
        EventPlan.create(
            event = event,
            state = state,
            activeTasks = active,
            maxDepth = maxDepth,
            maxPending = maxPending,
            actions = actions.toMap(),
            alreadySeen = event.id in seen,
        )

    private fun detachedEvent(event: RuntimeEvent) =
        event.copy(
            scope = event.scope.copy(),
            sources = event.sources.map { it.copy() },
            data = event.data.toMap(),
            visitedRules = event.visitedRules.toSet(),
            inputArtifacts = event.inputArtifacts.toList(),
        )

    private fun EventDispatchPlan.toPreview(active: Int): EventPreview =
        EventPreview(
            eventId = event.id,
            rootId = event.rootId,
            configurationRevision = state.configurationRevision,
            selectedRules =
                selectedRules.map { rule ->
                    PlannedRule(
                        rule.id,
                        rule.actionId,
                        rule.output,
                        rule.dependsOn,
                        registeredActionIds.contains(rule.actionId),
                        rule.output.provider in
                            state.providers.filter { it.enabled }.map { it.id }.toSet(),
                    )
                },
            excludedRules = excludedRules,
            issues = issues,
            availableQueueSlots = (maxPending - active).coerceAtLeast(0),
        )

    private class DependencyUnavailable(id: String) : Exception("Dependency unavailable: $id")

    private class TaskExecutionFailure(
        val problem: RuntimeProblem,
        val status: TaskStatus,
        cause: Throwable,
    ) : Exception(problem.message, cause)

    /** Observe all prerequisites so one failure does not wait behind another slow task. */
    private suspend fun awaitDependencies(
        ids: Set<String>,
        outcomes: Map<String, CompletableDeferred<Artifact?>>,
    ): Map<String, Artifact> {
        val pending = ids.toMutableSet()
        val results = mutableMapOf<String, Artifact>()
        while (pending.isNotEmpty()) {
            val (id, artifact) =
                select<Pair<String, Artifact?>> {
                    pending.forEach { id -> outcomes.getValue(id).onAwait { id to it } }
                }
            results[id] = artifact ?: throw DependencyUnavailable(id)
            pending.remove(id)
        }
        return ids.associateWith { results.getValue(it) }
    }

    private fun update(
        id: String,
        status: TaskStatus,
        error: String? = null,
        artifactId: String? = null,
        problem: RuntimeProblem? = null,
        startedAtMillis: Long? = null,
        finishedAtMillis: Long? = null,
        elapsedExecutionMillis: Long? = null,
        attemptCount: Int? = null,
    ) {
        // StateFlow.update may rerun its lambda after a concurrent write, so keep the
        // calculation pure and remove the monotonic start only after the update succeeds.
        val terminalElapsed =
            elapsedExecutionMillis
                ?: if (status.isTerminal) executionStartedNanos[id]?.let(::elapsedSince) else null
        records.update { current ->
            current.map { record ->
                if (record.id != id || record.status.isTerminal) record
                else
                    record.copy(
                        status = status,
                        error = error,
                        artifactId = artifactId,
                        problem = problem,
                        startedAtMillis = startedAtMillis ?: record.startedAtMillis,
                        finishedAtMillis =
                            if (status.isTerminal) finishedAtMillis ?: System.currentTimeMillis()
                            else null,
                        elapsedExecutionMillis = terminalElapsed ?: record.elapsedExecutionMillis,
                        attemptCount = attemptCount ?: record.attemptCount,
                    )
            }
        }
        if (status.isTerminal) executionStartedNanos.remove(id)
    }

    private fun elapsedSince(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000

    private fun recordDispatchProblem(eventId: String, rootId: String, problem: RuntimeProblem) {
        dispatchProblemsState.update {
            (it + DispatchProblem(eventId, rootId, problem)).takeLast(256)
        }
        dispatchError.value = problem.message
    }

    private fun trim(records: List<TaskRecord>): List<TaskRecord> {
        if (records.size < 512) return records
        return records.filter { !it.status.isTerminal } +
            records.filter { it.status.isTerminal }.takeLast(256)
    }

    private fun problem(code: ProblemCode, message: String, target: String? = null) =
        RuntimeProblem(code, ProblemSeverity.ERROR, message, target)

    companion object {
        const val ARTIFACT_PUBLISHED = "artifact.published"
    }
}
