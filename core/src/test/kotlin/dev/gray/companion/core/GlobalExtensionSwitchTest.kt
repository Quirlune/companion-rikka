package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class GlobalExtensionSwitchTest {
    private val ref = ExtensionResourceRef(ExtensionResourceKind.GROUP, "group")
    private val card = CardResource(id = "card", sourceTemplate = "retained value",
        definition = CardDefinition(title = "Status", fields = listOf(CardFieldDefinition("value", "Value")),
            defaultRegion = CardRegion.MENU))
    private fun base(enabled: Boolean? = true): Library = Library(
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList())),
        groups = listOf(CallGroup(id = "group", name = "Status", cards = listOf(card))),
        extensions = listOf(ConfigurationExtension(id = "extension", name = "Status", resources = listOf(ref), enabled = enabled)))

    @Test fun globalSwitchLoadsAcrossMainsAndOffGatesManualBindingsWithSharedOwners() {
        val library = base().copy(groups = base().groups + CallGroup(id = "manual", name = "Manual"))
        val session = Session(mainCallId = "another", groupIds = listOf("group", "manual"))
        assertEquals(listOf("group", "manual"), library.loadedCallGroups(session).map { it.id })
        val off = library.copy(extensions = library.extensions.map { it.copy(enabled = false) })
        assertEquals(listOf("manual"), off.loadedCallGroups(session).map { it.id })
        val shared = off.copy(extensions = off.extensions + off.extensions.single().copy(id = "other", enabled = true))
        assertEquals(listOf("group", "manual"), shared.loadedCallGroups(session).map { it.id })
    }

    @Test fun liveOffHidesFrozenCardAndOnRestoresSameAcceptedDefinition() {
        val library = base()
        val session = Session(mainCallId = "main")
        val world = CompletedWorlds.capture(library, session, "")
        val restored = session.copy(executableWorld = world)
        assertEquals("retained value", CardDisplays.resolve(library, restored).single().snapshot!!.fields.single().value)
        val off = library.copy(extensions = library.extensions.map { it.copy(enabled = false) })
        assertTrue(CardDisplays.resolve(off, restored).isEmpty())
        assertTrue(CardDisplays.effectivePlacements(off, restored).isEmpty())
        assertSame(world, restored.executableWorld)
        assertEquals(CardRegion.MENU, CardDisplays.effectivePlacements(library, restored).single().region)
        assertEquals("retained value", CardDisplays.resolve(library, restored).single().snapshot!!.fields.single().value)
    }

    @Test fun migrationPreservesCardIdentityMainScopeAndFrozenWorldWithoutActivatingImports() {
        val original = base(null)
        val session = Session(mainCallId = "main", enabledExtensionIds = listOf("extension"))
        val world = CompletedWorlds.capture(original, session, "", 22)
        val placement = CardPlacement("card", CardRegion.MENU)
        val live = original.copy(groups = emptyList(), extensions = listOf(
            original.extensions.single().copy(resources = emptyList()), ConfigurationExtension(id = "dormant", name = "Dormant")),
            mainCalls = listOf(original.mainCalls.single().copy(cards = listOf(card))),
            sessions = listOf(session.copy(executableWorld = world, cardPlacements = listOf(placement))))
        val migrated = ExtensionLiveMigration.upgrade(live)
        assertTrue(migrated.mainCalls.single().cards.isEmpty())
        assertEquals(listOf(card), migrated.groups.single().cards)
        assertEquals(true, migrated.extensions.first { it.id == "extension" }.enabled)
        assertEquals(false, migrated.extensions.first { it.id == "dormant" }.enabled)
        assertEquals("main", migrated.extensions.last().mainScopeId)
        assertSame(world, migrated.sessions.single().executableWorld)
        assertEquals(listOf(placement), migrated.sessions.single().cardPlacements)
        assertEquals(migrated, ExtensionLiveMigration.upgrade(migrated))
        assertTrue(migrated.loadedCallGroups(Session(mainCallId = "another")).isEmpty())
        assertEquals(migrated.groups, migrated.loadedCallGroups(Session(mainCallId = "main")))
    }

    @Test fun newTurnUsesOffPolicyButDoesNotRewriteHistoricalWorldOrDefinitions() {
        val original = base()
        val session = Session(mainCallId = "main")
        val world = CompletedWorlds.capture(original, session, "")
        val off = original.copy(extensions = original.extensions.map { it.copy(enabled = false) })
        val next = CompletedWorlds.withExtensionPolicyForNewTurn(off, session.copy(executableWorld = world))
        assertTrue(CompletedWorlds.executionLibrary(off, next).loadedCallGroups(next).isEmpty())
        assertEquals(true, world.library.extensions.single().enabled)
        assertEquals(world.library.groups, next.executableWorld!!.library.groups)
    }

    @Test fun disabledReachableOwnersRemainCapturedToGateManualBindings() {
        val library = base(false)
        val session = Session(mainCallId = "main", groupIds = listOf("group"))
        val world = CompletedWorlds.capture(library, session, "")
        // The manual root must retain its disabled owner even though it is not a participant.
        assertEquals(false, world.library.extensions.single().enabled)
        assertEquals("group", world.library.groups.single().id)
        assertTrue(world.library.loadedCallGroups(session).isEmpty())
    }
}
