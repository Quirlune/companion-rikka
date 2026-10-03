package me.rerere.prompt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EventRuntimeTest {
    private val context = ContextScope("agent", "chat", "main")

    private fun event() = RuntimeEvent("reply", context, listOf(SourceVersion("m1", "v1")))

    @Test
    fun `preview rejects missing actions competing writers and queue overflow before tickets`() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                suspend fun check(
                    rules: List<EventRule>,
                    installed: Set<String>,
                    pending: Int,
                    expected: ProblemCode,
                ) {
                    val store = RuntimeStore(MemoryRuntimePersistence())
                    val engine = EventRuntime(store, owner, maxPending = pending)
                    rules
                        .map { it.output }
                        .distinct()
                        .forEach { store.register(ReferenceDefinition(it, "Output")) }
                    rules.forEach { store.putRule(it) }
                    installed.forEach { engine.registerAction(it) { "done" } }
                    val event = event()
                    val before = store.eventSnapshot()
                    assertTrue(engine.preview(event).issues.any { it.code == expected })
                    val rejected = runCatching { engine.dispatch(event) }.exceptionOrNull()
                    assertEquals(expected, (rejected as RuntimeOperationException).problem.code)
                    assertEquals(before.sequence, store.eventSnapshot().sequence)
                    assertTrue(store.eventSnapshot().tickets.isEmpty())
                    assertTrue(engine.tasks.value.isEmpty())
                }
                val a = ReferenceId("test", "a")
                val b = ReferenceId("test", "b")
                check(
                    listOf(EventRule("a", "reply", "missing", a)),
                    emptySet(),
                    2,
                    ProblemCode.UNKNOWN_ACTION,
                )
                check(
                    listOf(
                        EventRule("a", "reply", "action", a),
                        EventRule("b", "reply", "action", a),
                    ),
                    setOf("action"),
                    2,
                    ProblemCode.COMPETING_WRITERS,
                )
                check(
                    listOf(
                        EventRule("a", "reply", "action", a),
                        EventRule("b", "reply", "action", b),
                    ),
                    setOf("action"),
                    1,
                    ProblemCode.QUEUE_REJECTED,
                )
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun `publication storage failure is distinguished from action failure`() = runBlocking {
        val persistence =
            object : RuntimePersistence {
                private var state: RuntimeState? = null

                override suspend fun load() = state

                override suspend fun save(state: RuntimeState) {
                    if (state.artifacts.isNotEmpty()) error("disk unavailable")
                    this.state = state
                }
            }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(persistence)
            val engine = EventRuntime(store, owner)
            val output = ReferenceId("test", "out")
            store.register(ReferenceDefinition(output, "Output"))
            store.putRule(EventRule("rule", "reply", "action", output))
            engine.registerAction("action") { "done" }
            withTimeout(5000) { engine.dispatch(event()).joinAll() }
            val record = engine.tasks.value.single()
            assertEquals(TaskStatus.FAILED, record.status)
            assertEquals(ProblemCode.STORAGE_FAILURE, record.problem?.code)
            assertEquals(1, record.attemptCount)
            assertTrue(record.elapsedExecutionMillis != null)
            assertTrue(store.artifacts(context).isEmpty())
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `downstream plan failure retains parent success and child correlation`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            val parentOutput = ReferenceId("test", "parent")
            val childOutput = ReferenceId("test", "child")
            store.register(ReferenceDefinition(parentOutput, "Parent"))
            store.register(ReferenceDefinition(childOutput, "Child"))
            store.putRule(EventRule("parent", "reply", "parent-action", parentOutput))
            store.putRule(
                EventRule("child", EventRuntime.ARTIFACT_PUBLISHED, "missing-action", childOutput)
            )
            engine.registerAction("parent-action") { "done" }
            val event = event()
            withTimeout(5000) { engine.dispatch(event).joinAll() }
            val record = engine.tasks.value.single()
            assertEquals(TaskStatus.SUCCEEDED, record.status)
            val downstream = engine.dispatchProblems.value.single()
            assertEquals(event.rootId, downstream.rootId)
            assertTrue(downstream.eventId != event.id)
            assertEquals(ProblemCode.UNKNOWN_ACTION, downstream.problem.code)
            assertEquals(TaskStatus.SUCCEEDED, engine.tasks.value.single().status)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `dispatch fixes caller event collections before asynchronous action`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            val output = ReferenceId("test", "out")
            store.register(ReferenceDefinition(output, "Output"))
            store.putRule(EventRule("rule", "reply", "action", output))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sources = mutableListOf(SourceVersion("m1", "v1"))
            val data = mutableMapOf("key" to "original")
            val visited = mutableSetOf<String>()
            val inputs = mutableListOf<String>()
            val event =
                RuntimeEvent(
                    "reply",
                    context,
                    sources,
                    data,
                    visitedRules = visited,
                    inputArtifacts = inputs,
                )
            engine.registerAction("action") { input ->
                entered.complete(Unit)
                release.await()
                assertEquals(listOf(SourceVersion("m1", "v1")), input.event.sources)
                assertEquals("original", input.event.data["key"])
                assertTrue(input.event.visitedRules.isEmpty())
                assertTrue(input.event.inputArtifacts.isEmpty())
                "done"
            }
            val jobs = engine.dispatch(event)
            withTimeout(5000) { entered.await() }
            sources += SourceVersion("m2", "v2")
            data["key"] = "changed"
            visited += "rule"
            inputs += "missing"
            release.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            assertEquals(TaskStatus.SUCCEEDED, engine.tasks.value.single().status)
            assertEquals(
                listOf(SourceVersion("m1", "v1")),
                store.artifacts(context).single().ticket.sources,
            )
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `independent tasks start in parallel without waiting for siblings`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner, parallelism = 2)
            val enteredA = CompletableDeferred<Unit>()
            val enteredB = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            engine.registerAction("A") {
                enteredA.complete(Unit)
                release.await()
                "A"
            }
            engine.registerAction("B") {
                enteredB.complete(Unit)
                release.await()
                "B"
            }
            for (name in listOf("A", "B")) {
                val ref = ReferenceId("test", name)
                store.register(ReferenceDefinition(ref, name))
                store.putRule(EventRule(name, "reply", name, ref))
            }
            val event = event()
            val jobs = engine.dispatch(event)
            withTimeout(5000) {
                enteredA.await()
                enteredB.await()
            }
            assertEquals(2, engine.tasks.value.count { it.status == TaskStatus.RUNNING })
            assertTrue(engine.dispatch(event).isEmpty())
            release.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            assertEquals(2, store.artifacts(context).size)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `failure and cancellation do not cancel siblings or publish partial results`() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = RuntimeStore(MemoryRuntimePersistence())
                val engine = EventRuntime(store, owner)
                val failStarted = CompletableDeferred<Unit>()
                val cancelStarted = CompletableDeferred<Unit>()
                val okStarted = CompletableDeferred<Unit>()
                engine.registerAction("fail") {
                    failStarted.complete(Unit)
                    error("broken")
                }
                engine.registerAction("cancel") {
                    cancelStarted.complete(Unit)
                    awaitCancellation()
                }
                engine.registerAction("ok") {
                    okStarted.complete(Unit)
                    "complete"
                }
                for (name in listOf("fail", "cancel", "ok")) {
                    val ref = ReferenceId("test", name)
                    store.register(ReferenceDefinition(ref, name))
                    store.putRule(EventRule(name, "reply", name, ref))
                }
                val jobs = engine.dispatch(event())
                withTimeout(5000) {
                    failStarted.await()
                    cancelStarted.await()
                    okStarted.await()
                }
                val cancelled = engine.tasks.value.single { it.ruleId == "cancel" }
                engine.cancel(cancelled.id)
                withTimeout(5000) { jobs.joinAll() }
                assertEquals(
                    setOf(TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.SUCCEEDED),
                    engine.tasks.value.map { it.status }.toSet(),
                )
                assertTrue(engine.tasks.value.all { it.attemptCount == 1 })
                assertTrue(
                    engine.tasks.value.all {
                        it.startedAtMillis != null && it.finishedAtMillis != null
                    }
                )
                assertTrue(engine.tasks.value.all { it.elapsedExecutionMillis != null })
                assertTrue(
                    engine.tasks.value.single { it.status == TaskStatus.FAILED }.problem?.code ==
                        ProblemCode.ACTION_FAILED
                )
                assertTrue(
                    engine.tasks.value.single { it.status == TaskStatus.CANCELLED }.problem?.code ==
                        ProblemCode.CANCELLED
                )
                assertEquals("complete", store.artifacts(context).single().text)
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun `published result triggers a followup and repeated rule cannot loop`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner, parallelism = 1, maxPending = 1)
            val first = ReferenceId("test", "first")
            val next = ReferenceId("test", "next")
            store.register(ReferenceDefinition(first, "First"))
            store.register(ReferenceDefinition(next, "Next"))
            engine.registerAction("first") { "seed" }
            engine.registerAction("next") { it.event.data.getValue("text") + "!" }
            store.putRule(EventRule("first", "reply", "first", first))
            store.putRule(EventRule("next", EventRuntime.ARTIFACT_PUBLISHED, "next", next))
            engine.dispatch(event()).joinAll()
            withTimeout(5000) {
                while (engine.tasks.value.count { it.status == TaskStatus.SUCCEEDED } < 2) delay(10)
            }
            assertEquals(listOf("seed", "seed!"), store.artifacts(context).map { it.text })
            val artifacts = store.artifacts(context)
            assertEquals(listOf(artifacts.first().ticket.id), artifacts.last().dependencies)
            store.setEnabled("test", false)
            assertFalse(store.areUsable(listOf(artifacts.last())))
            assertEquals(2, engine.tasks.value.size)
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `conditions and assistant filters exclude unrelated events`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            val ref = ReferenceId("test", "out")
            store.register(ReferenceDefinition(ref, "Output"))
            engine.registerAction("action") { error("Must not execute") }
            store.putRule(
                EventRule(
                    "rule",
                    "reply",
                    "action",
                    ref,
                    condition = EventCondition.All(listOf(EventCondition.Equals("tag", "yes"))),
                    assistantId = "agent",
                )
            )
            assertTrue(engine.dispatch(event()).isEmpty())
            assertTrue(
                engine
                    .dispatch(
                        event()
                            .copy(
                                scope = context.copy(assistantId = "other"),
                                data = mapOf("tag" to "yes"),
                            )
                    )
                    .isEmpty()
            )
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `timeout is a failure and never publishes an artifact`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            val ref = ReferenceId("test", "out")
            store.register(ReferenceDefinition(ref, "Output"))
            store.putRule(EventRule("rule", "reply", "action", ref, timeoutMillis = 25))
            engine.registerAction("action") { awaitCancellation() }
            withTimeout(5000) { engine.dispatch(event()).joinAll() }
            assertEquals(TaskStatus.FAILED, engine.tasks.value.single().status)
            assertEquals(ProblemCode.TIMEOUT, engine.tasks.value.single().problem?.code)
            assertNotNull(engine.tasks.value.single().elapsedExecutionMillis)
            assertTrue(store.artifacts(context).isEmpty())
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `preview is side effect free and duplicate or deep dispatch remains a no op`() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = RuntimeStore(MemoryRuntimePersistence())
                val engine = EventRuntime(store, owner, maxDepth = 1)
                val ref = ReferenceId("test", "out")
                store.register(ReferenceDefinition(ref, "Output"))
                engine.registerAction("action") { "done" }
                store.putRule(EventRule("rule", "reply", "action", ref))
                val incoming = event()
                val before = store.eventSnapshot()

                val preview = engine.preview(incoming)
                assertTrue(preview.valid)
                assertEquals(1, preview.selectedRules.size)
                assertTrue(preview.selectedRules.single().actionAvailable)
                assertTrue(engine.tasks.value.isEmpty())
                assertEquals(before.sequence, store.eventSnapshot().sequence)

                val jobs = engine.dispatch(incoming)
                withTimeout(5000) { jobs.joinAll() }
                assertTrue(engine.dispatch(incoming).isEmpty())
                assertTrue(
                    engine.preview(incoming).issues.any { it.code == ProblemCode.DUPLICATE_EVENT }
                )

                val deep = incoming.copy(id = "deep", visitedRules = setOf("previous"))
                assertTrue(engine.preview(deep).issues.any { it.code == ProblemCode.DEPTH_LIMIT })
                assertTrue(engine.dispatch(deep).isEmpty())
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun `bad trigger artifact is rejected by preview and dispatch before reservation`() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = RuntimeStore(MemoryRuntimePersistence())
                val engine = EventRuntime(store, owner)
                val output = ReferenceId("test", "out")
                val input = ReferenceId("source", "input")
                store.register(ReferenceDefinition(output, "Output"))
                store.register(ReferenceDefinition(input, "Input"))
                val foreignScope = context.copy(conversationId = "another-chat")
                val artifact =
                    store.publish(store.begin(input, foreignScope, event().sources)!!, "private")!!
                var called = false
                engine.registerAction("action") {
                    called = true
                    "done"
                }
                store.putRule(EventRule("rule", "reply", "action", output))
                val bad = event().copy(inputArtifacts = listOf(artifact.ticket.id))

                assertTrue(
                    engine.preview(bad).issues.any { it.code == ProblemCode.REFERENCE_UNAVAILABLE }
                )
                assertTrue(
                    runCatching { engine.dispatch(bad) }.exceptionOrNull()
                        is RuntimeOperationException
                )
                assertFalse(called)
                assertTrue(engine.tasks.value.isEmpty())
                assertTrue(store.artifacts(context).isEmpty())
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun `disabled output is inspectable without action and enabled dependent is skipped`() =
        runBlocking {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = RuntimeStore(MemoryRuntimePersistence())
                val engine = EventRuntime(store, owner)
                val disabledOutput = ReferenceId("disabled", "out")
                val joinedOutput = ReferenceId("joined", "out")
                store.register(ReferenceDefinition(disabledOutput, "Disabled"))
                store.register(ReferenceDefinition(joinedOutput, "Joined"))
                store.putRule(EventRule("disabled-rule", "reply", "not-installed", disabledOutput))
                store.putRule(
                    EventRule(
                        "join",
                        "reply",
                        "join",
                        joinedOutput,
                        dependsOn = setOf("disabled-rule"),
                    )
                )
                engine.registerAction("join") { error("join must be skipped") }
                store.setEnabled("disabled", false)

                val preview = engine.preview(event())
                assertTrue(
                    preview.issues.none {
                        it.code == ProblemCode.UNKNOWN_ACTION &&
                            it.targetPath == "rules.disabled-rule.actionId"
                    }
                )
                assertTrue(
                    preview.selectedRules
                        .single { it.ruleId == "disabled-rule" }
                        .let { !it.outputEnabled && !it.actionAvailable }
                )
                engine.registerAction("not-installed") { "unused" }
                assertTrue(
                    engine
                        .preview(event())
                        .selectedRules
                        .single { it.ruleId == "disabled-rule" }
                        .actionAvailable
                )
                val jobs = engine.dispatch(event())
                withTimeout(5000) { jobs.joinAll() }
                assertEquals(TaskStatus.SKIPPED, engine.tasks.value.single().status)
                assertEquals("join", engine.tasks.value.single().ruleId)
            } finally {
                owner.cancel()
            }
        }

    @Test
    fun `cancelled while queued remains unstarted without an elapsed duration`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner, parallelism = 1)
            val blocker = ReferenceId("test", "blocker")
            val queued = ReferenceId("test", "queued")
            store.register(ReferenceDefinition(blocker, "Blocker"))
            store.register(ReferenceDefinition(queued, "Queued"))
            store.putRule(EventRule("blocker", "reply", "blocker", blocker))
            store.putRule(EventRule("queued", "reply", "queued", queued))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            engine.registerAction("blocker") {
                started.complete(Unit)
                release.await()
                "done"
            }
            engine.registerAction("queued") { "must not start" }

            val jobs = engine.dispatch(event())
            withTimeout(5000) { started.await() }
            val queuedRecord = engine.tasks.value.single { it.ruleId == "queued" }
            assertEquals(TaskStatus.QUEUED, queuedRecord.status)
            engine.cancel(queuedRecord.id)
            withTimeout(5000) {
                while (
                    engine.tasks.value.single { it.ruleId == "queued" }.status !=
                        TaskStatus.CANCELLED
                ) delay(1)
            }
            val cancelled = engine.tasks.value.single { it.ruleId == "queued" }
            assertEquals(0, cancelled.attemptCount)
            assertNull(cancelled.startedAtMillis)
            assertNull(cancelled.elapsedExecutionMillis)

            release.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `configuration change after planning fails before output reservation`() = runBlocking {
        val persistence = EventOrderGatePersistence()
        val store = RuntimeStore(persistence)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = EventRuntime(store, owner)
            val output = ReferenceId("test", "out")
            val concurrentOutput = ReferenceId("test", "concurrent")
            store.register(ReferenceDefinition(output, "Output"))
            store.register(ReferenceDefinition(concurrentOutput, "Concurrent"))
            store.putRule(EventRule("rule", "reply", "action", output))
            engine.registerAction("action") { "done" }

            val dispatch =
                owner.async(start = CoroutineStart.UNDISPATCHED) { engine.dispatch(event()) }
            withTimeout(5000) { persistence.eventOrderSaveStarted.await() }
            // Enter applyConfiguration while dispatch still owns the store mutex in nextEventOrder.
            val mutation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.putRule(
                        EventRule("concurrent", "reply", "concurrent-action", concurrentOutput)
                    )
                }
            persistence.releaseEventOrderSave.complete(Unit)
            mutation.await()
            val error = runCatching { dispatch.await() }.exceptionOrNull()
            assertTrue(error is RuntimeOperationException)
            assertEquals(
                ProblemCode.CONFIGURATION_CONFLICT,
                (error as RuntimeOperationException).problem.code,
            )
            assertTrue(engine.tasks.value.isEmpty())
            assertTrue(store.artifacts(context).isEmpty())
        } finally {
            persistence.releaseEventOrderSave.complete(Unit)
            owner.cancel()
        }
    }

    private class EventOrderGatePersistence : RuntimePersistence {
        private var state: RuntimeState? = null
        val eventOrderSaveStarted = CompletableDeferred<Unit>()
        val releaseEventOrderSave = CompletableDeferred<Unit>()

        override suspend fun load(): RuntimeState? = state

        override suspend fun save(state: RuntimeState) {
            if (state.sequence == 1L && !eventOrderSaveStarted.isCompleted) {
                eventOrderSaveStarted.complete(Unit)
                releaseEventOrderSave.await()
            }
            this.state = state
        }
    }
}
