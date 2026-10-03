package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTemplateTest {
    @Test fun extractedTextIsEscapedBeforeItReachesHtml() {
        val tile = MacroTile(kind = MacroTileKind.HTML, htmlTemplate = "<p>{{text.place}}</p>",
            htmlBindings = listOf(MacroHtmlBinding("place", "place=(.*)", 1)))
        val result = MacroOutputContracts.read(tile, "place=庭院 <b> & \"茶\" '")
        assertEquals(null, result.error)
        assertTrue(result.html.orEmpty().contains("庭院 &lt;b&gt; &amp; &quot;茶&quot; &#39;"))
        assertFalse(result.html.orEmpty().contains("<b>"))
    }

    @Test fun numericBindingIsCanonicalForHtmlAndCssProgress() {
        val tile = MacroTile(kind = MacroTileKind.HTML,
            htmlTemplate = "<div style=\"width:{{number.level}}%\"></div>",
            htmlCss = ".bar{width:{{number.level}}%;}",
            htmlBindings = listOf(MacroHtmlBinding("level", "level:([0-9.]+)", 1, MacroHtmlValueKind.NUMBER)))
        val result = MacroOutputContracts.read(tile, "level:42.500")
        assertEquals(null, result.error)
        assertTrue(result.html.orEmpty().contains("width:42.5%"))
        assertFalse(result.html.orEmpty().contains("42.500"))
    }

    @Test fun missingAndInvalidGeneratedValuesRejectTheCandidate() {
        val tile = MacroTile(kind = MacroTileKind.HTML, htmlTemplate = "{{number.level}}",
            htmlBindings = listOf(MacroHtmlBinding("level", "level:(\\S+)", 1, MacroHtmlValueKind.NUMBER)))
        assertTrue(MacroOutputContracts.read(tile, "other:12").error.orEmpty().contains("未匹配"))
        assertTrue(MacroOutputContracts.read(tile, "level:NaN").error.orEmpty().contains("有限数值"))
    }

    @Test fun templateAndRegexProblemsAreConfigurationErrors() {
        val tile = MacroTile(kind = MacroTileKind.HTML, htmlTemplate = "<div style=\"{{text.place}}\"></div>",
            htmlCss = ".x{content:{{text.place}}}",
            htmlBindings = listOf(MacroHtmlBinding("place", "(", 1)))
        val issues = HtmlTemplate.validate(tile)
        assertTrue(issues.any { it.path.endsWith(".pattern") })
        assertTrue(issues.any { it.message.contains("元素正文") })
        assertTrue(issues.any { it.message.contains("CSS") })
        assertTrue(MacroOutputContracts.read(tile, "value").error.orEmpty().startsWith("HTML 模板配置无效"))
    }
}
