package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryInputProvenanceTest {
    private fun fixture(steps: List<TextProcessStep>): Library {
        val small = WorkflowNode(id = "small-model", outputKey = "small_summary", includeHistory = false,
            inputTemplate = "seed", inputProcessing = steps,
            blocks = listOf(PromptBlock(template = "Summarize in <summary>...</summary>.")))
        val leaf = Workflow(id = "leaf", trigger = RuntimeEvents.HISTORY_PROCESS, nodes = listOf(small))
        val initial = HistoryProcessor(workflow = leaf, outputKey = small.outputKey,
            minimumNewFloors = 3, maximumBatchFloors = 2, keepRecentFloors = 1,
            sourceKind = HistorySourceKind.DISPLAY, thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS)
        val processor = initial.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(initial))
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(leaf, processor.aggregationWorkflow!!))
        val extension = ConfigurationExtension(id = "memory", name = "Memory", resources = listOf(
            ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)), memory = MemoryPlugins.rules(group.id, processor))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", nodes = listOf(
            WorkflowNode(id = "reply", actionId = "text.render", inputTemplate = "reply", includeHistory = false, visible = true)))))
        val session = Session(id = "session", characterId = "character", mainCallId = main.id,
            enabledExtensionIds = listOf(extension.id), messages = (1..5).map { ChatMessage(id = "f$it",
                role = if (it % 2 == 0) Role.ASSISTANT else Role.USER, text = "BODY-$it", createdAt = it.toLong()) })
        return Library(characters = listOf(CharacterCard(id = "character", name = "Character", namespace = "hero")),
            mainCalls = listOf(main), groups = listOf(group), extensions = listOf(extension), sessions = listOf(session),
            selectedSessionId = session.id, memoryConfig = GlobalMemoryConfig(enabled = true, extensionId = extension.id,
                xmlValidationVersion = 1), selectedProfileId = "model",
            profiles = listOf(ModelProfile(id = "model", model = "offline", apiConnectionId = "connection")),
            connections = listOf(ApiConnection(id = "connection")))
    }

    private fun gateway(sent: MutableList<List<ChatMessage>>, fail: Boolean = false) = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
            sent += messages
            if (fail) throw IllegalStateException("synthetic request failure")
            return "<summary>saved summary</summary>"
        }
    }

    private fun ref(library: Library): PlannedNode {
        val flow = library.groups.single().workflows.first { it.id == "leaf" }
        val node = flow.nodes.single()
        return PlannedNode(flow, node, ActionRegistry.defaults().snapshot().getValue(node.actionId))
    }

    private fun environment(library: Library, currentUser: String = "f5"): InvocationEnvironment {
        val session = library.sessions.single()
        return InvocationEnvironment(session, "BODY-5", HistoryContext(library, session, library.mainCalls.single()), currentUser)
    }

    @Test fun summaryInputProcessingCapturesAllFourHistoryTemplateFields() = runBlocking {
        val steps = listOf(
            TextProcessStep(TextProcessKind.APPEND, text = "{{history.pending}}"),
            TextProcessStep(TextProcessKind.PREPEND, text = "{{history.display}}"),
            TextProcessStep(TextProcessKind.FORMAT, text = "{value}\n{{history.pending}}"),
            TextProcessStep(TextProcessKind.REGEX_REPLACE, pattern = "seed", replacement = "{{history.display}}"))
        for (step in steps) {
            val library = fixture(listOf(step))
            val sent = mutableListOf<List<ChatMessage>>()
            var captured: RecoveryPrepared? = null
            val result = WorkflowEngine().run(library, library.sessions.single(), "BODY-5", gateway(sent)) { update ->
                update.progress?.recovery?.prepared?.firstOrNull { it.nodeKey == "leaf/small-model" }?.let { captured = it }
            }
            assertEquals("${step.kind}: ${result.traces}", RunStatus.SUCCESS, result.status)
            assertEquals(listOf("f1", "f2"), captured!!.inputSourceIds)
            val request = sent.single().joinToString("\n") { it.text }
            assertTrue(request.contains("BODY-1") && request.contains("BODY-2"))
            assertFalse(request.contains("BODY-3") || request.contains("BODY-5"))
            val segment = result.historyMemory.segments.values.single()
            assertEquals("saved summary", segment.summary)
            assertEquals(library.sessions.single().messages.take(2).map(HistoryMemory::source), segment.sources)
        }
    }

    @Test fun emptyOrUnsentProcessedInputCannotClaimItsHistoryReads() = runBlocking {
        val erased = fixture(listOf(TextProcessStep(TextProcessKind.APPEND, text = "{{history.pending}}"),
            TextProcessStep(TextProcessKind.REGEX_REPLACE, pattern = "(?s).+", replacement = "")))
        val sent = mutableListOf<List<ChatMessage>>()
        val result = WorkflowEngine().run(erased, erased.sessions.single(), "BODY-5", gateway(sent))
        assertNotEquals(RunStatus.SUCCESS, result.status)
        assertTrue(sent.isEmpty())
        assertTrue(result.historyMemory.segments.isEmpty())
        assertTrue(result.traces.any { it.detail.contains("须实际引用") })

        val library = fixture(listOf(TextProcessStep(TextProcessKind.APPEND, text = "{{history.pending}}")))
        val original = ref(library)
        val onlyFirstFloor = original.node.copy(explicitHistory = true, blocks = listOf(PromptBlock(template =
            "{{type:builtin.in:history.name:messages.source:display.from:start-1.through:start-1}}")))
        val preparation = InvocationPreparer(library).prepare(original.copy(node = onlyFirstFloor), environment(library, "f1"))
        assertEquals(listOf("f1", "f2"), preparation.inputSourceIds)
        assertEquals(listOf("f1"), preparation.historyRead!!.sources.map { it.messageId })
        assertFalse(preparation.messages.any { "BODY-2" in it.text })
    }

    @Test fun recoveryKeepsSavedInputSourcesAndLegacyReplaysTheEntireFrozenPipeline() = runBlocking {
        val library = fixture(listOf(TextProcessStep(TextProcessKind.APPEND, text = "{{history.pending}}")))
        val before = library.sessions.single()
        val paused = WorkflowEngine().run(library, before, "BODY-5", gateway(mutableListOf(), fail = true))
        assertEquals(paused.traces.toString(), RunStatus.PAUSED, paused.status)
        val decoded = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), requireNotNull(paused.recovery)))
        val captured = decoded.prepared.single { it.nodeKey == "leaf/small-model" }
        assertEquals(listOf("f1", "f2"), captured.inputSourceIds)
        val live = library.copy(groups = library.groups.map { group -> group.copy(workflows = group.workflows.map { flow ->
            flow.copy(nodes = flow.nodes.map { it.copy(inputProcessing = listOf(TextProcessStep(TextProcessKind.APPEND,
                text = "LIVE CHANGED INPUT"))) })
        }) })
        val legacy = decoded.copy(prepared = decoded.prepared.map { it.copy(inputSourceIds = null) })
        for (journal in listOf(decoded, legacy)) {
            val sent = mutableListOf<List<ChatMessage>>()
            val current = before.withRuntime(paused.runtimeSnapshot()).copy(pendingRun = journal)
            val result = WorkflowEngine().resume(live, current, gateway(sent), journal,
                RecoveryDecision(RecoveryDecisionKind.RETRY, "leaf/small-model"))
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertEquals(listOf("f1", "f2"), result.historyMemory.segments.values.single().sources.map { it.messageId })
            val request = sent.single().joinToString("\n") { it.text }
            assertTrue(request.contains("BODY-1") && request.contains("BODY-2"))
            assertFalse(request.contains("LIVE CHANGED INPUT"))
        }
    }

    @Test fun legacyMismatchAndExplicitEmptyProofNeverGuessSources() {
        val library = fixture(listOf(TextProcessStep(TextProcessKind.APPEND, text = "{{history.pending}}")))
        val preparer = InvocationPreparer(library)
        val ref = ref(library)
        val environment = environment(library)
        val rendered = preparer.renderInput(ref, environment)
        val legacy = RecoveryPrepared(ref.key, "environment", "different saved input", rendered.arguments)
        val mismatch = assertThrows(NodeExecutionFailure::class.java) { preparer.prepare(ref, environment, legacy) }
        assertTrue(mismatch.detail.contains("冻结输入无法重建"))
        val explicitlyEmpty = legacy.copy(renderedInput = rendered.text, inputSourceIds = emptyList())
        val noProof = assertThrows(NodeExecutionFailure::class.java) { preparer.prepare(ref, environment, explicitlyEmpty) }
        assertTrue(noProof.detail.contains("须实际引用"))
    }
}
