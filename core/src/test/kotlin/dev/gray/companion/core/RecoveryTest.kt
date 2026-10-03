package dev.gray.companion.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private fun session() = Session(id = "session", characterId = "character", mainCallId = "main",
        messages = listOf(ChatMessage(id = "user-turn", role = Role.USER, text = "hello")))

    private fun library(session: Session, workflows: List<Workflow>, tiles: List<MacroTile> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows, tiles = tiles)),
        profiles = listOf(ModelProfile(id = "profile", model = "demo")), selectedProfileId = "profile",
        sessions = listOf(session), selectedSessionId = session.id
    )

    private fun strictStatusNode(id: String = "status") = WorkflowNode(id = id, title = "Status",
        outputKey = "status", blocks = listOf(PromptBlock(template = "status")),
        outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
            pattern = "(?s)<StatusBlock>.*?</StatusBlock>", requireMatch = true)))

    private fun statusTile() = MacroTile(title = "Status", template = "{{result.status}}",
        kind = MacroTileKind.STATUS, fields = listOf(MacroStatusField("love", "Love",
            MacroTileKind.PROGRESS, 0.0, 100.0)))

    @Test fun siblingSurvivesAndEditedPromptUsesFrozenBreakpoint() = runBlocking {
        val good = WorkflowNode(id = "good", title = "Good", outputKey = "good", visible = true,
            blocks = listOf(PromptBlock(template = "good")))
        val bad = WorkflowNode(id = "bad", title = "Bad", outputKey = "bad", visible = true,
            blocks = listOf(PromptBlock(template = "old {{character.name}}")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
                pattern = "<ok>.*?</ok>", requireMatch = true)))
        val flow = Workflow(id = "flow", nodes = listOf(good, bad))
        val before = session()
        val base = library(before, listOf(flow))
        var goodCalls = 0
        val seen = mutableListOf<String>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String {
                val prompt = messages.joinToString("|") { it.text }
                if ("good" in prompt) { goodCalls++; return "kept" }
                seen += prompt
                return if ("revised Hero" in prompt) "<ok>fixed</ok>" else "bad format"
            }
        }
        val engine = WorkflowEngine()
        var saved = before
        val first = engine.run(base, before, "hello", gateway) { update ->
            update.progress?.let { progress ->
                saved = if (saved.pendingRun == null) MessageOperations.recordPartialTurn(saved, progress).session!!
                    else MessageOperations.continuePartialTurn(saved, progress).session!!
            }
        }
        assertEquals(RunStatus.PAUSED, first.status)
        assertEquals("kept", first.results["good"])
        assertEquals(1, first.replies.size)
        saved = MessageOperations.continuePartialTurn(saved, first).session!!
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), saved))
        assertNotNull(restored.pendingRun)
        val changed = bad.copy(blocks = listOf(PromptBlock(
            template = "revised {{character.name}} {{result.good}} {{history.text}}")))
        val edited = base.copy(mainCalls = listOf(base.mainCalls.single().copy(
            workflows = listOf(flow.copy(nodes = listOf(good, changed))))), sessions = listOf(restored))
        var resuming = restored
        val final = engine.resume(edited, restored, gateway, restored.pendingRun!!,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/bad")) { update ->
                update.progress?.let { resuming = MessageOperations.continuePartialTurn(resuming, it).session!! }
            }
        assertEquals(RunStatus.SUCCESS, final.status)
        assertTrue(seen.last().contains("revised Hero"))
        assertFalse(seen.last().contains("kept"))
        assertEquals(1, goodCalls)
        assertEquals("kept", final.results["good"])
        val continued = MessageOperations.continuePartialTurn(resuming, final).session!!
        assertEquals(first.replies.single().id, continued.messages.first { it.nodeId == "good" }.id)
        assertEquals(saved.macroHistory["result.good"], continued.macroHistory["result.good"])

        val accepted = CompletableDeferred<Unit>()
        var interrupted = before
        val waitingGateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = if (messages.any { it.text == "good" }) "kept"
                    else awaitCancellation()
        }
        val job = launch {
            engine.run(base, before, "hello", waitingGateway) { update ->
                update.progress?.let { progress ->
                    interrupted = if (interrupted.pendingRun == null)
                        MessageOperations.recordPartialTurn(interrupted, progress).session!!
                    else MessageOperations.continuePartialTurn(interrupted, progress).session!!
                    if (interrupted.results["good"] == "kept") accepted.complete(Unit)
                }
            }
        }
        withTimeout(5_000) { accepted.await() }
        job.cancelAndJoin()
        assertEquals("kept", interrupted.results["good"])
        assertNotNull(interrupted.pendingRun)

        val slow = WorkflowNode(id = "slow", outputKey = "slow", visible = true,
            blocks = listOf(PromptBlock(template = "slow")))
        val fast = WorkflowNode(id = "fast", outputKey = "fast", visible = true,
            blocks = listOf(PromptBlock(template = "fast")))
        val reject = bad.copy(id = "reject", outputKey = "reject",
            blocks = listOf(PromptBlock(template = "reject")))
        val reverseBase = library(before, listOf(Workflow(id = "reverse", nodes = listOf(slow, fast, reject))))
        val releaseSlow = CompletableDeferred<Unit>()
        val reverseGateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = when {
                messages.any { it.text == "slow" } -> { releaseSlow.await(); "slow body" }
                messages.any { it.text == "fast" } -> "fast body"
                else -> "bad format"
            }
        }
        var reverseSaved = before
        val reverseFirst = engine.run(reverseBase, before, "hello", reverseGateway) { update ->
            update.progress?.let { progress ->
                reverseSaved = if (reverseSaved.pendingRun == null)
                    MessageOperations.recordPartialTurn(reverseSaved, progress).session!!
                else MessageOperations.continuePartialTurn(reverseSaved, progress).session!!
                if (reverseSaved.results["fast"] == "fast body") releaseSlow.complete(Unit)
            }
        }
        assertEquals(RunStatus.PAUSED, reverseFirst.status)
        assertEquals(listOf("slow", "fast"), reverseFirst.replies.map { it.nodeId })
        reverseSaved = MessageOperations.continuePartialTurn(reverseSaved, reverseFirst).session!!
        var reverseResuming = reverseSaved
        val reverseFinal = engine.resume(reverseBase.copy(sessions = listOf(reverseSaved)), reverseSaved,
            reverseGateway, reverseSaved.pendingRun!!,
            RecoveryDecision(RecoveryDecisionKind.MANUAL, "reverse/reject", manualText = "<ok>fixed</ok>")) {
                update -> update.progress?.let {
                    reverseResuming = MessageOperations.continuePartialTurn(reverseResuming, it).session!!
                }
            }
        assertEquals(RunStatus.SUCCESS, reverseFinal.status)
        val reverseContinued = MessageOperations.continuePartialTurn(reverseResuming, reverseFinal).session!!
        assertEquals(reverseFirst.replies.map { it.id },
            reverseContinued.messages.filter { it.role == Role.ASSISTANT }.take(2).map { it.id })
    }

    @Test fun formatCandidatesPublishOnceAndSerialModeStopsAtThree() = runBlocking {
        val before = session()
        val flow = Workflow(id = "flow", nodes = listOf(strictStatusNode()))
        val base = library(before, listOf(flow), listOf(statusTile()))
        var calls = 0
        val gateway = object : ModelGateway {
            override fun maxParallelCandidates(profile: ModelProfile) = 2
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = when (++calls) {
                1 -> "no tag"
                2 -> "<StatusBlock><other>x</other></StatusBlock>"
                else -> "<StatusBlock><love>42</love></StatusBlock>"
            }
        }
        val result = WorkflowEngine().run(base, before, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertTrue(calls in 3..5)
        assertEquals(1, result.macroHistory["result.status"].orEmpty().size)
        assertEquals("<StatusBlock><love>42</love></StatusBlock>", result.results["status"])

        var serialCalls = 0
        val serial = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String { serialCalls++; return "invalid" }
        }
        val failed = WorkflowEngine().run(base, before, "hello", serial)
        assertEquals(RunStatus.PAUSED, failed.status)
        assertEquals(3, serialCalls)
        assertFalse(failed.results.containsKey("status"))
    }

    @Test fun skipKeepsAcceptedIncrementsEventsAndMasksOldLatest() = runBlocking {
        val before = session().copy(state = mapOf("count" to "0", "fired" to "0"),
            results = mapOf("reply" to "stale"),
            resultOrigins = mapOf("reply" to ResultOrigin("main", "userFlow", "failed")),
            macroHistory = mapOf("result.reply" to listOf(MacroVersion(1, "stale",
                ResultOrigin("main", "userFlow", "failed"), turn = 1, turnId = "user-turn"))))
        val prep = Workflow(id = "prepFlow", trigger = "turn.prepare", nodes = listOf(
            WorkflowNode(id = "increment", actionId = "state.increment", outputKey = "inc",
                arguments = mapOf("key" to "count", "amount" to "1"))))
        val emit = WorkflowNode(id = "emit", actionId = "event.emit", outputKey = "emitResult",
            arguments = mapOf("event" to "custom.once"))
        val failed = WorkflowNode(id = "failed", outputKey = "reply")
        val user = Workflow(id = "userFlow", nodes = listOf(emit, failed))
        val custom = Workflow(id = "customFlow", trigger = "custom.once", nodes = listOf(
            WorkflowNode(id = "fired", actionId = "state.increment", outputKey = "fireResult",
                arguments = mapOf("key" to "fired", "amount" to "1"))))
        val base = library(before, listOf(prep, user, custom))
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = throw ModelRequestFailure("network")
        }
        val first = WorkflowEngine().run(base, before, "hello", gateway)
        assertEquals(RunStatus.PAUSED, first.status)
        assertEquals("1", first.state["count"])
        assertEquals("1", first.state["fired"])
        val saved = MessageOperations.recordPartialTurn(before, first).session!!
        val final = WorkflowEngine().resume(base.copy(sessions = listOf(saved)), saved, gateway,
            saved.pendingRun!!, RecoveryDecision(RecoveryDecisionKind.SKIP, "userFlow/failed"))
        assertEquals(RunStatus.SUCCESS, final.status)
        assertEquals("1", final.state["count"])
        assertEquals("1", final.state["fired"])
        assertTrue("reply" in final.unavailableResultKeys)
        val continued = MessageOperations.continuePartialTurn(saved, final).session!!
        assertEquals("", MacroResolver(base, continued).render("{{macro.reply}}"))
        assertEquals("stale", MacroResolver(base, continued).render("{{macro.reply@version-1}}"))
    }

    @Test fun bodySurvivesStatusFailureInSameVariant() = runBlocking {
        val before = session()
        val body = WorkflowNode(id = "body", title = "Body", outputKey = "body", visible = true,
            blocks = listOf(PromptBlock(template = "body")))
        val bodyFlow = Workflow(id = "bodyFlow", nodes = listOf(body))
        val statusFlow = Workflow(id = "statusFlow", trigger = "reply.completed",
            nodes = listOf(strictStatusNode()))
        val base = library(before, listOf(bodyFlow, statusFlow), listOf(statusTile()))
        var bodyCalls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String {
                return if (messages.any { it.text == "body" }) { bodyCalls++; "body answer" } else "bad xml"
            }
        }
        val engine = WorkflowEngine()
        var saved = before
        val first = engine.run(base, before, "hello", gateway) { update ->
            update.progress?.let { progress ->
                saved = if (saved.pendingRun == null) MessageOperations.recordPartialTurn(saved, progress).session!!
                    else MessageOperations.continuePartialTurn(saved, progress).session!!
            }
        }
        assertEquals(RunStatus.PAUSED, first.status)
        assertEquals("body answer", first.replies.single().text)
        saved = MessageOperations.continuePartialTurn(saved, first).session!!
        val variantId = saved.turnRecords.single().selectedVariantId
        var resuming = saved
        val final = engine.resume(base.copy(sessions = listOf(saved)), saved, gateway,
            saved.pendingRun!!, RecoveryDecision(RecoveryDecisionKind.MANUAL, "statusFlow/status",
                manualText = "<StatusBlock><love>42</love></StatusBlock>")) { update ->
                update.progress?.let { resuming = MessageOperations.continuePartialTurn(resuming, it).session!! }
            }
        assertEquals(RunStatus.SUCCESS, final.status)
        assertEquals(1, bodyCalls)
        assertEquals(first.replies.single().id, final.replies.single().id)
        val continued = MessageOperations.continuePartialTurn(resuming, final).session!!
        assertEquals(variantId, continued.turnRecords.single().selectedVariantId)
        assertEquals("<StatusBlock><love>42</love></StatusBlock>", continued.results["status"])
    }
}
