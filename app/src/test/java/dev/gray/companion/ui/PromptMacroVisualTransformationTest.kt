package dev.gray.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class PromptMacroVisualTransformationTest {
    @Test fun expandedTokensKeepOffsetsBoundedAndOrdered() {
        val raw = "前 {{input}} 中 {{user.name}} 后"
        val second = raw.indexOf("{{user")
        for (preview in listOf("短", "很长的内容".repeat(20), "")) {
            val result = PromptMacroVisualTransformation(mapOf(2 to preview, second to "身份"), null, Color.Blue, Color.Black).filter(AnnotatedString(raw))
            val forward = (0..raw.length).map(result.offsetMapping::originalToTransformed)
            val backward = (0..result.text.length).map(result.offsetMapping::transformedToOriginal)
            assertTrue(forward.zipWithNext().all { it.first <= it.second })
            assertTrue(backward.zipWithNext().all { it.first <= it.second })
            assertTrue(forward.all { it in 0..result.text.length })
            assertTrue(backward.all { it in 0..raw.length })
            assertEquals(result.text.length, forward.last())
            assertEquals(raw.length, backward.last())
            assertEquals(1, forward[1])
            assertEquals(raw.length - 1, backward[backward.lastIndex - 1])
        }
    }
    @Test fun activeTokenKeepsSourceAndIdentityOffsets() {
        val raw = "前 {{input}} 后"
        val result = PromptMacroVisualTransformation(mapOf(2 to "内容"), 2, Color.Blue, Color.Black).filter(AnnotatedString(raw))
        assertEquals(raw, result.text.text)
        (0..raw.length).forEach { assertEquals(it, result.offsetMapping.originalToTransformed(it)); assertEquals(it, result.offsetMapping.transformedToOriginal(it)) }
    }
    @Test fun previewTruncationDoesNotSplitEmoji() {
        assertEquals("a".repeat(239) + " …", compactMacroPreview("a".repeat(239) + "😀end"))
    }
}
