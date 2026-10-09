package dev.gray.companion.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class CollectionCompatibilityTest {
    private fun base() = LibraryFormat.upgrade(Library(
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList(),
            boundPackageIds = listOf("shared", "manual"), boundGroupIds = listOf("group"))),
        packages = listOf(PromptPackage(id = "manual", namespace = "manual", name = "Manual"),
            PromptPackage(id = "shared", namespace = "shared", name = "Shared")),
        groups = listOf(CallGroup(id = "group", name = "Group"))))

    private fun legacy(main: MainCall) = main.copy(
        extensionBindings = listOf(ExtensionBinding("enabled-a", "a", true), ExtensionBinding("enabled-b", "b", true),
            ExtensionBinding("disabled", "c", false)),
        extensionBindingOrigins = listOf(
            ExtensionBindingOrigin(ExtensionResourceKind.PACKAGE, "manual", true, listOf("enabled-a")),
            ExtensionBindingOrigin(ExtensionResourceKind.PACKAGE, "shared", false, listOf("enabled-a", "enabled-b")),
            ExtensionBindingOrigin(ExtensionResourceKind.GROUP, "group", true, listOf("enabled-a"))))

    private fun image(main: MainCall): JsonElement = LibraryFormat.json.encodeToJsonElement(MainCall.serializer(), main)
    private fun assertCanonical(image: JsonElement?) {
        val value = image as JsonObject
        assertEquals(JsonArray(emptyList()), value["extensionBindings"])
        assertEquals(JsonArray(emptyList()), value["extensionBindingOrigins"])
    }

    @Test fun liveAndLocalReceiptMigrationPreserveActualAssociationsOrderAndCapturedWorlds() {
        val original = base()
        val oldMain = legacy(original.mainCalls.single())
        val receipt = ConfigurationEditReceipt(id = "receipt-a", createdAt = 123, label = "Old edit", changes = listOf(
            ConfigurationChange("mainCall", "main", image(oldMain.copy(name = "Before")), image(oldMain), 0, 0)))
        val otherReceipt = ConfigurationEditReceipt(id = "receipt-b", createdAt = 456, label = "Other edit")
        val frozen = original.copy(schemaVersion = 11, mainCalls = listOf(oldMain))
        val accepted = Session(id = "session", mainCallId = "main", messages = listOf(ChatMessage(id = "floor", text = "retained")),
            state = mapOf("value" to "accepted"), results = mapOf("result" to "accepted result"))
        val recovery = RunRecovery(id = "run", sessionId = accepted.id, turnId = "floor", input = "retained",
            checkpoint = TurnCheckpoint(accepted.runtimeSnapshot()), initialSession = accepted, frozenResources = frozen,
            executionVersion = 8)
        val pending = accepted.copy(pendingRun = recovery)
        val old = frozen.copy(sessions = listOf(pending), selectedSessionId = accepted.id,
            savedChats = listOf(SavedChat(id = "saved", folderId = "folder", snapshot = pending)),
            configurationEdits = listOf(receipt, otherReceipt))
        val migrated = LibraryFormat.upgrade(old)
        val main = migrated.mainCalls.single()
        assertEquals(12, migrated.schemaVersion)
        assertEquals(original.mainCalls.single(), main)
        assertSame(oldMain.boundPackageIds, main.boundPackageIds)
        assertSame(oldMain.boundGroupIds, main.boundGroupIds)
        val repeated = oldMain.copy(boundPackageIds = listOf("shared", "manual", "shared"))
        assertEquals(repeated.boundPackageIds, CollectionCompatibility.canonicalMain(repeated).boundPackageIds)
        assertEquals(listOf("receipt-a", "receipt-b"), migrated.configurationEdits.map { it.id })
        assertEquals(listOf(123L, 456L), migrated.configurationEdits.map { it.createdAt })
        assertSame(otherReceipt, migrated.configurationEdits.last())
        val upgradedReceipt = migrated.configurationEdits.first()
        assertCanonical(upgradedReceipt.changes.single().before)
        assertCanonical(upgradedReceipt.changes.single().after)
        assertEquals((receipt.changes.single().after as JsonObject)["boundPackageIds"],
            (upgradedReceipt.changes.single().after as JsonObject)["boundPackageIds"])
        assertEquals(upgradedReceipt, LibraryFormat.json.decodeFromString(ConfigurationEditReceipt.serializer(),
            LibraryFormat.json.encodeToString(ConfigurationEditReceipt.serializer(), upgradedReceipt)))
        val upgradedPending = migrated.sessions.single().pendingRun!!
        assertSame(accepted, upgradedPending.initialSession)
        assertSame(recovery.checkpoint, upgradedPending.checkpoint)
        assertEquals(recovery.executionVersion, upgradedPending.executionVersion)
        assertEquals(oldMain, upgradedPending.frozenResources.mainCalls.single())
        assertEquals(recovery.copy(frozenResources = frozen.copy(schemaVersion = 12)), upgradedPending)
        assertEquals(migrated.sessions.single(), migrated.savedChats.single().snapshot)
        assertSame(pending.messages, migrated.sessions.single().messages)
        assertSame(migrated, LibraryFormat.upgrade(migrated))
        val decoded = LibraryFormat.decode(LibraryFormat.json.encodeToJsonElement(Library.serializer(), old) as JsonObject)
        assertEquals(main, decoded.mainCalls.single())
        assertEquals(oldMain, decoded.sessions.single().pendingRun!!.frozenResources.mainCalls.single())
        assertTrue(decoded.configurationEdits.isEmpty()) // Local SQLite receipts remain outside exported Library JSON.
    }

    @Test fun oldMainImagesApplyAndUndoWithoutRestoringOwnersButActualConfigurationConflictsStillFail() {
        val initial = base()
        val before = legacy(initial.mainCalls.single())
        val after = before.copy(name = "Renamed", boundPackageIds = listOf("manual", "shared"),
            extensionBindings = before.extensionBindings.map { it.copy(enabled = !it.enabled) })
        val change = ConfigurationChange("mainCall", "main", image(before), image(after), 0, 0)
        val plan = ConfigurationPlan("Old imported edit", listOf(change))
        val applied = ConfigurationCommands.apply(plan, initial)
        assertNotNull(applied.issues.toString(), applied.library)
        val actual = applied.library!!
        assertEquals(CollectionCompatibility.canonicalMain(after), actual.mainCalls.single())
        assertCanonical(applied.receipt!!.changes.single().before)
        assertCanonical(applied.receipt!!.changes.single().after)
        val rawReceipt = ConfigurationEditReceipt(id = "old-receipt", createdAt = 7, label = plan.label, changes = plan.changes)
        val loaded = LibraryFormat.upgrade(actual.copy(configurationEdits = listOf(rawReceipt)))
        val undone = ConfigurationCommands.undo(rawReceipt, loaded)
        assertNotNull(undone.issues.toString(), undone.library)
        assertEquals(initial.mainCalls, undone.library!!.mainCalls)
        assertEquals(loaded.configurationEdits, undone.library!!.configurationEdits)
        assertTrue(undone.library!!.mainCalls.single().extensionBindingOrigins.isEmpty())
        val changedBindingOrder = loaded.copy(mainCalls = listOf(loaded.mainCalls.single().copy(boundPackageIds = listOf("shared", "manual"))))
        assertNull(ConfigurationCommands.undo(rawReceipt, changedBindingOrder).library)
        val changedConfiguration = loaded.copy(mainCalls = listOf(loaded.mainCalls.single().copy(description = "later actual edit")))
        assertTrue(ConfigurationCommands.undo(rawReceipt, changedConfiguration).issues.any { it.message.contains("后续编辑") })
        // Migration is a load/save concern; a configuration preview cannot rewrite local receipt records.
        val dirtyLocalReceipts = loaded.copy(configurationEdits = listOf(rawReceipt))
        assertNull(ConfigurationCommands.previewEdit(loaded, dirtyLocalReceipts).plan)
        assertNull(ConfigurationCommands.previewEdit(dirtyLocalReceipts, dirtyLocalReceipts).plan)
        assertNull(ConfigurationCommands.previewEdit(loaded, loaded.copy(sessions = listOf(Session(id = "forbidden")))).plan)
    }
}
