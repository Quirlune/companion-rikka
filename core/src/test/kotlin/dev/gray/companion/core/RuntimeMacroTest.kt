package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMacroTest {
    private fun session(input: String = "hello") = Session(
        id = "session", characterId = "card", mainCallId = "main",
        messages = listOf(ChatMessage(id = "turn-1", role = Role.USER, text = input))
    )
    private fun library(workflows: List<Workflow>, session: Session = session(), groups: List<CallGroup> = emptyList(),
                        boundGroupIds: List<String> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "card", name = "Card")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows,
            primaryWorkflowId = workflows.first().id, boundGroupIds = boundGroupIds)),
        groups = groups, sessions = listOf(session)
    )
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("unexpected model call")
    }

    @Test fun serialMacroUpdatesKeepAddressVersionsAndLatestProjection() = runBlocking {
        val first = WorkflowNode(id = "first", actionId = "text.render", outputKey = "fact",
            inputTemplate = "one", includeHistory = false, resultCategory = "lore")
        val update = WorkflowNode(id = "update", actionId = "macro.write", dependsOn = listOf("first"),
            outputKey = "receipt", inputTemplate = "unused", includeHistory = false, resultCategory = "lore",
            arguments = mapOf("key" to "{{macro.fact}}", "value" to "two"))
        val activation = WorkflowNode(id = "activation", actionId = "macro.activate", dependsOn = listOf("update"),
            outputKey = "fact", inputTemplate = "activation", includeHistory = false, resultCategory = "lore",
            arguments = mapOf("key" to "result.fact", "enabled" to "true"))
        val answer = WorkflowNode(id = "answer", actionId = "text.render", dependsOn = listOf("activation"),
            outputKey = "reply", inputTemplate = "{{macro.fact@version-1}}/{{macro.fact@latest}}/{{macro.fact@turn-0}}",
            visible = true, includeHistory = false)
        val workflow = Workflow(id = "flow", nodes = listOf(first, update, activation, answer))
        val lib = library(listOf(workflow))
        val result = WorkflowEngine().run(lib, session(), "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("one/two/two", result.replies.single().text)
        assertEquals("two", result.results["fact"])
        assertEquals(listOf(1, 2, 3), result.macroHistory["result.fact"]?.map { it.version })
        assertEquals(listOf(1, 1, 1), result.macroHistory["result.fact"]?.map { it.turn })
        val finalSession = session().copy(results = result.results, resultOrigins = result.resultOrigins,
            macroHistory = result.macroHistory)
        assertEquals(2, MacroResolver(lib, finalSession).render("{{results.lore}}").lines().count { it == "two" })
    }

    @Test fun boundGroupRecognitionInjectsOnlyForMatchingTurn() = runBlocking {
        val modelNode = WorkflowNode(id = "model", outputKey = "reply", visible = true)
        val main = Workflow(id = "mainFlow", nodes = listOf(modelNode))
        val inject = WorkflowNode(id = "inject", actionId = "prompt.inject", outputKey = "injectionReceipt",
            inputTemplate = "remember {{input}}", includeHistory = false, arguments = mapOf("id" to "memo"))
        val persist = WorkflowNode(id = "persist", actionId = "prompt.inject", outputKey = "persistentReceipt",
            inputTemplate = "session note", includeHistory = false,
            arguments = mapOf("id" to "memo-session", "scope" to "SESSION"))
        val remove = WorkflowNode(id = "remove", actionId = "prompt.remove", outputKey = "removeReceipt",
            includeHistory = false, arguments = mapOf("id" to "memo-session"))
        val group = CallGroup(id = "group", name = "Group", workflows = listOf(
            Workflow(id = "injectFlow", trigger = "turn.prepare", nodes = listOf(inject),
                recognition = Recognition(rules = listOf(RecognitionRule(pattern = "hello")))),
            Workflow(id = "persistFlow", trigger = "turn.prepare", nodes = listOf(persist),
                recognition = Recognition(rules = listOf(RecognitionRule(pattern = "persist")))),
            Workflow(id = "removeFlow", trigger = "turn.prepare", nodes = listOf(remove),
                recognition = Recognition(rules = listOf(RecognitionRule(pattern = "clear"))))
        ))
        val profile = ModelProfile(id = "profile", model = "test")
        val seen = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                seen += messages
                return "ok"
            }
        }
        val hello = session()
        val base = library(listOf(main), hello, listOf(group), listOf("group"))
            .copy(profiles = listOf(profile), selectedProfileId = profile.id)
        val first = WorkflowEngine().run(base, hello, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, first.status)
        assertTrue(seen.single().any { it.text == "remember hello" })
        assertTrue(first.promptInjections.any { it.id == "memo" && it.scope == InjectionScope.TURN })

        val bye = hello.copy(messages = hello.messages + first.replies + ChatMessage(id = "turn-2", role = Role.USER, text = "bye"),
            state = first.state, results = first.results, resultOrigins = first.resultOrigins,
            macroHistory = first.macroHistory, promptInjections = first.promptInjections)
        val second = WorkflowEngine().run(base.copy(sessions = listOf(bye)), bye, "bye", gateway)
        assertEquals(RunStatus.SUCCESS, second.status)
        assertFalse(seen.last().any { it.text.contains("remember hello") })
        assertFalse(second.promptInjections.any { it.id == "memo" })

        fun next(previous: Session, result: RunResult, input: String, id: String) = previous.copy(
            messages = previous.messages + result.replies + ChatMessage(id = id, role = Role.USER, text = input),
            state = result.state, results = result.results, resultOrigins = result.resultOrigins,
            macroHistory = result.macroHistory, promptInjections = result.promptInjections
        )
        val persistTurn = next(bye, second, "persist", "turn-3")
        val third = WorkflowEngine().run(base.copy(sessions = listOf(persistTurn)), persistTurn, "persist", gateway)
        assertEquals(RunStatus.SUCCESS, third.status)
        assertTrue(seen.last().any { it.text == "session note" })
        val ordinaryTurn = next(persistTurn, third, "ordinary", "turn-4")
        val fourth = WorkflowEngine().run(base.copy(sessions = listOf(ordinaryTurn)), ordinaryTurn, "ordinary", gateway)
        assertEquals(RunStatus.SUCCESS, fourth.status)
        assertTrue(seen.last().any { it.text == "session note" })
        val clearTurn = next(ordinaryTurn, fourth, "clear", "turn-5")
        val fifth = WorkflowEngine().run(base.copy(sessions = listOf(clearTurn)), clearTurn, "clear", gateway)
        assertEquals(RunStatus.SUCCESS, fifth.status)
        assertFalse(seen.last().any { it.text == "session note" })
        assertFalse(fifth.promptInjections.any { it.id == "memo-session" })
    }

    @Test fun guaranteedParallelMacroCollisionFailsBeforeModelCall() = runBlocking {
        var calls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                calls++
                return "unwanted"
            }
        }
        val model = WorkflowNode(id = "model", outputKey = "reply", visible = true)
        val write = WorkflowNode(id = "write", actionId = "macro.write", outputKey = "receipt",
            arguments = mapOf("key" to "result.reply", "value" to "collision"))
        val profile = ModelProfile(id = "profile", model = "test")
        val s = session()
        val lib = library(listOf(Workflow(id = "flow", nodes = listOf(model, write))), s)
            .copy(profiles = listOf(profile), selectedProfileId = profile.id)
        val result = WorkflowEngine().run(lib, s, "hello", gateway)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, calls)
        assertTrue(result.traces.any { it.status == "error" })
    }

    @Test fun unloadedGroupPublicationsStayStoredButBecomeInvisible() = runBlocking {
        val oldOrigin = ResultOrigin("group", "groupFlow", "groupNode")
        val old = session().copy(results = mapOf("groupValue" to "secret"),
            resultOrigins = mapOf("groupValue" to oldOrigin),
            macroHistory = mapOf("result.groupValue" to listOf(MacroVersion(1, "secret", oldOrigin, turn = 1, turnId = "turn-1"))))
        val group = CallGroup(id = "group", name = "Group", workflows = listOf(Workflow(id = "groupFlow", nodes = listOf(
            WorkflowNode(id = "groupNode", actionId = "text.render", outputKey = "groupValue")
        ))))
        val node = WorkflowNode(id = "show", actionId = "text.render", outputKey = "reply", visible = true,
            inputTemplate = "{{macro.groupValue@latest}}/{{result.groupValue}}", includeHistory = false)
        val result = WorkflowEngine().run(library(listOf(Workflow(id = "mainFlow", nodes = listOf(node))), old,
            listOf(group)), old, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("/", result.replies.single().text)
        assertEquals("secret", result.results["groupValue"])
        assertEquals(old.macroHistory["result.groupValue"], result.macroHistory["result.groupValue"])
    }
}
