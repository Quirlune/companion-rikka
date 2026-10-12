package dev.gray.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Formatting belongs to one reference and contains no processing or storage configuration. */
@Serializable data class HistoryReferenceFormat(
    val floorPrefix: String = "",
    val floorSuffix: String = "",
    val apiRole: Role? = null,
    val speakers: List<HistorySpeakerRule> = emptyList()
)

object HistoryReferences {
    private val json = Json { ignoreUnknownKeys = false }
    private val wrapperToken = Regex("\\{\\{([^{}]*)\\}\\}")

    internal fun decodeFormat(text: String): HistoryReferenceFormat =
        json.decodeFromString(HistoryReferenceFormat.serializer(), text).also(::validate)

    private fun validate(format: HistoryReferenceFormat) {
        require(format.speakers.map { it.speaker }.distinct().size == format.speakers.size) { "原始发言方规则不能重复" }
        val wrappers = listOf(format.floorPrefix, format.floorSuffix) +
            format.speakers.flatMap { listOfNotNull(it.prefix, it.suffix) }
        wrappers.forEach { text ->
            val remainder = wrapperToken.replace(text, "")
            require("{{" !in remainder && "}}" !in remainder && wrapperToken.findAll(text).all {
                it.groupValues[1].trim() in setOf("speaker", "speaker.name", "floor")
            }) { "每楼包装只支持 speaker、speaker.name、floor" }
        }
    }

    /** Returns one complete token, including both JSON and reference-language quoting layers. */
    fun expression(selection: HistorySelection): String = buildString {
        append("{{type:builtin.in:history.name:")
        append(if (selection.compressed) "context" else "messages")
        if (selection.sourceKind == HistorySourceKind.MODEL_RAW) append(".source:model")
        selection.sourceRole?.let { append(".speaker:").append(it.name.lowercase()) }
        selection.viewId?.let { append(".view:").append(it) }
        selection.revision?.let { append(".revision:").append(it) }
        if (selection.rangeExplicit || selection.range != HistoryRange()) {
            fun point(point: HistoryPoint): String {
                require(point.value.isFinite() && point.value == point.value.toInt().toDouble()) { "历史范围端点须为整数" }
                return "${point.anchor.name.lowercase()}-${point.value.toInt()}"
            }
            append(".from:").append(point(selection.range.from))
            append(".through:").append(point(selection.range.through))
        }
        if (selection.readMode == HistoryReadMode.TEXT) append(".as:text")
        selection.format?.let { format ->
            validate(format)
            val encoded = json.encodeToString(HistoryReferenceFormat.serializer(), format)
            append(".format:\"").append(encoded.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
        }
        append("}}")
    }.also { expression ->
        val compiled = PromptReferenceLanguage.compile(expression)
        require((compiled.reference as? PromptReferenceLanguage.QueryPlan)?.historySelection != null) {
            compiled.diagnostics.firstOrNull()?.message ?: "History 引用无效"
        }
    }

    fun isText(expression: String, executionVersion: Int = ExecutionSemantics.CURRENT): Boolean =
        HistoryViews.selection(expression, executionVersion)?.readMode == HistoryReadMode.TEXT

    /** The returned value is inserted as text under the containing prompt block's role. */
    fun plainText(messages: List<ChatMessage>): String = messages.joinToString("\n") { message ->
        val speaker = when (message.role) {
            Role.SYSTEM -> "System"
            Role.USER -> "User"
            Role.ASSISTANT -> "Assistant"
        }
        "$speaker: ${message.text}"
    }
}
