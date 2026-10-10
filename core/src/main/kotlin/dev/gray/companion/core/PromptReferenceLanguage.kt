package dev.gray.companion.core

/** Compiles one reference without loading resources, rendering text or running actions. */
object PromptReferenceLanguage {
    const val LANGUAGE_VERSION = 1
    const val MAX_REFERENCE_LENGTH = 4096

    /** Half-open UTF-16 offsets into the original input, including any wrapper or whitespace. */
    data class SourceSpan(val start: Int, val endExclusive: Int)
    enum class Code {
        TOO_LONG, EMPTY_REFERENCE, INVALID_WRAPPER, INVALID_LEGACY, EXPECTED_FIELD,
        EXPECTED_COLON, EXPECTED_VALUE, EXPECTED_SEPARATOR, UNCLOSED_QUOTE,
        INVALID_ESCAPE, MIXED_SYNTAX, UNKNOWN_FIELD, DUPLICATE_FIELD, MISSING_FIELD,
        INVALID_VALUE, UNSUPPORTED_FIELD, AMBIGUOUS_SCOPE
    }
    data class Diagnostic(val code: Code, val message: String, val span: SourceSpan, val field: String? = null)
    data class FieldLocation(val name: String, val span: SourceSpan, val values: List<SourceSpan>)
    data class CompileResult(
        val reference: CompiledReference?,
        val diagnostics: List<Diagnostic>,
        val fields: List<FieldLocation> = emptyList()
    )
    sealed interface CompiledReference {
        val languageVersion: Int get() = LANGUAGE_VERSION
    }
    data class LegacyReference(val expression: String) : CompiledReference
    enum class SourceType(val keyword: String) {
        PACK("pack"), CHARACTER("character"), MAIN("main"), GROUP("group"), BUILTIN("builtin")
    }
    sealed interface SourceScope {
        data class Named(val slug: String) : SourceScope
        data object Loaded : SourceScope
    }
    data class FolderScope(val path: List<String>, val includeDescendants: Boolean)
    enum class Cardinality { ONE, MANY }
    sealed interface VersionSelector {
        data object Latest : VersionSelector
        data class Turn(val offset: Int) : VersionSelector
        data class Update(val offset: Int) : VersionSelector
        data class Version(val number: Int) : VersionSelector
    }
    data class QueryPlan(
        val sourceType: SourceType,
        val source: SourceScope,
        val call: String? = null,
        val folder: FolderScope? = null,
        val name: String? = null,
        val requiredTags: Set<String> = emptySet(),
        val anyTags: Set<String> = emptySet(),
        val excludedTags: Set<String> = emptySet(),
        val at: VersionSelector = VersionSelector.Latest,
        val xml: List<String>? = null,
        val historySelection: HistorySelection? = null
    ) : CompiledReference {
        val cardinality: Cardinality get() = if (name == null) Cardinality.MANY else Cardinality.ONE
    }

