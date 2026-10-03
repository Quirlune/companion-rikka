package dev.gray.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroCompletionTest {
    @Test
    fun caretCompletionReplacesOnlyItsTokenAndClosingDelimiter() {
        val emptyToken = "before {{ after"
        val emptyCaret = emptyToken.indexOf("{{") + 2
        assertEquals("", macroCompletionToken(emptyToken, emptyCaret)?.query)
        assertEquals("before {{input}} after", replaceMacroCompletion(emptyToken, emptyCaret, emptyCaret, "{{input}}").text)

        val middle = "before {{pack.old.key}} after {{input}}"
        val middleCaret = middle.indexOf("old") + 1
        assertEquals("pack.o", macroCompletionToken(middle, middleCaret)?.query)
        val edited = replaceMacroCompletion(middle, middleCaret, middleCaret, "{{pack.new.key@turn-1#profile/name}}")
        assertEquals("before {{pack.new.key@turn-1#profile/name}} after {{input}}", edited.text)
        assertEquals(edited.text.indexOf(" after"), edited.caret)

        val halfClosed = "before {{res} after"
        val halfCaret = halfClosed.indexOf('}')
        assertEquals("before {{result.reply}} after", replaceMacroCompletion(halfClosed, halfCaret, halfCaret, "{{result.reply}}").text)

        val editedDsl = replaceMacroCompletion("left {{ right", 7, 7, "macro(\"pack.ns.key\")")
        assertEquals("left macro(\"pack.ns.key\") right", editedDsl.text)
        assertNull(macroCompletionToken("{{input}}", 9))
        assertNull(macroCompletionToken("{{bad token", 11))
        assertNull(macroCompletionToken(middle, middleCaret, middleCaret + 1))
        assertEquals("before {{input}} after", replaceMacroCompletion("before selected after", 7, 15, "{{input}}").text)
    }
}
