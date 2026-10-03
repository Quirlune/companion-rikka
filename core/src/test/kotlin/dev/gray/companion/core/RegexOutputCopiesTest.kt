package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RegexOutputCopiesTest {
    @Test fun rawOutputAndDerivedBodyHaveIndependentAddressesAndCompletionConsumer() = runBlocking {
        val raw = "<thinking>private planning</thinking><body>Hello.</body>"
        val node = WorkflowNode(id = "reply", outputKey = "raw_reply", resultCategory = "reply", visible = true,
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "clean_reply",
                pattern = "(?s)<body>(.*?)</body>", captureGroup = 1))
        val primary = Workflow(id = "primary", nodes = listOf(node))
        val consumer = Workflow(id = "consumer", afterWorkflowId = primary.id,
            nodes = listOf(WorkflowNode(id = "use_copy", actionId = "text.render",
                inputTemplate = "{{result.clean_reply}}", outputKey = "consumed", includeHistory = false)))
        val session = Session(id = "session", mainCallId = "main", characterId = "character",
            messages = listOf(ChatMessage(id = "turn", role = Role.USER, text = "hello")))
        val library = Library(characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(primary, consumer))),
            profiles = listOf(ModelProfile(id = "model", model = "test")), selectedProfileId = "model")
        var requests = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String { requests++; return raw }
        }
        val result = WorkflowEngine().run(library, session, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(raw, result.results["raw_reply"])
        assertEquals("Hello.", result.results["clean_reply"])
        assertEquals("Hello.", result.results["consumed"])
        assertEquals("Hello.", result.replies.single().text)
        assertEquals(1, result.macroHistory["result.clean_reply"]!!.size)
        val declarations = ResultReferences.declarations(library, session)
        assertEquals(1, declarations.count { it.key == "clean_reply" })
        assertEquals("$raw\nHello.", ResultReferences.category("reply", declarations, result.results, result.resultOrigins))
        assertEquals(1, requests)
    }

    @Test fun replacementAllowsAlreadyCleanTextAndChecksReplacementConfiguration() {
        val trimThinking = RegexOutputCopy(mode = RegexTransformMode.REPLACE,
            pattern = "(?s)<thinking>.*?</thinking>", replacement = "")
        assertEquals("hello", RegexOutputCopies.transform(trimThinking, "<thinking>x</thinking>hello"))
        assertEquals("hello", RegexOutputCopies.transform(trimThinking, "hello"))
        val invalid = trimThinking.copy(enabled = true, outputKey = "copy", replacement = "\u00241")
        assertFalse(RegexOutputCopies.validate(invalid, "test").isEmpty())
        assertTrue(runCatching { RegexOutputCopies.transform(invalid, "hello") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RegexOutputCopies.transform(RegexOutputCopy(pattern = "<body>(.*?)</body>", captureGroup = 1), "missing") }
            .exceptionOrNull() is OutputFormatException)
    }
}
