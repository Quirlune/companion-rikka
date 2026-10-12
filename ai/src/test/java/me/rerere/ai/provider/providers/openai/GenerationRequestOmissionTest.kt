package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationRequestOmissionTest {
    private val client = OkHttpClient()
    private val messages = listOf(UIMessage.user("hello"))

    private fun params(modelId: String, level: ReasoningLevel? = null, legacy: Boolean = false,
        maxTokens: Int? = null) = TextGenerationParams(
        model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
        reasoningLevel = level, legacyGeneration = legacy, maxTokens = maxTokens)

    private fun google(params: TextGenerationParams): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody",
            List::class.java, TextGenerationParams::class.java)
        method.isAccessible = true
        return method.invoke(GoogleProvider(client), messages, params) as JsonObject
    }

    private fun claude(params: TextGenerationParams): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest",
            ProviderSetting.Claude::class.java, List::class.java, TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(ClaudeProvider(client), ProviderSetting.Claude(), messages, params, false) as JsonObject
    }

    private fun chat(params: TextGenerationParams): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages, params,
            ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"), false) as JsonObject
    }

    private fun responses(params: TextGenerationParams): JsonObject =
        ResponseAPI(client).buildRequestBody(ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            messages, params, false)

    @Test fun omittedReasoningDoesNotCreateProviderThinkingFields() {
        assertFalse(google(params("gemini-2.5-flash"))["generationConfig"]?.jsonObject
            ?.containsKey("thinkingConfig") ?: false)
        val claude = claude(params("claude-sonnet-4-6"))
        assertFalse(claude.containsKey("thinking"))
        assertEquals("4096", claude["max_tokens"]?.jsonPrimitive?.content)
        assertFalse(chat(params("gpt-5.2")).containsKey("reasoning_effort"))
        assertFalse(responses(params("gpt-5.2")).containsKey("reasoning"))
    }

    @Test fun explicitReasoningUsesSupportedGoogleAndClaudeShapes() {
        val off = google(params("gemini-2.5-flash", ReasoningLevel.OFF))["generationConfig"]!!
            .jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("0", off["thinkingBudget"]?.jsonPrimitive?.content)
        val auto = google(params("gemini-2.5-flash", ReasoningLevel.AUTO))["generationConfig"]!!
            .jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("true", auto["includeThoughts"]?.jsonPrimitive?.content)
        assertFalse(auto.containsKey("thinkingBudget"))
        val oldClaude = claude(params("claude-sonnet-4-5", ReasoningLevel.HIGH))
        assertEquals("enabled", oldClaude["thinking"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("8000", oldClaude["thinking"]!!.jsonObject["budget_tokens"]?.jsonPrimitive?.content)
        val newClaude = claude(params("claude-sonnet-4-6", ReasoningLevel.HIGH))
        assertEquals("adaptive", newClaude["thinking"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("high", newClaude["output_config"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
    }

    @Test fun openAiUsesCurrentTokenFieldButLegacyCaptureKeepsItsOldWire() {
        val current = chat(params("gpt-5.2", ReasoningLevel.OFF, maxTokens = 321))
        assertEquals("321", current["max_completion_tokens"]?.jsonPrimitive?.content)
        assertFalse(current.containsKey("max_tokens"))
        assertEquals("none", current["reasoning_effort"]?.jsonPrimitive?.content)
        val legacy = chat(params("gpt-5.2", ReasoningLevel.OFF, legacy = true, maxTokens = 321))
        assertEquals("321", legacy["max_tokens"]?.jsonPrimitive?.content)
        assertFalse(legacy.containsKey("max_completion_tokens"))
        assertTrue(responses(params("gpt-5.2", ReasoningLevel.AUTO)).containsKey("reasoning"))
    }

    @Test fun knownUnsupportedLevelsFailBeforeSending() {
        val googleError = runCatching { google(params("gemini-2.5-pro", ReasoningLevel.OFF)) }
            .exceptionOrNull()?.cause
        assertTrue(googleError is IllegalArgumentException)
        val claudeError = runCatching { claude(params("claude-sonnet-4-5", ReasoningLevel.AUTO)) }
            .exceptionOrNull()?.cause
        assertTrue(claudeError is IllegalArgumentException)
        val openAiError = runCatching { chat(params("gpt-5.2", ReasoningLevel.MAX)) }
            .exceptionOrNull()?.cause
        assertTrue(openAiError is IllegalArgumentException)
    }
}
