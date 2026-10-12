package dev.gray.companion.ui

import dev.gray.companion.core.BodyAppearanceTemplates
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** Local display template only: never enters the project macro resolver. */
internal object BodyAppearanceMarkup {
    private const val slot = "{{正文}}"
    const val incrementalContentId = "companion-markdown-content"
    private val allowed = Safelist.none().addTags(
        "div", "span", "section", "article", "header", "footer", "main", "aside", "p", "br", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u", "s", "del", "small",
        "blockquote", "pre", "code", "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody",
        "tfoot", "tr", "th", "td", "caption", "a", "style", "details", "summary", "sup", "sub"
    ).addAttributes(":all", "class", "id", "style", "title", "lang", "dir")
        .addAttributes("td", "colspan", "rowspan").addAttributes("th", "colspan", "rowspan")
        .addAttributes("ol", "start").addAttributes("li", "value")
    private val forbiddenParents = setOf("style", "script", "textarea", "title", "xmp", "iframe", "noembed", "noscript", "plaintext")

    fun issue(template: String): String? {
        BodyAppearanceTemplates.issue(template)?.let { return it }
        if (!BodyAppearanceTemplates.isCustom(template)) return null
        val doc = Jsoup.parseBodyFragment(template)
        val nodes = textNodes(doc.body()).filter { slot in it.wholeText }
        if (nodes.size != 1 || generateSequence(nodes.single().parent()) { it.parent() }.any { it is Element && it.normalName() in forbiddenParents })
            return "{{正文}} 必须放在 HTML 的普通文字位置，不能放入属性、样式或注释。"
        val clean = Cleaner(allowed).clean(doc)
        if (textNodes(clean.body()).count { slot in it.wholeText } != 1)
            return "{{正文}} 所在的 HTML 元素不受支持。"
        return null
    }

    fun render(template: String, markdown: String, colors: DisplaySetting = DisplaySetting()): String =
        renderFrame(template, markdownFragment(markdown), colors, incremental = false)

    /** The template and its CSS stay mounted while a response streams. */
    fun incrementalShell(template: String, colors: DisplaySetting = DisplaySetting()): String =
        renderFrame(template, "", colors, incremental = true)

    fun markdownFragment(markdown: String): String {
        val content = Jsoup.parseBodyFragment(markdownHtml(markdown))
        content.outputSettings().prettyPrint(false)
        highlightQuotes(content.body())
        return content.body().html()
    }

    private fun renderFrame(template: String, markdownContent: String, colors: DisplaySetting, incremental: Boolean): String {
        require(issue(template) == null) { issue(template).orEmpty() }
        val doc = Cleaner(allowed).clean(Jsoup.parseBodyFragment(template.ifBlank { slot }))
        doc.outputSettings().prettyPrint(false)
        val target = textNodes(doc.body()).single { slot in it.wholeText }
        val parts = target.wholeText.split(slot, limit = 2)
        // Generated host ids must not collide with author ids.
        doc.select("[id]").filter { it.id().startsWith("companion-") }.forEach { it.removeAttr("id") }
        target.before(TextNode(parts[0]))
        // Insert parsed, sanitized Markdown as nodes, never as template source.
        val content = Element("div").addClass("companion-markdown")
        if (incremental) content.attr("id", incrementalContentId)
        content.html(markdownContent)
        target.before(content)
        target.before(TextNode(parts[1]))
        target.remove()
        val colorCss = ":where(.companion-markdown) strong,:where(.companion-markdown) b{color:${normalizedBodyColor(colors.companionBoldColor, "#767676")}}" +
            ":where(.companion-markdown) em,:where(.companion-markdown) i{color:${normalizedBodyColor(colors.companionItalicColor, "#767676")}}" +
            ":where(.companion-markdown) .body-quote{color:${normalizedBodyColor(colors.companionQuoteColor, "#A0712F")}}"
        return "<style>$colorCss .companion-markdown{display:flow-root;min-width:0}.companion-markdown>:first-child{margin-top:0}.companion-markdown>:last-child{margin-bottom:0}.companion-markdown pre{white-space:pre-wrap;overflow-wrap:anywhere}.companion-markdown table{border-collapse:collapse;max-width:100%}.companion-markdown th,.companion-markdown td{border:1px solid currentColor;padding:4px 8px}</style>" + doc.body().html()
    }

    private fun highlightQuotes(root: Element) {
        val text = StringBuilder()
        val nodes = mutableListOf<Pair<TextNode, Int>>()
        fun collect(node: Node) {
            when (node) {
                is TextNode -> { nodes += node to text.length; text.append(node.wholeText) }
                is Element -> {
                    if (node.normalName() in setOf("code", "pre")) { text.append('\u0000'); return }
                    if (node.isBlock || node.normalName() == "br") text.append('\n')
                    node.childNodes().forEach(::collect)
                    if (node.isBlock) text.append('\n')
                }
            }
        }
        collect(root)
        val ranges = bodyQuoteRanges(text.toString())
        nodes.forEach { (node, offset) ->
            val source = node.wholeText
            var cursor = 0
            ranges.forEach { range ->
                val start = (range.first - offset).coerceAtLeast(0)
                val end = (range.last + 1 - offset).coerceAtMost(source.length)
                if (start < end) {
                    if (cursor < start) node.before(TextNode(source.substring(cursor, start)))
                    node.before(Element("span").addClass("body-quote").text(source.substring(start, end)))
                    cursor = end
                }
            }
            if (cursor > 0) {
                if (cursor < source.length) node.before(TextNode(source.substring(cursor)))
                node.remove()
            }
        }
    }

    fun markdownHtml(markdown: String): String {
        val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
        val parser = MarkdownParser(flavour)
        val rawTree = parser.buildMarkdownTreeFromString(markdown)
        val rawHtml = mutableListOf<IntRange>()
        fun collect(node: org.intellij.markdown.ast.ASTNode) {
            // The Markdown generator already escapes literal code; don't escape it twice.
            if (node.type == MarkdownElementTypes.CODE_SPAN || node.type == MarkdownElementTypes.CODE_BLOCK ||
                node.type == MarkdownElementTypes.CODE_FENCE) return
            if (node.type == MarkdownElementTypes.HTML_BLOCK || node.type == MarkdownTokenTypes.HTML_TAG)
                rawHtml += node.startOffset until node.endOffset
            else node.children.forEach(::collect)
        }
        collect(rawTree)
        var safeSource = markdown
        rawHtml.sortedByDescending { it.first }.forEach { range ->
            safeSource = safeSource.replaceRange(range, escape(safeSource.substring(range)))
        }
        val html = HtmlGenerator(safeSource, parser.buildMarkdownTreeFromString(safeSource), flavour).generateHtml()
        val bodyRules = Safelist(allowed).removeTags("style").removeAttributes(":all", "style", "id")
        return Cleaner(bodyRules).clean(Jsoup.parseBodyFragment(html)).also {
            it.outputSettings().prettyPrint(false)
        }.body().html()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun textNodes(node: Node): List<TextNode> = node.childNodes().flatMap {
        if (it is TextNode) listOf(it) else textNodes(it)
    }
}
