package dev.gray.companion.core

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ConversationMetadataTest {
    @Test fun freshConversationsUseLocalDateTimeWithoutPendingTitle() {
        assertEquals("1970-01-01 08:00:00", ConversationMetadata.defaultTitle(0L, ZoneId.of("Asia/Shanghai")))
        val library = LibraryDefaults.create()
        val next = library.startNewConversation()
        val pattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
        (library.sessions + next.sessions).forEach {
            assertTrue(it.title, pattern.matches(it.title))
            assertFalse(it.titlePending)
        }
        assertNotEquals(library.selectedSessionId, next.selectedSessionId)
    }

    @Test fun duplicatePreservesAllDataAndRebindsPendingOwner() {
        val path = ConversationPathSnapshot(id = "path", messages = listOf(ChatMessage(id = "old", text = "old path")),
            turnRecords = emptyList(), runtime = RuntimeSnapshot(state = mapOf("place" to "old")),
            macroHistory = emptyMap(), choices = CapturedSessionChoices("character", "main"), updatedAt = 23L)
        val initial = Session(id = "original", title = "聊天", characterId = "character", mainCallId = "main",
            messages = listOf(ChatMessage(id = "user", text = "hello")), state = mapOf("place" to "new"),
            results = mapOf("answer" to "kept"), inactivePaths = listOf(path), folderId = "folder", updatedAt = 42L)
        val recovery = RunRecovery(id = "run", sessionId = initial.id, turnId = "user", input = "hello",
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = initial, frozenResources = Library(),
            environments = listOf(RecoveryEnvironment("environment", "event", initial, "hello")))
        val source = initial.copy(pendingRun = recovery)
        val library = Library(sessions = listOf(source), selectedSessionId = source.id)
        val copiedLibrary = ChatArchive.duplicate(library, source.id)
        val copied = copiedLibrary.sessions.first()
        assertNotEquals(source.id, copied.id)
        assertEquals(copied.id, copiedLibrary.selectedSessionId)
        assertEquals(source.title + " 副本", copied.title)
        assertEquals(source, copiedLibrary.sessions.last())
        assertEquals(source.copy(id = copied.id, title = copied.title, pendingRun = copied.pendingRun), copied)
        val rebound = copied.pendingRun!!
        assertEquals(copied.id, rebound.sessionId)
        assertEquals(copied.id, rebound.initialSession.id)
        assertTrue(rebound.environments.all { it.session.id == copied.id })
        assertEquals(recovery.copy(sessionId = copied.id, initialSession = initial.copy(id = copied.id),
            environments = recovery.environments.map { it.copy(session = it.session.copy(id = copied.id)) }), rebound)
    }

    @Test fun folderMovesAndDeletionPreserveChatsAndProtectSavedSnapshots() {
        val original = Session(id = "chat", messages = listOf(ChatMessage(text = "keep")))
        val library = Library(sessions = listOf(original), chatFolders = listOf(ChatFolder("folder", "目录")))
        val moved = ConversationMetadata.move(library, original.id, "folder")
        assertEquals(original.copy(folderId = "folder"), moved.sessions.single())
        val removed = ConversationMetadata.deleteFolder(moved, "folder")
        assertTrue(removed.chatFolders.isEmpty())
        assertEquals(original, removed.sessions.single())
        val protected = moved.copy(savedChats = listOf(SavedChat(folderId = "folder", snapshot = original)))
        try {
            ConversationMetadata.deleteFolder(protected, "folder")
            fail("A folder referenced by a saved snapshot must not be deleted")
        } catch (_: IllegalArgumentException) {
            assertEquals("folder", protected.savedChats.single().folderId)
        }
        assertEquals(original, ConversationMetadata.move(moved, original.id, null).sessions.single())
    }
}
