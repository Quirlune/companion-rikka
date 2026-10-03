package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningConflictTest {
    private val session = Session(
        id = "session", characterId = "character", mainCallId = "main",
        state = mapOf("score" to "old"), results = mapOf("saved" to "old result")
    )

    private fun library(workflows: List<Workflow>, profile: ModelProfile? = null) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows)),
        sessions = listOf(session), profiles = listOfNotNull(profile), selectedProfileId = profile?.id
    )

    @Test fun laterParallelConflictAcrossWorkflowsRejectsBeforeModelAndPreservesState() = runBlocking {
        var modelCalls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                modelCalls++
                return "generated"
            }
        }
        val registry = ActionRegistry.defaults().register(ActionDescriptor(
            "default.write", "Default write",
            fields = listOf(ActionFieldDescriptor("target", "Target", required = true, defaultValue = "score")),
            stateWriteKeyField = "target"
        )) { context -> ActionOutcome(stateWrites = mapOf(context.arguments.getValue("target") to "second")) }
        val workflows = listOf(
            Workflow(id = "modelFlow", nodes = listOf(
                WorkflowNode(id = "model", outputKey = "generated"),
                WorkflowNode(id = "write", actionId = "state.set", dependsOn = listOf("model"), outputKey = "firstWrite",
                    arguments = mapOf("key" to " score ", "value" to "first"))
            )),
            Workflow(id = "otherFlow", nodes = listOf(
                WorkflowNode(id = "prepare", actionId = "text.render", outputKey = "prepared"),
                WorkflowNode(id = "write", actionId = "default.write", dependsOn = listOf("prepare"), outputKey = "secondWrite")
            ))
        )
        val result = WorkflowEngine(registry).run(library(workflows, ModelProfile(id = "profile", model = "test")), session, "hello", gateway)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, modelCalls)
        assertEquals(session.state, result.state)
        assertEquals(session.results, result.results)
        assertTrue(result.replies.isEmpty())
        assertTrue(result.traces.any { it.status == "error" && it.detail.contains("同一状态键") })
    }

    @Test fun conditionalAndDisabledAncestorsAllowExclusiveBranchesAndSerialWrites() = runBlocking {
        val noModel = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
                error("unexpected model call")
        }
        // Both variants skip the left branch through a transitive prerequisite; the right branch writes twice serially.
        for (disabled in listOf(false, true)) {
            val nodes = listOf(
                WorkflowNode(id = "leftRoot", actionId = "text.render", outputKey = "leftRoot", enabled = !disabled,
                    condition = if (disabled) null else Condition("score", Compare.NOT_EQUALS, "old")),
                WorkflowNode(id = "rightRoot", actionId = "text.render", outputKey = "rightRoot",
                    condition = if (disabled) null else Condition("score", Compare.EQUALS, "old")),
                WorkflowNode(id = "leftMiddle", actionId = "text.render", dependsOn = listOf("leftRoot"), outputKey = "leftMiddle"),
                WorkflowNode(id = "rightMiddle", actionId = "text.render", dependsOn = listOf("rightRoot"), outputKey = "rightMiddle"),
                WorkflowNode(id = "leftWrite", actionId = "state.set", dependsOn = listOf("leftMiddle"), outputKey = "leftWrite",
                    arguments = mapOf("key" to "score", "value" to "left")),
                WorkflowNode(id = "rightWrite", actionId = "state.set", dependsOn = listOf("rightMiddle"), outputKey = "rightWrite",
                    arguments = mapOf("key" to "score", "value" to "right")),
                WorkflowNode(id = "serialWrite", actionId = "state.set", dependsOn = listOf("rightWrite"), outputKey = "serialWrite",
                    arguments = mapOf("key" to "score", "value" to "final"))
            )
            val result = WorkflowEngine().run(library(listOf(Workflow(id = "flow", nodes = nodes))), session, "hello", noModel)
            assertEquals(RunStatus.SUCCESS, result.status)
            assertEquals("final", result.state["score"])
            assertEquals("right", result.results["rightWrite"])
            assertTrue("leftWrite" !in result.results)
            assertTrue(result.traces.any { it.nodeId == "leftWrite" && it.status == "skipped" })
        }
    }
}
