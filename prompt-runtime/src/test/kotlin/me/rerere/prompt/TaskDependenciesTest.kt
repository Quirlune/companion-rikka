package me.rerere.prompt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TaskDependenciesTest {
    private val scope = ContextScope("agent", "chat", "main")
    private val event = RuntimeEvent("reply", scope, listOf(SourceVersion("m", "v")))

    @Test
    fun `overlapping event batches never mix their join inputs`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner, parallelism = 3)
            rule(store, "A")
            rule(store, "B")
            rule(store, "C", setOf("A", "B"))
            val oldEntered = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            engine.registerAction("A") {
                if (it.event.data["batch"] == "old") {
                    oldEntered.complete(Unit)
                    releaseOld.await()
                }
                it.event.data.getValue("batch")
            }
            engine.registerAction("B") { it.event.data.getValue("batch") }
            engine.registerAction("C") {
                val values = it.dependencies.values.map { artifact -> artifact.text }
                assertEquals(setOf(it.event.data.getValue("batch")), values.toSet())
                values.joinToString("+")
            }
            val old = engine.dispatch(event.copy(id = "old", data = mapOf("batch" to "old")))
            withTimeout(5000) { oldEntered.await() }
            val newer = engine.dispatch(event.copy(id = "new", data = mapOf("batch" to "new")))
            withTimeout(5000) { newer.joinAll() }
            releaseOld.complete(Unit)
            withTimeout(5000) { old.joinAll() }
            assertEquals(
                "new+new",
                store.artifacts(scope).single { it.ticket.reference.provider == "C" }.text,
            )
            assertEquals(
                TaskStatus.SKIPPED,
                engine.tasks.value.single { it.eventId == "old" && it.ruleId == "C" }.status,
            )
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `one failed prerequisite skips join while another is still running`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            rule(store, "C", linkedSetOf("A", "B"))
            rule(store, "A")
            rule(store, "B")
            val entered = CompletableDeferred<Unit>()
            engine.registerAction("A") {
                entered.complete(Unit)
                awaitCancellation()
            }
            engine.registerAction("B") {
                entered.await()
                error("failed")
            }
            engine.registerAction("C") { error("must not run") }
            val jobs = engine.dispatch(event)
            withTimeout(5000) {
                engine.tasks.first { records ->
                    records.any { it.ruleId == "C" && it.status == TaskStatus.SKIPPED }
                }
            }
            assertEquals(TaskStatus.RUNNING, engine.tasks.value.single { it.ruleId == "A" }.status)
            engine.cancelAll()
            withTimeout(5000) { jobs.joinAll() }
        } finally {
            owner.cancel()
        }
    }

    private suspend fun rule(
        store: RuntimeStore,
        name: String,
        dependencies: Set<String> = emptySet(),
    ) {
        val output = ReferenceId(name, "result")
        store.register(ReferenceDefinition(output, name))
        store.putRule(EventRule(name, "reply", name, output, dependsOn = dependencies))
    }

    @Test
    fun `join receives exact parallel outputs and waits outside execution permits`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner, parallelism = 2)
            // Deliberately register the dependent first: queue order must not deadlock its inputs.
            rule(store, "C", setOf("A", "B"))
            rule(store, "A")
            rule(store, "B")
            val enteredA = CompletableDeferred<Unit>()
            val enteredB = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            engine.registerAction("A") {
                enteredA.complete(Unit)
                release.await()
                "a"
            }
            engine.registerAction("B") {
                enteredB.complete(Unit)
                release.await()
                "b"
            }
            engine.registerAction("C") { input ->
                assertEquals(setOf("A", "B"), input.dependencies.keys)
                input.dependencies.getValue("A").text + input.dependencies.getValue("B").text
            }
            val jobs = engine.dispatch(event)
            withTimeout(5000) {
                enteredA.await()
                enteredB.await()
            }
            assertFalse(
                engine.tasks.value.any { it.ruleId == "C" && it.status == TaskStatus.RUNNING }
            )
            release.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            val output = store.artifacts(scope).single { it.ticket.reference.provider == "C" }
            assertEquals("ab", output.text)
            assertEquals(2, output.dependencies.size)
            assertTrue(engine.tasks.value.all { it.status == TaskStatus.SUCCEEDED })
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `failed cancelled and disabled prerequisites skip dependents without using old outputs`() =
        runBlocking {
            for (mode in listOf("failed", "cancelled", "disabled")) {
                val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val store = RuntimeStore(MemoryRuntimePersistence())
                    val engine = EventRuntime(store, owner, parallelism = 1)
                    rule(store, "C", setOf("A"))
                    rule(store, "A")
                    rule(store, "B")
                    val ref = ReferenceId("A", "result")
                    store.publish(store.begin(ref, scope, event.sources)!!, "old result")
                    if (mode == "disabled") store.setEnabled("A", false)
                    val entered = CompletableDeferred<Unit>()
                    engine.registerAction("A") {
                        entered.complete(Unit)
                        if (mode == "failed") error("failed") else awaitCancellation()
                    }
                    engine.registerAction("B") { "independent" }
                    engine.registerAction("C") { error("Must never run with old or missing input") }
                    val jobs = engine.dispatch(event)
                    if (mode == "cancelled") {
                        withTimeout(5000) { entered.await() }
                        engine.cancel(engine.tasks.value.single { it.ruleId == "A" }.id)
                    }
                    withTimeout(5000) { jobs.joinAll() }
                    assertEquals(
                        mode,
                        TaskStatus.SKIPPED,
                        engine.tasks.value.single { it.ruleId == "C" }.status,
                    )
                    assertEquals(
                        TaskStatus.SUCCEEDED,
                        engine.tasks.value.single { it.ruleId == "B" }.status,
                    )
                    assertTrue(store.artifacts(scope).none { it.ticket.reference.provider == "C" })
                } finally {
                    owner.cancel()
                }
            }
        }

    @Test
    fun `invalid graphs reject before reserving outputs or executing actions`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            rule(store, "A", setOf("B"))
            rule(store, "B", setOf("A"))
            engine.registerAction("A") { error("not executed") }
            engine.registerAction("B") { error("not executed") }
            val ticket = store.begin(ReferenceId("A", "result"), scope, event.sources)!!
            assertTrue(
                runCatching { engine.dispatch(event) }.exceptionOrNull() is IllegalArgumentException
            )
            assertTrue(store.isCurrent(ticket))
            assertTrue(engine.tasks.value.isEmpty())
            store.removeRule("B")
            assertTrue(
                runCatching { engine.dispatch(event) }.exceptionOrNull() is IllegalArgumentException
            )
            assertTrue(store.isCurrent(ticket))
        } finally {
            owner.cancel()
        }
    }

    @Test
    fun `a dependency disabled during execution prevents downstream publication`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val engine = EventRuntime(store, owner)
            rule(store, "A")
            rule(store, "C", setOf("A"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            engine.registerAction("A") { "a" }
            engine.registerAction("C") {
                entered.complete(Unit)
                release.await()
                "c"
            }
            val jobs = engine.dispatch(event)
            withTimeout(5000) { entered.await() }
            store.setEnabled("A", false)
            release.complete(Unit)
            withTimeout(5000) { jobs.joinAll() }
            assertEquals(
                TaskStatus.SUPERSEDED,
                engine.tasks.value.single { it.ruleId == "C" }.status,
            )
            assertTrue(store.artifacts(scope).none { it.ticket.reference.provider == "C" })
        } finally {
            owner.cancel()
        }
    }
}
