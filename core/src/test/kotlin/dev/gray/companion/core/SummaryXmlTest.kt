package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SummaryXmlTest {
    @Test fun extractsOnlyOneTextRootAndRejectsInvalidOutputs() {
        assertEquals("A & B < C", SummaryXml.extract("说明\n<summary>A &amp; B <![CDATA[< C]]></summary>\n结束", "summary"))
        assertEquals("changed", SummaryXml.extract("<memory>changed</memory>", "memory"))
        assertEquals("内容", SummaryXml.extract("<摘要>内容</摘要>", "摘要"))
        listOf("plain", "<summary/>", "<summary> </summary>", "<summary>x",
            "<summary><item>x</item></summary>", "<summary>x</summary><summary>y</summary>",
            "<summary key='a'>x</summary>", "<!DOCTYPE summary [<!ENTITY x 'bad'>]><summary>&x;</summary>",
            "<summary>&unknown;</summary>", "<summary>x</other>").forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { SummaryXml.extract(input, "summary") }
        }
        assertFalse(SummaryXml.validTag("a:b"))
        assertFalse(SummaryXml.validTag(""))
    }

    private fun fixture(): Library {
        val node = WorkflowNode(id = "small", inputTemplate = "{{history.pending}}", includeHistory = false,
            outputKey = "small_summary", blocks = listOf(PromptBlock(id = "task", template = "Keep my task.")))
        val leaf = Workflow(id = "leaf", title = "Small", trigger = RuntimeEvents.HISTORY_PROCESS, nodes = listOf(node))
        val initial = HistoryProcessor(workflow = leaf, outputKey = node.outputKey, minimumNewFloors = 2,
            keepRecentFloors = 0, maximumBatchFloors = 4, sourceKind = HistorySourceKind.DISPLAY,
            thresholdBasis = HistoryThresholdBasis.UNCOMPRESSED_FLOORS)
        val processor = initial.copy(aggregationWorkflow = defaultHistoryAggregationWorkflow(initial))
        val group = CallGroup(id = "memory-group", name = "Memory", workflows = listOf(leaf, processor.aggregationWorkflow!!))
        val extension = ConfigurationExtension(id = "memory", name = "Memory", resources = listOf(
            ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)), memory = MemoryPlugins.rules(group.id, processor))
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "chat", nodes = listOf(
            WorkflowNode(actionId = "text.render", inputTemplate = "reply", includeHistory = false, visible = true)))))
        val session = Session(id = "session", characterId = "character", mainCallId = main.id,
            enabledExtensionIds = listOf(extension.id), messages = (1..4).map { ChatMessage(id = "f$it",
                role = if (it % 2 == 0) Role.ASSISTANT else Role.USER, text = "floor $it") })
        return Library(characters = listOf(CharacterCard(id = "character", name = "Character", namespace = "hero")),
            mainCalls = listOf(main), groups = listOf(group), extensions = listOf(extension), sessions = listOf(session),
            selectedSessionId = session.id, memoryConfig = GlobalMemoryConfig(enabled = true, extensionId = extension.id),
            selectedProfileId = "model", profiles = listOf(ModelProfile(id = "model", model = "offline", apiConnectionId = "connection")),
            connections = listOf(ApiConnection(id = "connection")))
    }

    @Test fun liveUpgradePreservesTaskAndIsIdempotent() {
        val before = fixture()
        val upgraded = GlobalMemory.ensureXmlValidation(before)
        assertEquals(upgraded, GlobalMemory.ensureXmlValidation(upgraded))
        val old = GlobalMemory.draft(before)!!
        val next = GlobalMemory.draft(upgraded)!!
        assertEquals(old.smallNode.blocks, next.smallNode.blocks.dropLast(1))
        assertTrue(next.smallNode.blocks.last().template.contains("{{state.memory_summary_xml_tag}}"))
        assertEquals(before.sessions, upgraded.sessions)
        assertNull(GlobalMemory.summaryXmlTag(upgraded, old.processor.workflow.id, old.smallNode.id, 22))
        assertEquals("summary", GlobalMemory.summaryXmlTag(upgraded, old.processor.workflow.id, old.smallNode.id, 23))
    }

    @Test fun bothStagesRejectMissingXmlWhileLegacyRemainsUnchanged() {
        val library = GlobalMemory.ensureXmlValidation(fixture())
        val draft = GlobalMemory.draft(library)!!
        val session = library.sessions.single()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String = error("No model call")
        }
        listOf(draft.processor.workflow to draft.smallNode, draft.processor.aggregationWorkflow!! to draft.largeNode).forEach { (flow, node) ->
            val ref = PlannedNode(flow, node, ActionRegistry.defaults().snapshot().getValue(node.actionId))
            val context = ActionContext(node, "", emptyList(), emptyMap(), emptyMap(), emptyMap(), null, gateway, {},
                session = session, library = library, workflowId = flow.id, executionVersion = 23)
            val validator = OutcomeValidator(library, session)
            val failure = assertThrows(NodeExecutionFailure::class.java) { validator.validate(ref, context, ActionOutcome("plain")) }
            assertEquals(RecoveryFailureKind.OUTPUT_FORMAT, failure.kind)
            assertEquals("plain", validator.validate(ref, context.copy(executionVersion = 22), ActionOutcome("plain")).text)
            assertEquals("accepted", validator.validate(ref, context, ActionOutcome("<summary>accepted</summary>")).text)
        }
    }

    @Test fun invalidCandidateDoesNotSaveAndValidCandidateUsesVisibleProtocol() = runBlocking {
        val library = GlobalMemory.ensureXmlValidation(fixture())
        val session = library.sessions.single()
        val sent = mutableListOf<String>()
        fun gateway(value: String) = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                sent += messages.joinToString("\n") { it.text }
                return value
            }
        }
        val invalid = WorkflowEngine().run(library, session, "floor 4", gateway("plain"))
        assertNotEquals(RunStatus.SUCCESS, invalid.status)
        assertTrue(invalid.historyMemory.segments.isEmpty())
        val valid = WorkflowEngine().run(library, session, "floor 4", gateway("<summary>saved &amp; decoded</summary>"))
        assertEquals(valid.traces.toString(), RunStatus.SUCCESS, valid.status)
        assertEquals("saved & decoded", valid.historyMemory.segments.values.single().summary)
        assertTrue(sent.any { it.contains("<summary>") && it.contains("Keep my task.") })
        assertFalse(valid.state.containsKey("memory_summary_xml_tag"))
    }
}
