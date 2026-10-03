package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTriggersTest {
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("unexpected model call")
    }
    private fun renderNode(id: String, key: String = id, input: String = id, dependsOn: List<String> = emptyList()) =
        WorkflowNode(id = id, actionId = "text.render", outputKey = key, inputTemplate = input,
            dependsOn = dependsOn, includeHistory = false)
    private fun library(workflows: List<Workflow>, session: Session, groups: List<CallGroup> = emptyList(),
                        packages: List<PromptPackage> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "card", name = "Card")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows,
            boundGroupIds = groups.map { it.id })),
        sessions = listOf(session), groups = groups, packages = packages
    )

    @Test fun queuedAdmissionConsumesCurrentResultsAndMasksOldResultsForPeers() = runBlocking {
        val producer = Workflow(id = "producer", nodes = listOf(
            renderNode("draft", input = "5"), renderNode("score", input = "7", dependsOn = listOf("draft"))
        ))
        val stale = Workflow(id = "stale", condition = Condition(expression = "result.score == 'old'"),
            nodes = listOf(renderNode("staleReceipt", input = "must stay skipped")))
        val historical = Workflow(id = "historical", condition = Condition(expression = "macro('macro.score') == 'old'"),
            nodes = listOf(renderNode("historicalReceipt", input = "explicit history")))
        val consumer = Workflow(id = "consumer", queued = true, priority = 1,
            condition = Condition(expression = "number(macro('result.score')) >= 7"),
            recognition = Recognition(rules = listOf(RecognitionRule(template = "{{result.score}}",
                pattern = "([0-9]+)", captureGroup = 1, op = Compare.EQUALS, value = "7"))),
            nodes = listOf(renderNode("answer", input = "fresh {{result.score}}/{{result.draft}}").copy(visible = true)))
        val group = CallGroup(id = "group", name = "Group", workflows = listOf(consumer))
        val origin = ResultOrigin("main", "producer", "score")
        val session = Session(id = "session", characterId = "card", mainCallId = "main",
            messages = listOf(ChatMessage(id = "turn", text = "hello")), results = mapOf("score" to "old"),
            resultOrigins = mapOf("score" to origin),
            macroHistory = mapOf("result.score" to listOf(MacroVersion(1, "old", origin, turn = 0))))
        val result = WorkflowEngine().run(library(listOf(stale, historical, producer), session, listOf(group)), session, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("fresh 7/5", result.replies.single().text)
        assertFalse("staleReceipt" in result.results)
        assertEquals("explicit history", result.results["historicalReceipt"])
        assertEquals(ResultOrigin("group", "consumer", "answer"), result.resultOrigins["answer"])

        val inactiveProducer = producer.copy(condition = Condition(expression = "false"))
        val skipped = WorkflowEngine().run(library(listOf(stale, inactiveProducer), session, listOf(group)), session, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, skipped.status)
        assertTrue(skipped.replies.isEmpty())
        assertFalse("answer" in skipped.results)
        assertEquals("old", skipped.results["score"])
    }

    @Test fun equalLevelsShareWavesAndLegacyDecodesRemainParallel() {
        val first = Workflow(id = "first", queued = true, priority = -1,
            nodes = listOf(renderNode("a"), renderNode("tail", dependsOn = listOf("a"))))
        val peer = Workflow(id = "peer", queued = true, priority = -1, nodes = listOf(renderNode("b")))
        val ordinary = Workflow(id = "ordinary", priority = 99, nodes = listOf(renderNode("c")))
        val later = Workflow(id = "later", queued = true, priority = 1, nodes = listOf(renderNode("d")))
        val (plan, issue) = RunPlan.create(listOf(later, ordinary, peer, first), ActionRegistry.defaults().snapshot())
        assertNull(issue)
        assertEquals(listOf(setOf("b", "a"), setOf("tail"), setOf("c"), setOf("d")),
            plan!!.events.getValue("user.message").waves.map { wave -> wave.map { it.node.id }.toSet() })
        val legacy = Json.decodeFromString<Workflow>("""{"id":"old","nodes":[]}""")
        assertFalse(legacy.queued)
        assertEquals(0, legacy.priority)
        assertNull(Json.decodeFromString<PromptBlock>("""{"id":"oldBlock"}""").recognition)
    }

    @Test fun macroRegexDslAndPromptRecognitionShareSourceActivation() {
        val raw = PromptEntry(id = "raw", key = "raw", text = "score=17")
        val conditional = PromptEntry(id = "conditional", key = "conditional", text = "source active",
            condition = Condition(expression = "number(extract(macro('pack.calc.raw'), 'score=([0-9]+)', 1)) >= 17"))
        val pack = PromptPackage(id = "pack", namespace = "calc", name = "Calc", entries = listOf(conditional, raw))
        val session = Session(id = "session", characterId = "card", mainCallId = "main", packageIds = listOf("pack"),
            state = mapOf("allow" to "yes"))
        val recognition = Recognition(rules = listOf(RecognitionRule(template = "{{pack.calc.raw}}",
            pattern = "score=([0-9]+)", captureGroup = 1, op = Compare.GREATER, value = "16")))
        val node = renderNode("node", input = "").copy(blocks = listOf(
            PromptBlock(id = "matched", template = "{{pack.calc.conditional}}", recognition = recognition,
                condition = Condition(expression = "text(macro('state.allow')) == 'yes' && exists(macro('pack.calc.raw'))")),
            PromptBlock(id = "guarded", template = "legacy guard must hold", recognition = recognition,
                condition = Condition("allow", Compare.EQUALS, "no"))
        ))
        val library = library(listOf(Workflow(id = "flow", nodes = listOf(node))), session, packages = listOf(pack))
        val preview = PromptCompiler().preview(library, session, node, "")
        assertEquals(listOf("source active"), preview.messages.map { it.text })
        assertFalse(preview.issues.any { it.error })
        val resolver = MacroResolver(library, session)
        assertTrue(ConditionExpressions.matches("number(extract(macro('pack.calc.raw'), 'score=([0-9]+)', 1)) == 17",
            session.state, readMacro = resolver::read))
        assertTrue(ConditionExpressions.matches("", emptyMap()))
        assertNull(ConditionExpressions.validate(""))
        assertNotNull(ConditionExpressions.validate("extract(input, '([0-9]+)', 2)"))
        assertNotNull(ConditionExpressions.validate("macro('unknown')"))
        assertNotNull(ConditionExpressions.validate("fetch('side effect')"))
        val numeric = Recognition(rules = listOf(RecognitionRule(op = Compare.EQUALS, value = "1",
            valueType = RecognitionValueType.NUMBER)))
        assertTrue(RecognitionEvaluator.matches(numeric) { "1.0" })
        val text = numeric.copy(rules = numeric.rules.map { it.copy(valueType = RecognitionValueType.TEXT) })
        assertFalse(RecognitionEvaluator.matches(text) { "1.0" })
        val numericNotEqual = numeric.copy(rules = numeric.rules.map { it.copy(op = Compare.NOT_EQUALS) })
        assertFalse(RecognitionEvaluator.matches(numericNotEqual) { "NaN" })
        assertFalse(RecognitionEvaluator.matches(numericNotEqual) { "" })
        val numericExists = numeric.copy(rules = numeric.rules.map { it.copy(op = Compare.EXISTS, value = "") })
        assertTrue(RecognitionEvaluator.matches(numericExists) { "0" })
        assertFalse(RecognitionEvaluator.matches(numericExists) { "hello" })
        assertTrue(RecognitionEvaluator.validate(numericExists).isEmpty())
        assertTrue(RecognitionEvaluator.validate(numeric.copy(rules = numeric.rules.map {
            it.copy(value = "Infinity")
        })).any { it.error })

        val a = PromptEntry(id = "a", key = "a", text = "a", condition = Condition(expression = "exists(macro('pack.calc.b'))"))
        val b = PromptEntry(id = "b", key = "b", text = "b", condition = Condition(expression = "!exists(macro('pack.calc.a'))"))
        val cyclic = library.copy(packages = listOf(pack.copy(entries = listOf(a, b))))
        val cyclePreview = PromptCompiler().preview(cyclic, session,
            node.copy(blocks = listOf(PromptBlock(id = "cycle", template = "{{pack.calc.a}}/{{pack.calc.b}}"))), "")
        assertTrue(cyclePreview.issues.any { it.error && it.message.contains("循环引用") })
        assertEquals(listOf("/"), cyclePreview.messages.map { it.text })
    }
}
