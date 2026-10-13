package dev.gray.companion

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.quickJs
import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One fresh isolated runtime per pure text invocation. No native/console/file/network bindings. */
class QuickJsPluginExecutor(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val hostRuntimeFactory: ((PluginExecutionContext) -> PluginHostRuntime)? = null
) : PluginCodeExecutor {
    init { require(timeoutMillis in 1..DEFAULT_TIMEOUT_MS) }

    override suspend fun execute(context: PluginExecutionContext): String =
        if (context.code.version == 2) executeV2(context) else executeV1(context)

    private suspend fun executeV1(context: PluginExecutionContext): String = withContext(Dispatchers.Default) {
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

    private suspend fun executeV2(context: PluginExecutionContext): String = withContext(Dispatchers.Default) {
        PluginCodes.requireContext(context)
        val hostRuntime = requireNotNull(hostRuntimeFactory?.invoke(context)) { "插件宿主尚未配置" }
        val payload = LibraryFormat.json.encodeToString(PluginExecutionContext.serializer(), context)
        val pendingCalls = AtomicInteger(0)
        val lastJsNanos = AtomicLong(System.nanoTime())
        try {
            val text = withTimeoutOrNull(PluginCapabilities.MAX_TOTAL_RUNTIME_MS) {
                quickJs(Dispatchers.Default) {
                    memoryLimit = MEMORY_LIMIT_BYTES
                    maxStackSize = MAX_STACK_BYTES
                    evaluationTimeoutMillis = PluginCapabilities.MAX_TOTAL_RUNTIME_MS
                    asyncFunction<String, String>("__hostInvoke") { raw ->
                        pendingCalls.incrementAndGet()
                        try { hostRuntime.invokeJson(raw) }
                        finally {
                            pendingCalls.decrementAndGet()
                            lastJsNanos.set(System.nanoTime())
                        }
                    }
                    coroutineScope {
                        lastJsNanos.set(System.nanoTime())
                        val watchdog = launch(Dispatchers.Default) {
                            while (true) {
                                delay(100)
                                if (pendingCalls.get() == 0 &&
                                    System.nanoTime() - lastJsNanos.get() > 2_000_000_000L) {
                                    interruptEvaluation()
                                    break
                                }
                            }
                        }
                        try {
                            evaluate<String>(
                                """
                                await (async () => {
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
                                    const host = freeze({invoke: async (capability, args) =>
                                        parse(await __hostInvoke(JSON.stringify({capability, args: args || {}})))});
                                    (0, eval)(${JsonPrimitive(context.code.source)});
                                    if (typeof globalThis.execute !== 'function') throw new Error('execute(context, host) is required');
                                    const result = await globalThis.execute(context, host);
                                    const text = typeof result === 'string' ? result :
                                        (result !== null && typeof result === 'object' ? result.text : undefined);
                                    if (typeof text !== 'string') throw new Error('Plugin must return text');
                                    if (text.length > ${PluginCodes.MAX_OUTPUT_BYTES}) throw new Error('Plugin output exceeds limit');
                                    return text;
                                })()
                                """.trimIndent()
                            )
                        } finally { watchdog.cancel() }
                    }
                }
            } ?: throw IllegalStateException("插件运行超时；已提交的远端任务可能仍在执行")
            PluginCodes.requireOutput(text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: QuickJsInterruptedException) {
            throw IllegalArgumentException("插件代码执行超时")
        } catch (_: QuickJsException) {
            throw IllegalArgumentException("插件代码执行失败；请检查 execute(context, host) 和纯文本返回值")
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_000L
        private const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024
        private const val MAX_STACK_BYTES = 256L * 1024
    }
}
