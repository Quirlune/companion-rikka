package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPlacementTest {
    @Test fun positionsUseFormedHistoryAndKeepSameAnchorOrder() {
        val previous = ChatMessage(id = "previous", role = Role.ASSISTANT, text = "previous")
        val current = ChatMessage(id = "current", role = Role.USER, text = "draft")
        val auxiliary = ChatMessage(role = Role.ASSISTANT, text = "side", outputSlot = OutputSlot.BEFORE_BODY)
        val session = Session(messages = listOf(previous, auxiliary, current))
        val node = WorkflowNode(blocks = listOf(
            PromptBlock(template = "before"),
            PromptBlock(template = "first", position = PromptPosition.IN_HISTORY, depth = 1),
            PromptBlock(template = "second", position = PromptPosition.IN_HISTORY, depth = 1),
            PromptBlock(template = "tail", position = PromptPosition.IN_HISTORY),
            PromptBlock(template = "after", position = PromptPosition.AFTER_HISTORY)
        ))
        val preview = PromptCompiler().preview(Library(), session, node, "sent", currentUserMessageId = current.id)
        assertEquals(listOf("before", "previous", "first", "second", "sent", "tail", "after"), preview.messages.map { it.text })
        assertFalse(preview.messages.any { it.text == "side" })
        assertEquals(1, preview.messages.count { it.text == "sent" })
    }

    @Test fun categoryReadsOnlyExactLoadedOriginAndDeclarationOrder() {
        val first = WorkflowNode(id = "first", actionId = "text.render", outputKey = "a", resultCategory = "notes")
        val second = WorkflowNode(id = "second", actionId = "text.render", outputKey = "b", resultCategory = "notes")
        val third = WorkflowNode(id = "third", actionId = "text.render", outputKey = "d", resultCategory = "notes")
        val hidden = WorkflowNode(id = "hidden", actionId = "text.render", outputKey = "c", resultCategory = "notes", enabled = false)
        val flow = Workflow(id = "flow", nodes = listOf(first, second, third, hidden))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(flow))
        val session = Session(mainCallId = main.id, results = mapOf("a" to "A", "b" to "B", "d" to "D", "c" to "C", "legacy" to "old"),
            resultOrigins = mapOf(
                "a" to ResultOrigin(main.id, flow.id, first.id, "notes"),
                "b" to ResultOrigin(main.id, flow.id, second.id, "other"),
                "d" to ResultOrigin(main.id, flow.id, third.id, "notes"),
                "c" to ResultOrigin(main.id, flow.id, hidden.id, "notes")
            ))
        val node = WorkflowNode(includeHistory = false, inputTemplate = "{{results.notes}}|{{result.legacy}}")
        val preview = PromptCompiler().preview(Library(mainCalls = listOf(main)), session, node, "")
        assertEquals("A\nD|old", preview.messages.last().text)
        assertTrue(preview.issues.none { it.error })
    }
}
