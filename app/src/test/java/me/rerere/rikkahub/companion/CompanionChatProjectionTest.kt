package me.rerere.rikkahub.companion

import dev.gray.companion.PendingOutput
import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.Library
import dev.gray.companion.core.OutputSlot
import dev.gray.companion.core.Role
import dev.gray.companion.core.RuntimeSnapshot
import dev.gray.companion.core.Session
import dev.gray.companion.core.TurnCheckpoint
import dev.gray.companion.core.TurnRecord
import dev.gray.companion.core.TurnVariant
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class CompanionChatProjectionTest {
    @Test fun illustrationsOnlyProjectTheExactActiveBodyAndEnabledPolicy() {
        val reply = ChatMessage(id = "picture-body", role = Role.ASSISTANT, text = "One.\n\nTwo.")
        val chat = Session(id = "image-chat", messages = listOf(reply))
        val image = dev.gray.companion.core.IllustrationImage(chat.id, reply.id,
            dev.gray.companion.core.IllustrationCore.sourceKey(reply), "p1", "unused.png")
        val projected = projectCompanionHistory(chat, listOf(image), true)
        assertEquals(listOf(image), projected.inlineIllustrations.values.flatten())
        assertEquals(reply.text, projected.nodes.single().currentMessage.toText())
        assertTrue(projectCompanionHistory(chat, listOf(image), false).inlineIllustrations.isEmpty())
        val changed = chat.copy(messages = listOf(reply.copy(text = "Changed.", activeTextRevisionId = "changed")))
        assertTrue(projectCompanionHistory(changed, listOf(image), true).inlineIllustrations.isEmpty())
    }

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

    @Test fun pendingJournalAndPublishedReplyKeepOnePresentationIdentity() {
        val reply = freshReply.copy(workflowId = "flow", nodeId = "node")
        val pending = PendingOutput("flow", "node", reply.text, turnId = user.id)
        val expectedId = pendingOutputId("chat", pending)
        val journal = session(reply)
        val published = journal.copy(turnRecords = journal.turnRecords.map { record ->
            record.copy(selectedVariantId = "new", variants = listOf(
                TurnVariant(id = "new", replyIds = listOf(reply.id)),
            ))
        })
        val stages = listOf(
            journal.copy(turnRecords = emptyList()), // First reply before a turn receipt exists.
            journal, // Regeneration journal while the old receipt is still selected.
            published,
        ).map(::projectCompanionHistory)
        stages.forEach { history ->
            val node = history.nodes.last()
            assertEquals(expectedId, node.id)
            assertEquals(expectedId, history.presentationIds[node.currentMessage.id])
            assertEquals(reply.text, node.currentMessage.toText())
        }
        // Candidate/action identities remain distinct from physical presentation identity.
        assertNotEquals(stages[1].nodes.last().currentMessage.id, stages[2].nodes.last().currentMessage.id)

        // Library publication precedes clearing pending outputs; never render both with the same key.
        val settings = Settings()
        val projection = projectCompanionChat(settings, Library(), published, listOf(pending),
            stages.last().nodes, presentationSettings = settings)
        assertEquals(stages.last().nodes, projection.conversation.messageNodes)
    }

    @Test fun switchingGenerationRefreshesContentWithoutChangingThePhysicalOutput() {
        val first = oldReply.copy(workflowId = "flow", nodeId = "node")
        val second = freshReply.copy(workflowId = "flow", nodeId = "node")
        val record = TurnRecord(userMessageId = user.id, checkpoint = TurnCheckpoint(RuntimeSnapshot()),
            selectedVariantId = "first", variants = listOf(
                TurnVariant(id = "first", replies = listOf(first)),
                TurnVariant(id = "second", replies = listOf(second)),
            ))
        val firstHistory = projectCompanionHistory(Session(id = "chat", messages = listOf(user, first),
            turnRecords = listOf(record)))
        val secondHistory = projectCompanionHistory(Session(id = "chat", messages = listOf(user, second),
            turnRecords = listOf(record.copy(selectedVariantId = "second"))))
        val firstNode = firstHistory.nodes.last()
        val secondNode = secondHistory.nodes.last()
        assertEquals(firstNode.id, secondNode.id)
        assertNotEquals(firstNode.currentMessage.id, secondNode.currentMessage.id)
        assertEquals(first.text, firstNode.currentMessage.toText())
        assertEquals(second.text, secondNode.currentMessage.toText())
        assertEquals(2, secondHistory.branchChoices.getValue(secondNode.id).size)
        assertTrue(secondNode.messages.all { secondHistory.presentationIds[it.id] == secondNode.id })
        val selected = secondHistory.branchChoices.getValue(secondNode.id)[secondNode.selectIndex]
            as CompanionBranchChoice.Generation
        assertEquals("second", selected.variantId)
    }

    @Test fun repeatedRoutesAndOutputRegionsHaveDistinctPresentationKeys() {
        val first = freshReply.copy(workflowId = "flow", nodeId = "node")
        val duplicate = first.copy(id = "another", text = "another output")
        val after = first.copy(id = "after", outputSlot = OutputSlot.AFTER_BODY)
        val history = projectCompanionHistory(Session(id = "chat", messages = listOf(user, first, duplicate, after)))
        assertEquals(history.nodes.size, history.nodes.map { it.id }.toSet().size)
        assertEquals(pendingOutputId("chat", PendingOutput("flow", "node", first.text, turnId = user.id)),
            history.nodes[1].id)
        assertEquals(pendingOutputId("chat", PendingOutput("flow", "node", after.text,
            outputSlot = OutputSlot.AFTER_BODY, turnId = user.id)), history.nodes.last().id)
    }
}
