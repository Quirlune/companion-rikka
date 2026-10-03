package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.prompt.EventRuntime
import me.rerere.prompt.PromptSnapshot
import me.rerere.prompt.RuntimeEvent
import me.rerere.prompt.RuntimeStore
import me.rerere.rikkahub.data.model.Conversation

data class RequestInspection(
    val context: PromptRequestContext,
    val prompt: PromptSnapshot,
    val messages: List<UIMessage>,
)

/** Chat notifications and automation deliberately use different buses and failure policies. */
class PromptRuntimeService(
    val events: EventRuntime,
    private val scope: CoroutineScope,
    private val json: Json,
    private val store: RuntimeStore,
) {
    private val dispatchError = MutableStateFlow<String?>(null)
    val lastDispatchError = dispatchError.asStateFlow()
    private val requestRecords = MutableStateFlow<List<RequestInspection>>(emptyList())
    val requests = requestRecords.asStateFlow()

    fun recordRequest(
        context: PromptRequestContext,
        prompt: PromptSnapshot,
        messages: List<UIMessage>,
    ) {
        requestRecords.update { records ->
            (records.filterNot { it.context.scope == context.scope } +
                    RequestInspection(context, prompt, messages.toList()))
                .takeLast(16)
        }
    }

    suspend fun context(
        conversation: Conversation,
        messages: List<UIMessage> = conversation.currentMessages,
    ) =
        withContext(Dispatchers.Default) {
            PromptContextIdentity.from(conversation, json, messages)
        }

    suspend fun onReplyCompleted(conversation: Conversation) {
        val last = conversation.currentMessages.lastOrNull() ?: return
        if (
            last.role != MessageRole.ASSISTANT ||
                last.finishedAt == null ||
                last.getTools().any { !it.isExecuted }
        )
            return
        val order =
            try {
                if (store.rules().none { it.enabled && it.eventType == REPLY_COMPLETED }) return
                store.nextEventOrder()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dispatchError.value = error.message ?: error.javaClass.simpleName
                return
            }
        scope.launch(Dispatchers.Default) {
            try {
                val context = context(conversation)
                events.dispatch(
                    RuntimeEvent(
                        type = REPLY_COMPLETED,
                        order = order,
                        scope = context.scope,
                        sources = context.sources,
                        id = PromptContextIdentity.eventId(context),
                        data =
                            mapOf(
                                MESSAGES to json.encodeToString(conversation.currentMessages),
                                "text" to last.toText(),
                            ),
                    )
                )
                dispatchError.value = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                dispatchError.value = error.message ?: error.javaClass.simpleName
            }
        }
    }

    companion object {
        const val REPLY_COMPLETED = "chat.reply.completed"
        const val MESSAGES = "chat.messages"

        fun messages(event: RuntimeEvent, json: Json): List<UIMessage> =
            event.data[MESSAGES]?.let { json.decodeFromString<List<UIMessage>>(it) }
                ?: listOf(UIMessage.user(event.data["text"].orEmpty()))
    }
}
