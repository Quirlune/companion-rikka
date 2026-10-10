package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class XmlCardDataTest {
    private fun inferred(sample: String) = XmlCardData.infer(CardDefinition(dataMode = CardDataMode.XML, xmlSample = sample))
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid XML to fail") } catch (_: IllegalArgumentException) { }
    }

    @Test fun nestedRepeatedPathsRemainListsWithOneRuntimeItemAndDecodeXmlText() {
        val definition = inferred("<status><people><person><name>A</name></person><person><name>B</name></person></people><place><name>Home</name></place></status>")
        assertEquals(listOf("people.person.name", "place.name"), definition.fields.map { it.key })
        assertEquals(CardFieldKind.LIST, definition.fields.first().kind)
        val fields = CardDisplays.extractFields(definition,
            "Complete reply\n```xml\n<status><people><person><name>A &amp; B &#x2665;</name></person></people><extra>ignored</extra></status>\n```\nDone")
        assertEquals(listOf("A & B ♥"), fields.first().items)
        assertEquals("", fields.last().value)
        assertNull(XmlCardData.schemaProblem(definition))
    }

    @Test fun regenerationPreservesPresentationCssAndValidRepeatedReferences() {
        val base = inferred("<status><health><value>20</value></health><old>x</old></status>")
        val styled = base.copy(css = ".card { color:red; }", fields = base.fields.map {
            if (it.key == "health.value") it.copy(title = "Health", kind = CardFieldKind.PROGRESS, maximum = 50.0) else it
        }, elements = listOf(CardElement("custom", "health.value"), CardElement("duplicate", "health.value"), CardElement("old", "old")),
            xmlSample = "<status><health><value>30</value></health><new>x</new></status>")
        assertNotNull(XmlCardData.schemaProblem(styled))
        val rebuilt = XmlCardData.infer(styled)
        assertNull(XmlCardData.schemaProblem(rebuilt))
        assertEquals(styled.css, rebuilt.css)
        assertEquals("Health", rebuilt.fields.first().title)
        assertEquals(CardFieldKind.PROGRESS, rebuilt.fields.first().kind)
        assertEquals(50.0, rebuilt.fields.first().maximum, 0.0)
        assertEquals(listOf("custom", "duplicate", "new"), rebuilt.elements.map { it.id })
        assertEquals(base.elements.first().id, inferred(base.xmlSample).elements.first().id)
        assertEquals("health__value", base.elements.first().id)
        assertTrue(base.elements.all { Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(it.id) })
        val collision = inferred("<status><a><b>x</b></a><a__b>y</a__b></status>")
        assertEquals(listOf("a__b", "a__b_1"), collision.elements.map { it.id })
        assertEquals(collision.elements, XmlCardData.infer(collision).elements)
    }

    @Test fun invalidAmbiguousExternalAndOversizedXmlAreRejected() {
        listOf("<status><x>unfinished</status>", "<status><x>a</x></status><status><x>b</x></status>",
            "<!DOCTYPE status [<!ENTITY secret SYSTEM 'file:///unread'>]><status><x>&secret;</x></status>",
            "<status><x>&unknown;</x></status>", "<status xmlns='x'><x>a</x></status>",
            "<status><x a='b'>a</x></status>", "<status>mixed<x>a</x></status>",
            "<status><x>${"a".repeat(16385)}</x></status>", "<status><x>a</x></status></status>")
            .forEach { xml -> rejects { inferred(xml) } }
        val scalar = inferred("<status><x>a</x></status>")
        rejects { XmlCardData.extract(scalar, "<status><x>a</x><x>b</x></status>") }
    }

    @Test fun acceptedSnapshotKeepsPresentationWithoutXmlExtractionMetadata() {
        val definition = inferred("<status><health><value>12</value></health></status>").let { card ->
            card.copy(fields = card.fields.map { it.copy(kind = CardFieldKind.NUMBER) })
        }
        val fields = CardDisplays.extractFields(definition, "<status><health><value><![CDATA[0012.50]]></value></health></status>")
        assertEquals("12.5", fields.single().value)
        val snapshot = CardSnapshot(formatVersion = 3, nodeId = "card", title = definition.title,
            fields = fields, elements = definition.elements)
        val encoded = CardDisplays.encodeSnapshot(snapshot)
        assertEquals(snapshot, CardDisplays.decodeSnapshot(encoded))
        assertNull(CardDisplays.validateOutcome(WorkflowNode(id = "card", actionId = "card.define", cardDefinition = definition), encoded))
        assertFalse(encoded.contains("xmlSample"))
        assertTrue(CardDefinitions.validate(definition, executionVersion = 19).any { it.error })
    }

    @Test fun manualDefaultsAndNewXmlDefaultsStayIndependent() {
        val manual = CardDefinition(fields = listOf(CardFieldDefinition("value", "Value", pattern = "value=(.*)", captureGroup = 1)))
        assertEquals(CardDataMode.MANUAL, manual.dataMode)
        assertEquals("old", CardDisplays.extractFields(manual, "value=old").single().value)
        val xml = XmlCardData.defaultDefinition()
        assertEquals(CardDataMode.XML, xml.dataMode)
        assertNull(XmlCardData.schemaProblem(xml))
        assertEquals(xml.fields.size, xml.elements.size)
        assertTrue(CardDefinitions.validate(xml).none { it.error })
    }
}
