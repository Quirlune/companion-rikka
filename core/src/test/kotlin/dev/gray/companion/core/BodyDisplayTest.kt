package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BodyDisplayTest {
    private fun before() = Session(id = "session", characterId = "card", mainCallId = "main",
        messages = listOf(ChatMessage(id = "turn", text = "hello")))
    private fun library(main: MainCall) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(main), profiles = listOf(ModelProfile(id = "profile", model = "offline")),
        selectedProfileId = "profile")
    // Exercise the archived tile interpretation explicitly; new turns use execution 19.
    private fun legacyBefore(library: Library) = before().let { session ->
        session.copy(executableWorld = CompletedWorlds.capture(library, session, "hello", executionVersion = 18))
    }
    private val gateway = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (String) -> Unit): String = "  draft  "
    }
    private fun model() = WorkflowNode(id = "model", outputKey = "draft", visible = true,
        recordTrajectory = false, outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
    private fun html() = MacroTile(id = "body", template = "{{result.final}}", kind = MacroTileKind.HTML,
        htmlTemplate = "<p>{{text.body}}</p>", htmlBindings = listOf(MacroHtmlBinding("body")),
        style = MacroTileStyle(fontSize = 18))

    @Test fun replyCompletedBuildsFrozenBodyOnSameReplyAndPreservesDraftReferences() = runBlocking {
        var captured: HistorySourceRef? = null
        var calls = 0
        val actions = ActionRegistry.defaults().register("finish") { context ->
            calls++
            val reply = context.session!!.messages.last { it.role == Role.ASSISTANT }
            assertEquals("draft", reply.text)
            captured = HistoryMemory.source(reply)
            ActionOutcome("final & complete")
        }
        val main = MainCall(id = "main", name = "Main", workflows = listOf(
            Workflow(id = "flow", nodes = listOf(model())),
            Workflow(id = "finish", trigger = RuntimeEvents.REPLY_COMPLETED,
                nodes = listOf(WorkflowNode(id = "finish", actionId = "finish", outputKey = "final", recordTrajectory = false)))
        ), tiles = listOf(html()), bodySourceTileId = "body", bodyTextTemplate = "{{result.final}}")
        val lib = library(main)
        var journal: RunRecovery? = null
        val result = WorkflowEngine(actions).run(lib, legacyBefore(lib), "hello", gateway) { update ->
            update.progress?.recovery?.takeIf { it.bodyDisplayReceipt != null }?.let { journal = it }
        }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val reply = result.replies.single()
        assertEquals("final & complete", reply.text)
        assertEquals(captured!!.messageId, reply.id)
        assertEquals("  draft  ", reply.rawText)
        assertTrue(reply.bodyDisplay!!.html!!.contains("final &amp; complete"))
        assertEquals(18, reply.bodyDisplay!!.style.fontSize)
        assertEquals("draft", HistoryMemory.resolve(captured!!, mapOf(reply.id to reply))!!.text)
        assertEquals("  draft  ", result.macroHistory.getValue("result.draft").single().rawText)
        assertFalse(result.macroHistory.containsKey("result.final"))
        assertEquals(1, calls)
        val encoded = LibraryFormat.json.encodeToString(RunRecovery.serializer(), journal!!)
        val restored = LibraryFormat.json.decodeFromString(RunRecovery.serializer(), encoded)
        assertEquals(reply, restored.bodyDisplayReceipt)
        assertEquals(reply, restored.replies.single())
        assertTrue(restored.failures.isEmpty())
        assertEquals("draft", restored.accepted.first { it.nodeKey == "flow/model" }.reply!!.text)
        val saved = MessageOperations.recordSuccessfulTurn(before(), result).session!!
        val changed = MessageOperations.editText(saved, reply.id, "manual").session!!
        assertNull(changed.messages.last().bodyDisplay)
        val restoredText = MessageOperations.selectTextRevision(changed, reply.id, reply.activeTextRevisionId!!).session!!
        assertEquals(reply.bodyDisplay, restoredText.messages.last().bodyDisplay)
        val deletedEdit = MessageOperations.deleteMessage(changed, reply.id).session!!
        assertEquals(reply.bodyDisplay, deletedEdit.messages.last().bodyDisplay)
    }

    @Test fun unavailableEmptyAndMissingPrimaryBodyFallBackWithoutLosingDraft() = runBlocking {
        val draft = Workflow(id = "flow", nodes = listOf(model()))
        val empty = MacroTile(id = "empty", template = "")
        val cases = listOf(
            MainCall(id = "main", name = "Main", workflows = listOf(draft), bodySourceTileId = "unloaded"),
            MainCall(id = "main", name = "Main", workflows = listOf(draft), tiles = listOf(empty), bodySourceTileId = "empty"),
            MainCall(id = "main", name = "Main", workflows = listOf(draft), tiles = listOf(html().copy(template = "fixed")),
                bodySourceTileId = "body", bodyTextTemplate = ""),
            MainCall(id = "main", name = "Main", workflows = listOf(draft.copy(nodes = listOf(model().copy(outputSlot = OutputSlot.AFTER_BODY)))) ,
                tiles = listOf(empty.copy(template = "replacement")), bodySourceTileId = "empty")
        )
        for (main in cases) {
            val result = WorkflowEngine().run(library(main), legacyBefore(library(main)), "hello", gateway)
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertEquals("draft", result.replies.single().text)
            assertNull(result.replies.single().bodyDisplay)
            assertTrue(result.traces.any { it.status == "display" })
        }
    }

    @Test fun invisibleAndHandlerModelRawAreRecordedButProgramOverwriteHasNoRaw() = runBlocking {
        val actions = ActionRegistry.defaults().register(ActionDescriptor("handler.model", "Handler model", usesModel = true)) {
            ActionOutcome("  handler raw  ", stateWrites = mapOf("side" to "effect"))
        }
        val pure = model().copy(visible = false, outputSlot = OutputSlot.BEFORE_BODY)
        val handler = WorkflowNode(id = "handler", actionId = "handler.model", outputKey = "handler",
            visible = false, recordTrajectory = false)
        val program = WorkflowNode(id = "program", actionId = "macro.process", outputKey = "program", recordTrajectory = true, inputTemplate = "program",
            macroProcess = MacroProcessConfig(key = "result.draft", mode = MacroProcessMode.SET))
        for (recording in listOf(false, true)) {
            val main = MainCall(id = "main", name = "Main", workflows = listOf(
                Workflow(id = "flow", nodes = listOf(pure, handler)),
                Workflow(id = "program", afterWorkflowId = "flow", nodes = listOf(program.copy(recordTrajectory = recording)))))
            val result = WorkflowEngine(actions).run(library(main), before(), "hello", gateway)
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertEquals(if (recording) listOf("  draft  ", null) else listOf("  draft  "),
                result.macroHistory.getValue("result.draft").map { it.rawText })
            assertEquals("program", result.results["draft"])
            assertEquals(!recording, result.currentOnlyMacros.containsKey("result.draft"))
            assertEquals(recording, result.macroHistory.containsKey("result.program"))
            assertEquals("  handler raw  ", result.macroHistory.getValue("result.handler").single().rawText)
            assertEquals("  handler raw  ", result.macroHistory.getValue("result.handler").single().text)
            assertEquals("effect", result.state["side"])
            assertFalse(result.macroHistory.containsKey("state.side"))
            assertTrue(result.currentOnlyMacros.containsKey("state.side"))
            assertTrue(result.replies.isEmpty())
        }
    }

    @Test fun executionThreeStillHonorsModelOverwriteAndDoesNotFreezeBody() = runBlocking {
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = listOf(model()))),
            tiles = listOf(MacroTile(id = "body", template = "replacement")), bodySourceTileId = "body")
        val lib = library(main)
        val before = before()
        val recovery = RunRecovery(id = "old", sessionId = before.id, turnId = "turn", input = "hello",
            checkpoint = MessageOperations.captureCheckpoint(before), initialSession = before, frozenResources = lib,
            executionVersion = 3, failures = listOf(RecoveryFailure("flow/model", "flow", "model", "Model",
                RecoveryFailureKind.ACTION, "paused", provisional = true)))
        val result = WorkflowEngine().resume(lib, before.copy(pendingRun = recovery), gateway, recovery,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/model"))
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertTrue(result.macroHistory.isEmpty())
        assertTrue(result.currentOnlyMacros.containsKey("result.draft"))
        assertEquals("draft", result.replies.single().text)
        assertNull(result.replies.single().bodyDisplay)
    }
}
