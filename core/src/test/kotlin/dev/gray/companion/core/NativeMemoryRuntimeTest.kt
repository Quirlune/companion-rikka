package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryRuntimeTest {
    private fun floors(count: Int) = (1..count).map { number -> ChatMessage(id = "floor-$number",
        role = if (number % 2 == 1) Role.USER else Role.ASSISTANT, text = "floor $number",
        rawText = if (number % 2 == 0) "raw $number" else null, createdAt = number.toLong()) }
    private val leaf = Workflow(id = "small", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS,
        nodes = listOf(WorkflowNode(id = "small-model", outputKey = "small_summary", includeHistory = false,
            inputTemplate = "{{history.pending}}", blocks = listOf(PromptBlock(id = "small-block", template = "SMALL")))))
    private val processor = HistoryProcessor(leaf, minimumNewFloors = 19, keepRecentFloors = 1,
        outputKey = "small_summary", thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS,
        protectFirstFloors = 7, maximumBatchFloors = 11)
    private val aggregate = defaultHistoryAggregationWorkflow(processor)
    private val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", title = "Chat",
        nodes = listOf(WorkflowNode(id = "reply", actionId = "text.render", visible = true,
            includeHistory = false, inputTemplate = "{{history.text}}")))))
    private fun rules(kind: HistorySourceKind = HistorySourceKind.DISPLAY) = MemoryPluginRules(
        groupId = "memory-group", workflowId = leaf.id, aggregationWorkflowId = aggregate.id,
        outputKey = processor.outputKey, sourceKind = kind, minimumNewFloors = 19, keepRecentFloors = 1,
        thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS, protectFirstFloors = 7, maximumBatchFloors = 11)
    private fun library(kind: HistorySourceKind = HistorySourceKind.DISPLAY): Library {
        val flows = if (kind == HistorySourceKind.DISPLAY) listOf(leaf, aggregate) else listOf(leaf)
        val nativeRules = rules(kind).let { if (kind == HistorySourceKind.DISPLAY) it else it.copy(aggregationWorkflowId = null) }
        return Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), groups = listOf(CallGroup(id = "memory-group", name = "Memory", workflows = flows)),
            extensions = listOf(ConfigurationExtension(id = "memory", name = "Memory",
                resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, "memory-group")), memory = nativeRules)),
            profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")
    }
    private fun session(count: Int, active: Boolean = true) = Session(id = "session", characterId = "card", mainCallId = main.id,
        messages = floors(count), enabledExtensionIds = if (active) listOf("memory") else emptyList())
    private fun leaves(source: Session, count: Int, family: String = leaf.id): Session {
        val segments = (0 until count).map { index -> HistoryMemory.segment("small", "small summary $index",
            source.messages.subList(index * 2, index * 2 + 2), family) }
        return source.copy(historyMemory = HistoryMemory.apply(source.historyMemory, HistoryMemoryPatch(segments = segments)))
    }

    @Test fun nativeCandidatePreservesCustomRulesAndMarksOnlyItsOwnFamily() {
        val lib = library()
        assertNull(HistoryContext(lib, session(17), main, executionVersion = 13).pendingCandidate())
        val before = session(19)
        val pending = HistoryContext(lib, before, main, executionVersion = 13).pendingCandidate()!!
        assertEquals((8..18).map { "floor-$it" }, pending.sources.map { it.messageId })
        assertEquals(11, pending.newFloors)
        assertEquals(before.messages, session(19).messages)
        val island = HistoryMemory.segment("other", "unrelated", before.messages.subList(10, 12), "another-processor")
        val foreign = before.copy(historyMemory = HistoryMemory.apply(before.historyMemory, HistoryMemoryPatch(segments = listOf(island))))
        assertEquals(pending.sources, HistoryContext(lib, foreign, main, executionVersion = 13).pendingCandidate()!!.sources)
        val legacyMain = main.copy(history = main.history.copy(processor = processor))
        val legacyLibrary = lib.copy(mainCalls = listOf(legacyMain), groups = emptyList(), extensions = emptyList())
        assertNull(HistoryContext(legacyLibrary, foreign, legacyMain, executionVersion = 12).pendingCandidate())
    }

    @Test fun disablingRestoresOriginalProjectionWithoutDeletingSummariesOrExplicitViews() {
        val lib = library()
        val before = leaves(session(9), 1)
        val active = HistoryViews.project(lib, before, main, HistorySelection(compressed = true), executionVersion = 13)
        assertTrue(active.issues.toString(), active.issues.isEmpty())
        assertTrue(active.messages.any { it.text.contains("small summary") })
        val patch = HistoryViews.copy(before, HistorySelection(), "explicit", "Explicit", HistorySegmentMode.COMPRESS,
            before.historyMemory.segments.keys.toList(), main, executionVersion = 13, library = lib)
        val archived = before.copy(historyMemory = HistoryMemory.apply(before.historyMemory, patch))
        val off = archived.copy(enabledExtensionIds = emptyList())
        assertNull(HistoryContext(lib, off, main, executionVersion = 13).pendingCandidate())
        val raw = HistoryViews.project(lib, off, main, HistorySelection(compressed = true), executionVersion = 13)
        assertEquals(off.messages.map { it.text }, raw.messages.map { it.text })
        assertEquals(archived.historyMemory, off.historyMemory)
        assertTrue(HistoryViews.project(lib, off, main, HistorySelection(compressed = true, viewId = "explicit"),
            executionVersion = 13).messages.any { it.text.contains("small summary") })
        assertTrue(HistoryActions.read(off, main, before.historyMemory.segments.keys.toList()).contains("small summary"))
        val oldMain = main.copy(history = main.history.copy(processor = processor.copy(workflow = leaf.copy(enabled = false))))
        assertTrue(HistoryContext(lib.copy(mainCalls = listOf(oldMain), extensions = emptyList()), off, oldMain,
            executionVersion = 12).projected().any { it.text.contains("small summary") })
        val modelLibrary = library(HistorySourceKind.MODEL_RAW)
        assertEquals("raw 8", HistoryContext(modelLibrary, session(19), main, executionVersion = 13)
            .pendingCandidate()!!.messages.first().text)
    }

    @Test fun fifthLeafAggregatesThroughOriginalEngineWithoutCreatingAViewAndOffMasksManualGroup() = runBlocking {
        val lib = library()
        val before = leaves(session(27), 4)
        var smallCalls = 0
        var largeCalls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                if (messages.any { it.text == "SMALL" }) { smallCalls++; "new small summary" } else { largeCalls++; "BIG SUMMARY" }
        }
        val result = WorkflowEngine().run(lib, before, "floor 27", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(1, smallCalls)
        assertEquals(1, largeCalls)
        val parent = result.historyMemory.segments.values.single { it.childSegmentIds.isNotEmpty() }
        assertEquals(5, parent.childSegmentIds.size)
        assertEquals(parent.childSegmentIds.flatMap { result.historyMemory.segments.getValue(it).sources }, parent.sources)
        assertTrue(result.historyMemory.views.isEmpty())
        assertTrue(result.historyMemory.captures.isEmpty())
        assertEquals("memory-group", result.resultOrigins.getValue("small_summary").resourceId)
        assertTrue(result.replies.single().text.contains("BIG SUMMARY"))
        assertEquals(before.messages, session(27).messages)
        val off = before.copy(enabledExtensionIds = emptyList(), groupIds = listOf("memory-group"))
        val closed = WorkflowEngine().run(lib, off, "floor 27", gateway)
        assertEquals(closed.traces.toString(), RunStatus.SUCCESS, closed.status)
        assertEquals(1, smallCalls)
        assertEquals(1, largeCalls)
        assertEquals(off.historyMemory, closed.historyMemory)
        assertFalse(closed.replies.single().text.contains("small summary"))
        val restoredOff = off.withRuntime(closed.runtimeSnapshot()).copy(
            executableWorld = CompletedWorlds.checkpointWorld(closed.completedWorld))
        val resumed = WorkflowEngine().run(CompletedWorlds.executionLibrary(lib, restoredOff), restoredOff, "floor 27", gateway)
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(1, smallCalls)
        assertEquals(1, largeCalls)
    }

    @Test fun nativeAggregationResumeReusesAcceptedLeafAndFrozenChildrenAfterGroupPromptRepair() = runBlocking {
        val lib = library()
        val before = leaves(session(27), 4)
        var smallCalls = 0
        var largeCalls = 0
        var repaired = false
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                if (messages.any { it.text == "SMALL" }) { smallCalls++; return "new small summary" }
                largeCalls++
                if (!repaired) throw ModelRequestFailure("offline", retryable = false)
                assertTrue(messages.any { it.text.contains("repair") })
                assertTrue(messages.any { it.text.contains("new small summary") })
                return "recovered parent"
            }
        }
        val paused = WorkflowEngine().run(lib, before, "floor 27", gateway)
        assertEquals(paused.traces.toString(), RunStatus.PAUSED, paused.status)
        assertEquals(5, paused.historyMemory.segments.size)
        assertEquals("memory-group", paused.resultOrigins.getValue("small_summary").resourceId)
        val pending = MessageOperations.recordPartialTurn(before, paused).session!!
        val journal = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), paused.recovery!!))
        assertEquals(ExecutionSemantics.CURRENT, journal.executionVersion)
        assertEquals(rules(), journal.frozenResources.extensions.single { it.id == "memory" }.memory)
        val model = aggregate.nodes.single { it.actionId == "model.generate" }
        val changedModel = model.copy(blocks = model.blocks.map { it.copy(template = it.template + "\nrepair") })
        val changedGroup = lib.groups.single().copy(workflows = lib.groups.single().workflows.map { workflow ->
            if (workflow.id != aggregate.id) workflow else workflow.copy(nodes = workflow.nodes.map {
                if (it.id == model.id) changedModel else it
            })
        })
        repaired = true
        val resumed = WorkflowEngine().resume(lib.copy(groups = listOf(changedGroup)), pending, gateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "${aggregate.id}/${model.id}"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(1, smallCalls)
        assertEquals(2, largeCalls)
        assertEquals(6, resumed.historyMemory.segments.size)
        assertEquals(paused.historyMemory.segments,
            resumed.historyMemory.segments.filterValues { it.childSegmentIds.isEmpty() })
        val parent = resumed.historyMemory.segments.values.single { it.childSegmentIds.isNotEmpty() }
        assertEquals(5, parent.childSegmentIds.size)
        assertEquals(paused.historyMemory.segments.keys, parent.childSegmentIds.toSet())
        assertEquals(parent.childSegmentIds.flatMap { resumed.historyMemory.segments.getValue(it).sources }, parent.sources)
        assertTrue(resumed.historyMemory.views.isEmpty())
        assertTrue(resumed.historyMemory.captures.isEmpty())
    }
}
