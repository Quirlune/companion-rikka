package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CardResourceIntegrationTest {
    private fun definition() = CardDefinition(title = "Shared", fields = listOf(CardFieldDefinition("value", "Value")))

    @Test fun currentExecutionIgnoresLegacyOutputContractsAndBodyFreeze() = runBlocking {
        val node = WorkflowNode(id = "node", outputKey = "answer", visible = true)
        val tile = MacroTile(id = "legacy", title = "Legacy", template = "{{result.answer}}",
            kind = MacroTileKind.NUMBER)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = listOf(node))),
            tiles = listOf(tile), bodySourceTileId = tile.id)
        val library = LibraryFormat.upgrade(Library(mainCalls = listOf(main),
            characters = listOf(CharacterCard(id = "hero", namespace = "hero", name = "Hero")),
            profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile"))
        val session = Session(id = "session", characterId = "hero", mainCallId = "main")
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) = "plain body"
        }
        val result = WorkflowEngine().run(library, session, "hello", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("plain body", result.replies.single().text)
        assertNull(result.replies.single().bodyDisplay)
        assertFalse(OutcomeValidator(library, session).hasDirectContract("result.answer"))
        assertTrue(MacroDisplay.configuredTiles(library, session).isEmpty())
        assertEquals(listOf(tile), MacroDisplay.configuredTiles(library, session, 18))
        assertTrue(ExecutionSemantics(18).bodyDisplayTemplates)
        assertFalse(ExecutionSemantics(19).bodyDisplayTemplates)
        val invalid = library.copy(mainCalls = listOf(main.copy(tiles = listOf(tile.copy(pattern = "[")))))
        assertFalse(LibraryValidator.validate(invalid).any { it.path.startsWith("tile.") })
        assertTrue(LibraryValidator.validate(invalid, 18).any { it.path.startsWith("tile.") })
    }

    @Test fun independentCardsRoundTripThroughConfigurationAndCopyWithFreshIdentity() {
        val card = CardResource(id = "card", sourceTemplate = "literal", definition = definition().copy(
            css = ".card { color: red; }", elements = listOf(CardElement(id = "one", fieldKey = "value"),
                CardElement(id = "two", fieldKey = "value"))))
        val original = LibraryFormat.upgrade(Library(groups = listOf(CallGroup(id = "group", name = "Group", cards = listOf(card)))))
        val restored = LibraryFormat.decode(LibraryFormat.encode(original))
        assertEquals(card, restored.groups.single().cards.single())
        val base = LibraryFormat.upgrade(Library())
        val preview = ConfigurationCommands.previewResources(restored, base)
        assertNotNull(preview.issues.toString(), preview.plan)
        val imported = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
        assertEquals(imported.issues.toString(), listOf(card), imported.library?.groups?.single()?.cards)
        val copy = ResourceEditCommands.previewCopy(original, ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, "group")))
        assertTrue(copy.issues.toString(), copy.canApply)
        val copied = ResourceEditCommands.apply(copy, original).library!!.groups.last().cards.single()
        assertNotEquals(card.id, copied.id)
        assertEquals(card.copy(id = copied.id), copied)
    }

    @Test fun sourceFieldAndVisibilityReferencesProtectDependenciesAndEnterCapturedWorld() {
        val pack = PromptPackage(id = "pack", name = "Pack", namespace = "data", entries = listOf(
            PromptEntry(id = "entry", key = "value", text = "retained")))
        val reference = "{{pack.data.value}}"
        val cards = listOf(
            CardResource(id = "source", sourceTemplate = reference, definition = definition()),
            CardResource(id = "field", definition = definition().copy(fields = listOf(CardFieldDefinition("value", "Value", source = reference)))),
            CardResource(id = "condition", definition = definition().copy(condition = Condition(value = reference))))
        val library = LibraryFormat.upgrade(Library(packages = listOf(pack),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList(), cards = cards))))
        val target = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.PACKAGE, "pack"), entryId = "entry")
        val uses = ResourceUsageIndex.usages(library, target).filter { it.kind == ResourceUseKind.TEMPLATE }
        assertEquals(uses.toString(), 3, uses.size)
        assertFalse(ResourceEditCommands.previewDelete(library, target).canApply)
        val captured = CapturedResourceClosure.capture(library, Session(mainCallId = "main"))
        assertEquals("pack", captured.packages.single().id)
        val slot = ResourceTemplateSlots.list(library).single { it.location.section == "cards.condition.definition.condition.value" }
        assertEquals(reference, slot.text)
        val issue = Issue("card.field.definition.fields.0.source", "bad reference")
        assertEquals("cards.field", ResourceLocations.resolveIssue(library, issue)?.section)
    }
}
