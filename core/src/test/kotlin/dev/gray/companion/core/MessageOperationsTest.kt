package dev.gray.companion.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageOperationsTest {
    private val origin = ResultOrigin("main", "workflow", "node")
    private val user = ChatMessage(id = "user", role = Role.USER, text = "hello")

    private fun run(text: String, state: String, macro: String): RunResult = RunResult(
        state = mapOf("count" to state), results = mapOf("reply" to text),
        replies = listOf(ChatMessage(id = "reply-$text", role = Role.ASSISTANT, text = text, turnId = user.id)),
        traces = emptyList(), resultOrigins = mapOf("reply" to origin),
        macroHistory = mapOf("story" to listOf(MacroVersion(1, macro, origin, turn = 1, turnId = user.id))),
        promptInjections = listOf(PromptInjection("injection", PromptBlock(template = text), origin)),
        historyDigests = mapOf("main" to HistoryDigest(text, user.id, 1, "processor"))
    )

    @Test fun regenerationSwitchesWholeWorldWithoutStackingMacroOrState() {
        val staged = Session(messages = listOf(user))
        val first = MessageOperations.recordSuccessfulTurn(staged, run("A", "1", "alpha")).session!!
        assertTrue(first.turnRecords.single().variants.single().replies.isEmpty())
        assertEquals(listOf(first.messages.last().id), first.turnRecords.single().variants.single().replyIds)
        val candidate = MessageOperations.prepareRegeneration(first, user.id).session!!
        assertEquals(emptyMap<String, String>(), candidate.state)
        assertTrue(candidate.macroHistory.isEmpty())
        assertEquals(listOf(user.id), candidate.messages.map { it.id })
        assertNull(MessageOperations.commitRegeneration(first, candidate,
            run("failed", "99", "wrong").copy(status = RunStatus.FAILED)).session)

        val second = MessageOperations.commitRegeneration(first, candidate, run("B", "4", "beta")).session!!
        assertEquals("4", second.state["count"])
        assertEquals(listOf("beta"), second.macroHistory["story"]!!.map { it.text })
        val variants = second.turnRecords.single().variants
        assertEquals(2, variants.size)
        assertEquals(listOf("A"), variants.first().replies.map { it.text })
        assertTrue(variants.last().replies.isEmpty())
        assertEquals(listOf("B"), MessageOperations.repliesForVariant(second, second.turnRecords.single(), variants.last()).map { it.text })
        val back = MessageOperations.applyLatestVariant(second, user.id, variants.first().id).session!!
        assertEquals("1", back.state["count"])
        assertEquals("A", back.messages.last().text)
        assertEquals(listOf("alpha"), back.macroHistory["story"]!!.map { it.text })
        assertEquals("A", back.promptInjections.single().block.template)
        assertEquals("A", back.historyDigests["main"]!!.text)
        val forward = MessageOperations.applyLatestVariant(back, user.id, variants.last().id).session!!
        assertEquals("4", forward.state["count"])
        assertEquals(listOf("beta"), forward.macroHistory["story"]!!.map { it.text })
        assertEquals("B", forward.promptInjections.single().block.template)
        assertEquals("B", forward.historyDigests["main"]!!.text)
        val removed = MessageOperations.deleteGenerationVariant(forward, user.id, variants.last().id).session!!
        assertEquals("1", removed.state["count"])
        assertEquals("A", removed.messages.last().text)
        assertEquals(listOf("alpha"), removed.macroHistory["story"]!!.map { it.text })
        assertEquals(1, removed.turnRecords.single().variants.size)
        val newerFailedUser = removed.copy(messages = removed.messages + ChatMessage(
            id = "failed-user", role = Role.USER, text = "later"
        ))
        assertNull(MessageOperations.prepareRegeneration(newerFailedUser, user.id).session)
        assertEquals(newerFailedUser, MessageOperations.prepareRegeneration(newerFailedUser, "failed-user").session)
        val resumed = MessageOperations.commitRegeneration(newerFailedUser, newerFailedUser, RunResult(
            state = mapOf("count" to "2"), results = emptyMap(), traces = emptyList(),
            replies = listOf(ChatMessage(role = Role.ASSISTANT, text = "later answer", turnId = "failed-user")),
            macroHistory = newerFailedUser.macroHistory
        )).session!!
        assertEquals("1", resumed.turnRecords.last().checkpoint.runtime.state["count"])
        assertEquals("2", resumed.state["count"])
    }

    @Test fun textHistoryAndDeletionKeepLogicalTurnButDisappearFromPrompt() {
        val main = MainCall(id = "main", name = "main")
        val session = Session(mainCallId = main.id, messages = listOf(user),
            historyDigests = mapOf(main.id to HistoryDigest("old", user.id, 1, "processor")))
        val edited = MessageOperations.editText(session, user.id, "revised").session!!
        assertEquals(user.id, edited.messages.single().id)
        assertEquals(1, edited.messages.single().textRevisions.size)
        assertTrue(edited.historyDigests.isEmpty())
        val originalRevisionId = edited.messages.single().textRevisions.single().id
        val selected = MessageOperations.selectTextRevision(edited, user.id, originalRevisionId).session!!
        assertEquals("hello", selected.messages.single().text)
        val once = MessageOperations.deleteMessage(selected, user.id).session!!
        val deleted = MessageOperations.deleteMessage(once, user.id).session!!
        assertTrue(deleted.messages.single().deleted)
        assertEquals("", deleted.messages.single().text)
        assertEquals(1, MacroReferences.currentTurn(deleted))
        assertEquals(user.id, MacroReferences.currentTurnId(deleted))
        assertEquals("0", HistoryContext(Library(mainCalls = listOf(main)), deleted, main).read("count"))
        assertTrue(PromptCompiler().preview(Library(mainCalls = listOf(main)), deleted,
            WorkflowNode(inputTemplate = "", includeHistory = true), "").messages.none { it.id == user.id })
    }

    @Test fun editsStayWithTheirSelectedGenerationVariant() {
        val staged = Session(messages = listOf(user))
        val first = MessageOperations.recordSuccessfulTurn(staged, run("A", "1", "alpha")).session!!
        val candidate = MessageOperations.prepareRegeneration(first, user.id).session!!
        val second = MessageOperations.commitRegeneration(first, candidate, run("B", "2", "beta")).session!!
        val edited = MessageOperations.editText(second, second.messages.last().id, "B fixed").session!!
        val variants = edited.turnRecords.single().variants
        val a = MessageOperations.applyLatestVariant(edited, user.id, variants.first().id).session!!
        assertEquals("A", a.messages.last().text)
        val b = MessageOperations.applyLatestVariant(a, user.id, variants.last().id).session!!
        assertEquals("B fixed", b.messages.last().text)
        assertEquals(listOf("B"), b.messages.last().textRevisions.map { it.text })
    }

    @Test fun priorLegacyForkIsRejectedButCurrentEndAndRecordedBoundaryAreSerializable() {
        val legacy = Session(messages = listOf(user, ChatMessage(id = "old-reply", role = Role.ASSISTANT, text = "old")))
        assertNull(MessageOperations.forkAt(legacy, user.id).session)
        val copiedEnd = MessageOperations.forkAt(legacy, "old-reply").session!!
        assertNotEquals(legacy.id, copiedEnd.id)
        assertEquals(legacy.messages.map { it.id }, copiedEnd.messages.map { it.id })

        val staged = legacy.copy(messages = legacy.messages + ChatMessage(id = "new-user", role = Role.USER, text = "next"))
        val result = RunResult(state = mapOf("count" to "1"), results = emptyMap(), replies = listOf(
            ChatMessage(id = "new-reply", role = Role.ASSISTANT, text = "done", turnId = "new-user")
        ), traces = emptyList())
        val recorded = MessageOperations.recordSuccessfulTurn(staged, result).session!!
        val fork = MessageOperations.forkAt(recorded, "new-user").session!!
        assertEquals(legacy.messages.size + 1, fork.messages.size)
        assertTrue(fork.state.isEmpty())
        assertFalse(fork.turnRecords.any { it.userMessageId == "new-user" })
        val decoded = Json.decodeFromString(Session.serializer(), Json.encodeToString(Session.serializer(), recorded))
        assertEquals(recorded.turnRecords, decoded.turnRecords)
    }


}
