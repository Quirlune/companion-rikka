package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompletedWorldReplayTest {
    private fun before(input: String = "question") = Session(id = "session", characterId = "card", mainCallId = "main",
        state = mapOf("phase" to "old"), messages = listOf(ChatMessage(id = "turn", role = Role.USER, text = input)))

    private fun library(session: Session, nodes: List<WorkflowNode>, groups: List<CallGroup> = emptyList(),
                        extensions: List<ConfigurationExtension> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Old character")),
        mainCalls = listOf(MainCall(id = "main", name = "Old main", workflows = listOf(Workflow(id = "flow", nodes = nodes)), primaryWorkflowId = "flow")),
        profiles = listOf(ModelProfile(id = "model", model = "offline")), selectedProfileId = "model",
        groups = groups, extensions = extensions, sessions = listOf(session), selectedSessionId = session.id)

    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("Pure replay must never request a model")
    }

    @Test fun oldRegexRebuildUsesCanonicalInputAndCapturedDefinitionsAfterLiveEdits() = runBlocking {
        var requests = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests++; return "ignore:value=7"
            }
        }
        val model = WorkflowNode(id = "model-node", outputKey = "body", visible = true, includeHistory = false)
        val cheap = WorkflowNode(id = "extract", actionId = "text.extract", outputKey = "cheap", visible = false,
            dependsOn = listOf(model.id), inputTemplate = "{{result.body}}", includeHistory = false, recordTrajectory = false,
            inputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_REPLACE, pattern = "^ignore:", replacement = "")),
            arguments = mapOf("pattern" to "value=(\\d+)", "captureGroup" to "1"),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "|{{state.phase}}")))
        val start = before()
        val old = library(start, listOf(model, cheap))
        val result = WorkflowEngine().run(old, start, "question", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("7|old", result.results["cheap"])
        assertEquals("value=7", result.completedWorld!!.recipes.single().canonicalInput)
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        assertNull(committed.executableWorld)
        assertFalse(committed.turnRecords.single().variants.single().after.results.containsKey("cheap"))
        assertEquals("ignore:value=7", committed.turnRecords.single().variants.single().after.results["body"])
        val changed = old.copy(mainCalls = old.mainCalls.map { it.copy(workflows = it.workflows.map { workflow ->
            workflow.copy(nodes = workflow.nodes.map { node -> if (node.id == cheap.id) node.copy(
                arguments = node.arguments + ("pattern" to "NEW=(\\d+)"), inputProcessing = emptyList(),
                outputProcessing = emptyList()) else node })
        }) }, characters = old.characters.map { it.copy(name = "New character") })
        val variant = committed.turnRecords.single().variants.single()
        val fork = MessageOperations.forkAtVariant(committed, "turn", variant.id).session!!
        assertTrue("cheap" in fork.unavailableResultKeys)
        val replay = BranchReplay.rebuild(fork)
        assertTrue(replay.issues.toString(), replay.succeeded)
        assertEquals("7|old", replay.session!!.results["cheap"])
        assertEquals("old", replay.session!!.state["phase"])
        assertFalse("cheap" in replay.session!!.unavailableResultKeys)
        assertEquals("Old character", CompletedWorlds.executionLibrary(changed, replay.session!!).characters.single().name)
        assertEquals(1, requests)
        assertEquals(1, replay.session!!.currentOnlyMacros["result.cheap"]?.version)
        val roundTrip = LibraryFormat.decode(LibraryFormat.encode(old.copy(sessions = listOf(committed))))
        assertEquals(LibraryFormat.CURRENT_VERSION, roundTrip.schemaVersion)
        assertNotNull(roundTrip.sessions.single().turnRecords.single().variants.single().after.completedWorld)
    }

    @Test fun statefulHandlersAndLaterTargetWritersAreRetainedNeverReplayed() = runBlocking {
        var effects = 0
        val registry = ActionRegistry.defaults().register(ActionDescriptor("write.once", "Write once")) {
            effects++
            ActionOutcome("accepted", stateWrites = mapOf("phase" to "side-effect"),
                macroWrites = mapOf("result.shared" to "stateful-value"), emittedEvents = listOf("custom.finished"))
        }
        val cheap = WorkflowNode(id = "cheap", actionId = "text.render", outputKey = "shared",
            inputTemplate = "cheap", includeHistory = false, recordTrajectory = false)
        val effect = WorkflowNode(id = "effect", actionId = "write.once", outputKey = "effect-result",
            dependsOn = listOf(cheap.id), includeHistory = false, recordTrajectory = false)
        val start = before()
        val result = WorkflowEngine(registry).run(library(start, listOf(cheap, effect)), start, "question", noModel)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertTrue(result.completedWorld!!.recipes.isEmpty())
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        val fork = MessageOperations.forkAtVariant(committed, "turn", committed.turnRecords.single().variants.single().id).session!!
        val replay = BranchReplay.rebuild(fork)
        assertTrue(replay.issues.toString(), replay.succeeded)
        assertEquals(1, effects)
        assertEquals("stateful-value", replay.session!!.results["shared"])
        assertEquals("side-effect", replay.session!!.state["phase"])
    }

    @Test fun oldRecordsKeepStoredValuesAndOversizedRecipesFallBackWithoutDataLoss() = runBlocking {
        val legacyVariant = TurnVariant(id = "legacy", after = RuntimeSnapshot(results = mapOf("cheap" to "stored old value")))
        val oldSession = before().copy(results = mapOf("cheap" to "stored old value"), turnRecords = listOf(
            TurnRecord("turn", TurnCheckpoint(RuntimeSnapshot()), listOf(legacyVariant), legacyVariant.id)))
        val oldFork = MessageOperations.forkAtVariant(oldSession, "turn", legacyVariant.id).session!!
        val legacyReplay = BranchReplay.rebuild(oldFork, PluginCodeExecutor { error("No code for old records") })
        assertTrue(legacyReplay.succeeded)
        assertNull(legacyReplay.session!!.executableWorld)
        assertEquals("stored old value", legacyReplay.session!!.results["cheap"])
        val large = "x".repeat(CompletedWorlds.MAX_RECIPE_CHARS + 1)
        val start = before(large)
        val node = WorkflowNode(id = "large", actionId = "text.render", outputKey = "large",
            includeHistory = false, recordTrajectory = false)
        val result = WorkflowEngine().run(library(start, listOf(node)), start, large, noModel)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertTrue(result.completedWorld!!.recipes.isEmpty())
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        assertEquals(large, committed.turnRecords.single().variants.single().after.results["large"])
    }

    @Test fun pluginRecipeRetainsOldCodeAndParticipationWithoutUsingTheLiveDefinition() = runBlocking {
        var codeRuns = 0
        val executor = PluginCodeExecutor { context ->
            codeRuns++
            context.input + if ("OLD" in context.code.source) "|OLD" else "|NEW"
        }
        val entry = WorkflowNode(id = "entry", actionId = "text.render", outputKey = "entry", includeHistory = false,
            recordTrajectory = true)
        val pluginNode = WorkflowNode(id = "plugin-node", actionId = "plugin.run", outputKey = "plugin-result",
            arguments = mapOf("pluginId" to "plugin"), inputTemplate = "{{state.phase}}", includeHistory = false,
            recordTrajectory = false)
        val group = CallGroup(id = "plugin-group", name = "Plugin calls", workflows = listOf(Workflow(id = "plugin-flow", nodes = listOf(pluginNode))))
        val plugin = ConfigurationExtension(id = "plugin", name = "Plugin",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
            code = PluginCode(source = "function execute(context) { return context.input + '|OLD'; }"))
        val start = before().copy(enabledExtensionIds = listOf(plugin.id))
        val old = library(start, listOf(entry), listOf(group), listOf(plugin))
        val result = WorkflowEngine(ActionRegistry.defaults(executor)).run(old, start, "question", noModel)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        val fork = MessageOperations.forkAtVariant(committed, "turn", committed.turnRecords.single().variants.single().id).session!!
        val live = old.copy(extensions = listOf(plugin.copy(code = PluginCode(source = "function execute(context) { return context.input + '|NEW'; }"))))
        val replay = BranchReplay.rebuild(fork, executor)
        assertTrue(replay.issues.toString(), replay.succeeded)
        assertEquals("old|OLD", replay.session!!.results["plugin-result"])
        assertEquals(listOf(plugin.id), replay.session!!.enabledExtensionIds)
        assertEquals(plugin.code, CompletedWorlds.executionLibrary(live, replay.session!!).extensions.single().code)
        assertEquals(2, codeRuns)
        val edited = replay.session!!.copy(results = replay.session!!.results + ("plugin-result" to "manual"))
        val subsequent = BranchReplay.rebuild(edited, executor)
        assertTrue(subsequent.succeeded)
        assertEquals("manual", subsequent.session!!.results["plugin-result"])
        assertEquals("Already restored values must not rerun code", 2, codeRuns)
    }

    @Test fun capturedClosureKeepsPluginMembersAndTheirDependenciesWithoutEnablingThem() {
        val start = before().copy(enabledExtensionIds = listOf("plugin"))
        val entry = WorkflowNode(id = "entry", actionId = "text.render", outputKey = "entry",
            inputTemplate = "{{character.name}}", includeHistory = false)
        val dependency = CallGroup(id = "dependency", name = "Dependency", workflows = listOf(
            Workflow(id = "dependency-flow", nodes = listOf(WorkflowNode(id = "dependency-node", actionId = "text.render")))))
        val pack = PromptPackage(id = "dependency-pack", namespace = "dependency", name = "Dependency pack")
        val memberCard = CharacterCard(id = "member-card", namespace = "member", name = "Member card",
            boundPackageIds = listOf(pack.id), boundGroupIds = listOf(dependency.id))
        val memberMain = MainCall(id = "member-main", name = "Member main", workflows = listOf(
            Workflow(id = "member-flow", afterWorkflowId = "dependency-flow", modelProfileId = "extra-model",
                nodes = listOf(WorkflowNode(id = "member-node", actionId = "text.render")))))
        val plugin = ConfigurationExtension(id = "plugin", name = "Plugin", resources = listOf(
            ExtensionResourceRef(ExtensionResourceKind.CHARACTER, memberCard.id),
            ExtensionResourceRef(ExtensionResourceKind.MAIN, memberMain.id)), parameters = listOf(
            ExtensionParameter(id = "member-field", title = "Member enabled", target = ExtensionFieldRef(
                ExtensionResourceKind.MAIN, memberMain.id, ExtensionField.WORKFLOW_ENABLED, workflowId = "member-flow"))))
        val source = library(start, listOf(entry), listOf(dependency), listOf(plugin)).let { base -> base.copy(
            characters = base.characters + memberCard + CharacterCard(id = "unrelated", namespace = "other", name = "Unrelated"),
            mainCalls = base.mainCalls + memberMain, packages = listOf(pack),
            profiles = base.profiles + ModelProfile(id = "extra-model", model = "extra")) }
        val captured = CompletedWorlds.capture(source, start, "question").library
        assertTrue(LibraryValidator.validate(captured).filter { it.error }.toString(), LibraryValidator.validate(captured).none { it.error })
        assertEquals(setOf("card", memberCard.id), captured.characters.map { it.id }.toSet())
        assertEquals(setOf("main", memberMain.id), captured.mainCalls.map { it.id }.toSet())
        assertEquals(listOf(pack.id), captured.packages.map { it.id })
        assertTrue(captured.profiles.any { it.id == "extra-model" })
        assertTrue(captured.loadedCallGroups(start).isEmpty())
        assertTrue(captured.loadedPromptPackages(start).isEmpty())
        assertTrue(captured.sessions.isEmpty())
    }
}
