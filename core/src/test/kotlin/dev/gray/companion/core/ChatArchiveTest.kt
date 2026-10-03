package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatArchiveTest {
    @Test fun restoredBreakpointKeepsAcceptedIdentityAndCanContinue() = runBlocking {
        val before = Session(id = "source", characterId = "character", mainCallId = "main",
            messages = listOf(ChatMessage(id = "turn", text = "hello")))
        val flow = Workflow(id = "flow", nodes = listOf(
            WorkflowNode(id = "kept", actionId = "text.render", inputTemplate = "kept", outputKey = "kept", visible = true),
            WorkflowNode(id = "failed", outputKey = "failed")))
        val base = Library(characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(flow))),
            profiles = listOf(ModelProfile(id = "profile", model = "demo")), selectedProfileId = "profile",
            sessions = listOf(before), chatFolders = listOf(ChatFolder("folder")))
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String = throw ModelRequestFailure("offline")
        }
        val engine = WorkflowEngine()
        val first = engine.run(base, before, "hello", gateway)
        val paused = MessageOperations.recordPartialTurn(before, first).session!!
        val originalJournal = paused.pendingRun!!
        val saved = ChatArchive.save(base.copy(sessions = listOf(paused)), paused.id, "folder")
        val restored = ChatArchive.restore(saved, saved.savedChats.single().id)
        val chat = restored.sessions.first()
        val journal = chat.pendingRun!!
        assertNotEquals(paused.id, chat.id)
        assertEquals(chat.id, journal.sessionId)
        assertEquals(chat.id, journal.initialSession.id)
        assertTrue(journal.environments.all { it.session.id == chat.id })
        assertEquals(originalJournal.accepted, journal.accepted)
        assertEquals(paused.turnRecords, chat.turnRecords)
        val resumed = engine.resume(restored, chat, gateway, journal,
            RecoveryDecision(RecoveryDecisionKind.MANUAL, "flow/failed", manualText = "fixed"))
        assertEquals(RunStatus.SUCCESS, resumed.status)
        val continued = MessageOperations.continuePartialTurn(chat, resumed).session!!
        assertEquals(chat.id, continued.id)
        assertEquals(first.replies.single().id, continued.messages.first { it.nodeId == "kept" }.id)
        assertEquals(paused, restored.savedChats.single().snapshot)

        val candidateId = "historical-candidate"
        val historical = originalJournal.copy(regenerating = true, sessionId = candidateId,
            initialSession = originalJournal.initialSession.copy(id = candidateId),
            environments = originalJournal.environments.map { it.copy(session = it.session.copy(id = candidateId)) },
            regenerationBase = before.copy(id = candidateId))
        val historicalSource = paused.copy(pendingRun = historical)
        val historicalSaved = ChatArchive.save(base.copy(sessions = listOf(historicalSource)), paused.id, "folder")
        val historicalChat = ChatArchive.restore(historicalSaved, historicalSaved.savedChats.single().id).sessions.first()
        val historicalJournal = historicalChat.pendingRun!!
        assertNotEquals(candidateId, historicalJournal.sessionId)
        assertNotEquals(historicalChat.id, historicalJournal.sessionId)
        assertEquals(historicalJournal.sessionId, historicalJournal.initialSession.id)
        assertEquals(historicalJournal.sessionId, historicalJournal.regenerationBase!!.id)
        assertTrue(historicalJournal.environments.all { it.session.id == historicalJournal.sessionId })
        assertEquals(historical.accepted, historicalJournal.accepted)
        assertEquals(historical.id, historicalJournal.id)
        assertEquals(historicalSource, historicalSaved.savedChats.single().snapshot)
    }

    @Test fun sourceChangesNeverAlterSnapshotAndRestoringAlwaysCreatesIndependentSession() {
        val message = ChatMessage(id = "user-1", text = "first", speakerName = "User")
        val origin = ResultOrigin("main", "flow", "node")
        val session = Session(id = "source", title = "Original", messages = listOf(message),
            state = mapOf("affection" to "2"), results = mapOf("note" to "saved"),
            macroHistory = mapOf("result.note" to listOf(MacroVersion(1, "saved", origin, turnId = message.id, turn = 1))),
            historyDigests = mapOf("main" to HistoryDigest("summary", message.id, 1, "processor")))
        val folder = ChatFolder(id = "folder", name = "Saved")
        val original = Library(sessions = listOf(session), selectedSessionId = session.id, chatFolders = listOf(folder))
        val saved = ChatArchive.save(original, session.id, folder.id)
        val edited = saved.copy(sessions = listOf(session.copy(messages = listOf(message.copy(text = "changed")), state = emptyMap())))
        assertEquals("first", edited.savedChats.single().snapshot.messages.single().text)
        assertEquals("2", edited.savedChats.single().snapshot.state["affection"])
        val restored = ChatArchive.restore(edited, edited.savedChats.single().id)
        val chat = restored.sessions.first()
        assertNotEquals(session.id, chat.id)
        assertEquals(chat.id, restored.selectedSessionId)
        assertEquals(session.messages, chat.messages)
        assertEquals(session.macroHistory, chat.macroHistory)
        assertEquals(session.historyDigests, chat.historyDigests)
        val again = ChatArchive.restore(restored, restored.savedChats.single().id)
        assertNotEquals(chat.id, again.sessions.first().id)
        assertEquals(edited.savedChats, again.savedChats)
    }

    @Test fun threeHourBinsKeepUnreportedUsageDistinctAndHonorLocalClockBoundaries() {
        val zone = java.time.ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.of(2026, 3, 8, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun time(hour: Int) = java.time.ZonedDateTime.of(2026, 3, 8, hour, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun record(hour: Int, tokens: Long?) = ModelUsageRecord(sessionId = "s", profileId = "m", model = "model",
            startedAt = time(hour), finishedAt = time(hour), status = UsageStatus.SUCCESS, totalTokens = tokens)
        val bins = UsageAggregation.threeHourly(listOf(record(0, 12), record(3, null), record(4, 20), record(6, null)), now, 1, zone)
        assertEquals(8, bins.size)
        assertEquals(12L, bins[0].totalTokens)
        assertEquals(20L, bins[1].totalTokens)
        assertEquals(1, bins[1].unknownCalls)
        assertNull(bins[2].totalTokens)
        assertEquals(1, bins[2].callCount)
        assertEquals(0L, bins[3].totalTokens)
        assertEquals(2 * 60 * 60 * 1000L, bins[1].startedAt - bins[0].startedAt)
    }
}
