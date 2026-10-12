package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class AutomaticOptionsEditingTest {
    private val node = WorkflowNode(id = "automatic-options", visible = true)
    private val flow = Workflow(id = "primary", nodes = listOf(node))
    private val main = MainCall(id = "main", workflows = listOf(flow), primaryWorkflowId = flow.id,
        history = HistoryConfig(prefix = "MAIN HISTORY WRAPPER"), specialDefault = true)
    private val profile = ModelProfile(id = "model", model = "test", temperature = 0.9f, maxTokens = 200)
    private val library = Library(mainCalls = listOf(main), profiles = listOf(profile), selectedProfileId = profile.id)

    private fun session() = Session(id = "chat", mainCallId = main.id,
        state = mapOf("theme" to "calm", "secret" to "MUST NOT EXPAND"),
        messages = (1..7).flatMap { index -> listOf(
            ChatMessage(id = "u$index", text = "user $index"),
            ChatMessage(id = "a$index", role = Role.ASSISTANT, text = "display $index", turnId = "u$index",
                workflowId = flow.id, nodeId = node.id, rawText = "hidden model $index",
                reasoningSnapshot = ReasoningSnapshot(text = "hidden thought"))) },
        promptInjections = listOf(PromptInjection("main-only", PromptBlock(template = "MAIN INJECTION"),
            ResultOrigin(main.id, flow.id, node.id), scope = InjectionScope.SESSION)))

    @Test fun haiku55RequiresVisibleUserRequestAfterStructuredHistory() {
        val haiku = profile.copy(model = "anthropic/claude-haiku-5.5")
        val config = AutomaticOptionsConfig(enabled = true,
            blocks = listOf(PromptBlock(id = "history", template = AutomaticOptions.HISTORY_MACRO)))
        val configured = library.copy(profiles = listOf(haiku), automaticOptions = config)
        val failure = runCatching { AutomaticOptions.request(configured, session()) }.exceptionOrNull()
        assertTrue(failure is AutomaticOptionsAssistantPrefill)
        assertTrue(failure!!.message.orEmpty().contains("历史之后"))
        assertEquals(config, configured.automaticOptions)
        val withRequest = AutomaticOptions.withGenerationRequest(config)
        assertEquals(config.blocks, withRequest.blocks!!.dropLast(1))
        assertEquals(withRequest.blocks!!.size, withRequest.blocks!!.map { it.id }.distinct().size)
        assertEquals(Role.USER, AutomaticOptions.request(configured.copy(automaticOptions = withRequest), session())!!
            .messages.last().role)
        val default = AutomaticOptions.editableConfig(AutomaticOptionsConfig(enabled = true, prompt = "温柔"))
        assertEquals(Role.USER, AutomaticOptions.request(configured.copy(automaticOptions = default), session())!!
            .messages.last().role)
    }

    @Test fun outputOnlyReadsInvalidateRequestWithoutChangingPromptAndKeepFrozenProcessing() {
        val config = AutomaticOptionsConfig(enabled = true, count = 1,
            blocks = listOf(PromptBlock(id = "fixed", template = "Return an option")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_REPLACE,
                pattern = "TOKEN", replacement = "{{state.theme}}")))
        val configured = library.copy(automaticOptions = config)
        val before = session()
        val after = before.copy(state = before.state + ("theme" to "bold"))
        val oldRequest = AutomaticOptions.request(configured, before)!!
        val newRequest = AutomaticOptions.request(configured, after)!!
        assertEquals(oldRequest.source.key, newRequest.source.key)
        assertEquals(oldRequest.messages.map { it.role to it.text }, newRequest.messages.map { it.role to it.text })
        assertEquals(oldRequest.profile, newRequest.profile)
        assertNotEquals(oldRequest.contextKey, newRequest.contextKey)
        assertEquals("<option>calm</option>", oldRequest.processOutput("<option>TOKEN</option>"))
        assertEquals("<option>bold</option>", newRequest.processOutput("<option>TOKEN</option>"))
        assertEquals(oldRequest.contextKey, AutomaticOptions.request(configured,
            before.copy(state = before.state + ("unrelated" to "changed")))!!.contextKey)
        val character = CharacterCard(id = "character", namespace = "hero", name = "Before")
        val resourceLibrary = configured.copy(characters = listOf(character), automaticOptions = config.copy(
            outputProcessing = listOf(config.outputProcessing.single().copy(replacement = "{{character.name}}"))))
        val resourceSession = before.copy(characterId = character.id)
        val oldResource = AutomaticOptions.request(resourceLibrary, resourceSession)!!
        val newResource = AutomaticOptions.request(resourceLibrary.copy(
            characters = listOf(character.copy(name = "After"))), resourceSession)!!
        assertNotEquals(oldResource.contextKey, newResource.contextKey)
        assertEquals("<option>Before</option>", oldResource.processOutput("<option>TOKEN</option>"))
        assertEquals("<option>After</option>", newResource.processOutput("<option>TOKEN</option>"))

        // The expression branch is chosen by the future model result, so all branches need identity.
        val expressionLibrary = configured.copy(automaticOptions = config.copy(outputProcessing = listOf(
            TextProcessStep(TextProcessKind.EXPRESSION,
                expression = "if(value == 'pick', concat('<option>', concat(macro('state.theme'), '</option>')), value)"))))
        val oldExpression = AutomaticOptions.request(expressionLibrary, before)!!
        assertEquals(oldExpression.contextKey, AutomaticOptions.request(expressionLibrary, before)!!.contextKey)
        val newExpression = AutomaticOptions.request(expressionLibrary, after)!!
        assertNotEquals(oldExpression.contextKey, newExpression.contextKey)
        assertEquals("<option>calm</option>", oldExpression.processOutput("pick"))
        assertEquals("<option>bold</option>", newExpression.processOutput("pick"))
        assertEquals(oldExpression.contextKey, AutomaticOptions.request(expressionLibrary.copy(
            sessions = listOf(before, before.copy(id = "unrelated-chat", state = mapOf("changed" to "yes")))), before)!!.contextKey)
    }

    @Test fun inheritedReasoningParsingInvalidatesRequestsButExplicitOverrideStaysStable() {
        val config = AutomaticOptionsConfig(enabled = true, count = 1,
            blocks = listOf(PromptBlock(id = "fixed", template = "Return an option")))
        val off = library.copy(automaticOptions = config,
            reasoningParsing = ReasoningParsingConfig(mode = ReasoningParsingMode.OFF))
        val tagged = off.copy(reasoningParsing = ReasoningParsingConfig(mode = ReasoningParsingMode.TAGGED))
        val session = session()
        val old = AutomaticOptions.request(off, session)!!
        val changed = AutomaticOptions.request(tagged, session)!!
        assertEquals(old.messages.map { it.role to it.text }, changed.messages.map { it.role to it.text })
        assertNotEquals(old.contextKey, changed.contextKey)
        val response = "<think>hidden</think><option>one</option>"
        assertEquals(response, old.processOutput(response))
        assertEquals("<option>one</option>", changed.processOutput(response))
        val explicit = config.copy(reasoningParsing = off.reasoningParsing)
        assertEquals(AutomaticOptions.request(off.copy(automaticOptions = explicit), session)!!.contextKey,
            AutomaticOptions.request(tagged.copy(automaticOptions = explicit), session)!!.contextKey)
        val legacy = config.copy(blocks = null, prompt = "gentle")
        assertNotEquals(AutomaticOptions.request(off.copy(automaticOptions = legacy), session)!!.contextKey,
            AutomaticOptions.request(tagged.copy(automaticOptions = legacy), session)!!.contextKey)
    }

    @Test fun editedBlocksAreOnlyPromptSourceAndContextChangesInvalidateRequestIdentity() {
        val config = AutomaticOptionsConfig(enabled = true, historyTurns = 1, blocks = listOf(
            PromptBlock(id = "custom", role = Role.USER, template = "chosen {{state.theme}}",
                processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = " edited"))),
            PromptBlock(id = "disabled", enabled = false, template = "HIDDEN")))
        val configured = library.copy(automaticOptions = config)
        val session = session()
        val request = AutomaticOptions.request(configured, session)!!
        assertEquals(listOf(Role.USER), request.messages.map { it.role })
        assertEquals(listOf("chosen calm edited"), request.messages.map { it.text })
        assertEquals(listOf("u7"), request.source.turns.map { it.user.id })
        val changed = AutomaticOptions.request(configured, session.copy(state = session.state + ("theme" to "bold")))!!
        assertEquals(request.source.key, changed.source.key)
        assertNotEquals(request.contextKey, changed.contextKey)
        assertNotEquals(request.source.key, AutomaticOptions.source(configured.copy(
            automaticOptions = config.copy(historyTurns = 7)), session)!!.key)
        assertTrue(runCatching { AutomaticOptions.request(configured.copy(
            automaticOptions = config.copy(blocks = emptyList())), session) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { AutomaticOptions.request(configured.copy(automaticOptions = config.copy(
            blocks = listOf(PromptBlock(template = "{{broken")))), session) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("MAIN INJECTION", session.promptInjections.single().block.template)
        assertEquals("MAIN HISTORY WRAPPER", library.mainCalls.single().history.prefix)
    }

    @Test fun editableDefaultsPreserveLiteralLegacyStyleAndUseNativeParametersAndProcessing() {
        val old = LibraryFormat.json.decodeFromString(AutomaticOptionsConfig.serializer(),
            """{"enabled":true,"modelProfileId":"model","count":2,"prompt":"温柔 {{state.secret}} {content}"}""")
        assertNull(old.blocks)
        assertEquals(5, old.historyTurns)
        val config = AutomaticOptions.editableConfig(old).copy(historyTurns = 7,
            generation = ModelCallOptions(profileId = profile.id, temperature = 0.3f, topP = 0.7f,
                maxTokens = 96, reasoning = ModelReasoning.LOW),
            reasoningParsing = ReasoningParsingConfig(mode = ReasoningParsingMode.TAGGED),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
                pattern = "(?s)<result>(.*?)</result>", group = 1, requireMatch = true)))
        val configured = library.copy(automaticOptions = config)
        val session = session()
        assertTrue(AutomaticOptions.validate(configured).toString(), AutomaticOptions.validate(configured).isEmpty())
        val request = AutomaticOptions.request(configured, session)!!
        assertEquals(16, request.messages.size)
        assertEquals(8, request.messages.count { it.role == Role.USER })
        assertEquals("display 7", request.messages.dropLast(1).last().text)
        assertEquals(Role.USER, request.messages.last().role)
        assertTrue(request.messages.first().text.contains("温柔 {{state.secret}} {content}"))
        assertTrue(request.messages.first().text.contains("恰好 2 个"))
        assertFalse(request.messages.any { "MUST NOT EXPAND" in it.text || "MAIN INJECTION" in it.text ||
            "MAIN HISTORY WRAPPER" in it.text || "hidden model" in it.text || "hidden thought" in it.text })
        assertEquals(0.3f, request.profile.temperature)
        assertEquals(0.7f, request.profile.topP)
        assertEquals(96, request.profile.maxTokens)
        assertEquals(ModelReasoning.LOW, request.profile.reasoning)
        assertEquals(listOf("one", "two"), AutomaticOptions.parse(request.processOutput(
            "<think>private thought</think><result><option>one</option><option>two</option></result>"), 2))
        assertEquals(config, LibraryFormat.json.decodeFromString(AutomaticOptionsConfig.serializer(),
            LibraryFormat.json.encodeToString(AutomaticOptionsConfig.serializer(), config)))
        assertEquals(0.9f, profile.temperature)
        assertEquals("MUST NOT EXPAND", session.state["secret"])
        val customInput = AutomaticOptions.request(configured.copy(automaticOptions = config.copy(
            inputTemplate = "seed", inputProcessing = listOf(TextProcessStep(TextProcessKind.APPEND,
                text = " {{state.automatic_options_count}}")))), session)!!
        assertEquals("seed 2\n请根据上面的对话和输出格式，给出下一句回复选项。", customInput.messages.last().text)
        assertTrue(AutomaticOptions.validate(configured.copy(automaticOptions = config.copy(
            generation = config.generation.copy(profileId = "missing")))).any { it.path.endsWith("generation.profileId") })
    }
}
