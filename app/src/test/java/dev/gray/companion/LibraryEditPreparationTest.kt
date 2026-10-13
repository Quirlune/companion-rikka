package dev.gray.companion

import dev.gray.companion.core.*
import org.junit.Assert.*
import org.junit.Test

class LibraryEditPreparationTest {
    @Test fun ordinarySaveCanEditActualAssociationsAfterCollectionOwnersAreRetired() {
        val definition = ConfigurationExtension(id = "ext", name = "扩展",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.PACKAGE, "pack")))
        val origin = ExtensionBindingOrigin(ExtensionResourceKind.PACKAGE, "pack", ownerBindingIds = listOf("binding"))
        val main = MainCall(id = "main", name = "主调用", boundPackageIds = listOf("pack"),
            extensionBindings = listOf(ExtensionBinding("binding", "ext", true)), extensionBindingOrigins = listOf(origin))
        val before = Library(mainCalls = listOf(main), extensions = listOf(definition))
        val erased = before.copy(mainCalls = listOf(MainCall(id = "main", name = "主调用")))
        assertTrue(LibraryEditPreparation.extensionMetadataIssues(before, erased).isEmpty())
        assertTrue(LibraryEditPreparation.extensionMetadataIssues(before,
            before.copy(mainCalls = listOf(main.copy(description = "changed")))).isEmpty())
        assertTrue(LibraryEditPreparation.extensionMetadataIssues(before,
            before.copy(extensions = emptyList())).any { it.error })
    }

    @Test fun legacyAuthorIsFrozenAndRuntimeEditsInvalidateRestoreOrPendingWorld() {
        val userFloor = ChatMessage(id = "user-floor", role = Role.USER, text = "你好", speakerName = null)
        val record = TurnRecord(userFloor.id, TurnCheckpoint(RuntimeSnapshot()))
        val session = Session(id = "chat", messages = listOf(userFloor), turnRecords = listOf(record))
        val before = LibraryFormat.upgrade(Library(user = UserIdentity(name = "旧名字"), sessions = listOf(session)))

        val renamed = before.copy(user = UserIdentity(name = "新名字"))
        val named = LibraryEditPreparation.normalize(before, renamed).sessions.single()
        assertEquals("旧名字", named.messages.single().speakerName)
        assertTrue(named.turnRecords.single().restoreAllowed)
        assertNull(before.sessions.single().messages.single().speakerName)

        val changedRuntime = before.copy(sessions = listOf(session.copy(state = mapOf("mood" to "new"))))
        val invalidated = LibraryEditPreparation.normalize(before, changedRuntime).sessions.single()
        assertFalse(invalidated.turnRecords.single().restoreAllowed)

        val pending = RunRecovery(id = "run", sessionId = session.id, turnId = userFloor.id, input = userFloor.text,
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = session, frozenResources = Library())
        val frozenBefore = before.copy(sessions = listOf(session.copy(pendingRun = pending)))
        val renamedWhilePaused = LibraryEditPreparation.prepareRecoveryEdits(frozenBefore,
            frozenBefore.copy(user = UserIdentity(name = "新名字")))
        assertEquals(pending, renamedWhilePaused.sessions.single().pendingRun)
        val editedWhilePaused = LibraryEditPreparation.normalize(frozenBefore,
            frozenBefore.copy(sessions = listOf(session.copy(pendingRun = pending, state = mapOf("mood" to "new")))))
        assertNull(editedWhilePaused.sessions.single().pendingRun)
        assertEquals("new", editedWhilePaused.sessions.single().state["mood"])
        assertFalse(editedWhilePaused.sessions.single().turnRecords.single().restoreAllowed)
        assertEquals(pending, frozenBefore.sessions.single().pendingRun)
    }

    @Test fun displayEditsPreserveContinuationWhileEnvironmentChangesRetireOnlyTheJournal() {
        val initial = Session(id = "session", mainCallId = "main", state = mapOf("accepted" to "kept"))
        val pending = RunRecovery(id = "run", sessionId = initial.id, turnId = "turn", input = "input",
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = initial, frozenResources = Library(),
            unavailableKeys = setOf("unfinished"))
        val session = initial.copy(pendingRun = pending)
        val before = Library(sessions = listOf(session), selectedSessionId = session.id)
        val layout = listOf(CardPlacement(nodeId = "card", region = CardRegion.CHAT))
        val shown = LibraryEditPreparation.prepareRecoveryEdits(before,
            before.copy(sessions = listOf(session.copy(title = "renamed", cardPlacements = layout))))
        assertEquals(pending, shown.sessions.single().pendingRun)
        assertEquals(pending, CardLayoutCommands.update(before, session.id, emptyList(), layout)
            .library!!.sessions.single().pendingRun)
        val rebound = LibraryEditPreparation.prepareRecoveryEdits(before,
            before.copy(sessions = listOf(session.copy(mainCallId = "different"))))
        assertNull(rebound.sessions.single().pendingRun)
        assertEquals(session.state, rebound.sessions.single().state)
        assertEquals(setOf("unfinished"), rebound.sessions.single().unavailableResultKeys)
        assertEquals("different", rebound.sessions.single().mainCallId)
    }
}
