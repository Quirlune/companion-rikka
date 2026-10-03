package me.rerere.prompt

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeStoreTest {
    @Test
    fun `late child from old event chain cannot reserve over a newer chain`() = runBlocking {
        val store = store()
        val oldOrder = store.nextEventOrder()
        val newOrder = store.nextEventOrder()
        val recent = store.beginAll(listOf(reference), scope, sources, newOrder).single()
        store.publish(recent, "new chain")
        assertTrue(store.beginAll(listOf(reference), scope, sources, oldOrder).isEmpty())
        assertEquals("new chain", store.resolve(listOf(reference), scope, sources).single().text)
    }

    @Test
    fun `batch reservations reject competing writers without invalidating existing work`() =
        runBlocking {
            val store = store()
            val ticket = store.begin(reference, scope, sources)!!
            assertTrue(
                runCatching { store.beginAll(listOf(reference, reference), scope, sources) }
                    .isFailure
            )
            assertNotNull(store.publish(ticket, "still valid"))
        }

    @Test
    fun `unknown persistence schema is surfaced instead of overwriting data`() = runBlocking {
        val persistence = MemoryRuntimePersistence()
        persistence.save(RuntimeState(schemaVersion = 99))
        assertTrue(runCatching { RuntimeStore(persistence).definitions() }.isFailure)
        assertEquals(99, persistence.load()!!.schemaVersion)
    }

    private val reference = ReferenceId("analysis", "result")
    private val scope = ContextScope("agent", "chat", "main")
    private val sources = listOf(SourceVersion("message", "revision-1"))

    private suspend fun store(persistence: RuntimePersistence = MemoryRuntimePersistence()) =
        RuntimeStore(persistence).also { it.register(ReferenceDefinition(reference, "Analysis")) }

    @Test
    fun `older completion cannot overwrite a newer scheduled run`() = runBlocking {
        val store = store()
        val old = store.begin(reference, scope, sources)!!
        val recent = store.begin(reference, scope, sources)!!
        assertNotNull(store.publish(recent, "new"))
        assertNull(store.publish(old, "old"))
        assertEquals("new", store.resolve(listOf(reference), scope, sources).single().text)
        assertNull(store.publish(recent, "duplicate"))
    }

    @Test
    fun `disable and reenable reject in flight work and hide previous content`() = runBlocking {
        val store = store()
        store.publish(store.begin(reference, scope, sources)!!, "previous")
        val running = store.begin(reference, scope, sources)!!
        store.setEnabled(reference.provider, false)
        assertEquals(
            ReferenceStatus.DISABLED,
            store.resolve(listOf(reference), scope, sources).single().status,
        )
        assertNull(store.publish(running, "late"))
        store.setEnabled(reference.provider, true)
        assertNull(store.publish(running, "late after reenable"))
        assertEquals(
            ReferenceStatus.EMPTY,
            store.resolve(listOf(reference), scope, sources).single().status,
        )
        assertEquals(1, store.artifacts(scope).size)
    }

    @Test
    fun `references never leak across assistant conversation or branch`() = runBlocking {
        val store = store()
        store.publish(store.begin(reference, scope, sources)!!, "private")
        listOf(
                scope.copy(assistantId = "other"),
                scope.copy(conversationId = "other"),
                scope.copy(branchId = "other"),
            )
            .forEach {
                assertEquals(
                    ReferenceStatus.EMPTY,
                    store.resolve(listOf(reference), it, sources).single().status,
                )
            }
    }

    @Test
    fun `edits invalidate outputs but appending new messages does not`() = runBlocking {
        val store = store()
        store.publish(store.begin(reference, scope, sources)!!, "analysis")
        assertEquals(
            ReferenceStatus.READY,
            store
                .resolve(
                    listOf(reference),
                    scope,
                    sources + SourceVersion("next", "v1"),
                )
                .single()
                .status,
        )
        assertEquals(
            ReferenceStatus.STALE,
            store
                .resolve(
                    listOf(reference),
                    scope,
                    listOf(SourceVersion("message", "edited")),
                )
                .single()
                .status,
        )
        assertEquals(
            ReferenceStatus.STALE,
            store.resolve(listOf(reference), scope, emptyList()).single().status,
        )
    }

    @Test
    fun `request snapshot remains stable when provider is disabled later`() = runBlocking {
        val store = store()
        store.setPreset(
            scope.assistantId,
            PromptPreset(
                "custom",
                listOf(
                    PromptBlock("analysis", PromptSource.Reference(reference)),
                    PromptBlock("history", PromptSource.History),
                ),
            ),
        )
        store.publish(store.begin(reference, scope, sources)!!, "fixed")
        val before = store.compile(scope, sources, emptyMap())
        store.setEnabled(reference.provider, false)
        val after = store.compile(scope, sources, emptyMap())
        assertEquals("fixed", before.blocks.first().text)
        assertEquals(1, after.blocks.size)
        assertTrue(after.blocks.single().history)
    }

    @Test
    fun `configuration and artifacts survive reopening file store`() = runBlocking {
        val directory = Files.createTempDirectory("prompt-test").toFile()
        try {
            val persistence = FileRuntimePersistence(directory.resolve("state.json"))
            val first = store(persistence)
            first.publish(first.begin(reference, scope, sources)!!, "persisted")
            first.putRule(EventRule("rule", "event", "action", reference))
            val second = RuntimeStore(persistence)
            assertEquals(
                "persisted",
                second.resolve(listOf(reference), scope, sources).single().text,
            )
            assertEquals("rule", second.rules().single().id)
            assertEquals(1, second.artifacts(scope).size)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `failed persistence does not publish an in memory success`() = runBlocking {
        var fail = false
        val persistence =
            object : RuntimePersistence {
                private var state: RuntimeState? = null

                override suspend fun load() = state

                override suspend fun save(state: RuntimeState) {
                    if (fail) error("disk full")
                    this.state = state
                }
            }
        val store = store(persistence)
        val ticket = store.begin(reference, scope, sources)!!
        fail = true
        assertTrue(runCatching { store.publish(ticket, "lost") }.isFailure)
        assertEquals(
            ReferenceStatus.EMPTY,
            store.resolve(listOf(reference), scope, sources).single().status,
        )
    }

    @Test
    fun `static packages can be shared exported and detached`() = runBlocking {
        val store = store()
        val id = ReferenceId("pack", "style")
        val pack = PromptPackage("pack", 1, listOf(ReferenceDefinition(id, "Style", "original")))
        store.importPackage(pack)
        val text = store.resolve(listOf(id), scope, emptyList()).single().text!!
        val detached =
            PromptPreset(
                "detached",
                listOf(
                    PromptBlock("style", PromptSource.Literal(text)),
                    PromptBlock("history", PromptSource.History),
                ),
            )
        store.importPackage(
            pack.copy(version = 2, entries = listOf(ReferenceDefinition(id, "Style", "changed")))
        )
        assertEquals(2, store.exportPackage("pack").version)
        assertEquals(
            "changed",
            store
                .resolve(listOf(id), scope.copy(conversationId = "other"), emptyList())
                .single()
                .text,
        )
        assertEquals(
            "original",
            PromptCompiler.compile(detached, emptyMap(), emptyList()).blocks.first().text,
        )
    }
}
