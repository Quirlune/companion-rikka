package dev.gray.companion.core

import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** Complete-response XML automation for the existing card definition and snapshot contracts. */
object XmlCardData {
    private val name = Regex("[\\p{L}_][\\p{L}\\p{N}_-]{0,63}")
    fun validPath(path: String) = path.length <= 256 && path.split('.').all { name.matches(it) }

    fun schemaProblem(definition: CardDefinition): String? = try {
        val schema = leaves(parse(definition.xmlSample, definition.xmlRoot))
        require(schema.isNotEmpty() && schema.size <= 32) { "XML 样例须有 1 到 32 个叶字段" }
        require(definition.fields.map { it.xmlPath }.toSet() == schema.keys &&
            definition.fields.size == schema.size && definition.fields.all {
                it.key == it.xmlPath && (!schema.getValue(it.xmlPath).repeated || it.kind == CardFieldKind.LIST)
            }) { "XML 样例与字段不一致，请重新生成字段与显示元素" }
        null
    } catch (error: IllegalArgumentException) { error.message ?: "XML 样例无效" }

    fun defaultDefinition(): CardDefinition = infer(CardDefinition(defaultRegion = CardRegion.CHAT,
        dataMode = CardDataMode.XML, xmlSample = """
            <status>
              <name>角色名称</name>
              <mood>平静</mood>
              <location>当前位置</location>
              <items><item>随身物品一</item><item>随身物品二</item></items>
            </status>
        """.trimIndent()))

    fun infer(definition: CardDefinition): CardDefinition {
        val leaves = leaves(parse(definition.xmlSample, definition.xmlRoot))
        require(leaves.isNotEmpty() && leaves.size <= 32) { "XML 样例须有 1 到 32 个叶字段" }
        val old = definition.fields.associateBy { it.xmlPath }
        val fields = leaves.map { (path, values) ->
            val previous = old[path]
            (previous ?: CardFieldDefinition(path, path.takeLast(128))).copy(key = path, xmlPath = path,
                source = "", pattern = "", captureGroup = 0,
                kind = if (values.repeated) CardFieldKind.LIST else previous?.kind ?: CardFieldKind.TEXT)
        }
        val keys = fields.map { it.key }.toSet()
        val used = definition.sections.mapTo(mutableSetOf()) { it.id }
        val elements = definition.elements.filter { it.fieldKey in keys &&
            Regex("[A-Za-z_][A-Za-z0-9_-]{0,127}").matches(it.id) && used.add(it.id) }.toMutableList()
        fields.filter { field -> elements.none { it.fieldKey == field.key } }.forEach { field ->
            val base = CardMarkup.defaultElementId(field.key)
            var id = base
            var suffix = 1
            while (!used.add(id)) id = "${base}_${suffix++}"
            elements += CardElement(id, field.key)
        }
        return definition.copy(dataMode = CardDataMode.XML, fields = fields, elements = elements)
    }

    fun extract(definition: CardDefinition, input: String): List<CardSnapshotField> {
        val values = leaves(parse(input, definition.xmlRoot))
        return definition.fields.map { field ->
            require(validPath(field.xmlPath)) { "XML 字段路径无效：${field.key}" }
            val items = values[field.xmlPath]?.values.orEmpty()
            require(field.kind == CardFieldKind.LIST || values[field.xmlPath]?.repeated != true) { "XML 标量字段重复：${field.xmlPath}" }
            // Reuse the same numeric normalization and presentation contract as manual cards.
            if (field.kind == CardFieldKind.LIST) CardSnapshotField(field.key, field.title, field.kind,
                items = items, minimum = field.minimum, maximum = field.maximum, compactLimit = field.compactLimit)
            else CardDisplays.extractFields(CardDefinition(fields = listOf(field.copy(source = "", pattern = "", captureGroup = 0))),
                items.firstOrNull().orEmpty()).single()
        }
    }

