package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class CharacterCardTest {
    @Test fun legacyCardKeepsTextIdsExtrasAndWorkingMacros() {
        val overview = PromptEntry(id = "old-profile", key = "identity", text = "<bio>旧角色资料</bio>")
        val examples = PromptEntry(id = "old-examples", key = "examples", text = "旧对话")
        val extra = PromptEntry(id = "extra", key = "instructions", text = "额外规则", condition = Condition("ready"))
        val old = CharacterCard(id = "hero", namespace = "hero", name = "角色", entries = listOf(overview, examples, extra))
        val card = CharacterFields.normalize(old)
        assertEquals(CharacterFields.definitions.map { it.key }, card.entries.take(7).map { it.key })
        assertEquals("old-profile", card.entries.first { it.key == "profile" }.id)
        assertEquals("旧对话", card.entries.first { it.key == "dialogue_examples" }.text)
        assertEquals(extra, card.entries.last())
        assertEquals(card, CharacterFields.normalize(card))

        val node = WorkflowNode(includeHistory = false, inputTemplate = "", blocks = listOf(PromptBlock(
            template = "{{character.identity#bio}}|{{character.profile#bio}}|{{character.examples}}|{{character.appearance}}"
        )))
        val preview = PromptCompiler().preview(Library(characters = listOf(card)), Session(characterId = card.id), node, "")
        assertEquals("旧角色资料|旧角色资料|旧对话|", preview.messages.first().text)
        assertFalse(preview.issues.any { it.message.contains("appearance") })
        assertEquals(old.entries, listOf(overview, examples, extra))
    }

    @Test fun explicitLegacyAndCanonicalEntriesAreNotMergedOrDiscarded() {
        val old = CharacterCard(id = "hero", namespace = "hero", name = "角色", entries = listOf(
            PromptEntry(key = "profile", text = "新概览"), PromptEntry(key = "identity", text = "保留的旧词条")
        ))
        val card = CharacterFields.normalize(old)
        assertEquals(8, card.entries.size)
        val node = WorkflowNode(includeHistory = false, inputTemplate = "", blocks = listOf(PromptBlock(template = "{{character.profile}}|{{character.identity}}")))
        val preview = PromptCompiler().preview(Library(characters = listOf(card)), Session(characterId = card.id), node, "")
        assertEquals("新概览|保留的旧词条", preview.messages.first().text)
        val inactiveLegacy = card.copy(entries = card.entries.map { if (it.key == "identity") it.copy(enabled = false) else it })
        val inactivePreview = PromptCompiler().preview(Library(characters = listOf(inactiveLegacy)), Session(characterId = card.id), node, "")
        assertEquals("新概览|", inactivePreview.messages.first().text)

        val duplicate = card.copy(entries = card.entries + PromptEntry(key = "profile", text = "重复"))
        val normalized = CharacterFields.normalize(duplicate)
        assertTrue(LibraryValidator.validate(Library(characters = listOf(normalized))).any { it.error && it.message.contains("重复") })
    }

    @Test fun newConversationRetainsChoicesAndLeavesOldRuntimeIntact() {
        val old = Session(id = "old", characterId = "hero", mainCallId = "main", packageIds = listOf("lore"), groupIds = listOf("affinity"),
            messages = listOf(ChatMessage(text = "旧消息")), state = mapOf("affinity" to "4"), results = mapOf("analysis" to "旧结果"))
        val library = Library(sessions = listOf(old), selectedSessionId = old.id)
        val next = library.startNewConversation()
        assertEquals(old, next.sessions.first())
        val fresh = next.sessions.last()
        assertNotEquals(old.id, fresh.id)
        assertEquals(fresh.id, next.selectedSessionId)
        assertEquals(old.characterId, fresh.characterId)
        assertEquals(old.mainCallId, fresh.mainCallId)
        assertEquals(old.packageIds, fresh.packageIds)
        assertEquals(old.groupIds, fresh.groupIds)
        assertTrue(fresh.messages.isEmpty() && fresh.state.isEmpty() && fresh.results.isEmpty())
        assertEquals(1, library.sessions.size)
    }

    @Test fun choosingAnotherCharacterStartsFreshWithoutChangingPreviousChat() {
        val old = Session(id = "old", characterId = "first", mainCallId = "main", packageIds = listOf("lore"), groupIds = listOf("group"),
            messages = listOf(ChatMessage(text = "原聊天消息")), state = mapOf("affinity" to "3"), results = mapOf("draft" to "原结果"))
        val library = Library(characters = listOf(CharacterCard(id = "first"), CharacterCard(id = "second")), sessions = listOf(old), selectedSessionId = old.id)
        assertSame(library, library.selectCharacter("first"))
        val next = library.selectCharacter("second")
        assertEquals(old, next.sessions.first())
        val fresh = next.sessions.last()
        assertEquals("second", fresh.characterId)
        assertEquals(old.mainCallId, fresh.mainCallId)
        assertEquals(old.packageIds, fresh.packageIds)
        assertEquals(old.groupIds, fresh.groupIds)
        assertTrue(fresh.messages.isEmpty() && fresh.state.isEmpty() && fresh.results.isEmpty())
        assertSame(next, next.selectCharacter("second"))
        try { library.selectCharacter("missing"); fail("Missing cards must not create chats") }
        catch (_: IllegalArgumentException) { assertEquals(1, library.sessions.size) }
    }
}
