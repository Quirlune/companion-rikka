package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ConversationPathsTest {
    private val origin = ResultOrigin("main", "flow", "node")
    private fun accepted(session: Session, answer: String): Session {
        val user = session.messages.last { it.role == Role.USER }
        return MessageOperations.recordSuccessfulTurn(session, result(session, user.id, answer)).session!!
    }
    private fun result(session: Session, turnId: String, answer: String) = RunResult(
        state = mapOf("place" to answer), results = mapOf("answer" to answer),
        replies = listOf(ChatMessage(role = Role.ASSISTANT, text = answer, turnId = turnId)), traces = emptyList(),
        macroHistory = mapOf("story" to (session.macroHistory["story"].orEmpty() +
            MacroVersion(session.macroHistory["story"].orEmpty().size + 1, answer, origin, turnId = turnId))))
    private fun initial() = accepted(Session(id = "chat", messages = listOf(ChatMessage(id = "u1", text = "cafe"))), "sunny")
    private fun continueWith(session: Session, id: String, text: String, answer: String) =
        accepted(session.copy(messages = session.messages + ChatMessage(id = id, text = text)), answer)

    @Test fun editingLastUserWithAcceptedSilentRoundRestoresItsCheckpoint() {
        val staged = Session(id = "chat", messages = listOf(ChatMessage(id = "u1", text = "old")))
        val completed = MessageOperations.recordSuccessfulTurn(staged, RunResult(
            state = mapOf("accepted" to "old effect"), results = mapOf("answer" to "old result"),
            replies = emptyList(), traces = emptyList(), macroHistory = mapOf("story" to listOf(
                MacroVersion(1, "old macro", origin, turnId = "u1")
            )))).session!!
        assertEquals(listOf("u1"), completed.messages.map { it.id })
        val edited = ConversationPaths.editUserAndSend(completed, "u1", "new").session!!
        assertEquals(listOf("new"), edited.messages.map { it.text })
        assertTrue(edited.state.isEmpty())
        assertTrue(edited.results.isEmpty())
        assertTrue(edited.macroHistory.values.flatten().isEmpty())
        assertTrue(edited.turnRecords.isEmpty())
        val original = ConversationPaths.selectUserVersion(edited, "u1", "u1").session!!
        assertEquals(completed.messages, original.messages)
        assertEquals(completed.state, original.state)
        assertEquals(completed.macroHistory, original.macroHistory)
    }

    @Test fun editedPromptOwnsRepliesAndContinuationAfterSerialization() {
        val original = continueWith(initial(), "u2", "tea", "old future")
        val edited = ConversationPaths.editUserAndSend(original, "u1", "train").session!!
        val revision = edited.messages.single().activeTextRevisionId!!
        assertEquals(revision, HistoryMemory.source(edited.messages.single(), HistorySourceKind.MODEL_RAW).revisionId)
        assertNotEquals(HistoryMemory.source(original.messages.first()).fingerprint,
            HistoryMemory.source(edited.messages.single()).fingerprint)
        assertEquals(original.id, edited.id)
        assertEquals(listOf("train"), edited.messages.map { it.text })
        assertTrue(edited.state.isEmpty())
        assertTrue(edited.results.isEmpty())
        assertTrue(edited.macroHistory.values.flatten().isEmpty())
        assertTrue(edited.turnRecords.isEmpty())
        val alternate = continueWith(accepted(edited, "rain"), "u3", "umbrella", "new future")
        val encoded = LibraryFormat.json.encodeToString(Session.serializer(), alternate)
        val decoded = LibraryFormat.json.decodeFromString(Session.serializer(), encoded)
        assertTrue(ConversationPaths.validate(decoded).isEmpty())
        val restored = ConversationPaths.selectUserVersion(decoded, "u1", "u1").session!!
        assertEquals(original.messages, restored.messages)
        assertEquals(original.state, restored.state)
        assertEquals(original.macroHistory, restored.macroHistory)
        assertEquals(2, ConversationPaths.userVersions(restored, "u1").size)
        assertEquals(listOf("sunny"), ConversationPaths.generationVariants(restored, "u1").flatMap { it.replies }.map { it.text })
        val back = ConversationPaths.selectUserVersion(restored, "u1", revision).session!!
        assertEquals(alternate.messages, back.messages)
        assertEquals("new future", back.state["place"])
        assertEquals(listOf("rain", "new future"), back.macroHistory["story"]!!.map { it.text })
    }

    @Test fun historicalRegenerationAndAiSelectionKeepEachFutureInSameChat() {
        val original = continueWith(initial(), "u2", "tea", "old future")
        val oldVariant = original.turnRecords.first().selectedVariantId!!
        val base = MessageOperations.regenerationBase(original, "u1").session!!
        assertEquals(original.id, base.id)
        val prepared = MessageOperations.prepareRegeneration(base, "u1").session!!
        assertEquals(listOf("cafe"), prepared.messages.map { it.text })
        assertTrue(prepared.results.isEmpty())
        val regenerated = MessageOperations.commitRegeneration(base, prepared, result(prepared, "u1", "snow")).session!!
        val newVariant = regenerated.turnRecords.first().selectedVariantId!!
        val alternate = continueWith(regenerated, "u3", "coat", "new future")
        val restored = ConversationPaths.selectGenerationVariant(alternate, "u1", oldVariant).session!!
        assertEquals(original.id, restored.id)
        assertEquals(original.messages, restored.messages)
        assertEquals(original.state, restored.state)
        assertEquals(2, ConversationPaths.generationVariants(restored, "u1").size)
        val back = ConversationPaths.selectGenerationVariant(restored, "u1", newVariant).session!!
        assertEquals(alternate.messages, back.messages)
        assertEquals(alternate.macroHistory, back.macroHistory)
        assertEquals(alternate.state, back.state)
    }

    @Test fun ancestryAndLegacyDefaultsDoNotInventReplyOwnership() {
        val original = continueWith(initial(), "u2", "tea", "old future")
        val oldU2 = original.turnRecords.last().selectedVariantId!!
        val alternate = ConversationPaths.editUserAndSend(original, "u1", "train").session!!
        assertTrue(ConversationPaths.userVersions(alternate, "u2").isEmpty())
        assertTrue(ConversationPaths.generationVariants(alternate, "u2").isEmpty())
        assertNull(ConversationPaths.selectGenerationVariant(alternate, "u2", oldU2).session)
        val legacy = LibraryFormat.json.decodeFromString(Session.serializer(), "{\"id\":\"old\"}")
        assertTrue(legacy.inactivePaths.isEmpty())
        assertNull(legacy.folderId)
        val snapshot = alternate.inactivePaths.single()
        val invalid = alternate.copy(inactivePaths = listOf(snapshot, snapshot))
        assertTrue(ConversationPaths.validate(invalid).any { it.error })
        assertTrue(CompletedWorlds.detachedSession(alternate, ExecutionSemantics.CURRENT).inactivePaths.isEmpty())
    }

    @Test fun editedUserCompilesOnlyNewPromptFromFrozenCheckpoint() {
        val node = WorkflowNode(id = "body", visible = true, blocks = listOf(PromptBlock(template = "{{history}}")))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "flow", nodes = listOf(node))))
        val library = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main))
        val before = Session(id = "chat", characterId = "card", mainCallId = main.id,
            messages = listOf(ChatMessage(id = "u1", text = "OLD_PROMPT_CAFE")))
        val world = CompletedWorlds.capture(library, before, "OLD_PROMPT_CAFE")
        val completed = MessageOperations.recordSuccessfulTurn(before,
            result(before, "u1", "OLD_ANSWER_SUNNY").copy(completedWorld = world)).session!!
        val original = continueWith(completed, "u2", "OLD_FUTURE_TEA", "OLD_FUTURE_REPLY")
        val edited = ConversationPaths.editUserAndSend(original, "u1", "NEW_PROMPT_TRAIN").session!!
        assertNotNull(edited.executableWorld)
        assertTrue(edited.executableWorld!!.environments.isEmpty())
        assertTrue(edited.executableWorld!!.recipes.isEmpty())
        val frozen = CompletedWorlds.executionLibrary(library.copy(mainCalls = emptyList()), edited)
        val frozenNode = frozen.mainCalls.single().workflows.single().nodes.single()
        val preview = PromptCompiler().preview(frozen, edited, frozenNode, "NEW_PROMPT_TRAIN", currentUserMessageId = "u1")
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(listOf("NEW_PROMPT_TRAIN"), preview.messages.map { it.text })
        assertEquals(1, edited.inactivePaths.size)
        assertTrue(edited.inactivePaths.single().messages.any { it.text == "OLD_FUTURE_REPLY" })
    }
}
