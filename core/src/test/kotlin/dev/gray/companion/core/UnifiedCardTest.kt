package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class UnifiedCardTest {
    private fun definition() = CardDefinition(title = "Shared", fields = listOf(
        CardFieldDefinition("name", "Name", pattern = "name=([^;]+)", captureGroup = 1),
        CardFieldDefinition("items", "Items", pattern = "item=([^;]+)", captureGroup = 1, kind = CardFieldKind.LIST)),
        elements = listOf(CardElement("first", "name"), CardElement("again", "name"), CardElement("list", "items")),
        defaultRegion = CardRegion.CHAT, floorScope = CardFloorScope.EACH)

    @Test fun oneExtractionSupportsRepeatedEscapedElementsAndGenericLists() {
        val definition = definition()
        val fields = CardDisplays.extractFields(definition, "name=<b>A</b>;item=one;item=two;")
        assertEquals(2, fields.size)
        val html = CardMarkup.render(CardSnapshot(nodeId = "card", title = "Title", fields = fields,
            elements = definition.elements, css = ".card > .card-element { color:red; } /* note */"))
        assertEquals(2, Regex("&lt;b&gt;A&lt;/b&gt;").findAll(html).count())
        assertTrue(html.contains("<li>one</li><li>two</li>"))
        assertFalse(html.contains("<b>A</b>"))
        assertNotNull(CardMarkup.validate("</style><script>bad</script>"))
        assertNotNull(CardMarkup.validate(".card{background:u/**/rl('https://example.com')}"))
        assertNull(CardMarkup.validate(".card > #first { display:grid; gap:8px; } /* okay */"))
    }

    @Test fun selectedFloorReadsOwnAfterStateAndRejectsChangedRevisionAndStaleResult() {
        val card = CardResource(id = "card", sourceTemplate = "{{result.reply}}", definition = definition())
        val main = MainCall(id = "main", slug = "main", workflows = listOf(Workflow(id = "flow", slug = "flow", nodes = listOf(
            WorkflowNode(id = "model", slug = "model", outputKey = "reply", visible = true)))), cards = listOf(card))
        val first = ChatMessage(id = "body1", role = Role.ASSISTANT, text = "One", turnId = "user1", workflowId = "flow")
        val second = first.copy(id = "body2", turnId = "user2", text = "Two")
        val origin = ResultOrigin("main", "flow", "model")
        val firstVersion = MacroVersion(1, "name=OLD;item=a;", origin, turn = 1, turnId = "user1")
        val secondVersion = MacroVersion(2, "name=NEW;item=b;", origin, turn = 2, turnId = "user2")
        fun record(body: ChatMessage, version: MacroVersion) = TurnRecord(body.turnId!!,
            TurnCheckpoint(RuntimeSnapshot()), variants = listOf(TurnVariant(id = body.id, replyIds = listOf(body.id),
                after = RuntimeSnapshot(results = mapOf("reply" to version.text), resultOrigins = mapOf("reply" to origin)),
                macroAppends = mapOf("result.reply" to listOf(version)))), selectedVariantId = body.id)
        val session = Session(id = "s", mainCallId = "main", messages = listOf(
            ChatMessage(id = "user1", text = "1"), first, ChatMessage(id = "user2", text = "2"), second),
            results = mapOf("reply" to secondVersion.text), resultOrigins = mapOf("reply" to origin),
            macroHistory = mapOf("result.reply" to listOf(firstVersion, secondVersion)),
            turnRecords = listOf(record(first, firstVersion), record(second, secondVersion)))
        val library = Library(mainCalls = listOf(main))
        assertEquals("OLD", CardDisplays.resolve(library, session, first).single().snapshot!!.fields.first().value)
        assertEquals("NEW", CardDisplays.resolve(library, session, second).single().snapshot!!.fields.first().value)
        assertTrue(CardDisplays.resolve(library, session, first.copy(activeTextRevisionId = "edited")).isEmpty())
        val stale = session.copy(turnRecords = emptyList(), macroHistory = mapOf("result.reply" to listOf(firstVersion)),
            results = mapOf("reply" to firstVersion.text))
        assertTrue(CardDisplays.resolve(library, stale, second).isEmpty())
        assertTrue(CardDisplays.resolve(library, stale).isEmpty())
        val typed = library.copy(mainCalls = listOf(main.copy(cards = listOf(card.copy(
            sourceTemplate = "{{type:main.in:main.call:flow.name:model}}")))))
        assertTrue(CardDisplays.resolve(typed, stale, second).isEmpty())
        assertEquals("OLD", CardDisplays.resolve(typed, session, first).single().snapshot!!.fields.first().value)
        val nested = library.copy(characters = listOf(CharacterCard(id = "character", entries = listOf(
            PromptEntry(key = "bridge", text = "{{result.reply}}", isTemplate = true)))),
            mainCalls = listOf(main.copy(cards = listOf(card.copy(sourceTemplate = "{{character.bridge}}")))))
        assertTrue(CardDisplays.resolve(nested, stale.copy(characterId = "character"), second).isEmpty())
    }

    @Test fun defaultPlacementAndAuthorLockOverrideOldPreferencesAndPreventMutation() {
        val card = CardResource(id = "card", definition = definition().copy(userAdjustable = false))
        val session = Session(id = "s", mainCallId = "main", cardPlacements = listOf(
            CardPlacement("card", CardRegion.SIDEBAR, hidden = true)))
        val library = Library(mainCalls = listOf(MainCall(id = "main", cards = listOf(card))),
            sessions = listOf(session), selectedSessionId = session.id)
        assertEquals(CardRegion.CHAT, CardDisplays.effectivePlacements(library, session).single().region)
        assertFalse(CardDisplays.effectivePlacements(library, session).single().hidden)
        assertNull(CardLayoutCommands.update(library, session.id, session.cardPlacements, emptyList()).library)
        val adjustable = library.copy(mainCalls = library.mainCalls.map { it.copy(cards = listOf(card.copy(
            definition = card.definition.copy(userAdjustable = true)))) })
        assertTrue(CardDisplays.effectivePlacements(adjustable, session).single().hidden)
    }
}
