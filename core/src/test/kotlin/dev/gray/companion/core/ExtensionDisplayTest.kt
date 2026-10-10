package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ExtensionDisplayTest {
    private val extension = ConfigurationExtension(id = "extension", name = "Display extension",
        resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, "display")))
    private val display = CallGroup(id = "display", name = "Display", cards = listOf(
        CardResource(id = "card-resource", sourceTemplate = "room", definition = CardDefinition(
            title = "Place", defaultRegion = CardRegion.CHAT, fields = listOf(CardFieldDefinition("place", "Place"))))))

    private fun base(): Library = LibraryFormat.upgrade(Library(
        characters = listOf(CharacterCard(id = "card", namespace = "card", name = "Card")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList())),
        groups = listOf(display), extensions = listOf(extension),
        sessions = listOf(Session(id = "one", characterId = "card", mainCallId = "main", state = mapOf("score" to "7")),
            Session(id = "two", characterId = "card", mainCallId = "main")), selectedSessionId = "one"))

    private fun ok(result: ExtensionEditResult): Library {
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        return requireNotNull(result.library)
    }

    @Test fun creatingDisplayOwnerNeedsNoMainOrSessionAndDeletingCollectionKeepsItsCards() {
        val emptyDefinition = extension.copy(resources = emptyList())
        val initial = LibraryFormat.upgrade(Library(extensions = listOf(emptyDefinition)))
        val added = ok(ExtensionCommands.addDisplayGroup(initial, emptyDefinition, display))
        assertTrue(added.mainCalls.isEmpty())
        assertTrue(added.sessions.isEmpty())
        assertEquals(listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, "display")), added.extensions.single().resources)
        assertEquals(display.cards, added.groups.single().cards)
        val removed = ok(ExtensionCommands.deleteDefinition(added, extension.id))
        assertTrue(removed.extensions.isEmpty())
        assertEquals(added.groups, removed.groups)
        assertNull(ExtensionCommands.addDisplayGroup(initial.copy(extensions = emptyList()), emptyDefinition, display).library)
        assertNull(ExtensionCommands.addDisplayGroup(initial.copy(groups = listOf(display)), emptyDefinition, display).library)
    }

    @Test fun sessionSelectionShowsExtensionCardWithoutBindingMainOrTouchingAnotherChat() {
        val initial = base()
        val joined = ok(ExtensionCommands.setDisplayGroupForSession(initial, extension, "one", "display", true))
        assertEquals(initial.mainCalls, joined.mainCalls)
        assertEquals(initial.sessions.last(), joined.sessions.last())
        assertEquals(initial.sessions.first().state, joined.sessions.first().state)
        assertEquals(listOf("display"), joined.sessions.first().groupIds)
        val visible = CardDisplays.resolve(joined, joined.sessions.first()).single()
        assertEquals("room", visible.snapshot!!.fields.single().value)
        assertTrue(CardDisplays.configured(joined, joined.sessions.last()).isEmpty())
        val repeat = ok(ExtensionCommands.setDisplayGroupForSession(joined, extension, "one", "display", true))
        assertEquals(joined, repeat)
        val removed = ok(ExtensionCommands.setDisplayGroupForSession(joined, extension, "one", "display", false))
        assertEquals(initial, removed)
    }

    @Test fun removingLocalAssociationKeepsInheritedMainBindingAndAllResources() {
        val initial = base().let { it.copy(mainCalls = it.mainCalls.map { main -> main.copy(boundGroupIds = listOf("display")) }) }
        val joined = ok(ExtensionCommands.setDisplayGroupForSession(initial, extension, "one", "display", true))
        val removed = ok(ExtensionCommands.setDisplayGroupForSession(joined, extension, "one", "display", false))
        assertEquals(initial.mainCalls, removed.mainCalls)
        assertEquals(initial.groups, removed.groups)
        assertEquals(listOf(display.id), removed.loadedCallGroups(removed.sessions.first()).map { it.id })
        val collectionDeleted = ok(ExtensionCommands.deleteDefinition(joined, extension.id))
        assertEquals(joined.sessions, collectionDeleted.sessions)
        assertEquals(joined.groups, collectionDeleted.groups)
    }

    @Test fun changedSelectionDefinitionMissingMemberAndPendingRunRejectSessionEdits() {
        val initial = base()
        assertNull(ExtensionCommands.setDisplayGroupForSession(initial.copy(selectedSessionId = "two"), extension, "one", "display", true).library)
        assertNull(ExtensionCommands.setDisplayGroupForSession(initial, extension.copy(name = "stale"), "one", "display", true).library)
        assertNull(ExtensionCommands.setDisplayGroupForSession(initial, extension, "one", "missing", true).library)
        val session = initial.sessions.first()
        val recovery = RunRecovery(id = "run", sessionId = session.id, turnId = "turn", input = "",
            checkpoint = TurnCheckpoint(session.runtimeSnapshot()), initialSession = session, frozenResources = initial.copy(sessions = emptyList(), selectedSessionId = null))
        val pending = initial.copy(sessions = listOf(session.copy(pendingRun = recovery), initial.sessions.last()))
        val blocked = ExtensionCommands.setDisplayGroupForSession(pending, extension, "one", "display", true)
        assertNull(blocked.library)
        assertTrue(blocked.issues.single().message.contains("断点"))
    }

    @Test fun cardRemainsOrdinaryPortableGroupContentWithStrictUndo() {
        val initial = LibraryFormat.upgrade(Library())
        val imported = LibraryFormat.upgrade(Library(groups = listOf(display), extensions = listOf(extension)))
        val preview = ConfigurationCommands.previewResources(LibraryFormat.decode(LibraryFormat.encode(imported)), initial)
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), initial)
        val actual = requireNotNull(applied.library)
        assertEquals(display.cards, actual.groups.single().cards)
        assertEquals(initial, ConfigurationCommands.undo(requireNotNull(applied.receipt), actual).library)
    }
}
