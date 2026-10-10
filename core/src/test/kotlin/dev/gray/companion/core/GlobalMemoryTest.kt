package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GlobalMemoryTest {
    private fun floors(count: Int) = (1..count).map { index -> ChatMessage(id = "f$index",
        role = if (index % 2 == 1) Role.USER else Role.ASSISTANT, text = "BODY-$index", createdAt = index.toLong()) }

    private fun fixture(count: Int = 15): Library {
        val leaf = Workflow(id = "leaf", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS,
            nodes = listOf(WorkflowNode(id = "leaf-model", outputKey = "small_summary", includeHistory = false,
                inputTemplate = "{{history.pending}}", blocks = listOf(PromptBlock(id = "task-small", template = "SMALL")))))
        val processor = HistoryProcessor(workflow = leaf, outputKey = "small_summary", minimumNewFloors = 5,
            keepRecentFloors = 1, maximumBatchFloors = 4)
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(leaf))
        val extension = ConfigurationExtension(id = "memory", name = "Memory",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)), memory = MemoryPlugins.rules(group.id, processor))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", title = "Chat",
            nodes = listOf(WorkflowNode(id = "reply", actionId = "text.render", visible = true,
                inputTemplate = "reply", includeHistory = false)))))
        val session = Session(id = "s", characterId = "card", mainCallId = main.id, messages = floors(count), enabledExtensionIds = listOf(extension.id))
        val base = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), groups = listOf(group), extensions = listOf(extension), sessions = listOf(session), selectedSessionId = session.id,
            profiles = listOf(ModelProfile(id = "profile", model = "offline", apiConnectionId = "connection")), selectedProfileId = "profile",
            connections = listOf(ApiConnection(id = "connection")))
        val result = GlobalMemory.ensureGlobal(base)
        assertTrue(result.issues.toString(), result.library != null)
        return requireNotNull(result.library)
    }

    private fun withLeaves(library: Library, count: Int, width: Int = 2): Library {
        val session = library.sessions.single()
        val leaf = requireNotNull(GlobalMemory.draft(library)).processor.workflow.id
        val segments = (0 until count).map { index -> HistoryMemory.segment("child $index", "SUMMARY-$index",
            session.messages.subList(index * width, (index + 1) * width), leaf) }
        return library.copy(sessions = listOf(session.copy(historyMemory = HistoryMemory.apply(session.historyMemory, HistoryMemoryPatch(segments = segments)))))
    }

    @Test fun countersExcludeOriginalPrefixAndRetainTriggerTail() {
        var library = fixture(15)
        val draft = requireNotNull(GlobalMemory.draft(library))
        library = GlobalMemory.withDraft(library, draft.copy(processor = draft.processor.copy(
            protectFirstFloors = 4, minimumNewFloors = 12, keepRecentFloors = 2, maximumBatchFloors = 4)))
        val session = library.sessions.single()
        val main = library.mainCalls.single()
        assertNull(HistoryContext(library, session, main).pendingCandidate()) // 11 after protection, not 15.
        val changed = requireNotNull(GlobalMemory.draft(library))
        library = GlobalMemory.withDraft(library, changed.copy(processor = changed.processor.copy(minimumNewFloors = 7)))
        val marked = HistoryMemory.segment("middle", "marked", session.messages.subList(6, 8), "leaf")
        val source = session.copy(historyMemory = HistoryMemory.apply(session.historyMemory, HistoryMemoryPatch(segments = listOf(marked))))
        val candidate = requireNotNull(HistoryContext(library, source, main).pendingCandidate())
        assertEquals(listOf("f5", "f6", "f9", "f10"), candidate.sources.map { it.messageId })
        assertFalse(candidate.sources.any { it.messageId in setOf("f14", "f15") })
        assertEquals(session.messages, source.messages)
    }

    @Test fun originalFloorPromptRangesMarkOnlyReferencedSources() = runBlocking {
        var library = fixture(15)
        val draft = requireNotNull(GlobalMemory.draft(library))
        library = GlobalMemory.withDraft(library, draft.copy(
            processor = draft.processor.copy(protectFirstFloors = 4, minimumNewFloors = 5, keepRecentFloors = 1, maximumBatchFloors = 8),
            smallNode = draft.smallNode.copy(blocks = listOf(PromptBlock(template = "SMALL"), PromptBlock(
                template = "{{type:builtin.in:history.name:messages.source:display.from:start-7.through:start-9}}")))))
        val requests = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests += messages
                return "accepted small"
            }
        }
        val before = library.sessions.single()
        val result = WorkflowEngine().run(library, before, "BODY-15", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val segment = result.historyMemory.segments.values.single()
        assertEquals(listOf("f7", "f8", "f9"), segment.sources.map { it.messageId })
        val sent = requests.single().joinToString("\n") { it.text }
        assertTrue(sent.contains("BODY-7") && sent.contains("BODY-9"))
        assertFalse(sent.contains("BODY-5") || sent.contains("BODY-10") || sent.contains("BODY-15"))
        assertTrue(before.historyMemory.segments.isEmpty())
        val flow = GlobalMemory.draft(library)!!.processor.workflow
        val noHistory = GlobalMemory.modelNode(flow)!!.copy(blocks = listOf(PromptBlock(template = "Only instructions")))
        val action = ActionRegistry.defaults().snapshot().getValue(noHistory.actionId)
        val history = HistoryContext(library, before, library.mainCalls.single())
        assertThrows(NodeExecutionFailure::class.java) {
            InvocationPreparer(library).prepare(PlannedNode(flow, noHistory, action),
                InvocationEnvironment(before, "BODY-15", history, "f15"))
        }
        assertEquals(1, requests.size)
    }

    @Test fun bigSummaryUsesWholeChildrenAndOriginalFloorLimits() {
        val library = withLeaves(fixture(15), 5)
        val session = library.sessions.single()
        val main = library.mainCalls.single()
        val all = session.historyMemory.segments.values.sortedBy { it.sources.first().messageId.removePrefix("f").toInt() }
        val rules = MemoryStageRules(protectFirstFloors = 1, minimumNewFloors = 8, keepRecentFloors = 3, maximumBatchFloors = 4)
        val ids = HistoryActions.selectedIds(HistoryActions.select(session, main, HistorySelection(), "leaf", "UNMERGED_LEAVES",
            99, true, rules, library), "", 0)
        // First child straddles original protection; last two children overlap the retained 3 covered floors.
        // Unsummarized BODY 11..15 must not consume the retained count.
        assertEquals(all.subList(1, 3).map { it.id }, ids)
        assertEquals("", HistoryActions.select(session, main, HistorySelection(), "leaf", "UNMERGED_LEAVES", 0, false,
            rules.copy(maximumBatchFloors = 3), library))
        val scope = MemoryPromptScope(library, session, main, all.flatMap { it.sources }, all)
        val selection = requireNotNull(HistoryViews.selection("type:builtin.in:history.name:messages.source:display.from:start-3.through:start-6"))
        val projection = scope.project(selection)
        assertEquals(listOf("f3", "f4", "f5", "f6"), projection.sourceIds)
        assertEquals(all.subList(1, 3).map { it.id }, scope.manifest(projection.sourceIds).childSegmentIds)
        assertTrue(projection.messages.all { "SUMMARY" in it.text && "BODY-" !in it.text })
        val partial = scope.project(selection.copy(range = HistoryRange(HistoryPoint(value = 4.0), HistoryPoint(value = 6.0))))
        assertEquals(listOf("f5", "f6"), partial.sourceIds)
    }

    @Test fun acceptedLargeSummaryMarksOnlyPromptSelectedChildren() = runBlocking {
        var library = withLeaves(fixture(15), 4)
        val draft = requireNotNull(GlobalMemory.draft(library))
        library = GlobalMemory.withDraft(library, draft.copy(
            processor = draft.processor.copy(minimumNewFloors = 5, keepRecentFloors = 1, maximumBatchFloors = 2),
            largeRules = MemoryStageRules(minimumNewFloors = 8, keepRecentFloors = 0, maximumBatchFloors = 10),
            largeNode = draft.largeNode.copy(blocks = listOf(PromptBlock(template = "LARGE"), PromptBlock(
                template = "{{type:builtin.in:history.name:messages.source:display.from:start-3.through:start-6}}")))))
        val originalChildren = library.sessions.single().historyMemory.segments.values.filter {
            it.sources.first().messageId in setOf("f3", "f5") }.map { it.id }.toSet()
        val largeRequests = mutableListOf<String>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                val text = messages.joinToString("\n") { it.text }
                return if ("LARGE" in text) { largeRequests += text; "accepted large" } else "accepted small"
            }
        }
        val result = WorkflowEngine().run(library, library.sessions.single(), "BODY-15", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val parent = result.historyMemory.segments.values.single { it.childSegmentIds.isNotEmpty() }
        assertEquals(originalChildren, parent.childSegmentIds.toSet())
        assertEquals(listOf("f3", "f4", "f5", "f6"), parent.sources.map { it.messageId })
        assertTrue(largeRequests.single().contains("SUMMARY-1"))
        assertFalse(largeRequests.single().contains("SUMMARY-0") || largeRequests.single().contains("BODY-"))
    }

    @Test fun globalMigrationPreservesIdsAndSessions() {
        val configured = withLeaves(fixture(7), 2)
        val definition = requireNotNull(GlobalMemory.extension(configured))
        val old = configured.copy(memoryConfig = null)
        val upgraded = GlobalMemory.ensureGlobal(old)
        assertTrue(upgraded.issues.toString(), upgraded.library != null)
        val next = requireNotNull(upgraded.library)
        assertEquals(old.sessions, next.sessions)
        assertEquals(definition.id, next.memoryConfig!!.extensionId)
        assertEquals(definition.memory!!.workflowId, GlobalMemory.draft(next)!!.processor.workflow.id)
        val changed = GlobalMemory.setEnabled(next, requireNotNull(next.memoryConfig), false).library!!
        assertEquals(next.sessions, changed.sessions)
        assertNull(MemoryPlugins.binding(changed, changed.sessions.single(), changed.mainCalls.single()))
        val frozen = CapturedResourceClosure.capture(changed, changed.sessions.single())
        assertNotNull(GlobalMemory.extension(frozen))
        assertTrue(GlobalMemory.validate(frozen).isEmpty())
        assertTrue(HistoryActions.read(changed.sessions.single(), changed.mainCalls.single(),
            changed.sessions.single().historyMemory.segments.keys.toList(), changed).contains("SUMMARY"))
        val preview = ConfigurationCommands.previewEdit(next, changed, "memory toggle")
        assertTrue(preview.issues.toString(), preview.plan != null)
        assertTrue(preview.settings.any { it.key == "memoryConfig" })
    }

    @Test fun newTurnOverlayPreservesFrozenResourcesAndCredentialsAcrossEdits() {
        val frozen = fixture(7)
        var live = frozen
        val draft = GlobalMemory.draft(live)!!
        live = GlobalMemory.withDraft(live, draft.copy(generation = draft.generation.copy(profileId = "profile")))
        live = live.copy(profiles = live.profiles.map { it.copy(model = "new-model") },
            connections = live.connections.map { it.copy(baseUrl = "https://example.test/v1") })
        val session = frozen.sessions.single()
        val world = CompletedWorlds.capture(frozen, session, "original", 16)
        val pinned = session.copy(executableWorld = world)
        val updated = CompletedWorlds.withGlobalMemoryForNewTurn(live, pinned)
        val resources = updated.executableWorld!!.library
        assertEquals(world, pinned.executableWorld)
        assertEquals(session.messages, updated.messages)
        assertEquals(world.library.mainCalls, resources.mainCalls)
        assertEquals(world.library.profiles.single { it.id == "profile" }, resources.profiles.single { it.id == "profile" })
        val memoryResources = ExecutionResources.forWorkflow(resources, draft.processor.workflow.id)
        val memoryProfile = GlobalMemory.draft(memoryResources)!!.smallNode.generation.profileId!!
        assertNotEquals("profile", memoryProfile)
        assertEquals("profile", GlobalMemory.credentialOwner(resources, memoryProfile))
        val connection = memoryResources.profiles.single { it.id == memoryProfile }.apiConnectionId!!
        assertEquals("connection", GlobalMemory.credentialOwner(resources, connection))
        val newer = live.copy(profiles = live.profiles.map { it.copy(model = "newer-model") })
        val again = CompletedWorlds.withGlobalMemoryForNewTurn(newer, updated).executableWorld!!.library
        val newerId = GlobalMemory.draft(ExecutionResources.forWorkflow(again, draft.processor.workflow.id))!!.smallNode.generation.profileId!!
        assertNotEquals(memoryProfile, newerId)
        assertTrue(again.profiles.none { it.id == memoryProfile })
        assertEquals("profile", GlobalMemory.credentialOwner(again, newerId))
        assertEquals(resources.profiles.single { it.id == "profile" }, again.profiles.single { it.id == "profile" })
        val disabled = GlobalMemory.overlayGlobal(live.copy(memoryConfig = live.memoryConfig!!.copy(enabled = false)), world.library, pinned)
        assertEquals(world.library.groups, disabled.groups)
        assertEquals(world.library.profiles, disabled.profiles)
        assertNull(disabled.memoryConfig!!.extensionId)
        assertNull(MemoryPlugins.binding(disabled, pinned, disabled.mainCalls.single()))
    }
}
