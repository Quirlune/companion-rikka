package dev.gray.companion.core

import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AvatarImageTest {
    private val png = AvatarImage("image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMScAAAAASUVORK5CYII=")

    @Test fun avatarIsPortableOptionalMetadataAndNeverPromptContent() {
        val json = Json { encodeDefaults = true }
        val legacy = json.decodeFromString<CharacterCard>("""{"namespace":"hero","name":"角色"}""")
        assertNull(legacy.avatar)
        val card = legacy.copy(avatar = png)
        assertEquals(card, json.decodeFromString<CharacterCard>(json.encodeToString(CharacterCard.serializer(), card)))
        assertTrue(LibraryValidator.validate(Library(characters = listOf(card))).isEmpty())
        val preview = PromptCompiler().preview(Library(characters = listOf(card)), Session(characterId = card.id),
            WorkflowNode(inputTemplate = "", includeHistory = false, blocks = listOf(PromptBlock(template = "{{character.name}}"))), "")
        assertEquals("角色", preview.messages.single().text)
        assertFalse(preview.messages.any { png.base64 in it.text })
    }

    @Test fun oversizedOrNonImageAvatarsAreRejectedBeforeStorage() {
        val huge = ByteArray(AvatarImage.MAX_BYTES + 1).apply { this[0] = 0xff.toByte(); this[1] = 0xd8.toByte(); this[2] = 0xff.toByte() }
        val invalid = listOf(AvatarImage(base64 = "invalid?"), png.copy(mediaType = "image/svg+xml"), png.copy(mediaType = "image/jpeg"), AvatarImage(base64 = Base64.getEncoder().encodeToString(huge)))
        for (image in invalid) {
            val card = CharacterCard(namespace = "hero", name = "角色", avatar = image)
            assertNull(image.decodedBytes())
            assertTrue(LibraryValidator.validate(Library(characters = listOf(card))).any { it.error && it.path.endsWith(".avatar") })
            val main = MainCall(id = "main", name = "Main", workflows = emptyList(), avatar = image)
            assertTrue(LibraryValidator.validate(Library(mainCalls = listOf(main))).any { it.error && it.path == "main.main.avatar" })
        }
    }

    @Test fun mainAvatarRoundTripsWithoutRewritingCapturedResources() {
        val legacy = LibraryFormat.json.decodeFromString<MainCall>("""{"id":"main","name":"Main","workflows":[]}""")
        assertNull(legacy.avatar)
        val main = legacy.copy(avatar = png)
        val captured = LibraryFormat.upgrade(Library(mainCalls = listOf(main)))
        val original = Session(id = "session", mainCallId = main.id, messages = listOf(ChatMessage(id = "floor", text = "kept")))
        val recovery = RunRecovery(id = "run", sessionId = original.id, turnId = "floor", input = "kept",
            checkpoint = MessageOperations.captureCheckpoint(original), initialSession = original, frozenResources = captured)
        val current = captured.copy(mainCalls = listOf(captured.mainCalls.single().copy(avatar = null)), sessions = listOf(original.copy(pendingRun = recovery)))
        assertTrue(LibraryValidator.validate(current).none { it.error })
        val restored = LibraryFormat.decode(LibraryFormat.encode(current))
        assertEquals(current, restored)
        assertNull(restored.mainCalls.single().avatar)
        assertEquals(png, restored.sessions.single().pendingRun!!.frozenResources.mainCalls.single().avatar)
        assertEquals(recovery, restored.sessions.single().pendingRun)
        val exported = LibraryFormat.decode(LibraryFormat.encode(captured))
        assertEquals(png, exported.mainCalls.single().avatar)
    }
}
