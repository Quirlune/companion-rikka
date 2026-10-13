package dev.gray.companion.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentResponsesClientTest {
    @Test fun requestUsesStatelessStreamAndOneNamespacedStrictTool() {
        val client = AgentResponsesClient { "unused" }
        val input = listOf(Json.parseToJsonElement("""{"role":"user","content":"hello"}""").jsonObject)
        val body = client.requestBody("selected-model", "high", "Agent instructions", input)
        assertEquals("selected-model", body["model"].toString().trim('"'))
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        assertFalse(body.containsKey("previous_response_id"))
        val namespace = (body["tools"] as JsonArray).first() as JsonObject
        assertEquals("velune", namespace["name"].toString().trim('"'))
        val tool = (namespace["tools"] as JsonArray).first() as JsonObject
        assertEquals("true", tool["strict"].toString())
        val schema = tool["parameters"] as JsonObject
        assertEquals("false", schema["additionalProperties"].toString())
        assertEquals(2, (schema["required"] as JsonArray).size)
    }

    @Test fun streamPreservesReasoningAndToolCallOnlyAtCompleted() {
        val deltas = mutableListOf<String>()
        val stream = Buffer().writeUtf8(
            """event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"Hi"}

event: response.completed
data: {"type":"response.completed","response":{"output":[{"type":"reasoning","encrypted_content":"opaque"},{"type":"function_call","namespace":"velune","name":"invoke","call_id":"c1","arguments":"{\"operation\":\"list\",\"arguments\":\"{}\"}"},{"type":"message","content":[{"type":"output_text","text":"Hi"}]}],"usage":{"input_tokens":12,"output_tokens":8}}}

"""
        )
        val result = parseResponseStream(stream) { deltas += it }
        assertEquals("Hi", result.text)
        assertEquals(listOf("Hi"), deltas)
        assertEquals(3, result.output.size)
        assertEquals(12, result.inputTokens)
        assertEquals(8, result.outputTokens)
    }

    @Test fun failureIncompleteAndEofCannotSucceed() {
        val failed = Buffer().writeUtf8(
            """event: response.failed
data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}

"""
        )
        val failure = assertThrows(AgentResponseException::class.java) { parseResponseStream(failed) {} }
        assertEquals("subscription_sharing_usage_limit_exceeded", failure.errorCode)
        val incomplete = Buffer().writeUtf8("""event: response.incomplete
data: {"type":"response.incomplete"}

""")
        assertThrows(AgentResponseException::class.java) { parseResponseStream(incomplete) {} }
        val eof = Buffer().writeUtf8("""event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"partial"}

""")
        assertThrows(AgentResponseException::class.java) { parseResponseStream(eof) {} }
    }
}
