package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class AutomaticOptionsTest {
    private val node = WorkflowNode(id = "body", visible = true)
    private val flow = Workflow(id = "primary", nodes = listOf(node))
    private val main = MainCall(id = "main", workflows = listOf(flow), primaryWorkflowId = flow.id, specialDefault = true)
    private val profile = ModelProfile(id = "model", model = "test")
    private val library = Library(mainCalls = listOf(main), profiles = listOf(profile), selectedProfileId = profile.id)

    private fun pair(index: Int): List<ChatMessage> = listOf(
        ChatMessage(id = "u$index", text = "user $index"),
        ChatMessage(id = "a$index", role = Role.ASSISTANT, text = "display $index", turnId = "u$index",
            workflowId = flow.id, nodeId = node.id, rawText = "hidden raw $index",
            reasoningSnapshot = ReasoningSnapshot(text = "hidden thought"))
    )

    @Test fun sourceUsesFiveSelectedMainTurnsAndIgnoresParallelAndInactiveReplies() {
        val messages = (1..6).flatMap(::pair)
        val session = Session(id = "chat", mainCallId = main.id, messages = messages,
            turnRecords = listOf(TurnRecord("u6", TurnCheckpoint(RuntimeSnapshot()),
                variants = listOf(TurnVariant(id = "inactive", replies = listOf(messages.last().copy(text = "inactive")))))))
        val source = AutomaticOptions.source(library, session)!!
        assertEquals(listOf("u2", "u3", "u4", "u5", "u6"), source.turns.map { it.user.id })
        assertEquals("display 6", source.turns.last().assistants.single().text)
        val extras = listOf(
            messages.last().copy(id = "parallel", workflowId = "other", text = "parallel body"),
            messages.last().copy(id = "before", outputSlot = OutputSlot.BEFORE_BODY),
            messages.last().copy(id = "deleted", deleted = true),
            messages.last().copy(id = "opening", authoredOpening = true),
            messages.last().copy(id = "orphan", turnId = "another user")
        )
        assertEquals(source.key, AutomaticOptions.source(library, session.copy(messages = messages + extras))!!.key)
        assertNotEquals(source.key, AutomaticOptions.source(library,
            session.copy(messages = messages.dropLast(1) + messages.last().copy(text = "edited", activeTextRevisionId = "revision")))!!.key)
        assertNull(AutomaticOptions.source(library, session.copy(messages = messages + ChatMessage(id = "unanswered", text = "new user"))))
        val legacy = Session(id = "legacy", mainCallId = main.id, messages = pair(1).map {
            it.copy(turnId = null, workflowId = null, nodeId = null)
        })
        assertEquals("display 1", AutomaticOptions.source(library, legacy)!!.turns.single().assistants.single().text)
    }

    @Test fun parserEnforcesBoundedExactPlainOptionContractAfterSharedThoughtParsing() {
        val thoughts = ReasoningParsingConfig(mode = ReasoningParsingMode.TAGGED)
        val body = ReasoningTextParser.parse("<think><option>thought</option></think><option> first </option>\n<option>second</option>", thoughts).text
        assertEquals(listOf("first", "second"), AutomaticOptions.parse(body, 2))
        fun rejects(text: String, count: Int = 2) {
            assertTrue(runCatching { AutomaticOptions.parse(text, count) }.exceptionOrNull() is IllegalArgumentException)
        }
        rejects("<option>one</option>")
        rejects("<option>one</option><option>two</option><option>three</option>")
        rejects("explanation<option>one</option><option>two</option>")
        rejects("<option> </option><option>two</option>")
        rejects("<option><option>nested</option></option><option>two</option>")
        rejects("<option>${"x".repeat(2001)}</option><option>two</option>")
        rejects("<option>${"中".repeat(22000)}</option>", 1)
        rejects("<option>one</option><option>two")
    }

    @Test fun requestUsesLiteralStyleLiveConfigAndCapturedDefaultWithoutPersistingAuxiliaryConfig() {
        val session = Session(id = "chat", mainCallId = main.id, messages = pair(1))
        assertNull(AutomaticOptions.request(library, session))
        val configured = library.copy(automaticOptions = AutomaticOptionsConfig(enabled = true, count = 2,
            prompt = "温柔。{{state.secret}} {content}"))
        val request = AutomaticOptions.request(configured, session)!!
        assertEquals(profile.id, request.profile.id)
        assertTrue(request.messages.first().text.contains("{{state.secret}} {content}"))
        assertTrue(request.messages.first().text.contains("恰好 2 个"))
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), request.messages.map { it.role })
        assertEquals("display 1", request.messages[request.messages.lastIndex - 1].text)
        assertNull(request.messages[request.messages.lastIndex - 1].rawText)
        assertNull(request.messages[request.messages.lastIndex - 1].reasoningSnapshot)
        assertNull(AutomaticOptions.request(configured.copy(automaticOptions = configured.automaticOptions.copy(prompt = "")), session))
        val legacyThoughts = configured.copy(reasoningParsing = ReasoningParsingConfig(mode = ReasoningParsingMode.TAGGED))
        val legacySession = session.copy(messages = pair(1).map { if (it.role == Role.ASSISTANT)
            it.copy(text = "<think>old thought</think>legacy body", rawText = null, reasoningSnapshot = null) else it })
        assertEquals("legacy body", AutomaticOptions.request(legacyThoughts, legacySession)!!.messages.dropLast(1).last().text)

        val world = CompletedWorlds.capture(configured, session, "user 1")
        assertEquals(AutomaticOptionsConfig(), world.library.automaticOptions)
        val replacement = profile.copy(id = "new-model")
        val changedMain = main.copy(workflows = listOf(flow.copy(modelProfileId = replacement.id)))
        val live = configured.copy(mainCalls = listOf(changedMain), profiles = listOf(replacement), selectedProfileId = replacement.id)
        val frozenSession = session.copy(executableWorld = world)
        assertEquals(profile.id, AutomaticOptions.request(live, frozenSession)!!.profile.id)
        assertEquals(replacement.id, AutomaticOptions.request(live.copy(automaticOptions = live.automaticOptions.copy(modelProfileId = replacement.id)), frozenSession)!!.profile.id)
        assertTrue(AutomaticOptions.validate(configured.copy(profiles = emptyList(), automaticOptions = configured.automaticOptions.copy(modelProfileId = profile.id))).any { it.path.endsWith("modelProfileId") })
    }
}
