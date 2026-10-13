package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomaticOptionsControllerTest {
    @Test(timeout = 15_000) fun acceptedBodySchedulesAgainWhenItsRequestContextChangesAfterLastBodyCallback() = runBlocking {
        val assistant = ChatMessage(id = "assistant", role = Role.ASSISTANT, text = "accepted BODY",
            turnId = "user", workflowId = "primary")
        val session = Session(id = "session", mainCallId = "main", state = mapOf("theme" to "calm"),
            messages = listOf(ChatMessage(id = "user", role = Role.USER, text = "question"), assistant))
        val config = AutomaticOptionsConfig(enabled = true, count = 1,
            blocks = listOf(PromptBlock(id = "request", role = Role.USER, template = "Offer one reply")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_REPLACE,
                pattern = "TOKEN", replacement = "{{state.theme}}")))
        val main = MainCall(id = "main", primaryWorkflowId = "primary",
            workflows = listOf(Workflow(id = "primary")))
        val initial = Library(mainCalls = listOf(main), profiles = listOf(ModelProfile(id = "model", model = "offline")),
            selectedProfileId = "model", sessions = listOf(session), selectedSessionId = session.id,
            automaticOptions = config)
        val library = MutableStateFlow(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                return "<option>TOKEN</option>"
            }
        }
        try {
            val controller = AutomaticOptionsController(scope, library) { _, _, _ -> gateway }
            val accepted = RunResult(emptyMap(), emptyMap(), listOf(assistant), emptyList())
            controller.bodyCommitted(session.id, accepted)
            withTimeout(5_000) { firstStarted.await() }
            // A peer accepts a state write after the BODY. The source key is unchanged,
            // but the exact option request and output processing are now different.
            library.value = initial.copy(sessions = listOf(session.copy(state = mapOf("theme" to "bold"))))
            // No further BODY callback is guaranteed after a later library publication.
            val ready = withTimeout(5_000) { controller.state.first { it.options.isNotEmpty() } }
            assertEquals(2, calls.get())
            assertEquals("bold", ready.options.single().content)
        } finally { scope.cancel() }
    }
}
