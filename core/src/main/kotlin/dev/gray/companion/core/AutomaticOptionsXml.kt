package dev.gray.companion.core

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Complete, bounded, text-only XML elements; repeated names bind by occurrence order. */
internal object AutomaticOptionsXml {
    private val tag = Regex("[\\p{L}_][\\p{L}\\p{N}_-]{0,63}")
    fun validTag(value: String) = tag.matches(value)

    fun parse(text: String, config: AutomaticOptionsConfig): List<AutomaticOption> {
        require(config.count in 1..AutomaticOptions.MAX_COUNT) { "选项数量无效" }
        require(text.length <= AutomaticOptions.MAX_RESPONSE_BYTES && text.toByteArray(Charsets.UTF_8).size <= AutomaticOptions.MAX_RESPONSE_BYTES) { "选项回复超过 64 KiB" }
        val specs = AutomaticOptions.effectiveItems(config)
        require(specs.all { validTag(it.contentTag) && (!config.titlesEnabled || validTag(it.titleTag)) }) { "选项 XML 标签无效" }
        val expected = specs.flatMap { if (config.titlesEnabled) listOf(it.titleTag, it.contentTag) else listOf(it.contentTag) }
        val values = mutableListOf<Pair<String, String>>()
        var depth = 0
        val buffer = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                require(attributes.length == 0) { "选项 XML 不支持属性" }
                depth++
                if (depth == 1) return
                require(depth == 2) { "选项 XML 不支持嵌套标签" }
                require(values.size < expected.size && qName in expected) { "选项标签数量不符或存在未配置标签" }
                buffer.setLength(0)
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (depth == 2) buffer.append(ch, start, length)
                else require((start until start + length).all { ch[it].isWhitespace() }) { "选项标签外存在额外内容" }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (depth == 2) {
                    val value = buffer.toString().trim()
                    require(value.isNotEmpty()) { "选项标题或内容不能为空" }
                    require(value.length <= AutomaticOptions.MAX_OPTION_CHARS) { "选项内容过长" }
                    values += qName to value
                }
                depth--
            }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
                throw IllegalArgumentException("选项 XML 不支持外部实体")
            }
            override fun processingInstruction(target: String?, data: String?) { throw IllegalArgumentException("选项 XML 不支持处理指令") }
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
        }
        try {
            require(!text.contains("<!DOCTYPE", ignoreCase = true) && !text.contains("<!ENTITY", ignoreCase = true) && !text.contains("<!--")) { "选项 XML 不支持 DTD、实体声明或注释" }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                // Android SAX does not implement every feature. The lexical DTD guard and
                // rejecting resolver remain authoritative even on those implementations.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newSAXParser().parse(InputSource(StringReader("<automatic_options_root>$text</automatic_options_root>")), handler)
        } catch (failure: IllegalArgumentException) { throw failure }
        catch (failure: Exception) { throw IllegalArgumentException("选项 XML 格式无效：${failure.message}", failure) }
        val actualCounts = values.groupingBy { it.first }.eachCount()
        expected.groupingBy { it }.eachCount().forEach { (name, count) ->
            val actual = actualCounts[name] ?: 0
            require(actual == count) { "XML 标签 <$name> 应有 $count 项，实际 $actual 项；请检查输出协议与提示词" }
        }
        val byTag = values.groupBy({ it.first }, { it.second }).mapValues { it.value.toMutableList() }
        fun take(name: String) = byTag.getValue(name).removeAt(0)
        return specs.map { spec ->
            val title = if (config.titlesEnabled) take(spec.titleTag).also {
                require(it.length <= AutomaticOptions.MAX_TITLE_CHARS) { "选项标题超过 ${AutomaticOptions.MAX_TITLE_CHARS} 字符" }
            } else null
            AutomaticOption(take(spec.contentTag), title)
        }
    }
}
