package dev.gray.companion.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreRefactorTest {
    private val session = Session(
        id = "session", characterId = "character", mainCallId = "main",
        messages = listOf(ChatMessage(id = "user", role = Role.USER, text = "hello"))
    )
    private fun library(workflows: List<Workflow>, session: Session = this.session, profile: ModelProfile? = null) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = workflows)),
        profiles = listOfNotNull(profile), selectedProfileId = profile?.id, sessions = listOf(session)
    )
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("unexpected model call")
    }

    @Test fun descriptorsExposeIncrementAndIncrementClampsState() = runBlocking {
        val descriptor = ActionRegistry.defaults().descriptors().single { it.id == "state.increment" }
        assertEquals(ActionFieldType.NUMBER, descriptor.fields.single { it.key == "amount" }.type)
        assertFalse(descriptor.usesModel)
        val changed = session.copy(state = mapOf("score" to "4"))
        val node = WorkflowNode(
            id = "increment", actionId = "state.increment", outputKey = "scoreOutput",
            arguments = mapOf("key" to "score", "amount" to "2.5", "max" to "5"), includeHistory = false
        )
        val result = WorkflowEngine().run(library(listOf(Workflow(id = "mainFlow", nodes = listOf(node))), changed), changed, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals("5", result.state["score"])
        assertEquals("5", result.results["scoreOutput"])
    }

    @Test fun planningRejectsInvalidLaterActionBeforeModelCall() = runBlocking {
        var calls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                calls++
                return "paid"
            }
        }
        val profile = ModelProfile(id = "profile", model = "demo")
        val nodes = listOf(
            WorkflowNode(id = "first", outputKey = "first"),
            WorkflowNode(id = "second", actionId = "missing.plugin", dependsOn = listOf("first"), outputKey = "second")
        )
        val result = WorkflowEngine().run(library(listOf(Workflow(id = "mainFlow", nodes = nodes)), profile = profile), session, "hello", gateway)
        assertEquals(RunStatus.FAILED, result.status)
        assertEquals(0, calls)
        assertTrue(result.traces.any { it.status == "error" && it.detail.contains("未注册") })
    }

    @Test fun dependenciesExposeExactCurrentOutcomeAndSkipStaleFallback() = runBlocking {
        var observed: String? = null
        val registry = ActionRegistry.defaults().register(ActionDescriptor("inspect", "Inspect")) { context ->
            observed = context.dependencyOutputs["first"]?.text
            ActionOutcome(observed.orEmpty())
        }
        val first = WorkflowNode(id = "first", actionId = "text.render", inputTemplate = "fresh", outputKey = "draft", includeHistory = false)
        val second = WorkflowNode(id = "second", actionId = "inspect", dependsOn = listOf("first"), outputKey = "answer", visible = true)
        val stale = session.copy(results = mapOf("draft" to "stale"))
        val success = WorkflowEngine(registry).run(library(listOf(Workflow(id = "flow", nodes = listOf(first, second))), stale), stale, "hello", noModel)
        assertEquals("fresh", observed)
        assertEquals("fresh", success.replies.single().text)
        observed = null
        val skipped = WorkflowEngine(registry).run(library(listOf(Workflow(id = "flow", nodes = listOf(first.copy(enabled = false), second))), stale), stale, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, skipped.status)
        assertTrue(skipped.replies.isEmpty())
        assertTrue(observed == null)
    }

    @Test fun postprocessingSeesPreparationAndInteractionRepliesOnce() = runBlocking {
        val seen = mutableListOf<List<ChatMessage>>()
        val registry = ActionRegistry.defaults().register(ActionDescriptor("inspect", "Inspect")) { context ->
            seen += context.messages
            ActionOutcome()
        }
        val prepare = Workflow(id = "prepare", trigger = "turn.prepare", nodes = listOf(
            WorkflowNode(id = "prep", actionId = "text.render", inputTemplate = "prepared", outputKey = "prepOutput", visible = true, includeHistory = false)
        ))
        val interaction = Workflow(id = "interaction", trigger = "user.message", nodes = listOf(
            WorkflowNode(id = "reply", actionId = "text.render", inputTemplate = "answer", outputKey = "replyOutput", visible = true, includeHistory = false)
        ))
        val post = Workflow(id = "post", trigger = "reply.completed", nodes = listOf(
            WorkflowNode(id = "observe", actionId = "inspect", outputKey = "observed")
        ))
        val result = WorkflowEngine(registry).run(library(listOf(prepare, interaction, post)), session, "hello", noModel)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(1, seen.size)
        assertEquals(listOf("hello", "prepared", "answer"), seen.single().map { it.text })
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.ASSISTANT), seen.single().map { it.role })

        for (eventArgument in listOf("reply.completed", "{{state.target}}")) {
            val invalidPrepare = prepare.copy(nodes = listOf(
                WorkflowNode(id = "emit", actionId = "event.emit", outputKey = "event",
                    arguments = mapOf("event" to eventArgument), includeHistory = false)
            ))
            val targeted = session.copy(state = mapOf("target" to "reply.completed"))
            val rejected = WorkflowEngine(registry).run(library(listOf(invalidPrepare, interaction, post), targeted), targeted, "hello", noModel)
            assertFalse(rejected.results.containsKey("event"))
            if (eventArgument == "reply.completed") {
                assertEquals(RunStatus.FAILED, rejected.status)
                assertTrue(rejected.replies.isEmpty())
                assertEquals(1, seen.size)
            } else {
                assertEquals(RunStatus.PAUSED, rejected.status)
                assertEquals(listOf("prepare/emit"), rejected.recovery!!.failures.map { it.nodeKey })
                assertEquals(listOf("answer"), rejected.replies.map { it.text })
                assertEquals(2, seen.size)
                assertEquals(listOf("hello", "answer"), seen.last().map { it.text })
            }
        }
    }

    @Test fun modelNodesShareConcurrencyBoundAndCancellationPropagates() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        var started = CompletableDeferred<Unit>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                started.complete(Unit)
                try {
                    delay(30)
                    return "ok"
                } finally {
                    active.decrementAndGet()
                }
            }
        }
        val profile = ModelProfile(id = "profile", model = "demo")
        val nodes = (1..5).map { WorkflowNode(id = "n$it", outputKey = "r$it", includeHistory = false) }
        val base = library(listOf(Workflow(id = "flow", nodes = nodes)), profile = profile)
        val result = WorkflowEngine(maxConcurrentNodes = 2).run(base, session, "hello", gateway)
        assertEquals(RunStatus.SUCCESS, result.status)
        assertEquals(2, peak.get())

        started = CompletableDeferred()
        val cancellable = async { WorkflowEngine(maxConcurrentNodes = 2).run(base, session, "hello", gateway) }
        started.await()
        cancellable.cancel()
        try {
            cancellable.await()
            throw AssertionError("cancelled run returned normally")
        } catch (_: CancellationException) {
            assertEquals(emptyMap<String, String>(), session.results)
        }
    }
}
