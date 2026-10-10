package dev.gray.companion.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class MemoryViewStorageTest {
    private fun resources(): Library {
        val flow = Workflow(id = "small", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS)
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(flow))
        val extension = ConfigurationExtension(id = "memory", name = "Memory",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
            memory = MemoryPluginRules(groupId = group.id, workflowId = flow.id))
        val policy = GlobalMemoryConfig(enabled = true, extensionId = extension.id)
        val captured = Library(groups = listOf(group), extensions = listOf(extension), memoryConfig = policy)
        return Library(memoryConfig = policy, memoryExecutionView = CapturedMemoryResources(captured, setOf(flow.id)))
    }

    private fun world(resources: Library, version: Int = 18) = CompletedWorld(resources,
        CapturedSessionChoices("character", "main"), "input", version)

    private fun recovery(resources: Library, version: Int = 18) = RunRecovery(
        id = "run", sessionId = "session", turnId = "turn", input = "input",
        checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = Session(id = "initial"),
        frozenResources = resources, executionVersion = version)

    private fun rejectWire(library: Library) {
        assertTrue(runCatching { LibraryFormat.encode(library) }.exceptionOrNull() is IllegalArgumentException)
        val unchecked = LibraryFormat.json.encodeToString(Library.serializer(), library)
        assertTrue(runCatching { LibraryFormat.decode(unchecked) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun roundTripPreservesCapturedViewWithoutLiveNormalization() {
        val resources = resources()
        val captured = world(resources)
        val session = Session(id = "session", executableWorld = captured, pendingRun = recovery(resources),
            turnRecords = listOf(TurnRecord("turn", TurnCheckpoint(RuntimeSnapshot(completedWorld = captured)),
                variants = listOf(TurnVariant(after = RuntimeSnapshot(completedWorld = captured))))))
        val stored = LibraryFormat.decode(LibraryFormat.encode(Library(sessions = listOf(session)))).sessions.single()
        val expected = resources.memoryExecutionView
        assertEquals(expected, stored.executableWorld!!.library.memoryExecutionView)
        assertEquals(expected, stored.pendingRun!!.frozenResources.memoryExecutionView)
        assertEquals(expected, stored.turnRecords.single().checkpoint.runtime.completedWorld!!.library.memoryExecutionView)
        assertEquals(expected, stored.turnRecords.single().variants.single().after.completedWorld!!.library.memoryExecutionView)
    }

    @Test fun providerSecretsCannotHideInViewOrArchivedWorlds() {
        val base = resources()
        val view = base.memoryExecutionView!!
        val secret = ApiConnection(id = "secret", nativeProvider = JsonObject(mapOf(
            "_formatVersion" to JsonPrimitive(1), "apiKey" to JsonPrimitive("must-not-persist"))))
        val poisoned = base.copy(memoryExecutionView = view.copy(library = view.library.copy(connections = listOf(secret))))
        val captured = world(poisoned)
        val sessions = listOf(
            Session(executableWorld = captured),
            Session(pendingRun = recovery(poisoned)),
            Session(turnRecords = listOf(TurnRecord("turn", TurnCheckpoint(RuntimeSnapshot(completedWorld = captured))))),
            Session(turnRecords = listOf(TurnRecord("turn", TurnCheckpoint(RuntimeSnapshot()),
                variants = listOf(TurnVariant(after = RuntimeSnapshot(completedWorld = captured)))))))
        sessions.forEach { session ->
            val library = Library(sessions = listOf(session))
            assertTrue(NativeProviderConfiguration.validate(library).any { it.path.endsWith("nativeProvider.apiKey") })
            rejectWire(library)
        }
    }

    @Test fun nestedViewsBacklinksAndOldExecutionAreRejected() {
        val base = resources()
        val view = base.memoryExecutionView!!
        val invalid = listOf(
            base.copy(memoryExecutionView = view.copy(library = view.library.copy(memoryExecutionView = view))),
            base.copy(memoryExecutionView = view.copy(library = view.library.copy(sessions = listOf(Session())))))
        invalid.forEach { rejectWire(Library(sessions = listOf(Session(executableWorld = world(it))))) }
        rejectWire(Library(sessions = listOf(Session(executableWorld = world(base, 17)))))
        rejectWire(Library(sessions = listOf(Session(pendingRun = recovery(base, 17)))))
        val backlink = world(base).copy(environments = listOf(PureReplayEnvironment("environment",
            Session(executableWorld = world(Library())))))
        rejectWire(Library(sessions = listOf(Session(executableWorld = backlink))))
        // The absent-view legacy default remains admissible.
        assertTrue(NativeProviderConfiguration.validate(Library(sessions = listOf(
            Session(executableWorld = world(Library(), 17), pendingRun = recovery(Library(), 17))))).isEmpty())
    }
}
