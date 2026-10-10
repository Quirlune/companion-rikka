package dev.gray.companion.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BodyAppearanceMarkupTest {
    @Test fun placeholderMustBeOneOrdinaryTextNode() {
        assertNull(BodyAppearanceMarkup.issue("<style>.body{color:red}</style><div class='body'>{{正文}}</div>"))
        listOf("<div title='{{正文}}'>body</div>", "<!--{{正文}}-->", "<style>{{正文}}</style>",
            "<script>{{正文}}</script>", "<textarea>{{正文}}</textarea>", "{{正文}}{{正文}}")
            .forEach { assertNotNull(it, BodyAppearanceMarkup.issue(it)) }
        assertNull(BodyAppearanceMarkup.issue(""))
        assertNull(BodyAppearanceMarkup.issue("{{正文}}"))
    }

    @Test fun markdownKeepsStructureAndLiteralUnknownMacros() {
        val html = BodyAppearanceMarkup.render("<article>{{正文}}</article>", "# Title\n\n**bold** and {{unknown}}\n\n- one\n- two\n\n| A | B |\n| --- | --- |\n| x | y |")
        val doc = Jsoup.parseBodyFragment(html)
        assertEquals("Title", doc.selectFirst("article h1")?.text())
        assertEquals("bold", doc.selectFirst("strong")?.text())
        assertEquals(2, doc.select("li").size)
        assertEquals(1, doc.select("table").size)
        assertTrue(doc.text().contains("{{unknown}}"))
    }

    @Test fun rawModelHtmlIsVisibleTextAndCannotEscapeTheTemplate() {
        val source = "<script>alert(1)</script>\n\n</style><img src=x onerror=alert(2)>\n\n`<b>code</b>`"
        val doc = Jsoup.parseBodyFragment(BodyAppearanceMarkup.render("<section>{{正文}}</section>", source))
        assertTrue(doc.select("script,img").isEmpty())
        assertTrue(doc.text().contains("<script>alert(1)</script>"))
        assertTrue(doc.text().contains("<img src=x onerror=alert(2)>"))
        assertEquals("<b>code</b>", doc.selectFirst("code")?.text())
    }

    @Test fun templateStripsActiveElementsAttributesAndPreservesLocalStyle() {
        val html = BodyAppearanceMarkup.render("<style>.body{color:red}</style><div class='body' onclick='evil()'>{{正文}}</div><script>evil()</script><iframe src='https://example.com'></iframe><img src='https://example.com/a'>", "hello")
        val doc = Jsoup.parseBodyFragment(html)
        assertTrue(doc.select("script,iframe,img,[onclick],[src],[href]").isEmpty())
        assertTrue(html.contains(".body{color:red}"))
        assertEquals("hello", doc.selectFirst(".body")?.text())
    }

    @Test fun incrementalScriptUsesAnEncodedStringLiteral() {
        val content = "</script>\";alert(1);//\n\\\u2028正文"
        val script = BodyAppearanceScripts.replaceFragment(content)
        val encoded = script.substringAfter("root.innerHTML=").substringBeforeLast(";})();")
        assertEquals(content, Json.parseToJsonElement(encoded).jsonPrimitive.content)
        assertFalse(encoded.contains('\u2028'))
    }
}