    /** Accepts either an expression body or one complete {{...}} token. */
    fun compile(source: String): CompileResult {
        fun rejected(code: Code, message: String, start: Int, end: Int) =
            CompileResult(null, listOf(Diagnostic(code, message, SourceSpan(start, end))))
        if (source.length > MAX_REFERENCE_LENGTH)
            return rejected(Code.TOO_LONG, "宏引用不能超过 $MAX_REFERENCE_LENGTH 个 UTF-16 字符", MAX_REFERENCE_LENGTH, source.length)
        var start = source.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return rejected(Code.EMPTY_REFERENCE, "宏引用不能为空", 0, source.length)
        var end = source.indexOfLast { !it.isWhitespace() } + 1
        if (source.startsWith("{{", start)) {
            if (end - start < 4 || !source.substring(start, end).endsWith("}}"))
                return rejected(Code.INVALID_WRAPPER, "宏引用缺少结束的 }}", start, end)
            start += 2
            end -= 2
            while (start < end && source[start].isWhitespace()) start++
            while (end > start && source[end - 1].isWhitespace()) end--
        } else if (source.substring(start, end).endsWith("}}")) {
            return rejected(Code.INVALID_WRAPPER, "宏引用缺少开始的 {{", start, end)
        }
        if (start == end) return rejected(Code.EMPTY_REFERENCE, "宏引用不能为空", start, end)
        val body = source.substring(start, end)
        // Only an opening field token selects the new grammar. A legacy XML namespace
        // such as character.bio#ns:tag must never switch parsers because of its colon.
        var headEnd = start
        if (source[headEnd].isFieldStart()) {
            headEnd++
            while (headEnd < end && source[headEnd].isFieldPart()) headEnd++
            while (headEnd < end && source[headEnd].isWhitespace()) headEnd++
        }
        if (headEnd == start || headEnd == end || source[headEnd] != ':') {
            val error = macroExpressionError(body)
            return if (error == null) CompileResult(LegacyReference(body), emptyList())
            else rejected(Code.INVALID_LEGACY, error, start, end)
        }
        val scanner = Scanner(source, start, end)
        val parsed = scanner.scan()
        if (scanner.diagnostics.isNotEmpty())
            return CompileResult(null, scanner.diagnostics.toList(), parsed.map { it.location })
        return compileQuery(parsed, SourceSpan(start, end))
    }

    private enum class Field(val keyword: String) {
        TYPE("type"), IN("in"), CALL("call"), FOLDER("folder"), UNDER("under"), NAME("name"),
        TAG("tag"), ANY("any"), NOT("not"), AT("at"), XML("xml"),
        FROM("from"), THROUGH("through"), VIEW("view"), REVISION("revision"), SOURCE("source"), FORMAT("format"), SPEAKER("speaker")
    }
    private data class Value(val text: String, val span: SourceSpan, val quoted: Boolean)
    private data class ParsedField(val name: String, val span: SourceSpan, val values: List<Value>) {
        val location get() = FieldLocation(name, span, values.map { it.span })
    }
    private val xmlName = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
    private val version = Regex("(turn|update|version)-([0-9]+)")

