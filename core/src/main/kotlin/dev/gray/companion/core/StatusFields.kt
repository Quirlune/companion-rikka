package dev.gray.companion.core

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** Reads one named scalar from bounded, inert XML. A single key finds the first descendant. */
object StatusFields {
    private val segment = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")

    fun validKey(key: String): Boolean {
        val parts = key.split('/').filter { it.isNotBlank() }
        return key.length <= 256 && parts.isNotEmpty() && parts.size <= 16 &&
            parts.all { segment.matches(it) } && !key.startsWith('/') && !key.endsWith('/') && "//" !in key
    }

    fun read(xml: String, key: String): String? = readAll(xml, listOf(key))[key]

    /** Parse once when a status view displays several fields from the same value. */
    fun readAll(xml: String, keys: Collection<String>): Map<String, String?> {
        val requested = keys.distinct()
        if (requested.isEmpty()) return emptyMap()
        if (requested.any { !validKey(it) } || xml.length > 65_536 ||
            Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml))
            return requested.associateWith { null }
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                XMLConstants.FEATURE_SECURE_PROCESSING to true
            ).forEach { (feature, enabled) -> try { factory.setFeature(feature, enabled) } catch (_: Exception) { } }
            factory.isExpandEntityReferences = false
            try { factory.isXIncludeAware = false } catch (_: Exception) { }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
            val document = builder.parse(InputSource(StringReader("<root>$xml</root>")))
            fun children(node: Node): List<Node> = (0 until node.childNodes.length)
                .map { node.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }
            fun firstDescendant(node: Node, name: String, depth: Int): Node? {
                if (depth > 16) return null
                for (child in children(node)) {
                    if (child.nodeName == name) return child
                    firstDescendant(child, name, depth + 1)?.let { return it }
                }
                return null
            }
            requested.associateWith { key ->
                val parts = key.split('/')
                var current: Node? = firstDescendant(document.documentElement, parts.first(), 0)
                for (part in parts.drop(1)) current = current?.let { node ->
                    children(node).firstOrNull { it.nodeName == part }
                }
                current?.textContent?.trim()?.takeIf { it.length <= 4_096 }
            }
        } catch (_: Exception) {
            requested.associateWith { null }
        }
    }
}
