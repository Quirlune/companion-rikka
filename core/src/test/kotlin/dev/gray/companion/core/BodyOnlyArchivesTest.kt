package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BodyOnlyArchivesTest {
    @Test fun incompleteProviderFinishSurvivesParsingCandidateAndAcceptedBody() = runBlocking {
        val node = WorkflowNode(id = "model", visible = true)
        val workflow = Workflow(id = "flow", nodes = listOf(node))
        val main = MainCall(id = "main", workflows = listOf(workflow))
        val session = Session(id = "chat", mainCallId = main.id)
        val library = Library(mainCalls = listOf(main))
        val actions = ActionRegistry.defaults().snapshot()
        val ref = PlannedNode(workflow, node, actions.getValue(node.actionId))
        val source = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit) = "partial body"
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                onDelta(ModelDelta.Text("partial body"))
                return ModelResponse("partial body", finishReason = "incomplete:max_output_tokens")
            }
        }
        val context = ActionContext(node, "question", emptyList(), emptyMap(), emptyMap(), emptyMap(),
            ModelProfile(model = "offline"), source, {}, session = session, library = library,
            workflowId = workflow.id, executionVersion = 26)
        val candidate = NodeCandidateExecutor(library, source, OutcomeValidator(library, session),
            Semaphore(1), ExecutionSemantics(26)).execute(ref, context, null, { _, _ -> }, {}, {})
        assertEquals("partial body", candidate.outcome.text)
        assertEquals("incomplete:max_output_tokens", candidate.modelFinishReason)
        val receipt = OutcomePublisher(library, main, actions, mapOf(workflow.id to main.id),
            setOf(main.id), 1, "user", 26).prepare(ref, candidate.outcome, session, emptySet(), false,
            rawModelText = candidate.rawModelText, modelFinishReason = candidate.modelFinishReason)
        val saved = LibraryFormat.json.decodeFromString(RecoveryReceipt.serializer(),
            LibraryFormat.json.encodeToString(RecoveryReceipt.serializer(), receipt))
        assertEquals("partial body", saved.reply!!.text)
        assertEquals("incomplete:max_output_tokens", saved.reply!!.modelFinishReason)
        assertTrue(modelResponseWasTruncated("length"))
        assertTrue(modelResponseWasTruncated("MAX_TOKENS"))
        assertFalse(modelResponseWasTruncated("stop"))
        assertNull(boundedModelFinishReason("private prompt with spaces"))
    }

    @Test fun completedReplyKeepsDisplayThoughtWithoutSendingOrPublishingItAsHistory() = runBlocking {
        val version = 21
        val node = WorkflowNode(id = "model", visible = true,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
        val workflow = Workflow(id = "flow", nodes = listOf(node))
        val main = MainCall(id = "main", workflows = listOf(workflow))
        val session = Session(id = "chat", mainCallId = main.id)
        val library = Library(mainCalls = listOf(main), reasoningParsing = parsing)
        val actions = ActionRegistry.defaults().snapshot()
        val ref = PlannedNode(workflow, node, actions.getValue(node.actionId))
        val context = ActionContext(node, "question", emptyList(), emptyMap(), emptyMap(), emptyMap(),
            ModelProfile(model = "offline"), gateway(), {}, session = session, library = library,
            workflowId = workflow.id, executionVersion = version)
        val candidate = NodeCandidateExecutor(library, gateway(), OutcomeValidator(library, session),
            Semaphore(1), ExecutionSemantics(version)).execute(ref, context, null, { _, _ -> }, {}, {})
        val receipt = OutcomePublisher(library, main, actions, mapOf(workflow.id to main.id), setOf(main.id), 1, "user", version)
            .prepare(ref, candidate.outcome, session, emptySet(), false, rawModelText = candidate.rawModelText,
                fullResponseText = candidate.fullResponseText, reasoningSnapshot = candidate.reasoningSnapshot)
        val saved = LibraryFormat.json.decodeFromString(RecoveryReceipt.serializer(),
            LibraryFormat.json.encodeToString(RecoveryReceipt.serializer(), receipt))
        val reply = requireNotNull(saved.reply)
        assertEquals("native-secret\n\ntag-secret", reply.reasoningSnapshot!!.text)
        assertNotNull(reply.reasoningSnapshot!!.finishedAt)
        assertNull(reply.reasoningSnapshot!!.nativeMessage)
        assertEquals("<ok>answer</ok>", reply.text)
        assertEquals(originalBody, reply.rawText)
        val macro = saved.publication.macroAppends.getValue("result.reply").single()
        assertNull(macro.reasoningSnapshot)
        assertNull(macro.fullResponseText)
        var sent: List<ChatMessage> = emptyList()
        val checkingGateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                sent = messages
                return "next body"
            }
        }
        CandidateModelGateway(checkingGateway, parsing, version).generate(ModelProfile(), listOf(reply)) {}
        assertEquals(reply.text, sent.single().text)
        assertNull(sent.single().reasoningSnapshot)
        assertNotNull(reply.reasoningSnapshot)
    }

    private val parsing = ReasoningParsingConfig(ReasoningParsingMode.TAGGED)
    private val fullText = "<think>tag-secret</think> <ok>answer</ok> "
    private val originalBody = " <ok>answer</ok> "
    private val native = ReasoningSnapshot("native-secret", 1, 2,
        JsonObject(mapOf("thought" to JsonPrimitive("native-secret"))))

    private fun gateway() = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (String) -> Unit): String = fullText

        override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
            fullText.chunked(3).forEach { onDelta(ModelDelta.Text(it)) }
            return ModelResponse(fullText, native)
        }
    }

    @Test fun gatewayKeepsBodyMarkupAndLiveThoughtButOnlyLegacyCapturesArchiveThought() = runBlocking {
        val oldMessage = ChatMessage(role = Role.ASSISTANT, text = "old body", rawText = "old body", reasoningSnapshot = native)
        for (executionVersion in listOf(8, 9)) {
            val live = mutableListOf<ReasoningSnapshot>()
            var requestMessages: List<ChatMessage>? = null
            val source = object : ModelGateway {
                override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                    onDelta: suspend (String) -> Unit): String = fullText

                override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                    onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                    requestMessages = messages
                    return gateway().generateResponseAttempt(profile, messages, onDelta)
                }
            }
            val capturing = CandidateModelGateway(source, parsing, executionVersion) { live += it }
            val response = capturing.generateResponseAttempt(ModelProfile(), listOf(oldMessage)) {}
            val capture = requireNotNull(capturing.singleCapture)
            val sentMessage = requireNotNull(requestMessages).single()
            assertEquals(originalBody, response.text)
            assertEquals(originalBody, capture.body)
            assertTrue(live.any { it.text.contains("tag-secret") && it.text.contains("native-secret") })
            if (executionVersion == 9) {
                assertNull(response.reasoning)
                assertNull(capture.fullText)
                assertNull(capture.reasoning)
                assertNull(sentMessage.reasoningSnapshot)
            } else {
                assertEquals(fullText, capture.fullText)
                assertEquals(native.nativeMessage, response.reasoning!!.nativeMessage)
                assertEquals(response.reasoning, capture.reasoning)
                assertEquals(native, sentMessage.reasoningSnapshot)
                assertEquals(native.nativeMessage, sentMessage.reasoningSnapshot!!.nativeMessage)
            }
            assertEquals("old body", sentMessage.text)
            assertEquals(native, oldMessage.reasoningSnapshot)
            assertEquals(native.nativeMessage, oldMessage.reasoningSnapshot!!.nativeMessage)
        }
    }

    @Test fun acceptedBodyAndRegexDisplayHaveNoThoughtInReceiptOrMacroArchive() = runBlocking {
        val node = WorkflowNode(id = "model", outputKey = "reply", visible = true,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)),
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "display", pattern = "<ok>(.*?)</ok>", captureGroup = 1))
        val workflow = Workflow(id = "flow", nodes = listOf(node))
        val main = MainCall(id = "main", workflows = listOf(workflow))
        val session = Session(id = "chat", mainCallId = main.id,
            messages = listOf(ChatMessage(id = "user", role = Role.USER, text = "question")))
        val library = Library(mainCalls = listOf(main), reasoningParsing = parsing)
        val actions = ActionRegistry.defaults().snapshot()
        val ref = PlannedNode(workflow, node, actions.getValue(node.actionId))
        val source = gateway()
        val context = ActionContext(node, "question", session.messages, emptyMap(), emptyMap(), emptyMap(),
            ModelProfile(model = "offline"), source, {}, session = session, library = library,
            workflowId = workflow.id, executionVersion = 9)
        val live = mutableListOf<ReasoningSnapshot>()
        val candidate = NodeCandidateExecutor(library, source, OutcomeValidator(library, session),
            Semaphore(1), ExecutionSemantics(9)).execute(ref, context, null, { _, _ -> }, { live += it }, {})
        assertEquals(originalBody, candidate.rawModelText)
        assertEquals("<ok>answer</ok>", candidate.outcome.text)
        assertNull(candidate.fullResponseText)
        assertNull(candidate.reasoningSnapshot)
        assertTrue(live.any { it.text.contains("tag-secret") })
        val receipt = OutcomePublisher(library, main, actions, mapOf(workflow.id to main.id), setOf(main.id), 1, "user", 9)
            .prepare(ref, candidate.outcome, session, emptySet(), false, rawModelText = candidate.rawModelText,
                fullResponseText = fullText, reasoningSnapshot = native)
        assertEquals("answer", receipt.reply!!.text)
        assertEquals(originalBody, receipt.reply!!.rawText)
        assertNull(receipt.reply!!.reasoningSnapshot)
        val model = receipt.publication.macroAppends.getValue("result.reply").single()
        assertEquals(originalBody, model.rawText)
        assertEquals("<ok>answer</ok>", model.text)
        assertNull(model.fullResponseText)
        assertNull(model.reasoningSnapshot)
        val archived = LibraryFormat.json.encodeToString(RecoveryReceipt.serializer(), receipt)
        assertFalse(archived.contains("tag-secret"))
        assertFalse(archived.contains("native-secret"))
    }

    @Test fun manualCandidateParsesOnlyNewModelBodyWithoutRewritingOldArchives() {
        val node = WorkflowNode(id = "model", outputKey = "reply", outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
        val workflow = Workflow(id = "flow", nodes = listOf(node))
        val main = MainCall(id = "main", workflows = listOf(workflow))
        val session = Session(id = "chat", mainCallId = main.id)
        val library = Library(mainCalls = listOf(main), reasoningParsing = parsing)
        val ref = PlannedNode(workflow, node, ActionRegistry.defaults().snapshot().getValue(node.actionId))
        fun context(version: Int) = ActionContext(node, "", emptyList(), emptyMap(), emptyMap(), emptyMap(),
            null, gateway(), {}, session = session, library = library, executionVersion = version)
        val validator = OutcomeValidator(library, session)
        assertEquals("<ok>answer</ok>", validator.validateManual(ref, context(9), fullText).text)
        assertEquals(fullText.trim(), validator.validateManual(ref, context(8), fullText).text)
        assertEquals("<scene>untagged body</scene>", validator.validateManual(ref, context(9), "<scene>untagged body</scene>").text)
        try {
            validator.validateManual(ref, context(9), "<think>only thought</think>")
            fail("Thought-only manual candidates cannot become accepted BODY")
        } catch (failure: NodeExecutionFailure) { assertEquals(RecoveryFailureKind.OUTPUT_FORMAT, failure.kind) }
        val oldMessage = ChatMessage(text = "body", rawText = "body", reasoningSnapshot = native)
        val oldMacro = MacroVersion(1, "body", ResultOrigin("main", "flow", "model"), rawText = "body",
            fullResponseText = fullText, reasoningSnapshot = native)
        assertSame(oldMessage, BodyOnlyArchives.message(oldMessage, 8))
        assertSame(oldMacro, BodyOnlyArchives.macro(oldMacro, 8))
        assertNull(BodyOnlyArchives.message(oldMessage, 9).reasoningSnapshot)
        assertNull(BodyOnlyArchives.macro(oldMacro, 9).fullResponseText)
        assertEquals(native, oldMessage.reasoningSnapshot)
        assertEquals(fullText, oldMacro.fullResponseText)
    }
}
