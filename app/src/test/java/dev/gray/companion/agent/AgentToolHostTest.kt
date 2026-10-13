package dev.gray.companion.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolHostTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun workspacesArePrivateAndCommitNeedsExplicitGate() = runBlocking {
        val owned = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val host = AgentToolHost(AgentFiles(temporary.newFolder("files")), configuration = { request ->
            val op = request.getValue("op").jsonPrimitive.content
            calls += op
            when (op) {
                "open" -> result("OK", "workspaceId" to "mine")
                "list" -> buildJsonObject {
                    put("status", "OK")
                    put("data", JsonArray(listOf(args("""{"id":"mine"}"""), args("""{"id":"another"}"""))))
                }
                else -> result("OK")
            }
        }, promptPreview = { result("OK") }, ownedWorkspaceIds = owned)
        assertEquals("NOT_FOUND", host.execute("config", args("""{"op":"preview","workspaceId":"another","expectedRevision":0}""")).status())
        assertTrue(calls.isEmpty())
        assertEquals("OK", host.execute("config", args("""{"op":"open"}""")).status())
        assertTrue("mine" in owned)
        val listed = host.execute("config", args("""{"op":"list"}"""))
        assertEquals(1, (listed.getValue("data") as JsonArray).size)
        assertEquals("APPROVAL_REQUIRED", host.execute("config", args("""{"op":"commit","workspaceId":"mine","expectedRevision":0}""")).status())
        assertFalse("commit" in calls)
        assertEquals("NOT_FOUND", host.execute("prompt.preview", args("""{"sessionId":"chat","workspaceId":"another"}""")).status())
    }

    @Test fun describeNamesFileArgumentsAndHashContract() {
        val host = AgentToolHost(AgentFiles(temporary.newFolder("files")),
            configuration = { result("OK") }, promptPreview = { result("OK") }, ownedWorkspaceIds = mutableSetOf())
        val tools = host.describe().getValue("tools") as JsonArray
        fun tool(name: String): JsonObject = tools.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == name }
        val write = tool("file.write").getValue("arguments").jsonObject
        assertTrue("path" in write && "text" in write && "expectedHash" in write)
        val move = tool("file.move").getValue("arguments").jsonObject
        assertTrue("from" in move && "to" in move && "expectedHash" in move)
        assertFalse("newPath" in move)
    }

    private fun result(status: String, extra: Pair<String, String>? = null): JsonObject = buildJsonObject {
        put("status", status)
        extra?.let { put(it.first, it.second) }
    }
    private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.status(): String = getValue("status").jsonPrimitive.content
}
