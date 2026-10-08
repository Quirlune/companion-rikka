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
        groups = listOf(CallGroup(id = "group", name = "Group", workflows = listOf(Workflow(id = "flow", nodes = listOf(
            WorkflowNode(id = "node", actionId = "state.set", arguments = mapOf("key" to "tone", "value" to "{{pack.tone.tone}}"), outputKey = "tone", includeHistory = false)
        )))))
    ))
    private fun ok(result: ExtensionEditResult): Library {
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        return requireNotNull(result.library)
    }
    private fun binding(library: Library, extensionId: String) = library.mainCalls.single().extensionBindings.single { it.extensionId == extensionId }.id
    private fun install(library: Library, id: String, refs: List<ExtensionResourceRef> = listOf(packRef)): Library {
        val defined = ok(ExtensionCommands.createDefinition(library, ConfigurationExtension(id, id, refs)))
        return ok(ExtensionCommands.install(defined, "main", id))
    }
    private fun enabled(library: Library, id: String, value: Boolean = true) = ok(ExtensionCommands.setEnabled(library, "main", binding(library, id), value))

    @Test fun manualAndMultipleOwnersSurviveIndependentDisableAndDirectRemovalIsRejected() {
        for (manual in listOf(false, true)) {
            var library = enabled(install(base(manual), "first"), "first")
            library = enabled(install(library, "second"), "second")
            val main = library.mainCalls.single()
            assertEquals(listOf("pack"), main.boundPackageIds)
            assertEquals(manual, main.extensionBindingOrigins.single().manual)
            assertEquals(2, main.extensionBindingOrigins.single().ownerBindingIds.size)
            val illegal = library.copy(mainCalls = listOf(main.copy(boundPackageIds = emptyList())))
            assertTrue(ExtensionCommands.validate(illegal).any { it.message.contains("first") && it.message.contains("不能直接移除") })
            library = enabled(library, "first", false)
            assertEquals(listOf("pack"), library.mainCalls.single().boundPackageIds)
            library = enabled(library, "second", false)
            assertEquals(if (manual) listOf("pack") else emptyList<String>(), library.mainCalls.single().boundPackageIds)
            assertTrue(library.mainCalls.single().extensionBindingOrigins.isEmpty())
        }
    }

    @Test fun disabledInstallCompositionUpdateAndDetachNeverDeleteResources() {
        var library = install(base(), "extension")
        assertTrue(library.mainCalls.single().boundPackageIds.isEmpty())
        assertFalse(library.mainCalls.single().extensionBindings.single().enabled)
        assertNull(ExtensionCommands.install(library, "main", "extension").library)
        assertNull(ExtensionCommands.deleteDefinition(library, "extension").library)
        library = enabled(library, "extension")
        library = ok(ExtensionCommands.updateDefinition(library, library.extensions.single().copy(resources = listOf(groupRef))))
        assertTrue(library.mainCalls.single().boundPackageIds.isEmpty())
        assertEquals(listOf("group"), library.mainCalls.single().boundGroupIds)
        library = ok(ExtensionCommands.detach(library, "main", binding(library, "extension")))
        assertTrue(library.mainCalls.single().boundGroupIds.isEmpty())
        assertEquals(1, library.packages.size)
        assertEquals(1, library.groups.size)
        assertNotNull(ExtensionCommands.deleteDefinition(library, "extension").library)
    }

    @Test fun publicFieldsWriteRealValuesAndRejectStaleValuesMissingPathsAndWrongTypes() {
        val target = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, "pack", ExtensionField.ENTRY_TEXT, entryId = "entry")
        val parameter = ExtensionParameter("parameter", "Tone", target)
        var library = ok(ExtensionCommands.createDefinition(base(), ConfigurationExtension("extension", "Extension", listOf(packRef), listOf(parameter))))
        library = ok(ExtensionCommands.setParameter(library, "extension", "parameter", "gentle", "old"))
        assertEquals("gentle", library.packages.single().entries.single().text)
        assertNull(ExtensionCommands.setParameter(library, "extension", "parameter", "stale", "old").library)
        assertNull(ExtensionFieldRegistry.describe(library, target.copy(entryId = "gone")))
        assertNull(ExtensionFieldRegistry.describe(library, target.copy(nodeId = "extraneous")))
        val enabled = target.copy(field = ExtensionField.ENTRY_ENABLED)
        assertNull(ExtensionFieldRegistry.write(library, enabled, "yes", "true").library)
        val workflowModel = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.WORKFLOW_MODEL, workflowId = "flow")
        assertTrue(ExtensionFieldRegistry.describe(library, workflowModel)!!.nullable)
        assertNull(ExtensionFieldRegistry.read(library, workflowModel))
        assertNotNull(ExtensionFieldRegistry.write(library, workflowModel, null, null).library)
        assertNull(ExtensionFieldRegistry.write(library, workflowModel, "missing-profile", null).library)
        val argument = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.NODE_ARGUMENT, workflowId = "flow", nodeId = "node", argDescriptorKey = "value")
        assertEquals(ActionFieldType.TEMPLATE, ExtensionFieldRegistry.describe(library, argument)!!.type)
        assertNull(ExtensionFieldRegistry.describe(library, argument.copy(argDescriptorKey = "not-registered")))
        val missing = library.copy(packages = emptyList())
        assertTrue(ExtensionCommands.validate(missing).any { it.message.contains("不存在") })
    }

    @Test fun configurationRoundtripReconcilesDefinitionUpdatesAndStrictUndoRestoresAssociations() {
        val original = enabled(install(base(), "extension"), "extension")
        assertEquals(original, LibraryFormat.decode(LibraryFormat.encode(original)))
        val definition = original.extensions.single().copy(resources = listOf(groupRef))
        val encoded = LibraryFormat.json.encodeToJsonElement(ConfigurationExtension.serializer(), definition)
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","resources":{"extensions":[$encoded]}}""", original)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(setOf("mainCall", "extension"), preview.changes.map { it.kind }.toSet())
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), original)
        assertTrue(applied.issues.toString(), applied.issues.isEmpty())
        assertEquals(listOf("group"), applied.library!!.mainCalls.single().boundGroupIds)
        val receipt = requireNotNull(applied.receipt)
        assertEquals(original, ConfigurationCommands.undo(receipt, applied.library!!).library)
        val edited = applied.library!!.copy(mainCalls = listOf(applied.library!!.mainCalls.single().copy(description = "later edit")))
        assertNull(ConfigurationCommands.undo(receipt, edited).library)
        val missing = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[{"op":"remove","kind":"group","id":"group"}]}""", applied.library!!)
        assertNull(missing.plan)

        // One config may create a definition and explicitly install its owned association on an existing main.
        for (alreadyManual in listOf(false, true)) {
            val before = base().let { library -> library.copy(mainCalls = library.mainCalls.map {
                it.copy(boundGroupIds = if (alreadyManual) listOf("group") else emptyList())
            }) }
            val definitionImage = LibraryFormat.json.encodeToJsonElement(ConfigurationExtension.serializer(),
                ConfigurationExtension("new-extension", "New", listOf(groupRef)))
            val installedMain = before.mainCalls.single().copy(boundGroupIds = listOf("group"),
                extensionBindings = listOf(ExtensionBinding("new-binding", "new-extension", true)),
                extensionBindingOrigins = listOf(ExtensionBindingOrigin(ExtensionResourceKind.GROUP, "group", false, listOf("new-binding"))))
            val mainImage = LibraryFormat.json.encodeToJsonElement(MainCall.serializer(), installedMain)
            val combined = ConfigurationCommands.preview("""{"format":"companion.config.v1","resources":{"extensions":[$definitionImage],"mainCalls":[$mainImage]}}""", before)
            assertTrue(combined.issues.toString(), combined.issues.isEmpty())
            val imported = ConfigurationCommands.apply(requireNotNull(combined.plan), before)
            assertTrue(imported.issues.toString(), imported.issues.isEmpty())
            val actual = requireNotNull(imported.library)
            assertEquals(alreadyManual, actual.mainCalls.single().extensionBindingOrigins.single().manual)
            val disabled = ok(ExtensionCommands.setEnabled(actual, "main", "new-binding", false))
            assertEquals(if (alreadyManual) listOf("group") else emptyList<String>(), disabled.mainCalls.single().boundGroupIds)
            assertEquals(before, ConfigurationCommands.undo(requireNotNull(imported.receipt), actual).library)
        }
    }

    @Test fun runtimeProjectionIgnoresOnlyMetadataAndRetainsActualBindingAndParameterDifferences() {
        val baseline = base()
        val original = baseline.mainCalls.single()
        val installed = install(baseline, "extension")
        assertEquals(MacroNaming.runtimeShape(original), MacroNaming.runtimeShape(installed.mainCalls.single()))
        val active = enabled(installed, "extension")
        assertNotEquals(MacroNaming.runtimeShape(original), MacroNaming.runtimeShape(active.mainCalls.single()))
        val stripped = ExtensionCommands.withoutMetadata(active)
        assertTrue(stripped.extensions.isEmpty())
        assertTrue(stripped.mainCalls.single().extensionBindings.isEmpty())
        assertEquals(active.mainCalls.single().boundPackageIds, stripped.mainCalls.single().boundPackageIds)
        val changed = active.packages.single().copy(entries = active.packages.single().entries.map { it.copy(text = "new") })
        assertNotEquals(MacroNaming.runtimeShape(active.packages.single()), MacroNaming.runtimeShape(changed))
        assertEquals(1, active.extensions.size)
    }

    @Test fun existingEngineConsumesOrdinaryAssociationsAndRealParameterValue() = runBlocking {
        var library = enabled(install(base(), "extension", listOf(packRef, groupRef)), "extension")
        val target = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, "pack", ExtensionField.ENTRY_TEXT, entryId = "entry")
        library = ok(ExtensionFieldRegistry.write(library, target, "gentle", "old"))
        library = library.copy(
            characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            mainCalls = library.mainCalls.map { main -> main.copy(workflows = listOf(Workflow(
                id = "main-entry", trigger = RuntimeEvents.USER_MESSAGE, nodes = listOf(WorkflowNode(
                    id = "main-render", actionId = "text.render", inputTemplate = "{{input}}",
                    outputKey = "mainReply", includeHistory = false
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
    }
}
