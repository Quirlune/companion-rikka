package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTextReferenceTest {
    @Test fun textModeRoundTripsWithRangeAndFormat() {
        val selection = HistorySelection(
            range = HistoryRange(HistoryPoint(HistoryAnchor.END, 3.0),
                HistoryPoint(HistoryAnchor.END, 1.0)),
            rangeExplicit = true,
            format = HistoryReferenceFormat(floorPrefix = "[{{floor}}] "),
            readMode = HistoryReadMode.TEXT)
        val expression = HistoryReferences.expression(selection)
        assertTrue(".from:end-3.through:end-1.as:text." in expression)
        assertEquals(selection, HistoryViews.selection(expression))
        assertTrue(HistoryReferences.isText(expression))
        assertFalse(HistoryReferences.isText(HistoryReferences.expression(selection.copy(readMode = HistoryReadMode.MESSAGES))))
    }

    @Test fun textModeIsHistoryOnlyAndVersioned() {
        val expression = "{{type:builtin.in:history.name:messages.from:end-2.through:end-1.as:text}}"
        assertNull(HistoryViews.selection(expression, executionVersion = 25))
        assertTrue(macroExpressionError(expression.removeSurrounding("{{", "}}"), 25)?.contains("不支持") == true)
        assertEquals(HistoryReadMode.TEXT, HistoryViews.selection(expression, executionVersion = 26)?.readMode)
        assertTrue(PromptReferenceLanguage.compile("{{type:pack.in:book.name:entry.as:text}}").diagnostics.isNotEmpty())
        assertTrue(PromptReferenceLanguage.compile(expression.replace("as:text", "as:raw")).diagnostics.isNotEmpty())
        assertTrue(HistoryReferences.isText("{{history.text}}"))
        assertTrue(HistoryReferences.isText("{{history.raw}}"))
    }

    @Test fun plainTextUsesOneLabelPerFloor() {
        val messages = listOf(ChatMessage(role = Role.ASSISTANT, text = "早上好"),
            ChatMessage(role = Role.USER, text = "天气怎样？"))
        assertEquals("Assistant: 早上好\nUser: 天气怎样？", HistoryReferences.plainText(messages))
    }

    @Test fun textProjectionKeepsSourceRolesDespiteGlobalMergeAndRoleRules() {
        val messages = listOf(ChatMessage(id = "assistant", role = Role.ASSISTANT, text = "早上好"),
            ChatMessage(id = "user", role = Role.USER, text = "天气怎样？"))
        val main = MainCall(id = "main", history = HistoryConfig(apiRole = Role.SYSTEM,
            mergeMessages = true, floorPrefix = "[{{floor}}] ",
            speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, apiRole = Role.SYSTEM)),
            floors = listOf(HistoryFloorRule("user", apiRole = Role.SYSTEM))))
        val session = Session(mainCallId = main.id, messages = messages)
        val library = Library(mainCalls = listOf(main), sessions = listOf(session))
        val text = HistoryViews.project(library, session, main,
            HistorySelection(readMode = HistoryReadMode.TEXT), executionVersion = 26)
        assertTrue(text.issues.toString(), text.issues.isEmpty())
        assertEquals(listOf(Role.ASSISTANT, Role.USER), text.messages.map { it.role })
        assertEquals("Assistant: [1] 早上好\nUser: [2] 天气怎样？", HistoryReferences.plainText(text.messages))
        val structured = HistoryViews.project(library, session, main, HistorySelection(), executionVersion = 26)
        assertEquals(listOf(Role.SYSTEM), structured.messages.map { it.role })
    }
}
