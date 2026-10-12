package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DefaultPromptSlotsTest {
    private fun slot(enabled: Boolean = true) = PromptBlock(id = "slot", title = "世界背景", slug = "world_background",
        injectionSlot = true, enabled = enabled, slotReferences = listOf("{{state.world}}"))
    private fun model(block: PromptBlock = slot()) = WorkflowNode(id = "model-node", visible = true,
        inputTemplate = "", includeHistory = false, blocks = listOf(
            PromptBlock(id = "base", title = "Base", template = "BASE"), block,
            PromptBlock(id = "tail", title = "Tail", template = "TAIL")))
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main",
        state = mapOf("world" to "REFERENCE"), messages = listOf(ChatMessage(id = "turn", text = "question")))
    private fun library(session: Session, node: WorkflowNode, flows: List<Workflow> = emptyList(),
                        groups: List<CallGroup> = emptyList(), extensions: List<ConfigurationExtension> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Card")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", specialDefault = true, primaryWorkflowId = "primary",
            workflows = listOf(Workflow(id = "primary", nodes = listOf(node))) + flows)),
        groups = groups, extensions = extensions, profiles = listOf(ModelProfile(id = "profile", model = "offline")),
        selectedProfileId = "profile", sessions = listOf(session), selectedSessionId = session.id)
    private fun injector(id: String, priority: Int, trigger: String = RuntimeEvents.TURN_PREPARE, contextOrder: Int? = null) = Workflow(
        id = "flow-$id", title = id, trigger = trigger, queued = true, priority = priority,
        nodes = listOf(WorkflowNode(id = "node-$id", title = id, actionId = "prompt.inject", inputTemplate = id,
            outputKey = "result-$id", includeHistory = false, arguments = mapOf("id" to id,
                "targetWorkflowId" to "primary", "targetNodeId" to "model-node", "targetBlockId" to "slot") +
                contextOrder?.let { mapOf("contextOrder" to it.toString()) }.orEmpty())))

    @Test fun fillMissingPreservesOrdinaryCollisionsAndReadonlySourcesAreValidated() {
        val existing = PromptBlock(id = "ordinary", title = "世界背景", slug = "world_background", template = "keep me")
        val node = WorkflowNode(id = "model", blocks = listOf(existing))
        val filled = DefaultPromptSlots.ensureMissing(node)
        assertEquals(existing, filled.blocks.first())
        assertEquals(4, filled.blocks.count { it.injectionSlot })
        assertEquals(filled, DefaultPromptSlots.ensureMissing(filled))
        assertEquals(filled.blocks.size, filled.blocks.map { it.slug }.distinct().size)
        assertEquals(filled.blocks.size, filled.blocks.map { it.title }.distinct().size)
        assertNull(DefaultPromptSlots.referenceError("{{history.text}}"))
        assertNotNull(DefaultPromptSlots.referenceError("{{history}}"))
        assertNotNull(DefaultPromptSlots.referenceError("prefix {{state.world}}"))
        assertNotNull(DefaultPromptSlots.referenceError("{{state.world}}{{input}}"))
        val malformed = slot().copy(template = "forbidden", processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "x")))
        assertEquals(2, DefaultPromptSlots.blockIssues(malformed, "slot").size)
        val start = session()
        val source = library(start, model())
        val target = ExtensionFieldRef(ExtensionResourceKind.MAIN, "main", ExtensionField.BLOCK_TEMPLATE,
            workflowId = "primary", nodeId = "model-node", blockId = "slot")
        assertNull(ExtensionFieldRegistry.describe(source, target))
        assertTrue(DefaultPromptSlots.blockIssues(slot(), "slot", 9).any { it.message.contains("不支持") })
    }

    @Test fun existingCustomSlotSetIsPreservedWithoutAddingDefaultSlots() {
        val importedSlots = listOf(
            PromptBlock(id = "gray_g11_slot_world", title = "世界观与环境 · 接收槽", injectionSlot = true),
            PromptBlock(id = "gray_g11_slot_characters", title = "其他角色 · 接收槽", injectionSlot = true),
            PromptBlock(id = "gray_g11_slot_rules", title = "其他规则 · 接收槽", injectionSlot = true),
            PromptBlock(id = "gray_g11_slot_other", title = "其他资料 · 接收槽", injectionSlot = true),
        )
        val node = WorkflowNode(id = "gray_g11_generate", actionId = "model.generate", blocks = importedSlots)

        val preserved = DefaultPromptSlots.ensureMissing(node)

        assertEquals(importedSlots, preserved.blocks)
        assertEquals(importedSlots.map { it.id }, preserved.blocks.map { it.id })
        assertEquals(4, preserved.blocks.count { it.injectionSlot })
        assertEquals(preserved, DefaultPromptSlots.ensureMissing(preserved))
    }

    @Test fun cleanupCandidatesExcludeConfiguredAndReferencedDefaultSlots() {
        val imported = PromptBlock(id = "gray_g11_slot_world", title = "世界观与环境 · 接收槽", injectionSlot = true)
        val defaults = DefaultPromptSlots.definitions.mapIndexed { index, definition ->
            PromptBlock(id = "default-$index", title = definition.name, slug = definition.slug, injectionSlot = true)
        }
        val configured = defaults[2].copy(slotReferences = listOf("{{state.world}}"))
        val similarName = PromptBlock(id = "similar", title = "额外规则（注入槽1）", slug = "extra_rules_slot_1", injectionSlot = true)
        val node = WorkflowNode(id = "model-node", actionId = "model.generate", blocks = listOf(imported, similarName) +
            defaults.take(2) + configured + defaults.last())
        val configuredTarget = WorkflowNode(id = "group-inject", actionId = "prompt.inject",
            arguments = mapOf("targetBlockId" to " ${defaults[0].id} "))
        val group = CallGroup(id = "group", name = "Group", workflows = listOf(Workflow(id = "inject",
            nodes = listOf(configuredTarget))))
        val start = session().copy(promptInjections = listOf(PromptInjection("session-inject", PromptBlock(),
            ResultOrigin("group", "inject", "group-inject"), targetBlockId = defaults[1].id)))
        val source = library(start, node, groups = listOf(group))

        assertEquals(listOf(defaults.last().id), DefaultPromptSlots.unusedDefaultDuplicates(node, source))
        assertTrue(DefaultPromptSlots.unusedDefaultDuplicates(WorkflowNode(id = "only-defaults", actionId = "model.generate",
            blocks = defaults), source).isEmpty())
    }

    @Test fun slotReferencesPrecedeStableLiteralSourcesAndSlotAndPluginDisablingSuppressThem() {
        val nodes = (1..3).map { WorkflowNode(id = "producer-$it", actionId = "text.render", outputKey = "producer-$it") }
        val group = CallGroup(id = "group", name = "Group", workflows = listOf(Workflow(id = "producers", nodes = nodes)))
        val plugin = ConfigurationExtension(id = "plugin", name = "Plugin",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)))
        val injections = listOf(3, 2, 1).map { number -> PromptInjection("inject-$number",
            PromptBlock(template = "L$number {{state.world}}", role = Role.USER, position = PromptPosition.AFTER_HISTORY),
            ResultOrigin(group.id, "producers", "producer-$number"), "primary", "model-node",
            InjectionScope.SESSION, pluginOwnerIds = listOf(plugin.id), targetBlockId = "slot",
            queueOrder = 99 - number, contextOrder = if (number == 1) 1 else 2) }
        val start = session().copy(enabledExtensionIds = listOf(plugin.id), promptInjections = injections)
        val node = model()
        val source = library(start, node, groups = listOf(group), extensions = listOf(plugin))
        fun preview(chat: Session, selected: WorkflowNode = node) = PromptCompiler().preview(source, chat, selected, "question")
        val expected = listOf("BASE", "REFERENCE", "L1 {{state.world}}", "L2 {{state.world}}", "L3 {{state.world}}", "TAIL")
        val first = preview(start)
        assertTrue(first.issues.toString(), first.issues.none { it.error })
        assertEquals(listOf(expected.joinToString("\n")), first.messages.map { it.text })
        assertTrue(first.messages.all { it.role == Role.SYSTEM })
        assertEquals(listOf(expected.joinToString("\n")), preview(start.copy(promptInjections = listOf(injections[1], injections[0], injections[2]))).messages.map { it.text })
        assertEquals(listOf("BASE\nTAIL"), preview(start, model(slot(false))).messages.map { it.text })
        assertEquals(listOf("BASE\nREFERENCE\nTAIL"), preview(PluginParticipation.disable(start, plugin.id)).messages.map { it.text })
    }

    @Test fun contextOrderOpposesExecutionPrioritiesAndRemainsStableWhenSchedulingChanges() = runBlocking {
        var requests = 0
        var prompt: List<ChatMessage> = emptyList()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests++; prompt = messages; return "BODY"
            }
        }
        val start = session()
        val flows = (4 downTo 1).map { injector("I$it", it, contextOrder = 5 - it) }
        val source = library(start, model(), flows)
        val result = WorkflowEngine().run(source, start, "question", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        val expected = listOf("BASE", "REFERENCE", "I4", "I3", "I2", "I1", "TAIL")
        assertEquals(listOf(expected.joinToString("\n")), prompt.map { it.text })
        assertEquals(listOf("I1", "I2", "I3", "I4"), result.promptInjections.map { it.id })
        assertEquals(listOf(4, 3, 2, 1), result.promptInjections.map { it.contextOrder })
        assertTrue(result.promptInjections.all { it.queueOrder == 0 })
        assertEquals(1, requests)
        val changed = library(start, model(), flows.map { it.copy(priority = 5 - it.priority) })
        val rescheduled = WorkflowEngine().run(changed, start, "question", gateway)
        assertEquals(rescheduled.traces.toString(), RunStatus.SUCCESS, rescheduled.status)
        assertEquals(listOf("I4", "I3", "I2", "I1"), rescheduled.promptInjections.map { it.id })
        assertEquals(listOf(expected.joinToString("\n")), prompt.map { it.text })
        val sameWave = injector("late", 0, RuntimeEvents.USER_MESSAGE)
        val refused = WorkflowEngine().run(library(start, model(), listOf(sameWave)), start, "question", gateway)
        assertTrue(refused.traces.toString(), refused.traces.any { it.detail.contains("必须早于目标模型编译") })
        assertTrue(refused.promptInjections.isEmpty())
    }

    @Test fun disabledSlotIsSaveableButRuntimeTargetNeverFallsBackAndReferencesProtectItsOwner() = runBlocking {
        val start = session()
        val flow = injector("source", 1)
        val node = model(slot(false))
        val source = library(start, node, listOf(flow))
        assertTrue(LibraryValidator.validate(source).toString(), LibraryValidator.validate(source).none { it.error })
        val actions = ActionRegistry.defaults().snapshot()
        assertNotNull(injectionTarget(source.mainCalls.single(), actions, "primary", "model-node", "slot", 10, requireEnabled = false))
        assertNull(injectionTarget(source.mainCalls.single(), actions, "primary", "model-node", "slot", 10))
        assertNull(injectionTarget(source.mainCalls.single(), actions, "primary", "model-node", "missing", 10))
        assertNull(injectionTarget(source.mainCalls.single(), actions, "primary", "model-node", "slot", 9))
        val uses = ResourceUsageIndex.usages(source, ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, "main"),
            workflowId = "primary", nodeId = "model-node", blockId = "slot"))
        assertTrue(uses.any { it.path.endsWith(".argument.targetBlockId") })
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) = "BODY"
        }
        val result = WorkflowEngine().run(source, start, "question", gateway)
        assertTrue(result.traces.toString(), result.traces.any { it.status == "error" })
        assertTrue(result.promptInjections.isEmpty())
    }

    @Test fun capturedWire17Execution9RemainsRunnableWithoutAddingOrInterpretingSlots() = runBlocking {
        val start = session()
        val oldNode = model().copy(blocks = listOf(PromptBlock(id = "old", title = "Old", template = "OLD")))
        val old = library(start, oldNode).copy(schemaVersion = 17)
        val world = CompletedWorlds.capture(old, start, "question", 9)
        val restored = start.copy(executableWorld = world)
        val effective = CompletedWorlds.executionLibrary(old, restored)
        assertEquals(LibraryFormat.CURRENT_VERSION, effective.schemaVersion)
        assertEquals(17, world.library.schemaVersion)
        assertEquals(9, world.executionVersion)
        assertFalse(effective.mainCalls.single().workflows.single().nodes.single().blocks.any { it.injectionSlot })
        var requests = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests++; assertEquals(listOf("OLD"), messages.map { it.text }); return "BODY"
            }
        }
        val result = WorkflowEngine().run(old, restored, "question", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(1, requests)
        val newFields = oldNode.copy(blocks = listOf(slot()))
        val unsupported = PromptCompiler().preview(effective, restored, newFields, "question", executionVersion = 9)
        assertTrue(unsupported.issues.any { it.message.contains("不支持") })
    }

    @Test fun defaultOrderPersistsAndExecution10KeepsItsStoredQueueInterpretation() = runBlocking {
        val start = session()
        var prompt: List<ChatMessage> = emptyList()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                prompt = messages; return "BODY"
            }
        }
        val source = library(start, model(), listOf(injector("default", 7)))
        val result = WorkflowEngine().run(source, start, "question", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(1, result.promptInjections.single().contextOrder)
        assertEquals(0, result.promptInjections.single().queueOrder)
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        val roundTrip = LibraryFormat.decode(LibraryFormat.encode(source.copy(sessions = listOf(committed))))
        assertEquals(LibraryFormat.CURRENT_VERSION, roundTrip.schemaVersion)
        assertEquals(1, roundTrip.sessions.single().promptInjections.single().contextOrder)
        val invalid = injector("invalid", 1, contextOrder = 0)
        assertTrue(LibraryValidator.validate(library(start, model(), listOf(invalid))).any {
            it.path.endsWith(".argument.contextOrder") && it.error
        })

        val legacySource = library(start, model(), listOf(injector("A", 1, contextOrder = 2), injector("B", 2, contextOrder = 1)))
        val oldWorld = CompletedWorlds.capture(legacySource.copy(schemaVersion = 18), start, "question", 10)
        val oldSession = start.copy(executableWorld = oldWorld)
        val oldResult = WorkflowEngine().run(legacySource, oldSession, "question", gateway)
        assertEquals(oldResult.traces.toString(), RunStatus.SUCCESS, oldResult.status)
        assertEquals(listOf("BASE", "REFERENCE", "A", "B", "TAIL"), prompt.map { it.text })
        assertEquals(listOf(1, 2), oldResult.promptInjections.map { it.queueOrder })
        assertTrue(oldResult.promptInjections.all { it.contextOrder == null })
        // Explicit old-version compilation ignores even a later-added context metadata field.
        val changedMetadata = oldResult.promptInjections.mapIndexed { index, injection -> injection.copy(contextOrder = 2 - index) }
        val oldPreview = PromptCompiler().preview(CompletedWorlds.executionLibrary(legacySource, oldSession),
            oldSession.copy(promptInjections = changedMetadata), model(), "question", executionVersion = 10)
        assertEquals(listOf("BASE", "REFERENCE", "A", "B", "TAIL"), oldPreview.messages.map { it.text })
        val newPreview = PromptCompiler().preview(legacySource, start.copy(promptInjections = oldResult.promptInjections),
            model(), "question", executionVersion = 11)
        assertEquals(listOf("BASE", "REFERENCE", "A", "B", "TAIL"), newPreview.messages.map { it.text })
    }
}
