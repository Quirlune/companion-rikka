package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryProvenanceScenarioTest {
    private fun fixture(copy: Boolean, selector: String = "latest"): Library {
        val model = WorkflowNode(id = "summarize", outputKey = "model_summary", includeHistory = false,
            inputTemplate = "{{history.pending}}", blocks = listOf(PromptBlock(template = "Summarize")))
        val finish = WorkflowNode(id = "finish", actionId = if (copy) "macro.process" else "text.extract",
            outputKey = "small_summary", dependsOn = listOf(model.id), includeHistory = false,
            inputTemplate = if (copy) "" else "{{result.model_summary@$selector}}",
            arguments = if (copy) emptyMap() else mapOf("pattern" to "(?s).+"),
            macroProcess = MacroProcessConfig(key = "state.memory_copy", mode = MacroProcessMode.COPY,
                source = "result.model_summary@$selector"))
        val flow = Workflow(id = "small", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS,
            nodes = listOf(model, finish))
        val processor = HistoryProcessor(flow, outputKey = "small_summary", minimumNewFloors = 3,
            keepRecentFloors = 1, maximumBatchFloors = 2)
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(flow))
        val extension = ConfigurationExtension(id = "memory", name = "Memory",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
            memory = MemoryPlugins.rules(group.id, processor))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", title = "Chat",
            nodes = listOf(WorkflowNode(id = "reply", actionId = "text.render", visible = true,
                includeHistory = false, inputTemplate = "Reply")))))
        val messages = (1..5).map { ChatMessage(id = "f$it", role = if (it % 2 == 1) Role.USER else Role.ASSISTANT,
            text = "BODY-$it") }
        val session = Session(id = "session", characterId = "card", mainCallId = main.id, messages = messages,
            enabledExtensionIds = listOf(extension.id))
        val base = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), groups = listOf(group), extensions = listOf(extension), sessions = listOf(session),
            selectedSessionId = session.id, profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")
        val migrated = GlobalMemory.ensureGlobal(base)
        assertTrue(migrated.issues.toString(), migrated.library != null)
        return requireNotNull(migrated.library)
    }

    private val gateway = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) = "summary"
    }

    @Test fun latestVersionAndCopyKeepActualDependencySources() = runBlocking {
        for (copy in listOf(false, true)) {
            val library = fixture(copy)
            val result = WorkflowEngine().run(library, library.sessions.single(), "BODY-5", gateway)
            assertEquals("copy=$copy ${result.traces}", RunStatus.SUCCESS, result.status)
            assertEquals(listOf("f1", "f2"), result.historyMemory.segments.values.single().sources.map { it.messageId })
        }
    }

    @Test fun previousVersionWithIdenticalTextCannotMarkCurrentFloors() = runBlocking {
        for (copy in listOf(false, true)) {
            val library = fixture(copy, "update-1")
            val origin = ResultOrigin("memory-group", "small", "summarize")
            val session = library.sessions.single().copy(results = mapOf("model_summary" to "summary"),
                resultOrigins = mapOf("model_summary" to origin), macroHistory = mapOf("result.model_summary" to listOf(
                    MacroVersion(1, "summary", origin, turn = 1, turnId = "f1"))))
            val result = WorkflowEngine().run(library, session, "BODY-5", gateway)
            assertEquals("copy=$copy ${result.traces}", RunStatus.PAUSED, result.status)
            assertTrue(result.historyMemory.segments.isEmpty())
        }
    }

    @Test fun copiedCompressedViewIncludesRetiredGlobalFamily() {
        var library = fixture(false)
        library = library.copy(memoryConfig = library.memoryConfig!!.copy(
            legacyLeafWorkflowIds = library.memoryConfig!!.legacyLeafWorkflowIds + "retired-leaf"))
        val before = library.sessions.single()
        val segment = HistoryMemory.segment("Old", "retired summary", before.messages.take(2), "retired-leaf")
        val session = before.copy(historyMemory = HistoryMemory.apply(before.historyMemory, HistoryMemoryPatch(segments = listOf(segment))))
        val main = library.mainCalls.single()
        val patch = HistoryViews.copy(session, HistorySelection(compressed = true), "copied", "Copied",
            HistorySegmentMode.COMPRESS, emptyList(), main, library = library)
        val restored = session.copy(historyMemory = HistoryMemory.apply(session.historyMemory, patch))
        val projected = HistoryViews.project(library, restored, main, HistorySelection(viewId = "copied", compressed = true))
        assertTrue(projected.issues.toString(), projected.issues.isEmpty())
        assertTrue(projected.messages.any { "retired summary" in it.text })
        val oldPatch = HistoryViews.copy(session, HistorySelection(compressed = true), "legacy", "Legacy",
            HistorySegmentMode.COMPRESS, emptyList(), main, executionVersion = 16, library = library)
        assertFalse(oldPatch.views.single().segmentModes.containsKey(segment.id))
    }
}
