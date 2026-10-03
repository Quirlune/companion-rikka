package me.rerere.rikkahub.data.ai.runtime

import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.prompt.ContextScope
import me.rerere.prompt.SourceVersion
import me.rerere.rikkahub.data.model.Conversation

data class PromptRequestContext(val scope: ContextScope, val sources: List<SourceVersion>)

/** Reuses upstream message IDs and attachments; no parallel message/asset model is introduced. */
object PromptContextIdentity {
    fun eventId(context: PromptRequestContext): String =
        digest(
            "${context.scope}:" + context.sources.joinToString("/") { "${it.id}:${it.revision}" }
        )

    fun from(
        conversation: Conversation,
        json: Json,
        messages: List<UIMessage> = conversation.currentMessages,
    ): PromptRequestContext {
        val alternatives =
            conversation.messageNodes
                .filter { it.messages.size > 1 }
                .joinToString("/") { "${it.id}:${it.currentMessage.id}" }
        return PromptRequestContext(
            ContextScope(
                conversation.assistantId.toString(),
                conversation.id.toString(),
                if (alternatives.isEmpty()) "main" else digest(alternatives),
            ),
            sources(messages, json),
        )
    }

    fun sources(messages: List<UIMessage>, json: Json): List<SourceVersion> = messages.map {
        SourceVersion(it.id.toString(), digest(it.role.name + json.encodeToString(it.parts)))
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
