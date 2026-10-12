package dev.gray.companion.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run with VELUNE_BUNDLE (or -Dvelune.bundle) pointing to the delivered ZIP. */
class GrayG11ImportTest {
    @Test fun importedBundleKeepsHistoryMemoryAndCharacterLoreOnTheMainRequest() = runBlocking {
        val configuredPath = System.getProperty("velune.bundle")?.takeIf(String::isNotBlank)
            ?: System.getenv("VELUNE_BUNDLE")?.takeIf(String::isNotBlank)
        assumeTrue("Set VELUNE_BUNDLE or -Dvelune.bundle to validate the delivered ZIP", configuredPath != null)
        val archive = File(requireNotNull(configuredPath))
        assertTrue("Bundle is missing: $archive", archive.isFile)
        val staging = Files.createTempDirectory("gray-g11-import-").toFile()
        val imported = try {
            val read = archive.inputStream().use { ResourceArchiveImport.read(it, staging, fileName = archive.name) }
            assertTrue(read.issues.toString(), read.issues.none { it.error })
            val base = LibraryFormat.upgrade(Library())
            val preview = ConfigurationCommands.preview(requireNotNull(read.document), base)
            assertTrue(preview.issues.toString(), preview.issues.none { it.error })
            val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
            assertTrue(applied.issues.toString(), applied.issues.none { it.error })
            requireNotNull(applied.library)
        } finally {
            staging.deleteRecursively()
        }

        val character = imported.characters.single { it.id == "floluo_character" }
        val main = imported.mainCalls.single { it.id == "gray_g11_main" }
        val group = imported.groups.single { it.id == "floluo_lore_group" }
        val lore = imported.packages.single { it.id == "floluo_lore_package" }
        assertNotNull(character.avatar?.decodedBytes())
        assertEquals("floluo_rover_persona", character.boundPersonaId)
        assertTrue(imported.personas.any { it.id == character.boundPersonaId })
        assertTrue(group.id in character.boundGroupIds)
        assertTrue(lore.id in group.boundPackageIds)
        assertEquals(Role.ASSISTANT, main.history.apiRole)
        assertFalse(main.history.mergeMessages)

        val workflow = main.workflows.single { it.id == "gray_g11_reply_workflow" }
        val node = workflow.nodes.single { it.id == "gray_g11_generate" }
        val historyBlock = node.blocks.single { it.id == "gray_g11_history" }
        assertEquals("{{type:builtin.in:history.name:context.source:display}}", historyBlock.template)
        val slotIds = setOf("gray_g11_slot_world", "gray_g11_slot_characters", "gray_g11_slot_rules", "gray_g11_slot_other")
        assertTrue(node.blocks.filter { it.id in slotIds }.all {
            it.injectionSlot && it.template.isEmpty() && it.slotReferences.isEmpty()
        })
        val placeholder = node.blocks.single { it.id == "gray_g11_recent_placeholder" }
        assertEquals("", placeholder.template)
        assertEquals("xxx", placeholder.recognition?.rules?.single()?.pattern)
        assertEquals("{{type:builtin.in:history.name:messages.source:display.from:end-3.through:end-1}}",
            placeholder.recognition?.rules?.single()?.template)

        val currentText = "G11_CURRENT_xxx_UNIQUE 残星会 秧秧"
        val older = ChatMessage(id = "g11-older", role = Role.USER, text = "G11_OLD_USER")
        val answer = ChatMessage(id = "g11-answer", role = Role.ASSISTANT, text = "G11_OLD_ANSWER")
        val current = ChatMessage(id = "g11-current", role = Role.USER, text = currentText)
        val session = Session(id = "g11-test-session", characterId = character.id, mainCallId = main.id,
            personaId = character.boundPersonaId,
            messages = listOf(older, answer, current))
        assertEquals(listOf(group.id), imported.loadedCallGroups(session, main).filter { it.id == group.id }.map { it.id })
        assertEquals(listOf(lore.id), imported.loadedPromptPackages(session, main).filter { it.id == lore.id }.map { it.id })
        val profile = ModelProfile(id = "g11-offline-profile", model = "offline-test")
        val source = imported.copy(profiles = imported.profiles + profile, selectedProfileId = profile.id,
            sessions = listOf(session), selectedSessionId = session.id)
        var request: List<ChatMessage> = emptyList()
        var requests = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                requests++
                request = messages
                return "G11_REPLY"
            }
        }
        val run = WorkflowEngine().run(source, session, currentText, gateway)
        assertEquals(run.traces.toString(), RunStatus.SUCCESS, run.status)
        assertEquals(1, requests)
        assertTrue("Character group did not inject lore", run.promptInjections.any {
            it.origin.resourceId == group.id && it.targetWorkflowId == workflow.id &&
                it.targetNodeId == node.id && it.targetBlockId in slotIds
        })
        assertTrue("Lore injection did not reach the model", run.promptInjections.any { injection ->
            injection.block.template.isNotBlank() && request.any { injection.block.template in it.text }
        })
        assertEquals(1, request.sumOf { it.text.split(currentText).size - 1 })

        val initialized = GlobalMemory.ensureGlobal(source)
        assertTrue(initialized.issues.toString(), initialized.issues.none { it.error })
        val memoryBase = requireNotNull(initialized.library)
        val memoryConfig = requireNotNull(memoryBase.memoryConfig)
        val memoryLibrary = memoryBase.copy(memoryConfig = memoryConfig.copy(enabled = true))
        val memoryWorkflow = requireNotNull(GlobalMemory.draft(memoryLibrary)).processor.workflow.id
        val segment = HistoryMemory.segment("previous exchange", "G11_MEMORY_SUMMARY", listOf(older, answer), memoryWorkflow)
        val remembered = session.copy(historyMemory = HistoryMemory.apply(session.historyMemory,
            HistoryMemoryPatch(segments = listOf(segment))))
        val projected = HistoryViews.project(memoryLibrary, remembered, main,
            requireNotNull(HistoryViews.selection(historyBlock.template)))
        assertTrue(projected.issues.toString(), projected.issues.none { it.error })
        assertTrue(projected.messages.toString(), projected.messages.all { it.role == Role.ASSISTANT })
        assertTrue(projected.messages.any { "G11_MEMORY_SUMMARY" in it.text })
        assertFalse(projected.messages.any { "G11_OLD_USER" in it.text || "G11_OLD_ANSWER" in it.text })
        assertEquals(listOf(older.id, answer.id, current.id), projected.sourceIds)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), remembered.messages.map { it.role })

        val rememberedPreview = PromptCompiler().preview(memoryLibrary, remembered, node, currentText,
            currentUserMessageId = current.id)
        assertTrue(rememberedPreview.issues.toString(), rememberedPreview.issues.none { it.error })
        assertEquals(1, rememberedPreview.messages.sumOf { it.text.split(currentText).size - 1 })
        val filled = node.copy(blocks = node.blocks.map {
            if (it.id == placeholder.id) it.copy(template = "G11_XXX_CONDITIONAL") else it
        })
        val hit = PromptCompiler().preview(memoryLibrary, remembered, filled, currentText,
            currentUserMessageId = current.id)
        val miss = PromptCompiler().preview(memoryLibrary, remembered, filled, "G11_CURRENT_CLEAN",
            currentUserMessageId = current.id)
        assertTrue(hit.issues.toString(), hit.issues.none { it.error })
        assertTrue(miss.issues.toString(), miss.issues.none { it.error })
        assertTrue(hit.messages.any { "G11_XXX_CONDITIONAL" in it.text })
        assertFalse(miss.messages.any { "G11_XXX_CONDITIONAL" in it.text })
    }
}