    private data class Node(val name: String, val children: MutableList<Node> = mutableListOf(), val text: StringBuilder = StringBuilder())
    private data class Values(val values: MutableList<String> = mutableListOf(), var repeated: Boolean = false)
    private fun leaves(root: Node): Map<String, Values> {
        val result = linkedMapOf<String, Values>()
        val containers = mutableSetOf<String>()
        fun visit(node: Node, path: String, repeated: Boolean) {
            require(path.length <= 256) { "XML 路径过长" }
            if (node.children.isEmpty()) {
                require(path !in containers) { "XML 同一路径不能同时为文本和结构：$path" }
                val value = result.getOrPut(path) { Values() }
                value.values += node.text.toString().trim()
                value.repeated = value.repeated || repeated || value.values.size > 1
                require(value.values.size <= 256) { "XML 列表超过 256 项" }
            } else {
                require(path !in result && node.text.isBlank()) { "XML 不支持混合文本与子标签：$path" }
                containers += path
                val counts = node.children.groupingBy { it.name }.eachCount()
                node.children.forEach { visit(it, if (path.isEmpty()) it.name else "$path.${it.name}", repeated || counts.getValue(it.name) > 1) }
            }
        }
        require(root.children.isNotEmpty() || root.text.isBlank()) { "XML 根标签须包含字段标签" }
        if (root.children.isNotEmpty()) visit(root, "", false)
        return result
    }

    private fun parse(input: String, rootName: String): Node {
        require(name.matches(rootName)) { "XML 根标签须是有效的简单标签名" }
        require(input.length <= 1_048_576) { "XML 来源超过 1 MiB 字符限制" }
        val fragment = fragment(input, rootName)
        val stack = mutableListOf<Node>()
        var root: Node? = null
        var count = 0
        val handler = object : DefaultHandler() {
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                throw IllegalArgumentException("XML 禁止外部实体")
            override fun error(e: SAXParseException) { throw e }
            override fun fatalError(e: SAXParseException) { throw e }
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                require(name.matches(qName) && attributes.length == 0) { "XML 暂不支持属性、命名空间或复杂标签名" }
                require(++count <= 8192 && stack.size < 32) { "XML 节点数或嵌套深度超限" }
                val node = Node(qName)
                if (stack.isEmpty()) root = node else stack.last().children += node
                stack += node
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                stack.lastOrNull()?.text?.let {
                    require(it.length + length <= 16384) { "XML 字段内容过长" }
                    it.append(ch, start, length)
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String?) { stack.removeAt(stack.lastIndex) }
        }
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            // Android and desktop SAX implementations differ in optional feature support.
            // Declaration rejection below and the mandatory resolver remain fail-closed on both.
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd").forEach { feature ->
                runCatching { factory.setFeature(feature, false) }
            }
            factory.newSAXParser().parse(InputSource(StringReader(fragment)), handler)
        } catch (error: Exception) {
            throw IllegalArgumentException("XML 解析失败：${error.message ?: "格式无效"}", error)
        }
        return requireNotNull(root) { "XML 缺少根标签" }
    }

    /** Locate a complete fragment lexically; SAX alone interprets and decodes its contents. */
    private fun fragment(input: String, root: String): String {
        var position = 0
        var start = -1
        var end = -1
        var depth = 0
        var roots = 0
        while (position < input.length) {
            val open = input.indexOf('<', position)
            if (open < 0) break
            if (input.startsWith("<!--", open) || input.startsWith("<![CDATA[", open)) {
                val comment = input.startsWith("<!--", open)
                val delimiter = if (comment) "-->" else "]]>"
                val close = input.indexOf(delimiter, open + if (comment) 4 else 9)
                require(close >= 0) { "XML 注释或 CDATA 未结束" }
                position = close + delimiter.length
                continue
            }
            require(!input.startsWith("<!", open)) { "XML 禁止 DTD 与实体声明" }
            val close = input.indexOf('>', open + 1)
            require(close >= 0 || start < 0 || end >= 0) { "XML 标签未结束" }
            if (close < 0) break
            val token = input.substring(open + 1, close)
            val closing = token.startsWith('/')
            val tag = token.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' && it != '>' }
            if (tag == root && closing) require(start >= 0 && end < 0) { "XML 根标签闭合位置无效" }
            if (tag == root && !closing) {
                roots++
                require(roots == 1) { "XML 来源包含多个根标签，无法确定卡片数据" }
                start = open
            }
            if (start >= 0 && end < 0 && !token.startsWith('?')) {
                if (closing) depth-- else if (!token.trimEnd().endsWith('/')) depth++
                if (depth == 0) end = close + 1
            }
            position = close + 1
        }
        require(start >= 0 && end > start && depth == 0) { "XML 来源缺少完整的 <$root> 根标签" }
        return input.substring(start, end)
    }
}
