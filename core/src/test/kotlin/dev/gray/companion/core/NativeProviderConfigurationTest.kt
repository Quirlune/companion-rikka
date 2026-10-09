package dev.gray.companion.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NativeProviderConfigurationTest {
    @Test fun signedNativeResponseSurvivesParsingAndStoredHistory() = kotlinx.coroutines.runBlocking {
        val nativeMessage = payload("""{"role":"ASSISTANT","parts":[{"type":"text","text":"answer"}],"metadata":{"signature":"signed-fixture"}}""")
        val nativeReasoning = ReasoningSnapshot("thinking", 1L, 2L, nativeMessage)
        val source = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) = "answer"
            override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit) =
                ModelResponse("answer", nativeReasoning)
        }
        val parsed = CandidateModelGateway(source, ReasoningParsingConfig()) {}.generateResponseAttempt(ModelProfile(), emptyList()) {}
        assertEquals(nativeMessage, parsed.reasoning!!.nativeMessage)
        val library = Library(sessions = listOf(Session(id = "chat", messages = listOf(
            ChatMessage(role = Role.ASSISTANT, text = parsed.text, reasoningSnapshot = parsed.reasoning)))))
        val stored = LibraryFormat.decode(LibraryFormat.encode(library))
        assertEquals(nativeMessage, stored.sessions.single().messages.single().reasoningSnapshot!!.nativeMessage)
        assertEquals("thinking", stored.sessions.single().messages.single().reasoningSnapshot!!.text)
    }

    private fun payload(text: String): JsonObject = LibraryFormat.json.parseToJsonElement(text) as JsonObject

    @Test fun legacyWireRetainsIdsParametersAndNullNativeConfiguration() {
        val oldWire = """{
            "schemaVersion":13,
            "connections":[{"id":"account","protocol":"GOOGLE","baseUrl":"https://example.com"}],
            "profiles":[{"id":"model","apiConnectionId":"account","model":"gemini","temperature":0.6,
                "customBody":[{"key":"extra","value":{"apiKey":"ordinary request data"}}]}],
            "selectedProfileId":"model"
        }"""
        val restored = LibraryFormat.decode(oldWire)
        assertEquals(14, restored.schemaVersion)
        assertEquals("account", restored.connections.single().id)
        assertNull(restored.connections.single().nativeProvider)
        assertEquals("model", restored.profiles.single().id)
        assertNull(restored.profiles.single().nativeModel)
        assertNull(ModelCatalog.resolveProfile(restored)!!.resolvedProvider)
        assertEquals(0.6f, restored.profiles.single().temperature)
        assertEquals(payload("""{"apiKey":"ordinary request data"}"""), restored.profiles.single().customBody.single().value)
        assertEquals(restored, LibraryFormat.decode(LibraryFormat.encode(restored)))
    }

    @Test fun typedNativeLocationsRejectSecretsAndFutureFormatsIncludingArchivedPreparation() {
        val allowed = payload("""{"_formatVersion":1,"providerOverwrite":{"type":"google","apiKey":"",
            "privateKey":null,"models":[{"customBodies":[{"key":"payload","value":{"privateKey":"body data"}}]}]},
            "customBodies":[{"key":"providerOverwrite","value":{"apiKey":"body data"}}]}""")
        assertTrue(NativeProviderConfiguration.validateModel(allowed, "model").isEmpty())
        val nested = payload("""{"_formatVersion":1,"providerOverwrite":{"type":"google","models":[{
            "providerOverwrite":{"type":"google","privateKey":"private fixture"}}]}}""")
        val nestedIssues = NativeProviderConfiguration.validateModel(nested, "model")
        assertEquals("model.providerOverwrite.models.0.providerOverwrite.privateKey", nestedIssues.single().path)
        assertTrue(NativeProviderConfiguration.validateProvider(payload("""{"_formatVersion":2}"""), "provider").any { it.error })
        assertTrue(NativeProviderConfiguration.validateProvider(payload("""{"_formatVersion":"1"}"""), "provider").any { it.error })
        val connection = ApiConnection(id = "account", nativeProvider = payload("""{"_formatVersion":1,"apiKey":"secret fixture"}"""))
        val initial = Session(id = "session")
        val journal = RunRecovery(id = "run", sessionId = initial.id, turnId = "turn", input = "hello",
            checkpoint = TurnCheckpoint(RuntimeSnapshot()), initialSession = initial,
            frozenResources = Library(connections = listOf(connection)), executionVersion = 8,
            prepared = listOf(RecoveryPrepared("flow/node", "environment", "hello", emptyMap(),
                ModelProfile(id = "model", nativeModel = nested))))
        val library = Library(savedChats = listOf(SavedChat(id = "saved", folderId = "folder",
            snapshot = initial.copy(pendingRun = journal))))
        val issues = NativeProviderConfiguration.validate(library)
        assertTrue(issues.any { ".frozenResources.connections.account.nativeProvider.apiKey" in it.path })
        assertTrue(issues.any { ".prepared.0.profile.nativeModel.providerOverwrite.models.0.providerOverwrite.privateKey" in it.path })
        assertTrue(LibraryValidator.validate(library).any { it.path.endsWith("nativeProvider.apiKey") })
        assertTrue(runCatching { LibraryFormat.encode(library) }.exceptionOrNull() is IllegalArgumentException)
        val unsafeWire = LibraryFormat.json.encodeToString(Library.serializer(), library)
        assertTrue(runCatching { LibraryFormat.decode(unsafeWire) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun restoredPreparationRebuildsFrozenTransportWithoutReplacingCapturedGenerationOptions() {
        val nativeProvider = payload("""{"_formatVersion":1,"type":"google","vertexAI":true,"projectId":"frozen"}""")
        val nativeModel = payload("""{"_formatVersion":1,"abilities":["REASONING"]}""")
        val connection = ApiConnection(id = "account", protocol = Protocol.GOOGLE, nativeProvider = nativeProvider,
            customHeaders = listOf(RequestHeader("x-fixture", "frozen")))
        val profile = ModelProfile(id = "model", model = "gemini", apiConnectionId = connection.id,
            temperature = 0.2f, topP = 0.5f, nativeModel = nativeModel)
        val node = WorkflowNode(id = "node", blocks = listOf(PromptBlock(template = "hello")))
        val flow = Workflow(id = "flow", nodes = listOf(node), modelProfileId = profile.id)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(flow))
        val session = Session(id = "session", characterId = "character", mainCallId = main.id)
        val frozen = Library(characters = listOf(CharacterCard(id = "character", name = "Hero", namespace = "hero")),
            mainCalls = listOf(main), profiles = listOf(profile), connections = listOf(connection), selectedProfileId = profile.id)
        val capturedProfile = ModelCatalog.resolveProfile(frozen)!!.copy(temperature = 0.9f, topP = 0.8f, maxTokens = 123)
        val prepared = RecoveryPrepared("flow/node", "environment", "hello", emptyMap(), capturedProfile)
        val wire = LibraryFormat.json.encodeToString(RecoveryPrepared.serializer(), prepared)
        assertFalse(wire.contains("resolvedProvider"))
        assertFalse(wire.contains("resolvedHeaders"))
        val restored = LibraryFormat.json.decodeFromString(RecoveryPrepared.serializer(), wire)
        assertNull(restored.profile!!.resolvedProvider)
        val result = InvocationPreparer(frozen).prepare(
            PlannedNode(flow, node, ActionRegistry.defaults().snapshot()[node.actionId]),
            InvocationEnvironment(session, "hello", HistoryContext(frozen, session, main), null), restored)
        assertEquals(capturedProfile, result.profile)
        assertEquals(nativeProvider, result.profile!!.resolvedProvider)
        assertEquals(nativeModel, result.profile.nativeModel)
        assertEquals(nativeProvider, LibraryFormat.decode(LibraryFormat.encode(frozen)).connections.single().nativeProvider)
    }

    @Test fun legacyCredentialStoreDelegatesOnlyApiKeySlots() {
        val values = mutableMapOf<String, String>()
        val store = object : CredentialStore {
            override fun get(profileId: String): String = values[profileId].orEmpty()
            override fun put(profileId: String, secret: String) { values[profileId] = secret }
            override fun remove(profileId: String) { values.remove(profileId) }
        }
        val ordinary = CredentialSlot("account")
        store.put(ordinary, "fixture")
        assertEquals("fixture", store.get("account"))
        assertEquals("fixture", store.get(ordinary))
        CredentialKind.values().filter { it != CredentialKind.API_KEY }.forEach { kind ->
            val slot = CredentialSlot("account", kind)
            assertTrue(runCatching { store.get(slot) }.exceptionOrNull() is UnsupportedOperationException)
            assertTrue(runCatching { store.put(slot, "other") }.exceptionOrNull() is UnsupportedOperationException)
            assertTrue(runCatching { store.remove(slot) }.exceptionOrNull() is UnsupportedOperationException)
        }
        assertEquals("fixture", store.get(ordinary))
        store.remove(ordinary)
        assertEquals("", store.get("account"))
    }
}
