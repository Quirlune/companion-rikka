package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalMessageOperationsTest {
    private val origin = ResultOrigin("main", "flow", "node")
    private val firstUser = ChatMessage(id = "u1", role = Role.USER, text = "first")
    private val secondUser = ChatMessage(id = "u2", role = Role.USER, text = "second")

    private fun version(number: Int, text: String, turnId: String) =
        MacroVersion(number, text, origin, turn = number, turnId = turnId)

    private fun run(turnId: String, answer: String, state: String, macros: List<MacroVersion>) = RunResult(
        state = mapOf("world" to state), results = mapOf("answer" to answer),
        replies = listOf(ChatMessage(id = "reply-$answer", role = Role.ASSISTANT, text = answer, turnId = turnId)),
        traces = emptyList(), resultOrigins = mapOf("answer" to origin),
        macroHistory = mapOf("story" to macros)
    )

    @Test fun historicalVariantForkRestoresWholeWorldAndSourceFutureRemains() {
        val staged = Session(messages = listOf(firstUser))
        val a = MessageOperations.recordSuccessfulTurn(staged,
            run(firstUser.id, "A", "one", listOf(version(1, "alpha", firstUser.id)))).session!!
        val regenerated = MessageOperations.prepareRegeneration(a, firstUser.id).session!!
        val b = MessageOperations.commitRegeneration(a, regenerated,
            run(firstUser.id, "B", "two", listOf(version(1, "beta", firstUser.id)))).session!!
        val withSecond = b.copy(messages = b.messages + secondUser)
        val source = MessageOperations.recordSuccessfulTurn(withSecond,
            run(secondUser.id, "future", "five", listOf(
                version(1, "beta", firstUser.id), version(2, "later", secondUser.id)
            ))).session!!
        val firstRecord = source.turnRecords.first()
        val aId = firstRecord.variants.first().id
        assertTrue(MessageOperations.capabilities(source, firstUser.id).canRegenerate)
        assertTrue(MessageOperations.capabilities(source, firstUser.id).canSelectGenerationVariant)

        val fork = MessageOperations.forkAtVariant(source, firstUser.id, aId).session!!
        assertNotEquals(source.id, fork.id)
        assertEquals(listOf("u1", "reply-A"), fork.messages.map { it.id })
        assertEquals("one", fork.state["world"])
        assertEquals(listOf("alpha"), fork.macroHistory["story"]!!.map { it.text })
        assertEquals(1, fork.turnRecords.size)
        assertEquals(aId, fork.turnRecords.single().selectedVariantId)
        assertTrue(fork.turnRecords.single().variants.first().replies.isEmpty())
        assertEquals(listOf("reply-A"), fork.turnRecords.single().variants.first().replyIds)
        assertEquals(listOf("beta", "later"), source.macroHistory["story"]!!.map { it.text })
        assertEquals("five", source.state["world"])
        assertEquals(listOf("u1", "reply-B", "u2", "reply-future"), source.messages.map { it.id })

        val base = MessageOperations.regenerationBase(source, firstUser.id).session!!
        assertNotEquals(source.id, base.id)
        assertEquals("two", base.state["world"])
        assertEquals(listOf("beta"), base.macroHistory["story"]!!.map { it.text })
        val candidate = MessageOperations.prepareRegeneration(base, firstUser.id).session!!
        assertTrue(candidate.state.isEmpty())
        val completedFork = MessageOperations.commitRegeneration(base, candidate,
            run(firstUser.id, "C", "three", listOf(version(1, "gamma", firstUser.id)))).session!!
        assertEquals(base.id, completedFork.id)
        assertEquals(3, completedFork.turnRecords.single().variants.size)
        assertEquals(listOf("gamma"), completedFork.macroHistory["story"]!!.map { it.text })
        assertEquals("five", source.state["world"])
        assertEquals(2, source.turnRecords.size)

        val retired = MessageOperations.deleteGenerationVariant(source, firstUser.id, aId).session!!
        assertEquals(source.id, retired.id)
        assertEquals(source.messages, retired.messages)
        assertEquals(source.macroHistory, retired.macroHistory)
        assertEquals(1, retired.turnRecords.first().variants.size)
        val removedActive = MessageOperations.deleteGenerationVariant(source, firstUser.id,
            firstRecord.selectedVariantId!!).session!!
        assertNotEquals(source.id, removedActive.id)
        assertEquals("one", removedActive.state["world"])
        assertEquals(listOf("u1", "reply-A"), removedActive.messages.map { it.id })
        assertEquals("five", source.state["world"])
    }

    @Test fun oldAnswersNeedRealValidRestoreBoundary() {
        val legacy = Session(messages = listOf(firstUser,
            ChatMessage(id = "legacy-answer", role = Role.ASSISTANT, text = "old", turnId = firstUser.id), secondUser))
        assertNull(MessageOperations.regenerationBase(legacy, firstUser.id).session)
        assertNull(MessageOperations.forkAtVariant(legacy, firstUser.id, "missing").session)

        val recorded = MessageOperations.recordSuccessfulTurn(Session(messages = listOf(firstUser)),
            run(firstUser.id, "A", "one", listOf(version(1, "alpha", firstUser.id)))).session!!
        val invalidated = MessageOperations.invalidateRestore(recorded.copy(messages = recorded.messages + secondUser))
        assertNull(MessageOperations.forkAtVariant(invalidated, firstUser.id,
            recorded.turnRecords.single().selectedVariantId!!).session)
    }
}
