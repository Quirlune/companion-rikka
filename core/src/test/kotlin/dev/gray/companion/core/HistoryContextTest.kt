package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryContextTest {
    private val older = ChatMessage(id = "older", role = Role.USER, text = "hello", speakerName = "Ada {{floor}}")
    private val reply = ChatMessage(id = "reply", role = Role.ASSISTANT, text = "welcome", speakerName = "Old Hero")
    private val current = ChatMessage(id = "current", role = Role.USER, text = "draft", speakerName = "Ada")

    @Test fun structuredProjectionKeepsRolesAndLiteralSpeakerNames() {
        val history = HistoryConfig(
            prefix = "[", suffix = "]", floorPrefix = "{{ floor }} {{speaker.name}}: ",
            speakers = listOf(HistorySpeakerRule(Role.ASSISTANT, apiRole = Role.SYSTEM)),
            floors = listOf(HistoryFloorRule(reply.id, prefix = "from {{speaker.name}}: ")),
            insertions = listOf(HistoryInsertion(template = "separator", at = HistoryPoint(HistoryAnchor.START, 1.0)))
        )
        val main = MainCall(id = "main", name = "Main", history = history)
        val session = Session(mainCallId = main.id, messages = listOf(older, reply, current))
        val library = Library(mainCalls = listOf(main))
        val block = PromptBlock(template = "{{ history }}")
        val node = WorkflowNode(inputTemplate = "{{input}}", includeHistory = true, blocks = listOf(block))
        val preview = PromptCompiler().preview(library, session, node, "sent", currentUserMessageId = current.id)
        assertTrue(preview.issues.none { it.error })
        assertEquals(listOf(Role.USER, Role.SYSTEM, Role.SYSTEM, Role.USER), preview.messages.map { it.role })
        assertEquals(listOf("[1 Ada {{floor}}: hello", "separator", "from Old Hero: welcome", "3 Ada: sent]"),
            preview.messages.map { it.text })
        assertEquals(1, preview.messages.count { it.text.contains("sent") })
        assertTrue(LibraryValidator.validate(library.copy(mainCalls = listOf(main.copy(
            history = history.copy(mergeMessages = true))))).any { it.path.endsWith("mergeMessages") && it.error })
    }

    @Test fun rangeIntersectsArchiveAndInteriorInsertionExposesOriginalFloors() {
        val messages = listOf(older, reply, current)
        val digest = HistoryDigest("summary", reply.id, 2, "processor")
        val base = HistoryConfig(range = HistoryRange(HistoryPoint(HistoryAnchor.START, 1.0), HistoryPoint(HistoryAnchor.END, 1.0)))
        val main = MainCall(id = "main", name = "Main", history = base.copy(
            processor = HistoryProcessor(workflow = defaultHistoryProcessorWorkflow().copy(id = "processor"))))
        val session = Session(mainCallId = main.id, messages = messages, historyDigests = mapOf(main.id to digest))
        val library = Library(mainCalls = listOf(main))
        assertEquals(listOf("summary", "draft"), HistoryContext(library, session, main).projected().map { it.text })
        val inserted = main.copy(history = main.history.copy(insertions = listOf(
            HistoryInsertion(template = "between", at = HistoryPoint(HistoryAnchor.START, 1.0)))))
        assertEquals(listOf("hello", "between", "welcome", "draft"),
            HistoryContext(library, session, inserted).projected().map { it.text })
        val beyond = main.copy(history = main.history.copy(range = HistoryRange(
            HistoryPoint(HistoryAnchor.START, 20.0), HistoryPoint(HistoryAnchor.START, 21.0))))
        assertTrue(HistoryContext(library, session, beyond).projected().isEmpty())
    }

    @Test fun processorUsesCapturedPrefixAndStagesOnlySelectedExecutedOutput() = runBlocking {
        val historyNode = defaultHistoryProcessorWorkflow().nodes.single().copy(id = "summarize", inputTemplate = "",
            includeHistory = true, outputKey = "digest", visible = true)
        val processor = Workflow(id = "processor", trigger = "history.process", nodes = listOf(historyNode))
        val replyNode = WorkflowNode(id = "answer", actionId = "text.render", inputTemplate = "okay",
            includeHistory = false, outputKey = "answer", visible = true)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", nodes = listOf(replyNode))),
            history = HistoryConfig(processor = HistoryProcessor(processor, minimumNewFloors = 1, keepRecentFloors = 0, outputKey = "digest")))
        val session = Session(characterId = "hero", mainCallId = main.id, messages = listOf(older, reply, current))
        val profile = ModelProfile(id = "model", model = "local-stub")
        val library = Library(characters = listOf(CharacterCard(id = "hero", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), profiles = listOf(profile), selectedProfileId = profile.id)
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                assertEquals(Role.SYSTEM, messages.first().role)
                assertTrue(messages.first().text.contains("摘要"))
                assertEquals(listOf("hello", "welcome"), messages.drop(1).map { it.text })
                onDelta("compressed")
                return "compressed"
            }
        }
        val run = WorkflowEngine().run(library, session, "sent", gateway)
        assertEquals(RunStatus.SUCCESS, run.status)
        assertEquals(listOf("okay"), run.replies.map { it.text })
        assertEquals("compressed", run.historyDigests[main.id]?.text)
        assertEquals(reply.id, run.historyDigests[main.id]?.throughMessageId)
        assertEquals(2, run.historyDigests[main.id]?.coveredFloors)
        assertFalse(session.historyDigests.containsKey(main.id))
    }
}
