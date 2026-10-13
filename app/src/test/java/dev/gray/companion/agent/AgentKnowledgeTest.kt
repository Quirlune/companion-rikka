package dev.gray.companion.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AgentKnowledgeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val texts = mapOf(
        "AGENT_GUIDE.txt" to "先读指南。提示词编辑要看实际配置。",
        "AI_IMPORT_GUIDE.txt" to "提示词可见。提示词可编辑。",
        "AUTHORING_ARCHITECTURE.txt" to "当前架构：核心与原生前端分离。",
        "APPEARANCE_GUIDE.txt" to "文字分宽，插图全宽。",
        "PLUGIN_API.md" to "插件权限需要原生授权。",
        "AI_IMPORT_API_REFERENCE.json" to "{\"schema\":\"real\"}",
    )
    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private fun JsonObject.data() = getValue("data").jsonObject

    @Test fun fixedIdsBoundReadAndSearchWithoutOpeningArbitraryPaths() {
        val opened = mutableListOf<String>()
        val knowledge = AgentKnowledge { file -> opened += file; texts[file] }
        assertEquals("OK", knowledge.execute("knowledge.list", args("{}" )).status())
        val read = knowledge.execute("knowledge.read", args("""{"id":"agent-guide","offset":2,"maxChars":4}"""))
        assertEquals("OK", read.status())
        assertEquals(4, read.data().getValue("text").jsonPrimitive.content.length)
        assertEquals("INVALID", knowledge.execute("knowledge.read", args("""{"id":"../../secret","offset":0}""")).status())
        assertEquals("INVALID", knowledge.execute("knowledge.read", args("""{"id":"agent-guide","maxChars":16001}""")).status())
        val search = knowledge.execute("knowledge.search", args("""{"query":"提示词","limit":1}"""))
        assertEquals("OK", search.status())
        assertEquals(1, (search.data().getValue("matches") as JsonArray).size)
        assertTrue(opened.all { it in texts.keys })
    }

    @Test fun hostExposesPackagedKnowledgeThroughReadOnlyOperations() = runBlocking {
        val knowledge = AgentKnowledge { texts[it] }
        val host = AgentToolHost(AgentFiles(temporary.newFolder("files")),
            configuration = { args("""{"status":"OK"}""") },
            promptPreview = { args("""{"status":"OK"}""") },
            ownedWorkspaceIds = mutableSetOf(), knowledge = knowledge)
        val named = host.describe().getValue("knowledgeIndex") as JsonArray
        assertEquals(6, named.size)
        val appearance = host.execute("knowledge.read", args("""{"id":"appearance-guide"}"""))
        assertEquals("OK", appearance.status())
        assertTrue(appearance.data().getValue("text").jsonPrimitive.content.contains("插图全宽"))
        val architecture = host.execute("knowledge.search", args("""{"id":"architecture-guide","query":"原生前端"}"""))
        assertEquals(1, (architecture.data().getValue("matches") as JsonArray).size)
        val read = host.execute("knowledge.read", args("""{"id":"plugin-api"}"""))
        assertEquals("OK", read.status())
        assertTrue(read.data().getValue("text").jsonPrimitive.content.contains("原生授权"))
    }
}
