package me.rerere.prompt

import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class RuntimeStoreConfigurationTest {
    private val output = ReferenceId("analysis", "result")
    private val scope = ContextScope("assistant", "conversation", "main")
    private val sources = listOf(SourceVersion("message", "r1"))

    @Test
    fun `configuration batches validate only final candidate and preserve no-op revision`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val rule = EventRule("analyze", "reply", "action", output)

            val mutation =
                store.applyConfiguration(
                    listOf(
                        ConfigurationCommand.PutRule(rule),
                        ConfigurationCommand.RegisterReference(
                            ReferenceDefinition(output, "Result")
                        ),
                    )
                )
            assertTrue(mutation.changed)
            val revision = mutation.snapshot.configurationRevision
            assertEquals(listOf("analyze"), mutation.snapshot.rules.map { it.id })

            assertFalse(
                store.applyConfiguration(listOf(ConfigurationCommand.PutRule(rule))).changed
            )
            assertEquals(revision, store.configuration().configurationRevision)
            val staleNoop = runCatching {
                store.applyConfiguration(emptyList(), expectedRevision = revision - 1)
            }
                .exceptionOrNull()
            assertEquals(
                ProblemCode.CONFIGURATION_CONFLICT,
                (staleNoop as RuntimeOperationException).problem.code,
            )
        }

    @Test
    fun `updating first of multiple configured rules does not reorder configuration`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val secondOutput = ReferenceId("analysis", "secondary")
            val first = EventRule("first", "reply", "action", output)
            val second = EventRule("second", "reply", "action", secondOutput)
            store.applyConfiguration(
                listOf(
                    ConfigurationCommand.RegisterReference(ReferenceDefinition(output, "Result")),
                    ConfigurationCommand.RegisterReference(
                        ReferenceDefinition(secondOutput, "Secondary")
                    ),
                    ConfigurationCommand.PutRule(first),
                    ConfigurationCommand.PutRule(second),
                )
            )
            val before = store.configuration()

            val mutation = store.applyConfiguration(listOf(ConfigurationCommand.PutRule(first)))

            assertFalse(mutation.changed)
            assertEquals(before.rules.map { it.id }, mutation.snapshot.rules.map { it.id })
            assertEquals(before.configurationRevision, mutation.snapshot.configurationRevision)
        }

    @Test
    fun `failed save leaves snapshot and both revisions unchanged`() = runBlocking {
        var failSaves = false
        val persistence =
            object : RuntimePersistence {
                var saved: RuntimeState? = null

                override suspend fun load() = saved

                override suspend fun save(state: RuntimeState) {
                    if (failSaves) error("disk unavailable")
                    saved = state
                }
            }
        val store = RuntimeStore(persistence)
        store.register(ReferenceDefinition(output, "Result"))
        val before = store.configuration()
        val stateRevision = store.stateRevisions.value
        val persistedBefore = persistence.saved
        failSaves = true

        val failure = runCatching {
            store.setPreset("assistant", PromptPreset.default())
        }
            .exceptionOrNull()
        val operationFailure = failure as RuntimeOperationException
        assertEquals(ProblemCode.STORAGE_FAILURE, operationFailure.problem.code)
        assertEquals("disk unavailable", operationFailure.cause?.message)
        assertEquals(persistedBefore, persistence.saved)
        assertEquals(before, store.configuration())
        assertEquals(stateRevision, store.stateRevisions.value)
    }

    @Test
    fun `configuration observation emits initial and successful state but not failed commits`() =
        runBlocking {
            var failSaves = false
            val persistence =
                object : RuntimePersistence {
                    private var saved: RuntimeState? = null

                    override suspend fun load() = saved

                    override suspend fun save(state: RuntimeState) {
                        if (failSaves) error("disk unavailable")
                        saved = state
                    }
                }
            val store = RuntimeStore(persistence)
            val emissions = Channel<ConfigurationSnapshot>(Channel.UNLIMITED)
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    store.observeConfiguration().collect { emissions.send(it) }
                }
            try {
                assertEquals(0L, emissions.receive().configurationRevision)
                store.register(ReferenceDefinition(output, "Result"))
                assertEquals(1L, emissions.receive().configurationRevision)

                failSaves = true
                val failed = runCatching {
                    store.setPreset("assistant", PromptPreset.default())
                }
                    .isFailure
                assertTrue(failed)
                assertEquals(1L, store.configuration().configurationRevision)
                assertNull(withTimeoutOrNull(50) { emissions.receive() })
            } finally {
                observer.cancelAndJoin()
                emissions.cancel()
            }
        }

    @Test
    fun `artifact observation starts empty filters scopes and emits committed artifacts`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            store.register(ReferenceDefinition(output, "Result"))
            val otherScope = scope.copy(conversationId = "other")
            val emissions = Channel<List<Artifact>>(Channel.UNLIMITED)
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    store.observeArtifacts(scope).collect { emissions.send(it) }
                }
            try {
                assertTrue(emissions.receive().isEmpty())

                store.publish(store.begin(output, otherScope, sources)!!, "other conversation")
                assertNull(withTimeoutOrNull(50) { emissions.receive() })
                assertEquals(1, store.artifacts(otherScope).size)

                store.publish(store.begin(output, scope, sources)!!, "current conversation")
                val updated = emissions.receive()
                assertEquals(1, updated.size)
                assertEquals("current conversation", updated.single().text)
                assertEquals(1, store.artifacts(scope).size)
            } finally {
                observer.cancelAndJoin()
                emissions.cancel()
            }
        }

    @Test
    fun `old schema defaults and invalid persisted revisions are handled on load`() = runBlocking {
        val oldState =
            kotlinx.serialization.json.Json.decodeFromString<RuntimeState>(
                """{"schemaVersion":1,"sequence":0,"providers":[],"definitions":[],"tickets":[],"artifacts":[],"presets":[],"rules":[],"packages":[]}"""
            )
        assertEquals(0L, oldState.configurationRevision)
        assertEquals(0L, oldState.stateRevision)
        val persistence = MemoryRuntimePersistence()
        persistence.save(oldState)
        assertEquals(0L, RuntimeStore(persistence).configuration().configurationRevision)

        persistence.save(RuntimeState(configurationRevision = 1, stateRevision = 0))
        assertTrue(runCatching { RuntimeStore(persistence).configuration() }.isFailure)
    }

    @Test
    fun `file store reload preserves configuration and state revisions`() = runBlocking {
        val directory = Files.createTempDirectory("prompt-revision-test").toFile()
        try {
            val persistence = FileRuntimePersistence(directory.resolve("state.json"))
            val first = RuntimeStore(persistence)
            first.register(ReferenceDefinition(output, "Result"))
            first.publish(first.begin(output, scope, sources)!!, "persisted")
            val before = first.configuration()
            val stateRevision = first.stateRevisions.value

            val reopened = RuntimeStore(persistence)
            assertEquals(
                before.configurationRevision,
                reopened.configuration().configurationRevision,
            )
            assertEquals(stateRevision, reopened.stateRevisions.value)
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test
    fun `caller-owned input collections and resolved artifacts are detached`() = runBlocking {
        val store = RuntimeStore(MemoryRuntimePersistence())
        store.register(ReferenceDefinition(output, "Result"))
        val mutableArguments = linkedMapOf("prompt" to "initial", "model" to "m1")
        val mutableDependencies = linkedSetOf("first", "second")
        val rule =
            EventRule(
                id = "analyze",
                eventType = "reply",
                actionId = "action",
                output = output,
                arguments = mutableArguments,
                dependsOn = mutableDependencies,
            )
        store.putRule(rule)
        mutableArguments["prompt"] = "mutated"
        mutableDependencies.clear()
        assertEquals("initial", store.rules().single().arguments["prompt"])
        assertEquals(setOf("first", "second"), store.rules().single().dependsOn)

        store.publish(store.begin(output, scope, sources)!!, "result")
        val returnedSources =
            store.resolve(listOf(output), scope, sources).single().artifact!!.ticket.sources
                as MutableList<SourceVersion>
        returnedSources.clear()
        assertEquals(
            sources,
            store.resolve(listOf(output), scope, sources).single().artifact!!.ticket.sources,
        )
    }

    @Test
    fun `loaded and saved persistence collections cannot alias the live store`() = runBlocking {
        val providers = mutableListOf(ProviderState(output.provider))
        val definitions = mutableListOf(ReferenceDefinition(output, "Result"))
        val backing = RuntimeState(providers = providers, definitions = definitions)
        val persistence =
            object : RuntimePersistence {
                var state: RuntimeState? = backing

                override suspend fun load() = state

                override suspend fun save(state: RuntimeState) {
                    this.state = state
                }
            }
        val store = RuntimeStore(persistence)
        assertEquals(listOf(output), store.definitions().map { it.id })

        providers.clear()
        definitions.clear()
        assertEquals(listOf(output), store.definitions().map { it.id })

        val next = ReferenceId("analysis", "another")
        store.register(ReferenceDefinition(next, "Another"))
        (persistence.state!!.definitions as MutableList).clear()
        assertEquals(setOf(output, next), store.definitions().map { it.id }.toSet())
    }

    @Test
    fun `historical definitions cannot be removed and package removal retains epoch fence`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            store.register(ReferenceDefinition(output, "Result"))
            store.begin(output, scope, sources)
            val removal = runCatching { store.removeReference(output) }.exceptionOrNull()
            assertEquals(
                ProblemCode.HISTORICAL_REFERENCE,
                (removal as RuntimeOperationException).problem.code,
            )

            val packageId = "styles"
            val packageEntry = ReferenceDefinition(ReferenceId(packageId, "tone"), "Tone", "calm")
            store.importPackage(PromptPackage(packageId, 1, listOf(packageEntry)))
            val epoch = store.configuration().providers.single { it.id == packageId }.epoch
            store.removePackage(packageId)
            val afterRemoval = store.configuration().providers.single { it.id == packageId }.epoch
            assertEquals(epoch + 1, afterRemoval)
            store.importPackage(PromptPackage(packageId, 1, listOf(packageEntry)))
            assertEquals(
                afterRemoval,
                store.configuration().providers.single { it.id == packageId }.epoch,
            )
        }

    @Test
    fun `dynamic rule output removal requires removing the rule in the same transaction`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            store.register(ReferenceDefinition(output, "Result"))
            store.putRule(EventRule("analyze", "reply", "action", output))

            val failed = runCatching { store.removeReference(output) }.exceptionOrNull()
            assertEquals(
                ProblemCode.INVALID_CONFIGURATION,
                (failed as RuntimeOperationException).problem.code,
            )
            assertEquals(listOf("analyze"), store.rules().map { it.id })
            assertEquals(listOf(output), store.definitions().map { it.id })

            val mutation =
                store.applyConfiguration(
                    listOf(
                        ConfigurationCommand.RemoveRule("analyze"),
                        ConfigurationCommand.RemoveReference(output),
                    )
                )
            assertTrue(mutation.changed)
            assertTrue(mutation.snapshot.rules.isEmpty())
            assertTrue(mutation.snapshot.definitions.isEmpty())
        }

    @Test
    fun `prompt preview reports unresolved inputs and unknown builtins without committing`() =
        runBlocking {
            val store = RuntimeStore(MemoryRuntimePersistence())
            val missing = ReferenceId("optional", "context")
            store.setPreset(
                scope.assistantId,
                PromptPreset(
                    "inspect",
                    listOf(
                        PromptBlock("context", PromptSource.Reference(missing)),
                        PromptBlock("system", PromptSource.Builtin("not.provided")),
                        PromptBlock("history", PromptSource.History),
                    ),
                ),
            )
            val before = store.configuration()
            val revision = store.stateRevisions.value

            val preview = store.previewPrompt(scope, sources, emptyMap())

            assertNull(preview.snapshot)
            assertEquals(
                setOf(ProblemCode.REFERENCE_UNAVAILABLE, ProblemCode.UNKNOWN_BUILTIN),
                preview.issues.map { it.code }.toSet(),
            )
            assertEquals(before, store.configuration())
            assertEquals(revision, store.stateRevisions.value)
        }

    @Test
    fun `valid prompt preview matches normal compilation`() = runBlocking {
        val store = RuntimeStore(MemoryRuntimePersistence())
        store.register(ReferenceDefinition(output, "Result"))
        store.setPreset(
            scope.assistantId,
            PromptPreset(
                "inspect",
                listOf(
                    PromptBlock("builtin", PromptSource.Builtin("assistant.system")),
                    PromptBlock("result", PromptSource.Reference(output)),
                    PromptBlock("history", PromptSource.History),
                ),
            ),
        )
        store.publish(store.begin(output, scope, sources)!!, "compiled result")
        val builtins = mapOf("assistant.system" to "system text")

        val preview = store.previewPrompt(scope, sources, builtins)

        assertTrue(preview.issues.isEmpty())
        assertEquals(store.compile(scope, sources, builtins), preview.snapshot)
    }
}
