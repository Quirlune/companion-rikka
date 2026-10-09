package dev.gray.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable enum class ReasoningParsingMode { OFF, TAGGED, START_UNTIL_END }
@Serializable data class ReasoningParsingConfig(
    val mode: ReasoningParsingMode = ReasoningParsingMode.OFF,
    val startTag: String = "<think>", val endTag: String = "</think>"
) {
    fun validationError(): String? = when {
        mode == ReasoningParsingMode.OFF -> null
        endTag.isEmpty() -> "思考结束标记不能为空"
        mode == ReasoningParsingMode.TAGGED && startTag.isEmpty() -> "思考开始标记不能为空"
        mode == ReasoningParsingMode.TAGGED && (startTag == endTag || startTag.startsWith(endTag) || endTag.startsWith(startTag)) -> "思考开始与结束标记不能相同或互为前缀"
        else -> null
    }
}
@Serializable data class ReasoningSnapshot(
    val text: String = "", val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long? = null,
    val nativeMessage: JsonObject? = null
)
data class ModelResponse(val text: String, val reasoning: ReasoningSnapshot? = null)
sealed interface ModelDelta {
    data class Text(val text: String) : ModelDelta
    data class Reasoning(val text: String) : ModelDelta
    data class ReasoningStart(val startedAt: Long = System.currentTimeMillis()) : ModelDelta
    data class ReasoningEnd(val finishedAt: Long = System.currentTimeMillis()) : ModelDelta
}
class ReasoningFormatFailure(message: String) : IllegalArgumentException(message)

/** One parser per actual response. Potential delimiters are held until disambiguated. */
class ReasoningTextParser(private val config: ReasoningParsingConfig) {
    private var thinking = config.mode == ReasoningParsingMode.START_UNTIL_END
    private var initialFinished = false
    private var pending = ""
    private val body = StringBuilder()
    private val tagged = StringBuilder()
    private var taggedStart: Long? = if (thinking) System.currentTimeMillis() else null
    private var taggedEnd: Long? = null
    private var native: ReasoningSnapshot? = null
    private var finished = false
    init { require(config.validationError() == null) { config.validationError().orEmpty() } }

    internal val nativeSnapshot: ReasoningSnapshot? get() = native
    val reasoning: ReasoningSnapshot? get() {
        val start = listOfNotNull(native?.startedAt, taggedStart).minOrNull() ?: return null
        val texts = listOfNotNull(native?.text?.takeIf { it.isNotEmpty() }, tagged.toString().takeIf { it.isNotEmpty() })
        val end = if (thinking || native?.finishedAt == null && native != null) null
            else listOfNotNull(native?.finishedAt, taggedEnd).maxOrNull()
        return ReasoningSnapshot(texts.joinToString("\n\n"), start, end, native?.nativeMessage)
    }

    fun accept(delta: ModelDelta): String {
        check(!finished)
        return when (delta) {
            is ModelDelta.Text -> append(delta.text)
            is ModelDelta.ReasoningStart -> { native = (native ?: ReasoningSnapshot(startedAt = delta.startedAt)).copy(finishedAt = null); "" }
            is ModelDelta.Reasoning -> { native = (native ?: ReasoningSnapshot()).let { it.copy(text = it.text + delta.text, finishedAt = null) }; "" }
            is ModelDelta.ReasoningEnd -> { native = (native ?: ReasoningSnapshot(startedAt = delta.finishedAt)).copy(finishedAt = delta.finishedAt); "" }
        }
    }

    private fun append(text: String): String {
        val before = body.length
        pending += text
        while (pending.isNotEmpty()) {
            if (config.mode == ReasoningParsingMode.OFF || initialFinished) {
                body.append(pending); pending = ""; break
            }
            if (thinking && taggedStart == null) taggedStart = System.currentTimeMillis()
            val delimiter = if (thinking) config.endTag else config.startTag
            val found = pending.indexOf(delimiter)
            if (found >= 0) {
                emit(pending.substring(0, found))
                pending = pending.substring(found + delimiter.length)
                thinking = !thinking
                if (thinking) {
                    if (taggedStart == null) taggedStart = System.currentTimeMillis()
                    taggedEnd = null
                } else {
                    taggedEnd = System.currentTimeMillis()
                    if (config.mode == ReasoningParsingMode.START_UNTIL_END) initialFinished = true
                }
            } else {
                val held = (minOf(pending.length, delimiter.length - 1) downTo 1)
                    .firstOrNull { pending.endsWith(delimiter.substring(0, it)) } ?: 0
                emit(pending.dropLast(held))
                pending = pending.takeLast(held)
                break
            }
        }
        return body.substring(before)
    }

    private fun emit(text: String) { if (thinking) tagged.append(text) else body.append(text) }

    fun finish(nativeReasoning: ReasoningSnapshot? = null): ModelResponse {
        check(!finished)
        if (thinking) throw ReasoningFormatFailure("思考内容缺少结束标记：${config.endTag}")
        // EOF disambiguates a partial opening delimiter as literal body text.
        body.append(pending)
        pending = ""
        if (nativeReasoning != null) native = nativeReasoning
        if (native != null && native?.finishedAt == null) native = native?.copy(finishedAt = System.currentTimeMillis())
        finished = true
        return ModelResponse(body.toString(), reasoning)
    }

    companion object {
        fun parse(text: String, config: ReasoningParsingConfig, nativeReasoning: ReasoningSnapshot? = null): ModelResponse =
            ReasoningTextParser(config).apply { accept(ModelDelta.Text(text)) }.finish(nativeReasoning)
    }
}
