package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LibraryFormatTest {
    @Test fun legacyResourcesAndCapturedWorldUpgradeWithoutChangingData() {
        val original = Session(id = "chat", messages = listOf(ChatMessage(id = "floor", text = "input")),
            state = mapOf("count" to "7"), results = mapOf("status" to "kept"))
        val frozen = Library(schemaVersion = 1, characters = listOf(CharacterCard(id = "character", namespace = "hero")),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(Workflow(
                nodes = listOf(WorkflowNode(recordTrajectory = false)))))),
            packages = listOf(PromptPackage(id = "pack", namespace = "设定", name = "世界",
                entries = listOf(PromptEntry(id = "entry", key = "人物", text = "unchanged", category = "cast")))))
        val recovery = RunRecovery(id = "run", sessionId = original.id, turnId = "floor", input = "input",
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = original, frozenResources = frozen)
        val pending = original.copy(pendingRun = recovery)
        val legacy = frozen.copy(sessions = listOf(pending), selectedSessionId = original.id,
            savedChats = listOf(SavedChat(id = "archive", folderId = "folder", snapshot = pending)))
        for (version in 1..5) {
        // Encode the actual legacy shape, rather than a current model with an old version number.
        val oldDocument = withoutMetadata(LibraryFormat.json.encodeToJsonElement(Library.serializer(), legacy))
        val oldWire = oldDocument.toString().replace("\"schemaVersion\":1", "\"schemaVersion\":$version")
            .replace(",\"formatVersion\":1", "").replace(",\"executionVersion\":1", "")
        val restored = LibraryFormat.decode(oldWire)

        assertEquals(LibraryFormat.CURRENT_VERSION, restored.schemaVersion)
        val resumed = restored.sessions.single()
        assertEquals(original.messages, resumed.messages)
        assertEquals(original.state, resumed.state)
        assertEquals(original.results, resumed.results)
        assertEquals(recovery.id, resumed.pendingRun!!.id)
        assertEquals(1, resumed.pendingRun!!.formatVersion)
        assertEquals(1, resumed.pendingRun!!.executionVersion)
        assertEquals(recovery.initialSession, resumed.pendingRun!!.initialSession)
        assertTrue(restored.mainCalls.single().workflows.single().nodes.single().recordTrajectory)
        assertTrue(resumed.pendingRun!!.frozenResources.mainCalls.single().workflows.single().nodes.single().recordTrajectory)
        assertEquals(LibraryFormat.CURRENT_VERSION, resumed.pendingRun!!.frozenResources.schemaVersion)
        assertEquals(resumed, restored.savedChats.single().snapshot)
        assertEquals("", restored.characters.single().name)
        assertEquals("hero", restored.characters.single().namespace)
        assertEquals("设定", restored.packages.single().namespace)
        assertEquals(frozen.packages.single().entries.single().copy(title = "人物", slug = restored.packages.single().entries.single().slug),
            restored.packages.single().entries.single())
        assertTrue(MacroNaming.isValidSlug(restored.packages.single().slug))
        assertEquals(restored.characters, resumed.pendingRun!!.frozenResources.characters)
        assertEquals(restored.packages, resumed.pendingRun!!.frozenResources.packages)
        assertEquals(restored, LibraryFormat.decode(LibraryFormat.encode(restored)))
        assertSame(restored, LibraryFormat.upgrade(restored))

        val futureWire = oldWire.replace("\"schemaVersion\":$version", "\"schemaVersion\":99")
        assertTrue(runCatching { LibraryFormat.decode(futureWire) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun catalogueRenameRetainsStableEntryAssignmentsAndRoundTrips() {
        val tag = PromptTag(id = "tag", name = "登场角色", slug = "cast")
        val entry = PromptEntry(id = "entry", key = "legacy_key", title = "沐瑶", slug = "muyao", tagIds = listOf(tag.id))
        val library = LibraryFormat.upgrade(Library(packages = listOf(
            PromptPackage(id = "pack", namespace = "legacy_pack", name = "世界", entries = listOf(entry))), promptTags = listOf(tag)))
        val renamed = library.copy(promptTags = listOf(tag.copy(name = "人物", slug = "characters")))
        val restored = LibraryFormat.decode(LibraryFormat.encode(renamed))
        assertEquals(renamed, restored)
        assertEquals(listOf(tag.id), restored.packages.single().entries.single().tagIds)
        assertEquals("legacy_key", restored.packages.single().entries.single().key)
        assertSame(library.packages.single(), MacroNaming.normalize(renamed).packages.single())
    }

    @Test fun currentHeadsRawBodyAndPoliciesRoundTrip() {
        val origin = ResultOrigin("main", "flow", "node")
        val current = MacroHead(3, origin, turn = 2, gap = MacroGap(1))
        val display = BodyDisplaySnapshot("body", "<p>visible</p>", MacroTileStyle(fontSize = 18))
        val reply = ChatMessage(role = Role.ASSISTANT, text = "visible", rawText = "<think>raw</think>visible",
            bodyDisplay = display, textRevisions = listOf(TextRevision(id = "draft", text = "draft",
                bodyDisplay = display.copy(html = "<p>draft</p>"))))
        val session = Session(results = mapOf("reply" to "processed"), messages = listOf(reply),
            currentOnlyMacros = mapOf("result.reply" to current), macroHistory = mapOf("result.model" to listOf(
                MacroVersion(1, "processed", origin, rawText = "raw model response"))))
        val node = WorkflowNode(recordTrajectory = false,
            regexOutputCopy = RegexOutputCopy(recordTrajectory = false))
        val library = LibraryFormat.upgrade(Library(sessions = listOf(session), mainCalls = listOf(
            MainCall(name = "Main", workflows = listOf(Workflow(nodes = listOf(node))), bodySourceTileId = "body",
                bodyTextTemplate = "{{result.reply}}", tiles = listOf(MacroTile(id = "body", kind = MacroTileKind.HTML,
                    htmlTemplate = "<p>visible</p>"))))))
        val restored = LibraryFormat.decode(LibraryFormat.encode(library))
        assertEquals(library, restored)
        assertFalse(restored.mainCalls.single().workflows.single().nodes.single().recordTrajectory)
        assertFalse(restored.mainCalls.single().workflows.single().nodes.single().regexOutputCopy.recordTrajectory)
        assertEquals(current, restored.sessions.single().currentOnlyMacros["result.reply"])
        assertEquals(reply.rawText, restored.sessions.single().messages.single().rawText)
        assertEquals(display, restored.sessions.single().messages.single().bodyDisplay)
        assertEquals("raw model response", restored.sessions.single().macroHistory.getValue("result.model").single().rawText)
    }

    private fun withoutMetadata(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::withoutMetadata))
        is JsonObject -> JsonObject(value.filterKeys { key ->
            key !in setOf("slug", "tagIds", "promptTags", "afterWorkflowId", "regexOutputCopy",
                "historyMemory", "historyWrites", "completedWorkflowIds", "htmlTemplate", "htmlCss", "htmlBindings",
                "recordTrajectory", "currentOnlyMacros", "headWrites", "headRemovals", "precedingGap", "rawText",
                "bodySourceTileId", "bodyTextTemplate", "bodyDisplay", "bodyDisplayReceipt") &&
                !(key == "title" && "key" in value && "text" in value && "category" in value)
        }.mapValues { (_, item) -> withoutMetadata(item) })
        else -> value
    }
}
