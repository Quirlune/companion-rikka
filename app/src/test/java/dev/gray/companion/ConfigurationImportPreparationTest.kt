package dev.gray.companion

import dev.gray.companion.core.*
import org.junit.Assert.*
import org.junit.Test

class ConfigurationImportPreparationTest {
    @Test fun compiledDocumentKeepsSourceWarningsAndUsesTheSameStrictConfigurationReceipt() {
        val current = LibraryFormat.upgrade(Library(packages = listOf(PromptPackage(
            id = "unrelated", namespace = "unrelated", name = "原有资源"))))
        val document = LibraryFormat.json.parseToJsonElement("""{
          "format":"companion.config.v1","label":"分文件资源",
          "resources":{"packages":[{"id":"imported","namespace":"imported","name":"导入资源",
          "entries":[{"id":"entry","key":"body","text":"第一行\\n第二行"}]}]}
        }""") as kotlinx.serialization.json.JsonObject
        val warning = Issue("archive.README.md", "说明文档仅作为包说明", error = false)
        val prepared = ConfigurationImportPreparer.prepare(document, current, "document-preview", false, listOf(warning))
        assertTrue(prepared.issues.toString(), prepared.draft is ConfigurationImportDraft.Configuration)
        assertEquals(1, prepared.preview.issues.count { it == warning })
        assertEquals(listOf(warning), prepared.draft!!.warnings)
        val applied = ConfigurationImportPreparer.apply(prepared.draft!!, current)
        assertNotNull(applied.issues.toString(), applied.library)
        assertNotNull(applied.receipt)
        val later = applied.library!!.copy(packages = applied.library!!.packages.map {
            if (it.id == "unrelated") it.copy(name = "后续编辑") else it
        })
        val undone = ConfigurationCommands.undo(applied.receipt!!, later)
        assertNotNull(undone.issues.toString(), undone.library)
        assertEquals("后续编辑", undone.library!!.packages.single().name)
        val touched = later.copy(packages = later.packages.map {
            if (it.id == "imported") it.copy(name = "精修过") else it
        })
        assertNull(ConfigurationCommands.undo(applied.receipt!!, touched).library)
        val refused = ConfigurationImportPreparer.prepare(document, current, "refused", false,
            listOf(Issue("archive.bad", "文件错误")))
        assertNull(refused.draft)
        assertTrue(refused.preview.issues.any { it.error })
    }

    @Test fun resourceOnlyBackupPreparesUndoablePlanWithoutChangingCurrentLibrary() {
        val current = LibraryFormat.upgrade(Library())
        val imported = LibraryFormat.upgrade(Library(packages = listOf(
            PromptPackage(id = "pack", namespace = "lore", name = "设定", entries = emptyList()))))

        val prepared = ConfigurationImportPreparer.prepare(LibraryFormat.encode(imported), current, "preview", false)
        assertTrue(prepared.issues.toString(), prepared.issues.none { it.error })
        assertTrue(prepared.preview.undoable)
        assertEquals("preview", prepared.preview.id)
        assertTrue(prepared.draft is ConfigurationImportDraft.Configuration)
        assertTrue(current.packages.isEmpty())

        val applied = ConfigurationImportPreparer.apply(requireNotNull(prepared.draft), current)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        assertEquals("pack", applied.library!!.packages.single().id)
        assertNotNull(applied.receipt)
        assertTrue(current.packages.isEmpty())
    }

    @Test fun chatBackupKeepsExistingChatAndCannotOfferConfigurationUndo() {
        val existing = Session(id = "existing", messages = listOf(ChatMessage(id = "old", text = "旧消息")))
        val current = LibraryFormat.upgrade(Library(sessions = listOf(existing), selectedSessionId = existing.id))
        val archived = Session(id = "archived", messages = listOf(ChatMessage(id = "new", text = "备份消息")))
        val imported = LibraryFormat.upgrade(Library(sessions = listOf(archived), selectedSessionId = archived.id))

        val prepared = ConfigurationImportPreparer.prepare(LibraryFormat.encode(imported), current, "chat-preview", false)
        assertTrue(prepared.issues.toString(), prepared.issues.none { it.error })
        assertFalse(prepared.preview.undoable)
        assertTrue(prepared.draft is ConfigurationImportDraft.Legacy)
        assertTrue(prepared.issues.any { it.path == "import.chats" && !it.error })
        assertEquals(listOf(existing), current.sessions)

        val applied = ConfigurationImportPreparer.apply(requireNotNull(prepared.draft), current)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        assertNull(applied.receipt)
        assertEquals(listOf(existing, archived), applied.library!!.sessions)
        assertEquals(existing.id, applied.library!!.selectedSessionId)
    }

    @Test fun fixedResourcePreviewRejectsLaterIdCollisionAtApply() {
        val current = LibraryFormat.upgrade(Library())
        val imported = LibraryFormat.upgrade(Library(packages = listOf(
            PromptPackage(id = "pack", namespace = "lore", name = "设定", entries = emptyList()))))
        val prepared = ConfigurationImportPreparer.prepare(LibraryFormat.encode(imported), current, "preview", false)
        assertTrue(prepared.draft is ConfigurationImportDraft.Configuration)
        val changed = current.copy(packages = listOf(
            PromptPackage(id = "pack", namespace = "other", name = "后续新增", entries = emptyList())))

        val result = ConfigurationImportPreparer.apply(requireNotNull(prepared.draft), changed)
        assertNull(result.library)
        assertTrue(result.issues.any { it.error && it.message.contains("后续编辑") })
        assertEquals("后续新增", changed.packages.single().name)
    }
}
