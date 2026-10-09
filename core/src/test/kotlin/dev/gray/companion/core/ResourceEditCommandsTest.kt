package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ResourceEditCommandsTest {
    private val pack = ExtensionResourceRef(ExtensionResourceKind.PACKAGE, "pack")
    private val source = ExtensionFieldRef(pack.kind, pack.id, ExtensionField.ENTRY_TEXT, entryId = "entry")
    private val owner = ExtensionFieldRef(ExtensionResourceKind.MAIN, "main", ExtensionField.BLOCK_TEMPLATE,
        workflowId = "flow", nodeId = "node", blockId = "block")

    private fun base(text: String = "{{pack.tone.tone}}") = LibraryFormat.upgrade(Library(
        packages = listOf(PromptPackage(id = "pack", namespace = "tone", name = "Tone", entries = listOf(
            PromptEntry(id = "entry", key = "tone", text = "source")))),
        mainCalls = listOf(MainCall(id = "main", name = "Main", boundPackageIds = listOf("pack"), workflows = listOf(
            Workflow(id = "flow", nodes = listOf(WorkflowNode(id = "node", actionId = "state.set",
                arguments = mapOf("key" to "tone", "value" to "value"), outputKey = "tone", includeHistory = false,
                blocks = listOf(PromptBlock(id = "block", template = text))))))))))

    private fun applied(plan: ResourceMutationPlan, library: Library): ConfigurationApplyResult {
        assertTrue(plan.issues.toString(), plan.canApply)
        val result = ResourceEditCommands.apply(plan, library)
        assertTrue(result.issues.toString(), result.library != null)
        return result
    }

    @Test fun separationRewritesOnlyCapturedUtf16OccurrenceAndStrictReceiptNeverRewindsOtherChanges() {
        val text = "{{category.shared}} 🌙 {{pack.tone.tone}} / {{pack.tone.tone@latest}}"
        val original = base(text)
        val library = original.copy(packages = listOf(original.packages.single().copy(entries = listOf(
            original.packages.single().entries.single().copy(category = "shared", tagIds = listOf("tag"))))),
            promptTags = listOf(PromptTag(id = "tag", name = "Shared", slug = "shared")))
        val start = text.lastIndexOf("{{")
        val plan = ResourceEditCommands.previewSeparate(library, owner, text, start, text.length, source)
        val result = applied(plan, library)
        val next = requireNotNull(result.library)
        val copied = next.packages.single().entries.single { it.id == plan.destination?.entryId }
        assertNotEquals("entry", copied.id)
        assertEquals("source", copied.text)
        assertEquals("", copied.category)
        assertTrue(copied.tagIds.isEmpty())
        assertEquals("{{category.shared}} 🌙 {{pack.tone.tone}} / {{pack.tone.${copied.key}@latest}}", ExtensionFieldRegistry.read(next, owner))
        assertEquals(library.packages.single().entries.single(), next.packages.single().entries.first())
        val unrelated = next.copy(user = next.user.copy(name = "unrelated"))
        val undone = ConfigurationCommands.undo(requireNotNull(result.receipt), unrelated)
        assertNotNull(undone.library)
        assertEquals("unrelated", undone.library?.user?.name)
        assertEquals(library.packages, undone.library?.packages)
        val changed = ExtensionFieldRegistry.write(next, source, "changed later", "source").library!!
        assertNull(ConfigurationCommands.undo(result.receipt!!, changed).library)
        assertNull(ResourceEditCommands.apply(plan, library.copy(packages = listOf(library.packages.single().copy(name = "source changed")))).library)
    }

    @Test fun separationRejectsStaleHistoricalDynamicAndSelfDependentSourcesButStagesSameResourceDraft() {
        for (text in listOf("{{pack.tone.tone@version-1}}", "{{result.tone}}", "{{history}}")) {
            val library = base(text)
            assertFalse(ResourceEditCommands.previewSeparate(library, owner, text, 0, text.length, source).canApply)
        }
        val library = base()
        assertFalse(ResourceEditCommands.previewSeparate(library, owner, "stale", 0, 5, source).canApply)
        assertFalse(ResourceEditCommands.previewSeparate(library, owner, "{{pack.tone.tone}}", 1, 18, source).canApply)
        val self = library.copy(packages = listOf(library.packages.single().copy(entries = listOf(
            library.packages.single().entries.single().copy(isTemplate = true, text = "{{pack.tone.tone}}")))))
        assertFalse(ResourceEditCommands.previewSeparate(self, owner, "{{pack.tone.tone}}", 0, "{{pack.tone.tone}}".length, source).canApply)
        val second = PromptEntry(id = "consumer", key = "consumer", isTemplate = true, text = "{{pack.tone.tone}}")
        val unfinished = library.copy(packages = listOf(library.packages.single().copy(name = "", entries = library.packages.single().entries + second)))
        val draftOwner = source.copy(entryId = "consumer")
        val draft = ResourceEditCommands.previewSeparateDraft(unfinished, draftOwner, second.text, 0, second.text.length, source)
        assertNotNull(draft.draftLibrary)
        assertFalse(draft.canApply)
        assertNull(ResourceEditCommands.apply(draft, unfinished).library)
        assertEquals(3, draft.draftLibrary?.packages?.single()?.entries?.size)
        assertNull(ResourceEditCommands.previewSeparateDraft(library, owner, second.text, 0, second.text.length, source).draftLibrary)
        val broad = library.copy(groups = listOf(CallGroup(id = "bulk", name = "Bulk", workflows = listOf(
            Workflow(id = "bulk-flow", nodes = listOf(library.mainCalls.single().workflows.single().nodes.single().copy(
                id = "bulk-node", blocks = listOf(PromptBlock(id = "bulk-block", template = "{{type:pack.in:*}}")))))))))
        assertTrue(ResourceEditCommands.previewSeparate(broad, owner, second.text, 0, second.text.length, source).issues.any { "批量引用" in it.message })
        val beforeBroad = ResourceEditCommands.previewSeparate(library, owner, second.text, 0, second.text.length, source)
        assertTrue(ResourceEditCommands.apply(beforeBroad, broad).issues.any { "批量引用" in it.message })
        val charText = "{{character.profile}}"
        val characterLibrary = LibraryFormat.upgrade(base(charText).copy(characters = listOf(CharacterCard(id = "hero", namespace = "hero", name = "Hero"))))
        val characterField = ExtensionFieldRef(ExtensionResourceKind.CHARACTER, "hero", ExtensionField.ENTRY_TEXT,
            entryId = characterLibrary.characters.single().entries.single { it.key == "profile" }.id)
        val beforeAmbiguity = ResourceEditCommands.previewSeparate(characterLibrary, owner, charText, 0, charText.length, characterField)
        assertTrue(beforeAmbiguity.issues.toString(), beforeAmbiguity.canApply)
        val ambiguous = LibraryFormat.upgrade(characterLibrary.copy(characters = characterLibrary.characters + CharacterCard(id = "other", namespace = "other", name = "Other")))
        assertTrue(ResourceEditCommands.apply(beforeAmbiguity, ambiguous).issues.any { "来源解析" in it.message })
        val dependency = PromptPackage(id = "dependency", namespace = "dependency", name = "Dependency",
            entries = listOf(PromptEntry(id = "dependency-entry", key = "value", text = "literal")))
        val beforeCycle = LibraryFormat.upgrade(library.copy(packages = listOf(library.packages.single().copy(entries = listOf(
            library.packages.single().entries.single().copy(isTemplate = true, text = "{{pack.dependency.value}}"))), dependency)))
        val guarded = ResourceEditCommands.previewSeparate(beforeCycle, owner, second.text, 0, second.text.length, source)
        assertTrue(guarded.issues.toString(), guarded.canApply)
        val cycle = beforeCycle.copy(packages = beforeCycle.packages.map { if (it.id != "dependency") it else it.copy(entries = listOf(
            it.entries.single().copy(isTemplate = true, text = "{{pack.tone.tone}}"))) })
        assertTrue(ResourceEditCommands.apply(guarded, cycle).issues.any { "依赖已改变" in it.message })
    }

    @Test fun copyRemapsInternalGraphHistoryTilesAndKeepsCollectionsIndependent() {
        fun checked(result: ExtensionEditResult): Library {
            assertNotNull(result.issues.joinToString("\n") { "${it.path}: ${it.message}" }, result.library)
            return requireNotNull(result.library)
        }
        var library = base()
        val first = library.mainCalls.single().workflows.single().nodes.single()
        val second = first.copy(id = "second", slug = "second", dependsOn = listOf(first.id), blocks = emptyList(), outputKey = "second")
        val original = library.mainCalls.single()
        val tile = MacroTile(id = "tile", template = "{{result.tone}}")
        library = LibraryFormat.upgrade(library.copy(mainCalls = listOf(original.copy(primaryWorkflowId = "flow", bodySourceTileId = "tile", tiles = listOf(tile),
            workflows = listOf(original.workflows.single().copy(nodes = listOf(first, second)),
                Workflow(id = "after", title = "After", afterWorkflowId = "flow", nodes = emptyList()))))))
        library = checked(ExtensionCommands.createDefinition(library, ConfigurationExtension(id = "ext", name = "Ext", resources = listOf(pack))))
        val sourceLocation = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, "main"))
        val plan = ResourceEditCommands.previewCopy(library, sourceLocation)
        val next = applied(plan, library).library!!
        val copied = next.mainCalls.single { it.id == plan.destination?.resource?.id }
        val old = library.mainCalls.single()
        assertEquals(old, next.mainCalls.first { it.id == old.id })
        assertNotEquals(old.workflows.first().id, copied.workflows.first().id)
        assertEquals(copied.workflows.first().id, copied.primaryWorkflowId)
        assertEquals(copied.workflows.first().id, copied.workflows.last().afterWorkflowId)
        assertEquals(listOf(copied.workflows.first().nodes.first().id), copied.workflows.first().nodes.last().dependsOn)
        assertNotEquals(old.history.processor.workflow.id, copied.history.processor.workflow.id)
        assertEquals(copied.tiles.single().id, copied.bodySourceTileId)
        assertEquals("{{result.tone}}", copied.tiles.single().template)
        assertTrue(copied.extensionBindings.isEmpty())
        assertTrue(copied.extensionBindingOrigins.isEmpty())
        assertEquals(library.extensions, next.extensions)
        assertEquals(listOf("pack"), copied.boundPackageIds)
        assertTrue(plan.changes.any { "共享" in it })
        val namespaceCollision = LibraryFormat.upgrade(base().copy(characters = listOf(CharacterCard(id = "character", name = "Character", namespace = "tone_copy"))))
        val packagePlan = ResourceEditCommands.previewCopy(namespaceCollision, ResourceLocation(pack))
        val copiedPackage = applied(packagePlan, namespaceCollision).library!!.packages.single { it.id == packagePlan.destination!!.resource.id }
        assertEquals("tone_copy_2", copiedPackage.namespace)
    }

    @Test fun deletionListsStaticAndConversationBlockersAndCleansConfiguredOwnersAtomically() {
        var library = base()
        val target = ResourceLocation(pack)
        assertTrue(ResourceEditCommands.previewDelete(library, target, true).issues.any { "静态引用" in it.message })
        val unused = PromptEntry(id = "unused", key = "unused", text = "not referenced")
        val loaded = LibraryFormat.upgrade(library.copy(packages = listOf(library.packages.single().copy(entries = library.packages.single().entries + unused)),
            sessions = listOf(Session(id = "loaded-session", mainCallId = "main", packageIds = listOf("pack")))))
        val unusedTarget = ResourceLocation(pack, entryId = unused.id)
        val unusedPlan = ResourceEditCommands.previewDelete(loaded, unusedTarget)
        assertTrue(unusedPlan.issues.toString(), unusedPlan.canApply)
        val removedUnused = applied(unusedPlan, loaded).library!!
        assertEquals(loaded.sessions, removedUnused.sessions)
        assertFalse(ResourceEditCommands.previewDelete(loaded, ResourceLocations.fromField(source)).canApply)
        val recorded = loaded.copy(sessions = listOf(loaded.sessions.single().copy(macroHistory = mapOf(
            "pack.tone.unused" to listOf(MacroVersion(1, "accepted", ResultOrigin("main", "flow", "node")))))))
        assertTrue(ResourceEditCommands.previewDelete(recorded, unusedTarget).usages.any { it.kind == ResourceUseKind.ACCEPTED_WORLD })
        assertFalse(ResourceEditCommands.previewDelete(recorded, unusedTarget).canApply)
        library = library.copy(mainCalls = listOf(library.mainCalls.single().copy(workflows = emptyList())))
        library = ExtensionCommands.createDefinition(library, ConfigurationExtension("ext", "Ext", listOf(pack), listOf(ExtensionParameter("parameter", "Tone", source)))).library!!
        assertFalse(ResourceEditCommands.previewDelete(library, target).canApply)
        val plan = ResourceEditCommands.previewDelete(library, target, true)
        val result = applied(plan, library)
        val next = result.library!!
        assertTrue(next.packages.isEmpty())
        assertTrue(next.mainCalls.single().boundPackageIds.isEmpty())
        assertTrue(next.extensions.single().resources.isEmpty())
        assertTrue(next.extensions.single().parameters.isEmpty())
        assertEquals(library, ConfigurationCommands.undo(result.receipt!!, next).library)
        val session = Session(id = "session", mainCallId = "main", packageIds = listOf("pack"))
        val active = library.copy(sessions = listOf(session))
        assertTrue(ResourceEditCommands.previewDelete(active, target, true).usages.any { it.kind == ResourceUseKind.SESSION })
        val saved = library.copy(savedChats = listOf(SavedChat(id = "saved", folderId = "folder", snapshot = session)))
        assertTrue(ResourceUsageIndex.usages(saved, target).any { it.kind == ResourceUseKind.SAVED_CHAT })
        assertNull(ResourceEditCommands.apply(plan, active).library)
    }

    @Test fun acceptedOriginsRemainProtectedEvenWhenNoSessionAssociationLoadsResource() {
        val library = base().copy(mainCalls = emptyList())
        val origin = ResultOrigin("pack", "flow", "node")
        val session = Session(id = "session", resultOrigins = mapOf("value" to origin), results = mapOf("value" to "accepted"))
        val protected = library.copy(sessions = listOf(session))
        val target = ResourceLocation(pack)
        val usages = ResourceUsageIndex.usages(protected, target)
        assertTrue(usages.any { it.kind == ResourceUseKind.ACCEPTED_WORLD && it.path == "session.session.runtime" })
        assertFalse(ResourceEditCommands.previewDelete(protected, target, true).canApply)
        for (prefix in listOf("result", "macro")) {
            val usedProducer = base("{{$prefix.produced}}").copy(groups = listOf(CallGroup(id = "producer", name = "Producer", workflows = listOf(
                Workflow(id = "producer-flow", nodes = listOf(WorkflowNode(id = "producer-node", outputKey = "produced")))))))
            val producer = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, "producer"))
            assertTrue(ResourceEditCommands.previewDelete(usedProducer, producer, true).usages.any {
                it.kind == ResourceUseKind.TEMPLATE && it.location?.field == owner
            })
            assertFalse(ResourceEditCommands.previewDelete(usedProducer, producer, true).canApply)
        }
    }

    @Test fun issueLocationsRetainNestedIdsAndDraftWritesDoNotWeakenFinalValidation() {
        val library = base()
        val blockIssue = Issue("workflow.flow.node.node.block.block", "bad template")
        assertEquals(owner, ResourceLocations.resolveIssue(library, blockIssue)?.field)
        val missing = owner.copy(blockId = "missing")
        val failure = ExtensionFieldRegistry.writeDraft(library, missing, "new", "old")
        assertNull(failure.library)
        assertEquals(missing, ResourceLocations.resolveIssue(library, failure.issues.single())?.field)
        val invalid = library.copy(packages = listOf(library.packages.single().copy(name = "")))
        assertNotNull(ExtensionFieldRegistry.writeDraft(invalid, source, "new", "source").library)
        assertNull(ExtensionFieldRegistry.write(invalid, source, "new", "source").library)
        assertNull(ConfigurationCommands.previewEdit(library, library.copy(sessions = listOf(Session()))).plan)
        val secondProducer = library.copy(groups = listOf(CallGroup(id = "group", name = "Other", workflows = listOf(
            Workflow(id = "other-flow", nodes = listOf(WorkflowNode(id = "other-node", outputKey = "tone")))))))
        assertEquals(2, ResourceLocations.resolveReference(secondProducer, owner, "{{result.tone}}").size)
        val character = LibraryFormat.upgrade(library.copy(characters = listOf(CharacterCard(id = "character", name = "Character", namespace = "character"))))
        val basicEnabled = ExtensionFieldRef(ExtensionResourceKind.CHARACTER, "character", ExtensionField.ENTRY_ENABLED,
            entryId = character.characters.single().entries.single { it.key == "profile" }.id)
        assertEquals(listOf("true"), ExtensionFieldRegistry.describe(character, basicEnabled)!!.choices.map { it.value })
        val disabledDraft = ExtensionFieldRegistry.writeDraft(character, basicEnabled, "false", "true")
        assertNull(disabledDraft.library)
        assertTrue(disabledDraft.issues.single().message.contains("固定启用"))
        assertEquals(basicEnabled, ResourceLocations.resolveIssue(character, disabledDraft.issues.single())?.field)
        assertNull(ExtensionFieldRegistry.write(character, basicEnabled, "false", "true").library)
    }
}
