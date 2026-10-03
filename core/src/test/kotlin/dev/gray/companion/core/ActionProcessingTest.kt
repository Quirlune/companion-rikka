package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionProcessingTest {
    private fun session() = Session(id = "session", characterId = "character", mainCallId = "main",
        messages = listOf(ChatMessage(id = "user", role = Role.USER, text = "hello")))
    private fun library(workflows: List<Workflow>, session: Session = session(), profiles: List<ModelProfile> = emptyList(),
                        selectedProfileId: String? = null) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows,
            primaryWorkflowId = workflows.last().id)),
        sessions = listOf(session), profiles = profiles, selectedProfileId = selectedProfileId)
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                      onDelta: suspend (String) -> Unit): String = error("unexpected model call")
    }

    @Test fun macroTargetCopyNumericAndLegacyStateStayCurrent() = runBlocking {
        val nodes = listOf(
            WorkflowNode(id = "set", actionId = "macro.process", inputTemplate = "hp=7", outputKey = "set",
                macroProcess = MacroProcessConfig(key = "macro.score", steps = listOf(
                    TextProcessStep(TextProcessKind.REGEX_EXTRACT, pattern = "[0-9]+"),
                    TextProcessStep(TextProcessKind.INCREASE, amount = "1")))),
            WorkflowNode(id = "copy", actionId = "macro.process", dependsOn = listOf("set"), outputKey = "copy",
                macroProcess = MacroProcessConfig(key = "macro.saved", mode = MacroProcessMode.COPY,
                    source = "macro.score@version-1")),
            WorkflowNode(id = "state", actionId = "macro.process", dependsOn = listOf("copy"), outputKey = "stateResult",
                macroProcess = MacroProcessConfig(key = "state.health", mode = MacroProcessMode.UPDATE,
                    initial = "1", steps = listOf(TextProcessStep(TextProcessKind.INCREASE, amount = "1")))),
            WorkflowNode(id = "legacy", actionId = "state.increment", dependsOn = listOf("state"), outputKey = "legacyResult",
                arguments = mapOf("key" to "health", "amount" to "3")),
            WorkflowNode(id = "again", actionId = "macro.process", dependsOn = listOf("legacy"), outputKey = "again",
                macroProcess = MacroProcessConfig(key = "state.health", mode = MacroProcessMode.UPDATE,
                    steps = listOf(TextProcessStep(TextProcessKind.INCREASE, amount = "1"))))
        )
        val s = session()
        val result = WorkflowEngine().run(library(listOf(Workflow(id = "flow", nodes = nodes)), s), s, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("8", result.results["score"])
        assertEquals("8", result.results["saved"])
        assertEquals("6", result.state["health"])
        assertEquals("9007199254740994", ConditionExpressions.evaluate("add(number('9007199254740993'), 1)"))
        assertEquals("6", PromptCompiler().preview(library(listOf(Workflow(id = "flow", nodes = nodes)), s),
            s.copy(state = result.state, macroHistory = result.macroHistory),
            WorkflowNode(actionId = "text.render", inputTemplate = "{{state.health}}", includeHistory = false),
            "hello").messages.last().text)
    }

    @Test fun collisionPreventsModelEffectAndProcessingFailureRollsBack() = runBlocking {
        var calls = 0
        val profile = ModelProfile(id = "model", model = "demo")
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                          onDelta: suspend (String) -> Unit): String { calls++; return "raw" }
        }
        val conflict = listOf(
            WorkflowNode(id = "model", outputKey = "same"),
            WorkflowNode(id = "macro", actionId = "macro.process", outputKey = "other",
                macroProcess = MacroProcessConfig(key = "result.same"))
        )
        val s = session()
        val failed = WorkflowEngine().run(library(listOf(Workflow(id = "flow", nodes = conflict)), s,
            listOf(profile), profile.id), s, "hello", gateway)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(0, calls)
        val staged = listOf(
            WorkflowNode(id = "first", actionId = "state.set", outputKey = "first", arguments = mapOf("key" to "x", "value" to "3")),
            WorkflowNode(id = "bad", actionId = "macro.process", dependsOn = listOf("first"), outputKey = "bad",
                macroProcess = MacroProcessConfig(key = "state.x", mode = MacroProcessMode.SET,
                    steps = listOf(TextProcessStep(TextProcessKind.INCREASE, amount = "1"))), inputTemplate = "oops")
        )
        val rejected = WorkflowEngine().run(library(listOf(Workflow(id = "flow", nodes = staged)), s), s, "hello", noModel)
        assertEquals(RunStatus.FAILED, rejected.status)
        assertFalse(rejected.state.containsKey("x"))
    }

    @Test fun nodeProfileAndInputOutputProcessingAreAuthoritative() = runBlocking {
        val first = ModelProfile(id = "first", model = "default")
        val second = ModelProfile(id = "second", model = "override")
        var seenProfile: ModelProfile? = null
        var seenMessages: List<ChatMessage> = emptyList()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                          onDelta: suspend (String) -> Unit): String {
                seenProfile = profile; seenMessages = messages; onDelta("raw"); return "raw"
            }
        }
        val node = WorkflowNode(id = "model", visible = true, inputTemplate = " hello ",
            inputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM), TextProcessStep(TextProcessKind.APPEND, text = "!")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_REPLACE, pattern = "raw", replacement = "done")),
            generation = ModelCallOptions(profileId = second.id, temperature = 0.4f),
            blocks = listOf(PromptBlock(template = " abc ", processing = listOf(
                TextProcessStep(TextProcessKind.TRIM), TextProcessStep(TextProcessKind.APPEND, text = "!")))))
        val s = session()
        val lib = library(listOf(Workflow(id = "flow", nodes = listOf(node))), s, listOf(first, second), first.id)
        val updates = mutableListOf<RunUpdate>()
        val result = WorkflowEngine().run(lib, s, "hello", gateway) { updates += it }
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(second.id, seenProfile?.id)
        assertEquals(0.4f, seenProfile?.temperature)
        assertTrue(seenMessages.any { it.role == Role.USER && it.text == "hello!" })
        assertTrue(seenMessages.any { it.role == Role.SYSTEM && it.text == "abc!" })
        assertEquals("done", result.replies.single().text)
        assertEquals(listOf("done"), updates.filter { it.visible }.map { it.text })
        assertEquals(listOf(second.id), WorkflowRequirements.requiredProfiles(lib, s, ActionRegistry.defaults().descriptors()).map { it?.id })
    }

    @Test fun injectedContentUsesPipelineBeforeTargetCompilation() = runBlocking {
        val profile = ModelProfile(id = "model", model = "demo")
        var seen: List<ChatMessage> = emptyList()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                          onDelta: suspend (String) -> Unit): String { seen = messages; return "reply" }
        }
        val inject = WorkflowNode(id = "inject", actionId = "prompt.inject", outputKey = "injected",
            inputTemplate = " raw ", inputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM),
                TextProcessStep(TextProcessKind.APPEND, text = "!")), arguments = mapOf("id" to "owner-note"))
        val model = WorkflowNode(id = "model", visible = true, outputKey = "reply")
        val workflows = listOf(Workflow(id = "prepare", trigger = "turn.prepare", nodes = listOf(inject)),
            Workflow(id = "chat", nodes = listOf(model)))
        val s = session()
        val result = WorkflowEngine().run(library(workflows, s, listOf(profile), profile.id), s, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertTrue(seen.any { it.role == Role.SYSTEM && it.text == "raw!" })
        assertEquals("raw!", result.promptInjections.single().block.template)
    }
}
