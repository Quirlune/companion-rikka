package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class MemoryPluginCommandsTest {
    private fun admitted(result: ExtensionEditResult): Library {
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        return requireNotNull(result.library)
    }

    @Test fun explicitMigrationKeepsFamiliesAndParticipationWithoutChangingCapturedWorlds() {
        val base = LibraryDefaults.create()
        val workflow = defaultHistoryProcessorWorkflow().copy(id = "old-leaf", enabled = true,
            nodes = defaultHistoryProcessorWorkflow().nodes.map { it.copy(id = "old-model", outputKey = "old_summary") })
        val rules = HistoryProcessor(workflow = workflow, minimumNewFloors = 7, keepRecentFloors = 3,
            outputKey = "old_summary", sourceKind = HistorySourceKind.MODEL_RAW,
            thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS, protectFirstFloors = 2, maximumBatchFloors = 9)
        val processor = rules.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(rules, 2))
        val main = base.mainCalls.single().copy(history = base.mainCalls.single().history.copy(processor = processor))
        val message = ChatMessage(id = "source", role = Role.USER, text = "immutable source")
        val segment = HistoryMemory.segment("existing", "expensive accepted summary", listOf(message), workflow.id)
        val first = base.sessions.single().copy(messages = listOf(message),
            historyMemory = HistoryMemoryState(segments = mapOf(segment.id to segment)))
        val preliminary = base.copy(mainCalls = listOf(main), sessions = listOf(first))
        val world = CompletedWorlds.capture(preliminary, first, "old input", 12)
        val frozen = first.copy(id = "frozen", executableWorld = world)
        val sibling = first.copy(id = "sibling")
        val folder = ChatFolder(id = "folder", name = "Saved")
        val saved = SavedChat(id = "saved", folderId = folder.id, snapshot = frozen)
        val before = preliminary.copy(sessions = listOf(first, sibling, frozen),
            chatFolders = listOf(folder), savedChats = listOf(saved))
        val after = admitted(MemoryPluginCommands.create(before, expectedMain = main))
        val definition = after.extensions.single { value -> before.extensions.none { it.id == value.id } }
        assertEquals(processor, MemoryPlugins.processor(after, definition))
        assertFalse(after.mainCalls.single().history.processor.workflow.enabled)
        assertNotEquals(workflow.id, after.mainCalls.single().history.processor.workflow.id)
        assertTrue(after.sessions.first { it.id == first.id }.enabledExtensionIds.contains(definition.id))
        assertTrue(after.sessions.first { it.id == sibling.id }.enabledExtensionIds.contains(definition.id))
        assertEquals(frozen, after.sessions.first { it.id == frozen.id })
        assertEquals(listOf(saved), after.savedChats)
        assertEquals(first.historyMemory, after.sessions.first { it.id == first.id }.historyMemory)
        assertNull(MemoryPluginCommands.create(before, expectedMain = main.copy(name = "stale")).library)
    }

    @Test fun closingPendingNativeMemoryRetainsAcceptedRuntimeAndRestoreFlags() {
        val base = LibraryDefaults.create()
        val definition = base.extensions.single { it.memory != null }
        val message = ChatMessage(id = "source", role = Role.USER, text = "source")
        val segment = HistoryMemory.segment("saved", "accepted costly summary", listOf(message), definition.memory!!.workflowId)
        val active = base.sessions.single().copy(messages = listOf(message), enabledExtensionIds = listOf(definition.id),
            state = mapOf("shared" to "retained"), results = mapOf("accepted" to "retained"),
            historyMemory = HistoryMemoryState(segments = mapOf(segment.id to segment)))
        val variant = TurnVariant(id = "variant", after = active.runtimeSnapshot())
        val record = TurnRecord(message.id, TurnCheckpoint(RuntimeSnapshot()), listOf(variant), variant.id)
        val recorded = active.copy(turnRecords = listOf(record))
        val pending = RunRecovery("run", recorded.id, message.id, "source", TurnCheckpoint(recorded.runtimeSnapshot()),
            recorded, CapturedResourceClosure.capture(base, recorded), unavailableKeys = setOf("failed"), executionVersion = 13,
            state = recorded.state, results = recorded.results, historyMemory = recorded.historyMemory)
        val session = recorded.copy(pendingRun = pending)
        val before = base.copy(sessions = listOf(session))
        assertNull(MemoryPluginCommands.setParticipation(before, definition, session.id, false, true).library)
        val after = admitted(MemoryPluginCommands.setParticipation(before, definition, session.id, false, true, endPending = true))
        val closed = after.sessions.single()
        assertNull(closed.pendingRun)
        assertFalse(definition.id in closed.enabledExtensionIds)
        assertEquals(setOf("failed"), closed.unavailableResultKeys)
        assertEquals(session.state, closed.state)
        assertEquals(session.results, closed.results)
        assertEquals(session.historyMemory, closed.historyMemory)
        assertEquals(session.turnRecords, closed.turnRecords)
        assertEquals(session.messages, closed.messages)
        assertEquals(session.promptInjections, closed.promptInjections)
        val regenerating = before.copy(sessions = listOf(session.copy(pendingRun = pending.copy(regenerating = true))))
        val regenerated = admitted(MemoryPluginCommands.setParticipation(regenerating, definition, session.id, false, true, true))
        assertEquals(session.unavailableResultKeys, regenerated.sessions.single().unavailableResultKeys)
    }

    @Test fun inactiveManuallyLoadedMemoryKeepsCapturedDeclarationAndDeletionProtection() {
        val base = LibraryDefaults.create()
        val definition = base.extensions.single { it.memory != null }
        val memory = definition.memory!!
        val session = base.sessions.single().copy(groupIds = listOf(memory.groupId))
        val library = base.copy(sessions = listOf(session))
        assertNull(MemoryPlugins.binding(library, session, library.mainCalls.single(), 13))
        assertNotNull(MemoryPlugins.binding(library, session, library.mainCalls.single(), 12))
        assertFalse(MemoryPlugins.workflowActive(library, session, memory.workflowId, 13))
        assertFalse(MemoryPlugins.workflowActive(library, session.copy(enabledExtensionIds = listOf(definition.id)), memory.workflowId, 12))
        val world = CompletedWorlds.capture(library, session, "input", 13)
        assertEquals(definition, world.library.extensions.single { it.id == definition.id })
        assertTrue(world.choices.enabledExtensionIds.isEmpty())
        assertFalse(MemoryPlugins.workflowActive(world.library, session, memory.workflowId, 13))
        assertTrue(MemoryPlugins.validate(world.library).isEmpty())
        val active = session.copy(enabledExtensionIds = listOf(definition.id))
        val activeWorld = CompletedWorlds.capture(library, active, "captured active input", 13)
        val changed = library.copy(extensions = library.extensions.map {
            if (it.id == definition.id) it.copy(memory = memory.copy(minimumNewFloors = 1)) else it
        })
        val restored = CompletedWorlds.restore(session, active.runtimeSnapshot().copy(completedWorld = activeWorld))
        val executable = CompletedWorlds.executionLibrary(changed, restored)
        val restoredBinding = MemoryPlugins.binding(executable, restored, executable.mainCalls.single(), 13)!!
        assertEquals(listOf(definition.id), restored.enabledExtensionIds)
        assertEquals(definition.id, restoredBinding.extensionId)
        assertEquals(memory.minimumNewFloors, restoredBinding.processor.minimumNewFloors)
        assertEquals(1, changed.extensions.single { it.id == definition.id }.memory!!.minimumNewFloors)
        val deletion = ResourceEditCommands.previewDelete(library,
            ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, memory.groupId)), true)
        assertNull(deletion.configuration)
        assertTrue(deletion.issues.any { it.path == "extension.${definition.id}.memory" })
        assertNull(ExtensionCommands.deleteDefinition(library, definition.id).library)
    }

    @Test fun singleNativeParticipationAndExpectedGroupAreGuardedWithoutLoadTimeMigration() {
        val old = LibraryFormat.decode("""{"schemaVersion":19,"extensions":[{"id":"ordinary","name":"Ordinary"}]}""")
        assertNull(old.extensions.single().memory)
        assertTrue(old.groups.isEmpty())
        val base = LibraryDefaults.create()
        val first = base.extensions.single { it.memory != null }
        val created = admitted(MemoryPluginCommands.create(base))
        val second = created.extensions.single { it.id != first.id && it.memory != null }
        val session = created.sessions.single()
        val on = admitted(MemoryPluginCommands.setParticipation(created, first, session.id, true, false))
        assertNull(MemoryPluginCommands.setParticipation(on, second, session.id, true, false).library)
        val group = on.groups.single { it.id == first.memory!!.groupId }
        assertNull(MemoryPluginCommands.update(on, first, group.copy(name = "stale"), MemoryPlugins.processor(on, first)!!).library)
        assertNull(MemoryPluginCommands.create(on, expectedMain = on.mainCalls.single()).library)
    }
}
