package dev.gray.companion.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCallParameterOwnershipTest {
    @Test fun newCallsUseOnlyBehaviorParametersWhileLegacyCallsKeepProfileDefaults() {
        val profile = ModelProfile(id = "model", model = "fixture", temperature = 0.7f,
            topP = 0.8f, maxTokens = 900, reasoning = ModelReasoning.HIGH,
            customBody = listOf(RequestBodyField("temperature", JsonPrimitive(0.9))))
        val flow = Workflow(id = "flow", modelProfileId = profile.id)
        val library = Library(profiles = listOf(profile))

        val current = ModelCallOptions().resolve(library, flow, 24)!!
        assertNull(current.temperature)
        assertNull(current.topP)
        assertNull(current.maxTokens)
        assertNull(current.reasoning)
        assertTrue(current.customBody.isEmpty())
        assertFalse(current.legacyGeneration)

        val explicit = ModelCallOptions(temperature = 0.2f, reasoning = ModelReasoning.OFF)
            .resolve(library, flow, 24)!!
        assertEquals(0.2f, explicit.temperature)
        assertEquals(ModelReasoning.OFF, explicit.reasoning)
        assertNull(explicit.topP)

        val old = ModelCallOptions().resolve(library, flow, 23)!!
        assertEquals(profile.temperature, old.temperature)
        assertEquals(profile.topP, old.topP)
        assertEquals(profile.maxTokens, old.maxTokens)
        assertEquals(profile.reasoning, old.reasoning)
        assertEquals(profile.customBody, old.customBody)
        assertTrue(old.legacyGeneration)
    }

    @Test fun frozenPreparationWithoutSerializedReasoningRestoresLegacyOff() {
        // Older archives omitted OFF because it was the ModelProfile default.
        val oldProfile = LibraryFormat.json.decodeFromString(ModelProfile.serializer(),
            """{"id":"model","model":"fixture","temperature":0.4}""")
        assertNull(oldProfile.reasoning)
        val node = WorkflowNode(id = "node", blocks = listOf(PromptBlock(template = "hello")))
        val flow = Workflow(id = "flow", nodes = listOf(node), modelProfileId = oldProfile.id)
        val main = MainCall(id = "main", workflows = listOf(flow))
        val session = Session(id = "chat", mainCallId = main.id)
        val library = Library(mainCalls = listOf(main), profiles = listOf(oldProfile))
        val ref = PlannedNode(flow, node, ActionRegistry.defaults().snapshot()[node.actionId])
        val captured = RecoveryPrepared("flow/node", "environment", "hello", emptyMap(), oldProfile)
        val history = HistoryContext(library, session, main)

        val legacy = InvocationPreparer(library).prepare(ref,
            InvocationEnvironment(session, "hello", history, null, executionVersion = 23), captured).profile!!
        assertEquals(ModelReasoning.OFF, legacy.reasoning)
        assertTrue(legacy.legacyGeneration)
        assertEquals(0.4f, legacy.temperature)

        val current = InvocationPreparer(library).prepare(ref,
            InvocationEnvironment(session, "hello", history, null, executionVersion = 24), captured).profile!!
        assertNull(current.reasoning)
        assertFalse(current.legacyGeneration)
    }
}
