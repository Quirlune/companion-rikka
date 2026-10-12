package dev.gray.companion.ui

import dev.gray.companion.core.HistoryReferences
import dev.gray.companion.core.HistoryReadMode
import dev.gray.companion.core.HistoryRange
import dev.gray.companion.core.HistoryPoint
import dev.gray.companion.core.HistoryAnchor
import dev.gray.companion.core.HistorySelection
import dev.gray.companion.core.HistoryViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateHistoryRoutingTest {
    @Test
    fun textHistoryAliasesOpenTheRangeEditorInTextMode() {
        listOf("{{history.raw}}", "{{history.text}}").forEach { expression ->
            val selection = HistoryViews.selection(expression)
            assertNotNull(selection)
            assertTrue(isTextHistoryReference(expression))
            assertTrue(shouldOpenHistoryReferenceEditor(expression))
            assertEquals(HistoryReadMode.TEXT, selection?.readMode)
        }
        assertTrue(isTextHistoryReference("  {{ history.raw }}  "))
    }

    @Test
    fun editingTextHistoryKeepsTextModeAndItsSelectedRange() {
        val selection = requireNotNull(HistoryViews.selection("{{history.text}}")).copy(
            range = HistoryRange(HistoryPoint(HistoryAnchor.END, 3.0), HistoryPoint(HistoryAnchor.END, 1.0)),
            rangeExplicit = true,
        )
        val expression = HistoryReferences.expression(selection)
        val reparsed = requireNotNull(HistoryViews.selection(expression))

        assertTrue(expression.contains(".as:text"))
        assertEquals(HistoryReadMode.TEXT, reparsed.readMode)
        assertEquals(selection.range, reparsed.range)
        assertTrue(reparsed.rangeExplicit)
    }

    @Test
    fun structuredMessageHistoryKeepsItsEditorRoute() {
        val structured = HistoryReferences.expression(HistorySelection(compressed = true))

        assertNotNull(HistoryViews.selection(structured))
        assertFalse(isTextHistoryReference(structured))
        assertTrue(shouldOpenHistoryReferenceEditor(structured))
        assertEquals(HistoryReadMode.MESSAGES, HistoryViews.selection(structured)?.readMode)
        assertFalse(shouldOpenHistoryReferenceEditor("{{character.name}}"))
    }
}
