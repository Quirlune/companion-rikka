package dev.gray.companion.core

import kotlinx.serialization.Serializable

// Kept identical to the original TitleSummary.kt prompt; core cannot depend on app.
val DEFAULT_CHAT_TITLE_PROMPT = """
    I will give you some dialogue content in the `<content>` block.
    You need to summarize the conversation between user and assistant into a short title.
    1. The title language should be consistent with the user's primary language
    2. Do not use punctuation or other special symbols
    3. Reply directly with the title
    4. Summarize using {locale} language
    5. The title should not exceed 10 characters

    <content>
    {content}
    </content>
""".trimIndent()

@Serializable data class ChatTitleConfig(
    val enabled: Boolean = true,
    val modelProfileId: String? = null,
    val prompt: String = DEFAULT_CHAT_TITLE_PROMPT
)

data class ChatTitleRequest(val profile: ModelProfile, val messages: List<ChatMessage>)

object ChatTitles {
    private val placeholder = Regex("\\{locale\\}|\\{content\\}")

    fun request(library: Library, session: Session, locale: String): ChatTitleRequest? {
        val config = library.titleConfig
        if (!config.enabled || config.prompt.isBlank()) return null
        val history = session.messages.asReversed().asSequence()
            .filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
            .take(4).toList().asReversed()
        if (history.isEmpty()) return null

        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val primary = main?.workflows?.firstOrNull { it.id == main.primaryWorkflowId }
            ?: main?.workflows?.firstOrNull()
        val defaultNode = primary?.nodes?.lastOrNull { it.enabled && it.actionId == "model.generate" &&
            it.visible && it.outputSlot == OutputSlot.BODY }
            ?: primary?.nodes?.lastOrNull { it.enabled && it.actionId == "model.generate" }
        val profile = if (config.modelProfileId != null) ModelCatalog.resolveProfile(library, config.modelProfileId)
            else if (primary != null && defaultNode != null) defaultNode.generation.resolve(library, primary)
            else ModelCatalog.resolveProfile(library, primary?.modelProfileId ?: library.selectedProfileId)
        if (profile == null) return null
        val content = history.joinToString("\n\n") { message ->
            val summary = "[${message.role.name}]: ${message.text}"
            if (summary.length > 500) summary.take(497) + "..." else summary
        }
        val prompt = placeholder.replace(config.prompt) { match ->
            if (match.value == "{locale}") locale else content
        }
        return ChatTitleRequest(profile, listOf(ChatMessage(role = Role.USER, text = prompt)))
    }
}
