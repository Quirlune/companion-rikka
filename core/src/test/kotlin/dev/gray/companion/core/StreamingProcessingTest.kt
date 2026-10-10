package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingProcessingTest {
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main",
        messages = listOf(ChatMessage(id = "turn", role = Role.USER, text = "hello")))

    private fun library(node: WorkflowNode) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(
            Workflow(id = "flow", nodes = listOf(node))))),
        profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")

    private abstract class TypedGateway : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                      onDelta: suspend (String) -> Unit): String =
            generateResponseAttempt(profile, messages) { if (it is ModelDelta.Text) onDelta(it.text) }.text

        abstract override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (ModelDelta) -> Unit): ModelResponse
    }

    @Test fun processedPreviewRecomputesThePrefixAndPreservesReasoning() = runBlocking {
        val node = WorkflowNode(id = "model", outputKey = "raw", visible = true,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)),
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "display", mode = RegexTransformMode.REPLACE,
                pattern = "hello", replacement = "hi"))
        val updates = mutableListOf<Pair<RunUpdate, Boolean>>()
        var returned = false
        val gateway = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                onDelta(ModelDelta.ReasoningStart())
                onDelta(ModelDelta.Reasoning("quiet"))
                onDelta(ModelDelta.Text("he"))
                onDelta(ModelDelta.Text("llo "))
                returned = true
                return ModelResponse("hello ")
            }
        }
        val result = WorkflowEngine().run(library(node), session(), "hello", gateway) { updates += it to returned }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val previews = updates.filter { !it.second && it.first.visible && it.first.replace }.map { it.first }
        assertEquals(listOf("he", "hi"), previews.map { it.text })
        assertTrue(previews.all { it.reasoningSnapshot?.text == "quiet" })
        assertEquals("hi", result.replies.single().text)
        assertEquals("hello ", result.replies.single().rawText)
        assertEquals("hi", updates.last { it.first.visible && it.first.replace }.first.text)
    }

    @Test fun incompleteExtractionClearsDraftAndNeverArchivesRejectedCandidate() = runBlocking {
        val node = WorkflowNode(id = "model", outputKey = "raw", visible = true,
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "display",
                pattern = "^<ok>(.*?)</ok>$", captureGroup = 1))
        var calls = 0
        val updates = mutableListOf<RunUpdate>()
        val gateway = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                calls++
                val response = if (calls == 1) {
                    onDelta(ModelDelta.Text("<ok>bad</ok>"))
                    onDelta(ModelDelta.Text(" tail"))
                    "<ok>bad</ok> tail"
                } else "<ok>accepted</ok>"
                return ModelResponse(response)
            }
        }
        val result = WorkflowEngine().run(library(node), session(), "hello", gateway) { updates += it }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(2, calls)
        val previews = updates.filter { it.visible && it.replace }
        assertTrue(previews.any { it.text == "bad" })
        assertTrue(previews.indexOfFirst { it.text == "bad" } < previews.indexOfFirst { it.text.isEmpty() })
        assertEquals("accepted", result.replies.single().text)
        assertEquals("<ok>accepted</ok>", result.replies.single().rawText)
        assertFalse(result.macroHistory.values.flatten().any { it.text.contains("bad") })
    }

    @Test fun execution15StillStreamsUnprocessedBody() = runBlocking {
        val node = WorkflowNode(id = "model", outputKey = "raw", visible = true,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_REPLACE,
                pattern = "hello", replacement = "hi")))
        val before = session()
        val frozen = library(node)
        val journal = RunRecovery(id = "old", sessionId = before.id, turnId = "turn", input = "hello",
            checkpoint = MessageOperations.captureCheckpoint(before), initialSession = before,
            frozenResources = frozen, executionVersion = 15,
            failures = listOf(RecoveryFailure("flow/model", "flow", "model", "Model",
                RecoveryFailureKind.ACTION, "paused", provisional = true)))
        val updates = mutableListOf<RunUpdate>()
        val gateway = object : TypedGateway() {
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
                onDelta(ModelDelta.Text("he"))
                onDelta(ModelDelta.Text("llo"))
                return ModelResponse("hello")
            }
        }
        val result = WorkflowEngine().resume(frozen, before.copy(pendingRun = journal), gateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/model")) { updates += it }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("hello", updates.filter { it.visible && !it.replace }.joinToString("") { it.text })
        assertEquals("hi", result.replies.single().text)
    }
}
