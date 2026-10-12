package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RegenerationAdmissionTest {
    private fun staged() = Session(id = "chat", characterId = "character", mainCallId = "main",
        groupIds = listOf("group"), messages = listOf(ChatMessage(id = "u1", text = "hello")))

    private fun library(enabled: Boolean) = Library(
        characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
        mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(
            Workflow(id = "flow", nodes = listOf(WorkflowNode(id = "body", visible = true,
                blocks = listOf(PromptBlock(template = "answer {{history.text}}")))))))),
        profiles = listOf(ModelProfile(id = "model", model = "offline")), selectedProfileId = "model",
        groups = listOf(CallGroup(id = "group", name = "Extension group")),
        extensions = listOf(ConfigurationExtension(id = "extension", name = "Extension", enabled = enabled,
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, "group")))))

    private fun gateway(answer: String) = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                      onDelta: suspend (String) -> Unit): String = answer
    }

    private fun synthetic(session: Session, answer: String) = RunResult(
        state = mapOf("place" to answer), results = mapOf("answer" to answer),
        replies = listOf(ChatMessage(role = Role.ASSISTANT, text = answer, turnId = "u1")),
        traces = emptyList(), completedWorld = session.executableWorld)

    @Test fun engineRollAcceptsBothSwitchDirectionsAndKeepsOriginalWorld() = runBlocking {
        for (initialEnabled in listOf(true, false)) {
            val before = staged()
            val live = library(initialEnabled)
            val firstResult = WorkflowEngine().run(live, before, "hello", gateway("A"))
            assertEquals(firstResult.traces.toString(), RunStatus.SUCCESS, firstResult.status)
            val original = MessageOperations.recordSuccessfulTurn(before, firstResult).session!!
            val checkpoint = original.turnRecords.single().checkpoint
            val aId = original.turnRecords.single().selectedVariantId!!
            val base = MessageOperations.regenerationBase(original, "u1").session!!
            val prepared = MessageOperations.prepareRegeneration(base, "u1").session!!
            val replayed = BranchReplay.rebuild(prepared).session!!
            val changed = library(!initialEnabled)
            val candidate = CompletedWorlds.withExtensionPolicyForNewTurn(changed, replayed)
            val admission = MessageOperations.admitRegeneration(base, candidate, changed)
            val result = WorkflowEngine().run(changed, candidate, "hello", gateway("B"))
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            assertFalse(MessageOperations.commitRegeneration(base, candidate, result).succeeded)
            val operation = MessageOperations.commitRegeneration(base, candidate, result, admission)
            assertTrue(operation.issues.toString(), operation.succeeded)
            val committed = operation.session!!
            assertEquals("B", committed.messages.last().text)
            assertEquals(checkpoint, committed.turnRecords.single().checkpoint)
            assertEquals(!initialEnabled, committed.executableWorld!!.library.extensions.single().enabled)
            val restored = ConversationPaths.selectGenerationVariant(committed, "u1", aId).session!!
            assertEquals("A", restored.messages.last().text)
            assertEquals(initialEnabled, restored.executableWorld!!.library.extensions.single().enabled)
            assertEquals(original.macroHistory, restored.macroHistory)
            assertEquals(original.resultOrigins, restored.resultOrigins)
        }
    }

    @Test fun admissionRejectsChangesToRuntimeSourcesDefinitionsAndOriginalBoundary() {
        val live = library(true)
        val before = staged().let { it.copy(executableWorld = CompletedWorlds.capture(live, it, "hello")) }
        val original = MessageOperations.recordSuccessfulTurn(before, synthetic(before, "A")).session!!
        val prepared = MessageOperations.prepareRegeneration(original, "u1").session!!
        val off = library(false)
        val candidate = CompletedWorlds.withExtensionPolicyForNewTurn(off, prepared)
        val admission = MessageOperations.admitRegeneration(original, candidate, off)
        val result = synthetic(candidate, "B")
        val modifiedWorld = candidate.executableWorld!!.let { world -> world.copy(library = world.library.copy(
            mainCalls = world.library.mainCalls.map { it.copy(name = "Changed definition") })) }
        val tampered = listOf(
            candidate.copy(state = mapOf("injected" to "value")),
            candidate.copy(resultOrigins = mapOf("answer" to ResultOrigin("other", "flow", "body"))),
            candidate.copy(messages = candidate.messages.map { it.copy(text = "another prompt") }),
            candidate.copy(turnRecords = emptyList()),
            candidate.copy(executableWorld = modifiedWorld))
        tampered.forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) { MessageOperations.admitRegeneration(original, changed, off) }
            assertFalse(MessageOperations.commitRegeneration(original, changed, result, admission).succeeded)
        }
        assertFalse(MessageOperations.commitRegeneration(original.copy(state = mapOf("changed" to "yes")),
            candidate, result, admission).succeeded)
    }

    @Test fun serializedRecoveryRestoresSavedAdmissionAndRejectsDefinitionDrift() {
        val live = library(true)
        val before = staged().let { it.copy(executableWorld = CompletedWorlds.capture(live, it, "hello")) }
        val original = MessageOperations.recordSuccessfulTurn(before, synthetic(before, "A")).session!!
        val prepared = MessageOperations.prepareRegeneration(original, "u1").session!!
        val candidate = CompletedWorlds.withExtensionPolicyForNewTurn(library(false), prepared)
        val journal = RunRecovery(id = "run", sessionId = candidate.id, turnId = "u1", input = "hello",
            checkpoint = MessageOperations.captureCheckpoint(candidate), initialSession = candidate,
            frozenResources = candidate.executableWorld!!.library, regenerating = true, regenerationBase = original,
            executionVersion = ExecutionSemantics.CURRENT)
        val restored = LibraryFormat.json.decodeFromString(RunRecovery.serializer(),
            LibraryFormat.json.encodeToString(RunRecovery.serializer(), journal))
        val admission = MessageOperations.restoreRegenerationAdmission(restored)
        val committed = MessageOperations.commitRegeneration(restored.regenerationBase!!, restored.initialSession,
            synthetic(restored.initialSession, "B"), admission)
        assertTrue(committed.issues.toString(), committed.succeeded)
        assertEquals(false, committed.session!!.executableWorld!!.library.extensions.single().enabled)
        assertEquals(original.turnRecords.single().checkpoint, committed.session!!.turnRecords.single().checkpoint)
        val changed = candidate.executableWorld!!.copy(input = "different source")
        assertThrows(IllegalArgumentException::class.java) {
            MessageOperations.restoreRegenerationAdmission(restored.copy(initialSession = candidate.copy(executableWorld = changed)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MessageOperations.restoreRegenerationAdmission(restored.copy(turnId = "another turn"))
        }
    }
}
