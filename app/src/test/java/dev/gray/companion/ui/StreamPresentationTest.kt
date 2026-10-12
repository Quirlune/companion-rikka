package dev.gray.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPresentationTest {
    private val identity = StreamPresentationIdentity("node", "message", 2)

    @Test
    fun bufferMarksOnlyStrictAppendsAndKeepsTheNewestSnapshot() {
        val buffer = StreamPresentationBuffer(identity, "hello")

        assertNull(buffer.publish("hello").appendStart)
        val appended = buffer.publish("hello world")
        assertEquals("hello world", appended.text)
        assertEquals("hello", appended.previousText)
        assertEquals(5, appended.appendStart)
        assertTrue(appended.isAppending)

        val rewritten = buffer.publish("hello, world")
        assertNull(rewritten.appendStart)
        assertFalse(rewritten.isAppending)
        assertEquals("hello, world", rewritten.text)
    }

    @Test
    fun flushPublishesLatestTextWithoutAnAnimationRange() {
        val buffer = StreamPresentationBuffer(identity, "a")
        buffer.publish("ab")

        val flushed = buffer.flush("abc")

        assertEquals("abc", flushed.text)
        assertEquals("abc", flushed.previousText)
        assertNull(flushed.appendStart)
        assertFalse(flushed.isStreaming)
    }

    @Test
    fun bufferDoesNotFadeHalfOfASplitSurrogatePair() {
        val high = "\uD83D"
        val completeEmoji = high + "\uDE00"
        val buffer = StreamPresentationBuffer(identity, high)

        val completed = buffer.publish(completeEmoji)

        assertEquals(completeEmoji, completed.text)
        assertNull(completed.appendStart)
    }

    @Test
    fun suffixRangeSurvivesTheDisplayedTextUpdateDuringAnimation() {
        val state = StreamFadeRangeState()
        val initial = StreamPresentationFrame(identity, "hello", "hello", null, 0, true)
        state.suffixStart(initial, "hello")
        state.recordDisplayedText("hello")

        val appended = StreamPresentationFrame(identity, "hello world", "hello", 5, 1, true)
        assertEquals(5, state.suffixStart(appended, "hello world"))

        // streamFadeText records the current text in SideEffect; alpha updates then recompose
        // with the same frame and text, so the cached suffix range must remain present.
        state.recordDisplayedText("hello world")
        assertEquals(5, state.suffixStart(appended, "hello world"))
    }

    @Test
    fun htmlFadeScriptRequiresExplicitStreamingAndStrictVisibleTextGrowth() {
        val enabled = BodyAppearanceScripts.replaceFragment("<p>hello</p>", animateGrowth = true)
        val disabled = BodyAppearanceScripts.replaceFragment("<p>hello</p>")

        assertTrue(enabled.contains("var shouldFade=true&&nextText.length>appendStart&&nextText.indexOf(previousText)===0&&!splitSurrogate"))
        assertTrue(enabled.contains("clearFades(root)"))
        assertTrue(enabled.contains("data-companion-stream-fade"))
        assertTrue(disabled.contains("var shouldFade=false&&nextText.length>appendStart"))
    }
}
