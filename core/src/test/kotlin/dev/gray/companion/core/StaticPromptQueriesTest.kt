package dev.gray.companion.core

import dev.gray.companion.core.PromptReferenceLanguage.QueryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticPromptQueriesTest {
    private val tags = listOf(
        PromptTag(id = "npc-id", name = "NPC", slug = "npc"),
        PromptTag(id = "ally-id", name = "Ally", slug = "ally"),
        PromptTag(id = "warm-id", name = "Warm", slug = "warm"),
        PromptTag(id = "spoiler-id", name = "Spoiler", slug = "spoiler")
    )

    private fun plan(expression: String): QueryPlan =
        PromptReferenceLanguage.compile(expression).reference as QueryPlan

    private fun bind(expression: String, library: Library, session: Session): StaticPromptQueries.Selection =
        StaticPromptQueries.bind(plan(expression), loadedResources(library, session, session.state))

    @Test fun loadedResourceAndEntryOrderWithAndOrExclusionTags() {
        val first = PromptPackage(id = "first", namespace = "one", slug = "one", entries = listOf(
            PromptEntry(key = "late", slug = "late", order = 7, tagIds = listOf("npc-id", "ally-id", "warm-id")),
            PromptEntry(key = "early", slug = "early", order = 2, tagIds = listOf("npc-id", "ally-id"))
        ))
        val second = PromptPackage(id = "second", namespace = "two", slug = "two", entries = listOf(
            PromptEntry(key = "hidden", slug = "hidden", order = 0, tagIds = listOf("npc-id", "ally-id", "spoiler-id")),
            PromptEntry(key = "visible", slug = "visible", order = 1, tagIds = listOf("npc-id", "ally-id", "warm-id"))
        ))
        val library = Library(packages = listOf(first, second), promptTags = tags)
        val session = Session(packageIds = listOf(second.id, first.id))
        val selected = bind("not:spoiler.any:ally,warm.tag:npc.in:*.type:pack", library, session)
        assertNull(selected.diagnostic)
        assertEquals(listOf("pack.two.visible", "pack.one.early", "pack.one.late"),
            selected.entries.map { (source, entry) -> "$source.${entry.key}" })
        assertEquals(listOf("early", "late"), bind("type:pack.in:one.tag:npc", library, session)
            .entries.map { it.second.slug })
    }

    @Test fun namedSourceAndEntryUseSlugsAndNeverLoadOtherResources() {
        val loaded = PromptPackage(id = "loaded", namespace = "legacy_address", slug = "english", entries = listOf(
            PromptEntry(key = "old_key", title = "中文标题", slug = "new_name", text = "selected")
        ))
        val unloaded = PromptPackage(id = "unloaded", namespace = "other", slug = "unloaded", entries = listOf(
            PromptEntry(key = "old_key", slug = "new_name", text = "unloaded")
        ))
        val character = CharacterCard(id = "character", namespace = "char", slug = "hero", entries = listOf(
            PromptEntry(key = "bio", slug = "portrait", text = "character")
        ))
        val library = Library(characters = listOf(character), packages = listOf(loaded, unloaded))
        val session = Session(characterId = character.id, packageIds = listOf(loaded.id))
        val selected = bind("name:new_name.type:pack.in:english", library, session)
        assertEquals(listOf("pack.legacy_address" to "selected"),
            selected.entries.map { it.first to it.second.text })
        assertNull(bind("type:pack.in:english.name:new_name.xml:detail", library, session).diagnostic)
        assertTrue(bind("type:pack.in:unloaded.name:new_name", library, session).entries.isEmpty())
        assertTrue(bind("type:pack.in:legacy_address.name:new_name", library, session).entries.isEmpty())
        assertEquals(listOf("character"), bind("type:character.in:hero.name:portrait", library, session)
            .entries.map { it.second.text })
    }

    @Test fun activationConditionAndSessionOverrideAreAlreadyApplied() {
        val pack = PromptPackage(id = "pack", namespace = "world", slug = "world", entries = listOf(
            PromptEntry(key = "disabled", slug = "disabled", enabled = false),
            PromptEntry(key = "conditional", slug = "conditional", condition = Condition(key = "ready", value = "yes")),
            PromptEntry(key = "overridden", slug = "overridden", text = "source"),
            PromptEntry(key = "revived", slug = "revived", enabled = false, text = "source")
        ))
        val library = Library(packages = listOf(pack), promptTags = tags)
        val origin = ResultOrigin(pack.id, "flow", "node")
        val session = Session(packageIds = listOf(pack.id), macroHistory = mapOf(
            "pack.world.overridden" to listOf(MacroVersion(1, "hidden", origin, enabled = false)),
            "pack.world.revived" to listOf(MacroVersion(1, "override", origin, enabled = true))
        ))
        val query = "type:pack.in:world"
        assertEquals(listOf("revived"), bind(query, library, session).entries.map { it.second.slug })
        assertEquals(listOf("conditional", "revived"), bind(query, library, session.copy(state = mapOf("ready" to "yes")))
            .entries.map { it.second.slug })
        assertEquals("override", bind("type:pack.in:world.name:revived", library, session).entries.single().second.text)
    }

    @Test fun unsupportedMetadataAndUnknownTagsAreDiagnosed() {
        val resources = loadedResources(Library(promptTags = tags), Session(), emptyMap())
        val unsupported = listOf(
            "type:pack.in:world.folder:cast",
            "type:pack.in:world.under:cast",
            "type:pack.in:world.name:hero.at:version-1",
            "type:main.in:daily",
            "type:group.in:extras"
        )
        for (expression in unsupported) {
            assertTrue(expression, StaticPromptQueries.bind(plan(expression), resources).diagnostic != null)
            assertTrue(expression, macroExpressionError(expression) != null)
        }
        assertTrue(StaticPromptQueries.bind(plan("type:pack.in:world.tag:unknown"), resources).diagnostic != null)
    }

    @Test fun compilerRendersSelectedTemplatesAndXmlWithoutChangingOldRecovery() {
        val pack = PromptPackage(id = "pack", namespace = "legacy", name = "World", slug = "world", entries = listOf(
            PromptEntry(key = "first", slug = "first", text = "<detail>{{input}}</detail>", isTemplate = true,
                tagIds = listOf("npc-id", "ally-id", "warm-id")),
            PromptEntry(key = "second", slug = "second", text = "second", tagIds = listOf("npc-id", "ally-id"))
        ))
        val node = WorkflowNode(id = "node", includeHistory = false, inputTemplate = "", blocks = listOf(
            PromptBlock(template = "{{type:pack.in:world.tag:npc.any:ally,warm.not:spoiler}}"),
            PromptBlock(template = "{{type:pack.in:world.name:first.at:latest.xml:detail}}")
        ))
        val workflow = Workflow(id = "workflow", nodes = listOf(node))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(workflow), primaryWorkflowId = workflow.id)
        val library = Library(mainCalls = listOf(main), packages = listOf(pack), promptTags = tags)
        val session = Session(mainCallId = main.id, packageIds = listOf(pack.id))

        assertTrue(LibraryValidator.validate(library).none { it.error })
        val current = PromptCompiler().preview(library, session, node, "resolved")
        assertTrue(current.issues.none { it.error })
        assertEquals(listOf("<detail>resolved</detail>\nsecond", "resolved"), current.messages.map { it.text })

        val oldValidation = LibraryValidator.validate(library, executionVersion = 4)
        assertTrue(oldValidation.any { it.error && it.message.contains("尚未绑定") })
        val old = PromptCompiler().preview(library, session, node, "resolved", executionVersion = 4)
        assertTrue(old.issues.any { it.error && it.message.contains("尚未绑定") })
    }
}
