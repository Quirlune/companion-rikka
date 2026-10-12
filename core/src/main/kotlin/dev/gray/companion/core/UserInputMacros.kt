package dev.gray.companion.core

import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable enum class UserMacroValueKind { TEXT, REFERENCE, RANDOM_REFERENCE }

/** References use the existing prompt macro language, for example {{state.weather}}. */
@Serializable data class UserMacroValue(
    val kind: UserMacroValueKind = UserMacroValueKind.TEXT,
    val text: String = "",
    val references: List<String> = emptyList()
)

/** The shortcut includes its slash, for example /抽天气. */
@Serializable data class UserMacroDefinition(val shortcut: String, val value: UserMacroValue)

data class ArmedUserMacro(val start: Int, val endExclusive: Int, val shortcut: String)
data class UserMacroExpansion(val text: String? = null, val issues: List<Issue> = emptyList()) {
    val succeeded: Boolean get() = text != null && issues.none { it.error }
}

/** Drafts remain plain text. An ASCII space arms a registered shortcut without evaluating it. */
object UserInputMacros {
    private val shortcutPattern = Regex("/[\\p{L}\\p{N}_-]+")
    private val tokenPattern = Regex("(?<!\\S)/[\\p{L}\\p{N}_-]+ ")
    private val referencePattern = Regex("\\{\\{([^{}]+)\\}\\}")

    fun validate(definition: UserMacroDefinition, path: String = "userMacro"): List<Issue> = buildList {
        if (!shortcutPattern.matches(definition.shortcut))
            add(Issue("$path.shortcut", "快捷词须以 / 开头，后接文字、数字、下划线或连字符"))
        val value = definition.value
        when (value.kind) {
            UserMacroValueKind.TEXT -> {
                if (value.text.isBlank() || value.references.isNotEmpty())
                    add(Issue("$path.value", "固定文本不能为空，且不能同时配置引用"))
            }
            UserMacroValueKind.REFERENCE -> {
                if (value.text.isNotEmpty() || value.references.size != 1)
                    add(Issue("$path.value", "单一引用须恰好选择一个已有值"))
            }
            UserMacroValueKind.RANDOM_REFERENCE -> {
                if (value.text.isNotEmpty() || value.references.isEmpty() ||
                    value.references.map { it.trim() }.distinct().size != value.references.size)
                    add(Issue("$path.value", "随机选择须提供不重复的已有值引用"))
            }
        }
        value.references.forEachIndexed { index, reference ->
            val expression = referencePattern.matchEntire(reference.trim())?.groupValues?.get(1)?.trim()
            val base = expression?.substringBefore('@')?.substringBefore('#')
            val supported = base == "user.name" || base == "user.prompt" ||
                listOf("character.", "pack.", "result.", "macro.", "state.", "category.")
                    .any { base?.startsWith(it) == true }
            if (expression == null || !supported || macroExpressionError(expression) != null)
                add(Issue("$path.value.references.$index", "请选择可读取的单个已有值宏"))
        }
    }

    fun armedSpans(raw: String, definitions: Map<String, UserMacroDefinition>): List<ArmedUserMacro> =
        tokenPattern.findAll(raw).mapNotNull { match ->
            val shortcut = match.value.dropLast(1)
            if (definitions[shortcut]?.shortcut == shortcut) ArmedUserMacro(match.range.first,
                match.range.last + 1, shortcut) else null
        }.toList()

    /** Call only when admitting a new User message, under the same lock as its durable save. */
    fun expand(raw: String, library: Library, session: Session,
               executionVersion: Int = ExecutionSemantics.CURRENT,
               chooseIndex: (Int) -> Int = { Random.Default.nextInt(it) }): UserMacroExpansion {
        val spans = armedSpans(raw, session.userMacros)
        if (spans.isEmpty()) return UserMacroExpansion(raw)
        val resolver = MacroResolver(library, session, executionVersion = executionVersion)
        val output = StringBuilder()
        val evaluated = mutableMapOf<String, String>()
        var cursor = 0
        for (span in spans) {
            val definition = session.userMacros[span.shortcut] ?: continue
            val issue = validate(definition).firstOrNull { it.error }
            if (issue != null) return UserMacroExpansion(issues = listOf(issue))
            output.append(raw, cursor, span.start)
            val expanded = evaluated[span.shortcut] ?: run {
                val value = definition.value
                val selected = when (value.kind) {
                    UserMacroValueKind.TEXT -> value.text
                    UserMacroValueKind.REFERENCE, UserMacroValueKind.RANDOM_REFERENCE -> {
                        val resolved = value.references.map { reference ->
                            val expression = referencePattern.matchEntire(reference.trim())!!.groupValues[1].trim()
                            val before = resolver.issues.size
                            val result = resolver.read(expression)
                            if (resolver.issues.size > before)
                                return UserMacroExpansion(issues = listOf(Issue("userMacro.${span.shortcut}",
                                    "快捷词引用的值不存在或不可用：${span.shortcut}")) + resolver.issues.drop(before))
                            result
                        }
                        if (value.kind == UserMacroValueKind.REFERENCE) resolved.single()
                        else resolved[chooseIndex(resolved.size)]
                    }
                }
                evaluated[span.shortcut] = selected
                selected
            }
            output.append(expanded)
            // The trigger space remains an ordinary separator after the replacement.
            output.append(' ')
            cursor = span.endExclusive
        }
        output.append(raw, cursor, raw.length)
        return UserMacroExpansion(output.toString())
    }
}
