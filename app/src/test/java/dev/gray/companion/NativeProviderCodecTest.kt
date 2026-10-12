package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class NativeProviderCodecTest {
    @Test fun httpModelOverrideIsRejectedBeforeCredentialRead() {
        val override = ProviderSetting.Claude(baseUrl = "http://override.invalid/v1")
        val model = Model(modelId = "claude", providerOverwrite = override)
        val profile = ModelProfile(id = "profile", model = "claude", apiConnectionId = "parent",
            baseUrl = "https://parent.invalid/v1", nativeModel = NativeProviderCodec.modelOptions(model))
        val library = Library(profiles = listOf(profile))
        assertTrue(NativeProviderCodec.validate(library).any { it.path.endsWith("nativeModel") })
        var credentialRead = false
        val failure = runCatching { NativeProviderCodec.request(profile) {
            credentialRead = true
            "synthetic-key"
        } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(credentialRead)
        assertFalse(failure!!.message.orEmpty().contains("synthetic-key"))
    }

    @Test fun vertexServiceAccountSurvivesStorageAndUsesPrivateSlotWithoutApiKey() {
        val original = ProviderSetting.Google(vertexAI = true, useServiceAccount = true,
            serviceAccountEmail = "fixture@example.invalid", projectId = "fixture-project", location = "global",
            privateKey = "private-fixture", apiKey = "api-fixture")
        val connection = ApiConnection(id = "connection", protocol = Protocol.GOOGLE,
            nativeProvider = NativeProviderCodec.providerOptions(original))
        val profile = ModelProfile(id = "profile", model = "gemini", apiConnectionId = connection.id)
        val library = Library(connections = listOf(connection), profiles = listOf(profile), selectedProfileId = profile.id)
        val wire = LibraryFormat.encode(library)
        assertFalse(wire.contains("private-fixture")); assertFalse(wire.contains("api-fixture"))
        val resolved = ModelCatalog.resolveProfile(LibraryFormat.decode(wire))!!
        val slots = NativeProviderCodec.requiredCredentialSlots(resolved)
        assertEquals(setOf(CredentialSlot("connection", CredentialKind.PRIVATE_KEY)), slots)
        val request = NativeProviderCodec.request(resolved) { if (it in slots) "private-fixture" else error("Wrong credential") }
        val google = request.provider as ProviderSetting.Google
        assertTrue(google.vertexAI); assertTrue(google.useServiceAccount)
        assertEquals("fixture-project", google.projectId); assertEquals("global", google.location)
        assertEquals("private-fixture", google.privateKey); assertEquals("", google.apiKey)
    }

    @Test fun fullModelOverrideHasIndependentSecretsAndPreservesNativeCapabilities() {
        val override = ProviderSetting.Claude(apiKey = "override-fixture", promptCaching = true,
            promptCacheTtl = ClaudePromptCacheTtl.ONE_HOUR, baseUrl = "https://override.invalid/v1")
        val original = Model(modelId = "claude", displayName = "Name", abilities = listOf(ModelAbility.REASONING),
            tools = setOf(BuiltInTools.Search), customHeaders = listOf(CustomHeader("x-model", "header")),
            providerOverwrite = override)
        val profile = ModelProfile(id = "profile", model = "claude", apiConnectionId = "parent",
            nativeModel = NativeProviderCodec.modelOptions(original), customBody = listOf(RequestBodyField("seed", JsonPrimitive(42))))
        assertFalse(LibraryFormat.encode(Library(profiles = listOf(profile))).contains("override-fixture"))
        val slot = CredentialSlot("profile", CredentialKind.MODEL_API_KEY)
        assertEquals(setOf(slot), NativeProviderCodec.requiredCredentialSlots(profile))
        val request = NativeProviderCodec.request(profile) { assertEquals(slot, it); "override-fixture" }
        val claude = request.provider as ProviderSetting.Claude
        assertTrue(claude.promptCaching); assertEquals(ClaudePromptCacheTtl.ONE_HOUR, claude.promptCacheTtl)
        assertEquals("https://override.invalid/v1", claude.baseUrl)
        assertEquals(original.abilities, request.model.abilities); assertEquals(original.tools, request.model.tools)
        assertEquals(original.customHeaders, request.model.customHeaders)
        assertEquals(listOf(CustomBody("seed", JsonPrimitive(42))), request.model.customBodies)
        val clean = NativeProviderCodec.sanitizeProvider(ProviderSetting.OpenAI(apiKey = "parent-fixture", models = listOf(original)))
        assertEquals("", (clean.models.single().providerOverwrite as ProviderSetting.Claude).apiKey)
        assertEquals("", (clean as ProviderSetting.OpenAI).apiKey)
    }

    @Test fun pathsBalanceAndHydratedSecretsRoundTripThroughOneCodec() {
        val original = ProviderSetting.OpenAI(chatCompletionsPath = "/custom/chat", responsesPath = "/custom/responses",
            includeHistoryReasoning = false, balanceOption = BalanceOption(true, "/balance", "remaining"))
        val connection = ApiConnection(id = "account", responseApi = true, nativeProvider = NativeProviderCodec.providerOptions(original))
        val projected = NativeProviderCodec.provider(connection, Uuid.random(), emptyList()) as ProviderSetting.OpenAI
        assertEquals(original.chatCompletionsPath, projected.chatCompletionsPath)
        assertEquals(original.responsesPath, projected.responsesPath); assertFalse(projected.includeHistoryReasoning)
        assertEquals(original.balanceOption, projected.balanceOption); assertTrue(projected.useResponseApi)
        val hydrated = NativeProviderCodec.hydrateProvider(projected, connection.id, emptyMap()) { "api-fixture" }
        assertEquals(mapOf(CredentialSlot(connection.id) to "api-fixture"), NativeProviderCodec.credentialValues(hydrated, connection.id, emptyMap()))
        assertEquals(NativeProviderCodec.providerOptions(projected), NativeProviderCodec.providerOptions(hydrated))
    }

    @Test fun legacyNullConfigurationKeepsOriginalFallbackAndReportsMissingAuthSafely() {
        val profile = ModelProfile(id = "legacy", model = "chat", protocol = Protocol.OPENAI,
            baseUrl = "https://legacy.invalid/v1", responseApi = true)
        val request = NativeProviderCodec.request(profile) { assertEquals(CredentialSlot("legacy"), it); "api-fixture" }
        assertTrue((request.provider as ProviderSetting.OpenAI).useResponseApi)
        assertEquals(profile.baseUrl, request.provider.baseUrl)
        assertTrue(request.model.abilities.isEmpty())
        val failure = runCatching { NativeProviderCodec.request(profile) { "" } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(failure!!.message.orEmpty().contains("api-fixture"))
    }
}
