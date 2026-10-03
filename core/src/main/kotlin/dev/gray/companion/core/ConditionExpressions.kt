package dev.gray.companion.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A bounded, read-only expression language over a captured resolver and run snapshot. */
object ConditionExpressions {
    private const val MAX_LENGTH = 4096
    private const val MAX_TOKENS = 1024
    private const val MAX_DEPTH = 16
    private val cache = object : LinkedHashMap<String, Expr>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Expr>) = size > 64
    }

    fun validate(expression: String): String? = try {
        parse(expression)
        null
    } catch (error: Syntax) {
        error.message ?: "条件表达式无效"
    }

    fun matches(
        expression: String,
        state: Map<String, String>,
        results: Map<String, String> = emptyMap(),
        input: String = "",
        readMacro: ((String) -> String?)? = null
    ): Boolean = try {
        matchesStrict(expression, state, results, input, readMacro)
    } catch (_: RuntimeException) {
        false
    }

    internal fun matchesStrict(expression: String, state: Map<String, String>,
                               results: Map<String, String> = emptyMap(), input: String = "",
                               readMacro: ((String) -> String?)? = null): Boolean =
        parse(expression).eval(Context(state, results, input, readMacro)).truthy()

    /** Scalar evaluation for processing. Invalid arithmetic is an error, never a silent false. */
    fun evaluate(expression: String, state: Map<String, String> = emptyMap(),
                 results: Map<String, String> = emptyMap(), value: String = "",
                 readMacro: ((String) -> String?)? = null): String =
        parse(expression).eval(Context(state, results, value, readMacro)).text.orEmpty()

    private data class Context(
        val state: Map<String, String>, val results: Map<String, String>, val input: String,
        val readMacro: ((String) -> String?)?
    ) {
        fun macro(address: String): String? = if (readMacro != null) readMacro.invoke(address) else when {
            address == "input" -> input
            address.startsWith("state.") -> state[address.substring(6)]
            address.startsWith("result.") && '@' !in address -> results[address.substring(7)]
            else -> null
        }
    }
    private data class Value(val text: String?, val type: Type = Type.STRING) {
        fun truthy(): Boolean = when (type) {
            Type.BOOLEAN -> text == "true"
            Type.NUMBER -> text?.toBigDecimalOrNull()?.let { it.signum() != 0 } ?: false
            Type.STRING -> !text.isNullOrEmpty() && text != "false"
        }
    }
    private enum class Type { STRING, NUMBER, BOOLEAN }
    private sealed interface Expr { fun eval(context: Context): Value }
    private data class Literal(val value: Value) : Expr { override fun eval(context: Context) = value }
    private data class Variable(val name: String) : Expr {
        override fun eval(context: Context) = Value(when {
            name == "input" || name == "value" -> context.input
            name.startsWith("state.") -> context.state[name.substring(6)]
            else -> context.results[name.substring(7)]
        })
    }
    private data class Exists(val operand: Expr) : Expr {
        override fun eval(context: Context) = Value((!operand.eval(context).text.isNullOrEmpty()).toString(), Type.BOOLEAN)
    }
    private data class Macro(val address: String) : Expr {
        override fun eval(context: Context) = Value(context.macro(address))
    }
    private data class Extract(val operand: Expr, val pattern: String, val group: Int) : Expr {
        override fun eval(context: Context) = Value(operand.eval(context).text?.let {
            SafePatterns.extract(pattern, it, group)
        })
    }
    private data class Coerce(val operand: Expr, val type: Type) : Expr {
        override fun eval(context: Context): Value {
            val value = operand.eval(context).text
            if (type == Type.NUMBER) {
                val numeric = value?.toBigDecimalOrNull() ?: throw IllegalArgumentException("数值无效")
                return Value(numeric.stripTrailingZeros().toPlainString(), Type.NUMBER)
            }
            return Value(value, type)
        }
    }
    private data class Call(val name: String, val arguments: List<Expr>) : Expr {
        override fun eval(context: Context): Value {
            fun textAt(index: Int) = arguments[index].eval(context).text.orEmpty()
            fun numberAt(index: Int) = textAt(index).toBigDecimalOrNull() ?: throw IllegalArgumentException("数值无效")
            fun number(value: BigDecimal) = Value(value.stripTrailingZeros().toPlainString(), Type.NUMBER)
            return when (name) {
                "if" -> arguments[if (arguments[0].eval(context).truthy()) 1 else 2].eval(context)
                "concat" -> Value(arguments.joinToString("") { it.eval(context).text.orEmpty() })
                "replace" -> Value(textAt(0).replace(textAt(1), textAt(2)))
                "add" -> number(numberAt(0) + numberAt(1))
                "subtract" -> number(numberAt(0) - numberAt(1))
                "multiply" -> number(numberAt(0) * numberAt(1))
                "divide" -> {
                    val divisor = numberAt(1)
                    require(divisor.signum() != 0) { "不能除以零" }
                    number(numberAt(0).divide(divisor, MathContext.DECIMAL128))
                }
                "round" -> {
                    val digits = if (arguments.size == 2) numberAt(1).intValueExact() else 0
                    require(digits in 0..12) { "小数位数须在 0–12 之间" }
                    number(numberAt(0).setScale(digits, RoundingMode.HALF_UP))
                }
                "clamp" -> {
                    val lower = numberAt(1)
                    val upper = numberAt(2)
                    require(lower <= upper) { "数值边界无效" }
                    number(numberAt(0).max(lower).min(upper))
                }
                else -> error("未知函数")
            }
        }
    }
    private data class Not(val operand: Expr) : Expr {
        override fun eval(context: Context) = Value((!operand.eval(context).truthy()).toString(), Type.BOOLEAN)
    }
    private data class Binary(val op: String, val left: Expr, val right: Expr) : Expr {
        override fun eval(context: Context): Value {
            val a = left.eval(context)
            if (op == "&&" && !a.truthy()) return Value("false", Type.BOOLEAN)
            if (op == "||" && a.truthy()) return Value("true", Type.BOOLEAN)
            val b = right.eval(context)
            val answer = when (op) {
                "&&" -> a.truthy() && b.truthy()
                "||" -> a.truthy() || b.truthy()
                "==" -> compareEqual(a, b)
                "!=" -> !compareEqual(a, b)
                ">", ">=", "<", "<=" -> {
                    val x = a.text?.toBigDecimalOrNull()
                    val y = b.text?.toBigDecimalOrNull()
                    if (x == null || y == null) false else when (op) {
                        ">" -> x > y
                        ">=" -> x >= y
                        "<" -> x < y
                        else -> x <= y
                    }
                }
                else -> false
            }
            return Value(answer.toString(), Type.BOOLEAN)
        }
        private fun compareEqual(a: Value, b: Value): Boolean {
            if (a.type == Type.NUMBER || b.type == Type.NUMBER) {
                val x = a.text?.toBigDecimalOrNull()
                val y = b.text?.toBigDecimalOrNull()
                return x != null && y != null && x.compareTo(y) == 0
            }
            if (a.text == null || b.text == null) return a.text == b.text
            return a.text == b.text
        }
    }

    private data class Token(val kind: Kind, val text: String)
    private enum class Kind { IDENTIFIER, STRING, NUMBER, SYMBOL, END }
    private class Syntax(message: String) : IllegalArgumentException(message)

    @Synchronized private fun parse(source: String): Expr {
        if (source.isBlank()) return Literal(Value("true", Type.BOOLEAN))
        if (source.length > MAX_LENGTH) throw Syntax("条件表达式超过长度限制")
        return cache.getOrPut(source) { Parser(lex(source)).parse() }
    }

    private fun lex(source: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < source.length) {
            if (source[index].isWhitespace()) { index++; continue }
            val start = index
            val c = source[index]
            when {
                c == '\'' || c == '"' -> {
                    val quote = c
                    index++
                    val value = StringBuilder()
                    var closed = false
                    while (index < source.length) {
                        val next = source[index++]
                        if (next == quote) { closed = true; break }
                        if (next == '\\') {
                            if (index >= source.length) throw Syntax("字符串转义不完整")
                            value.append(when (val escaped = source[index++]) {
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                '\\', '\'', '"' -> escaped
                                else -> throw Syntax("不支持的字符串转义")
                            })
                        } else value.append(next)
                    }
                    if (!closed) throw Syntax("字符串未闭合")
                    tokens += Token(Kind.STRING, value.toString())
                }
                c.isLetter() || c == '_' -> {
                    index++
                    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '.' || source[index] == '-')) index++
                    tokens += Token(Kind.IDENTIFIER, source.substring(start, index))
                }
                c.isDigit() || (c == '-' && index + 1 < source.length && source[index + 1].isDigit()) -> {
                    index++
                    while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
                    val number = source.substring(start, index)
                    if (number.toBigDecimalOrNull() == null) throw Syntax("数字无效")
                    tokens += Token(Kind.NUMBER, number)
                }
                else -> {
                    val pair = source.substring(index, minOf(index + 2, source.length))
                    val symbol = if (pair in setOf("&&", "||", "==", "!=", ">=", "<=")) pair else c.toString()
                    if (symbol !in setOf("&&", "||", "==", "!=", ">=", "<=", ">", "<", "!", "(", ")", ",")) throw Syntax("不支持的符号：$c")
                    tokens += Token(Kind.SYMBOL, symbol)
                    index += symbol.length
                }
            }
            if (tokens.size > MAX_TOKENS) throw Syntax("条件表达式过于复杂")
        }
        tokens += Token(Kind.END, "")
        return tokens
    }

    private class Parser(private val tokens: List<Token>) {
        private var index = 0
        private var depth = 0
        private val current get() = tokens[index]
        private fun accept(symbol: String): Boolean {
            if (current.kind == Kind.SYMBOL && current.text == symbol) { index++; return true }
            return false
        }
        fun parse(): Expr {
            val result = or()
            if (current.kind != Kind.END) throw Syntax("条件表达式有多余内容")
            return result
        }
        private fun or(): Expr {
            var left = and()
            while (accept("||")) left = Binary("||", left, and())
            return left
        }
        private fun and(): Expr {
            var left = comparison()
            while (accept("&&")) left = Binary("&&", left, comparison())
            return left
        }
        private fun comparison(): Expr {
            var left = unary()
            if (current.text in setOf("==", "!=", ">", ">=", "<", "<=")) {
                val op = current.text
                index++
                left = Binary(op, left, unary())
            }
            return left
        }
        private fun unary(): Expr {
            if (accept("!")) return Not(nested { unary() })
            return primary()
        }
        private fun primary(): Expr {
            if (accept("(")) {
                val value = nested { or() }
                if (!accept(")")) throw Syntax("缺少右括号")
                return value
            }
            val token = current
            index++
            return when (token.kind) {
                Kind.STRING -> Literal(Value(token.text))
                Kind.NUMBER -> Literal(Value(token.text, Type.NUMBER))
                Kind.IDENTIFIER -> when (token.text) {
                    "true", "false" -> Literal(Value(token.text, Type.BOOLEAN))
                    "exists", "number", "text", "macro", "extract", "add", "subtract", "multiply", "divide", "round", "clamp", "concat", "replace", "if" -> function(token.text)
                    else -> {
                        if (token.text !in setOf("input", "value") && !Regex("(?:state|result)\\.[\\p{L}_][\\p{L}\\p{N}_-]*").matches(token.text))
                            throw Syntax("未知条件变量：${token.text}")
                        Variable(token.text)
                    }
                }
                else -> throw Syntax("条件表达式缺少值")
            }
        }
        private fun function(name: String): Expr {
            if (!accept("(")) throw Syntax("$name 需要括号")
            val value = nested {
                when (name) {
                    "macro" -> {
                        val address = stringArgument("macro 地址")
                        macroExpressionError(address)?.let { throw Syntax(it) }
                        Macro(address)
                    }
                    "extract" -> {
                        val operand = or()
                        if (!accept(",")) throw Syntax("extract 需要正则表达式")
                        val pattern = stringArgument("extract 正则表达式")
                        val group = if (accept(",")) {
                            val token = current
                            index++
                            if (token.kind != Kind.NUMBER) throw Syntax("捕获组须为非负整数")
                            token.text.toIntOrNull() ?: throw Syntax("捕获组须为非负整数")
                        } else 0
                        SafePatterns.validate(pattern, group)?.let { throw Syntax(it) }
                        Extract(operand, pattern, group)
                    }
                    "exists" -> Exists(or())
                    "number" -> Coerce(or(), Type.NUMBER)
                    "text" -> Coerce(or(), Type.STRING)
                    else -> {
                        val arguments = mutableListOf<Expr>()
                        if (current.text != ")") {
                            arguments += or()
                            while (accept(",")) arguments += or()
                        }
                        val size = arguments.size
                        val valid = when (name) {
                            "round" -> size in 1..2
                            "concat" -> size in 1..16
                            "replace", "clamp", "if" -> size == 3
                            else -> size == 2
                        }
                        if (!valid) throw Syntax("$name 参数数量无效")
                        Call(name, arguments)
                    }
                }
            }
            if (!accept(")")) throw Syntax("$name 缺少右括号或参数过多")
            return value
        }
        private fun stringArgument(label: String): String {
            if (current.kind != Kind.STRING) throw Syntax("$label 须为字符串字面量")
            return current.text.also { index++ }
        }
        private inline fun <T> nested(block: () -> T): T {
            if (++depth > MAX_DEPTH) throw Syntax("条件表达式嵌套过深")
            try { return block() } finally { depth-- }
        }
    }
}
