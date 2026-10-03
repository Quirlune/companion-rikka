package dev.gray.companion.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object HistoryActions {
    fun selectedIds(text: String, pattern: String, group: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val values = if (pattern.isNotBlank()) {
            SafePatterns.validate(pattern, group)?.let { error(it) }
            SafePatterns.extractAll(pattern, text, group).also {
                if (it.isEmpty()) throw OutputFormatException("记忆选择正则未匹配任何 ID")
            }
        } else if (text.trim().startsWith("[")) {
            val array = Json.parseToJsonElement(text) as? JsonArray ?: error("记忆选择须为 ID 数组")
            array.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("记忆 ID 须为字符串") }
        } else text.split(Regex("[\\s,;]+"))
        return values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
}
