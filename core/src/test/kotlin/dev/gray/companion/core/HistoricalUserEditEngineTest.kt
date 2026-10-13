package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoricalUserEditEngineTest {
    @Test fun middleUserEditRestoresFrozenCheckpointAndKeepsBothFutures() = runBlocking {
        var effects = 0
        var modelCalls = 0
        val registry = ActionRegistry.defaults().register(ActionDescriptor("accepted.effect", "Accepted effect")) {
            effects++
            ActionOutcome(stateWrites = mapOf("accepted" to effects.toString()))
        }
        val body = WorkflowNode(id = "body", outputKey = "body",
            visible = true, includeHistory = false, recordTrajectory = false)
        val effect = WorkflowNode(id = "effect", actionId = "accepted.effect", outputKey = "effect",
            dependsOn = listOf(body.id), includeHistory = false, recordTrajectory = false)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(
            Workflow(id = "flow", nodes = listOf(body, effect))))
        val library = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main), profiles = listOf(ModelProfile(id = "model", model = "offline")),
            selectedProfileId = "model")
        val localModel = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                onDelta: suspend (String) -> Unit): String {
                modelCalls++
                return messages.last().text
            }
        }
        suspend fun complete(before: Session, input: String): Session {
            val resources = CompletedWorlds.executionLibrary(library, before)
            val result = WorkflowEngine(registry).run(resources, before, input, localModel)
            assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
            return MessageOperations.recordSuccessfulTurn(before, result).session!!
        }
        suspend fun accept(session: Session, id: String, input: String): Session = complete(
            session.copy(messages = session.messages + ChatMessage(id = id, text = input)), input)

        val start = Session(id = "chat", characterId = "card", mainCallId = "main")
        val original = accept(accept(accept(start, "u1", "one"), "u2", "two"), "u3", "three")
        assertEquals(3, effects)
        assertEquals(3, modelCalls)
        val oldAnswer = original.messages.first { it.turnId == "u2" }
        val withEditedAi = MessageOperations.editText(original, oldAnswer.id, "edited old answer").session!!
        val edited = ConversationPaths.editUserAndSend(withEditedAi, "u2", "two revised").session!!
        assertEquals(listOf("u1", original.messages.first { it.turnId == "u1" }.id, "u2"),
            edited.messages.map { it.id })
        assertEquals("1", edited.state["accepted"])
        assertEquals("one", edited.results["body"])
        assertTrue(edited.macroHistory["result.body"].isNullOrEmpty())
        assertEquals(3, effects)
        assertEquals(3, modelCalls)
        assertNotNull(edited.executableWorld)
        assertEquals(emptyList<PureReplayRecipe>(), edited.executableWorld!!.recipes)
        assertTrue(BranchReplay.rebuild(edited).succeeded)
        assertEquals(3, modelCalls)

        val revised = complete(edited, "two revised")
        val revisedFuture = accept(revised, "u4", "four")
        assertEquals(5, effects)
        assertEquals(5, modelCalls)
        val restored = ConversationPaths.selectUserVersion(revisedFuture, "u2", "u2").session!!
        assertEquals(withEditedAi.messages, restored.messages)
        assertEquals(withEditedAi.state, restored.state)
        assertEquals("edited old answer", restored.messages.first { it.id == oldAnswer.id }.text)
        assertEquals(5, effects)
        assertEquals(5, modelCalls)
        val returned = ConversationPaths.selectUserVersion(restored, "u2",
            edited.messages.last().activeTextRevisionId!!).session!!
        assertEquals(revisedFuture.messages, returned.messages)
        assertEquals(revisedFuture.state, returned.state)
        assertEquals(5, effects)
        assertEquals(5, modelCalls)

        val oldVariant = restored.turnRecords.first { it.userMessageId == "u2" }.selectedVariantId!!
        val base = MessageOperations.regenerationBase(restored, "u2").session!!
        val candidate = MessageOperations.prepareRegeneration(base, "u2").session!!
        assertEquals("1", candidate.state["accepted"])
        val rerollResult = WorkflowEngine(registry).run(CompletedWorlds.executionLibrary(library, candidate),
            candidate, "two", localModel)
        assertEquals(rerollResult.traces.toString(), RunStatus.SUCCESS, rerollResult.status)
        val rerolled = MessageOperations.commitRegeneration(base, candidate, rerollResult).session!!
        assertEquals(6, effects)
        assertEquals(6, modelCalls)
        assertEquals("6", rerolled.state["accepted"])
        val selectedOld = ConversationPaths.selectGenerationVariant(rerolled, "u2", oldVariant).session!!
        assertEquals(withEditedAi.messages, selectedOld.messages)
        assertEquals(withEditedAi.state, selectedOld.state)
        assertEquals("edited old answer", selectedOld.messages.first { it.id == oldAnswer.id }.text)
        assertEquals(6, effects)
        assertEquals(6, modelCalls)
    }
}
