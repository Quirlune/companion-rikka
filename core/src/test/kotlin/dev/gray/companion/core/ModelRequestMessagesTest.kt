package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test

class ModelRequestMessagesTest {
    @Test fun adjacentFinalRolesMergeWithoutChangingSourceFloorsOrOldExecution() {
        val roles = listOf(Role.SYSTEM, Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.ASSISTANT, Role.USER, Role.USER)
        val source = roles.mapIndexed { index, role -> ChatMessage(id = "floor-$index", role = role,
            text = " text-$index ", rawText = "raw-$index", speakerName = "speaker-$index") }
        val original = source.toList()
        val merged = ModelRequestMessages.prepare(source, 12)
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), merged.map { it.role })
        assertEquals(listOf(" text-0 \n text-1 ", " text-2 ", " text-3 \n text-4 ", " text-5 \n text-6 "),
            merged.map { it.text })
        assertEquals(original, source)
        assertSame(source, ModelRequestMessages.prepare(source, 11))
        assertSame(source[2], merged[1])
        assertNull(merged.first().rawText)
        assertEquals(merged, ModelRequestMessages.prepare(merged, 12))
        val blankBoundary = listOf(ChatMessage(role = Role.SYSTEM, text = "a"),
            ChatMessage(role = Role.USER, text = " \n\t"), ChatMessage(role = Role.SYSTEM, text = "b"),
            ChatMessage(role = Role.USER, text = "real boundary"), ChatMessage(role = Role.SYSTEM, text = "c"))
        assertEquals(listOf("a\nb", "real boundary", "c"), ModelRequestMessages.prepare(blankBoundary, 12).map { it.text })
        assertSame(blankBoundary, ModelRequestMessages.prepare(blankBoundary, 11))
        assertEquals(" \n\t", blankBoundary[1].text)
    }

    @Test fun compilerMergesTheWholeProjectionAndRejectsNewSpeakerInOldWorld() {
        val session = Session(id = "session", mainCallId = "main")
        val main = MainCall(id = "main", history = HistoryConfig(mergeMessages = false))
        val library = Library(mainCalls = listOf(main))
        val node = WorkflowNode(id = "node", inputTemplate = "", includeHistory = false,
            blocks = listOf(PromptBlock(template = "base"), PromptBlock(template = "extra"),
                PromptBlock(template = "answer", role = Role.ASSISTANT)))
        val preview = PromptCompiler().preview(library, session, node, "", executionVersion = 12)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(listOf("base\nextra", "answer"), preview.messages.map { it.text })
        assertEquals(listOf("base", "extra", "answer"),
            PromptCompiler().preview(library, session, node, "", executionVersion = 11).messages.map { it.text })
        val unsupported = node.copy(includeHistory = true, blocks = listOf(
            PromptBlock(template = "{{type:builtin.in:history.name:messages.speaker:user}}")))
        val old = PromptCompiler().preview(library, session.copy(messages = listOf(ChatMessage(text = "source"))),
            unsupported, "", executionVersion = 11)
        assertTrue(old.issues.any { it.error && it.message.contains("原说话人") })
        assertTrue(old.messages.isEmpty())
    }

    @Test fun finalCandidateBoundaryMergesAppendedCorrectionAndRetryAfterBodyCleaning() = runBlocking {
        val source = listOf(ChatMessage(id = "user", role = Role.USER, text = "question"),
            ChatMessage(id = "tail", role = Role.SYSTEM, text = "tail", reasoningSnapshot = ReasoningSnapshot("private")))
        val original = source.toList()
        val requests = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests += messages
                return if (requests.size == 1) "<think>unfinished" else "done"
            }
        }
        val session = Session(id = "session")
        val node = WorkflowNode(id = "model", includeHistory = false)
        val workflow = Workflow(id = "flow", nodes = listOf(node))
        val library = Library(reasoningParsing = ReasoningParsingConfig(ReasoningParsingMode.TAGGED))
        val ref = PlannedNode(workflow, node, ActionRegistry.defaults().snapshot().getValue("model.generate"))
        val invocation = ActionContext(node, "", source, emptyMap(), emptyMap(), emptyMap(),
            ModelProfile(id = "profile", model = "offline"), gateway, {}, session = session, library = library,
            workflowId = workflow.id, executionVersion = 12)
        val result = NodeCandidateExecutor(library, gateway, OutcomeValidator(library, session), Semaphore(1),
            ExecutionSemantics(12)).execute(ref, invocation, "manual correction", { _, _ -> }, {}, {})
        assertEquals("done", result.outcome.text)
        assertEquals(2, requests.size)
        assertEquals(listOf(Role.USER, Role.SYSTEM), requests.first().map { it.role })
        assertEquals("tail\nmanual correction", requests.first().last().text)
        assertTrue(requests.last().last().text.startsWith("tail\nmanual correction\n上次结果未通过格式校验："))
        assertTrue(requests.flatten().all { it.reasoningSnapshot == null })
        assertEquals(original, source)
        requests.clear()
        CandidateModelGateway(gateway, ReasoningParsingConfig(), 11).generateAttempt(
            invocation.profile!!, source + ChatMessage(role = Role.SYSTEM, text = "manual correction")) {}
        assertEquals(listOf(Role.USER, Role.SYSTEM, Role.SYSTEM), requests.single().map { it.role })
    }
    @Test fun blockMergeBoundariesSurviveFinalGatewayAndLegacyIgnoresFlags() = runBlocking {
        val main = MainCall(id = "main", history = HistoryConfig(mergeMessages = false))
        val library = Library(mainCalls = listOf(main))
        val session = Session(id = "session", mainCallId = main.id)
        val node = WorkflowNode(id = "node", inputTemplate = "", includeHistory = false, blocks = listOf(
            PromptBlock(template = "a", role = Role.ASSISTANT),
            PromptBlock(template = "b", role = Role.ASSISTANT, mergeAdjacentMessages = false),
            PromptBlock(template = "c", role = Role.ASSISTANT),
            PromptBlock(template = "d", role = Role.ASSISTANT)))
        val preview = PromptCompiler().preview(library, session, node, "")
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(listOf("a", "b", "c\nd"), preview.messages.map { it.text })
        val requests = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests += messages
                return "done"
            }
        }
        CandidateModelGateway(gateway, ReasoningParsingConfig(), 26).generateAttempt(
            ModelProfile(id = "profile", model = "offline"), preview.messages) {}
        assertEquals(preview.messages.map { it.text }, requests.single().map { it.text })
        assertEquals(listOf("a\nb\nc\nd"),
            PromptCompiler().preview(library, session, node, "", executionVersion = 25).messages.map { it.text })
    }

    @Test fun textHistoryKeepsOneOuterRoleWhileMessageHistoryKeepsSourceRoles() {
        val main = MainCall(id = "main", history = HistoryConfig(mergeMessages = false))
        val library = Library(mainCalls = listOf(main))
        val session = Session(id = "session", mainCallId = main.id, messages = listOf(
            ChatMessage(id = "a", role = Role.ASSISTANT, text = "answer"),
            ChatMessage(id = "u", role = Role.USER, text = "question")))
        val selection = HistorySelection(compressed = false, readMode = HistoryReadMode.TEXT)
        val node = WorkflowNode(id = "node", inputTemplate = "", includeHistory = false,
            blocks = listOf(PromptBlock(role = Role.SYSTEM, template = HistoryReferences.expression(selection))))
        val text = PromptCompiler().preview(library, session, node, "")
        assertTrue(text.issues.toString(), text.issues.none { it.error })
        assertEquals(listOf(Role.SYSTEM), text.messages.map { it.role })
        assertEquals("Assistant: answer\nUser: question", text.messages.single().text)
        val legacy = node.copy(blocks = listOf(PromptBlock(template = "{{history.raw}}")))
        assertEquals(text.messages.single().text, PromptCompiler().preview(library, session, legacy, "").messages.single().text)
        assertEquals("assistant: answer\nuser: question", PromptCompiler().preview(library, session, legacy, "", executionVersion = 25).messages.single().text)
        val structured = node.copy(blocks = listOf(PromptBlock(template = HistoryReferences.expression(
            selection.copy(readMode = HistoryReadMode.MESSAGES)), mergeAdjacentMessages = false)))
        val messages = PromptCompiler().preview(library, session, structured, "")
        assertTrue(messages.issues.toString(), messages.issues.none { it.error })
        assertEquals(listOf(Role.ASSISTANT, Role.USER), messages.messages.map { it.role })
        assertTrue(messages.messages.all { !it.mergeAdjacentMessages })
    }

}
