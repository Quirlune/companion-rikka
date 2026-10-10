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
}
