package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class CardSectionsTest {
    private fun definition() = CardDefinition(title = "Card", fields = listOf(
        CardFieldDefinition("level", "Level", kind = CardFieldKind.PROGRESS),
        CardFieldDefinition("stage", "Stage"), CardFieldDefinition("note", "Note")),
        elements = listOf(CardElement("meter", "level"), CardElement("phase", "stage"),
            CardElement("note_element", "note", sectionId = "details")),
        sections = listOf(CardSection("details", "Details <safe>")))

    @Test fun foldingKeepsProgressAndStageOutsideEscapedDetailsAndRetainsPresentation() {
        val definition = definition()
        val snapshot = CardSnapshot(formatVersion = 3, nodeId = "card", title = definition.title,
            fields = listOf(CardSnapshotField("level", "Level", CardFieldKind.PROGRESS, "25"),
                CardSnapshotField("stage", "Stage", value = "Friends"),
                CardSnapshotField("note", "Note", value = "<script>unsafe</script>")),
            elements = definition.elements, sections = definition.sections)
        val html = CardMarkup.render(snapshot)
        val details = html.indexOf("<details")
        assertTrue(details > html.indexOf("<progress"))
        assertTrue(details > html.indexOf("Friends"))
        assertTrue(html.indexOf("&lt;script&gt;") > details)
        assertTrue(html.contains("value=\"0.25\""))
        assertTrue(html.contains("<summary class=\"card-section-title\">Details &lt;safe&gt;</summary>"))
        assertFalse(html.contains("id=\"details\" open"))
        assertTrue(CardMarkup.render(snapshot.copy(sections = listOf(definition.sections.single().copy(initiallyExpanded = true))))
            .contains("id=\"details\" open"))
        val encoded = CardDisplays.encodeSnapshot(snapshot)
        assertEquals(snapshot, CardDisplays.decodeSnapshot(encoded))
        val node = WorkflowNode(id = "card", actionId = "card.define", cardDefinition = definition)
        assertNull(CardDisplays.validateOutcome(node, encoded))
        assertNotNull(CardDisplays.validateOutcome(node, CardDisplays.encodeSnapshot(snapshot.copy(
            sections = listOf(CardSection("details", "Forged title"))))))
    }

    @Test fun invalidSectionsAndOldExecutionAreRejectedWhileLegacyDefaultsRemainFlat() {
        val definition = definition()
        assertTrue(CardDefinitions.validate(definition, executionVersion = 22).isEmpty())
        assertTrue(CardDefinitions.validate(definition, executionVersion = 21).any { it.error })
        listOf(definition.copy(sections = listOf(CardSection("details", ""))),
            definition.copy(sections = definition.sections + definition.sections),
            definition.copy(sections = listOf(CardSection("meter", "Collision"))),
            definition.copy(sections = emptyList()),
            definition.copy(sections = listOf(CardSection("bad id", "Bad"))))
            .forEach { assertTrue(CardDefinitions.validate(it, executionVersion = 22).any { issue -> issue.error }) }
        val old = LibraryFormat.json.decodeFromString(CardSnapshot.serializer(),
            """{"nodeId":"legacy","title":"Old","fields":[{"key":"a","title":"A","value":"text"}]}""")
        assertTrue(old.sections.isEmpty())
        assertFalse(CardMarkup.render(old).contains("<details"))
        assertTrue(CardMarkup.render(old).contains("text"))
    }

    @Test fun xmlRegenerationPreservesSectionAssignmentsAndAvoidsNewElementIdCollisions() {
        val original = XmlCardData.infer(CardDefinition(xmlSample = "<status><note>A</note></status>"))
        val grouped = original.copy(sections = listOf(CardSection("details", "Details")),
            elements = original.elements.map { it.copy(sectionId = "details") },
            xmlSample = "<status><note>B</note><details>New field</details></status>")
        val regenerated = XmlCardData.infer(grouped)
        assertEquals(grouped.sections, regenerated.sections)
        assertEquals("details", regenerated.elements.first { it.fieldKey == "note" }.sectionId)
        assertNotEquals("details", regenerated.elements.first { it.fieldKey == "details" }.id)
        assertTrue(CardDefinitions.validate(regenerated, executionVersion = 22).isEmpty())
    }
}
