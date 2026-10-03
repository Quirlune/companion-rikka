package dev.gray.companion.core

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable enum class TextProcessKind {
    REGEX_EXTRACT, REGEX_REPLACE, TRIM, APPEND, PREPEND, FORMAT, INCREASE, CLAMP, EXPRESSION
}

@Serializable data class TextProcessStep(
    val kind: TextProcessKind,
    val expression: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val group: Int = 0,
    val text: String = "",
    val amount: String = "",
    val min: String = "",
    val max: String = "",
    val requireMatch: Boolean = false
)

@Serializable data class ModelCallOptions(
    val profileId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoning: ModelReasoning? = null
)

@Serializable enum class MacroProcessMode { SET, UPDATE, COPY, ENABLE, DISABLE }
@Serializable data class MacroProcessConfig(
    val key: String = "",
    val mode: MacroProcessMode = MacroProcessMode.SET,
    val source: String = "",
    val initial: String = "",
    val steps: List<TextProcessStep> = emptyList()
)

internal fun ModelCallOptions.resolve(library: Library, workflow: Workflow): ModelProfile? {
    val id = profileId ?: workflow.modelProfileId ?: library.selectedProfileId
    val base = ModelCatalog.resolveProfile(library, id) ?: return null
    return base.copy(temperature = temperature ?: base.temperature, topP = topP ?: base.topP,
        maxTokens = maxTokens ?: base.maxTokens, reasoning = reasoning ?: base.reasoning)
}

internal fun canonicalWriteAddress(raw: String): String? {
    val text = raw.trim().removeSurrounding("{{", "}}").trim()
    if (text.startsWith("state.") && Regex("state\\.[\\p{L}_][\\p{L}\\p{N}_.-]*").matches(text)) return text
    return MacroReferences.canonicalAddress(text)
}

internal fun WorkflowNode.declaredWriteAddress(descriptor: ActionDescriptor?): String? {
    val field = descriptor?.macroWriteKeyField ?: return null
    val declared = descriptor.withDefaults(arguments)[field].orEmpty()
    val raw = if (actionId == "macro.process") macroProcess.key.ifBlank { declared } else declared
    return if (actionId == "macro.process") canonicalWriteAddress(raw) else MacroReferences.canonicalAddress(raw)
}

internal fun WorkflowNode.declaredStateWriteAddress(descriptor: ActionDescriptor?): String? {
    val field = descriptor?.stateWriteKeyField ?: return null
    return descriptor.withDefaults(arguments)[field]?.trim()
        ?.takeIf { it.isNotBlank() && !it.contains("{{") }?.let { "state.$it" }
}

/** Typed History targets participate in scheduling, but are never ordinary text macro addresses. */
internal fun WorkflowNode.declaredOutputAddresses(descriptor: ActionDescriptor?): Set<String> = buildSet {
    add("result.$outputKey")
    declaredStateWriteAddress(descriptor)?.let(::add)
    declaredWriteAddress(descriptor)?.let(::add)
    if (regexOutputCopy.enabled) add("result.${regexOutputCopy.outputKey}")
    if (actionId == "history.view.copy") arguments["targetId"]?.trim()?.takeIf { it.isNotBlank() && !it.contains("{{") }
        ?.let { add("history-view:$it") }
}

