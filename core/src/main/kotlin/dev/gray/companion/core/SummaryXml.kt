package dev.gray.companion.core

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Complete output only; SAX validates and decodes the single selected root's text. */
object SummaryXml {
    private val name = Regex("[\\p{L}_][\\p{L}\\p{N}_-]{0,63}")
    fun validTag(tag: String): Boolean = name.matches(tag)

    fun extract(input: String, tag: String): String {
        require(validTag(tag)) { "摘要 XML 标签名无效" }
        require(input.length <= 1_048_576) { "摘要 XML 超过长度限制" }
        var position = 0
        var start = -1
        var end = -1
        var roots = 0
        while (position < input.length) {
            val open = input.indexOf('<', position)
            if (open < 0) break
            if (input.startsWith("<!--", open) || input.startsWith("<![CDATA[", open)) {
                val comment = input.startsWith("<!--", open)
                val delimiter = if (comment) "-->" else "]]>"
                val close = input.indexOf(delimiter, open + if (comment) 4 else 9)
                require(close >= 0) { "摘要 XML 注释或 CDATA 未结束" }
                position = close + delimiter.length
                continue
            }
            require(!input.startsWith("<!", open)) { "摘要 XML 禁止 DTD 与实体声明" }
            val close = input.indexOf('>', open + 1)
            require(close >= 0) { "摘要 XML 标签未结束" }
            val token = input.substring(open + 1, close)
            val closing = token.startsWith('/')
            val tokenName = token.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' }
            if (tokenName == tag) {
                if (closing) {
                    require(start >= 0 && end < 0) { "摘要 XML 闭合标签重复或位置无效" }
                    end = close + 1
                } else {
                    require(++roots == 1) { "摘要 XML 包含重复标签 <$tag>" }
                    start = open
                    if (token.trimEnd().endsWith('/')) end = close + 1
                }
            }
            position = close + 1
        }
        require(start >= 0 && end > start) { "摘要缺少完整的 <$tag> 标签" }
        val text = StringBuilder()
        var depth = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw IllegalArgumentException("摘要 XML 禁止外部实体")
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                require(depth == 0 && qName == tag && attributes.length == 0) { "摘要 XML 只允许无属性的纯文本根标签，不允许嵌套" }
                depth++
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
            override fun processingInstruction(target: String?, data: String?) {
                throw IllegalArgumentException("摘要 XML 不允许处理指令")
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        }
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            listOf("http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { feature ->
                runCatching { factory.setFeature(feature, false) }
            }
            factory.newSAXParser().parse(InputSource(StringReader(input.substring(start, end))), handler)
        } catch (failure: Exception) {
            throw IllegalArgumentException("摘要 XML 格式无效：${failure.message.orEmpty()}", failure)
        }
        return text.toString().trim().also { require(it.isNotBlank()) { "摘要 XML 正文不能为空" } }
    }
}
