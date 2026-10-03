package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class MacroNamingTest {
    @Test fun namingRulesAreSharedAndSiblingNamesAreChecked() {
        listOf("a", "world_2", "a-b").forEach { assertTrue(MacroNaming.isValidSlug(it)) }
        listOf("", "A", "2a", "_a", "世界", "a.b", "a b", " a").forEach {
            assertFalse(MacroNaming.isValidSlug(it))
            assertNotNull(MacroNaming.slugProblem(it))
        }
        assertNull(MacroNaming.nameProblem("世界 / World"))
        assertNotNull(MacroNaming.nameProblem("  "))
        assertNotNull(MacroNaming.nameProblem(" 世界 ", listOf("世界")))
        assertNotNull(MacroNaming.slugProblem("world", listOf("world")))
        assertNull(MacroNaming.slugProblem("world", listOf("other")))
    }

    @Test fun normalizationPreservesContentLegacyAddressesAndRuntimeSnapshots() {
        val block = PromptBlock(id = "block", title = "", template = "{{pack.世界.人物}}", enabled = false)
        val node = WorkflowNode(id = "node", title = "", outputKey = "旧.结果", blocks = listOf(block),
            arguments = mapOf("key" to "result.旧.结果"), inputTemplate = "{{character.name}}")
        val workflow = Workflow(id = "workflow", title = "", nodes = listOf(node))
        val main = MainCall(id = "main", name = "", workflows = listOf(workflow))
        val entry = PromptEntry(id = "entry", key = "人物", text = "保持正文", category = "背景", enabled = false)
        val card = CharacterCard(id = "card", namespace = "旧世界", name = "", entries = listOf(entry), greeting = "开场白")
        val session = Session(id = "session", state = mapOf("score" to "7"), results = mapOf("旧.结果" to "原结果"),
            messages = listOf(ChatMessage(id = "message", text = "历史内容")))
        val original = Library(characters = listOf(card), mainCalls = listOf(main), sessions = listOf(session))
        val normalized = MacroNaming.normalize(original)
        val changedCard = normalized.characters.single()
        val changedMain = normalized.mainCalls.single()
        val changedWorkflow = changedMain.workflows.single()
        val changedNode = changedWorkflow.nodes.single()
        val changedBlock = changedNode.blocks.single()
        assertEquals(card, changedCard.copy(slug = "", entries = listOf(entry)))
        assertEquals(entry, changedCard.entries.single().copy(title = "", slug = ""))
        assertEquals(block, changedBlock.copy(slug = ""))
        assertEquals(node, changedNode.copy(slug = "", blocks = listOf(block)))
        assertEquals(workflow, changedWorkflow.copy(slug = "", nodes = listOf(node)))
        assertEquals(main, changedMain.copy(slug = "", workflows = listOf(workflow), history = main.history))
        assertSame(original.sessions, normalized.sessions)
        assertSame(original.savedChats, normalized.savedChats)
        assertEquals("人物", changedCard.entries.single().title)
        assertTrue(MacroNaming.isValidSlug(changedNode.slug))
        assertSame(normalized, MacroNaming.normalize(normalized))
    }

    @Test fun missingAliasesAreDeterministicAndReserveExplicitSiblingAliases() {
        val entries = listOf(PromptEntry(id = "first", key = "name"),
            PromptEntry(id = "explicit", key = "other", title = "自选名称", slug = "name"),
            PromptEntry(id = "unicode", key = "中文"))
        val nodes = listOf(WorkflowNode(id = "n1", outputKey = "shared"), WorkflowNode(id = "n2", outputKey = "shared"))
        val main = MainCall(id = "main", workflows = listOf(Workflow(id = "call", nodes = nodes)))
        val original = Library(packages = listOf(PromptPackage(id = "pack", namespace = "world", entries = entries)),
            mainCalls = listOf(main), personas = listOf(UserPersona(id = "persona")),
            promptTags = listOf(PromptTag(id = "tag", name = "标签")))
        val normalized = MacroNaming.normalize(original)
        assertEquals(normalized, MacroNaming.normalize(original))
        assertEquals("world", normalized.packages.single().slug)
        assertEquals(listOf("name_2", "name"), normalized.packages.single().entries.take(2).map { it.slug })
        assertEquals("自选名称", normalized.packages.single().entries[1].title)
        assertTrue(MacroNaming.isValidSlug(normalized.packages.single().entries.last().slug))
        assertEquals(listOf("shared", "shared_2"), normalized.mainCalls.single().workflows.single().nodes.map { it.slug })
        assertEquals(nodes.map { it.outputKey }, normalized.mainCalls.single().workflows.single().nodes.map { it.outputKey })
        assertTrue(MacroNaming.isValidSlug(normalized.mainCalls.single().history.processor.workflow.slug))
        assertTrue(MacroNaming.isValidSlug(normalized.personas.single().slug))
        assertTrue(MacroNaming.isValidSlug(normalized.promptTags.single().slug))
        assertSame(normalized, MacroNaming.normalize(normalized))
        val withNewTag = MacroNaming.normalize(normalized.copy(promptTags = normalized.promptTags + PromptTag(name = "新标签")))
        assertSame(normalized.packages.single(), withNewTag.packages.single())
        assertSame(normalized.mainCalls.single(), withNewTag.mainCalls.single())
        assertSame(normalized.personas.single(), withNewTag.personas.single())
        assertSame(normalized.promptTags.single(), withNewTag.promptTags.first())
        val basic = PromptEntry(id = "basic", key = "profile")
        val basicCard = MacroNaming.normalize(CharacterCard(entries = listOf(basic)))
        assertEquals("角色概览", basicCard.entries.single().title)
        assertEquals(basic.key, basicCard.entries.single().key)
    }

    @Test fun validationRejectsExplicitConflictsWithinTheirActualOwnerScopes() {
        val blocks = listOf(PromptBlock(id = "b1", slug = "prompt"), PromptBlock(id = "b2", slug = "prompt"))
        val nodes = listOf(WorkflowNode(id = "n1", slug = "output", blocks = blocks), WorkflowNode(id = "n2", slug = "output"))
        val first = Workflow(id = "w1", slug = "call", nodes = nodes)
        val main = MainCall(id = "m1", slug = "source", workflows = listOf(first))
        val duplicateHistory = main.history.copy(processor = main.history.processor.copy(
            workflow = main.history.processor.workflow.copy(slug = "call")))
        val entries = listOf(PromptEntry(id = "e1", key = "first", slug = "entry"), PromptEntry(id = "e2", key = "second", slug = "entry"))
        val library = Library(mainCalls = listOf(main.copy(history = duplicateHistory), MainCall(id = "m2", slug = "source")),
            packages = listOf(PromptPackage(id = "p", namespace = "world", entries = entries)))
        val normalized = MacroNaming.normalize(library)
        val paths = metadataIssues(normalized).map { it.path }.toSet()
        assertTrue(paths.containsAll(listOf("library.mainCalls.slugs", "main.m1.workflows.slugs",
            "workflow.w1.nodes.slugs", "workflow.w1.node.n1.blocks.slugs", "pack.p.entries.slugs")))
        assertEquals(listOf("source", "source"), normalized.mainCalls.map { it.slug })
        val independent = Library(characters = listOf(CharacterCard(namespace = "hero", name = "角色", entries = emptyList(), slug = "same")),
            packages = listOf(PromptPackage(namespace = "pack", name = "包", slug = "same")),
            groups = listOf(CallGroup(name = "组", workflows = listOf(Workflow(slug = "same")), slug = "same")))
        assertTrue(metadataIssues(independent).isEmpty())
        val legacy = Library(packages = listOf(PromptPackage(namespace = "legacy", name = "旧包",
            entries = listOf(PromptEntry(key = "old")))))
        assertTrue(metadataIssues(legacy).isEmpty())
    }

    @Test fun validationChecksCatalogAndAssignmentsOnEveryMacroBearingType() {
        val unknown = listOf("missing", "missing")
        val library = Library(promptTags = listOf(PromptTag(id = "tag", name = "标签", slug = "Invalid"),
            PromptTag(id = "tag", name = "", slug = "valid")),
            personas = listOf(UserPersona(id = "persona", slug = "bad.slug", tagIds = unknown)),
            packages = listOf(PromptPackage(id = "p", entries = listOf(PromptEntry(id = "entry", tagIds = unknown)))),
            groups = listOf(CallGroup(workflows = listOf(Workflow(id = "call", nodes = listOf(
                WorkflowNode(id = "node", tagIds = unknown, blocks = listOf(PromptBlock(id = "block", tagIds = unknown)))))))))
        val paths = metadataIssues(library).map { it.path }.toSet()
        assertTrue(paths.containsAll(listOf("library.promptTagIds", "tag.tag.name", "tag.tag.slug", "persona.persona.slug",
            "persona.persona.tagIds", "entry.entry.tagIds", "workflow.call.node.node.tagIds", "workflow.call.node.node.block.block.tagIds")))
        assertEquals("bad.slug", MacroNaming.normalize(library).personas.single().slug)
    }

    @Test fun tagRenamesKeepStableAssignmentsAndTogglingOnlyChangesSelection() {
        val tag = PromptTag(id = "tag", name = "背景", slug = "world")
        val entry = PromptEntry(id = "entry", key = "info", tagIds = listOf(tag.id))
        val library = Library(promptTags = listOf(tag), packages = listOf(
            PromptPackage(id = "pack", namespace = "world", name = "世界", entries = listOf(entry))))
        val renamed = MacroNaming.normalize(library.copy(promptTags = listOf(tag.copy(name = "人物背景", slug = "character_background"))))
        assertEquals(listOf(tag.id), renamed.packages.single().entries.single().tagIds)
        assertTrue(metadataIssues(renamed).isEmpty())
        assertEquals(listOf("other", tag.id), MacroNaming.toggleTag(listOf("other"), tag.id))
        assertEquals(listOf("other"), MacroNaming.toggleTag(listOf("other", tag.id), tag.id))
    }

    private fun metadataIssues(library: Library): List<Issue> = LibraryValidator.validate(library).filter {
        it.path.endsWith(".slug") || it.path.endsWith(".slugs") || it.path.endsWith(".tagIds") ||
            it.path == "library.promptTagIds" || it.path.startsWith("tag.")
    }
}
