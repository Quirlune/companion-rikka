package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ChatArchiveTest {
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
