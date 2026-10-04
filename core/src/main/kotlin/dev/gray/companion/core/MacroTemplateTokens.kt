package dev.gray.companion.core

/** Shared UTF-16 token spans for rendering, validation and native caret editing. */
object MacroTemplateTokens {
    data class Token(val start: Int, val endExclusive: Int, val expression: String, val closed: Boolean)
    data class Diagnostic(val start: Int, val endExclusive: Int, val message: String)
    data class Scan(val tokens: List<Token>, val diagnostics: List<Diagnostic>)
    private val legacyToken = Regex("\\{\\{([^{}]*)\\}\\}")

    fun scan(source: String, executionVersion: Int = 8): Scan {
        if (executionVersion < 8) {
            val tokens = legacyToken.findAll(source).map {
                Token(it.range.first, it.range.last + 1, it.groupValues[1], true)
            }.toList()
            val remainder = legacyToken.replace(source, "")
            val diagnostics = if ("{{" in remainder || "}}" in remainder)
                listOf(Diagnostic(0, source.length, "宏格式不完整")) else emptyList()
            return Scan(tokens, diagnostics)
        }
        val tokens = mutableListOf<Token>()
        val diagnostics = mutableListOf<Diagnostic>()
        var cursor = 0
        while (cursor < source.length) {
            if (source.startsWith("}}", cursor)) {
                diagnostics += Diagnostic(cursor, cursor + 2, "宏缺少开始的 {{")
                cursor += 2
                continue
            }
            if (!source.startsWith("{{", cursor)) { cursor++; continue }
            val start = cursor
            cursor += 2
            val bodyStart = cursor
            var quoted = false
            var closed = false
            while (cursor < source.length) {
                val char = source[cursor]
                if (quoted && char == '\\') {
                    cursor += if (cursor + 1 < source.length) 2 else 1
                } else if (char == '"') {
                    quoted = !quoted
                    cursor++
                } else if (!quoted && source.startsWith("}}", cursor)) {
                    tokens += Token(start, cursor + 2, source.substring(bodyStart, cursor), true)
                    cursor += 2
                    closed = true
                    break
                } else cursor++
            }
            if (!closed) {
                tokens += Token(start, source.length, source.substring(bodyStart), false)
                diagnostics += Diagnostic(start, source.length, if (quoted) "宏内双引号未闭合" else "宏缺少结束的 }}")
            }
        }
        return Scan(tokens, diagnostics)
    }

    /** Incomplete tokens stay in place; callers report scan diagnostics before using the result. */
    fun replace(source: String, scan: Scan, transform: (Token) -> String): String = buildString {
        var cursor = 0
        scan.tokens.filter { it.closed }.forEach { token ->
            append(source, cursor, token.start)
            append(transform(token))
            cursor = token.endExclusive
        }
        append(source, cursor, source.length)
    }
}
