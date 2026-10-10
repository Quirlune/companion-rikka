package dev.gray.companion.ui

import org.junit.Assert.*
import org.junit.Test

class SlashMacroCompletionTest {
    @Test fun slashChoiceUsesBracesWithoutEatingProseOrUrl() {
        val text = "资料 //role 后文"
        val caret = text.indexOf(" 后文")
        assertTrue(macroCompletionToken(text, caret)!!.shortcut)
        assertEquals("资料 {{character.bio}} 后文", replaceMacroCompletion(text, caret, caret, "{{character.bio}}").text)
        assertNull(macroCompletionToken("https://host//path", 18))
        assertFalse(macroCompletionToken("{{character.bio}}", 8)!!.shortcut)
    }
}
