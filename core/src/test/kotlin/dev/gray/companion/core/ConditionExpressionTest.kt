package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionExpressionTest {
    @Test fun expressionOverridesSimpleConditionAndReadsSnapshotValues() {
        val condition = Condition(key = "route", op = Compare.EQUALS, value = "wrong",
            expression = "state.route == 'on' && exists(result.draft) && input != ''")
        assertTrue(conditionMatches(condition, mapOf("route" to "on"), mapOf("draft" to "ready"), "hello"))
        assertFalse(conditionMatches(condition, mapOf("route" to "off"), mapOf("draft" to "ready"), "hello"))
        assertTrue(ConditionExpressions.matches("(state.score >= 2 || false) && !exists(result.missing)", mapOf("score" to "3")))
    }

    @Test fun malformedExpressionIsReportedByValidatorAndDirectPreview() {
        val condition = Condition(expression = "state.score > && 2")
        val block = PromptBlock(id = "bad", template = "must not render", condition = condition, depth = -1)
        val node = WorkflowNode(id = "node", blocks = listOf(block))
        val workflow = Workflow(id = "flow", nodes = listOf(node), condition = condition)
        val library = Library(mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(workflow))))
        val validation = LibraryValidator.validate(library)
        assertTrue(validation.any { it.path == "workflow.flow.condition.expression" })
        assertTrue(validation.any { it.path == "workflow.flow.node.node.block.bad.depth" })
        val preview = PromptCompiler().preview(library, Session(), node, "hello")
        assertTrue(preview.issues.any { it.path == "node.node.block.bad.condition.expression" })
        assertFalse(preview.messages.any { it.text == "must not render" })
        assertEquals(null, ConditionExpressions.validate("exists(state.route) && state.score >= 2"))
    }
}
