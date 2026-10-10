package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class RunPlanLanesTest {
    private val actions = ActionRegistry.defaults().snapshot()
    private fun node(id: String, output: String = id, dependsOn: List<String> = emptyList()) =
        WorkflowNode(id = id, actionId = "text.render", inputTemplate = id, outputKey = output, dependsOn = dependsOn)
    private fun ids(waves: List<List<PlannedNode>>) = waves.map { wave -> wave.map { it.node.id }.toSet() }

    @Test fun newLanesKeepLocalDependenciesAndQueueLevelsWithoutChangingOldTopology() {
        val first = Workflow(id = "first", queued = true, priority = -1,
            nodes = listOf(node("first"), node("tail", dependsOn = listOf("first"))))
        val peer = Workflow(id = "peer", queued = true, priority = -1, nodes = listOf(node("peer")))
        val later = Workflow(id = "later", queued = true, priority = 1, nodes = listOf(node("later")))
        val ordinary = Workflow(id = "ordinary", priority = 999,
            nodes = listOf(node("ordinary"), node("ordinary-tail", dependsOn = listOf("ordinary"))))
        val workflows = listOf(later, ordinary, peer, first)
        val (legacy, oldIssue) = RunPlan.create(workflows, actions, executionVersion = 14)
        assertNull(oldIssue)
        val old = legacy!!.events.getValue(RuntimeEvents.USER_MESSAGE)
        assertEquals(listOf(setOf("peer", "first"), setOf("tail"), setOf("ordinary"), setOf("ordinary-tail"), setOf("later")), ids(old.waves))
        assertTrue(old.parallelWaves.isEmpty())
        assertTrue(old.queuedWaves.isEmpty())
        val (current, currentIssue) = RunPlan.create(workflows, actions, executionVersion = 15)
        assertNull(currentIssue)
        val event = current!!.events.getValue(RuntimeEvents.USER_MESSAGE)
        assertEquals(ids(old.waves), ids(event.waves))
        assertEquals(listOf(setOf("ordinary"), setOf("ordinary-tail")), ids(event.parallelWaves))
        assertEquals(listOf(setOf("peer", "first"), setOf("tail"), setOf("later")), ids(event.queuedWaves))
        val changedPriority = ordinary.copy(priority = -999)
        val changed = RunPlan.create(workflows.map { if (it.id == ordinary.id) changedPriority else it }, actions, 15).first!!
        assertEquals(ids(event.parallelWaves), ids(changed.events.getValue(RuntimeEvents.USER_MESSAGE).parallelWaves))
        assertFalse(ExecutionSemantics(14).independentEventLanes)
        assertTrue(ExecutionSemantics(15).independentEventLanes)
    }

    @Test fun guaranteedCrossLaneWritesAreRejectedEvenAcrossDifferentWavesButLocalSequentialWritesRemain() {
        val ordinary = Workflow(id = "ordinary", nodes = listOf(node("ordinary", "shared")))
        val queued = Workflow(id = "queued", queued = true, priority = 1,
            nodes = listOf(node("head"), node("tail", "shared", dependsOn = listOf("head"))))
        assertNotNull(RunPlan.create(listOf(ordinary, queued), actions, 14).first)
        val (conflicting, issue) = RunPlan.create(listOf(ordinary, queued), actions, 15)
        assertNull(conflicting)
        assertNotNull(issue)
        assertTrue(issue!!.message.contains("同一宏地址"))
        val conditional = queued.copy(condition = Condition(expression = "state('run') == 'yes'"))
        assertNotNull(RunPlan.create(listOf(ordinary, conditional), actions, 15).first)
        val local = Workflow(id = "local", nodes = listOf(node("one", "same"), node("two", "same", listOf("one"))))
        assertNotNull(RunPlan.create(listOf(local), actions, 15).first)
        val independentTail = queued.copy(id = "parallel-tail", queued = false)
        assertNotNull(RunPlan.create(listOf(ordinary, independentTail), actions, 14).first)
        assertNull(RunPlan.create(listOf(ordinary, independentTail), actions, 15).first)
        assertNotNull(RunPlan.create(listOf(ordinary, independentTail.copy(condition = Condition(expression = "false"))), actions, 15).first)
        // Queue levels still impose real order between distinct queued workflows.
        assertNotNull(RunPlan.create(listOf(ordinary.copy(queued = true, priority = 0), queued), actions, 15).first)
        val sameLevelQueued = ordinary.copy(queued = true, priority = queued.priority)
        assertNotNull(RunPlan.create(listOf(sameLevelQueued, queued), actions, 14).first)
        assertNull(RunPlan.create(listOf(sameLevelQueued, queued), actions, 15).first)
        assertNotNull(RunPlan.create(listOf(local.copy(queued = true)), actions, 15).first)

        val stateFirst = node("state-first").copy(actionId = "state.set", arguments = mapOf("key" to "same", "value" to "one"))
        val stateSecond = node("state-second").copy(actionId = "state.set", arguments = mapOf("key" to "same", "value" to "two"))
        assertNull(RunPlan.create(listOf(ordinary.copy(nodes = listOf(stateFirst)), queued.copy(nodes = listOf(stateSecond))), actions, 15).first)
    }

    @Test fun replyCompletedNeedsNoExplicitWorkflowAssociationAndDependenciesStayWorkflowLocal() {
        val reply = Workflow(id = "after-main", trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(node("finish")))
        val plan = RunPlan.create(listOf(reply), actions, 15).first!!
        assertEquals(listOf(setOf("finish")), ids(plan.events.getValue(RuntimeEvents.REPLY_COMPLETED).parallelWaves))
        assertNull(reply.afterWorkflowId)
        assertFalse(ExecutionSemantics(14).primaryReplyCompletion)
        assertTrue(ExecutionSemantics(15).primaryReplyCompletion)
        val queued = Workflow(id = "queued", queued = true, nodes = listOf(node("other")))
        val invalid = Workflow(id = "ordinary", nodes = listOf(node("dependent", dependsOn = listOf("other"))))
        val (result, issue) = RunPlan.create(listOf(queued, invalid), actions, 15)
        assertNull(result)
        assertTrue(issue!!.message.contains("依赖"))
    }

    @Test fun modelPreparationWaitsOnlyForItsOwnedInjectionTargetsAndTheirAncestors() {
        val model = WorkflowNode(id = "model", visible = true, outputKey = "main-reply")
        val primary = Workflow(id = "primary", nodes = listOf(model))
        val second = Workflow(id = "second", nodes = listOf(model.copy(id = "second-model", outputKey = "second-reply")))
        val main = MainCall(id = "main", primaryWorkflowId = primary.id, workflows = listOf(primary, second))
        val ancestor = node("ancestor")
        val inject = node("inject", dependsOn = listOf(ancestor.id)).copy(actionId = "prompt.inject", arguments = mapOf("id" to "slot"))
        val remove = node("remove", dependsOn = listOf(inject.id)).copy(actionId = "prompt.remove", arguments = mapOf("id" to "slot"))
        val preparation = Workflow(id = "preparation", trigger = RuntimeEvents.TURN_PREPARE, queued = true,
            priority = 2, nodes = listOf(ancestor, inject, remove))
        val unrelated = Workflow(id = "unrelated", trigger = RuntimeEvents.TURN_PREPARE, queued = true,
            priority = -1, nodes = listOf(node("unrelated")))
        val groupModel = Workflow(id = "group-model", nodes = listOf(model.copy(id = "group-node", outputKey = "group-reply")))
        val group = CallGroup(id = "group", workflows = listOf(preparation, unrelated, groupModel))
        val session = Session(mainCallId = main.id, groupIds = listOf(group.id))
        val library = Library(mainCalls = listOf(main), groups = listOf(group))
        fun dependencies(actualPreparation: Workflow = preparation, actualSession: Session = session): Map<String, Set<String>> {
            val workflows = main.workflows + listOf(actualPreparation, unrelated, groupModel)
            val plan = RunPlan.create(workflows, actions, 15).first!!
            return plan.preparationDependencies(library.copy(groups = listOf(group.copy(workflows = listOf(actualPreparation, unrelated, groupModel)))), actualSession, main)
        }
        val required = dependencies()
        assertEquals(setOf("preparation/ancestor", "preparation/inject", "preparation/remove"), required["primary/model"])
        assertFalse("second/second-model" in required)
        assertFalse("group-model/group-node" in required)
        assertFalse(required.values.flatten().any { it.startsWith("unrelated/") })
        val dynamic = preparation.copy(nodes = listOf(inject.copy(dependsOn = emptyList(),
            arguments = mapOf("id" to "slot", "targetNodeId" to "{{state.target}}"))))
        val uncertain = dependencies(dynamic)
        assertEquals(setOf("preparation/inject"), uncertain["primary/model"])
        assertEquals(setOf("preparation/inject"), uncertain["second/second-model"])
        assertFalse("group-model/group-node" in uncertain)
        val dynamicId = preparation.copy(nodes = listOf(inject.copy(dependsOn = emptyList(),
            arguments = mapOf("id" to "{{state.patch_id}}"))))
        assertEquals(mapOf("primary/model" to setOf("preparation/inject")), dependencies(dynamicId))
        val dynamicBlock = preparation.copy(nodes = listOf(inject.copy(dependsOn = emptyList(),
            arguments = mapOf("id" to "slot", "targetWorkflowId" to second.id,
                "targetNodeId" to "second-model", "targetBlockId" to "{{state.slot}}"))))
        assertEquals(mapOf("second/second-model" to setOf("preparation/inject")), dependencies(dynamicBlock))
        val disabledSlot = PromptBlock(id = "disabled-slot", enabled = false, injectionSlot = true)
        val slotMain = main.copy(workflows = listOf(primary.copy(nodes = listOf(model.copy(blocks = listOf(disabledSlot)))), second))
        val slotWriter = preparation.copy(nodes = listOf(inject.copy(dependsOn = emptyList(),
            arguments = mapOf("id" to "slot", "targetBlockId" to disabledSlot.id))))
        val slotGroup = group.copy(workflows = listOf(slotWriter))
        val slotPlan = RunPlan.create(slotMain.workflows + slotGroup.workflows, actions, 15).first!!
        assertNull(injectionTarget(slotMain, actions, null, null, disabledSlot.id))
        assertEquals(mapOf("primary/model" to setOf("preparation/inject")), slotPlan.preparationDependencies(
            library.copy(mainCalls = listOf(slotMain), groups = listOf(slotGroup)), session, slotMain))
        val inputModel = model.copy(id = "input-model", outputKey = "input-result")
        val laterModel = model.copy(id = "later-model", outputKey = "later-result", dependsOn = listOf("inject"))
        val ownInjection = inject.copy(dependsOn = listOf(inputModel.id), arguments = mapOf(
            "id" to "{{state.patch_id}}", "targetWorkflowId" to primary.id, "targetNodeId" to laterModel.id))
        val ownFlow = primary.copy(trigger = RuntimeEvents.TURN_PREPARE, nodes = listOf(inputModel, ownInjection, laterModel))
        val ownMain = main.copy(workflows = listOf(ownFlow))
        fun ownDependencies(actual: Workflow) = RunPlan.create(listOf(actual), actions, 15).first!!
            .preparationDependencies(Library(mainCalls = listOf(ownMain.copy(workflows = listOf(actual)))),
                Session(mainCallId = ownMain.id), ownMain.copy(workflows = listOf(actual)))
        val ownExpected = mapOf("primary/later-model" to setOf("primary/input-model", "primary/inject"))
        assertEquals(ownExpected, ownDependencies(ownFlow))
        assertEquals(ownExpected, ownDependencies(ownFlow.copy(nodes = ownFlow.nodes.map {
            if (it.id == ownInjection.id) it.copy(arguments = it.arguments + ("targetNodeId" to "{{state.target}}")) else it
        })))

        val removalOnly = preparation.copy(nodes = listOf(remove.copy(dependsOn = emptyList())))
        assertTrue(dependencies(removalOnly).isEmpty())
        val prior = PromptInjection("slot", PromptBlock(template = "prior"), ResultOrigin(group.id, "former", "former-node"),
            targetWorkflowId = second.id, targetNodeId = "second-model", scope = InjectionScope.SESSION)
        assertEquals(setOf("preparation/remove"), dependencies(removalOnly, session.copy(promptInjections = listOf(prior)))["second/second-model"])
        assertTrue(dependencies(removalOnly, session.copy(messages = listOf(ChatMessage(id = "current-user")),
            promptInjections = listOf(prior.copy(scope = InjectionScope.TURN, turnId = "past-user")))).isEmpty())
        assertEquals(setOf("preparation/remove"), dependencies(removalOnly,
            session.copy(messages = listOf(ChatMessage(id = "current-user")), promptInjections = listOf(prior.copy(
                scope = InjectionScope.TURN, turnId = "current-user"))))["second/second-model"])
        assertTrue(dependencies(removalOnly, session.copy(promptInjections = listOf(prior.copy(origin = prior.origin.copy(resourceId = "another-owner"))))).isEmpty())
        assertTrue(dependencies(preparation.copy(nodes = preparation.nodes.map {
            if (it.id == ancestor.id) it.copy(enabled = false) else it
        })).isEmpty())
        val plugin = ConfigurationExtension(id = "plugin", name = "Plugin", resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)))
        val pluginLibrary = library.copy(extensions = listOf(plugin))
        val pluginPlan = RunPlan.create(main.workflows + group.workflows, actions, 15).first!!
        val pluginSession = session.copy(groupIds = emptyList())
        assertTrue(pluginPlan.preparationDependencies(pluginLibrary, pluginSession, main).isEmpty())
        assertEquals(required, pluginPlan.preparationDependencies(pluginLibrary,
            pluginSession.copy(enabledExtensionIds = listOf(plugin.id)), main))
    }
}
