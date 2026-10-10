package dev.gray.companion.core

import kotlinx.serialization.Serializable

// Legacy serialization defaults only. These fields no longer drive requests or dependencies.
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
