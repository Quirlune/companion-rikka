package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CharacterGroupBindingsTest {
    private fun base() = LibraryFormat.upgrade(Library(
        characters = listOf(CharacterCard(id = "hero", namespace = "hero", name = "Hero")),
        groups = listOf(CallGroup(id = "feature", name = "Feature")),
    ))

    @Test fun inheritedGroupsLoadOnceWithPackagesDefaultsAndRealActions() = runBlocking {
        val call = Workflow(id = "feature_call", title = "Feature", trigger = "turn.prepare", nodes = listOf(
            WorkflowNode(id = "feature_node", actionId = "text.render", includeHistory = false,
                inputTemplate = "{{pack.lore.fact}}|{{state.feature_mode}}", outputKey = "feature_note")))
        val shared = CallGroup(id = "feature", name = "Feature", workflows = listOf(call),
            boundPackageIds = listOf("lore"), stateDefaults = mapOf("feature_mode" to "quiet"))
        val primary = Workflow(id = "main_call", title = "Main", nodes = listOf(
            WorkflowNode(id = "main_node", actionId = "text.render", inputTemplate = "reply", includeHistory = false,
                outputKey = "reply", visible = true)))
        val session = Session(id = "chat", characterId = "hero", mainCallId = "main", groupIds = listOf("feature"),
            messages = listOf(ChatMessage(id = "turn", text = "hello")))
        val library = LibraryFormat.upgrade(base().copy(
            characters = base().characters.map { it.copy(boundGroupIds = listOf("feature")) } +
                CharacterCard(id = "other", namespace = "other", name = "Other"),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(primary),
                primaryWorkflowId = primary.id, boundGroupIds = listOf("feature"))),
            groups = listOf(shared), packages = listOf(PromptPackage(id = "lore", namespace = "lore", name = "Lore",
                entries = listOf(PromptEntry(id = "fact", key = "fact", text = "home")))),
            sessions = listOf(session), selectedSessionId = session.id))
        assertEquals(listOf("feature"), library.loadedCallGroups(session).map { it.id })
        assertEquals(listOf("lore"), library.loadedPromptPackages(session).map { it.id })
        val onlyCharacter = session.copy(groupIds = emptyList())
        val noMainBinding = library.copy(mainCalls = library.mainCalls.map { it.copy(boundGroupIds = emptyList()) })
        assertEquals(listOf("feature"), noMainBinding.loadedCallGroups(onlyCharacter).map { it.id })
        assertTrue(noMainBinding.loadedCallGroups(onlyCharacter.copy(characterId = "other")).isEmpty())
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                error("This repair must not request a model")
        }
        val result = WorkflowEngine().run(noMainBinding, onlyCharacter, "hello", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("home|quiet", result.results["feature_note"])
        assertEquals("feature", result.resultOrigins["feature_note"]?.resourceId)
        assertTrue(onlyCharacter.groupIds.isEmpty())
        assertTrue(session.state.isEmpty())
    }

    @Test fun configBindsCharacterGroupsAndStrictUndoPreservesLaterChat() {
        val library = base()
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"bind","kind":"character","id":"hero","groups":["feature"]}]}""", library)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), library)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        val next = requireNotNull(applied.library)
        assertEquals(listOf("feature"), next.characters.single().boundGroupIds)
        val chat = Session(id = "later", characterId = "hero", messages = listOf(ChatMessage(text = "keep")))
        val undone = ConfigurationCommands.undo(requireNotNull(applied.receipt), next.copy(sessions = listOf(chat)))
        assertTrue(undone.issues.toString(), undone.issues.none { it.error })
        assertEquals(library.characters, undone.library?.characters)
        assertEquals(listOf(chat), undone.library?.sessions)
        val changed = next.copy(characters = next.characters.map { it.copy(boundGroupIds = emptyList()) })
        assertNull(ConfigurationCommands.undo(applied.receipt!!, changed).library)
        val rejected = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"bind","kind":"group","id":"feature","groups":[]}]}""", library)
        assertTrue(rejected.issues.any { it.error })
    }

    @Test fun wire15CardsDefaultToNoGroupsAndWire16RoundTripsBindings() {
        val current = base()
        val image = LibraryFormat.json.encodeToJsonElement(Library.serializer(), current) as JsonObject
        val oldCard = (image["characters"] as JsonArray).single() as JsonObject
        val old = JsonObject(image + mapOf("schemaVersion" to JsonPrimitive(15),
            "characters" to JsonArray(listOf(JsonObject(oldCard.filterKeys { it != "boundGroupIds" })))))
        val decoded = LibraryFormat.decode(old)
        assertTrue(decoded.characters.single().boundGroupIds.isEmpty())
        assertEquals(current.characters, decoded.characters)
        val bound = current.copy(characters = current.characters.map { it.copy(boundGroupIds = listOf("feature")) })
        assertEquals(bound, LibraryFormat.decode(LibraryFormat.encode(bound)))
    }

    @Test fun groupUsageGuardsDeletionAndExplicitRemovalClearsOnlyTheBinding() {
        val library = base().let { it.copy(characters = it.characters.map { card -> card.copy(boundGroupIds = listOf("feature")) }) }
        val target = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, "feature"))
        val usage = ResourceUsageIndex.usages(library, target).single()
        assertEquals(ExtensionResourceKind.CHARACTER, usage.location?.resource?.kind)
        assertEquals("boundGroupIds", usage.location?.section)
        assertFalse(ResourceEditCommands.previewDelete(library, target).canApply)
        val plan = ResourceEditCommands.previewDelete(library, target, removeConfiguredAssociations = true)
        assertTrue(plan.issues.toString(), plan.canApply)
        val removed = ResourceEditCommands.apply(plan, library)
        assertTrue(removed.issues.toString(), removed.issues.none { it.error })
        assertTrue(removed.library!!.groups.isEmpty())
        assertEquals(library.characters.single().copy(boundGroupIds = emptyList()), removed.library!!.characters.single())
        assertEquals(library, ConfigurationCommands.undo(requireNotNull(removed.receipt), removed.library!!).library)
        val invalid = library.copy(groups = emptyList())
        assertTrue(LibraryValidator.validate(invalid).any { it.path == "character.hero.boundGroupIds" && it.error })
    }
}
