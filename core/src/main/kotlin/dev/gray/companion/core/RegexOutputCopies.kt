package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class RegexTransformMode { EXTRACT, REPLACE }

/** A separate output address; transforming it never replaces the source result macro. */
@Serializable data class RegexOutputCopy(
    val enabled: Boolean = false,
    val outputKey: String = "",
    val pattern: String = "",
    val captureGroup: Int = 0,
    val replacement: String = "",
    val mode: RegexTransformMode = RegexTransformMode.EXTRACT,
    val display: Boolean = true,
    // Legacy documents omitted this flag and always recorded every accepted update.
    val recordTrajectory: Boolean = true
)

object RegexOutputCopies {
    private val outputKeyPattern = Regex("[\\p{L}_][\\p{L}\\p{N}_.-]*")

    fun validate(config: RegexOutputCopy, path: String): List<Issue> {
        if (!config.enabled) return emptyList()
        return buildList {
            if (!outputKeyPattern.matches(config.outputKey))
                add(Issue("$path.outputKey", "副本输出键须为有效的宏标识"))
            transformProblem(config)?.let { add(Issue("$path.pattern", it)) }
        }
    }

    fun transform(config: RegexOutputCopy, source: String): String {
        transformProblem(config)?.let { throw IllegalArgumentException(it) }
        return when (config.mode) {
            RegexTransformMode.EXTRACT -> SafePatterns.extract(config.pattern, source, config.captureGroup)
                ?: throw OutputFormatException("输出正则未匹配有效内容")
            RegexTransformMode.REPLACE -> SafePatterns.replace(config.pattern, source, config.replacement)
        }
    }

    fun derive(node: WorkflowNode, outcome: ActionOutcome): ActionOutcome {
        val config = node.regexOutputCopy
        if (!config.enabled) return outcome
        require(validate(config, "node.${node.id}.regexOutputCopy").isEmpty()) { "输出正则副本配置无效" }
        require(config.outputKey != node.outputKey) { "副本与原始输出不能使用同一个宏地址" }
        val address = "result.${config.outputKey}"
        require(outcome.macroWrites.keys.none { canonicalWriteAddress(it) == address }) { "副本输出地址已被动作写入" }
        return outcome.copy(macroWrites = outcome.macroWrites + (address to transform(config, outcome.text)))
    }

    private fun transformProblem(config: RegexOutputCopy): String? {
        val group = if (config.mode == RegexTransformMode.EXTRACT) config.captureGroup else 0
        SafePatterns.validate(config.pattern, group)?.let { return it }
        if (config.mode == RegexTransformMode.REPLACE) {
            if (config.pattern.isBlank()) return "替换模式须填写正则表达式"
            // Validate replacement references even when this particular source has no match.
            var index = 0
            val replacement = config.replacement
            while (index < replacement.length) {
                when (replacement[index++]) {
                    '\\' -> if (index == replacement.length) return "替换文本的转义不完整" else index++
                    '$' -> {
                        val start = index
                        while (index < replacement.length && replacement[index].isDigit()) index++
                        if (index == start) return "替换文本只支持 $ 后跟捕获组数字；字面 $ 请写 \\$"
                        val capture = replacement.substring(start, index).toIntOrNull()
                            ?: return "替换捕获组过大"
                        SafePatterns.validate(config.pattern, capture)?.let { return "替换文本：$it" }
                    }
                }
            }
        }
        return null
    }
}
