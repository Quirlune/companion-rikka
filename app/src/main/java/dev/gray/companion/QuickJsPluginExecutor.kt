package dev.gray.companion

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.quickJs
import dev.gray.companion.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** One fresh isolated runtime per pure text invocation. No native/console/file/network bindings. */
class QuickJsPluginExecutor(private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS) : PluginCodeExecutor {
    init { require(timeoutMillis in 1..DEFAULT_TIMEOUT_MS) }

    override suspend fun execute(context: PluginExecutionContext): String = withContext(Dispatchers.Default) {
        PluginCodes.requireContext(context)
        val payload = LibraryFormat.json.encodeToString(PluginExecutionContext.serializer(), context)
        try {
            val text = withTimeoutOrNull(timeoutMillis) {
                quickJs(Dispatchers.Default) {
                    memoryLimit = MEMORY_LIMIT_BYTES
                    maxStackSize = MAX_STACK_BYTES
                    evaluationTimeoutMillis = timeoutMillis
                    evaluate<String>(
                        """
                        (() => {
                            const parse = JSON.parse;
                            const freeze = Object.freeze;
                            const defineProperty = Object.defineProperty;
                            const keys = Object.keys;
                            const context = parse(${JsonPrimitive(payload)});
                            delete context.code;
                            const deepFreeze = value => {
                                if (value && typeof value === 'object') {
                                    for (const key of keys(value)) deepFreeze(value[key]);
                                    freeze(value);
                                }
                                return value;
                            };
                            deepFreeze(context);
                            defineProperty(globalThis, 'Date', {value: undefined, writable: false, configurable: false});
                            defineProperty(Math, 'random', {value: () => { throw new Error('Random is unavailable'); }, writable: false, configurable: false});
                            freeze(Math);
                            (0, eval)(${JsonPrimitive(context.code.source)});
                            if (typeof globalThis.execute !== 'function') throw new Error('execute(context) is required');
                            const result = globalThis.execute(context);
                            const text = typeof result === 'string' ? result :
                                (result !== null && typeof result === 'object' ? result.text : undefined);
                            if (typeof text !== 'string') throw new Error('Plugin must return text');
                            if (text.length > ${PluginCodes.MAX_OUTPUT_BYTES}) throw new Error('Plugin output exceeds limit');
                            return text;
                        })()
                        """.trimIndent()
                    )
                }
            } ?: throw IllegalArgumentException("插件代码执行超时")
            PluginCodes.requireOutput(text)
        } catch (_: QuickJsInterruptedException) {
            throw IllegalArgumentException("插件代码执行超时")
        } catch (_: QuickJsException) {
            throw IllegalArgumentException("插件代码执行失败；请检查 execute(context) 和纯文本返回值")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_000L
        private const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024
        private const val MAX_STACK_BYTES = 256L * 1024
    }
}
