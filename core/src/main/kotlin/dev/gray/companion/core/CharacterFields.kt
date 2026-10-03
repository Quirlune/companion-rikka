package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable
data class CharacterField(
    val key: String,
    val title: String,
    val hint: String,
    val legacyKeys: List<String> = emptyList()
)

/** Shared character-card vocabulary. Editors consume this; cards remain passive prompt data. */
object CharacterFields {
    val definitions = listOf(
        CharacterField("profile", "角色概览", "缩小版的完整角色卡，可包含外貌、性格、经历、背景与关系。", listOf("identity")),
        CharacterField("appearance", "外貌特征", "描写外形、衣着、神态及其他外在特征。"),
        CharacterField("personality", "性格特征", "描写性格、习惯、表达方式与行为特点。"),
        CharacterField("background", "身世背景", "记录角色的经历、身份、生活背景与关系。"),
        CharacterField("dialogue_examples", "对话示例", "自由填写示例对话，用于表现角色的语气与互动方式。", listOf("examples")),
        CharacterField("world", "世界设定", "描写角色所在的世界、环境与基本设定。"),
        CharacterField("preferences", "兴趣偏好", "记录喜欢、厌恶、兴趣与其他个人偏好。")
    )
    private val keys = definitions.map { it.key }.toSet()
    private val aliases = definitions.flatMap { field -> field.legacyKeys.map { it to field.key } }.toMap()

    fun isBasic(key: String): Boolean = key in keys
    fun canonicalKey(key: String): String = aliases[key] ?: key
    fun defaults(): List<PromptEntry> = definitions.mapIndexed { index, field ->
        PromptEntry(key = field.key, order = index)
    }

    /** Add missing fields without erasing extras, duplicate-key evidence, IDs or legacy text. */
    fun normalize(card: CharacterCard): CharacterCard {
        val remaining = card.entries.toMutableList()
        val basics = definitions.mapIndexed { index, field ->
            val existing = remaining.indexOfFirst { it.key == field.key }
                .takeIf { it >= 0 } ?: remaining.indexOfFirst { it.key in field.legacyKeys }
            if (existing >= 0) remaining.removeAt(existing).copy(key = field.key, enabled = true, condition = null)
            else PromptEntry(id = "${card.id}.basic.${field.key}", key = field.key, order = index)
        }
        val normalized = basics + remaining
        return if (card.entries == normalized) card else card.copy(entries = normalized)
    }
}

fun Library.withStandardCharacterFields(): Library {
    val normalized = characters.map(CharacterFields::normalize)
    return if (characters == normalized) this else copy(characters = normalized)
}

/** Starting another chat copies resource choices only. Runtime data always starts fresh. */
fun Library.startNewConversation(characterId: String? = null): Library {
    require(characterId == null || characters.any { it.id == characterId }) { "角色卡不存在" }
    val selected = sessions.firstOrNull { it.id == selectedSessionId }
    val fresh = Session(
        characterId = characterId ?: selected?.characterId ?: characters.firstOrNull()?.id.orEmpty(),
        mainCallId = selected?.mainCallId ?: mainCalls.firstOrNull()?.id.orEmpty(),
        packageIds = selected?.packageIds?.toList() ?: emptyList(),
        groupIds = selected?.groupIds?.toList() ?: emptyList(),
        personaId = selectedPersonaId ?: selected?.personaId
    )
    val greeting = characters.firstOrNull { it.id == fresh.characterId }?.greeting.orEmpty()
    val opening = if (greeting.isBlank()) emptyList() else {
        val resolver = MacroResolver(this, fresh)
        val rendered = resolver.render(greeting)
        require(resolver.issues.none { it.error }) { "开场白包含无效宏" }
        if (rendered.isBlank()) emptyList() else listOf(ChatMessage(role = Role.ASSISTANT, text = rendered,
            speakerName = characters.firstOrNull { it.id == fresh.characterId }?.name))
    }
    val started = fresh.copy(messages = opening)
    return copy(sessions = sessions + started, selectedSessionId = started.id)
}

/** Matches the assistant-picker default: another world anchor starts its own fresh chat. */
fun Library.selectCharacter(characterId: String): Library {
    require(characters.any { it.id == characterId }) { "角色卡不存在" }
    val selected = sessions.firstOrNull { it.id == selectedSessionId }
    return if (selected?.characterId == characterId) this else startNewConversation(characterId)
}
