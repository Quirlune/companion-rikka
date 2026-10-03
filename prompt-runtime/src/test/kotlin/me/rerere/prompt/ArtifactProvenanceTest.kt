package me.rerere.prompt

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtifactProvenanceTest {
    private val scope = ContextScope("agent", "chat", "main")
    private val sources = listOf(SourceVersion("m", "v"))
    private val a = ReferenceId("a", "result")
    private val b = ReferenceId("b", "result")
    private val c = ReferenceId("c", "result")

    @Test
    fun `file persistence roundtrips dependency rules and exact artifact lineage`() = runBlocking {
        val directory = Files.createTempDirectory("prompt-lineage").toFile()
        try {
            val persistence = FileRuntimePersistence(directory.resolve("state.json"))
            val store = store(persistence)
            store.putRule(EventRule("join", "reply", "join", b, dependsOn = setOf("analyze")))
            val input = store.publish(store.begin(a, scope, sources)!!, "source")!!
            store.publish(store.begin(b, scope, sources)!!, "derived", listOf(input))!!
            val reopened = RuntimeStore(persistence)
            assertEquals(setOf("analyze"), reopened.rules().single().dependsOn)
            assertEquals(
                listOf(input.ticket.id),
                reopened.resolve(listOf(b), scope, sources).single().artifact!!.dependencies,
            )
            reopened.setEnabled("a", false)
            assertEquals(
                ReferenceStatus.STALE,
                reopened.resolve(listOf(b), scope, sources).single().status,
            )
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `failed retention commit keeps all history available`() = runBlocking {
        var fail = false
        val backing = MemoryRuntimePersistence()
        val persistence =
            object : RuntimePersistence {
                override suspend fun load() = backing.load()

                override suspend fun save(state: RuntimeState) {
                    check(!fail) { "disk full" }
                    backing.save(state)
                }
            }
        val store = store(persistence)
        store.publish(store.begin(a, scope, sources)!!, "one")
        store.publish(store.begin(a, scope, sources)!!, "two")
        fail = true
        assertTrue(runCatching { store.pruneArtifacts(1) }.isFailure)
        assertEquals(2, store.artifacts(scope).size)
        assertEquals(2, RuntimeStore(backing).artifacts(scope).size)
    }

    private suspend fun store(persistence: RuntimePersistence = MemoryRuntimePersistence()) =
        RuntimeStore(persistence).also { store ->
            listOf(a, b, c).forEach { store.register(ReferenceDefinition(it, it.name)) }
        }

    @Test
    fun `transitive provenance survives reload and invalidates on dependency replacement`() =
        runBlocking {
            val persistence = MemoryRuntimePersistence()
            val store = store(persistence)
            val first = store.publish(store.begin(a, scope, sources)!!, "a")!!
            val second = store.publish(store.begin(b, scope, sources)!!, "b", listOf(first))!!
            store.publish(store.begin(c, scope, sources)!!, "c", listOf(second))!!
            val reopened = RuntimeStore(persistence)
            assertEquals(
                ReferenceStatus.READY,
                reopened.resolve(listOf(c), scope, sources).single().status,
            )
            val replacement = reopened.begin(a, scope, sources)!!
            // A pending replacement does not hide previously completed reference chains.
            assertEquals(
                ReferenceStatus.READY,
                reopened.resolve(listOf(c), scope, sources).single().status,
            )
            reopened.publish(replacement, "new a")
            assertEquals(
                ReferenceStatus.STALE,
                reopened.resolve(listOf(c), scope, sources).single().status,
            )
        }

    @Test
    fun `dependency disable invalidates completed descendants and cannot revive old epochs`() =
        runBlocking {
            val store = store()
            val input = store.publish(store.begin(a, scope, sources)!!, "a")!!
            store.publish(store.begin(b, scope, sources)!!, "b", listOf(input))
            store.setEnabled("a", false)
            assertEquals(
                ReferenceStatus.STALE,
                store.resolve(listOf(b), scope, sources).single().status,
            )
            store.setEnabled("a", true)
            assertEquals(
                ReferenceStatus.STALE,
                store.resolve(listOf(b), scope, sources).single().status,
            )
        }

    @Test
    fun `foreign scope and forged input artifacts cannot be published as dependencies`() =
        runBlocking {
            val store = store()
            val input = store.publish(store.begin(a, scope, sources)!!, "a")!!
            val foreign = store.begin(b, scope.copy(conversationId = "other"), sources)!!
            assertNull(store.publish(foreign, "b", listOf(input)))
            val own = store.begin(b, scope, sources)!!
            assertNull(store.publish(own, "b", listOf(input.copy(text = "forged"))))
            assertNotNull(store.publish(own, "b", listOf(input)))
        }

    @Test
    fun `retention preserves provenance ancestors and late publication fences`() = runBlocking {
        val store = store()
        val first = store.publish(store.begin(a, scope, sources)!!, "a1")!!
        store.publish(store.begin(b, scope, sources)!!, "b", listOf(first))
        val obsolete = store.begin(a, scope, sources)!!
        store.publish(store.begin(a, scope, sources)!!, "a2")
        store.publish(store.begin(a, scope, sources)!!, "a3")
        assertEquals(1, store.pruneArtifacts(1))
        assertEquals(setOf("a1", "a3", "b"), store.artifacts(scope).map { it.text }.toSet())
        assertNull(store.publish(obsolete, "late"))
        store.publish(store.begin(b, scope, sources)!!, "independent b")
        assertEquals(2, store.pruneArtifacts(1))
        assertEquals(setOf("a3", "independent b"), store.artifacts(scope).map { it.text }.toSet())
        assertTrue(runCatching { store.pruneArtifacts(0) }.isFailure)
    }
}
