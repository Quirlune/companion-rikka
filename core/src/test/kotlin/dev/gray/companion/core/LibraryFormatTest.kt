package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class LibraryFormatTest {
    @Test fun legacyResourcesAndCapturedWorldUpgradeWithoutChangingData() {
        val original = Session(id = "chat", messages = listOf(ChatMessage(id = "floor", text = "input")),
            state = mapOf("count" to "7"), results = mapOf("status" to "kept"))
        val frozen = Library(schemaVersion = 1, characters = listOf(CharacterCard(id = "character", namespace = "hero")))
        val recovery = RunRecovery(id = "run", sessionId = original.id, turnId = "floor", input = "input",
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = original, frozenResources = frozen)
        val pending = original.copy(pendingRun = recovery)
        val legacy = frozen.copy(sessions = listOf(pending), selectedSessionId = original.id,
            savedChats = listOf(SavedChat(id = "archive", folderId = "folder", snapshot = pending)))
        val oldWire = LibraryFormat.json.encodeToString(Library.serializer(), legacy)
            .replace(",\"formatVersion\":1", "")
            .replace(",\"executionVersion\":1", "")
        val restored = LibraryFormat.decode(oldWire)

        assertEquals(LibraryFormat.CURRENT_VERSION, restored.schemaVersion)
        val resumed = restored.sessions.single()
        assertEquals(original.messages, resumed.messages)
        assertEquals(original.state, resumed.state)
        assertEquals(original.results, resumed.results)
        assertEquals(recovery.id, resumed.pendingRun!!.id)
        assertEquals(1, resumed.pendingRun!!.formatVersion)
        assertEquals(1, resumed.pendingRun!!.executionVersion)
        assertEquals(recovery.initialSession, resumed.pendingRun!!.initialSession)
        assertEquals(LibraryFormat.CURRENT_VERSION, resumed.pendingRun!!.frozenResources.schemaVersion)
        assertEquals(resumed, restored.savedChats.single().snapshot)
        assertEquals(restored, LibraryFormat.decode(LibraryFormat.encode(restored)))
        assertSame(restored, LibraryFormat.upgrade(restored))

        val futureWire = oldWire.replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        assertTrue(runCatching { LibraryFormat.decode(futureWire) }.exceptionOrNull() is IllegalArgumentException)
    }
}
