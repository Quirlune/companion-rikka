package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ConversationCandidateDeletionTest {
    private val origin = ResultOrigin("main", "flow", "node")
    private fun result(session: Session, answer: String): RunResult {
        val turn = session.messages.last { it.role == Role.USER }.id
        return RunResult(state = mapOf("place" to answer), results = mapOf("answer" to answer),
            replies = listOf(ChatMessage(role = Role.ASSISTANT, text = answer, turnId = turn)), traces = emptyList(),
            resultOrigins = mapOf("answer" to origin),
            macroHistory = mapOf("story" to (session.macroHistory["story"].orEmpty() +
                MacroVersion(session.macroHistory["story"].orEmpty().size + 1, answer, origin, turnId = turn))))
    }
    private fun accept(session: Session, answer: String) =
        MessageOperations.recordSuccessfulTurn(session, result(session, answer)).session!!
    private fun initial() = accept(Session(id = "chat", messages = listOf(ChatMessage(id = "u1", text = "hello"))), "A")
    private fun roll(session: Session, answer: String): Session {
        val base = MessageOperations.regenerationBase(session, "u1").session!!
        val prepared = MessageOperations.prepareRegeneration(base, "u1").session!!
        return MessageOperations.commitRegeneration(base, prepared, result(prepared, answer)).session!!
    }
    private fun continueWith(session: Session, id: String, answer: String) =
        accept(session.copy(messages = session.messages + ChatMessage(id = id, text = "continue")), answer)
    private fun roundTrip(session: Session) = LibraryFormat.json.decodeFromString(Session.serializer(),
        LibraryFormat.json.encodeToString(Session.serializer(), session))

    @Test fun deletingSelectedCandidateRemovesArchivedCopiesAndCannotResurrect() {
        val a = initial()
        val aId = a.turnRecords.single().selectedVariantId!!
        val b = roll(a, "B")
        val back = ConversationPaths.selectGenerationVariant(b, "u1", aId).session!!
        assertTrue(back.inactivePaths.isNotEmpty())
        val operation = ConversationPaths.deleteGenerationVariant(back, "u1", aId)
        assertTrue(operation.issues.toString(), operation.succeeded)
        val deleted = roundTrip(operation.session!!)
        assertEquals(a.id, deleted.id)
        assertEquals(listOf("B"), ConversationPaths.generationVariants(deleted, "u1").flatMap { it.replies }.map { it.text })
        assertFalse(ConversationPaths.selectGenerationVariant(deleted, "u1", aId).succeeded)
        assertEquals("B", deleted.state["place"])
        assertEquals(listOf("B"), deleted.macroHistory["story"]!!.map { it.text })
        assertTrue(ConversationPaths.validate(deleted).isEmpty())
    }

    @Test fun deletingHistoricalCandidateKeepsOtherFutureAndIndependentPromptBranches() {
        val a = continueWith(initial(), "u2", "A future")
        val aId = a.turnRecords.first().selectedVariantId!!
        val b = continueWith(roll(a, "B"), "u3", "B future")
        val bId = b.turnRecords.first().selectedVariantId!!
        val otherPrompt = ConversationPaths.editUserAndSend(b, "u1", "other prompt").session!!
        val revisionId = otherPrompt.messages.single().activeTextRevisionId!!
        val other = continueWith(accept(otherPrompt, "C"), "u4", "C future")
        val backToOriginalPrompt = ConversationPaths.selectUserVersion(other, "u1", "u1").session!!
        val selectedA = ConversationPaths.selectGenerationVariant(backToOriginalPrompt, "u1", aId).session!!
        val independent = selectedA.inactivePaths.single {
            it.messages.first().activeTextRevisionId == revisionId
        }
        val deleted = roundTrip(ConversationPaths.deleteGenerationVariant(selectedA, "u1", aId).session!!)
        assertEquals(a.id, deleted.id)
        assertEquals(b.messages, deleted.messages)
        assertEquals(b.macroHistory, deleted.macroHistory)
        assertEquals(b.state, deleted.state)
        assertEquals(listOf(bId), ConversationPaths.generationVariants(deleted, "u1").map { it.id })
        assertTrue(deleted.inactivePaths.contains(independent))
        assertFalse(ConversationPaths.selectGenerationVariant(deleted, "u1", aId).succeeded)
        val selectedOther = ConversationPaths.selectUserVersion(deleted, "u1", revisionId).session!!
        assertEquals(other.messages, selectedOther.messages)
        val returned = ConversationPaths.selectUserVersion(selectedOther, "u1", "u1").session!!
        assertFalse(ConversationPaths.selectGenerationVariant(returned, "u1", aId).succeeded)
        assertTrue(ConversationPaths.validate(returned).isEmpty())
    }

    @Test fun deletingUnselectedCandidatePreservesActiveFutureAndLastCandidateRestoresCheckpoint() {
        val a = initial()
        val aId = a.turnRecords.single().selectedVariantId!!
        val b = continueWith(roll(a, "B"), "u2", "B future")
        val bId = b.turnRecords.first().selectedVariantId!!
        val withoutA = ConversationPaths.deleteGenerationVariant(b, "u1", aId).session!!
        assertEquals(b.messages, withoutA.messages)
        assertEquals(b.macroHistory, withoutA.macroHistory)
        assertEquals(b.state, withoutA.state)
        val empty = ConversationPaths.deleteGenerationVariant(withoutA, "u1", bId).session!!
        assertEquals(b.id, empty.id)
        assertEquals(listOf("u1"), empty.messages.filter { !it.deleted }.map { it.id })
        assertTrue(empty.state.isEmpty())
        assertTrue(empty.macroHistory.values.flatten().isEmpty())
        assertTrue(ConversationPaths.generationVariants(empty, "u1").isEmpty())
        assertFalse(ConversationPaths.selectGenerationVariant(empty, "u1", bId).succeeded)
        assertTrue(ConversationPaths.validate(empty).isEmpty())
    }
}
