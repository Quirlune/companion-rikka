package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReasoningOpeningTest {
    private val tagged = ReasoningParsingConfig(ReasoningParsingMode.TAGGED)
    private fun before() = Session(id = "session", characterId = "card", mainCallId = "main",
        messages = listOf(ChatMessage(id = "turn", text = "hello")))
    private fun library(nodes: List<WorkflowNode>) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = nodes)))),
        profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile",
        reasoningParsing = tagged)
    private abstract class TypedGateway : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            generateResponseAttempt(profile, messages) { if (it is ModelDelta.Text) onDelta(it.text) }.text
        abstract override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (ModelDelta) -> Unit): ModelResponse
    }

    @Test fun delimitersAcrossChunksNativeTimingModesAndUnclosedReasoning() {
        val parser = ReasoningTextParser(tagged)
        parser.accept(ModelDelta.ReasoningStart(10))
        parser.accept(ModelDelta.Reasoning("native"))
        parser.accept(ModelDelta.ReasoningEnd(20))
        val streamed = "<think>secret</think>Hello <th".map { parser.accept(ModelDelta.Text(it.toString())) }.joinToString("")
        assertEquals("Hello ", streamed)
        val final = parser.finish()
        assertEquals("Hello <th", final.text)
        assertEquals("native\n\nsecret", final.reasoning!!.text)
        assertEquals(10, final.reasoning.startedAt)
        assertNotNull(final.reasoning.finishedAt)
        val native = ReasoningSnapshot("only native", 12, 15)
        val off = ReasoningTextParser.parse("<think>literal</think>body", ReasoningParsingConfig(), native)
        assertEquals("<think>literal</think>body", off.text)
        assertEquals(native, off.reasoning)
        val initial = ReasoningTextParser(ReasoningParsingConfig(ReasoningParsingMode.START_UNTIL_END))
        assertNotNull(initial.reasoning)
        assertEquals("", initial.reasoning!!.text)
        assertNull(initial.reasoning!!.finishedAt)
        assertNull(ReasoningTextParser(tagged).reasoning)
        assertEquals("", initial.accept(ModelDelta.Text("hidden</thi")))
        assertEquals("visible", initial.accept(ModelDelta.Text("nk>visible")))
        assertEquals("hidden", initial.finish().reasoning!!.text)
        for (text in listOf("<think>secret", "<think>secret</thi")) {
            val broken = ReasoningTextParser(tagged)
            assertEquals("", broken.accept(ModelDelta.Text(text)))
            try { broken.finish(); fail("Unclosed thought must fail") } catch (_: ReasoningFormatFailure) { }
        }
    }

    @Test fun canonicalArchivesAndReasoningSurviveAcceptedReceiptReplayWithoutReparsing() = runBlocking {
        val good = WorkflowNode(id = "good", outputKey = "good", visible = true,
            blocks = listOf(PromptBlock(template = "good")), outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
        val bad = WorkflowNode(id = "bad", outputKey = "bad", dependsOn = listOf("good"),
            blocks = listOf(PromptBlock(template = "bad")))
        val lib = library(listOf(good, bad))
        var goodCalls = 0
        var repaired = false
        val gateway = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                val goodRequest = messages.any { it.text == "good" }
                if (goodRequest) goodCalls++
                val text = if (goodRequest) "<think>private</think>  body  " else if (repaired) "done" else "<think>unfinished"
                text.chunked(3).forEach { onDelta(ModelDelta.Text(it)) }
                return ModelResponse(text, if (goodRequest) ReasoningSnapshot("native", 3, 5) else null)
            }
        }
        val result = WorkflowEngine().run(lib, before(), "hello", gateway)
        assertEquals(result.traces.toString(), RunStatus.PAUSED, result.status)
        val reply = result.replies.single()
        assertEquals("body", reply.text)
        assertEquals("  body  ", reply.rawText)
        assertEquals("native\n\nprivate", reply.reasoningSnapshot!!.text)
        val version = result.macroHistory.getValue("result.good").single()
        assertEquals("<think>private</think>  body  ", version.fullResponseText)
        assertEquals(reply.reasoningSnapshot, version.reasoningSnapshot)
        assertEquals("  body  ", version.rawText)
        val current = MessageOperations.recordPartialTurn(before(), result).session!!
        for (kind in HistorySourceKind.entries) {
            val visible = HistoryViews.originalMessages(current, HistorySelection(sourceKind = kind)).last().text
            assertFalse(visible.contains("private"))
            assertFalse(visible.contains("native"))
        }
        val journal = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), result.recovery!!))
        assertEquals(6, journal.executionVersion)
        assertTrue(journal.frozenResources.configurationEdits.isEmpty())
        repaired = true
        val resumed = WorkflowEngine().resume(lib.copy(reasoningParsing = ReasoningParsingConfig()), current,
            gateway, journal, RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/bad"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(1, goodCalls)
        assertEquals(reply, resumed.replies.single())
        assertEquals(version, resumed.macroHistory.getValue("result.good").single())
    }

    @Test fun regexStreamsOnlyInitialCleanCandidateThenClearsAndCorrectsWhileLegacyRemainsFrozen() = runBlocking {
        val node = WorkflowNode(id = "model", outputKey = "raw", visible = true,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)),
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "display", pattern = "<ok>(.*?)</ok>", captureGroup = 1))
        val lib = library(listOf(node))
        val updates = mutableListOf<RunUpdate>()
        var calls = 0
        val gateway = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                val text = if (++calls == 1) "<think>first secret</think>bad" else "<think>retry secret</think> <ok>accepted</ok> "
                text.chunked(2).forEach { onDelta(ModelDelta.Text(it)) }
                return ModelResponse(text)
            }
        }
        val result = WorkflowEngine().run(lib, before(), "hello", gateway) { updates += it }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(2, calls)
        assertEquals("bad", updates.filter { !it.replace }.joinToString("") { it.text })
        val clear = updates.indexOfFirst { it.replace && it.text.isEmpty() && it.reasoningSnapshot == null }
        assertTrue(clear >= 0)
        assertTrue(updates.take(clear).any { it.reasoningSnapshot?.text?.contains("first secret") == true })
        assertFalse(updates.drop(clear + 1).filter { !it.replace }.any { it.reasoningSnapshot != null || it.text.isNotEmpty() })
        val accepted = updates.last { it.replace }
        assertEquals("accepted", accepted.text)
        assertEquals("retry secret", accepted.reasoningSnapshot!!.text)
        assertEquals(" <ok>accepted</ok> ", result.replies.single().rawText)
        assertEquals("accepted", result.replies.single().text)

        val old = RunRecovery(id = "old", sessionId = before().id, turnId = "turn", input = "hello",
            checkpoint = MessageOperations.captureCheckpoint(before()), initialSession = before(), frozenResources = lib,
            executionVersion = 5, failures = listOf(RecoveryFailure("flow/model", "flow", "model", "Model",
                RecoveryFailureKind.ACTION, "paused", provisional = true)))
        val oldUpdates = mutableListOf<RunUpdate>()
        val legacy = WorkflowEngine().resume(lib, before().copy(pendingRun = old), gateway, old,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/model")) { oldUpdates += it }
        assertEquals(legacy.traces.toString(), RunStatus.SUCCESS, legacy.status)
        assertEquals("<think>retry secret</think> <ok>accepted</ok> ", legacy.replies.single().rawText)
        assertNull(legacy.replies.single().reasoningSnapshot)
        assertNull(legacy.macroHistory.getValue("result.raw").single().fullResponseText)
        assertFalse(oldUpdates.any { it.text.isNotEmpty() || it.reasoningSnapshot != null })

        val initialNode = node.copy(outputProcessing = emptyList(), regexOutputCopy = RegexOutputCopy(),
            reasoningParsing = ReasoningParsingConfig(ReasoningParsingMode.START_UNTIL_END))
        val initialUpdates = mutableListOf<RunUpdate>()
        var thoughtCalls = 0
        val thoughtsOnly = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                if (++thoughtCalls == 1) {
                    // The native animation is active before the first provider event.
                    assertTrue(initialUpdates.any { it.reasoningSnapshot?.let { thought ->
                        thought.text.isEmpty() && thought.finishedAt == null } == true })
                }
                val text = "only thought</think> "
                onDelta(ModelDelta.Text(text))
                return ModelResponse(text)
            }
        }
        val initialBefore = before()
        val emptyBody = WorkflowEngine().run(library(listOf(initialNode)), initialBefore, "hello", thoughtsOnly) { initialUpdates += it }
        assertEquals(3, thoughtCalls)
        assertEquals(RunStatus.PAUSED, emptyBody.status)
        assertEquals(RecoveryFailureKind.OUTPUT_FORMAT, emptyBody.recovery!!.failures.single().kind)
        assertTrue(emptyBody.replies.isEmpty())
        assertTrue(emptyBody.macroHistory.isEmpty())
        assertEquals(initialBefore.messages, emptyBody.recovery.initialSession.messages)
        assertTrue(initialUpdates.any { it.replace && it.text.isEmpty() && it.reasoningSnapshot == null })
    }

    @Test fun authoredOpeningsFreezeRevisionsAndBothHistorySourcesWithoutInventedRawOrRetroactiveEdits() {
        val card = CharacterCard(id = "card", namespace = "hero", name = "Hero", greeting = "Hello {{user.name}}",
            alternateGreetings = listOf("Welcome {{user.name}}", "Good evening {{user.name}}"))
        val lib = Library(characters = listOf(card), mainCalls = listOf(MainCall(id = "main", name = "Main")),
            personas = listOf(UserPersona(id = "persona", name = "Alice")), selectedPersonaId = "persona")
        val started = lib.startNewConversation().sessions.single()
        val opening = started.messages.single()
        assertEquals("Hello Alice", opening.text)
        assertTrue(opening.authoredOpening)
        assertNull(opening.rawText)
        assertEquals(2, opening.textRevisions.size)
        val initialRaw = HistoryMemory.source(opening, HistorySourceKind.MODEL_RAW)
        val selected = MessageOperations.selectOpeningRevision(started, opening.id, opening.textRevisions.first().id).session!!
        val active = selected.messages.single()
        assertEquals("Welcome Alice", active.text)
        assertEquals(opening.id, active.id)
        assertEquals("Hello Alice", HistoryMemory.resolve(initialRaw, HistoryMemory.sourceIndex(selected))!!.text)
        for (kind in HistorySourceKind.entries) {
            assertEquals("Welcome Alice", HistoryViews.originalMessages(selected, HistorySelection(sourceKind = kind)).single().text)
        }
        val changedResources = lib.copy(characters = listOf(card.copy(greeting = "changed", alternateGreetings = listOf("changed"))),
            personas = listOf(UserPersona(id = "persona", name = "Bob")))
        assertEquals("Welcome Alice", HistoryViews.originalMessages(selected, HistorySelection(sourceKind = HistorySourceKind.MODEL_RAW)).single().text)
        assertEquals("changed", changedResources.startNewConversation().sessions.single().messages.single().text)
        val begun = selected.copy(messages = selected.messages + ChatMessage(text = "start"))
        assertFalse(MessageOperations.canSelectOpening(begun, active.id))
        assertFalse(MessageOperations.selectTextRevision(begun, active.id, opening.id).succeeded)
        assertFalse(MessageOperations.editText(begun, active.id, "retroactive").succeeded)
        assertFalse(MessageOperations.deleteMessage(begun, active.id).succeeded)
        assertFalse(MessageOperations.capabilities(begun, active.id).canEditText)
        assertTrue(MessageOperations.editText(begun, begun.messages.last().id, "user edit").succeeded)
        val pending = selected.copy(pendingRun = RunRecovery(id = "pending", sessionId = selected.id, turnId = "", input = "",
            checkpoint = MessageOperations.captureCheckpoint(selected), initialSession = selected, frozenResources = lib))
        assertFalse(MessageOperations.canSelectOpening(pending, active.id))
        assertFalse(MessageOperations.deleteMessage(pending, active.id).succeeded)
    }
}
