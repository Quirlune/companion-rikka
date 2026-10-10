package dev.gray.companion.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AutomaticOptionsBodyReadyTest {
    private fun defaultNode() = WorkflowNode(id = "default", visible = true, outputKey = "reply",
        includeHistory = false, blocks = listOf(PromptBlock(id = "base", template = "default")),
        outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main", groupIds = listOf("side"),
        state = mapOf("enabled" to "yes"), messages = listOf(ChatMessage(id = "turn", text = "question")))
    private fun library(peer: WorkflowNode, node: WorkflowNode = defaultNode(), conditional: Boolean = false) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", specialDefault = true, primaryWorkflowId = "primary",
            workflows = listOf(Workflow(id = "primary", title = "Default", nodes = listOf(node))))),
        groups = listOf(CallGroup(id = "side", name = "Side", workflows = listOf(Workflow(id = "ordinary", title = "Ordinary",
            condition = if (conditional) Condition(key = "enabled", op = Compare.EXISTS) else null, nodes = listOf(peer))))),
        profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")

    @Test fun processedDefaultBodyIsAcceptedBeforeMixedPeerFinishesOnlyInExecution14() = runBlocking {
        for (version in listOf(14, 13)) {
            val peer = WorkflowNode(id = "slow", actionId = "slow", outputKey = "ordinary", inputTemplate = "", includeHistory = false)
            val lib = library(peer)
            val before = session().let { it.copy(executableWorld = CompletedWorlds.capture(lib, it, "question", version)) }
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            val accepted = CompletableDeferred<Unit>()
            var saved = before
            val registry = ActionRegistry.defaults().register(ActionDescriptor("slow", "Slow")) {
                started.complete(Unit)
                release.await()
                ActionOutcome("ordinary result")
            }
            val gateway = object : ModelGateway {
                override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                    started.await()
                    onDelta("provisional body")
                    returned.complete(Unit)
                    return " final body "
                }
            }
            val running = async {
                WorkflowEngine(registry).run(lib, before, "question", gateway) { update ->
                    update.progress?.let { progress ->
                        saved = if (saved.pendingRun == null) MessageOperations.recordPartialTurn(saved, progress).session!!
                            else MessageOperations.continuePartialTurn(saved, progress).session!!
                        if (saved.messages.any { it.nodeId == "default" }) {
                            assertEquals("final body", saved.messages.single { it.nodeId == "default" }.text)
                            accepted.complete(Unit)
                        }
                    }
                }
            }
            withTimeout(5_000) { returned.await() }
            if (version == 14) {
                withTimeout(5_000) { accepted.await() }
                assertFalse(release.isCompleted)
                assertFalse(running.isCompleted)
            } else assertFalse(accepted.isCompleted)
            release.complete(Unit)
            val result = withTimeout(5_000) { running.await() }
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertEquals("final body", result.replies.single().text)
            assertEquals("ordinary result", result.results["ordinary"])
        }
    }

    @Test fun declaredConflictsAndDependenciesDoNotAdvanceAndUndeclaredWritesCannotOverwriteAcceptedDefault() = runBlocking {
        for (declared in listOf(true, false)) {
            val peer = WorkflowNode(id = "slow", actionId = "slow", outputKey = "ordinary", inputTemplate = "", includeHistory = false,
                arguments = if (declared) mapOf("key" to "result.reply") else emptyMap())
            val lib = library(peer, conditional = declared)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            val accepted = CompletableDeferred<Unit>()
            var modelCalls = 0
            val descriptor = ActionDescriptor("slow", "Slow", fields = if (declared) listOf(ActionFieldDescriptor("key", "Key")) else emptyList(),
                macroWriteKeyField = if (declared) "key" else null)
            val registry = ActionRegistry.defaults().register(descriptor) {
                started.complete(Unit)
                release.await()
                ActionOutcome("ordinary result", macroWrites = mapOf("result.reply" to "overwrite"))
            }
            val gateway = object : ModelGateway {
                override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                    modelCalls++
                    started.await()
                    returned.complete(Unit)
                    return " final body "
                }
            }
            val running = async {
                WorkflowEngine(registry).run(lib, session(), "question", gateway) { update ->
                    if (update.progress?.replies?.any { it.nodeId == "default" } == true) accepted.complete(Unit)
                }
            }
            if (declared) {
                // The existing active-wave conflict guard rejects both declared writers before
                // either request starts; waiting for a model-return signal would never finish.
                val rejected = withTimeout(5_000) { running.await() }
                assertEquals(rejected.traces.toString(), RunStatus.PAUSED, rejected.status)
                assertEquals(0, modelCalls)
                assertFalse(started.isCompleted)
                assertFalse(accepted.isCompleted)
                assertTrue(rejected.replies.isEmpty())
                assertTrue(rejected.recovery!!.failures.any { it.nodeKey == "primary/default" })
                continue
            }
            withTimeout(5_000) { returned.await() }
            withTimeout(5_000) { accepted.await() }
            release.complete(Unit)
            val result = withTimeout(5_000) { running.await() }
            assertEquals(result.traces.toString(), RunStatus.PAUSED, result.status)
            assertEquals("final body", result.results["reply"])
            assertEquals("final body", result.replies.single().text)
            assertTrue(result.recovery!!.failures.any { it.nodeKey == "ordinary/slow" })
        }
        var modelCalls = 0
        var bodyAccepted = false
        val invalid = library(WorkflowNode(id = "peer", actionId = "text.render", outputKey = "ordinary", includeHistory = false),
            node = defaultNode().copy(dependsOn = listOf("missing")))
        val result = WorkflowEngine().run(invalid, session(), "question", object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                modelCalls++
                return "body"
            }
        }) { update -> if (update.progress?.replies?.isNotEmpty() == true) bodyAccepted = true }
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, modelCalls)
        assertFalse(bodyAccepted)
    }
}
