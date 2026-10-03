package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HistoryMemoryTest {
    private val messages = listOf(
        ChatMessage(id = "one", role = Role.USER, text = "old question"),
        ChatMessage(id = "two", role = Role.ASSISTANT, text = "old answer"),
        ChatMessage(id = "three", role = Role.USER, text = "new question"),
        ChatMessage(id = "four", role = Role.ASSISTANT, text = "accepted body"),
        ChatMessage(id = "side", role = Role.ASSISTANT, text = "status", outputSlot = OutputSlot.AFTER_BODY)
    )
    private val main = MainCall(id = "main", name = "Main", history = HistoryConfig(floorPrefix = "{{floor}}: "))
    private fun session() = Session(id = "session", characterId = "hero", mainCallId = main.id, messages = messages)
    private fun library(mainCall: MainCall = main) = Library(characters = listOf(
        CharacterCard(id = "hero", namespace = "hero", name = "Hero")), mainCalls = listOf(mainCall))
    private val unusedGateway = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("This scenario uses no model")
    }

    @Test fun referenceRangeSelectsBodyBeforeProjectionAndKeepsOriginalFloorNumbers() {
        val expression = "type:builtin.in:history.name:messages.from:end-3.through:end-1"
        val selection = requireNotNull(HistoryViews.selection(expression))
        val projected = HistoryViews.project(library(), session(), main, selection)
        assertTrue(projected.issues.isEmpty())
        assertEquals(listOf("two", "three", "four"), projected.messages.map { it.id })
        assertEquals(listOf("2: old answer", "3: new question", "4: accepted body"), projected.messages.map { it.text })
        val percentage = requireNotNull(HistoryViews.selection("type:builtin.in:history.name:messages.from:percent-50.through:percent-100"))
        assertEquals(listOf("two", "three", "four"), HistoryViews.sources(session(), percentage).map { it.messageId })
        val node = WorkflowNode(inputTemplate = "new question", includeHistory = false,
            blocks = listOf(PromptBlock(template = "{{$expression}}")))
        val preview = PromptCompiler().preview(library(), session(), node, "new question", currentUserMessageId = "three")
        assertTrue(preview.issues.none { it.error })
        assertTrue(preview.messages.any { it.id == "four" && it.text.contains("accepted body") })
        assertEquals(1, preview.messages.count { it.id == "three" })
    }

    @Test fun compressedAndExpandedCopiesShareCaptureAndPreserveExactSourceRevision() {
        val segment = HistoryMemory.segment("Earlier", "brief memory", messages.take(2), main.history.processor.workflow.id)
        val initial = session().copy(historyMemory = HistoryMemoryState(segments = mapOf(segment.id to segment)))
        val compressedPatch = HistoryViews.copy(initial, HistorySelection(), "compact", "Compact",
            HistorySegmentMode.COMPRESS, listOf(segment.id), main)
        val compact = initial.copy(historyMemory = HistoryMemory.apply(initial.historyMemory, compressedPatch))
        val expandedPatch = HistoryViews.copy(compact, HistorySelection(viewId = "compact"), "expanded", "Expanded",
            HistorySegmentMode.EXPAND, listOf(segment.id), main)
        assertTrue(expandedPatch.captures.isEmpty())
        val expanded = compact.copy(historyMemory = HistoryMemory.apply(compact.historyMemory, expandedPatch))
        assertEquals(1, expanded.historyMemory.captures.size)
        assertEquals("compact", expanded.historyMemory.views.getValue("expanded").single().baseViewId)
        assertEquals(messages.filter { it.outputSlot == OutputSlot.BODY }.map { it.id },
            HistoryViews.project(library(), expanded, main, HistorySelection(viewId = "expanded")).messages.map { it.id })
        val wire = Json.encodeToString(HistoryMemoryState.serializer(), expanded.historyMemory)
        assertFalse(wire.contains("old question"))
        assertFalse(wire.contains("old answer"))
        val edited = requireNotNull(MessageOperations.editText(expanded, "one", "edited question").session)
        val captured = HistoryViews.project(library(), edited, main, HistorySelection(viewId = "expanded"))
        assertTrue(captured.issues.isEmpty())
        assertTrue(captured.messages.first().text.contains("old question"))
        assertFalse(HistoryContext(library(), edited, main).read("summary")!!.contains("brief memory"))
        assertEquals(expanded.historyMemory, MessageOperations.captureRuntime(expanded).historyMemory)
    }

    @Test fun processedSourceInheritsUnselectedSummariesAndPersistsLegacyMappingOnlyOnWrite() {
        val digest = HistoryDigest("legacy summary", "two", 2, main.history.processor.workflow.id)
        val second = HistoryMemory.segment("Recent", "second summary", messages.subList(2, 4), main.history.processor.workflow.id)
        val initial = session().copy(historyDigests = mapOf(main.id to digest),
            historyMemory = HistoryMemoryState(segments = mapOf(second.id to second)))
        val legacy = requireNotNull(HistoryMemory.legacySegment(initial, main))
        assertTrue(HistoryContext(library(), initial, main).read("summary")!!.contains(legacy.id))
        assertEquals(1, initial.historyMemory.segments.size)
        val patch = HistoryViews.copy(initial, requireNotNull(HistoryViews.selection("{{history}}")), "recalled", "Recalled",
            HistorySegmentMode.EXPAND, listOf(legacy.id), main)
        val copied = initial.copy(historyMemory = HistoryMemory.apply(initial.historyMemory, patch))
        val projected = HistoryViews.project(library(), copied, main, HistorySelection(viewId = "recalled"))
        assertTrue(projected.issues.isEmpty())
        assertEquals(listOf("one", "two", "history-memory:${second.id}"), projected.messages.map { it.id })
        assertTrue(copied.historyMemory.segments.containsKey(legacy.id))
        assertEquals(digest, copied.historyDigests[main.id])
    }

    @Test fun unknownIdsOverlapsAndMissingSourceNeverSilentlySubstituteText() {
        val first = HistoryMemory.segment("First", "first", messages.take(2), "writer")
        val overlap = HistoryMemory.segment("Overlap", "overlap", messages.subList(1, 3), "writer")
        val initial = session().copy(historyMemory = HistoryMemoryState(segments = mapOf(first.id to first, overlap.id to overlap)))
        assertThrows(IllegalStateException::class.java) { HistoryViews.copy(initial, HistorySelection(), "bad", "Bad",
            HistorySegmentMode.EXPAND, listOf("missing"), main) }
        assertThrows(IllegalArgumentException::class.java) { HistoryViews.copy(initial, HistorySelection(), "bad", "Bad",
            HistorySegmentMode.COMPRESS, listOf(first.id, overlap.id), main) }
        val patch = HistoryViews.copy(initial, HistorySelection(), "copy", "Copy", HistorySegmentMode.EXPAND, emptyList(), main)
        val copied = initial.copy(historyMemory = HistoryMemory.apply(initial.historyMemory, patch))
        val deleted = copied.copy(messages = copied.messages.filterNot { it.id == "one" })
        val projection = HistoryViews.project(library(), deleted, main, HistorySelection(viewId = "copy"))
        assertTrue(projection.messages.isEmpty())
        assertTrue(projection.issues.any { it.error })
        assertThrows(OutputFormatException::class.java) { HistoryActions.selectedIds("unmatched", "memory-[a-z]+", 0) }
    }

    @Test fun completionRecoveryReplaysViewReceiptWithoutCreatingAnotherRevision() = runBlocking {
        val copy = WorkflowNode(id = "copy", actionId = "history.view.copy", outputKey = "view_identity", includeHistory = false,
            arguments = mapOf("source" to "{{history.raw}}", "targetId" to "archive"))
        val source = Workflow(id = "source", nodes = listOf(copy))
        val consumer = Workflow(id = "consumer", afterWorkflowId = source.id, nodes = listOf(
            WorkflowNode(id = "fail", actionId = "try.once", outputKey = "consumed", includeHistory = false)))
        var attempts = 0
        val registry = ActionRegistry.defaults().register(ActionDescriptor("try.once", "Try")) { context ->
            attempts++
            check(attempts > 1) { "pause" }
            check(context.session!!.historyMemory.views["archive"]?.size == 1)
            ActionOutcome("done")
        }
        val configuredMain = main.copy(workflows = listOf(source, consumer))
        val lib = library(configuredMain)
        val before = session().copy(messages = messages.take(3))
        val engine = WorkflowEngine(registry)
        val paused = engine.run(lib, before, "new question", unusedGateway)
        assertEquals(RunStatus.PAUSED, paused.status)
        val journal = requireNotNull(paused.recovery)
        assertEquals(2, journal.executionVersion)
        assertTrue("source" in journal.completedWorkflowIds)
        val resumed = engine.resume(lib, before.copy(pendingRun = journal), unusedGateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "consumer/fail"))
        assertEquals(RunStatus.SUCCESS, resumed.status)
        assertEquals(2, attempts)
        assertEquals(1, resumed.historyMemory.views.getValue("archive").size)
        assertEquals(paused.historyMemory.captures, resumed.historyMemory.captures)
        assertEquals(1, resumed.macroHistory.getValue("result.view_identity").size)
    }

    @Test fun legacyJournalResumesInSameSchedulerWithoutNewCompletionSemantics() = runBlocking {
        var attempts = 0
        val registry = ActionRegistry.defaults().register(ActionDescriptor("try.once", "Try")) {
            attempts++
            check(attempts > 1)
            ActionOutcome("done")
        }
        val flow = Workflow(id = "legacy", nodes = listOf(WorkflowNode(id = "work", actionId = "try.once", includeHistory = false)))
        val lib = library(main.copy(workflows = listOf(flow)))
        val before = session().copy(messages = messages.take(3))
        val engine = WorkflowEngine(registry)
        val paused = engine.run(lib, before, "new question", unusedGateway)
        val old = requireNotNull(paused.recovery).copy(executionVersion = 1)
        val resumed = engine.resume(lib, before.copy(pendingRun = old), unusedGateway, old,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "legacy/work"))
        assertEquals(RunStatus.SUCCESS, resumed.status)
        assertEquals(2, attempts)
        assertEquals(HistoryMemoryState(), resumed.historyMemory)
    }
}
