package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterErrorDetailTest {
    @Test fun extractsOnlyUpstreamDiagnosticFields() {
        val body = Json.parseToJsonElement("""{
            "error": {
                "message": "Provider returned error",
                "code": 400,
                "metadata": {
                    "provider_name": "Anthropic",
                    "raw": "{\"error\":{\"message\":\"This model does not support assistant message prefill. The conversation must end with a user message.\",\"type\":\"invalid_request_error\"}}"
                }
            }
        }""")
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val detail = with(api) { body.openRouterErrorDetail() }
        assertEquals("This model does not support assistant message prefill. The conversation must end with a user message.",
            detail.message)
        assertEquals("400", detail.providerCode)
        assertEquals("Anthropic", detail.providerName)
    }
}
