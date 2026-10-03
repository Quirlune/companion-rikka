package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistorySourcesTest {
    private val question = ChatMessage(id = "question", text = "question")
    private val reply = ChatMessage(id = "reply", role = Role.ASSISTANT, text = "clean display",
        rawText = "<thinking>archived</thinking>initial model", turnId = question.id,
        bodyDisplay = BodyDisplaySnapshot("body", "<p>clean display</p>"))
    private val current = ChatMessage(id = "current", text = "draft")
    private val main = MainCall(id = "main", name = "Main")
    private fun session(messages: List<ChatMessage> = listOf(question, reply, current)) =
        Session(id = "session", characterId = "hero", mainCallId = main.id, messages = messages)
    private fun library(mainCall: MainCall = main) = Library(characters = listOf(
        CharacterCard(id = "hero", namespace = "hero", name = "Hero")), mainCalls = listOf(mainCall))
    private val raw = HistorySelection(sourceKind = HistorySourceKind.MODEL_RAW)
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (String) -> Unit): String = error("No provider call expected")
    }

    @Test fun modelAndDisplaySourcesKeepIndependentRevisionsAndNeverGuessMissingArchives() {
        val initial = session()
        val displaySources = HistoryViews.sources(initial, HistorySelection())
        val modelSources = HistoryViews.sources(initial, raw)
        assertNotEquals(displaySources, modelSources)
        assertNotEquals(HistoryMemory.captureId(displaySources), HistoryMemory.captureId(modelSources))
        assertEquals(reply.id, modelSources[1].revisionId)
        assertEquals(reply.rawText, HistoryViews.project(library(), initial, main, raw).messages[1].text)
        assertNull(HistoryViews.project(library(), initial, main, raw).messages[1].bodyDisplay)
        val edited = MessageOperations.editText(initial, reply.id, "later display").session!!
        assertEquals(modelSources, HistoryViews.sources(edited, raw))
        assertNotEquals(displaySources, HistoryViews.sources(edited, HistorySelection()))
        assertEquals(reply.text, HistoryMemory.resolve(displaySources[1], HistoryMemory.sourceIndex(edited))!!.text)
        val changedUser = MessageOperations.editText(edited, question.id, "changed question").session!!
        assertNotEquals(modelSources[0], HistoryViews.sources(changedUser, raw)[0])
        assertEquals(question.text, HistoryMemory.resolve(modelSources[0], HistoryMemory.sourceIndex(changedUser))!!.text)
        val missing = initial.copy(messages = listOf(question, reply.copy(rawText = null), current))
        val unavailable = HistoryViews.project(library(), missing, main, raw)
        assertTrue(unavailable.messages.isEmpty())
        assertTrue(unavailable.issues.any { it.error && it.message.contains("初始模型原文缺失") })
        val onlyCurrent = raw.copy(range = HistoryRange(HistoryPoint(HistoryAnchor.END, 1.0)))
        assertEquals(listOf(current.id), HistoryViews.project(library(), missing, main, onlyCurrent).sourceIds)
        val resolver = MacroResolver(library(), initial)
        assertTrue(resolver.render("{{history.raw}}").contains(reply.text))
        assertFalse(resolver.render("{{history.raw}}").contains("<thinking>"))
        assertTrue(resolver.render("{{history.model}}").contains("<thinking>"))
        assertTrue(resolver.issues.isEmpty())

        val before = session(listOf(question))
        val first = MessageOperations.recordSuccessfulTurn(before, RunResult(emptyMap(), emptyMap(), listOf(reply), emptyList())).session!!
        val candidate = MessageOperations.prepareRegeneration(first, question.id).session!!
        val secondReply = reply.copy(id = "other", text = "other display", rawText = "other model")
        val second = MessageOperations.commitRegeneration(first, candidate,
            RunResult(emptyMap(), emptyMap(), listOf(secondReply), emptyList())).session!!
        assertEquals("other model", HistoryViews.project(library(), second, main, raw).messages.last().text)
        val originalId = second.turnRecords.single().variants.first().id
        val restored = MessageOperations.applyLatestVariant(second, question.id, originalId).session!!
        assertEquals(reply.rawText, HistoryViews.project(library(), restored, main, raw).messages.last().text)
    }

    @Test fun rawMemoryActionsAndExactRecoveryKeepCapturesSeparateFromDisplay() = runBlocking {
        val remember = WorkflowNode(id = "remember", actionId = "history.memory.write", inputTemplate = "one summary",
            outputKey = "memory_id", includeHistory = false, arguments = mapOf("source" to "{{history.model}}"))
        val copy = WorkflowNode(id = "copy", actionId = "history.view.copy", outputKey = "view_id", includeHistory = false,
            dependsOn = listOf(remember.id), arguments = mapOf("source" to "{{history.model}}", "targetId" to "raw-copy",
                "ids" to "{{result.memory_id}}"))
        val consumer = Workflow(id = "consumer", afterWorkflowId = "source", nodes = listOf(
            WorkflowNode(id = "pause", actionId = "try.once", outputKey = "done", includeHistory = false)))
        val configured = main.copy(workflows = listOf(Workflow(id = "source", nodes = listOf(remember, copy)), consumer))
        var attempts = 0
        val actions = ActionRegistry.defaults().register("try.once") {
            attempts++
            check(attempts > 1) { "pause once" }
            ActionOutcome("done")
        }
        val before = session()
        val lib = library(configured)
        val engine = WorkflowEngine(actions)
        val first = engine.run(lib, before, "sent", noModel)
        assertEquals(first.traces.toString(), RunStatus.PAUSED, first.status)
        val journal = first.recovery!!
        assertEquals(5, journal.executionVersion)
        val roundTrip = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), journal))
        val resumed = engine.resume(lib, before.copy(pendingRun = roundTrip), noModel, roundTrip,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "consumer/pause"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(first.historyMemory, resumed.historyMemory)
        assertEquals(2, attempts)
        val saved = before.copy(historyMemory = resumed.historyMemory)
        val segment = saved.historyMemory.segments.values.single()
        assertTrue(segment.sources.all { it.sourceKind == HistorySourceKind.MODEL_RAW })
        val compact = raw.copy(viewId = "raw-copy")
        assertEquals(listOf("history-memory:${segment.id}"), HistoryViews.project(lib, saved, configured, compact).messages.map { it.id })
        val expandedPatch = HistoryViews.copy(saved, compact, "expanded", "Expanded", HistorySegmentMode.EXPAND, listOf(segment.id), configured)
        assertTrue(expandedPatch.captures.isEmpty())
        val expanded = saved.copy(historyMemory = HistoryMemory.apply(saved.historyMemory, expandedPatch))
        assertEquals(listOf(question.text, reply.rawText, current.text),
            HistoryViews.project(lib, expanded, configured, raw.copy(viewId = "expanded")).messages.map { it.text })
        assertTrue(HistoryViews.project(lib, saved, configured, HistorySelection(viewId = "raw-copy")).issues.any { it.error })
        assertThrows(IllegalArgumentException::class.java) {
            HistoryViews.copy(saved, HistorySelection(), "wrong", "Wrong", HistorySegmentMode.EXPAND, listOf(segment.id), configured)
        }
        val resolver = MacroResolver(lib, saved)
        assertTrue(resolver.render("{{history.model.summary}}").contains(segment.id))
        assertEquals("", resolver.render("{{history.display.summary}}"))
        assertEquals("", resolver.render("{{history.summary}}"))
        val captureWire = LibraryFormat.json.encodeToString(HistoryMemoryState.serializer(), saved.historyMemory)
        assertFalse(captureWire.contains(reply.rawText!!))
        assertFalse(captureWire.contains(reply.text))
    }

    @Test fun automaticThresholdUsesEligibleContiguousUnmarkedFloorsOfTheSelectedSource() = runBlocking {
        val floors = (0 until 12).map { index -> ChatMessage(id = "floor-$index",
            role = if (index % 2 == 0) Role.USER else Role.ASSISTANT, text = "display-$index",
            rawText = if (index % 2 == 1) "model-$index" else null) } + current
        val processor = main.history.processor.copy(minimumNewFloors = 3, keepRecentFloors = 2,
            workflow = main.history.processor.workflow.copy(enabled = true))
        val configured = main.copy(history = main.history.copy(processor = processor))
        val manual = HistoryMemory.segment("manual", "covered", floors.subList(1, 3), "manual-writer")
        val marked = session(floors).copy(historyMemory = HistoryMemoryState(segments = mapOf(manual.id to manual)))
        val pending = HistoryContext(library(configured), marked, configured).pendingCandidate()!!
        assertEquals(floors.subList(3, 10).map { it.id }, pending.messages.map { it.id })
        assertEquals(7, pending.newFloors)
        assertEquals(pending.messages.map(HistoryMemory::source), pending.sources)
        val rawConfigured = configured.copy(history = configured.history.copy(processor = processor.copy(sourceKind = HistorySourceKind.MODEL_RAW)))
        val rawPending = HistoryContext(library(rawConfigured), marked, rawConfigured).pendingCandidate()!!
        assertEquals(10, rawPending.newFloors) // DISPLAY marks cannot cover MODEL_RAW floors.
        assertEquals("model-1", rawPending.messages[1].text)
        assertTrue(rawPending.sources.all { it.sourceKind == HistorySourceKind.MODEL_RAW })
        val separated = listOf(2, 5, 8).map { index ->
            HistoryMemory.segment("mark", "covered-$index", listOf(floors[index]), processor.workflow.id)
        }
        val gaps = session(floors).copy(historyMemory = HistoryMemoryState(segments = separated.associateBy { it.id }))
        assertNull(HistoryContext(library(configured), gaps, configured).pendingCandidate())
        for (version in 2..4) {
            assertEquals(2, HistoryContext(library(configured), gaps, configured, executionVersion = version).pendingCandidate()!!.newFloors)
        }
        for (version in 1..4) {
            val historical = HistoryContext(library(rawConfigured), marked, rawConfigured,
                legacyProcessing = version == 1, executionVersion = version).pendingCandidate()!!
            assertEquals(10, historical.newFloors)
            assertEquals("display-1", historical.messages[1].text)
            assertTrue(historical.sources.all { it.sourceKind == HistorySourceKind.DISPLAY })
        }
        val missing = marked.copy(messages = marked.messages.map { if (it.id == "floor-1") it.copy(rawText = null) else it })
        val withMissing = HistoryContext(library(rawConfigured), missing, rawConfigured)
        assertTrue(withMissing.issues.any { it.message.contains("floor-1") })
        assertEquals(floors.subList(2, 10).map { it.id }, withMissing.pendingCandidate()!!.messages.map { it.id })

        val automatic = rawConfigured.copy(workflows = listOf(Workflow(id = "main-flow", nodes = listOf(
            WorkflowNode(id = "body", actionId = "text.render", inputTemplate = "done")))),
            history = rawConfigured.history.copy(processor = rawConfigured.history.processor.copy(
                workflow = processor.workflow.copy(nodes = listOf(WorkflowNode(id = "summary", actionId = "text.render",
                    inputTemplate = "{{history.pending}}", outputKey = processor.outputKey, includeHistory = false))))))
        val accepted = WorkflowEngine().run(library(automatic), marked, "sent", noModel)
        assertEquals(accepted.traces.toString(), RunStatus.SUCCESS, accepted.status)
        val rawSegment = accepted.historyMemory.segments.values.single { it.sources.first().sourceKind == HistorySourceKind.MODEL_RAW }
        assertEquals(rawPending.sources, rawSegment.sources)
        assertTrue(rawSegment.summary.contains("model-1"))
    }

    @Test fun structuredMergedHistoryUsesSourceIdentityAndOldExecutionsKeepTheirMeaning() {
        val configured = main.copy(history = main.history.copy(mergeMessages = true))
        for (expression in listOf("history", "history.display", "history.model",
            "type:builtin.in:history.name:messages.source:model")) {
            val node = WorkflowNode(blocks = listOf(PromptBlock(template = "{{$expression}}")))
            val preview = PromptCompiler().preview(library(configured), session(), node, "sent")
            assertTrue(preview.issues.toString(), preview.issues.none { it.error })
            assertEquals(expression, 1, preview.messages.size)
            assertEquals(expression, 1, Regex("sent").findAll(preview.messages.single().text).count())
        }
        for (version in 1..4) {
            val node = WorkflowNode(blocks = listOf(PromptBlock(template = "{{history}}")))
            assertEquals(2, PromptCompiler().preview(library(configured), session(), node, "sent", executionVersion = version).messages.size)
            val old = MacroResolver(library(configured), session(), executionVersion = version)
            assertEquals("", old.render("{{history.model}}"))
            assertEquals("", old.render("{{type:builtin.in:history.name:messages.source:display}}"))
            assertTrue(old.issues.all { it.error })
            assertTrue(old.render("{{history.raw}}").contains(reply.text))
        }
        val selection = HistoryViews.selection("type:builtin.in:history.name:messages.source:model.from:end-2.through:end-1")!!
        assertEquals(HistorySourceKind.MODEL_RAW, selection.sourceKind)
        assertEquals(listOf(reply.id, current.id), HistoryViews.sources(session(), selection).map { it.messageId })
        assertTrue(PromptReferenceLanguage.compile("type:pack.in:book.source:model").diagnostics.isNotEmpty())
        assertTrue(PromptReferenceLanguage.compile("type:builtin.in:history.name:messages.source:unknown").diagnostics.isNotEmpty())
    }
}