    private fun compileQuery(parsed: List<ParsedField>, bodySpan: SourceSpan): CompileResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val locations = parsed.map { it.location }
        fun issue(code: Code, message: String, field: ParsedField) {
            diagnostics += Diagnostic(code, message, field.span, field.name)
        }
        val fields = linkedMapOf<Field, MutableList<ParsedField>>()
        for (item in parsed) {
            val field = Field.entries.firstOrNull { it.keyword == item.name }
            if (field == null) {
                issue(Code.UNKNOWN_FIELD, "未知选择字段：${item.name}", item)
                continue
            }
            val previous = fields.getOrPut(field) { mutableListOf() }
            if (previous.isNotEmpty() && field != Field.TAG && field != Field.NOT)
                issue(Code.DUPLICATE_FIELD, "字段 ${item.name} 不能重复", item)
            previous += item
            if (field != Field.ANY && field != Field.NOT && item.values.size != 1)
                issue(Code.INVALID_VALUE, "字段 ${item.name} 只接受一个值", item)
            if (field !in setOf(Field.XML, Field.FORMAT) && item.values.any { it.quoted })
                issue(Code.INVALID_VALUE, "仅 xml、format 字段允许双引号值", item)
            if (field == Field.FORMAT && item.values.any { !it.quoted })
                issue(Code.INVALID_VALUE, "format 须为双引号包裹的 JSON", item)
        }
        for (required in listOf(Field.TYPE, Field.IN)) if (required !in fields)
            diagnostics += Diagnostic(Code.MISSING_FIELD, "缺少 ${required.keyword} 字段", bodySpan, required.keyword)
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics, locations)
        fun field(key: Field) = fields[key]?.firstOrNull()
        fun text(key: Field) = field(key)?.values?.singleOrNull()?.text
        fun invalid(key: Field, message: String) = issue(Code.INVALID_VALUE, message, fields.getValue(key).first())
        fun checkedSlug(key: Field): String? = text(key)?.also {
            if (!MacroNaming.isValidSlug(it)) invalid(key, "${key.keyword} 须为小写英文标识，可含数字、下划线和连字符")
        }
        val sourceType = SourceType.entries.firstOrNull { it.keyword == text(Field.TYPE) }
        if (sourceType == null) invalid(Field.TYPE, "type 须为 pack、character、main、group 或 builtin")
        val source = if (text(Field.IN) == "*") SourceScope.Loaded
            else checkedSlug(Field.IN)?.let { SourceScope.Named(it) }
        val call = checkedSlug(Field.CALL)
        val name = checkedSlug(Field.NAME)
        if (Field.FOLDER in fields && Field.UNDER in fields)
            issue(Code.UNSUPPORTED_FIELD, "folder 与 under 不能同时使用", fields.getValue(Field.UNDER).first())
        val folderField = if (Field.FOLDER in fields) Field.FOLDER else Field.UNDER
        val folder = text(folderField)?.let { path ->
            val parts = path.split('/')
            if (parts.any { !MacroNaming.isValidSlug(it) }) invalid(folderField, "目录路径须由英文标识以 / 分隔，不能有空层级")
            FolderScope(parts, folderField == Field.UNDER)
        }
        fun tags(key: Field): Set<String> = fields[key].orEmpty().flatMap { item ->
            item.values.map { value ->
                if (!MacroNaming.isValidSlug(value.text)) diagnostics += Diagnostic(Code.INVALID_VALUE,
                    "标签须为小写英文标识，可含数字、下划线和连字符", value.span, item.name)
                value.text
            }
        }.toSortedSet().toSet()
        val requiredTags = tags(Field.TAG)
        val anyTags = tags(Field.ANY)
        val excludedTags = tags(Field.NOT)
        val at = text(Field.AT)?.let { raw ->
            if (raw == "latest") VersionSelector.Latest else {
                val match = version.matchEntire(raw)
                val number = match?.groupValues?.get(2)?.toIntOrNull()
                if (match == null || number == null || (match.groupValues[1] == "version" && number == 0)) {
                    invalid(Field.AT, "at 须为 latest、turn-N、update-N 或 version-N；N 须在 Int 范围内，version 从 1 开始")
                    null
                } else when (match.groupValues[1]) {
                    "turn" -> VersionSelector.Turn(number)
                    "update" -> VersionSelector.Update(number)
                    else -> VersionSelector.Version(number)
                }
            }
        } ?: VersionSelector.Latest
        val xml = text(Field.XML)?.split('/')?.also { parts ->
            if (parts.any { !xmlName.matches(it) }) invalid(Field.XML, "xml 须为非空的元素路径，以 / 分隔")
        }
        if (sourceType != null) {
            if (call != null && sourceType !in setOf(SourceType.MAIN, SourceType.GROUP))
                issue(Code.UNSUPPORTED_FIELD, "call 仅适用于 main 或 group", fields.getValue(Field.CALL).first())
            if (folder != null && sourceType !in setOf(SourceType.PACK, SourceType.CHARACTER))
                issue(Code.UNSUPPORTED_FIELD, "folder/under 仅适用于 pack 或 character", fields.getValue(folderField).first())
            if (name != null && sourceType in setOf(SourceType.MAIN, SourceType.GROUP) && call == null)
                diagnostics += Diagnostic(Code.MISSING_FIELD, "main/group 单取须提供 call", fields.getValue(Field.NAME).first().span, "call")
            if (sourceType == SourceType.BUILTIN) {
                if (at != VersionSelector.Latest) issue(Code.UNSUPPORTED_FIELD, "builtin 仅支持 at:latest", fields.getValue(Field.AT).first())
                if (xml != null) issue(Code.UNSUPPORTED_FIELD, "builtin 不支持 xml", fields.getValue(Field.XML).first())
            }
        }
        if (name != null && source == SourceScope.Loaded)
            issue(Code.AMBIGUOUS_SCOPE, "name 单取须指定来源，不能使用 in:*", fields.getValue(Field.IN).first())
        if (name == null) for (singleOnly in listOf(Field.AT, Field.XML)) field(singleOnly)?.let {
            issue(Code.UNSUPPORTED_FIELD, "${singleOnly.keyword} 仅用于有 name 的单取引用", it)
        }
        val isHistory = sourceType == SourceType.BUILTIN && source == SourceScope.Named("history") && name in setOf("messages", "context")
        val historyFields = listOf(Field.FROM, Field.THROUGH, Field.VIEW, Field.REVISION, Field.SOURCE, Field.FORMAT, Field.SPEAKER)
        if (!isHistory) historyFields.forEach { key -> field(key)?.let {
            issue(Code.UNSUPPORTED_FIELD, "${key.keyword} 仅支持 builtin history messages", it)
        } }
        fun point(key: Field, fallback: HistoryPoint): HistoryPoint {
            val value = text(key) ?: return fallback
            val match = Regex("(start|end|percent)-([0-9]+)").matchEntire(value)
            val number = match?.groupValues?.get(2)?.toIntOrNull()
            if (match == null || number == null || (match.groupValues[1] == "percent" && number !in 0..100) ||
                (match.groupValues[1] != "percent" && number < 1)) {
                invalid(key, "范围端点须为 start-N、end-N（N 从 1 开始）或 percent-0…100")
                return fallback
            }
            val anchor = when (match.groupValues[1]) {
                "start" -> HistoryAnchor.START
                "end" -> HistoryAnchor.END
                else -> HistoryAnchor.PERCENT
            }
            return HistoryPoint(anchor, number.toDouble())
        }
        val selection = if (isHistory) {
            listOf(Field.TAG, Field.ANY, Field.NOT).forEach { key -> field(key)?.let {
                issue(Code.UNSUPPORTED_FIELD, "历史消息选择不支持标签条件", it)
            } }
            val viewId = checkedSlug(Field.VIEW)
            val revision = text(Field.REVISION)?.toIntOrNull()
            if (Field.REVISION in fields && (revision == null || revision < 1 || viewId == null))
                invalid(Field.REVISION, "revision 须为正整数，且须指定 view")
            val sourceKind = when (text(Field.SOURCE)) {
                null, "display" -> HistorySourceKind.DISPLAY
                "model" -> HistorySourceKind.MODEL_RAW
                else -> { invalid(Field.SOURCE, "source 须为 display 或 model"); HistorySourceKind.DISPLAY }
            }
            val sourceRole = when (text(Field.SPEAKER)) {
                null -> null
                "user" -> Role.USER
                "assistant" -> Role.ASSISTANT
                "system" -> Role.SYSTEM
                else -> { invalid(Field.SPEAKER, "speaker 须为 user、assistant 或 system；省略时读取全部原始发言方"); null }
            }
            val format = text(Field.FORMAT)?.let { raw ->
                try { HistoryReferences.decodeFormat(raw) }
                catch (failure: IllegalArgumentException) {
                    invalid(Field.FORMAT, "History 局部格式无效：${failure.message.orEmpty()}")
                    null
                }
            }
            HistorySelection(viewId, revision, HistoryRange(point(Field.FROM, HistoryPoint()),
                point(Field.THROUGH, HistoryPoint(HistoryAnchor.END, 1.0))), compressed = name == "context", sourceKind = sourceKind,
                format = format, rangeExplicit = Field.FROM in fields || Field.THROUGH in fields, sourceRole = sourceRole)
        } else null
        if (diagnostics.isNotEmpty()) return CompileResult(null, diagnostics, locations)
        return CompileResult(QueryPlan(requireNotNull(sourceType), requireNotNull(source), call, folder, name,
            requiredTags, anyTags, excludedTags, at, xml, selection), emptyList(), locations)
    }

    private fun Char.isFieldStart() = this in 'a'..'z' || this in 'A'..'Z' || this == '_'
    private fun Char.isFieldPart() = isFieldStart() || this in '0'..'9' || this == '-'

    /** A bounded linear scanner; quoted values are decoded before field-specific validation. */
    private class Scanner(private val source: String, start: Int, private val end: Int) {
        private var cursor = start
        val diagnostics = mutableListOf<Diagnostic>()
        private fun whitespace() { while (cursor < end && source[cursor].isWhitespace()) cursor++ }
        private fun error(code: Code, message: String, start: Int = cursor, finish: Int = minOf(cursor + 1, end), field: String? = null) {
            diagnostics += Diagnostic(code, message, SourceSpan(start, finish), field)
        }
        fun scan(): List<ParsedField> {
            val fields = mutableListOf<ParsedField>()
            while (cursor < end) {
                whitespace()
                val start = cursor
                if (cursor == end || !source[cursor].isFieldStart()) {
                    error(Code.EXPECTED_FIELD, "此处需要字段名")
                    return fields
                }
                cursor++
                while (cursor < end && source[cursor].isFieldPart()) cursor++
                val name = source.substring(start, cursor)
                whitespace()
                if (cursor == end || source[cursor] != ':') {
                    error(Code.EXPECTED_COLON, "字段名后需要 :；含点号的 XML 路径须加双引号", field = name)
                    return fields
                }
                cursor++
                val values = mutableListOf<Value>()
                while (true) {
                    whitespace()
                    val parsedValue = value(name) ?: return fields
                    values += parsedValue
                    whitespace()
                    if (cursor == end || source[cursor] != ',') break
                    cursor++
                }
                fields += ParsedField(name, SourceSpan(start, values.last().span.endExclusive), values)
                if (cursor == end) return fields
                if (source[cursor] != '.') {
                    error(if (source[cursor] in "@#") Code.MIXED_SYNTAX else Code.EXPECTED_SEPARATOR,
                        "字段之间使用点号；新语法的版本和 XML 使用 at/xml 字段", field = name)
                    return fields
                }
                cursor++
                whitespace()
                if (cursor == end) {
                    error(Code.EXPECTED_FIELD, "点号后需要字段")
                    return fields
                }
            }
            return fields
        }
        private fun value(field: String): Value? {
            val start = cursor
            if (cursor == end || source[cursor] in ".,") {
                error(Code.EXPECTED_VALUE, "字段或列表项不能为空", field = field)
                return null
            }
            if (source[cursor] == '"') {
                cursor++
                val decoded = StringBuilder()
                while (cursor < end) {
                    when (val char = source[cursor++]) {
                        '"' -> return Value(decoded.toString(), SourceSpan(start, cursor), true)
                        '\\' -> {
                            val escapeStart = cursor - 1
                            if (cursor == end || source[cursor] !in "\"\\") {
                                error(Code.INVALID_ESCAPE, "只支持双引号和反斜线的转义", escapeStart, minOf(cursor + 1, end), field)
                                return null
                            }
                            decoded.append(source[cursor++])
                        }
                        else -> decoded.append(char)
                    }
                }
                error(Code.UNCLOSED_QUOTE, "双引号未闭合", start, end, field)
                return null
            }
            while (cursor < end && !source[cursor].isWhitespace() && source[cursor] !in ".,") {
                if (source[cursor] in "@#") {
                    error(Code.MIXED_SYNTAX, "新语法不混用 @/#；请使用 at/xml 字段", field = field)
                    return null
                }
                if (source[cursor] in ":\"{}\\") {
                    error(Code.INVALID_VALUE, "值中包含分隔符；含冒号的 XML 路径须加双引号", field = field)
                    return null
                }
                cursor++
            }
            return Value(source.substring(start, cursor), SourceSpan(start, cursor), false)
        }
    }
}
