package dev.gray.companion.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CompletionSchedulingTest {
    private fun model(id: String = "body", key: String = "reply", prompt: String = "main") = WorkflowNode(
        id = id, outputKey = key, visible = true, includeHistory = false,
        blocks = listOf(PromptBlock(id = "$id-prompt", template = prompt)),
        outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
    private fun action(id: String, kind: String = "text.render", key: String = id, text: String = id) = WorkflowNode(
        id = id, actionId = kind, outputKey = key, inputTemplate = text, includeHistory = false)
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main", groupIds = listOf("group"),
        messages = listOf(ChatMessage(id = "turn", role = Role.USER, text = "question")))
    private fun library(primary: Workflow = Workflow(id = "primary", nodes = listOf(model())),
                        extra: List<Workflow> = emptyList(), mainExtra: List<Workflow> = emptyList(), special: Boolean = true) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", specialDefault = special, primaryWorkflowId = primary.id,
            workflows = listOf(primary) + mainExtra)),
        groups = listOf(CallGroup(id = "group", name = "Group", workflows = extra)),
        profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")
    private fun captured(lib: Library, version: Int) = session().let {
        it.copy(executableWorld = CompletedWorlds.capture(lib, it, "question", version))
    }
    private fun gateway(block: suspend (List<ChatMessage>) -> String) = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) = block(messages)
    }

    @Test fun queuedPrioritiesAndNodePermitDoNotBlockParallelDefaultIn15But14KeepsItsBarrier() = runBlocking {
        for (version in listOf(15, 14)) for (priority in listOf(-3, 3)) {
            val queuedStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val listener = CompletableDeferred<Unit>()
            val registry = ActionRegistry.defaults().register(ActionDescriptor("slow", "Slow")) {
                queuedStarted.complete(Unit); release.await(); ActionOutcome("slow")
            }.register(ActionDescriptor("listen", "Listen")) { listener.complete(Unit); ActionOutcome("heard") }
            val lib = library(extra = listOf(
                Workflow(id = "queued", queued = true, priority = priority, nodes = listOf(action("slow", "slow"))),
                Workflow(id = "listener", trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(action("listen", "listen")))))
            val running = async { WorkflowEngine(registry, maxConcurrentNodes = 1).run(lib, captured(lib, version), "question",
                gateway { " final body " }) }
            withTimeout(5_000) { queuedStarted.await() }
            if (version == 15) {
                withTimeout(5_000) { listener.await() }
                assertFalse(running.isCompleted)
            } else assertFalse(listener.isCompleted)
            release.complete(Unit)
            val result = withTimeout(5_000) { running.await() }
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertEquals("final body", result.replies.single().text)
            assertTrue(listener.isCompleted)
        }
    }

    @Test fun primaryWholeChainCompletesBeforeReplyListenersWhichIgnoreUnrelatedPeers() = runBlocking {
        val finishPrimary = CompletableDeferred<Unit>()
        val postStarted = CompletableDeferred<Unit>()
        val finishPeer = CompletableDeferred<Unit>()
        val peerStarted = CompletableDeferred<Unit>()
        val firstListener = CompletableDeferred<Unit>()
        val finishListener = CompletableDeferred<Unit>()
        val secondListener = CompletableDeferred<Unit>()
        val registry = ActionRegistry.defaults()
            .register(ActionDescriptor("post", "Post")) { postStarted.complete(Unit); finishPrimary.await(); ActionOutcome("post") }
            .register(ActionDescriptor("slow", "Slow")) { peerStarted.complete(Unit); finishPeer.await(); ActionOutcome("peer") }
            .register(ActionDescriptor("listenerSlow", "Listener")) { firstListener.complete(Unit); finishListener.await(); ActionOutcome("one") }
            .register(ActionDescriptor("listenerFast", "Listener")) { secondListener.complete(Unit); ActionOutcome("two") }
        val primary = Workflow(id = "primary", nodes = listOf(model(), action("post", "post").copy(dependsOn = listOf("body"))))
        val lib = library(primary, extra = listOf(
            Workflow(id = "peer", nodes = listOf(action("peer", "slow"))),
            Workflow(id = "listener1", trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(action("l1", "listenerSlow"))),
            Workflow(id = "listener2", trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(action("l2", "listenerFast")))), special = false)
        val running = async { WorkflowEngine(registry).run(lib, session(), "question", gateway { "body" }) }
        withTimeout(5_000) { postStarted.await(); peerStarted.await() }
        assertFalse(firstListener.isCompleted)
        finishPrimary.complete(Unit)
        withTimeout(5_000) { firstListener.await(); secondListener.await() }
        assertFalse(finishPeer.isCompleted)
        assertFalse(finishListener.isCompleted)
        finishListener.complete(Unit); finishPeer.complete(Unit)
        assertEquals(RunStatus.SUCCESS, withTimeout(5_000) { running.await() }.status)
    }

    @Test fun ordinaryBodyAndFailedInactiveOrDisabledPrimaryNeverDispatchReplyCompleted() = runBlocking {
        for (kind in listOf("failed", "inactive", "disabled")) {
            var listeners = 0
            val registry = ActionRegistry.defaults().register(ActionDescriptor("listen", "Listen")) { listeners++; ActionOutcome("heard") }
            val primary = Workflow(id = "primary", enabled = kind != "disabled",
                condition = if (kind == "inactive") Condition(key = "absent", op = Compare.EXISTS) else null,
                nodes = listOf(model()))
            val lib = library(primary, extra = listOf(Workflow(id = "listener", trigger = RuntimeEvents.REPLY_COMPLETED,
                nodes = listOf(action("listen", "listen")))),
                mainExtra = listOf(Workflow(id = "ordinary", nodes = listOf(model("ordinaryBody", "ordinaryReply", "ordinary")))), special = false)
            val result = WorkflowEngine(registry).run(lib, session(), "question", gateway { messages ->
                if (messages.any { it.text.contains("main") }) error("default failed") else "ordinary visible body"
            })
            assertEquals(kind + result.traces, if (kind == "failed") RunStatus.PAUSED else RunStatus.SUCCESS, result.status)
            assertTrue(result.replies.any { it.nodeId == "ordinaryBody" })
            assertEquals(kind, 0, listeners)
        }
    }

    @Test fun realQueuedInjectionAncestorsDelayOnlyTheirModelAndOwnAncestorsDoNotDeadlock() = runBlocking {
        val ancestorStarted = CompletableDeferred<Unit>()
        val finishAncestor = CompletableDeferred<Unit>()
        val unrelatedStarted = CompletableDeferred<Unit>()
        val finishUnrelated = CompletableDeferred<Unit>()
        val ordinaryModel = CompletableDeferred<Unit>()
        val defaultModel = CompletableDeferred<Unit>()
        val registry = ActionRegistry.defaults().register(ActionDescriptor("ancestor", "Ancestor")) {
            ancestorStarted.complete(Unit); finishAncestor.await(); ActionOutcome("injected fact")
        }.register(ActionDescriptor("unrelated", "Unrelated")) {
            unrelatedStarted.complete(Unit); finishUnrelated.await(); ActionOutcome("irrelevant")
        }
        val inject = action("inject", "prompt.inject", text = "{{result.source}}").copy(dependsOn = listOf("source"),
            arguments = mapOf("id" to "fact", "targetWorkflowId" to "primary", "targetNodeId" to "body"))
        val lib = library(extra = listOf(
            Workflow(id = "preparation", trigger = RuntimeEvents.TURN_PREPARE, queued = true, priority = 1,
                nodes = listOf(action("source", "ancestor"), inject)),
            Workflow(id = "unrelated", trigger = RuntimeEvents.TURN_PREPARE, queued = true, priority = 2,
                nodes = listOf(action("unrelated", "unrelated"))),
            Workflow(id = "ordinary", nodes = listOf(model("ordinary", "ordinaryReply", "ordinary")))))
        val running = async { WorkflowEngine(registry).run(lib, session(), "question", gateway { messages ->
            if (messages.any { it.text.contains("ordinary") }) { ordinaryModel.complete(Unit); "ordinary" }
            else { assertTrue(messages.any { it.text.contains("injected fact") }); defaultModel.complete(Unit); "body" }
        }) }
        withTimeout(5_000) { ancestorStarted.await(); ordinaryModel.await() }
        assertFalse(defaultModel.isCompleted)
        finishAncestor.complete(Unit)
        withTimeout(5_000) { unrelatedStarted.await(); defaultModel.await() }
        assertFalse(finishUnrelated.isCompleted)
        finishUnrelated.complete(Unit)
        assertEquals(RunStatus.SUCCESS, withTimeout(5_000) { running.await() }.status)

        val ownInject = inject.copy(inputTemplate = "own fact", dependsOn = listOf("source"))
        val ownPrimary = Workflow(id = "primary", trigger = RuntimeEvents.TURN_PREPARE,
            nodes = listOf(action("source"), ownInject, model().copy(dependsOn = listOf("inject"))))
        val ownLib = library(ownPrimary, mainExtra = listOf(Workflow(id = "entry", nodes = listOf(action("entry")))), special = false)
        val own = withTimeout(5_000) { WorkflowEngine().run(ownLib, session(), "question", gateway { messages ->
            assertTrue(messages.any { it.text.contains("own fact") }); "own body"
        }) }
        assertEquals(own.traces.toString(), RunStatus.SUCCESS, own.status)

        var disabledSlotCalls = 0
        val disabledSlot = PromptBlock(id = "disabled-slot", injectionSlot = true, enabled = false)
        val slotModel = model().copy(blocks = model().blocks + disabledSlot)
        val invalidInject = inject.copy(dependsOn = emptyList(), inputTemplate = "must not arrive",
            arguments = inject.arguments + ("targetBlockId" to disabledSlot.id))
        val disabledLib = library(Workflow(id = "primary", nodes = listOf(slotModel)), extra = listOf(
            Workflow(id = "preparation", trigger = RuntimeEvents.TURN_PREPARE, queued = true, nodes = listOf(invalidInject))))
        val rejected = withTimeout(5_000) { WorkflowEngine().run(disabledLib, session(), "question", gateway {
            disabledSlotCalls++; "wrong prompt"
        }) }
        assertEquals(rejected.traces.toString(), RunStatus.PAUSED, rejected.status)
        assertEquals(0, disabledSlotCalls)
        assertTrue(rejected.recovery!!.failures.any { it.nodeKey == "primary/body" })
    }

    @Test fun queuedSamePriorityChainsAdvanceIndependentlyButHigherCohortWaitsAndCanOverwrite() = runBlocking {
        val ancestor = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val samePriority = CompletableDeferred<Unit>()
        val higher = CompletableDeferred<Unit>()
        val registry = ActionRegistry.defaults()
            .register(ActionDescriptor("hold", "Hold")) { ancestor.complete(Unit); release.await(); ActionOutcome("low-final") }
            .register(ActionDescriptor("peer", "Peer")) { samePriority.complete(Unit); ActionOutcome("peer") }
            .register(ActionDescriptor("higher", "Higher")) { higher.complete(Unit); ActionOutcome("higher-final") }
        val lib = library(extra = listOf(
            Workflow(id = "low", queued = true, priority = -1, nodes = listOf(action("first", key = "shared"),
                action("second", "hold", "shared").copy(dependsOn = listOf("first")))),
            Workflow(id = "peer", queued = true, priority = -1, nodes = listOf(action("peer", "peer"))),
            Workflow(id = "higher", queued = true, priority = 4, nodes = listOf(action("higher", "higher", "shared")))))
        val running = async { WorkflowEngine(registry).run(lib, session(), "question", gateway { "body" }) }
        withTimeout(5_000) { ancestor.await(); samePriority.await() }
        assertFalse(higher.isCompleted)
        release.complete(Unit)
        val result = withTimeout(5_000) { running.await() }
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals("higher-final", result.results["shared"])
    }

    @Test fun conditionalUndeclaredOverlapsCannotOverwriteAcceptedValuesAcrossLanesOrEvents() = runBlocking {
        for (event in listOf(RuntimeEvents.USER_MESSAGE, RuntimeEvents.REPLY_COMPLETED)) {
            val slowStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val statusAccepted = CompletableDeferred<Unit>()
            val registry = ActionRegistry.defaults().register(ActionDescriptor("slow", "Slow")) {
                slowStarted.complete(Unit); release.await(); ActionOutcome("slow", macroWrites = mapOf("result.status" to "late overwrite"))
            }
            val lib = library(extra = listOf(
                Workflow(id = "slow", queued = true, nodes = listOf(action("slow", "slow", "ordinary"))),
                Workflow(id = "status", trigger = event, nodes = listOf(action("status", key = "status", text = "accepted status")))))
            val running = async { WorkflowEngine(registry).run(lib, session(), "question", gateway { slowStarted.await(); "body" }) { update ->
                if (update.progress?.results?.get("status") == "accepted status") statusAccepted.complete(Unit)
            } }
            withTimeout(5_000) { slowStarted.await(); statusAccepted.await() }
            release.complete(Unit)
            val result = withTimeout(5_000) { running.await() }
            assertEquals(result.traces.toString(), RunStatus.PAUSED, result.status)
            assertEquals("accepted status", result.results["status"])
            assertTrue(result.recovery!!.failures.any { it.nodeKey == "slow/slow" })
        }
    }

    @Test fun resumeReplaysAcceptedDefaultAndListenerWithoutRepeatingRequestsOrSideEffects() = runBlocking {
        var modelCalls = 0
        var listeners = 0
        var ordinaryCalls = 0
        val listenerAccepted = CompletableDeferred<Unit>()
        val registry = ActionRegistry.defaults().register(ActionDescriptor("listen", "Listen")) {
            listeners++; ActionOutcome("heard", stateWrites = mapOf("heard" to "once"))
        }.register(ActionDescriptor("ordinary", "Ordinary")) {
            ordinaryCalls++
            if (ordinaryCalls == 1) { listenerAccepted.await(); error("pause after listener") }
            ActionOutcome("recovered")
        }
        val lib = library(extra = listOf(
            Workflow(id = "ordinary", queued = true, nodes = listOf(action("ordinary", "ordinary"))),
            Workflow(id = "listener", trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(action("listen", "listen")))))
        val before = session()
        val modelGateway = gateway { modelCalls++; "body" }
        val first = WorkflowEngine(registry).run(lib, before, "question", modelGateway) { update ->
            if (update.progress?.state?.get("heard") == "once") listenerAccepted.complete(Unit)
        }
        assertEquals(RunStatus.PAUSED, first.status)
        assertEquals(1, modelCalls); assertEquals(1, listeners)
        val codec = Json { encodeDefaults = true }
        val journal = codec.decodeFromString(RunRecovery.serializer(), codec.encodeToString(RunRecovery.serializer(), first.recovery!!))
        val saved = MessageOperations.recordPartialTurn(before, first).session!!.copy(pendingRun = journal)
        val resumed = withTimeout(5_000) { WorkflowEngine(registry).resume(lib, saved, modelGateway, journal,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "ordinary/ordinary")) }
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(1, modelCalls); assertEquals(1, listeners); assertEquals(2, ordinaryCalls)
        assertEquals("once", resumed.state["heard"])
        assertEquals("recovered", resumed.results["ordinary"])
        assertEquals(1, resumed.replies.size)
    }
}
