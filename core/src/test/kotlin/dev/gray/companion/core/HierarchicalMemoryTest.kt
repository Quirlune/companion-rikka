package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HierarchicalMemoryTest {
    private fun floors(count: Int): List<ChatMessage> = (1..count).map { number ->
        ChatMessage(id = "floor-$number", role = if (number % 2 == 1) Role.USER else Role.ASSISTANT,
            text = "floor $number", createdAt = number.toLong())
    }
    private fun session(count: Int) = Session(id = "session", characterId = "card", mainCallId = "main", messages = floors(count))
    private fun processor(): HistoryProcessor {
        val workflow = Workflow(id = "small", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS,
            nodes = listOf(WorkflowNode(id = "small-model", outputKey = "small_summary", includeHistory = false,
                inputTemplate = "{{history.pending}}", blocks = listOf(PromptBlock(template = "SMALL")))))
        return HistoryProcessor(workflow, minimumNewFloors = 19, keepRecentFloors = 1, outputKey = "small_summary",
            thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS, protectFirstFloors = 7, maximumBatchFloors = 11)
    }
    private fun main(processor: HistoryProcessor = processor(), refresh: Boolean = false): MainCall {
        val select = WorkflowNode(id = "roots", actionId = "history.memory.select", outputKey = "roots", inputTemplate = "",
            recordTrajectory = false, arguments = mapOf("source" to "{{history.display}}", "leafWorkflowId" to processor.workflow.id,
                "mode" to "ROOTS", "count" to "0", "requireCount" to "false"))
        val copy = WorkflowNode(id = "view", actionId = "history.view.copy", outputKey = "view", inputTemplate = "",
            recordTrajectory = false, dependsOn = listOf(select.id), arguments = mapOf("source" to "{{history.display}}",
                "targetId" to "compact", "mode" to "COMPRESS", "ids" to "{{result.roots}}", "inheritTargetModes" to "true"))
        return MainCall(id = "main", name = "Main", history = HistoryConfig(processor = processor), workflows =
            listOf(Workflow(id = "chat", nodes = listOf(WorkflowNode(id = "reply", actionId = "text.render", visible = true,
                inputTemplate = if (refresh) "{{type:builtin.in:history.name:context.source:display.view:compact}}" else "reply")))) +
                if (refresh) listOf(Workflow(id = "refresh", trigger = RuntimeEvents.TURN_PREPARE, nodes = listOf(select, copy))) else emptyList())
    }
    private fun library(main: MainCall) = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(main), profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")
    private fun withLeaves(before: Session, count: Int, workflowId: String = "small"): Session {
        val leaves = (0 until count).map { index -> HistoryMemory.segment("small ${index + 1}", "small summary ${index + 1}",
            before.messages.subList(index * 2, index * 2 + 2), workflowId) }
        return before.copy(historyMemory = HistoryMemory.apply(before.historyMemory, HistoryMemoryPatch(segments = leaves)))
    }
    private fun apply(session: Session, patch: HistoryMemoryPatch) = session.copy(historyMemory = HistoryMemory.apply(session.historyMemory, patch))

    @Test fun uncompressedThresholdCountsInputProtectsSevenAndTakesOnlyNextEleven() {
        val main = main()
        val lib = library(main)
        assertNull(HistoryContext(lib, session(17), main).pendingCandidate())
        val nineteen = HistoryContext(lib, session(19), main).pendingCandidate()!!
        assertEquals((8..18).map { "floor-$it" }, nineteen.sources.map { it.messageId })
        val twenty = session(20).let { it.copy(messages = it.messages.dropLast(1) + it.messages.last().copy(role = Role.USER)) }
        assertEquals(nineteen.sources, HistoryContext(lib, twenty, main).pendingCandidate()!!.sources)
        val marked = withLeaves(session(27), 4)
        val next = HistoryContext(lib, marked, main).pendingCandidate()!!
        assertEquals((16..26).map { "floor-$it" }, next.sources.map { it.messageId })
        val expanded = apply(marked, HistoryViews.copy(marked, HistorySelection(), "expanded", "Expanded",
            HistorySegmentMode.EXPAND, marked.historyMemory.segments.keys.toList(), main))
        assertEquals(next.sources, HistoryContext(lib, expanded, main).pendingCandidate()!!.sources)
        // Uncompressed selection may cross an already marked island, without rebinding sources.
        val island = HistoryMemory.segment("island", "kept", session(21).messages.subList(10, 12), "manual")
        val gapped = apply(session(21), HistoryMemoryPatch(segments = listOf(island)))
        val pending = HistoryContext(lib, gapped, main).pendingCandidate()!!
        assertEquals(listOf(8, 9, 10) + (13..20).toList(), pending.sources.map { it.messageId.substringAfter('-').toInt() })
        assertNull(HistoryContext(lib, session(19), main, executionVersion = 6).pendingCandidate())
    }

    @Test fun fifthSmallSummaryAggregatesOnceBeforeViewAndNormalReply() = runBlocking {
        val configured = processor().let { it.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(it)) }
        val main = main(configured, refresh = true)
        val lib = library(main)
        var smallCalls = 0
        var largeCalls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                if (messages.any { it.text == "SMALL" }) { smallCalls++; "new small summary" } else { largeCalls++; "BIG SUMMARY" }
        }
        val four = WorkflowEngine().run(lib, withLeaves(session(25), 3), "floor 25", gateway)
        assertEquals(four.traces.toString(), RunStatus.SUCCESS, four.status)
        assertEquals(1, smallCalls)
        assertEquals(0, largeCalls)
        assertEquals(4, four.historyMemory.segments.size)
        val before = withLeaves(session(27), 4)
        val five = WorkflowEngine().run(lib, before, "floor 27", gateway)
        assertEquals(five.traces.toString(), RunStatus.SUCCESS, five.status)
        assertEquals(2, smallCalls)
        assertEquals(1, largeCalls)
        val parent = five.historyMemory.segments.values.single { it.childSegmentIds.isNotEmpty() }
        assertEquals(5, parent.childSegmentIds.size)
        assertEquals(parent.childSegmentIds.flatMap { five.historyMemory.segments.getValue(it).sources }, parent.sources)
        assertEquals(2, five.historyMemory.views.getValue("compact").last().projectionVersion)
        assertTrue(five.replies.single().text.contains("BIG SUMMARY"))
        assertFalse(five.replies.single().text.contains("small summary"))
        val saved = MessageOperations.recordSuccessfulTurn(before, five).session!!
        assertEquals("", HistoryActions.select(saved, main, HistorySelection(), "small", "UNMERGED_LEAVES", 5, true))
        val next = saved.copy(messages = saved.messages + ChatMessage(id = "next", text = "next"))
        val repeated = WorkflowEngine().run(lib, next, "next", gateway)
        assertEquals(repeated.traces.toString(), RunStatus.SUCCESS, repeated.status)
        assertEquals(2, smallCalls)
        assertEquals(1, largeCalls)
        assertEquals(1, repeated.historyMemory.segments.values.count { it.childSegmentIds.isNotEmpty() })
    }

    @Test fun graphRejectsCyclesDanglingMixedOrReorderedSourcesAndInvalidatesAncestors() {
        val before = session(7)
        val left = HistoryMemory.segment("left", "left summary", before.messages.take(2), "small")
        val right = HistoryMemory.segment("right", "right summary", before.messages.subList(3, 5), "small")
        val seeded = apply(before, HistoryMemoryPatch(segments = listOf(left, right)))
        val patch = HistoryActions.parent(seeded, main(), HistorySelection(), listOf(left.id, right.id), "parent", "parent summary", "big")
        val parent = patch.segments.last()
        val all = seeded.historyMemory.segments + (parent.id to parent)
        HistoryMemory.validateSegments(all)
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.validateSegments(all +
            (parent.id to parent.copy(childSegmentIds = listOf(right.id, left.id)))) }
        assertThrows(IllegalStateException::class.java) { HistoryMemory.validateSegments(all +
            (parent.id to parent.copy(childSegmentIds = listOf(left.id, "missing")))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.validateSegments(all +
            (left.id to left.copy(childSegmentIds = listOf(parent.id, right.id)))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.validateSegments(all +
            (parent.id to parent.copy(sources = parent.sources.mapIndexed { index, ref ->
                if (index == 0) ref.copy(sourceKind = HistorySourceKind.MODEL_RAW) else ref }))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryActions.parent(seeded, main(), HistorySelection(),
            listOf(right.id, left.id), "bad order", "summary", "big") }
        val changed = MessageOperations.editText(apply(seeded, patch), before.messages.first().id, "edited").session!!
        val active = HistoryHierarchy(changed.historyMemory.segments, changed.messages.map(HistoryMemory::source), ordered = true)
        assertFalse(active.valid(left.id))
        assertFalse(active.valid(parent.id))
        assertTrue(active.valid(right.id))
    }

    @Test fun hierarchyViewsExpandByLayerKeepRefreshPreferencesAndPreserveOldFlatSnapshots() {
        val before = session(7)
        val main = main()
        val lib = library(main)
        val left = HistoryMemory.segment("left", "left summary", before.messages.take(2), "small")
        val right = HistoryMemory.segment("right", "right summary", before.messages.subList(3, 5), "small")
        var current = apply(before, HistoryMemoryPatch(segments = listOf(left, right)))
        val parentPatch = HistoryActions.parent(current, main, HistorySelection(), listOf(left.id, right.id), "parent", "parent summary", "big")
        val parent = parentPatch.segments.last()
        current = apply(current, parentPatch)
        current = apply(current, HistoryViews.copy(current, HistorySelection(), "compact", "Compact",
            HistorySegmentMode.COMPRESS, listOf(parent.id), main))
        val collapsed = HistoryViews.project(lib, current, main, HistorySelection(viewId = "compact"))
        assertTrue(collapsed.issues.toString(), collapsed.issues.isEmpty())
        assertEquals(listOf("history-memory:${parent.id}", "floor-3", "floor-6", "floor-7"), collapsed.messages.map { it.id })
        current = apply(current, HistoryViews.copy(current, HistorySelection(viewId = "compact"), "compact", "Compact",
            HistorySegmentMode.EXPAND, listOf(parent.id), main))
        val expanded = HistoryViews.project(lib, current, main, HistorySelection(viewId = "compact"))
        assertEquals(listOf("history-memory:${left.id}", "floor-3", "history-memory:${right.id}", "floor-6", "floor-7"), expanded.messages.map { it.id })
        current = apply(current, HistoryViews.copy(current, HistorySelection(viewId = "compact"), "compact", "Compact",
            HistorySegmentMode.EXPAND, listOf(left.id), main))
        val extended = current.copy(messages = current.messages + ChatMessage(id = "floor-8", text = "new input"))
        val refreshed = apply(extended, HistoryViews.copy(extended, HistorySelection(), "compact", "Compact",
            HistorySegmentMode.COMPRESS, listOf(parent.id), main, inheritTargetModes = true))
        val projected = HistoryViews.project(lib, refreshed, main, HistorySelection(viewId = "compact"))
        assertEquals(listOf("floor-1", "floor-2", "floor-3", "history-memory:${right.id}", "floor-6", "floor-7", "floor-8"), projected.messages.map { it.id })
        val edited = MessageOperations.editText(refreshed, "floor-1", "edited").session!!
        assertEquals(projected.messages.map { it.text }, HistoryViews.project(lib, edited, main, HistorySelection(viewId = "compact")).messages.map { it.text })
        val flat = HistoryViews.copy(current, HistorySelection(), "old_flat", "Old", HistorySegmentMode.COMPRESS, listOf(left.id), main,
            executionVersion = 6)
        assertEquals(1, flat.views.single().projectionVersion)
        val flatSession = apply(current, flat)
        assertEquals("history-memory:${left.id}", HistoryViews.project(lib, flatSession, main, HistorySelection(viewId = "old_flat")).messages.first().id)
        assertThrows(IllegalArgumentException::class.java) { HistoryViews.copy(current, HistorySelection(), "bad_flat", "Bad",
            HistorySegmentMode.COMPRESS, listOf(parent.id), main, executionVersion = 6) }
    }

    @Test fun aggregationBreakpointReusesAcceptedSmallAndChildrenWithFrozenSelection() = runBlocking {
        val configured = processor().let { it.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(it)) }
        val main = main(configured, refresh = true)
        val lib = library(main)
        val before = withLeaves(session(27), 4)
        var smallCalls = 0
        var largeCalls = 0
        var repaired = false
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                if (messages.any { it.text == "SMALL" }) { smallCalls++; return "new small summary" }
                largeCalls++
                if (!repaired) throw ModelRequestFailure("offline", retryable = false)
                return "recovered parent"
            }
        }
        val paused = WorkflowEngine().run(lib, before, "floor 27", gateway)
        assertEquals(paused.traces.toString(), RunStatus.PAUSED, paused.status)
        assertEquals(5, paused.historyMemory.segments.size)
        val pending = MessageOperations.recordPartialTurn(before, paused).session!!
        val journal = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), paused.recovery!!))
        assertEquals(8, journal.executionVersion)
        val aggregation = configured.aggregationWorkflow!!
        val model = aggregation.nodes.single { it.actionId == "model.generate" }
        val changedModel = model.copy(blocks = model.blocks.map { it.copy(template = it.template + "\nrepair") })
        val changedMain = main.copy(history = main.history.copy(processor = configured.copy(aggregationWorkflow =
            aggregation.copy(nodes = aggregation.nodes.map { if (it.id == model.id) changedModel else it }))))
        repaired = true
        val resumed = WorkflowEngine().resume(lib.copy(mainCalls = listOf(changedMain)), pending, gateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "${aggregation.id}/${model.id}"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(1, smallCalls)
        assertEquals(2, largeCalls)
        assertEquals(6, resumed.historyMemory.segments.size)
        assertEquals(paused.historyMemory.segments, resumed.historyMemory.segments.filterValues { it.childSegmentIds.isEmpty() })
        assertEquals(paused.historyMemory.segments.keys.toSet(), resumed.historyMemory.segments.values.single { it.childSegmentIds.isNotEmpty() }.childSegmentIds.toSet())
    }

    @Test fun appendCapturesAndModeDeltasPreserveOldViewsAndFlattenAfterSourceEdits() {
        val main = main()
        val lib = library(main)
        val before = session(7)
        val left = HistoryMemory.segment("left", "left summary", before.messages.take(2), "small")
        val right = HistoryMemory.segment("right", "right summary", before.messages.subList(3, 5), "small")
        var current = apply(before, HistoryMemoryPatch(segments = listOf(left, right)))
        current = apply(current, HistoryViews.copy(current, HistorySelection(), "compact", "Compact",
            HistorySegmentMode.COMPRESS, listOf(left.id, right.id), main))
        val originalCapture = current.historyMemory.views.getValue("compact").single().captureId
        val originalProjection = HistoryViews.project(lib, current, main, HistorySelection(viewId = "compact", revision = 1))
        current = apply(current, HistoryViews.copy(current, HistorySelection(viewId = "compact"), "compact", "Compact",
            HistorySegmentMode.EXPAND, listOf(left.id), main))
        current = current.copy(messages = floors(9))
        val refresh = HistoryViews.copy(current, HistorySelection(), "compact", "Compact", HistorySegmentMode.COMPRESS,
            listOf(left.id, right.id), main, inheritTargetModes = true)
        val appendedCapture = refresh.views.single().captureId
        assertEquals(mapOf(appendedCapture to originalCapture), refresh.captureBases)
        assertEquals(floors(9).takeLast(2).map(HistoryMemory::source), refresh.captures.getValue(appendedCapture))
        assertTrue(refresh.views.single().segmentModes.isEmpty())
        assertEquals(2, refresh.views.single().baseRevision)
        current = apply(current, refresh)
        assertEquals(9, current.historyMemory.captures.values.sumOf { it.size })
        assertEquals(floors(9).map(HistoryMemory::source), HistoryMemory.captureSources(current.historyMemory, appendedCapture))
        assertEquals(HistorySegmentMode.EXPAND, HistoryMemory.modes(current.historyMemory, refresh.views.single())[left.id])
        assertEquals(originalProjection.messages.map { it.id to it.text },
            HistoryViews.project(lib, current, main, HistorySelection(viewId = "compact", revision = 1)).messages.map { it.id to it.text })
        val parentPatch = HistoryActions.parent(current, main, HistorySelection(), listOf(left.id, right.id), "parent", "parent summary", "big")
        current = apply(current, parentPatch)
        val parent = parentPatch.segments.last()
        val parentRefresh = HistoryViews.copy(current, HistorySelection(), "compact", "Compact", HistorySegmentMode.COMPRESS,
            listOf(parent.id), main, inheritTargetModes = true)
        assertEquals(mapOf(parent.id to HistorySegmentMode.COMPRESS), parentRefresh.views.single().segmentModes)
        current = apply(current, parentRefresh)
        val edited = MessageOperations.editText(current, "floor-1", "edited").session!!
        val editRefresh = HistoryViews.copy(edited, HistorySelection(), "compact", "Compact", HistorySegmentMode.COMPRESS,
            listOf(right.id), main, inheritTargetModes = true)
        assertTrue(editRefresh.captureBases.isEmpty())
        assertEquals(9, editRefresh.captures.values.single().size)
        assertNull(editRefresh.views.single().baseViewId)
        val saved = apply(edited, editRefresh)
        HistoryMemory.validateCaptures(saved.historyMemory)
        assertEquals(originalProjection.messages.map { it.id to it.text },
            HistoryViews.project(lib, saved, main, HistorySelection(viewId = "compact", revision = 1)).messages.map { it.id to it.text })
        assertEquals(mapOf(right.id to HistorySegmentMode.COMPRESS), HistoryMemory.modes(saved.historyMemory, editRefresh.views.single()))

        val legacy = apply(before, HistoryViews.copy(before, HistorySelection(), "legacy", "Legacy", HistorySegmentMode.EXPAND,
            emptyList(), main, executionVersion = 6))
        val oldJson = kotlinx.serialization.json.Json.encodeToString(HistoryMemoryState.serializer(), legacy.historyMemory)
        assertFalse(oldJson.contains("captureBases"))
        assertEquals(legacy.historyMemory, LibraryFormat.json.decodeFromString(HistoryMemoryState.serializer(), oldJson))
        val oldRefresh = HistoryViews.copy(legacy.copy(messages = floors(9)), HistorySelection(), "legacy", "Legacy",
            HistorySegmentMode.EXPAND, emptyList(), main, inheritTargetModes = true, executionVersion = 6)
        assertTrue(oldRefresh.captureBases.isEmpty())
        assertEquals(9, oldRefresh.captures.values.single().size)
        assertEquals(1, oldRefresh.views.single().projectionVersion)
    }

    @Test fun captureDeltaReceiptReplaysExactlyAndRejectsInvalidBases(): Unit = runBlocking {
        val originalMain = main()
        val seeded = session(3).let { apply(it, HistoryViews.copy(it, HistorySelection(), "compact", "Compact",
            HistorySegmentMode.EXPAND, emptyList(), originalMain)) }
        val copy = WorkflowNode(id = "copy", actionId = "history.view.copy", outputKey = "copy", includeHistory = false,
            arguments = mapOf("source" to "{{history.display}}", "targetId" to "compact", "inheritTargetModes" to "true"))
        val fail = WorkflowNode(id = "fail", actionId = "fail.once", outputKey = "done", includeHistory = false,
            dependsOn = listOf(copy.id))
        val configured = originalMain.copy(workflows = listOf(Workflow(id = "copy-flow", nodes = listOf(copy, fail))))
        val lib = library(configured)
        var attempts = 0
        val registry = ActionRegistry.defaults().register(ActionDescriptor("fail.once", "Fail once")) {
            check(++attempts > 1) { "pause after capture" }
            ActionOutcome("done")
        }
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                error("No model expected")
        }
        val before = seeded.copy(messages = floors(5))
        val engine = WorkflowEngine(registry)
        val paused = engine.run(lib, before, "floor 5", gateway)
        assertEquals(paused.traces.toString(), RunStatus.PAUSED, paused.status)
        val journal = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), paused.recovery!!))
        val write = journal.accepted.single { it.nodeKey == "copy-flow/copy" }.publication.historyWrites
        assertEquals(1, write.captureBases.size)
        assertEquals(2, write.captures.values.single().size)
        val pending = MessageOperations.recordPartialTurn(before, paused).session!!
        val resumed = engine.resume(lib, pending, gateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "copy-flow/fail"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(paused.historyMemory, resumed.historyMemory)
        assertEquals(2, resumed.historyMemory.views.getValue("compact").size)
        HistoryMemory.validateCaptures(resumed.historyMemory)
        val id = write.captures.keys.single()
        val baseId = write.captureBases.getValue(id)
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.apply(seeded.historyMemory,
            HistoryMemoryPatch(captureBases = mapOf(baseId to id))) }
        assertThrows(IllegalStateException::class.java) { HistoryMemory.apply(seeded.historyMemory,
            write.copy(views = emptyList(), expectedViewRevisions = emptyMap(), captureBases = mapOf(id to "missing"))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.apply(seeded.historyMemory,
            write.copy(views = emptyList(), expectedViewRevisions = emptyMap(), captureBases = mapOf(id to id))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.apply(resumed.historyMemory,
            HistoryMemoryPatch(captures = mapOf(id to HistoryMemory.captureSources(resumed.historyMemory, id)))) }
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.validateCaptures(
            resumed.historyMemory.copy(captureBases = mapOf(id to baseId, baseId to id))) }
        val duplicateSources = HistoryMemory.captureSources(seeded.historyMemory, baseId) + floors(3).map(HistoryMemory::source).take(1)
        assertThrows(IllegalArgumentException::class.java) { HistoryMemory.apply(seeded.historyMemory,
            HistoryMemoryPatch(captures = mapOf(HistoryMemory.captureId(duplicateSources) to duplicateSources.takeLast(1)),
                captureBases = mapOf(HistoryMemory.captureId(duplicateSources) to baseId))) }
    }
}
