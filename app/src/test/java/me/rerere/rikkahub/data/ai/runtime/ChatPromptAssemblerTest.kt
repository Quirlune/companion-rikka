package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.prompt.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ChatPromptAssemblerTest {
    @Test
    fun `summary replaces request prefix and disabling restores original messages`() = runBlocking {
        val assistant = Assistant()
        val history =
            listOf(UIMessage.user("old"), UIMessage.assistant("answer"), UIMessage.user("latest"))
        val conversation =
            Conversation(
                assistantId = assistant.id,
                messageNodes = history.map { MessageNode.of(it) },
            )
        val api = ContextHistoryApi(JsonInstant)
        val event = api.compressionEvent(conversation, 2)
        val request = PromptContextIdentity.from(conversation, JsonInstant)
        val store = RuntimeStore(MemoryRuntimePersistence())
        val summary = ReferenceId("compression", "summary")
        store.register(ReferenceDefinition(summary, "Summary"))
        store.publish(store.begin(summary, event.scope, event.sources)!!, "compressed")
        store.setPreset(
            assistant.id.toString(),
            PromptPreset.default().copy(historyPolicy = HistoryPolicy(summary)),
        )
        val assembler = ChatPromptAssembler(store)
        val projected =
            assembler.assemble(assistant, Model(), history, emptyList(), emptyList(), null, request)
        assertEquals(listOf("compressed", "latest"), projected.map { it.toText() })
        assertEquals(3, conversation.currentMessages.size)
        assertSame(history.first(), api.locate(conversation, event.sources.first()))
        store.setEnabled("compression", false)
        assertEquals(
            history,
            assembler.assemble(
                assistant,
                Model(),
                history,
                emptyList(),
                emptyList(),
                null,
                request,
            ),
        )
    }

    @Test
    fun `compression refuses tool history and selection retains branch scope`() {
        val tool =
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts =
                    listOf(
                        UIMessagePart.Tool(
                            "call",
                            "search",
                            "{}",
                            listOf(UIMessagePart.Text("result")),
                        )
                    ),
            )
        val latest = UIMessage.user("latest")
        val conversation =
            Conversation(
                assistantId = Assistant().id,
                messageNodes = listOf(MessageNode.of(tool), MessageNode.of(latest)),
            )
        val api = ContextHistoryApi(JsonInstant)
        assertTrue(runCatching { api.compressionEvent(conversation, 1) }.isFailure)
        val selected = api.select(conversation, setOf(latest.id.toString()))
        val fullContext = PromptContextIdentity.from(conversation, JsonInstant)
        val selectedContext = PromptContextIdentity.from(conversation, JsonInstant, selected)
        assertEquals(fullContext.scope, selectedContext.scope)
        assertEquals(listOf(latest.id.toString()), selectedContext.sources.map { it.id })
    }

    @Test
    fun `default assembly preserves images documents and history identity`() = runBlocking {
        val history =
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts =
                        listOf(
                            UIMessagePart.Text("Describe this"),
                            UIMessagePart.Image("file:///image.png"),
                            UIMessagePart.Document("file:///notes.txt", "notes.txt"),
                            UIMessagePart.Audio("file:///audio.wav"),
                            UIMessagePart.Video("file:///video.mp4"),
                        ),
                )
            )
        val assembler = ChatPromptAssembler(RuntimeStore(MemoryRuntimePersistence()))
        val result =
            assembler.assemble(
                Assistant(systemPrompt = "System"),
                Model(),
                history,
                emptyList(),
                emptyList(),
                null,
                null,
            )
        assertEquals("System", result.first().toText())
        assertTrue(result.first().isSynthetic)
        assertSame(history.single(), result.last())
        assertEquals(5, result.last().parts.size)
        assertFalse(history.single().isSynthetic)
    }

    @Test
    fun `dynamic result is request only and disabling it creates no placeholder`() = runBlocking {
        val store = RuntimeStore(MemoryRuntimePersistence())
        val assistant = Assistant()
        val history = listOf(UIMessage.user("input"))
        val request =
            PromptRequestContext(ContextScope(assistant.id.toString(), "chat", "main"), emptyList())
        val reference = ReferenceId("analysis", "result")
        store.register(ReferenceDefinition(reference, "Result"))
        store.setPreset(
            assistant.id.toString(),
            PromptPreset(
                "test",
                listOf(
                    PromptBlock("reference", PromptSource.Reference(reference)),
                    PromptBlock("history", PromptSource.History),
                ),
            ),
        )
        store.publish(store.begin(reference, request.scope, emptyList())!!, "analysis")
        val assembler = ChatPromptAssembler(store)
        val result =
            assembler.assemble(assistant, Model(), history, emptyList(), emptyList(), null, request)
        assertEquals("analysis", result.first().toText())
        assertEquals(1, history.size)
        store.setEnabled("analysis", false)
        assertEquals(
            history,
            assembler.assemble(
                assistant,
                Model(),
                history,
                emptyList(),
                emptyList(),
                null,
                request,
            ),
        )
    }

    @Test
    fun `branch identity changes on alternative selection and revisions detect edits`() {
        val first = UIMessage.user("first")
        val alternative = UIMessage.user("alternative")
        val conversation =
            Conversation(
                assistantId = Assistant().id,
                messageNodes = listOf(MessageNode(messages = listOf(first, alternative))),
            )
        val original = PromptContextIdentity.from(conversation, JsonInstant)
        val selected =
            PromptContextIdentity.from(
                conversation.copy(
                    messageNodes = listOf(conversation.messageNodes.single().copy(selectIndex = 1))
                ),
                JsonInstant,
            )
        assertNotEquals(original.scope.branchId, selected.scope.branchId)
        val appended =
            PromptContextIdentity.from(
                conversation.copy(
                    messageNodes =
                        conversation.messageNodes + MessageNode.of(UIMessage.assistant("next"))
                ),
                JsonInstant,
            )
        assertEquals(original.scope, appended.scope)
        val edited = first.copy(parts = listOf(UIMessagePart.Text("edited")))
        assertNotEquals(
            PromptContextIdentity.sources(listOf(first), JsonInstant),
            PromptContextIdentity.sources(listOf(edited), JsonInstant),
        )
    }
}
