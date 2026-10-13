package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuickJsPluginExecutorTest {
    private fun context(source: String) = PluginExecutionContext("plugin", PluginCode(source = source), "hello",
        state = mapOf("place" to "home"), results = mapOf("expensive" to "retained"),
        messages = listOf(PluginMessage(Role.USER, "original body")))

    @Test(timeout = 15_000) fun executesCapturedTextDeterministicallyAndHasNoHostOrClock() = runBlocking {
        val executor = QuickJsPluginExecutor()
        val input = context("""function execute(context) {
            if (typeof Date !== 'undefined' || typeof fetch !== 'undefined' || typeof std !== 'undefined' || typeof os !== 'undefined') throw new Error('Unexpected host');
            return {text: context.input + '|' + context.state.place + '|' + context.results.expensive + '|' + context.messages[0].text};
        }""")
        val expected = "hello|home|retained|original body"
        assertEquals(expected, executor.execute(input))
        assertEquals(expected, executor.execute(input))
        try {
            executor.execute(context("function execute(context) { return Math.random().toString(); }"))
            fail("Random must not be available")
        } catch (_: IllegalArgumentException) { }
    }

    @Test(timeout = 15_000) fun interruptsCodeAndOutputGettersAndRejectsOversizedOutput() = runBlocking {
        val executor = QuickJsPluginExecutor(100)
        for (source in listOf("function execute(context) { while (true) {} }",
            "function execute(context) { return {get text() { while (true) {} }}; }",
            "function execute(context) { return new ArrayBuffer(32 * 1024 * 1024); }",
            "function execute(context) { function recurse() { return recurse(); } return recurse(); }",
            "function execute(context) { return '中'.repeat(65536); }")) {
            try { executor.execute(context(source)); fail("Execution must fail within its bounds") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("hello", executor.execute(context("function execute(context) { return context.input; }")))
    }

    @Test(timeout = 15_000) fun awaitsVersionTwoHostBridge() = runBlocking {
        val captured = context("""async function execute(context, host) {
            const slept = await host.invoke('runtime.sleep', {milliseconds: 5});
            const described = await host.invoke('capabilities.describe', {});
            return slept.ok && described.ok ? context.input + '|' + described.data.apiVersion : 'failed';
        }""").copy(code = PluginCode(version = 2, source = """async function execute(context, host) {
            const slept = await host.invoke('runtime.sleep', {milliseconds: 5});
            const described = await host.invoke('capabilities.describe', {});
            return slept.ok && described.ok ? context.input + '|' + described.data.apiVersion : 'failed';
        }"""))
        val access = object : PluginHostAccess {
            override fun isGranted(context: PluginExecutionContext) = false
            override fun credential(pluginId: String, alias: String): String = error("unexpected secret read")
        }
        val executor = QuickJsPluginExecutor(hostRuntimeFactory = { PluginHostRuntime(it, access) })
        assertEquals("hello|2", executor.execute(captured))
    }
}