/** Pure ordered text operations. Errors reject this node; previously accepted receipts remain. */
object TextProcessing {
    private const val MAX_OUTPUT_LENGTH = 1_000_000
    fun validate(steps: List<TextProcessStep>, path: String): List<Issue> = buildList {
        if (steps.size > 64) add(Issue(path, "文本处理步骤过多"))
        steps.forEachIndexed { index, step ->
            val at = "$path.$index"
            for ((field, source) in listOf("text" to step.text, "replacement" to step.replacement)) {
                val remainder = templateToken.replace(source, "")
                if (remainder.contains("{{") || remainder.contains("}}")) add(Issue("$at.$field", "宏格式不完整"))
                templateToken.findAll(source).forEach { match ->
                    macroExpressionError(match.groupValues[1].trim())?.let { add(Issue("$at.$field", it)) }
                }
            }
            when (step.kind) {
                TextProcessKind.REGEX_EXTRACT -> SafePatterns.validate(step.pattern, step.group)?.let { add(Issue(at, it)) }
                TextProcessKind.REGEX_REPLACE -> {
                    if (step.pattern.isBlank()) add(Issue(at, "正则表达式不能为空"))
                    else SafePatterns.validate(step.pattern)?.let { add(Issue(at, it)) }
                }
                TextProcessKind.EXPRESSION -> if (step.expression.isBlank()) add(Issue(at, "表达式不能为空"))
                    else ConditionExpressions.validate(step.expression)?.let { add(Issue(at, it)) }
                TextProcessKind.INCREASE -> if (step.amount.toBigDecimalOrNull() == null) add(Issue(at, "增减量须为数字"))
                TextProcessKind.CLAMP -> {
                    val min = step.min.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
                    val max = step.max.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
                    if (step.min.isNotBlank() && min == null || step.max.isNotBlank() && max == null ||
                        min != null && max != null && min > max) add(Issue(at, "数值边界无效"))
                }
                else -> Unit
            }
        }
    }

    fun apply(steps: List<TextProcessStep>, source: String, state: Map<String, String> = emptyMap(),
              results: Map<String, String> = emptyMap(), readMacro: ((String) -> String?)? = null): String {
        require(steps.size <= 64) { "文本处理步骤过多" }
        var value = source
        for (step in steps) {
            require(value.length <= MAX_OUTPUT_LENGTH) { "文本处理内容过长" }
            value = when (step.kind) {
                TextProcessKind.REGEX_EXTRACT -> {
                    require(SafePatterns.validate(step.pattern, step.group) == null) { "正则表达式无效" }
                    val extracted = SafePatterns.extract(step.pattern, value, step.group)
                    if (step.requireMatch && extracted == null) throw OutputFormatException("正则表达式未匹配模型输出")
                    extracted.orEmpty()
                }
                TextProcessKind.REGEX_REPLACE -> {
                    require(SafePatterns.validate(step.pattern) == null && step.pattern.isNotBlank()) { "正则表达式无效" }
                    SafePatterns.replace(step.pattern, value, expand(step.replacement, readMacro))
                }
                TextProcessKind.TRIM -> value.trim()
                TextProcessKind.APPEND -> value + expand(step.text, readMacro)
                TextProcessKind.PREPEND -> expand(step.text, readMacro) + value
                TextProcessKind.FORMAT -> expand(step.text, readMacro).replace("{value}", value)
                TextProcessKind.INCREASE -> number((value.ifBlank { "0" }).toBigDecimal() + step.amount.toBigDecimal())
                TextProcessKind.CLAMP -> {
                    val low = step.min.takeIf { it.isNotBlank() }?.toBigDecimal()
                    val high = step.max.takeIf { it.isNotBlank() }?.toBigDecimal()
                    require(low == null || high == null || low <= high) { "数值边界无效" }
                    var current = value.toBigDecimal()
                    if (low != null && current < low) current = low
                    if (high != null && current > high) current = high
                    number(current)
                }
                TextProcessKind.EXPRESSION -> ConditionExpressions.evaluate(step.expression, state, results, value, readMacro)
            }
            require(value.length <= MAX_OUTPUT_LENGTH) { "文本处理结果过长" }
        }
        return value
    }

    private fun number(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private val templateToken = Regex("\\{\\{([^{}]*)\\}\\}")
    private fun expand(source: String, readMacro: ((String) -> String?)?): String {
        if (source.isEmpty()) return source
        val remainder = templateToken.replace(source, "")
        require(!remainder.contains("{{") && !remainder.contains("}}")) { "宏格式不完整" }
        return templateToken.replace(source) { match ->
            val address = match.groupValues[1].trim()
            require(macroExpressionError(address) == null) { "宏地址无效" }
            readMacro?.invoke(address).orEmpty()
        }
    }
}
