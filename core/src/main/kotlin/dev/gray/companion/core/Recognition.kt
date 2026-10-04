package dev.gray.companion.core

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Shared immutable patterns; matching does not create a worker or initialize optional services. */
object SafePatterns {
    private val cache = object : LinkedHashMap<String, Pattern>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>) = size > 64
    }
    @Synchronized private fun compiled(pattern: String): Pattern = cache.getOrPut(pattern) { Pattern.compile(pattern) }
    fun validate(pattern: String, captureGroup: Int = 0): String? {
        if (pattern.length > 2048) return "正则表达式过长"
        if (captureGroup < 0) return "捕获组不能小于零"
        if (pattern.isBlank()) return if (captureGroup == 0) null else "未填写正则表达式，不能指定捕获组"
        return try {
            if (captureGroup > compiled(pattern).groupCount()) "捕获组不存在" else null
        } catch (_: PatternSyntaxException) {
            "正则表达式无效或使用了不支持的语法"
        }
    }
    fun extract(pattern: String, text: String, captureGroup: Int = 0): String? {
        if (pattern.isBlank()) return text
        val matcher = compiled(pattern).matcher(text)
        return if (matcher.find()) matcher.group(captureGroup) else null
    }
    fun extractAll(pattern: String, text: String, captureGroup: Int = 0): List<String> {
        require(validate(pattern, captureGroup) == null) { "正则表达式或捕获组无效" }
        if (pattern.isBlank()) return listOf(text)
        val matcher = compiled(pattern).matcher(text)
        return buildList { while (matcher.find()) matcher.group(captureGroup)?.let(::add) }
    }
    fun replace(pattern: String, text: String, replacement: String): String =
        compiled(pattern).matcher(text).replaceAll(replacement)
}

object RecognitionEvaluator {
    fun validate(recognition: Recognition?, path: String = "recognition", executionVersion: Int = ExecutionSemantics.CURRENT): List<Issue> {
        if (recognition == null) return emptyList()
        val issues = mutableListOf<Issue>()
        var count = 0
        fun visit(group: Recognition, depth: Int) {
            if (depth > 8 || ++count > 128) { issues += Issue(path, "识别规则层级或数量过多"); return }
            group.rules.forEachIndexed { index, rule ->
                val rulePath = "$path.rule.$index"
                val scan = MacroTemplateTokens.scan(rule.template, executionVersion)
                scan.diagnostics.forEach { issues += Issue(rulePath, it.message) }
                scan.tokens.filter { it.closed }.forEach { token ->
                    macroExpressionError(token.expression.trim(), executionVersion)?.let { issues += Issue(rulePath, it) }
                }
                SafePatterns.validate(rule.pattern, rule.captureGroup)?.let { issues += Issue("$rulePath.pattern", it) }
                if (rule.op != Compare.EXISTS && (rule.valueType == RecognitionValueType.NUMBER ||
                    rule.op in setOf(Compare.GREATER, Compare.GREATER_OR_EQUAL, Compare.LESS, Compare.LESS_OR_EQUAL)) &&
                    rule.value.toBigDecimalOrNull() == null) issues += Issue("$rulePath.value", "数值比较需要有效数字")
            }
            group.groups.forEach { visit(it, depth + 1) }
        }
        visit(recognition, 0)
        return issues
    }

    fun matches(recognition: Recognition?, read: (String) -> String): Boolean {
        if (recognition == null) return true
        fun ruleMatches(rule: RecognitionRule): Boolean {
            val actual = SafePatterns.extract(rule.pattern, read(rule.template), rule.captureGroup)
            val answer = compare(actual, rule.op, rule.value, rule.valueType)
            return if (rule.negate) !answer else answer
        }
        val answer = when (recognition.mode) {
            RecognitionMode.ALL -> recognition.rules.all(::ruleMatches) && recognition.groups.all { matches(it, read) }
            RecognitionMode.ANY -> recognition.rules.any(::ruleMatches) || recognition.groups.any { matches(it, read) }
        }
        return if (recognition.negate) !answer else answer
    }

    private fun compare(actual: String?, op: Compare, expected: String, valueType: RecognitionValueType): Boolean {
        if (op == Compare.EXISTS) return if (valueType == RecognitionValueType.NUMBER)
            actual?.toBigDecimalOrNull() != null else !actual.isNullOrEmpty()
        if (actual == null) return false
        if (valueType == RecognitionValueType.NUMBER || op in setOf(
                Compare.GREATER, Compare.GREATER_OR_EQUAL, Compare.LESS, Compare.LESS_OR_EQUAL)) {
            val a = actual.toBigDecimalOrNull() ?: return false
            val b = expected.toBigDecimalOrNull() ?: return false
            val order = a.compareTo(b)
            return when (op) {
                Compare.EQUALS -> order == 0
                Compare.NOT_EQUALS -> order != 0
                Compare.GREATER -> order > 0
                Compare.GREATER_OR_EQUAL -> order >= 0
                Compare.LESS -> order < 0
                Compare.LESS_OR_EQUAL -> order <= 0
                else -> false
            }
        }
        return when (op) {
            Compare.EQUALS -> actual == expected
            Compare.NOT_EQUALS -> actual != expected
            else -> false
        }
    }
}

internal fun checkedRecognition(
    recognition: Recognition?, path: String, issues: MutableList<Issue>, executionVersion: Int = ExecutionSemantics.CURRENT, read: (String) -> String
): Boolean {
    val validation = RecognitionEvaluator.validate(recognition, path, executionVersion)
    issues += validation
    return validation.none { it.error } && RecognitionEvaluator.matches(recognition, read)
}
