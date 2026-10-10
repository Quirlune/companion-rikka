package dev.gray.companion.core

/** A local display placeholder, deliberately outside the prompt/macro language. */
object BodyAppearanceTemplates {
    const val DEFAULT = "{{正文}}"
    const val MAX_LENGTH = 100_000

    fun isCustom(template: String): Boolean = template.isNotBlank() && template.trim() != DEFAULT

    fun issue(template: String): String? = when {
        template.length > MAX_LENGTH -> "正文模板不能超过 $MAX_LENGTH 个字符"
        !isCustom(template) -> null
        template.indexOf(DEFAULT) < 0 || template.indexOf(DEFAULT) != template.lastIndexOf(DEFAULT) -> "模板需要且只能包含一个 {{正文}} 占位符"
        else -> null
    }
}
