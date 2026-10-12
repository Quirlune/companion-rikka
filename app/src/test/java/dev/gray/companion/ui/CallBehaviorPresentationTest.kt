package dev.gray.companion.ui

import dev.gray.companion.core.*
import org.junit.Assert.*
import org.junit.Test

class CallBehaviorPresentationTest {
    private val actions = ActionRegistry.defaults().descriptors()

    @Test fun changingTypeKeepsPublishedIdentityAndExistingConfiguration() {
        val original = WorkflowNode(id = "stable-node", title = "我的行为", actionId = "model.generate",
            outputKey = "published_result", slug = "published_slug", visible = true,
            blocks = listOf(PromptBlock(title = "保留提示词")), arguments = mapOf("custom" to "keep"))
        assertEquals(original, changeCallBehaviorType(original, actions.first { it.id == original.actionId }, actions))
        val changed = changeCallBehaviorType(original, actions.first { it.id == "prompt.inject" }, actions)
        assertEquals(original.id, changed.id)
        assertEquals(original.title, changed.title)
        assertEquals(original.outputKey, changed.outputKey)
        assertEquals(original.slug, changed.slug)
        assertEquals(original.blocks, changed.blocks)
        assertEquals("keep", changed.arguments["custom"])
        assertEquals(original.id, changed.arguments["id"])
        assertFalse(changed.visible)
        assertEquals("", changed.inputTemplate)
    }

    @Test fun multipleNewInjectionsHaveIndependentIdentifiersAndResults() {
        val injection = editableCallBehaviorTypes(actions).first { it.id == "prompt.inject" }
        val first = newCallBehavior(injection, emptyList(), setOf("step"), actions)
        val second = newCallBehavior(injection, listOf(first), setOf("step"), actions)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.arguments["id"], second.arguments["id"])
        assertNotEquals(first.outputKey, second.outputKey)
        assertNotEquals(first.slug, second.slug)
        assertNotEquals("step", first.outputKey)
        assertFalse(first.visible)
        assertFalse(second.visible)
    }
}
