package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoryExecutionResourcesTest {
    private fun fixture(): Library {
        val leaf = Workflow(id = "small", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS,
            nodes = listOf(
                WorkflowNode(id = "prefix", actionId = "text.render", inputTemplate = "prefix", includeHistory = false,
                    outputKey = "memory_prefix", recordTrajectory = false,
                    outputProcessing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "|{{pack.shared.value}}"))),
                WorkflowNode(id = "small-model", dependsOn = listOf("prefix"), inputTemplate = "{{history.pending}}",
                    includeHistory = false, outputKey = "small_summary",
                    blocks = listOf(PromptBlock(id = "small-task", template = "MEMORY {{pack.shared.value}} {{character.name}}")))))
        val ordinary = Workflow(id = "ordinary-group", title = "Ordinary group", nodes = listOf(
            WorkflowNode(id = "ordinary-node", actionId = "text.render", inputTemplate = "OLD-CALL {{pack.shared.value}}",
                outputKey = "ordinary_group", includeHistory = false)))
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(leaf, ordinary))
        val processor = HistoryProcessor(workflow = leaf, outputKey = "small_summary", minimumNewFloors = 3,
            keepRecentFloors = 0, maximumBatchFloors = 2)
        val extension = ConfigurationExtension(id = "memory", name = "Memory",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
            memory = MemoryPlugins.rules(group.id, processor))
        val main = MainCall(id = "main", name = "Main", boundPackageIds = listOf("shared"), workflows = listOf(
            Workflow(id = "chat", title = "Chat", nodes = listOf(WorkflowNode(id = "reply", actionId = "text.render",
                inputTemplate = "{{pack.shared.value}} {{character.name}}", includeHistory = false,
                outputKey = "ordinary_main", visible = true, recordTrajectory = false)))))
        val session = Session(id = "session", mainCallId = main.id, characterId = "character",
            enabledExtensionIds = listOf(extension.id), messages = (1..7).map { index ->
                ChatMessage(id = "floor-$index", role = if (index % 2 == 1) Role.USER else Role.ASSISTANT,
                    text = "BODY-$index", createdAt = index.toLong()) })
        val library = Library(characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "OldHero")),
            packages = listOf(PromptPackage(id = "shared", namespace = "shared", name = "Shared",
                entries = listOf(PromptEntry(id = "value", key = "value", text = "OLD")))),
            mainCalls = listOf(main), groups = listOf(group), extensions = listOf(extension),
            profiles = listOf(ModelProfile(id = "profile", model = "offline", apiConnectionId = "connection")),
            connections = listOf(ApiConnection(id = "connection")), selectedProfileId = "profile",
            sessions = listOf(session), selectedSessionId = session.id)
        return requireNotNull(GlobalMemory.ensureGlobal(library).library)
    }

    private fun current(old: Library): Library {
        val unrelated = CallGroup(id = "readable-only", name = "Readable dependency", workflows = listOf(
            Workflow(id = "must-not-run", title = "Dependency", nodes = listOf(WorkflowNode(id = "dependency-node",
                actionId = "text.render", inputTemplate = "UNEXPECTED", includeHistory = false, outputKey = "unexpected")))))
        return old.copy(characters = old.characters.map { it.copy(name = "CurrentHero") },
            packages = old.packages.map { it.copy(entries = it.entries.map { entry -> entry.copy(text = "CURRENT") }) },
            groups = old.groups.map { group -> group.copy(workflows = group.workflows.map { flow ->
                if (flow.id != "ordinary-group") flow else flow.copy(nodes = flow.nodes.map { it.copy(inputTemplate = "NEW-CALL") })
            }) } + unrelated,
            extensions = old.extensions.map { it.copy(resources = it.resources +
                ExtensionResourceRef(ExtensionResourceKind.GROUP, unrelated.id)) })
    }

    private fun restored(old: Library, live: Library): Session {
        val session = old.sessions.single()
        return CompletedWorlds.withGlobalMemoryForNewTurn(live,
            session.copy(executableWorld = CompletedWorlds.capture(old, session, "old", 17)))
    }

    private fun gateway(calls: MutableList<String>) = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
            calls += messages.joinToString("\n") { it.text }
            return "small accepted"
        }
    }

    @Test fun currentMemoryAndOrdinaryFrozenCallsUseSeparateDependencies() = runBlocking {
        val old = fixture()
        val live = current(old)
        val session = restored(old, live)
        val captured = session.executableWorld!!.library
        val calls = mutableListOf<String>()
        val result = WorkflowEngine().run(live, session, "BODY-7", gateway(calls))
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("OLD OldHero", result.results["ordinary_main"])
        assertEquals("OLD-CALL OLD", result.results["ordinary_group"])
        assertEquals("prefix|CURRENT", result.results["memory_prefix"])
        assertTrue(calls.single().contains("MEMORY CURRENT CurrentHero"))
        assertFalse(result.results.containsKey("unexpected"))
        assertTrue(captured.groups.none { it.id == "readable-only" })
        assertTrue(ExecutionResources.forWorkflow(captured, "small").groups.any { it.id == "readable-only" })
        assertEquals(old.promptTags, captured.promptTags)
    }

    @Test fun replayUsesCapturedViewForPureOutputProcessing() = runBlocking {
        val old = fixture()
        val live = current(old)
        val session = restored(old, live)
        val result = WorkflowEngine().run(live, session, "BODY-7", gateway(mutableListOf()))
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val world = requireNotNull(result.completedWorld)
        assertTrue(world.recipes.any { recipe -> recipe.targets.any { it.key == "memory_prefix" } })
        assertTrue(world.recipes.any { recipe -> recipe.targets.any { it.key == "ordinary_main" } })
        val missing = setOf("memory_prefix", "ordinary_main")
        val branch = session.withRuntime(result.runtimeSnapshot()).copy(executableWorld = world,
            results = result.results - missing, unavailableResultKeys = missing,
            macroHistory = result.macroHistory)
        val replay = BranchReplay.rebuild(branch)
        assertTrue(replay.issues.toString(), replay.session != null)
        assertEquals("prefix|CURRENT", replay.session!!.results["memory_prefix"])
        assertEquals("OLD OldHero", replay.session!!.results["ordinary_main"])
    }

    @Test fun recoveryKeepsDependenciesAndAppliesOnlyExplicitFrozenPromptEdits() = runBlocking {
        val old = fixture()
        val live = current(old)
        val session = restored(old, live)
        val failing = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                throw IllegalStateException("local stub failure")
        }
        val paused = WorkflowEngine().run(live, session, "BODY-7", failing)
        val pending = requireNotNull(paused.recovery)
        val failure = pending.failures.single { it.nodeId == "small-model" }
        val target = RecoveryPromptEdits.node(pending, failure.nodeKey)
        val edited = RecoveryPromptEdits.apply(pending, failure.nodeKey,
            target.blocks.map { if (it.id == "small-task") it.copy(template = "REPAIRED {{pack.shared.value}} {{character.name}}") else it })
        assertEquals(pending.accepted, edited.accepted)
        assertEquals(pending.prepared, edited.prepared)
        assertEquals(pending.frozenResources.packages, edited.frozenResources.packages)
        assertEquals("REPAIRED {{pack.shared.value}} {{character.name}}",
            RecoveryPromptEdits.node(edited, failure.nodeKey).blocks.single { it.id == "small-task" }.template)
        assertEquals(RecoveryPromptEdits.node(edited, failure.nodeKey).blocks,
            edited.frozenResources.groups.single { it.id == "memory-group" }.workflows.single { it.id == "small" }
                .nodes.single { it.id == "small-model" }.blocks)
        val newer = live.copy(packages = live.packages.map { it.copy(entries = it.entries.map { entry -> entry.copy(text = "LATER") }) },
            characters = live.characters.map { it.copy(name = "LaterHero") })
        val calls = mutableListOf<String>()
        val resumed = WorkflowEngine().resume(newer, session.withRuntime(paused.runtimeSnapshot()).copy(pendingRun = edited),
            gateway(calls), edited, RecoveryDecision(RecoveryDecisionKind.RETRY, failure.nodeKey))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertTrue(calls.single().contains("REPAIRED CURRENT CurrentHero"))
        assertFalse(calls.single().contains("LATER") || calls.single().contains("LaterHero"))
    }
}
