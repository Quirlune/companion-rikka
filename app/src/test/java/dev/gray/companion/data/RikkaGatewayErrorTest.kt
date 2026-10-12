package dev.gray.companion.data

import me.rerere.ai.util.HttpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RikkaGatewayErrorTest {
    @Test fun displaysProviderReasonWithoutCredentialsOrRawBody() {
        val detail = HttpException("This model does not support assistant message prefill. Bearer sk-secretvalue1234567890",
            providerCode = "400", providerName = "Anthropic")
        val wrapped = HttpException(detail.message.orEmpty(), 400, detail)
        val safe = safeHttpDiagnostic(wrapped).orEmpty()
        assertTrue(safe.contains("assistant message prefill"))
        assertTrue(safe.contains("提供商 Anthropic"))
        assertFalse(safe.contains("secretvalue"))
        assertFalse(safe.contains("sk-"))
        assertFalse(safe.contains("{"))
    }
}
