package me.rerere.rikkahub.data.ai.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.prompt.PromptCompiler
import me.rerere.prompt.PromptPreset
import me.rerere.prompt.PromptRole
import me.rerere.prompt.PromptSnapshot
import me.rerere.prompt.RuntimeStore
import me.rerere.rikkahub.data.ai.buildMemoryPrompt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory

/** Request-only assembly. Returned synthetic blocks never replace stored conversation messages. */
class ChatPromptAssembler(private val store: RuntimeStore) {
    suspend fun assemble(
        assistant: Assistant,
        model: Model,
        messages: List<UIMessage>,
        memories: List<AssistantMemory>,
        tools: List<Tool>,
        conversationSystemPrompt: String?,
        requestContext: PromptRequestContext?,
        onSnapshot: (PromptSnapshot) -> Unit = {},
    ): List<UIMessage> {
        val system = buildString {
            val instructions =
                if (
                    assistant.allowConversationSystemPrompt &&
                        !conversationSystemPrompt.isNullOrBlank()
                )
                    conversationSystemPrompt
                else assistant.systemPrompt
            if (instructions.isNotBlank()) append(instructions)
            if (assistant.enableMemory) {
                appendLine()
                append(buildMemoryPrompt(memories))
            }
            tools.forEach {
                appendLine()
                append(it.systemPrompt(model, messages))
            }
        }
        val builtins =
            assistant.promptEntries.mapKeys { "assistant.entry.${it.key}" } +
                (PromptPreset.LEGACY_SYSTEM to system)
        val snapshot =
            if (requestContext == null) {
                PromptCompiler.compile(PromptPreset.default(), builtins, emptyList())
            } else {
                store.compile(requestContext.scope, requestContext.sources, builtins)
            }
        val covered = snapshot.historyProjection?.covered?.map { it.id }?.toSet().orEmpty()
        val safeSnapshot =
            if (messages.any { it.id.toString() in covered && it.getTools().isNotEmpty() }) {
                snapshot.copy(historyProjection = null)
            } else snapshot
        onSnapshot(safeSnapshot)
        return materialize(safeSnapshot, messages.limitContext(assistant.contextMessageLimit))
    }
}

/** Keeps original multimodal objects, roles and tool groups intact inside the history block. */
internal fun materialize(snapshot: PromptSnapshot, history: List<UIMessage>): List<UIMessage> =
    buildList {
        val projection = snapshot.historyProjection
        val covered = projection?.covered?.map { it.id }?.toSet().orEmpty()
        // Tool calls/results are indivisible. Reject a summary spanning any tool-bearing messages.
        val canSummarize =
            projection != null &&
                history.filter { it.id.toString() in covered }.none { it.getTools().isNotEmpty() }
        snapshot.blocks.forEach { block ->
            if (block.history) {
                if (canSummarize) {
                    add(
                        UIMessage.system(requireNotNull(projection).summary)
                            .copy(isSynthetic = true)
                    )
                    addAll(history.filterNot { it.id.toString() in covered })
                } else addAll(history)
            } else
                add(
                    UIMessage(
                        role =
                            when (block.role) {
                                PromptRole.SYSTEM -> MessageRole.SYSTEM
                                PromptRole.USER -> MessageRole.USER
                                PromptRole.ASSISTANT -> MessageRole.ASSISTANT
                            },
                        parts =
                            listOf(me.rerere.ai.ui.UIMessagePart.Text(requireNotNull(block.text))),
                        isSynthetic = true,
                    )
                )
        }
    }
