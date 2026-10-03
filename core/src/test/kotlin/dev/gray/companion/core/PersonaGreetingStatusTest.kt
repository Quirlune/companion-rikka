package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGreetingStatusTest {
    @Test fun newChatSnapshotsPersonaAndRendersOneOpeningWithoutRewritingHistory() {
        val old = UserPersona(id = "old", name = "旧名", prompt = "旧指令")
        val selected = UserPersona(id = "selected", name = "新名", prompt = "新指令")
        val card = CharacterCard(id = "card", namespace = "hero", name = "角色",
            entries = listOf(PromptEntry(key = "profile", text = "欢迎 {{user.name}}", isTemplate = true)),
            greeting = "{{character.profile}} · {{user.name}}")
        val main = MainCall(id = "main", name = "主调用")
        val prior = Session(id = "prior", characterId = card.id, mainCallId = main.id, personaId = old.id,
            messages = listOf(ChatMessage(role = Role.USER, text = "之前", speakerName = old.name),
                ChatMessage(role = Role.USER, text = "更早")))
        val library = Library(characters = listOf(card), mainCalls = listOf(main), personas = listOf(old, selected),
            selectedPersonaId = selected.id, sessions = listOf(prior), selectedSessionId = prior.id)
        val freshLibrary = library.startNewConversation()
        val fresh = freshLibrary.sessions.last()
        assertEquals(selected.id, fresh.personaId)
        assertEquals(listOf("欢迎 新名 · 新名"), fresh.messages.map { it.text })
        assertEquals(Role.ASSISTANT, fresh.messages.single().role)
        assertEquals("旧名", freshLibrary.sessions.first().messages.first().speakerName)
        assertEquals("旧指令", MacroResolver(library, prior).read("user.prompt"))
        assertEquals("新指令", MacroResolver(freshLibrary, fresh).read("user.prompt"))
        val switched = library.selectPersonaForSession(prior.id, selected.id).sessions.single()
        assertEquals(listOf("旧名", "旧名"), switched.messages.map { it.speakerName })
        assertEquals(selected.id, switched.personaId)
    }

    @Test fun explicitEntryCyclesReportIssueAndArchivedPersonaReferencesAreValidated() {
        val card = CharacterCard(id = "card", namespace = "hero", name = "角色", entries = listOf(
            PromptEntry(key = "a", text = "{{character.b}}", isTemplate = true),
            PromptEntry(key = "b", text = "{{character.a}}", isTemplate = true)))
        val session = Session(id = "chat", characterId = card.id, mainCallId = "main", personaId = "missing")
        val library = Library(characters = listOf(card), mainCalls = listOf(MainCall(id = "main", name = "主调用")),
            savedChats = listOf(SavedChat(folderId = "folder", snapshot = session)),
            chatFolders = listOf(ChatFolder(id = "folder")))
        val resolver = MacroResolver(library, session)
        resolver.read("character.a")
        assertTrue(resolver.issues.any { it.error && it.message.contains("循环") })
        assertTrue(LibraryValidator.validate(library).any { it.path.contains("savedChat") && it.path.endsWith("personaId") })
    }

    @Test fun statusFieldsAreInertScalarsAndBoundsAreValidated() {
        val xml = "<status><location>庭院</location><love>42</love></status>"
        assertEquals("庭院", StatusFields.read(xml, "status/location"))
        assertEquals("42", StatusFields.read(xml, "love"))
        assertEquals(null, StatusFields.read("<!DOCTYPE x><status><love>42</love></status>", "love"))
        assertFalse(StatusFields.validKey("status//love"))
        val tile = MacroTile(kind = MacroTileKind.STATUS, fields = listOf(
            MacroStatusField(key = "status/love", title = "亲近", kind = MacroTileKind.PROGRESS,
                minimum = 5.0, maximum = 5.0)))
        val library = Library(mainCalls = listOf(MainCall(id = "main", name = "主调用", tiles = listOf(tile))))
        assertTrue(LibraryValidator.validate(library).any { it.path.contains("fields.0") && it.error })
    }
}
