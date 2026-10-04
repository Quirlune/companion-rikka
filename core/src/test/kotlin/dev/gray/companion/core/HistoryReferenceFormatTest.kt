package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryReferenceFormatTest {
    private val messages = listOf(
        ChatMessage(id = "user-1", role = Role.USER, text = "question", speakerName = "Original user"),
        ChatMessage(id = "reply-1", role = Role.ASSISTANT, text = "display answer", rawText = "raw answer", speakerName = "Gary"),
        ChatMessage(id = "user-2", role = Role.USER, text = "latest", speakerName = "Original user")
    )
    private fun session() = Session(id = "session", characterId = "card", mainCallId = "main", messages = messages)
    private fun main(history: HistoryConfig = HistoryConfig()) = MainCall(id = "main", name = "Main", history = history)
    private fun library(main: MainCall) = Library(
        characters = listOf(CharacterCard(id = "card", namespace = "card", name = "Current character")),
        mainCalls = listOf(main), profiles = listOf(ModelProfile(id = "profile", model = "offline")), selectedProfileId = "profile")
    private fun node(vararg blocks: PromptBlock) = WorkflowNode(id = "read", inputTemplate = "", includeHistory = false,
        explicitHistory = true, blocks = blocks.toList())
    private fun queryJson(json: String) = "{{type:builtin.in:history.name:messages.format:\"" +
        json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}"

    @Test fun quotedFormatRoundTripsAndOneScannerOwnsOuterTokenAndIncompleteDiagnostics() {
        val selection = HistorySelection(compressed = true, rangeExplicit = true, format = HistoryReferenceFormat(
            floorPrefix = "\"quoted\" \\ {{speaker.name}}: ", floorSuffix = "\n{note}",
            speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, Role.USER, "Gary: "))))
        val expression = HistoryReferences.expression(selection)
        assertEquals(selection, HistoryViews.selection(expression))
        listOf(
            selection.copy(range = HistoryRange(from = HistoryPoint(HistoryAnchor.START, 0.0))),
            selection.copy(range = HistoryRange(through = HistoryPoint(HistoryAnchor.PERCENT, 101.0))),
            selection.copy(viewId = "Invalid View"),
            selection.copy(revision = 1)
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { HistoryReferences.expression(invalid) } }
        val emptyRange = selection.copy(range = HistoryRange(from = HistoryPoint(HistoryAnchor.START, 100.0)))
        assertEquals(emptyRange, HistoryViews.selection(HistoryReferences.expression(emptyRange)))
        assertTrue(HistoryViews.sources(session(), emptyRange, main()).isEmpty())
        val text = "🙂 before $expression after {{input}}"
        val scan = MacroTemplateTokens.scan(text)
        assertTrue(scan.diagnostics.toString(), scan.diagnostics.isEmpty())
        assertEquals(2, scan.tokens.size)
        val token = scan.tokens.first()
        assertTrue(token.closed)
        assertEquals(expression, text.substring(token.start, token.endExclusive))
        assertEquals(selection, HistoryViews.selection(token.expression))
        assertTrue(token.expression.contains("{{speaker.name}}"))
        assertEquals("🙂 before history after input", MacroTemplateTokens.replace(text, scan) {
            if (it.expression.trim() == "input") "input" else "history"
        })
        val unfinished = expression.dropLast(3)
        val incomplete = MacroTemplateTokens.scan(unfinished)
        assertEquals(1, incomplete.tokens.size)
        assertFalse(incomplete.tokens.single().closed)
        assertTrue(incomplete.diagnostics.any { it.message.contains("双引号") })
        assertEquals(unfinished.length, incomplete.tokens.single().endExclusive)
        assertTrue(MacroTemplateTokens.scan("text }}").diagnostics.isNotEmpty())
        assertTrue(MacroTemplateTokens.scan(expression, 7).diagnostics.isNotEmpty())
        assertEquals(MacroTemplateTokens.scan("{{character.name}}", 7), MacroTemplateTokens.scan("{{character.name}}", 8))
        assertTrue(TextProcessing.validate(listOf(TextProcessStep(TextProcessKind.APPEND, text = expression)), "steps").isEmpty())
        assertTrue(TextProcessing.validate(listOf(TextProcessStep(TextProcessKind.APPEND, text = expression)), "steps", 7).isNotEmpty())
        assertEquals("ok", TextProcessing.apply(listOf(TextProcessStep(TextProcessKind.APPEND, text = expression)), "") {
            assertEquals(selection, HistoryViews.selection(it)); "ok"
        })
        assertTrue(RecognitionEvaluator.validate(Recognition(rules = listOf(RecognitionRule(template = expression)))).isEmpty())
    }

    @Test fun twoOccurrencesKeepTheirOwnFloorRulesNamesAndExplicitCompleteRange() {
        val global = HistoryConfig(
            range = HistoryRange(HistoryPoint(HistoryAnchor.START, 2.0), HistoryPoint(HistoryAnchor.START, 2.0)),
            apiRole = Role.SYSTEM, mergeMessages = true, prefix = "global start", suffix = "global end",
            floorPrefix = "global floor", speakers = listOf(HistorySpeakerRule(Role.USER, Role.SYSTEM, "global user")),
            floors = listOf(HistoryFloorRule("reply-1", Role.SYSTEM, "global reply")),
            insertions = listOf(HistoryInsertion(template = "global insertion")))
        val main = main(global)
        val lib = library(main)
        val before = session()
        val scoped = HistorySelection(compressed = true, rangeExplicit = true, format = HistoryReferenceFormat(
            speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, Role.USER, "Gary: "))))
        val named = scoped.copy(format = HistoryReferenceFormat(floorPrefix = "[{{speaker.name}}] ", apiRole = Role.ASSISTANT))
        val preview = PromptCompiler().preview(lib, before, node(PromptBlock(id = "first", template = HistoryReferences.expression(scoped)),
            PromptBlock(id = "second", template = HistoryReferences.expression(named))), "")
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(6, preview.messages.size)
        assertEquals(listOf("question", "Gary: display answer", "latest"), preview.messages.take(3).map { it.text })
        assertEquals(listOf(Role.USER, Role.USER, Role.USER), preview.messages.take(3).map { it.role })
        assertEquals(listOf("[Original user] question", "[Gary] display answer", "[Original user] latest"),
            preview.messages.drop(3).map { it.text })
        assertTrue(preview.messages.drop(3).all { it.role == Role.ASSISTANT })
        assertEquals(messages, before.messages)
        assertEquals(HistoryMemoryState(), before.historyMemory)
        assertEquals(global, main.history)
        val full = "{{type:builtin.in:history.name:context.from:start-1.through:end-1}}"
        assertEquals(3, HistoryViews.sources(before, HistoryViews.selection(full)!!, main).size)
        assertEquals(1, HistoryViews.sources(before, HistoryViews.selection(full, 7)!!, main).size)
        assertEquals(1, HistoryViews.sources(before, HistorySelection(compressed = true), main).size)
    }

    @Test fun mixedTextKeepsOuterRoleAndEmptyOmissionNeverHidesFormatErrors() {
        val main = main()
        val lib = library(main)
        val selection = HistorySelection(format = HistoryReferenceFormat(floorPrefix = "{{speaker.name}}: ", apiRole = Role.USER))
        val expression = HistoryReferences.expression(selection)
        val preview = PromptCompiler().preview(lib, session(), node(PromptBlock(role = Role.SYSTEM, template = "Heading\n$expression\nEnd")), "")
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(1, preview.messages.size)
        assertEquals(Role.SYSTEM, preview.messages.single().role)
        assertEquals("Heading\nOriginal user: question\nGary: display answer\nOriginal user: latest\nEnd", preview.messages.single().text)
        val invalid = listOf(
            "{\"apiRole\":\"TOOL\"}", "{\"processor\":{}}", "{\"floorPrefix\":",
            "{\"speakers\":[{\"speaker\":\"USER\"},{\"speaker\":\"USER\"}]}",
            "{\"floorPrefix\":\"{{input}}\"}", "{\"speakers\":[{\"speaker\":\"ASSISTANT\",\"unknown\":true}]}"
        ).map(::queryJson) + expression.dropLast(3)
        invalid.forEach { broken ->
            val block = PromptBlock(template = broken, hideWhenReferencesEmpty = true,
                processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "must not conceal error")))
            val rejected = PromptCompiler().preview(lib, session(), node(block), "")
            assertTrue(broken, rejected.issues.any { it.error })
            assertNull(HistoryViews.selection(broken))
        }
        val missingRange = selection.copy(range = HistoryRange(HistoryPoint(HistoryAnchor.START, 10.0)), rangeExplicit = true)
        val hidden = PromptCompiler().preview(lib, session(), node(PromptBlock(template = "Heading ${HistoryReferences.expression(missingRange)}",
            hideWhenReferencesEmpty = true, processing = listOf(TextProcessStep(TextProcessKind.APPEND, text = "unwanted")))), "")
        assertTrue(hidden.issues.toString(), hidden.issues.none { it.error })
        assertTrue(hidden.messages.isEmpty())
    }

    @Test fun localFormatReadsExactSavedSourcesWithoutChangingMemoryOrInventingSummaryAuthors() {
        val main = main()
        val lib = library(main)
        val original = session()
        val sources = original.messages.map(HistoryMemory::source)
        val segment = HistoryMemory.segment("Earlier", "saved summary", messages.take(2), main.history.processor.workflow.id)
        val marked = original.copy(historyMemory = HistoryMemory.apply(original.historyMemory, HistoryMemoryPatch(segments = listOf(segment))))
        val saved = marked.copy(historyMemory = HistoryMemory.apply(marked.historyMemory,
            HistoryViews.copy(marked, HistorySelection(), "saved", "Saved", HistorySegmentMode.COMPRESS, listOf(segment.id), main)))
        val memory = saved.historyMemory
        val selection = HistorySelection(viewId = "saved", compressed = true, format = HistoryReferenceFormat(
            floorPrefix = "{{speaker.name}}: ", apiRole = Role.USER,
            speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, Role.USER, "Gary: "))))
        val projected = HistoryViews.project(lib, saved, main, selection)
        assertTrue(projected.issues.toString(), projected.issues.isEmpty())
        assertEquals(2, projected.messages.size)
        assertEquals("history-memory:${segment.id}", projected.messages.first().id)
        assertTrue(projected.messages.first().text.startsWith("[记忆 "))
        assertFalse(projected.messages.first().text.contains("Gary:"))
        assertTrue(projected.messages.all { it.role == Role.USER })
        assertEquals("Original user: latest", projected.messages.last().text)
        assertEquals(memory, saved.historyMemory)
        assertEquals(sources, saved.messages.map(HistoryMemory::source))
        val raw = HistoryViews.project(lib, saved, main, HistorySelection(sourceKind = HistorySourceKind.MODEL_RAW,
            format = HistoryReferenceFormat(speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, Role.USER, "Gary: ")))))
        assertTrue(raw.issues.toString(), raw.issues.isEmpty())
        assertEquals(listOf("question", "Gary: raw answer", "latest"), raw.messages.map { it.text })
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), saved.messages.map { it.role })
    }

    @Test fun failedPromptRepairUsesCapturedNamesAndLegacyJournalKeepsItsAcceptedContract(): Unit = runBlocking {
        val block = PromptBlock(id = "history", template = "{{history.display}}")
        val model = WorkflowNode(id = "model", outputKey = "answer", inputTemplate = "", includeHistory = false, blocks = listOf(block))
        val flow = Workflow(id = "chat", nodes = listOf(model))
        val main = main().copy(workflows = listOf(flow))
        val lib = library(main)
        val before = session()
        var calls = 0
        val prompts = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                prompts += messages
                if (++calls == 1) throw ModelRequestFailure("offline failure", retryable = false)
                return "accepted"
            }
        }
        val engine = WorkflowEngine()
        val paused = engine.run(lib, before, "latest", gateway)
        assertEquals(paused.traces.toString(), RunStatus.PAUSED, paused.status)
        val journal = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), paused.recovery!!))
        assertEquals(8, journal.executionVersion)
        val pending = MessageOperations.recordPartialTurn(before, paused).session!!
        val legacy = journal.copy(executionVersion = 7)
        val oldResult = engine.resume(lib, pending.copy(pendingRun = legacy), gateway, legacy,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "chat/model"))
        assertEquals(oldResult.traces.toString(), RunStatus.SUCCESS, oldResult.status)
        assertEquals(prompts.first().map { it.role to it.text }, prompts[1].map { it.role to it.text })
        val format = HistoryReferenceFormat(speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, Role.USER, "{{speaker.name}}: ")))
        val repairedModel = model.copy(blocks = listOf(block.copy(template = HistoryReferences.expression(HistorySelection(format = format)))))
        val repairedMain = main.copy(workflows = listOf(flow.copy(nodes = listOf(repairedModel))))
        val repaired = lib.copy(mainCalls = listOf(repairedMain))
        val resumed = engine.resume(repaired, pending, gateway, journal, RecoveryDecision(RecoveryDecisionKind.RETRY, "chat/model"))
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(3, calls)
        assertEquals(listOf("question", "Gary: display answer", "latest"), prompts.last().map { it.text })
        assertEquals(listOf(Role.USER, Role.USER, Role.USER), prompts.last().map { it.role })
        assertEquals(before.historyMemory, resumed.historyMemory)
        assertEquals(before.messages, journal.initialSession.messages)
        val drifted = repaired.copy(mainCalls = listOf(repairedMain.copy(history = repairedMain.history.copy(floorPrefix = "global drift"))))
        val conflict = engine.resume(drifted, pending, gateway, journal, RecoveryDecision(RecoveryDecisionKind.RETRY, "chat/model"))
        assertEquals(RunStatus.PAUSED, conflict.status)
        assertTrue(conflict.traces.last().detail.contains("结构或环境已变化"))
        assertEquals(3, calls)
    }
}
