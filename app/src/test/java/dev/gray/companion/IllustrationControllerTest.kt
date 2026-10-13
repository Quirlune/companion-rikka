package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IllustrationControllerTest {
    private fun fixture(concurrency: Int = 1): Pair<Library, RunResult> {
        val profile = ModelProfile(id = "planner", model = "test-model")
        val workflow = Workflow(id = "primary")
        val main = MainCall(id = "main", workflows = listOf(workflow), primaryWorkflowId = workflow.id)
        val user = ChatMessage(id = "user", role = Role.USER, text = "Describe the room")
        val assistant = ChatMessage(id = "assistant", role = Role.ASSISTANT, text = "First paragraph.\n\nSecond paragraph.",
            workflowId = workflow.id)
        val session = Session(id = "session", mainCallId = main.id, messages = listOf(user, assistant))
        val library = Library(mainCalls = listOf(main), profiles = listOf(profile), sessions = listOf(session), selectedSessionId = session.id,
            illustration = IllustrationConfig(enabled = true, modelProfileId = profile.id,
                concurrency = concurrency, maxImages = 2))
        return library to RunResult(emptyMap(), emptyMap(), listOf(assistant), emptyList())
    }

    private val model = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) =
            """<illustrations><image after="p1"><prompt>scene one</prompt></image><image after="p2"><prompt>scene two</prompt></image></illustrations>"""
    }

    @Test(timeout = 15_000) fun oneSlotQueuesSecondRealImage() = runBlocking {
        val (initial, accepted) = fixture()
        val library = MutableStateFlow(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val submitted = AtomicInteger()
        val published = AtomicInteger()
        val sentPositive = java.util.concurrent.CopyOnWriteArrayList<String>()
        val service = object : IllustrationService {
            override suspend fun generate(payload: JsonObject, key: String,
                                          onPhase: (BizyAirPhase) -> Unit): BizyAirGeneratedImage {
                sentPositive += payload.getValue("input_values").jsonObject
                    .getValue(initial.illustration.positiveInput.name).jsonPrimitive.content
                val index = submitted.incrementAndGet()
                if (index == 1) first.await() else second.await()
                return BizyAirGeneratedImage(File(System.getProperty("java.io.tmpdir"),
                    "missing-${UUID.randomUUID()}.png").absolutePath, 1024, 1280)
            }
        }
        try {
            val controller = IllustrationController(scope, library, { _, _, _ -> model }, { "test-key" }, service) { _, _, _ ->
                published.incrementAndGet(); true
            }
            controller.bodyCommitted("session", accepted)
            withTimeout(5_000) { controller.state.first { it.running == 1 && it.queued == 1 } }
            withTimeout(5_000) { while (submitted.get() < 1) delay(10) }
            assertEquals(1, submitted.get())
            first.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.running == 1 && it.queued == 0 } }
            withTimeout(5_000) { while (submitted.get() < 2) delay(10) }
            assertEquals(2, submitted.get())
            second.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.running == 0 && published.get() == 2 } }
            assertEquals(2, published.get())
            assertEquals(listOf("scene one", "scene two").map { initial.illustration.positiveInput.value + ", " + it }, sentPositive.toList())
            assertTrue(sentPositive.all { it.startsWith("masterpiece, best quality, score_9") })
        } finally { scope.cancel() }
    }

    @Test(timeout = 15_000) fun staleSourceKeepsSubmittedSlotButNeverPublishes() = runBlocking {
        val (initial, accepted) = fixture()
        val library = MutableStateFlow(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val submitted = AtomicInteger()
        val published = AtomicInteger()
        val service = object : IllustrationService {
            override suspend fun generate(payload: JsonObject, key: String,
                                          onPhase: (BizyAirPhase) -> Unit): BizyAirGeneratedImage {
                submitted.incrementAndGet()
                release.await()
                return BizyAirGeneratedImage(File(System.getProperty("java.io.tmpdir"),
                    "missing-${UUID.randomUUID()}.png").absolutePath, 1024, 1280)
            }
        }
        try {
            val controller = IllustrationController(scope, library, { _, _, _ -> model }, { "test-key" }, service) { _, _, _ ->
                published.incrementAndGet(); true
            }
            controller.bodyCommitted("session", accepted)
            withTimeout(5_000) { controller.state.first { it.running == 1 && it.queued == 1 } }
            withTimeout(5_000) { while (submitted.get() < 1) delay(10) }
            val changed = initial.sessions.single().copy(messages = initial.sessions.single().messages.dropLast(1) +
                initial.sessions.single().messages.last().copy(text = "Edited text"))
            library.value = initial.copy(sessions = listOf(changed))
            withTimeout(5_000) { controller.state.first { it.running == 1 && it.queued == 0 } }
            assertEquals(1, submitted.get())
            release.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.running == 0 } }
            assertEquals(0, published.get())
        } finally { scope.cancel() }
    }

    @Test(timeout = 15_000) fun uncertainSubmitPausesQueueUntilExplicitAcknowledgement() = runBlocking {
        val (initial, accepted) = fixture()
        val library = MutableStateFlow(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val submitted = AtomicInteger()
        val service = object : IllustrationService {
            override suspend fun generate(payload: JsonObject, key: String,
                                          onPhase: (BizyAirPhase) -> Unit): BizyAirGeneratedImage {
                if (submitted.incrementAndGet() == 1)
                    throw BizyAirFailure("CREATE_UNCERTAIN", "远端任务可能已创建", uncertainDelivery = true)
                return BizyAirGeneratedImage(File(System.getProperty("java.io.tmpdir"),
                    "missing-${UUID.randomUUID()}.png").absolutePath, 1024, 1280)
            }
        }
        try {
            val controller = IllustrationController(scope, library, { _, _, _ -> model }, { "test-key" }, service) { _, _, _ -> true }
            controller.bodyCommitted("session", accepted)
            withTimeout(5_000) { controller.state.first { it.uncertainDelivery && it.running == 0 && it.queued == 1 } }
            assertEquals(1, submitted.get())
            controller.acknowledgeRemoteStatus()
            withTimeout(5_000) { while (submitted.get() < 2) delay(10) }
            assertFalse(controller.state.value.uncertainDelivery)
        } finally { scope.cancel() }
    }

    @Test(timeout = 15_000) fun manualRegenerateOlderBodyReplacesOnceAndRejectsDuplicateWhileBusy() = runBlocking {
        val (base, _) = fixture()
        val old = base.sessions.single()
        val later = old.copy(messages = old.messages + listOf(
            ChatMessage(id = "user2", role = Role.USER, text = "Later question"),
            ChatMessage(id = "assistant2", role = Role.ASSISTANT, workflowId = "primary",
                turnId = "user2", text = "Later answer")))
        val library = MutableStateFlow(base.copy(sessions = listOf(later)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val replaces = mutableListOf<Boolean>()
        val submitted = AtomicInteger()
        val service = object : IllustrationService {
            override suspend fun generate(payload: JsonObject, key: String,
                                          onPhase: (BizyAirPhase) -> Unit): BizyAirGeneratedImage {
                if (submitted.incrementAndGet() == 1) release.await()
                return BizyAirGeneratedImage(File(System.getProperty("java.io.tmpdir"),
                    "missing-${UUID.randomUUID()}.png").absolutePath, 1024, 1280)
            }
        }
        try {
            val controller = IllustrationController(scope, library, { _, _, _ -> model }, { "test-key" }, service) { _, _, replace ->
                synchronized(replaces) { replaces += replace }
                true
            }
            assertFalse(controller.regenerate("session", "user"))
            assertTrue(controller.regenerate("session", "assistant"))
            assertFalse(controller.regenerate("session", "assistant"))
            withTimeout(5_000) { controller.state.first { it.running == 1 && it.queued == 1 } }
            assertTrue(synchronized(replaces) { replaces.isEmpty() })
            release.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.running == 0 && synchronized(replaces) { replaces.size == 2 } } }
            assertEquals(listOf(true, false), synchronized(replaces) { replaces.toList() })
            assertTrue(controller.regenerate("session", "assistant"))
        } finally { scope.cancel() }
    }
}
