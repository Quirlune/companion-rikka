package dev.gray.companion.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPackagesTest {
    @Test fun emptyReferenceBlocksSkipProcessingWithoutHidingLiteralPrompts() {
        val pack = PromptPackage(id = "pack", namespace = "world", name = "World", entries = listOf(
            PromptEntry(key = "one", text = "  ", category = "empty"),
            PromptEntry(key = "two", text = "\n", category = "empty")))
        val main = MainCall(id = "main", boundPackageIds = listOf(pack.id))
        val library = Library(mainCalls = listOf(main), packages = listOf(pack))
        val node = WorkflowNode(includeHistory = false, inputTemplate = "", blocks = listOf(
            PromptBlock(template = "标题\n{{category.empty}}", hideWhenReferencesEmpty = true,
                processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "must not appear"))),
            PromptBlock(template = "missing: {{state.unavailable}}", hideWhenReferencesEmpty = true,
                processing = listOf(TextProcessStep(TextProcessKind.PREPEND, text = "must not appear"))),
            PromptBlock(template = "literal instruction", hideWhenReferencesEmpty = true)))
        val preview = PromptCompiler().preview(library, Session(mainCallId = main.id), node, "")
        assertEquals(listOf("literal instruction"), preview.messages.map { it.text })
        assertFalse(preview.issues.any { it.error })
    }

    @Test fun emptyOmissionKeepsDiagnosticsAndLegacyProcessingSemantics() {
        val library = Library(mainCalls = listOf(MainCall(id = "main")))
        val session = Session(mainCallId = "main")
        val compiler = PromptCompiler()
        val node = WorkflowNode(includeHistory = false, inputTemplate = "", blocks = listOf(
            PromptBlock(template = "{{state.unavailable}}", hideWhenReferencesEmpty = true,
                processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "legacy wrapper")))))
        assertTrue(compiler.preview(library, session, node, "").messages.isEmpty())
        assertEquals("legacy wrapper", compiler.preview(library, session, node, "", executionVersion = 6).messages.single().text)
        val malformed = node.copy(blocks = listOf(node.blocks.single().copy(template = "{{unknown.source}}")))
        val rejected = compiler.preview(library, session, malformed, "")
        assertTrue(rejected.messages.isEmpty())
        assertTrue(rejected.issues.any { it.error })
    }

    @Test fun mainBindingMakesMacrosAvailableWithoutAutomaticallySendingTheWarehouse() {
        val pack = PromptPackage(id = "world", namespace = "world", name = "World", entries = listOf(
            PromptEntry(key = "scene", text = "<place>garden</place>", category = "setting"),
            PromptEntry(key = "secret", text = "unreferenced content"),
            PromptEntry(key = "night", text = "night", category = "setting", condition = Condition("time", value = "night"))
        ))
        val main = MainCall(id = "main", name = "Main", boundPackageIds = listOf(pack.id))
        val session = Session(mainCallId = main.id, state = mapOf("time" to "day"))
        val library = Library(mainCalls = listOf(main), packages = listOf(pack))
        val compiler = PromptCompiler()
        val emptyNode = WorkflowNode(includeHistory = false, inputTemplate = "")
        assertTrue(compiler.preview(library, session, emptyNode, "").messages.isEmpty())
        val referenced = emptyNode.copy(blocks = listOf(PromptBlock(template = "{{pack.world.scene#place}}|{{category.setting}}")))
        val preview = compiler.preview(library, session, referenced, "")
        assertEquals("garden|<place>garden</place>", preview.messages.single().text)
        assertFalse(preview.issues.any { it.error })
        assertTrue(compiler.preview(library, session.copy(mainCallId = ""), referenced, "").messages.single().text == "|")
        val invalid = library.copy(mainCalls = listOf(main.copy(boundPackageIds = listOf("missing"))))
        assertTrue(LibraryValidator.validate(invalid).any { it.path == "main.main.boundPackageIds" && it.error })
    }

    @Test fun multipleLoadSourcesDeduplicateAndManualUnloadPreservesBindings() {
        val explicit = PromptPackage(id = "extra", namespace = "extra")
        val shared = PromptPackage(id = "shared", namespace = "shared", entries = listOf(PromptEntry(key = "text", text = "once", category = "shared")))
        val characterOnly = PromptPackage(id = "card", namespace = "card")
        val mainOnly = PromptPackage(id = "mainPack", namespace = "mainPack")
        val groupOnly = PromptPackage(id = "groupPack", namespace = "groupPack")
        val card = CharacterCard(id = "character", boundPackageIds = listOf(shared.id, characterOnly.id))
        val main = MainCall(id = "main", boundPackageIds = listOf(shared.id, mainOnly.id))
        val group = CallGroup(id = "group", boundPackageIds = listOf(shared.id, groupOnly.id))
        val session = Session(characterId = card.id, mainCallId = main.id, packageIds = listOf(explicit.id, shared.id), groupIds = listOf(group.id))
        val library = Library(characters = listOf(card), mainCalls = listOf(main), groups = listOf(group), packages = listOf(explicit, shared, characterOnly, mainOnly, groupOnly))
        assertEquals(listOf("extra", "shared", "card", "mainPack", "groupPack"), library.loadedPromptPackages(session).map { it.id })
        val unloaded = session.copy(packageIds = emptyList())
        assertEquals(listOf("shared", "card", "mainPack", "groupPack"), library.loadedPromptPackages(unloaded).map { it.id })
        val node = WorkflowNode(includeHistory = false, inputTemplate = "{{category.shared}}")
        assertEquals("once", PromptCompiler().preview(library, unloaded, node, "").messages.single().text)
        assertTrue(library.loadedPromptPackages(unloaded.copy(characterId = "", mainCallId = "", groupIds = emptyList())).isEmpty())
        val legacy = Json.decodeFromString<MainCall>("""{"id":"old","name":"Old","workflows":[]}""")
        assertTrue(legacy.boundPackageIds.isEmpty())
    }
}
