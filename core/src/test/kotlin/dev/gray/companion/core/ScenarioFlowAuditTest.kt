package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** User journeys crossing configuration, persistence, and the real workflow engine. No network. */
class ScenarioFlowAuditTest {
    private fun fixture(): Library {
        val base = LibraryDefaults.create()
        val main = base.mainCalls.single().let { it.copy(specialDefault = false, workflows = listOf(Workflow(
            id = it.primaryWorkflowId!!, title = "正文", nodes = listOf(WorkflowNode(
                id = "body", actionId = "text.render", visible = true, includeHistory = false,
                inputTemplate = "正文回复"))))) }
        fun chat(id: String) = base.sessions.single().copy(id = id, enabledExtensionIds = emptyList(),
            messages = (1..9).map { i -> ChatMessage(id = "$id-$i", text = "$id-BODY-$i",
                role = if (i % 2 == 1) Role.USER else Role.ASSISTANT, createdAt = i.toLong()) })
        val configured = base.copy(mainCalls = listOf(main), sessions = listOf(chat("A"), chat("B")),
            selectedSessionId = "A", profiles = base.profiles.map { it.copy(model = "offline") })
        val draft = GlobalMemory.draft(configured)!!
        val edited = GlobalMemory.withDraft(configured, draft.copy(config = draft.config.copy(enabled = true),
            processor = draft.processor.copy(minimumNewFloors = 4, keepRecentFloors = 1, maximumBatchFloors = 2)))
        // Match AppController's live-library normalization before configuration import.
        return LibraryFormat.upgrade(edited.withStandardCharacterFields())
    }

    @Test fun globalPolicyProcessesTwoChatsIndependentlyAndRetainsAcceptedBatchesAcrossReload() = runBlocking {
        val library = fixture()
        val inputs = mutableListOf<String>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                inputs += messages.joinToString("\n") { it.text }
                return "本地摘要-${inputs.size}"
            }
        }
        val results = library.sessions.map { chat ->
            val run = WorkflowEngine().run(library, chat, chat.messages.last().text, gateway)
            assertEquals(run.traces.toString(), RunStatus.SUCCESS, run.status)
            assertEquals(listOf("${chat.id}-1", "${chat.id}-2"),
                run.historyMemory.segments.values.single().sources.map { it.messageId })
            chat.copy(historyMemory = run.historyMemory)
        }
        assertEquals(2, inputs.size)
        assertFalse(inputs[0].contains("B-BODY"))
        assertFalse(inputs[1].contains("A-BODY"))
        val saved = library.copy(sessions = results)
        val reloaded = LibraryFormat.json.decodeFromString(Library.serializer(),
            LibraryFormat.json.encodeToString(Library.serializer(), saved))
        val off = GlobalMemory.setEnabled(reloaded, reloaded.memoryConfig!!, false).library!!
        val paused = WorkflowEngine().run(off, off.sessions.first(), "A-BODY-9", gateway)
        assertEquals(paused.traces.toString(), RunStatus.SUCCESS, paused.status)
        assertEquals(2, inputs.size)
        assertEquals(results.first().historyMemory, paused.historyMemory)
        val on = GlobalMemory.setEnabled(off, off.memoryConfig!!, true).library!!
        val resumed = WorkflowEngine().run(on, on.sessions.first(), "A-BODY-9", gateway)
        assertEquals(resumed.traces.toString(), RunStatus.SUCCESS, resumed.status)
        assertEquals(3, inputs.size)
        assertEquals(setOf("A-1", "A-2", "A-3", "A-4"),
            resumed.historyMemory.segments.values.flatMap { it.sources }.map { it.messageId }.toSet())
        assertFalse(inputs.last().contains("A-BODY-1"))
        assertEquals(results.last().historyMemory, on.sessions.last().historyMemory)
    }

    @Test fun configurationPreviewApplyReloadUndoPreserveGlobalPolicyAndChatMemories() {
        val base = fixture()
        val config = base.memoryConfig!!.copy(enabled = false)
        val encoded = LibraryFormat.json.encodeToString(GlobalMemoryConfig.serializer(), config)
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"select","key":"memoryConfig","value":$encoded}]}""", base)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        assertEquals(listOf("memoryConfig"), preview.settings.map { it.key })
        val applied = ConfigurationCommands.apply(preview.plan!!, base)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        val saved = applied.library!!
        // Receipts are local repository data, deliberately excluded from exported Library JSON.
        val receipt = LibraryFormat.json.decodeFromString(ConfigurationEditReceipt.serializer(),
            LibraryFormat.json.encodeToString(ConfigurationEditReceipt.serializer(), applied.receipt!!))
        val reloaded = LibraryFormat.json.decodeFromString(Library.serializer(),
            LibraryFormat.json.encodeToString(Library.serializer(), saved))
        assertEquals(config, reloaded.memoryConfig)
        val undo = ConfigurationCommands.undo(receipt, reloaded)
        assertTrue(undo.issues.toString(), undo.issues.none { it.error })
        assertEquals(base.memoryConfig, undo.library!!.memoryConfig)
        assertEquals(base.sessions, undo.library!!.sessions)
        val deletion = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"remove","kind":"extension","id":"${config.extensionId}"}]}""", base)
        assertTrue(deletion.issues.toString(), deletion.issues.any { it.error })
        assertNull(deletion.plan)
    }
}
