package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowEngineTest {
    private fun session() = Session(
        id = "session", characterId = "character", mainCallId = "main",
        messages = listOf(ChatMessage(role = Role.USER, text = "hello"))
    )
    private fun library(nodes: List<WorkflowNode>, session: Session = session(), groups: List<CallGroup> = emptyList()) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "mainFlow", nodes = nodes)))),
        groups = groups, sessions = listOf(session)
    )
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
            error("unexpected gateway call")
        }
    }

    @Test fun macrosLoadActivateSelectAndHide() {
        val card = CharacterCard(id = "character", namespace = "hero", name = "Hero", entries = listOf(
            PromptEntry(key = "identity", text = "<bio>present</bio>", category = "lore"),
            PromptEntry(key = "sleeping", text = "asleep", category = "lore", condition = Condition("awake", Compare.EQUALS, "no"))
        ))
        val pack = PromptPackage(id = "pack", namespace = "style", entries = listOf(PromptEntry(key = "tone", text = "gentle", category = "lore")))
        val s = session().copy(packageIds = listOf("pack"), state = mapOf("awake" to "yes"))
        val node = WorkflowNode(includeHistory = false, blocks = listOf(
            PromptBlock(template = "{{character.identity#bio}}|{{pack.style.tone}}|{{category.lore}}"),
            PromptBlock(template = "fixed {{character.missing}}", hideWhenReferencesEmpty = true),
            PromptBlock(template = "fixed", hideWhenReferencesEmpty = true)
        ))
        val preview = PromptCompiler().preview(Library(characters = listOf(card), packages = listOf(pack)), s, node, "hello")
        assertTrue(preview.messages.first().text.contains("present|gentle|<bio>present</bio>\ngentle"))
        assertEquals("fixed", preview.messages[1].text)
        assertFalse(preview.messages.any { it.text.contains("asleep") })
    }

    @Test fun dependencyUsesCurrentRunResultAndDoesNotRepeatHistoryInput() = runBlocking {
        val first = WorkflowNode(id = "first", actionId = "text.render", inputTemplate = "fresh", outputKey = "draft", includeHistory = false)
        val second = WorkflowNode(id = "second", actionId = "text.render", dependsOn = listOf("first"), inputTemplate = "{{result.draft}}", outputKey = "reply", visible = true)
        val s = session().copy(results = mapOf("draft" to "stale"))
        val result = WorkflowEngine().run(library(listOf(first, second), s), s, "hello", noModel)
        assertEquals("fresh", result.replies.single().text)
        assertEquals("fresh", result.results["draft"])
        val preview = PromptCompiler().preview(library(listOf(second), s), s, second.copy(inputTemplate = "{{input}}"), "hello")
        assertEquals(1, preview.messages.count { it.role == Role.USER && it.text == "hello" })
    }

    @Test fun eventRoutingAndStateAreSessionScoped() = runBlocking {
        val route = WorkflowNode(id = "route", actionId = "event.emit", arguments = mapOf("event" to "custom.answer"), outputKey = "route", includeHistory = false)
        val routed = Workflow(id = "routed", trigger = "custom.answer", nodes = listOf(
            WorkflowNode(id = "state", actionId = "state.set", arguments = mapOf("key" to "mood", "value" to "warm"), outputKey = "moodResult", includeHistory = false),
            WorkflowNode(id = "answer", actionId = "text.render", inputTemplate = "done", outputKey = "answer", visible = true, includeHistory = false)
        ))
        val s = session()
        val base = library(listOf(route), s).copy(mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "mainFlow", nodes = listOf(route)), routed))))
        val result = WorkflowEngine().run(base, s, "hello", noModel)
        assertEquals("done", result.replies.single().text)
        assertEquals("warm", result.state["mood"])
        assertFalse(s.state.containsKey("mood"))
    }

    @Test fun cyclesAndWriterConflictsStopBeforeGateway() = runBlocking {
        val model = ModelProfile(id = "profile", model = "sample")
        var calls = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                calls++
                return "called"
            }
        }
        val cycle = listOf(
            WorkflowNode(id = "a", dependsOn = listOf("b"), outputKey = "a"),
            WorkflowNode(id = "b", dependsOn = listOf("a"), outputKey = "b")
        )
        val conflict = listOf(
            WorkflowNode(id = "a", outputKey = "same"),
            WorkflowNode(id = "b", outputKey = "same")
        )
        for (nodes in listOf(cycle, conflict)) {
            val s = session()
            val lib = library(nodes, s).copy(profiles = listOf(model), selectedProfileId = model.id)
            val result = WorkflowEngine().run(lib, s, "hello", gateway)
            assertTrue(result.replies.isEmpty())
            assertTrue(result.traces.any { it.status == "error" })
        }
        assertEquals(0, calls)
    }

    @Test fun groupDefaultsFillOnlyMissingStateAndUnloadedResultsAreExcluded() = runBlocking {
        val node = WorkflowNode(id = "render", actionId = "text.render", inputTemplate = "{{state.mood}}", outputKey = "answer", visible = true, includeHistory = false)
        val loaded = CallGroup(id = "loaded", name = "Loaded", stateDefaults = mapOf("mood" to "default"))
        val unloaded = CallGroup(id = "unloaded", name = "Unloaded", workflows = listOf(Workflow(id = "unloadedFlow", nodes = listOf(
            WorkflowNode(id = "old", actionId = "text.render", outputKey = "oldResult")
        ))))
        val s = session().copy(groupIds = listOf("loaded"), state = mapOf("mood" to "existing"), results = mapOf("oldResult" to "stale"))
        val result = WorkflowEngine().run(library(listOf(node), s, listOf(loaded, unloaded)), s, "hello", noModel)
        assertEquals("existing", result.replies.single().text)
        assertEquals("existing", result.state["mood"])
        assertFalse(result.results.containsKey("oldResult"))
    }

    @Test fun malformedXmlAndMissingPrimaryRejectSafely() = runBlocking {
        val card = CharacterCard(id = "character", namespace = "hero", name = "Hero", entries = listOf(PromptEntry(key = "identity", text = "<!DOCTYPE x><x/>")))
        val node = WorkflowNode(id = "render", actionId = "text.render", blocks = listOf(PromptBlock(template = "{{character.identity#x}}")), includeHistory = false)
        val preview = PromptCompiler().preview(Library(characters = listOf(card)), session(), node, "hello")
        assertTrue(preview.issues.any { it.error && it.message.contains("XML") })
        val s = session().copy(characterId = "missing")
        val result = WorkflowEngine().run(library(listOf(node), s), s, "hello", noModel)
        assertTrue(result.traces.any { it.status == "error" && it.detail.contains("角色卡") })
    }

    @Test fun optionalGroupPreparesStateBeforeUnchangedMainEntry() = runBlocking {
        val mainNode = WorkflowNode(id = "replyNode", blocks = listOf(PromptBlock(template = "mood={{state.mood}}")), visible = true)
        val prepNode = WorkflowNode(id = "prepNode", actionId = "state.set", outputKey = "prepared", includeHistory = false,
            arguments = mapOf("key" to "mood", "value" to "ready"))
        val group = CallGroup(id = "group", name = "Optional", workflows = listOf(Workflow(id = "prepFlow", trigger = "turn.prepare", nodes = listOf(prepNode))))
        val profile = ModelProfile(id = "profile", model = "demo")
        val seen = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                seen += messages
                return "reply"
            }
        }
        val withGroup = session().copy(groupIds = listOf("group"))
        val base = library(listOf(mainNode), withGroup, listOf(group)).copy(profiles = listOf(profile), selectedProfileId = profile.id)
        val withResult = WorkflowEngine().run(base, withGroup, "hello", gateway)
        assertEquals("ready", withResult.state["mood"])
        assertTrue(seen.last().any { it.text == "mood=ready" })
        val withoutGroup = session()
        val withoutResult = WorkflowEngine().run(base.copy(sessions = listOf(withoutGroup)), withoutGroup, "hello", gateway)
        assertEquals("reply", withoutResult.replies.single().text)
        assertTrue(seen.last().any { it.text == "mood=" })
    }
}
