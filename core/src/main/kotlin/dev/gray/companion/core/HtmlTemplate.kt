package dev.gray.companion.core

/** Pure binding of one macro value to a static HTML/CSS fragment. No browser or model work lives here. */
data class HtmlRenderResult(val html: String? = null, val error: String? = null)

object HtmlTemplate {
    private val namePattern = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val token = Regex("\\{\\{([^{}]*)\\}\\}")
    private val inertTag = Regex("<\\s*/?\\s*(?:script|style|iframe|object|embed|base|link|meta)\\b", RegexOption.IGNORE_CASE)

    /** The editor and LibraryValidator use the same configuration rules as candidate validation. */
    fun validate(tile: MacroTile): List<Issue> {
        if (tile.kind != MacroTileKind.HTML) return emptyList()
        val path = "tile.${tile.id}"
        val issues = mutableListOf<Issue>()
        if (tile.htmlTemplate.isBlank()) issues += Issue("$path.htmlTemplate", "HTML 内容不能为空")
        if (tile.htmlTemplate.length > 32_768) issues += Issue("$path.htmlTemplate", "HTML 内容过长")
        if (tile.htmlCss.length > 16_384) issues += Issue("$path.htmlCss", "CSS 内容过长")
        if (inertTag.containsMatchIn(tile.htmlTemplate))
            issues += Issue("$path.htmlTemplate", "HTML 模板只填写正文片段，不支持脚本、样式标签或嵌入资源")
        if (Regex("</\\s*style", RegexOption.IGNORE_CASE).containsMatchIn(tile.htmlCss) ||
            Regex("@import\\b|url\\s*\\(", RegexOption.IGNORE_CASE).containsMatchIn(tile.htmlCss))
            issues += Issue("$path.htmlCss", "CSS 不支持外部资源或样式标签")
        if (tile.htmlBindings.isEmpty() || tile.htmlBindings.size > 32)
            issues += Issue("$path.htmlBindings", "HTML 值须有 1–32 项")
        val names = mutableSetOf<String>()
        tile.htmlBindings.forEachIndexed { index, binding ->
            val at = "$path.htmlBindings.$index"
            if (binding.name.length > 64 || !namePattern.matches(binding.name))
                issues += Issue("$at.name", "值名须以英文字母开头，只包含字母、数字和下划线")
            if (!names.add(binding.name)) issues += Issue("$at.name", "HTML 值名不能重复")
            SafePatterns.validate(binding.pattern, binding.captureGroup)?.let { issues += Issue("$at.pattern", it) }
        }
        fun check(source: String, at: String, css: Boolean) {
            val remainder = token.replace(source, "")
            if ("{{" in remainder || "}}" in remainder) issues += Issue(at, "HTML 占位符格式不完整")
            token.findAll(source).forEach { match ->
                val parts = match.groupValues[1].trim().split('.')
                val binding = if (parts.size == 2) tile.htmlBindings.firstOrNull { it.name == parts[1] } else null
                val expected = when (parts.firstOrNull()) {
                    "text" -> MacroHtmlValueKind.TEXT
                    "number" -> MacroHtmlValueKind.NUMBER
                    else -> null
                }
                when {
                    expected == null || binding == null || binding.kind != expected ->
                        issues += Issue(at, "未知或类型不符的 HTML 占位符：${match.value}")
                    css && expected == MacroHtmlValueKind.TEXT ->
                        issues += Issue(at, "文字值不能插入 CSS：${match.value}")
                    !css && expected == MacroHtmlValueKind.TEXT && insideTag(source, match.range.first) ->
                        issues += Issue(at, "文字值只能放在元素正文中：${match.value}")
                }
            }
        }
        check(tile.htmlTemplate, "$path.htmlTemplate", false)
        check(tile.htmlCss, "$path.htmlCss", true)
        return issues
    }

    /** Returns a style plus body fragment; the Android view adds the inert document shell and CSP. */
    fun render(tile: MacroTile, extracted: String): HtmlRenderResult {
        validate(tile).firstOrNull()?.let { return HtmlRenderResult(error = "HTML 模板配置无效：${it.message}") }
        return renderValidated(tile, extracted)
    }

    internal fun renderValidated(tile: MacroTile, extracted: String): HtmlRenderResult {
        if (extracted.length > 65_536) return HtmlRenderResult(error = "HTML 宏内容过长")
        val values = mutableMapOf<String, String>()
        for (binding in tile.htmlBindings) {
            val matched = SafePatterns.extract(binding.pattern, extracted, binding.captureGroup)
                ?: return HtmlRenderResult(error = "HTML 字段 ${binding.name} 未匹配")
            val trimmed = matched.trim()
            if (trimmed.isEmpty()) return HtmlRenderResult(error = "HTML 字段 ${binding.name} 为空")
            values[binding.name] = when (binding.kind) {
                MacroHtmlValueKind.TEXT -> {
                    if (matched.length > 4_096) return HtmlRenderResult(error = "HTML 字段 ${binding.name} 过长")
                    escapeText(matched)
                }
                MacroHtmlValueKind.NUMBER -> {
                    val number = trimmed.takeIf { it.length <= 64 }?.toBigDecimalOrNull()
                        ?: return HtmlRenderResult(error = "HTML 字段 ${binding.name} 需要有限数值")
                    if (number.scale() !in -128..128 || number.precision() > 64 || !number.toDouble().isFinite())
                        return HtmlRenderResult(error = "HTML 字段 ${binding.name} 需要有限数值")
                    number.stripTrailingZeros().toPlainString()
                }
            }
        }
        fun substitute(source: String): String = token.replace(source) { match -> values.getValue(match.groupValues[1].trim().substringAfter('.')) }
        return HtmlRenderResult("<style>${substitute(tile.htmlCss)}</style>\n${substitute(tile.htmlTemplate)}")
    }

    private fun escapeText(value: String): String = buildString(value.length) {
        value.forEach { char -> append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> char.toString()
        }) }
    }

    private fun insideTag(source: String, end: Int): Boolean {
        var tag = false
        var comment = false
        var quote: Char? = null
        var index = 0
        while (index < end) {
            if (comment && source.startsWith("-->", index)) {
                comment = false
                index += 3
                continue
            }
            if (!comment && !tag && source.startsWith("<!--", index)) {
                comment = true
                index += 4
                continue
            }
            val char = source[index]
            if (comment) {
                // A bound value in a comment is not visible element text.
            } else if (!tag) {
                if (char == '<') tag = true
            } else if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '>' -> tag = false
            }
            index++
        }
        return tag || comment
    }
}
