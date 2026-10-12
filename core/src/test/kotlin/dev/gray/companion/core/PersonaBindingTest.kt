package dev.gray.companion.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class PersonaBindingTest {
    private val bound = UserPersona(id = "bound", name = "绑定身份", prompt = "绑定设定")
    private val default = UserPersona(id = "default", name = "全局身份", prompt = "全局设定")
    private val previous = UserPersona(id = "previous", name = "旧身份", prompt = "旧设定")
    private val main = MainCall(id = "main", name = "主调用")

    private fun base(card: CharacterCard = CharacterCard(id = "card", namespace = "card", name = "角色",
        boundPersonaId = bound.id, greeting = "你好，{{user.name}}")) = Library(
        characters = listOf(card), mainCalls = listOf(main), personas = listOf(bound, default, previous),
        selectedPersonaId = default.id,
        sessions = listOf(Session(id = "old", characterId = card.id, mainCallId = main.id, personaId = previous.id,
            messages = listOf(ChatMessage(role = Role.USER, text = "之前")))), selectedSessionId = "old")

    @Test fun newChatUsesCharacterBindingWhileUnboundCardKeepsExistingFallback() {
        val library = base()
        val newChat = library.startNewConversation().sessions.last()
        assertEquals(bound.id, newChat.personaId)
        assertEquals("你好，绑定身份", newChat.messages.single().text)
        assertEquals("绑定设定", MacroResolver(library, newChat).read("user.prompt"))

        val withoutBinding = library.copy(characters = library.characters.map { it.copy(boundPersonaId = null) })
        assertEquals(default.id, withoutBinding.startNewConversation().sessions.last().personaId)
        assertEquals(previous.id, withoutBinding.copy(selectedPersonaId = null).startNewConversation().sessions.last().personaId)
    }

    @Test fun existingChatKeepsSavedIdentityAcrossCharacterAndGlobalChanges() {
        val library = base()
        val existing = library.sessions.single()
        val changed = library.copy(characters = library.characters.map { it.copy(boundPersonaId = default.id) },
            selectedPersonaId = bound.id, selectedSessionId = existing.id)
        assertEquals(previous.id, changed.sessions.single().personaId)
        assertEquals("旧设定", MacroResolver(changed, changed.sessions.single()).read("user.prompt"))
        val frozen = CapturedResourceClosure.capture(changed, changed.sessions.single())
        assertTrue(frozen.personas.any { it.id == previous.id })
        assertTrue(frozen.personas.any { it.id == default.id })
    }

    @Test fun referencedPersonaCannotBeRemovedAndOldWireCardsRemainReadable() {
        val library = LibraryFormat.upgrade(base())
        val attempted = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"remove","kind":"persona","id":"bound"}]}""", library)
        assertNull(attempted.plan)
        assertTrue(attempted.issues.any { it.path == "character.card.boundPersonaId" })

        val image = LibraryFormat.json.encodeToJsonElement(Library.serializer(), library) as JsonObject
        val card = (image["characters"] as JsonArray).single() as JsonObject
        val previousWire = JsonObject(image + mapOf("schemaVersion" to JsonPrimitive(30),
            "characters" to JsonArray(listOf(JsonObject(card.filterKeys { it != "boundPersonaId" })))))
        assertNull(LibraryFormat.decode(previousWire).characters.single().boundPersonaId)
        assertEquals(bound.id, LibraryFormat.decode(LibraryFormat.encode(library)).characters.single().boundPersonaId)
    }

    @Test fun archivedPathReferenceAlsoBlocksPersonaRemoval() {
        val oldSession = base().sessions.single().copy(personaId = default.id,
            inactivePaths = listOf(ConversationPathSnapshot(id = "branch", messages = emptyList(), turnRecords = emptyList(),
                runtime = RuntimeSnapshot(), macroHistory = emptyMap(),
                choices = CapturedSessionChoices("card", "main", previous.id))))
        val library = LibraryFormat.upgrade(base().copy(sessions = listOf(oldSession)))
        assertTrue(library.personaReferences(previous.id).any { it.contains("inactivePath.branch") })
        val attempted = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"remove","kind":"persona","id":"previous"}]}""", library)
        assertNull(attempted.plan)
        assertTrue(attempted.issues.any { it.path.contains("inactivePath.branch") })
    }
}
