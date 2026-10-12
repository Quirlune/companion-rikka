package dev.gray.companion.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BodyAppearanceMarkupTest {
    @Test fun nativeColorsPreserveTextAndExcludeInlineCode() {
        val source = androidx.compose.ui.text.AnnotatedString.Builder("bold “quote” \"code\"").apply {
            addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), 0, 4)
            addStyle(androidx.compose.ui.text.SpanStyle(fontFeatureSettings = "'calt' 0"), 13, 19)
        }.toAnnotatedString()
        val colored = colorBodyText(source, me.rerere.rikkahub.data.datastore.DisplaySetting())
        assertEquals(source.text, colored.text)
        val added = colored.spanStyles.drop(source.spanStyles.size)
        assertTrue(added.any { it.start == 0 && it.end == 4 })
        assertTrue(added.any { it.start == 5 && it.end == 12 })
        assertTrue(added.none { it.end > 13 })
    }

    @Test fun dialogueQuotesCrossEmphasisButNeverCodeOrParagraphs() {
        val markdown = "“你好，**朋友**。” and \"hello *friend*\"\n\n`\"code\"`\n\n```text\n“code”\n```"
        val doc = Jsoup.parseBodyFragment(BodyAppearanceMarkup.render("<article>{{正文}}</article>", markdown))
        assertEquals("朋友", doc.selectFirst("strong .body-quote")?.text())
        assertEquals("friend", doc.selectFirst("em .body-quote")?.text())
        assertTrue(doc.select("code .body-quote, pre .body-quote").isEmpty())
        assertEquals(emptyList<IntRange>(), bodyQuoteRanges("don't 6\" tall and \\\"escaped\\\""))
        assertEquals(emptyList<IntRange>(), bodyQuoteRanges("“unfinished\nnext”"))
    }

    @Test fun bodyColorsAreValidatedAndTemplateCssRemainsLast() {
        val settings = me.rerere.rikkahub.data.datastore.DisplaySetting(
            companionBoldColor = "#123456", companionItalicColor = "#654321", companionQuoteColor = "red;}</style>"
        )
        val html = BodyAppearanceMarkup.render("<style>.custom strong{color:red}</style><div class='custom'>{{正文}}</div>", "**bold** *italic* “quote”", settings)
        assertTrue(html.contains("color:#123456"))
        assertTrue(html.contains("color:#654321"))
        assertTrue(html.contains("color:#A0712F"))
        assertTrue(html.indexOf(".custom strong") > html.indexOf("color:#123456"))
    }

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
        val encoded = script.substringAfter("incoming.innerHTML=").substringBefore(";\n")
        assertEquals(content, Json.parseToJsonElement(encoded).jsonPrimitive.content)
        assertFalse(encoded.contains('\u2028'))
    }

    @Test fun streamingChangesOnlyMarkdownInsideStableTemplate() {
        val template = "<style>.pink{padding:24px}</style><details><summary>正文</summary><article class='pink'>{{正文}}</article><span id='companion-markdown-content'></span></details>"
        val shell = BodyAppearanceMarkup.incrementalShell(template)
        val doc = Jsoup.parseBodyFragment(shell)
        assertTrue(shell.contains(".pink{padding:24px}"))
        assertEquals(1, doc.select("#${BodyAppearanceMarkup.incrementalContentId}").size)
        assertEquals(1, doc.select("details .pink").size)
        assertEquals("", doc.selectFirst("#${BodyAppearanceMarkup.incrementalContentId}")?.text())
        val update = BodyAppearanceMarkup.markdownFragment("**first**")
        assertEquals("first", Jsoup.parseBodyFragment(update).selectFirst("strong")?.text())
        assertFalse(update.contains(".pink"))
        val script = BodyAppearanceScripts.replaceFragment(update, BodyAppearanceMarkup.incrementalContentId)
        assertTrue(script.contains("getElementById(\"${BodyAppearanceMarkup.incrementalContentId}\")"))
        assertFalse(script.contains("getElementById(\"companion-content\")"))
        assertEquals(shell, BodyAppearanceMarkup.incrementalShell(template))
    }
}
