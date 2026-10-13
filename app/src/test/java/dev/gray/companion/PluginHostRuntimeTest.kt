package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PluginHostRuntimeTest {
    private fun context() = PluginExecutionContext(
        "plugin-a", PluginCode(version = 2, source = "async function execute() { return 'ok'; }",
            permissions = listOf(PluginPermission("network.http", listOf("https://api.example.com"),
                listOf("GET", "POST"), listOf("service")))), "input"
    )

    @Test fun deniesBeforeReadingCredentialOrSending() = runBlocking {
        var reads = 0
        var sends = 0
        val access = object : PluginHostAccess {
            override fun isGranted(context: PluginExecutionContext) = false
            override fun credential(pluginId: String, alias: String): String { reads++; return "secret" }
        }
        val transport = object : PluginHttpTransport() {
            override suspend fun execute(request: PluginTransportRequest): PluginHttpResponse {
                sends++; return PluginHttpResponse(200, emptyMap(), "ok")
            }
        }
        val runtime = PluginHostRuntime(context(), access, transport)
        val result = runtime.invokeJson("""{"capability":"network.http","args":{"url":"https://api.example.com/a","credential":{"alias":"service"}}}""")
        assertEquals("PERMISSION_DENIED", LibraryFormat.json.parseToJsonElement(result).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, reads)
        assertEquals(0, sends)
    }

    @Test fun exactOriginMethodAndAliasBoundaries() = runBlocking {
        var sends = 0
        val access = object : PluginHostAccess {
            override fun isGranted(context: PluginExecutionContext) = true
            override fun credential(pluginId: String, alias: String) = "secret"
        }
        val transport = object : PluginHttpTransport() {
            override suspend fun execute(request: PluginTransportRequest): PluginHttpResponse {
                sends++
                assertEquals("Bearer secret", request.headers["Authorization"])
                return PluginHttpResponse(202, emptyMap(), "accepted")
            }
        }
        val runtime = PluginHostRuntime(context(), access, transport)
        for (args in listOf(
            """{"url":"https://api.example.com.evil.test/a"}""",
            """{"url":"https://api.example.com:444/a"}""",
            """{"url":"https://api.example.com/a","method":"DELETE"}""",
            """{"url":"https://api.example.com/a","credential":{"alias":"other"}}"""
        )) {
            val result = runtime.invokeJson("""{"capability":"network.http","args":$args}""")
            assertEquals("PERMISSION_DENIED", LibraryFormat.json.parseToJsonElement(result).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
        val result = runtime.invokeJson("""{"capability":"network.http","args":{"url":"https://api.example.com/a","credential":{"alias":"service"}}}""")
        assertEquals("202", LibraryFormat.json.parseToJsonElement(result).jsonObject["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, sends)
    }

    @Test fun blocksReservedDestinationsAndSensitiveHeaders() {
        for (literal in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "192.0.2.1", "198.18.0.1", "::1", "fc00::1", "2001:db8::1"))
            assertFalse(literal, PluginHttpTransport.isPublicAddress(InetAddress.getByName(literal)))
        assertTrue(PluginHttpTransport.isPublicAddress(InetAddress.getByName("8.8.8.8")))
        try { PluginHttpTransport.validateHeaders(mapOf("Authorization" to "raw secret")); fail() }
        catch (_: PluginHostFailure) { }
        try { PluginHttpTransport.validateHeaders(mapOf("X-API-Key" to "raw secret")); fail() }
        catch (_: PluginHostFailure) { }
    }
}
