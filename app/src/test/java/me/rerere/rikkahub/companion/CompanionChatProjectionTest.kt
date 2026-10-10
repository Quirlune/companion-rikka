package me.rerere.rikkahub.companion

import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.Role
import dev.gray.companion.core.RuntimeSnapshot
import dev.gray.companion.core.Session
import dev.gray.companion.core.TurnCheckpoint
import dev.gray.companion.core.TurnRecord
import dev.gray.companion.core.TurnVariant
import me.rerere.ai.core.MessageRole
import org.junit.Assert.*
import org.junit.Test

class CompanionChatProjectionTest {
    private val user = ChatMessage(id = "user", role = Role.USER, text = "prompt", createdAt = 1)
    private val oldReply = ChatMessage(id = "old", role = Role.ASSISTANT, text = "old answer", turnId = user.id, createdAt = 2)
    private val freshReply = oldReply.copy(id = "fresh", text = "journal answer", createdAt = 3)

    private fun session(reply: ChatMessage, inlineOldReply: Boolean = false): Session = Session(
        id = "chat", messages = listOf(user, reply),
        turnRecords = listOf(TurnRecord(
            userMessageId = user.id,
            checkpoint = TurnCheckpoint(RuntimeSnapshot()),
            selectedVariantId = "selected",
            variants = listOf(TurnVariant(
                id = "selected",
                replyIds = if (inlineOldReply) emptyList() else listOf(oldReply.id),
                replies = if (inlineOldReply) listOf(oldReply) else emptyList(),
            )),
        )),
    )

    @Test fun regenerationJournalReplyWithoutPublishedReceiptStaysVisible() {
        // Both compact receipts and legacy inline receipts still reference the replaced answer.
        listOf(false, true).forEach { inlineOldReply ->
            val projection = projectCompanionHistory(session(freshReply, inlineOldReply))
            assertEquals(2, projection.nodes.size)
            assertTrue(projection.nodes.all { it.messages.isNotEmpty() && it.selectIndex in it.messages.indices })
            val reply = projection.nodes.single { it.currentMessage.role == MessageRole.ASSISTANT }
            assertEquals("journal answer", reply.currentMessage.toText())
            assertEquals(1, reply.messages.size)
            assertEquals(freshReply.id, projection.logicalMessageIds[reply.currentMessage.id])
            assertTrue(projection.branchChoices[reply.id].orEmpty().isEmpty())
        }
    }

    @Test fun publishedReplyRetainsItsGenerationCandidate() {
        val projection = projectCompanionHistory(session(oldReply))
        val reply = projection.nodes.single { it.currentMessage.role == MessageRole.ASSISTANT }
        assertEquals("old answer", reply.currentMessage.toText())
        val choice = projection.branchChoices.getValue(reply.id).single() as CompanionBranchChoice.Generation
        assertEquals("selected", choice.variantId)
        assertEquals(oldReply.id, choice.messageId)
        assertEquals(oldReply.id, choice.revisionId)
    }
}
