package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class ExtensionCommandsTest {
    private val packRef = ExtensionResourceRef(ExtensionResourceKind.PACKAGE, "pack")
    private val groupRef = ExtensionResourceRef(ExtensionResourceKind.GROUP, "group")
    private fun base(manual: Boolean = false): Library = LibraryFormat.upgrade(Library(
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList(), boundPackageIds = if (manual) listOf("pack") else emptyList())),
        packages = listOf(PromptPackage(id = "pack", namespace = "tone", name = "Tone", entries = listOf(PromptEntry(id = "entry", key = "tone", text = "old")))),
        groups = listOf(CallGroup(id = "group", name = "Group", stateDefaults = linkedMapOf("mood" to "calm", "story.phase" to "start"),
            workflows = listOf(Workflow(id = "flow", nodes = listOf(
                WorkflowNode(id = "node", actionId = "state.set", arguments = mapOf("key" to "tone", "value" to "{{pack.tone.tone}}"), outputKey = "tone", includeHistory = false)
            )))))
    ))
    private fun ok(result: ExtensionEditResult): Library {
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        return requireNotNull(result.library)
    }
    private fun define(library: Library, id: String, refs: List<ExtensionResourceRef> = listOf(packRef)): Library =
        ok(ExtensionCommands.createDefinition(library, ConfigurationExtension(id, id, refs)))

    @Test fun collectionsNeedNoMainAndNeverCreateInstallationOrLoadingState() {
        val initial = base().copy(mainCalls = emptyList())
        var library = define(initial, "first", listOf(packRef, groupRef))
        library = define(library, "second")
        assertTrue(library.mainCalls.isEmpty())
        assertEquals(initial.packages, library.packages)
        assertEquals(initial.groups, library.groups)
        assertEquals(initial.sessions, library.sessions)
        assertEquals(listOf("first", "second"), library.extensions.map { it.id })
    }

    @Test fun deletingOrRecomposingCustomMemoryCollectionPreservesUsedResourcesAndBindings() {
        val initial = base(true).let { it.copy(mainCalls = listOf(it.mainCalls.single().copy(boundGroupIds = listOf("group")))) }
        var library = ok(ExtensionCommands.createDefinition(initial, ConfigurationExtension("custom-memory", "记忆", listOf(packRef, groupRef))))
        library = ok(ExtensionCommands.updateDefinition(library, library.extensions.single().copy(resources = listOf(groupRef))))
        assertEquals(initial.mainCalls, library.mainCalls)
        val legacy = library.copy(mainCalls = library.mainCalls.map { it.copy(
            extensionBindings = listOf(ExtensionBinding("binding", "custom-memory", true)),
            extensionBindingOrigins = listOf(ExtensionBindingOrigin(groupRef.kind, groupRef.id, false, listOf("binding")))) })
        val deleted = ok(ExtensionCommands.deleteDefinition(legacy, "custom-memory"))
        assertTrue(deleted.extensions.isEmpty())
        assertEquals(initial.packages, deleted.packages)
        assertEquals(initial.groups, deleted.groups)
        assertEquals(initial.mainCalls, deleted.mainCalls)
        assertFalse(ResourceEditCommands.previewDelete(deleted, ResourceLocation(packRef)).canApply)
        assertTrue(ExtensionCommands.validate(deleted.copy(mainCalls = listOf(deleted.mainCalls.single().copy(boundGroupIds = emptyList())))).isEmpty())
    }

    @Test fun publicFieldsWriteActualValuesAndLiteralExistingMacroKeysWithPreciseStaleFailures() {
        val target = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, "pack", ExtensionField.ENTRY_TEXT, entryId = "entry")
        val macro = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.GROUP_STATE_DEFAULT, argDescriptorKey = "story.phase")
        var library = ok(ExtensionCommands.createDefinition(base(), ConfigurationExtension("extension", "Extension", listOf(packRef, groupRef), listOf(
            ExtensionParameter("parameter", "Tone", target), ExtensionParameter("macro", "Phase", macro)))))
        library = ok(ExtensionCommands.setParameter(library, "extension", "parameter", "gentle", "old"))
        assertEquals("gentle", library.packages.single().entries.single().text)
        assertNull(ExtensionCommands.setParameter(library, "extension", "parameter", "stale", "old").library)
        assertNull(ExtensionFieldRegistry.describe(library, target.copy(entryId = "gone")))
        assertNull(ExtensionFieldRegistry.describe(library, target.copy(nodeId = "extraneous")))
        assertNull(ExtensionFieldRegistry.write(library, target.copy(field = ExtensionField.ENTRY_ENABLED), "yes", "true").library)
        val workflowModel = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.WORKFLOW_MODEL, workflowId = "flow")
        assertTrue(ExtensionFieldRegistry.describe(library, workflowModel)!!.nullable)
        assertNull(ExtensionFieldRegistry.read(library, workflowModel))
        assertNotNull(ExtensionFieldRegistry.write(library, workflowModel, null, null).library)
        assertNull(ExtensionFieldRegistry.write(library, workflowModel, "missing-profile", null).library)
        val argument = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.NODE_ARGUMENT, workflowId = "flow", nodeId = "node", argDescriptorKey = "value")
        assertEquals(ActionFieldType.TEMPLATE, ExtensionFieldRegistry.describe(library, argument)!!.type)
        assertNull(ExtensionFieldRegistry.describe(library, argument.copy(argDescriptorKey = "not-registered")))
        assertEquals(ActionFieldType.STRING, ExtensionFieldRegistry.describe(library, macro)!!.type)
        assertTrue(ExtensionFieldRegistry.describe(library, macro)!!.title.contains("state.story.phase"))
        library = ok(ExtensionCommands.setParameter(library, "extension", "macro", "{{pack.tone.tone}}", "start"))
        assertEquals("{{pack.tone.tone}}", library.groups.single().stateDefaults["story.phase"])
        assertEquals(listOf("mood", "story.phase"), library.groups.single().stateDefaults.keys.toList())
        assertNull(ExtensionFieldRegistry.writeDraft(library, macro, "stale", "start").library)
        val absent = macro.copy(argDescriptorKey = "missing.key")
        val failure = ExtensionFieldRegistry.writeDraft(library, absent, "new", null)
        assertNull(failure.library)
        assertEquals(absent, ResourceLocations.resolveIssue(library, failure.issues.single())?.field)
        assertFalse(library.groups.single().stateDefaults.containsKey("missing.key"))
        assertTrue(ExtensionCommands.validate(library.copy(packages = emptyList())).any { it.message.contains("不存在") })
    }

    @Test fun configurationEditsOnlyCollectionWhileLegacyImportsKeepExplicitAssociationsAndStrictUndo() {
        val original = define(base(true), "extension")
        assertEquals(original, LibraryFormat.decode(LibraryFormat.encode(original)))
        val definition = original.extensions.single().copy(resources = listOf(groupRef))
        val encoded = LibraryFormat.json.encodeToJsonElement(ConfigurationExtension.serializer(), definition)
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","resources":{"extensions":[$encoded]}}""", original)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(setOf("extension"), preview.changes.map { it.kind }.toSet())
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), original)
        assertTrue(applied.issues.toString(), applied.issues.isEmpty())
        assertEquals(original.mainCalls, applied.library!!.mainCalls)
        val receipt = requireNotNull(applied.receipt)
        assertEquals(original, ConfigurationCommands.undo(receipt, applied.library!!).library)
        val laterMain = applied.library!!.copy(mainCalls = applied.library!!.mainCalls.map { it.copy(description = "later main edit") })
        assertEquals(laterMain.mainCalls, ConfigurationCommands.undo(receipt, laterMain).library!!.mainCalls)
        val editedDefinition = applied.library!!.copy(extensions = listOf(definition.copy(name = "later collection edit")))
        assertNull(ConfigurationCommands.undo(receipt, editedDefinition).library)
        assertNull(ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[{"op":"remove","kind":"group","id":"group"}]}""", applied.library!!).plan)
        for (alreadyAssociated in listOf(false, true)) {
            val before = base().let { library -> library.copy(mainCalls = library.mainCalls.map {
                it.copy(boundGroupIds = if (alreadyAssociated) listOf("group") else emptyList())
            }) }
            val definitionImage = LibraryFormat.json.encodeToJsonElement(ConfigurationExtension.serializer(), ConfigurationExtension("new-extension", "New", listOf(groupRef)))
            val legacyMain = before.mainCalls.single().copy(boundGroupIds = listOf("group"),
                extensionBindings = listOf(ExtensionBinding("new-binding", "new-extension", true)),
                extensionBindingOrigins = listOf(ExtensionBindingOrigin(ExtensionResourceKind.GROUP, "group", false, listOf("new-binding"))))
            val mainImage = LibraryFormat.json.encodeToJsonElement(MainCall.serializer(), legacyMain)
            val combined = ConfigurationCommands.preview("""{"format":"companion.config.v1","resources":{"extensions":[$definitionImage],"mainCalls":[$mainImage]}}""", before)
            assertTrue(combined.issues.toString(), combined.issues.isEmpty())
            val imported = ConfigurationCommands.apply(requireNotNull(combined.plan), before)
            assertTrue(imported.issues.toString(), imported.issues.isEmpty())
            val actual = requireNotNull(imported.library)
            assertEquals(listOf("group"), actual.mainCalls.single().boundGroupIds)
            assertTrue(actual.mainCalls.single().extensionBindings.isEmpty())
            assertTrue(actual.mainCalls.single().extensionBindingOrigins.isEmpty())
            assertEquals(before, ConfigurationCommands.undo(requireNotNull(imported.receipt), actual).library)
        }
    }

    @Test fun runtimeProjectionIgnoresCollectionMetadataButRetainsActualAssociationsAndFieldValues() {
        val baseline = base()
        val original = baseline.mainCalls.single()
        val defined = define(baseline, "extension")
        val legacy = original.copy(extensionBindings = listOf(ExtensionBinding("legacy", "extension", true)))
        assertEquals(MacroNaming.runtimeShape(original), MacroNaming.runtimeShape(legacy))
        val associated = defined.copy(mainCalls = listOf(legacy.copy(boundPackageIds = listOf("pack"))))
        assertNotEquals(MacroNaming.runtimeShape(original), MacroNaming.runtimeShape(associated.mainCalls.single()))
        val stripped = ExtensionCommands.withoutMetadata(associated)
        assertTrue(stripped.extensions.isEmpty())
        assertTrue(stripped.mainCalls.single().extensionBindings.isEmpty())
        assertEquals(associated.mainCalls.single().boundPackageIds, stripped.mainCalls.single().boundPackageIds)
        val changed = associated.packages.single().copy(entries = associated.packages.single().entries.map { it.copy(text = "new") })
        assertNotEquals(MacroNaming.runtimeShape(associated.packages.single()), MacroNaming.runtimeShape(changed))
        assertEquals(1, associated.extensions.size)
    }

    @Test fun existingEngineConsumesExplicitAssociationsAndKeepsStateDefaultsLiteral() = runBlocking {
        var library = define(base(true), "extension", listOf(packRef, groupRef))
        val target = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, "pack", ExtensionField.ENTRY_TEXT, entryId = "entry")
        library = ok(ExtensionFieldRegistry.write(library, target, "gentle", "old"))
        library = library.copy(
            characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            groups = library.groups.map { it.copy(stateDefaults = it.stateDefaults + ("mood" to "{{pack.tone.tone}}")) },
            mainCalls = library.mainCalls.map { main -> main.copy(boundGroupIds = listOf("group"), workflows = listOf(Workflow(
                id = "main-entry", trigger = RuntimeEvents.USER_MESSAGE, nodes = listOf(WorkflowNode(
                    id = "main-render", actionId = "text.render", inputTemplate = "{{input}}", outputKey = "mainReply", includeHistory = false
                ))
            ))) }
        )
        val session = Session(id = "session", characterId = "character", mainCallId = "main", messages = listOf(ChatMessage(role = Role.USER, text = "hello")))
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String = error("No model should be called")
        }
        val result = WorkflowEngine().run(library, session, "hello", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("gentle", result.state["tone"])
        assertEquals("{{pack.tone.tone}}", result.state["mood"])
    }
}
