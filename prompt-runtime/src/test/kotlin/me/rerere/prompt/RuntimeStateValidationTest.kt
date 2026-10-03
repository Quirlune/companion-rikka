package me.rerere.prompt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeStateValidationTest {
    private val context = ContextScope("agent", "chat", "main")
    private val sources = listOf(SourceVersion("message", "v1"))
    private val a = ReferenceId("test", "a")
    private val b = ReferenceId("test", "b")

    private suspend fun validState(): RuntimeState {
        val persistence = MemoryRuntimePersistence()
        val store = RuntimeStore(persistence)
        store.register(ReferenceDefinition(a, "A"))
        store.register(ReferenceDefinition(b, "B"))
        val first = store.publish(store.begin(a, context, sources)!!, "a")!!
        store.publish(store.begin(b, context, sources)!!, "b", listOf(first))
        return persistence.load()!!
    }

    private suspend fun rejectsWithoutWrite(state: RuntimeState) {
        var writes = 0
        val persistence =
            object : RuntimePersistence {
                override suspend fun load() = state

                override suspend fun save(state: RuntimeState) {
                    writes++
                }
            }
        val store = RuntimeStore(persistence)
        assertTrue(
            runCatching { store.definitions() }.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            runCatching { store.register(ReferenceDefinition(a, "Attempted write")) }.isFailure
        )
        assertEquals(0, writes)
        assertEquals(state, persistence.load())
    }

    @Test
    fun `duplicate identifiers and missing registration are rejected without writes`() =
        runBlocking {
            val state = validState()
            rejectsWithoutWrite(state.copy(providers = state.providers + state.providers.first()))
            rejectsWithoutWrite(
                state.copy(definitions = state.definitions + state.definitions.first())
            )
            rejectsWithoutWrite(state.copy(artifacts = state.artifacts + state.artifacts.first()))
            rejectsWithoutWrite(state.copy(tickets = state.tickets + state.tickets.first()))
            rejectsWithoutWrite(state.copy(providers = emptyList()))
            rejectsWithoutWrite(state.copy(definitions = state.definitions.drop(1)))
        }

    @Test
    fun `impossible order and publication fences are rejected`() = runBlocking {
        val state = validState()
        rejectsWithoutWrite(state.copy(sequence = 0))
        rejectsWithoutWrite(state.copy(tickets = state.tickets.drop(1)))
        rejectsWithoutWrite(
            state.copy(tickets = state.tickets.map { it.copy(eventOrder = state.sequence + 1) })
        )
        rejectsWithoutWrite(state.copy(tickets = state.tickets.map { it.copy(providerEpoch = 99) }))
        rejectsWithoutWrite(
            state.copy(
                artifacts = state.artifacts.map { it.copy(ticket = it.ticket.copy(sequence = 1)) }
            )
        )
    }

    @Test
    fun `broken cyclic and foreign provenance are rejected`() = runBlocking {
        val state = validState()
        rejectsWithoutWrite(
            state.copy(
                artifacts = state.artifacts.map { it.copy(dependencies = listOf("missing")) }
            )
        )
        val first = state.artifacts.first()
        val last = state.artifacts.last()
        rejectsWithoutWrite(
            state.copy(artifacts = listOf(first.copy(dependencies = listOf(last.ticket.id)), last))
        )
        val foreignTicket = first.ticket.copy(scope = context.copy(conversationId = "other"))
        rejectsWithoutWrite(
            state.copy(
                tickets =
                    state.tickets.map { if (it.id == foreignTicket.id) foreignTicket else it },
                artifacts = listOf(first.copy(ticket = foreignTicket), last),
            )
        )
    }

    @Test
    fun `package definitions must agree and rules cannot write static content`() = runBlocking {
        val state = validState()
        val ref = ReferenceId("pack", "entry")
        val entry = ReferenceDefinition(ref, "Entry", "text")
        val pack = PromptPackage("pack", 1, listOf(entry))
        val packed =
            state.copy(
                providers = state.providers + ProviderState("pack"),
                definitions = state.definitions + entry,
                packages = listOf(pack),
            )
        RuntimeStateValidator.validate(packed)
        rejectsWithoutWrite(packed.copy(packages = listOf(pack.copy(entries = emptyList()))))
        rejectsWithoutWrite(packed.copy(rules = listOf(EventRule("bad", "reply", "action", ref))))
    }

    @Test
    fun `ambiguous source identities are rejected at event and store boundaries`() = runBlocking {
        val duplicate = sources + SourceVersion("message", "v2")
        assertTrue(runCatching { RuntimeEvent("reply", context, duplicate) }.isFailure)
        val store = RuntimeStore(MemoryRuntimePersistence())
        store.register(ReferenceDefinition(a, "A"))
        assertTrue(runCatching { store.begin(a, context, duplicate) }.isFailure)
        assertTrue(runCatching { store.resolve(listOf(a), context, duplicate) }.isFailure)
        assertTrue(runCatching { store.compile(context, duplicate, emptyMap()) }.isFailure)
        assertTrue(runCatching { SourceVersion("message", "") }.isFailure)
        assertTrue(store.artifacts(context).isEmpty())
    }
}
