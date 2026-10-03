package me.rerere.rikkahub.data.ai.runtime

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.prompt.RuntimeEvent
import me.rerere.prompt.SourceVersion
import me.rerere.rikkahub.data.model.Conversation

/**
 * Read-only selection/location API; compression and recall results use normal runtime references.
 */
class ContextHistoryApi(private val json: Json) {
    fun locate(conversation: Conversation, source: SourceVersion): UIMessage? =
        conversation.currentMessages
            .singleOrNull { it.id.toString() == source.id }
            ?.takeIf {
                PromptContextIdentity.sources(listOf(it), json).single() == source
            }

    fun select(conversation: Conversation, messageIds: Set<String>): List<UIMessage> {
        val result = conversation.currentMessages.filter { it.id.toString() in messageIds }
        require(result.size == messageIds.size) {
            "Selection contains messages outside this branch"
        }
        return result
    }

    /** Dispatch the returned event with a rule producing the preset's summary reference. */
    fun compressionEvent(conversation: Conversation, prefixCount: Int): RuntimeEvent {
        val history = conversation.currentMessages
        require(prefixCount in 1 until history.size) {
            "Keep at least the latest message uncompressed"
        }
        val selected = history.take(prefixCount)
        require(selected.none { it.getTools().isNotEmpty() }) {
            "Tool-bearing history cannot be compressed yet"
        }
        val context = PromptContextIdentity.from(conversation, json)
        return RuntimeEvent(
            type = COMPRESSION_REQUESTED,
            scope = context.scope,
            sources = context.sources.take(prefixCount),
            data = mapOf(PromptRuntimeService.MESSAGES to json.encodeToString(selected)),
        )
    }

    companion object {
        const val COMPRESSION_REQUESTED = "context.compression.requested"
    }
}
