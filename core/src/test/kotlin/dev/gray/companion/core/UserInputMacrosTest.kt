package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserInputMacrosTest {
    private fun fixed(shortcut: String, text: String) = UserMacroDefinition(shortcut, UserMacroValue(text = text))

    @Test fun onlyRegisteredShortcutFollowedByAsciiSpaceArmsAndBackspaceDisarms() {
        val definition = fixed("/抽天气", "晴天")
        val definitions = mapOf(definition.shortcut to definition)
        val raw = "先 /抽天气 再 /未知 末尾/抽天气\t"
        val span = UserInputMacros.armedSpans(raw, definitions).single()
        assertEquals("/抽天气 ", raw.substring(span.start, span.endExclusive))
        assertTrue(UserInputMacros.armedSpans(raw.removeRange(span.endExclusive - 1, span.endExclusive), definitions).isEmpty())
        assertEquals("先 晴天 再 /未知 末尾/抽天气\t",
            UserInputMacros.expand(raw, Library(), Session(userMacros = definitions)).text)
    }

    @Test fun randomResolvesEveryCandidateOnceAndReusesOneDrawWithinMessage() {
        val definition = UserMacroDefinition("/抽", UserMacroValue(UserMacroValueKind.RANDOM_REFERENCE,
            references = listOf("{{state.a}}", "{{state.b}}")))
        val session = Session(state = mapOf("a" to "甲", "b" to "乙"),
            userMacros = mapOf(definition.shortcut to definition))
        var draws = 0
        val result = UserInputMacros.expand("/抽 /抽 ", Library(), session, chooseIndex = {
            draws++
            1
        })
        assertEquals("乙 乙 ", result.text)
        assertEquals(1, draws)

        val missing = session.copy(userMacros = mapOf("/抽" to definition.copy(value = definition.value.copy(
            references = listOf("{{state.a}}", "{{state.absent}}")))))
        draws = 0
        val rejected = UserInputMacros.expand("/抽 ", Library(), missing, chooseIndex = { draws++; 0 })
        assertFalse(rejected.succeeded)
        assertNull(rejected.text)
        assertEquals(0, draws)
    }

    @Test fun registrationIsAcceptedStateAndRestoresWithTheTurn() = runBlocking {
        val definition = fixed("/抽天气", "晴天")
        val node = WorkflowNode(id = "register", title = "登记天气", actionId = "user.macro.create",
            inputTemplate = "", includeHistory = false, outputKey = "unused", recordTrajectory = false,
            userMacro = definition)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = listOf(node))))
        val user = ChatMessage(id = "user", role = Role.USER, text = "hello")
        val session = Session(id = "chat", characterId = "character", mainCallId = main.id, messages = listOf(user))
        val library = Library(characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), sessions = listOf(session), selectedSessionId = session.id)
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = error("No model request expected")
        }
        val result = WorkflowEngine().run(library, session, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(definition, result.userMacros[definition.shortcut])
        assertFalse(result.results.containsKey("unused"))
        val accepted = MessageOperations.recordSuccessfulTurn(session, result).session!!
        assertEquals(definition, accepted.userMacros[definition.shortcut])
        assertTrue(MessageOperations.prepareRegeneration(accepted, user.id).session!!.userMacros.isEmpty())
    }
}
