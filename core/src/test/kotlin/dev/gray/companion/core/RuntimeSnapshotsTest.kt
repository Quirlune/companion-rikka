package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeSnapshotsTest {
    @Test fun runtimeCarriersRoundTripWithoutReplacingConversationOrRecoveryIdentity() {
        val origin = ResultOrigin("source-main", "flow", "node")
        val floor = ChatMessage(id = "source-floor", role = Role.ASSISTANT, text = "display", rawText = "model")
        val sources = listOf(HistoryMemory.source(floor, HistorySourceKind.MODEL_RAW))
        val captureId = HistoryMemory.captureId(sources)
        val memory = HistoryMemoryState(
            segments = mapOf("segment" to HistoryMemorySegment("segment", "memory", "summary", sources, "flow")),
            captures = mapOf(captureId to sources),
            views = mapOf("view" to listOf(HistoryViewRevision("view", "view", 1, captureId,
                sourceKind = HistorySourceKind.MODEL_RAW))),
            unavailableViewIds = setOf("pending-view")
        )
        val source = Session(id = "source", messages = listOf(floor),
            state = mapOf("count" to "3"), results = mapOf("reply" to "accepted"),
            resultOrigins = mapOf("reply" to origin),
            promptInjections = listOf(PromptInjection("injection", PromptBlock(id = "block", template = "remember"), origin)),
            historyDigests = mapOf("main" to HistoryDigest("digest", floor.id, 1, "processor")),
            unavailableResultKeys = setOf("pending-result"), historyMemory = memory,
            currentOnlyMacros = mapOf("state.count" to MacroHead(4, origin, gap = MacroGap(2))))
        val expected = RuntimeSnapshot(source.state, source.results, source.resultOrigins,
            source.promptInjections, source.historyDigests, source.unavailableResultKeys,
            source.historyMemory, source.currentOnlyMacros)
        val checkpoint = TurnCheckpoint(RuntimeSnapshot(state = mapOf("before" to "1")))
        val record = TurnRecord("destination-user", checkpoint,
            variants = listOf(TurnVariant(id = "destination-variant")), selectedVariantId = "destination-variant")
        val destination = Session(id = "destination", title = "destination title", characterId = "character",
            mainCallId = "destination-main", packageIds = listOf("pack"), groupIds = listOf("group"), personaId = "persona",
            messages = listOf(ChatMessage(id = "destination-user", text = "destination input")),
            macroHistory = mapOf("result.reply" to listOf(MacroVersion(1, "destination history", origin))),
            turnRecords = listOf(record), state = mapOf("old" to "removed"), updatedAt = 42L, isPinned = true)
        val journal = RunRecovery("pending", destination.id, "destination-user", "captured input", checkpoint,
            destination, Library(), state = source.state, results = source.results,
            resultOrigins = source.resultOrigins, promptInjections = source.promptInjections,
            historyDigests = source.historyDigests, unavailableKeys = source.unavailableResultKeys,
            historyMemory = source.historyMemory, currentOnlyMacros = source.currentOnlyMacros,
            replies = listOf(floor), macroHistory = mapOf("source-only" to emptyList()))
        val current = destination.copy(pendingRun = journal)
        val result = RunResult(source.state, source.results, listOf(floor), emptyList(),
            resultOrigins = source.resultOrigins, promptInjections = source.promptInjections,
            historyDigests = source.historyDigests, unavailableResultKeys = source.unavailableResultKeys,
            historyMemory = source.historyMemory, currentOnlyMacros = source.currentOnlyMacros,
            recovery = journal, macroHistory = mapOf("source-only" to emptyList()))

        for (snapshot in listOf(source.runtimeSnapshot(), result.runtimeSnapshot(), journal.runtimeSnapshot())) {
            assertEquals(expected, snapshot)
            val restored = current.withRuntime(snapshot)
            assertEquals(expected, restored.runtimeSnapshot())
            assertEquals(current, restored.withRuntime(current.runtimeSnapshot()))
            assertSame(current.messages, restored.messages)
            assertSame(current.macroHistory, restored.macroHistory)
            assertSame(current.turnRecords, restored.turnRecords)
            assertSame(journal, restored.pendingRun)
            assertEquals(source, source.withRuntime(expected))
        }
    }
}
