package dev.gray.companion.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Read-only packaged authoring material. IDs are fixed; no user path reaches the asset loader. */
class AgentKnowledge(private val load: (String) -> String?) {
    private data class Document(val id: String, val file: String, val title: String, val purpose: String)
    private val documents = listOf(
        Document("agent-guide", "AGENT_GUIDE.txt", "Agent 工作指南", "能力边界、工作步骤与离线查询入口"),
        Document("architecture-guide", "AUTHORING_ARCHITECTURE.txt", "当前架构与知识导航", "模块关系、数据流、资源与运行边界、按任务查资料"),
        Document("appearance-guide", "APPEARANCE_GUIDE.txt", "正文和卡片美化语法", "正文HTML/CSS、卡片模板/XML、文字与插图分宽和MENU展示"),
        Document("resource-guide", "AI_IMPORT_GUIDE.txt", "资源导入与编写指南", "资源、提示词、配置和导入流程"),
        Document("plugin-api", "PLUGIN_API.md", "插件接口指南", "插件 v1/v2、宿主能力与权限"),
        Document("schema-reference", "AI_IMPORT_API_REFERENCE.json", "资源 API 参考", "类型、字段、行为和机器可读参考"),
    )
    private val cache = ConcurrentHashMap<String, String>()

    fun index(): JsonArray = buildJsonArray {
        documents.forEach { document -> add(buildJsonObject {
            put("id", document.id); put("title", document.title); put("purpose", document.purpose)
        }) }
    }

    fun execute(operation: String, args: JsonObject): JsonObject = when (operation) {
        "knowledge.list" -> ok(buildJsonObject { put("documents", index()); put("offline", true) })
        "knowledge.read" -> read(args)
        "knowledge.search" -> search(args)
        else -> problem("UNKNOWN_TOOL", "未知知识工具")
    }

    private fun read(args: JsonObject): JsonObject {
        val document = document(args) ?: return problem("INVALID", "id 必须是 knowledge.list 列出的文档 ID")
        val offset = number(args, "offset", 0) ?: return problem("INVALID", "offset 须为非负整数")
        val maxChars = number(args, "maxChars", DEFAULT_READ_CHARS)
            ?: return problem("INVALID", "maxChars 须为 1 到 $MAX_READ_CHARS")
        if (offset < 0 || maxChars !in 1..MAX_READ_CHARS) return problem("INVALID", "读取范围无效")
        val content = content(document) ?: return problem("UNAVAILABLE", "内置资料不可读取")
        if (offset > content.length) return problem("INVALID", "offset 超出文档长度")
        if (offset in 1 until content.length && content[offset - 1].isHighSurrogate() && content[offset].isLowSurrogate())
            return problem("INVALID", "offset 不能位于字符中间")
        var end = (offset.toLong() + maxChars).coerceAtMost(content.length.toLong()).toInt()
        if (end < content.length && end > offset && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
        return ok(buildJsonObject {
            put("id", document.id); put("offset", offset); put("text", content.substring(offset, end))
            put("nextOffset", end); put("totalChars", content.length); put("hasMore", end < content.length)
        })
    }

    private fun search(args: JsonObject): JsonObject {
        val query = string(args, "query")?.trim()
            ?: return problem("INVALID", "query 须为 2 到 $MAX_QUERY_CHARS 个字符")
        if (query.length !in 2..MAX_QUERY_CHARS) return problem("INVALID", "query 须为 2 到 $MAX_QUERY_CHARS 个字符")
        val limit = number(args, "limit", DEFAULT_SEARCH_LIMIT)
            ?: return problem("INVALID", "limit 须为 1 到 $MAX_SEARCH_LIMIT")
        if (limit !in 1..MAX_SEARCH_LIMIT) return problem("INVALID", "limit 须为 1 到 $MAX_SEARCH_LIMIT")
        val selected = if ("id" in args) listOfNotNull(document(args)) else documents
        if (selected.isEmpty()) return problem("INVALID", "id 必须是 knowledge.list 列出的文档 ID")
        val matches = mutableListOf<JsonObject>()
        for (document in selected) {
            val content = content(document) ?: return problem("UNAVAILABLE", "内置资料不可读取")
            var from = 0
            while (matches.size < limit) {
                val at = content.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                val begin = (at - SNIPPET_RADIUS).coerceAtLeast(0)
                val end = (at + query.length + SNIPPET_RADIUS).coerceAtMost(content.length)
                matches += buildJsonObject {
                    put("id", document.id); put("offset", at)
                    put("snippetOffset", begin); put("snippet", content.substring(begin, end))
                }
                from = at + query.length
            }
            if (matches.size >= limit) break
        }
        return ok(buildJsonObject { put("query", query); put("matches", JsonArray(matches)); put("limitReached", matches.size == limit) })
    }

    private fun content(document: Document): String? {
        cache[document.id]?.let { return it }
        val loaded = try { load(document.file) } catch (_: Exception) { null } ?: return null
        if (loaded.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return null
        cache.putIfAbsent(document.id, loaded)
        return cache[document.id]
    }

    private fun document(args: JsonObject): Document? =
        string(args, "id")?.let { id -> documents.firstOrNull { it.id == id } }

    private fun string(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(args: JsonObject, key: String, default: Int): Int? =
        if (key !in args) default else (args[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    companion object {
        const val MAX_READ_CHARS = 16_000
        private const val DEFAULT_READ_CHARS = 8_000
        private const val MAX_QUERY_CHARS = 120
        private const val DEFAULT_SEARCH_LIMIT = 8
        private const val MAX_SEARCH_LIMIT = 20
        private const val SNIPPET_RADIUS = 90
        private const val MAX_DOCUMENT_BYTES = 512 * 1024

        fun unavailable() = AgentKnowledge { null }
        private fun ok(data: JsonObject) = buildJsonObject { put("status", "OK"); put("data", data) }
        private fun problem(status: String, message: String) = buildJsonObject {
            put("status", status); put("retryable", false); put("message", message)
        }
    }
}
