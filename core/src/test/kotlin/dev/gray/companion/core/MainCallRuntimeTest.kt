package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainCallRuntimeTest {
    private val user = ChatMessage(id = "turn-1", role = Role.USER, text = "hello")
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main", messages = listOf(user))
    private fun library(workflows: List<Workflow>, session: Session = session(), groups: List<CallGroup> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "card", name = "Card")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows, primaryWorkflowId = workflows.first().id)),
        groups = groups, sessions = listOf(session)
    )
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("unexpected model call")
    }

    @Test fun eachCallUsesItsModelAndKeepsItsOutputSlotAndOrigin() = runBlocking {
        val before = Workflow(id = "before", modelProfileId = "other", nodes = listOf(
            WorkflowNode(id = "beforeNode", outputKey = "beforeText", resultCategory = "aside",
                outputSlot = OutputSlot.BEFORE_BODY, visible = true)
        ))
        val body = Workflow(id = "body", nodes = listOf(
            WorkflowNode(id = "bodyNode", outputKey = "bodyText", resultCategory = "reply", visible = true)
        ))
        val selected = ModelProfile(id = "selected", model = "body-model")
        val other = ModelProfile(id = "other", model = "before-model")
        val seen = mutableListOf<String>()
        val updates = mutableListOf<RunUpdate>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                seen += profile.model
                onDelta(profile.model)
                return profile.model
            }
        }
        val result = WorkflowEngine().run(
            library(listOf(before, body)).copy(profiles = listOf(selected, other), selectedProfileId = "selected"),
            session(), "hello", gateway, { updates.add(it) }
        )
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(setOf("before-model", "body-model"), seen.toSet())
        assertEquals(listOf(OutputSlot.BEFORE_BODY, OutputSlot.BODY), result.replies.map { it.outputSlot })
        assertTrue(result.replies.all { it.turnId == user.id })
        assertEquals(ResultOrigin("main", "before", "beforeNode", "aside"), result.resultOrigins["beforeText"])
        assertEquals(ResultOrigin("main", "body", "bodyNode", "reply"), result.resultOrigins["bodyText"])
        assertEquals(OutputSlot.BEFORE_BODY, updates.first { it.nodeId == "beforeNode" && it.visible }.outputSlot)
        assertEquals(user.id, updates.first { it.nodeId == "bodyNode" && it.visible }.turnId)
    }

    @Test fun falseWorkflowHidesStaleCategoryAndExplicitResultWithoutDemandingItsModel() = runBlocking {
        val branch = Workflow(id = "branch", condition = Condition(expression = "state.route == \"on\""),
            nodes = listOf(WorkflowNode(id = "branchNode", outputKey = "oldBranch",
                resultCategory = "aside", visible = true)))
        val body = Workflow(id = "body", nodes = listOf(WorkflowNode(id = "bodyNode", actionId = "text.render",
            inputTemplate = "{{result.oldBranch}}|{{results.aside}}", outputKey = "body", visible = true,
            includeHistory = false)))
        val previous = session().copy(results = mapOf("oldBranch" to "stale"),
            resultOrigins = mapOf("oldBranch" to ResultOrigin("main", "branch", "branchNode", "aside")))
        val result = WorkflowEngine().run(library(listOf(body, branch), previous), previous, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("|", result.replies.single().text)
        assertEquals("stale", result.results["oldBranch"])
        assertEquals(previous.resultOrigins["oldBranch"], result.resultOrigins["oldBranch"])
    }

    @Test fun workflowAdmissionIsFixedAtEventEntryAndFailurePreservesOrigins() = runBlocking {
        val first = WorkflowNode(id = "switch", actionId = "state.set", outputKey = "switchResult",
            arguments = mapOf("key" to "route", "value" to "off"), includeHistory = false)
        val second = WorkflowNode(id = "answer", actionId = "text.render", dependsOn = listOf("switch"),
            outputKey = "answer", inputTemplate = "ran", visible = true, includeHistory = false,
            condition = Condition(expression = "result.switchResult == \"off\" && input == \"hello\""))
        val branch = Workflow(id = "branch", condition = Condition("route", Compare.EQUALS, "on"), nodes = listOf(first, second))
        val previous = session().copy(state = mapOf("route" to "on"))
        val success = WorkflowEngine().run(library(listOf(branch), previous), previous, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, success.status)
        assertEquals("off", success.state["route"])
        assertEquals("ran", success.replies.single().text)

        val failing = branch.copy(nodes = listOf(first, second.copy(actionId = "model.generate")))
        val persisted = previous.copy(results = mapOf("answer" to "old"),
            resultOrigins = mapOf("answer" to ResultOrigin("main", "branch", "answer", "reply")))
        val failed = WorkflowEngine().run(library(listOf(failing), persisted), persisted, "hello", noModel)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(persisted.state, failed.state)
        assertEquals(persisted.results, failed.results)
        assertEquals(persisted.resultOrigins, failed.resultOrigins)
        assertTrue(failed.traces.any { it.status == "error" })
    }
}
