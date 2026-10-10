package dev.gray.companion.core

import dev.gray.companion.core.PromptReferenceLanguage.QueryPlan
import org.junit.Assert.*
import org.junit.Test

class PruningReferenceTest {
    private val pack = ExtensionResourceRef(ExtensionResourceKind.PACKAGE, "pack")
    private val owner = ExtensionResourceRef(ExtensionResourceKind.GROUP, "group")
    private val tags = listOf(
        PromptTag("npc-id", "NPC", "npc"), PromptTag("ally-id", "Ally", "ally"),
        PromptTag("warm-id", "Warm", "warm"), PromptTag("spoiler-id", "Spoiler", "spoiler"))

    private fun base(template: String = "") = LibraryFormat.upgrade(Library(
        packages = listOf(PromptPackage(id = pack.id, namespace = "legacy", name = "World", slug = "world",
            entries = listOf(
                PromptEntry(id = "first", key = "first", slug = "first", text = "first", enabled = false,
                    tagIds = listOf("npc-id", "ally-id")),
                PromptEntry(id = "second", key = "second", slug = "second", text = "second",
                    condition = Condition(key = "ready", value = "yes"), tagIds = listOf("npc-id", "warm-id")),
                PromptEntry(id = "spoiler", key = "spoiler", slug = "spoiler", text = "spoiler",
                    tagIds = listOf("npc-id", "ally-id", "spoiler-id"))))),
        groups = listOf(CallGroup(id = owner.id, name = "Consumer", workflows = listOf(
            Workflow(id = "flow", enabled = false, nodes = listOf(WorkflowNode(id = "node", actionId = "text.render",
                inputTemplate = template, includeHistory = false, outputKey = "value")))))),
        promptTags = tags))

    @Test fun cardFieldAloneProtectsUnloadedDisabledEntryAndKeepsSourceLocation() {
        val node = WorkflowNode(id = "node", actionId = "card.define", includeHistory = false, outputKey = "card",
            cardDefinition = CardDefinition(fields = listOf(CardFieldDefinition("detail", "Detail", "{{pack.legacy.first}}"))))
        val original = base()
        val library = original.copy(groups = listOf(original.groups.single().copy(workflows = listOf(
            Workflow(id = "flow", enabled = false, nodes = listOf(node))))))
        val target = ResourceLocation(pack, entryId = "first")
        val use = ResourceUsageIndex.usages(library, target).single { it.kind == ResourceUseKind.TEMPLATE }
        assertEquals(ResourceLocation(owner, workflowId = "flow", nodeId = "node", section = "cardDefinition.fields.0.source"), use.location)
        assertEquals("{{pack.legacy.first}}", ResourceTemplateSlots.read(library, requireNotNull(use.location)))
        assertFalse(ResourceEditCommands.previewDelete(library, target).canApply)
        assertTrue(ResourceEditCommands.previewDelete(original, target).canApply)
    }

    @Test fun staticQueriesProtectPossibleEntriesWhileRuntimeStillAppliesActivation() {
        val selections = linkedMapOf(
            "type:pack.in:world.name:first" to setOf("first"),
            "type:pack.in:world.tag:npc.any:ally,warm.not:spoiler" to setOf("first", "second"),
            "type:pack.in:*.any:warm" to setOf("second"),
            "type:pack.in:world.not:spoiler" to setOf("first", "second"),
            "type:pack.in:world" to setOf("first", "second", "spoiler"))
        for ((expression, expected) in selections) {
            val library = base("{{$expression}}")
            val candidates = ResourceUsageIndex.staticTargets(library, ResourceLocation(owner), expression)
            assertEquals(expression, expected, candidates.map { it.entryId }.toSet())
            for (id in expected) assertFalse(expression, ResourceEditCommands.previewDelete(library, ResourceLocation(pack, entryId = id)).canApply)
        }
        val library = base()
        val session = Session(packageIds = listOf(pack.id))
        val plan = PromptReferenceLanguage.compile("type:pack.in:world.tag:npc.any:ally,warm.not:spoiler").reference as QueryPlan
        assertTrue(StaticPromptQueries.bind(plan, loadedResources(library, session, session.state)).entries.isEmpty())
        val ready = session.copy(state = mapOf("ready" to "yes"))
        assertEquals(listOf("second"), StaticPromptQueries.bind(plan, loadedResources(library, ready, ready.state)).entries.map { it.second.id })
        for (unsupported in listOf("type:pack.in:world.folder:cast", "type:pack.in:world.name:first.at:version-1", "type:group.in:consumer"))
            assertTrue(unsupported, ResourceUsageIndex.staticTargets(library, ResourceLocation(owner), unsupported).isEmpty())
        val character = CharacterCard(id = "hero", namespace = "hero", name = "Hero", slug = "hero",
            entries = listOf(PromptEntry(id = "custom", key = "custom", slug = "custom", enabled = false, tagIds = listOf("npc-id"))))
        val withCharacter = library.copy(characters = listOf(character))
        assertEquals(listOf("custom"), ResourceUsageIndex.staticTargets(withCharacter, ResourceLocation(owner),
            "type:character.in:hero.tag:npc").map { it.entryId })
    }

    @Test fun personaBracesAndRetiredTitleTemplateAreNotDependencies() {
        val literal = base().copy(personas = listOf(UserPersona(id = "persona", name = "Persona",
            prompt = "{{pack.legacy.first}} {{type:pack.in:world.tag:npc}} {{literal identity notes}}")))
        val target = ResourceLocation(pack, entryId = "first")
        val errors = LibraryValidator.validate(literal).filter { it.error }
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(ResourceUsageIndex.usages(literal, target).isEmpty())
        assertTrue(ResourceEditCommands.previewDelete(literal, target).canApply)
        assertEquals(literal.personas.single().prompt, MacroResolver(literal, Session(personaId = "persona")).render("{{user.prompt}}"))
        val title = literal.copy(titleConfig = literal.titleConfig.copy(prompt = "{{type:pack.in:world.tag:npc}}"))
        assertTrue(ResourceUsageIndex.usages(title, target).isEmpty())
        assertTrue(ResourceEditCommands.previewDelete(title, target).canApply)
    }
}
