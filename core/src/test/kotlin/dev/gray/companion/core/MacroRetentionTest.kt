package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MacroRetentionTest {
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main")
    private fun user(session: Session, text: String) = session.copy(messages = session.messages +
        ChatMessage(id = "turn-${MacroReferences.currentTurn(session) + 1}", role = Role.USER, text = text))
    private fun library(session: Session, nodes: List<WorkflowNode>) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = nodes)))),
        profiles = listOf(ModelProfile(id = "model", model = "offline")), selectedProfileId = "model",
        sessions = listOf(session), selectedSessionId = session.id
    )
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
            onDelta: suspend (String) -> Unit): String = error("No model request expected")
    }
    private fun committed(before: Session, result: RunResult): Session {
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        return MessageOperations.recordSuccessfulTurn(before, result).session!!
    }

    @Test fun modelTrajectorySwitchAppliesOnlyToNewExecution() = runBlocking {
        val node = WorkflowNode(id = "reply", outputKey = "reply", visible = true,
            recordTrajectory = false, includeHistory = false)
        val before = user(session(), "hello")
        val lib = library(before, listOf(node))
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = " raw body "
        }
        val current = committed(before, WorkflowEngine().run(lib, before, "hello", gateway))
        assertEquals(" raw body ", current.results["reply"])
        assertEquals(" raw body ", current.messages.last().rawText)
        assertTrue(current.macroHistory["result.reply"].isNullOrEmpty())
        assertEquals(1, current.currentOnlyMacros.getValue("result.reply").version)
        assertEquals(" raw body ", MacroResolver(lib, current).render("{{macro.reply}}"))

        val oldWorld = CompletedWorlds.capture(lib, before, "hello", executionVersion = 27)
        val oldBefore = before.copy(executableWorld = oldWorld)
        val legacy = committed(oldBefore, WorkflowEngine().run(lib, oldBefore, "hello", gateway))
        assertEquals(" raw body ", legacy.macroHistory.getValue("result.reply").single().rawText)
        assertFalse("result.reply" in legacy.currentOnlyMacros)
    }

    @Test fun replacementAndRecordingTogglesKeepOnlyRealUpdatePoints() = runBlocking {
        var current = session()
        var lib = Library()
        for ((index, recording) in listOf(true, false, false, true, false).withIndex()) {
            val text = ('A'.code + index).toChar().toString()
            val before = user(current, text)
            val node = WorkflowNode(id = "reply", actionId = "text.render", outputKey = "reply",
                visible = true, includeHistory = false, recordTrajectory = recording)
            lib = library(before, listOf(node))
            current = committed(before, WorkflowEngine().run(lib, before, text, noModel))
        }
        val versions = current.macroHistory.getValue("result.reply")
        assertEquals(listOf(1, 4), versions.map { it.version })
        assertEquals(listOf("A", "D"), versions.map { it.text })
        assertEquals(MacroGap(2), versions.last().precedingGap)
        assertEquals(5, current.currentOnlyMacros.getValue("result.reply").version)
        assertNull(current.currentOnlyMacros.getValue("result.reply").text)
        assertEquals(MacroGap(5), current.currentOnlyMacros.getValue("result.reply").gap)
        assertEquals(listOf(true, false, false, true, false), current.turnRecords.map {
            it.variants.single().macroAppends.isNotEmpty()
        })
        val resolver = MacroResolver(lib, current)
        assertEquals("E|E|E", resolver.render("{{result.reply}}|{{macro.reply}}|{{macro.reply@turn-0}}"))
        assertEquals("D|A", resolver.render("{{macro.reply@update-0}}|{{macro.reply@update-1}}"))
        assertEquals("A||D|", resolver.render("{{macro.reply@version-1}}|{{macro.reply@version-2}}|{{macro.reply@version-4}}|{{macro.reply@version-5}}"))
        assertEquals("D|||A", resolver.render("{{macro.reply@turn-1}}|{{macro.reply@turn-2}}|{{macro.reply@turn-3}}|{{macro.reply@turn-4}}"))

        // Removing the resuming writer must not hide the preceding omitted interval.
        val hiddenResume = current.copy(macroHistory = mapOf("result.reply" to listOf(versions.first(),
            versions.last().copy(origin = ResultOrigin("unloaded", "flow", "reply")))))
        assertNull(MacroReferences.selectVersion(lib, hiddenResume, "result.reply", "turn-2"))
        val unknownGap = current.copy(currentOnlyMacros = current.currentOnlyMacros.mapValues {
            it.value.copy(gap = MacroGap())
        })
        assertNull(MacroReferences.selectVersion(lib, unknownGap, "result.reply", "turn-4"))
        assertEquals("E", MacroReferences.selectVersion(lib, unknownGap, "result.reply", "turn-0")?.text)
    }

    @Test fun ownTargetUpdateUsesCapturedValueAndKeepsDisabledStateAcrossTurns() = runBlocking {
        var current = session()
        val update = WorkflowNode(id = "counter", actionId = "macro.process", outputKey = "counter",
            includeHistory = false, recordTrajectory = false, macroProcess = MacroProcessConfig(
                key = "result.counter", mode = MacroProcessMode.UPDATE, initial = "0",
                steps = listOf(TextProcessStep(TextProcessKind.INCREASE, amount = "1"))))
        for ((index, node) in listOf(update, update.copy(macroProcess = update.macroProcess.copy(
            mode = MacroProcessMode.DISABLE)), update).withIndex()) {
            val before = user(current, "step-$index")
            current = committed(before, WorkflowEngine().run(library(before, listOf(node)), before, "step-$index", noModel))
        }
        assertEquals("2", current.results["counter"])
        assertFalse(current.currentOnlyMacros.getValue("result.counter").enabled)
        assertEquals(3, current.currentOnlyMacros.getValue("result.counter").version)
        assertTrue(current.macroHistory.isEmpty())
        val copy = update.copy(macroProcess = update.macroProcess.copy(mode = MacroProcessMode.COPY,
            source = "result.counter", steps = emptyList()))
        val before = user(current, "copy")
        val failed = WorkflowEngine().run(library(before, listOf(copy)), before, "copy", noModel)
        assertEquals(RunStatus.PAUSED, failed.status)
        assertEquals("2", failed.results["counter"])
        assertFalse(failed.recovery!!.prepared.single().macroTarget!!.enabled)
        assertEquals("2", failed.recovery!!.prepared.single().macroTarget!!.text)
    }

    @Test fun staticAndStateOverridesStayLocalAndHiddenHeadsCannotLeakThroughFallbacks() = runBlocking {
        val before = user(session().copy(packageIds = listOf("world")), "Mars")
        val place = WorkflowNode(id = "place", actionId = "macro.process", outputKey = "placeReceipt",
            includeHistory = false, recordTrajectory = false,
            macroProcess = MacroProcessConfig(key = "pack.world.place", mode = MacroProcessMode.SET))
        val score = WorkflowNode(id = "score", actionId = "state.set", outputKey = "reply", resultCategory = "lore",
            includeHistory = false, recordTrajectory = false, dependsOn = listOf(place.id),
            arguments = mapOf("key" to "score", "value" to "7"))
        val pack = PromptPackage(id = "world", namespace = "world", name = "World",
            entries = listOf(PromptEntry(key = "place", text = "Earth")))
        val lib = library(before, listOf(place, score)).copy(packages = listOf(pack))
        val current = committed(before, WorkflowEngine().run(lib, before, "Mars", noModel))
        assertTrue(current.macroHistory.isEmpty())
        assertEquals("Mars", current.currentOnlyMacros.getValue("pack.world.place").text)
        assertNull(current.currentOnlyMacros.getValue("state.score").text)
        assertEquals("Mars|7", MacroResolver(lib, current).render("{{pack.world.place}}|{{state.score}}"))
        assertEquals("Earth", pack.entries.single().text)
        val withCurrent = lib.copy(sessions = listOf(current), selectedSessionId = current.id)
        val freshLibrary = withCurrent.startNewConversation()
        val fresh = freshLibrary.sessions.last()
        assertTrue(fresh.currentOnlyMacros.isEmpty())
        assertTrue(fresh.historyMemory.segments.isEmpty())
        assertEquals("Earth", MacroResolver(freshLibrary, fresh).render("{{pack.world.place}}"))
        assertEquals(current, freshLibrary.sessions.first())

        val loadedOrigin = current.resultOrigins.getValue("reply")
        val hidden = current.copy(currentOnlyMacros = current.currentOnlyMacros.mapValues { (address, head) ->
            if (address == "result.reply" || address == "state.score") head.copy(
                origin = ResultOrigin("unloaded", "hidden", "toggle"), valueOrigin = loadedOrigin) else head
        })
        assertEquals("||||", MacroResolver(lib, hidden).render(
            "{{result.reply}}|{{macro.reply}}|{{result.reply@latest}}|{{results.lore}}|{{state.score}}"))
        assertNull(MacroReferences.baseValue(lib, hidden, "result.reply"))
        assertFalse("result.reply" in MacroReferences.currentVersions(lib, hidden))
    }

    @Test fun regexCopyAndRawBodySurviveVariantSwitchEditsAndHistoricalFork() = runBlocking {
        val node = WorkflowNode(id = "reply", outputKey = "raw_reply", visible = true, recordTrajectory = false,
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
                pattern = "<body>.*?</body>", requireMatch = true)),
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "body",
                pattern = "<body>(.*?)</body>", captureGroup = 1, recordTrajectory = true))
        var raw = "<thinking>private A</thinking><body>A</body>"
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = raw
        }
        val before = user(session(), "hello")
        val lib = library(before, listOf(node))
        val resultA = WorkflowEngine().run(lib, before, "hello", gateway)
        val first = committed(before, resultA)
        val rawA = raw
        assertEquals("<body>A</body>", first.results["raw_reply"])
        assertEquals("A", first.messages.last().text)
        assertEquals(rawA, first.messages.last().rawText)
        assertTrue(first.macroHistory["result.raw_reply"].isNullOrEmpty())
        assertEquals(1, first.currentOnlyMacros.getValue("result.raw_reply").version)
        assertEquals("A", first.macroHistory.getValue("result.body").single().text)
        val candidate = MessageOperations.prepareRegeneration(first, before.messages.single().id).session!!
        assertTrue(candidate.currentOnlyMacros.isEmpty())
        assertNull(MessageOperations.commitRegeneration(first, candidate, resultA.copy(status = RunStatus.FAILED)).session)
        assertEquals(rawA, first.messages.last().rawText)

        raw = "<thinking>private B</thinking><body>B</body>"
        val resultB = WorkflowEngine().run(lib, candidate, "hello", gateway)
        val second = MessageOperations.commitRegeneration(first, candidate, resultB).session!!
        val originalVariant = second.turnRecords.single().variants.first().id
        val switched = MessageOperations.applyLatestVariant(second, before.messages.single().id, originalVariant).session!!
        assertEquals(first.currentOnlyMacros, switched.currentOnlyMacros)
        assertEquals(first.results, switched.results)
        assertEquals(rawA, switched.messages.last().rawText)
        val edited = MessageOperations.editText(switched, switched.messages.last().id, "edited").session!!
        assertEquals(rawA, edited.messages.last().rawText)
        val laterBefore = user(edited, "later")
        raw = "<body>C</body>"
        val later = committed(laterBefore, WorkflowEngine().run(lib, laterBefore, "later", gateway))
        val fork = MessageOperations.forkAtVariant(later, before.messages.single().id, originalVariant).session!!
        assertEquals(first.currentOnlyMacros, fork.currentOnlyMacros)
        assertEquals(first.results, fork.results)
        assertEquals(rawA, fork.messages.last().rawText)
        assertEquals("edited", fork.messages.last().text)
        assertEquals("<body>C</body>", later.results["raw_reply"])
    }

    @Test fun durableRecoveryReplaysExactHeadsAndRawBodyWithoutRepeatingAcceptedModel() = runBlocking {
        val good = WorkflowNode(id = "good", outputKey = "good", visible = true, recordTrajectory = false,
            blocks = listOf(PromptBlock(template = "good")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM)))
        val bad = WorkflowNode(id = "bad", outputKey = "bad", visible = true, recordTrajectory = false,
            dependsOn = listOf(good.id), blocks = listOf(PromptBlock(template = "bad")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
                pattern = "<ok>(.*?)</ok>", group = 1, requireMatch = true)))
        val before = user(session(), "hello")
        val lib = library(before, listOf(good, bad))
        var goodCalls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = if (messages.any { it.text == "good" }) {
                goodCalls++; " kept "
            } else "invalid"
        }
        var saved = before
        val engine = WorkflowEngine()
        val first = engine.run(lib, before, "hello", gateway) { update ->
            update.progress?.let {
                saved = if (saved.pendingRun == null) MessageOperations.recordPartialTurn(saved, it).session!!
                    else MessageOperations.continuePartialTurn(saved, it).session!!
            }
        }
        assertEquals(RunStatus.PAUSED, first.status)
        saved = MessageOperations.continuePartialTurn(saved, first).session!!
        val json = LibraryFormat.json
        val restored = json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), saved))
        val receipt = restored.pendingRun!!.accepted.single()
        assertTrue(receipt.publication.macroAppends.isEmpty())
        assertEquals(1, receipt.publication.headWrites.getValue("result.good").version)
        assertEquals(" kept ", receipt.reply!!.rawText)
        val resumed = engine.resume(lib, restored, gateway, restored.pendingRun!!,
            RecoveryDecision(RecoveryDecisionKind.MANUAL, "flow/bad", manualText = "<ok>fixed</ok>"))
        assertEquals(RunStatus.SUCCESS, resumed.status)
        assertEquals(1, goodCalls)
        assertEquals(first.currentOnlyMacros.getValue("result.good"), resumed.currentOnlyMacros.getValue("result.good"))
        assertEquals(receipt.reply, resumed.replies.first { it.nodeId == "good" })
        assertNull(resumed.replies.first { it.nodeId == "bad" }.rawText)
        val finished = MessageOperations.continuePartialTurn(restored, resumed).session!!
        assertNull(finished.pendingRun)
        assertTrue(finished.macroHistory.isEmpty())
        assertEquals(setOf("result.good", "result.bad"), finished.currentOnlyMacros.keys)
        assertEquals("kept", finished.results["good"])
    }

    @Test fun legacyExecutionVersionsKeepRecordingDespiteNewFlags() = runBlocking {
        val before = user(session(), "hello")
        val node = WorkflowNode(id = "reply", actionId = "text.render", outputKey = "reply",
            includeHistory = false, visible = true, recordTrajectory = false,
            regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "copy", pattern = ".+", recordTrajectory = false))
        val lib = library(before, listOf(node))
        for (version in 1..2) {
            val recovery = RunRecovery(id = "legacy-$version", sessionId = before.id, turnId = before.messages.single().id,
                input = "hello", checkpoint = MessageOperations.captureCheckpoint(before), initialSession = before,
                frozenResources = lib, executionVersion = version,
                failures = listOf(RecoveryFailure("flow/reply", "flow", "reply", "Reply",
                    RecoveryFailureKind.ACTION, "paused", provisional = true)))
            val result = WorkflowEngine().resume(lib, before.copy(pendingRun = recovery), noModel, recovery,
                RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/reply"))
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertTrue(result.currentOnlyMacros.isEmpty())
            assertEquals("hello", result.macroHistory.getValue("result.reply").single().text)
            assertEquals("hello", result.macroHistory.getValue("result.copy").single().text)
        }
    }
}
