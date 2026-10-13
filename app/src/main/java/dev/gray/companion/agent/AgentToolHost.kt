package dev.gray.companion.agent

import dev.gray.companion.ConfigurationToolGateway
import dev.gray.companion.core.ConfigurationAuthoringSchema
import dev.gray.companion.core.ConfigurationContext
import dev.gray.companion.core.PluginCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A per-conversation allowlist. It owns no controller, credential, model transport or chat Job. */
class AgentToolHost(
    private val files: AgentFiles,
    private val configuration: suspend (JsonObject) -> JsonObject,
    private val promptPreview: suspend (JsonObject) -> JsonObject,
    private val ownedWorkspaceIds: MutableSet<String>,
    private val knowledge: AgentKnowledge = AgentKnowledge.unavailable(),
    private val allowCommit: () -> Boolean = { false }
) {
    suspend fun execute(operation: String, args: JsonObject): JsonObject = try {
        val result = when (operation) {
            "describe" -> ok(describe())
            "config" -> config(args)
            "prompt.preview" -> preview(args)
            in KNOWLEDGE_TOOLS -> knowledge.execute(operation, args)
            in FILE_TOOLS -> files.execute(operation, args)
            else -> problem("UNKNOWN_TOOL", "未知工具")
        }
        if (result.toString().toByteArray(Charsets.UTF_8).size >= MAX_RESULT_BYTES)
            problem("RESULT_TOO_LARGE", "结果超过工具上限；请缩小查询、读取更小文件或指定目标资源")
        else result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { problem("TOOL_FAILED", "工具未完成，请检查参数并重读当前状态") }

    fun describe(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            add(buildJsonObject { put("name", "config"); put("operations", JsonArray(CONFIG_TOOLS.sorted().map { JsonPrimitive(it) })) })
            add(buildJsonObject { put("name", "prompt.preview"); put("arguments", "mode=preview|recent (默认preview); preview: sessionId, workflowId?, nodeId?, input?, workspaceId?; recent: sessionId?筛选, limit?=1..10") })
            add(buildJsonObject { put("name", "knowledge.list"); put("arguments", buildJsonObject {}); put("notes", "列出打包在应用内的只读文档 ID。") })
            add(buildJsonObject { put("name", "knowledge.read"); put("arguments", buildJsonObject { put("id", "agent-guide"); put("offset", 0); put("maxChars", 8000) }); put("notes", "仅接受 knowledge.list 的 ID；按字符偏移分段读取，单次最多16000字符。") })
            add(buildJsonObject { put("name", "knowledge.search"); put("arguments", buildJsonObject { put("query", "提示词"); put("id", "resource-guide"); put("limit", 8) }); put("notes", "id可省略；最多20个有限片段，返回命中offset。") })
            fileExamples.forEach { add(it) }
        })
        put("knowledgeIndex", knowledge.index())
        put("configManifest", kotlinx.serialization.json.Json.encodeToJsonElement(ConfigurationContext.manifest()))
        put("configExamples", JsonArray(ConfigurationToolGateway.toolExamples.filter {
            it.string("op") in CONFIG_TOOLS && it.string("op") != "list"
        }))
        put("resourceSchema", ConfigurationAuthoringSchema.describe())
        put("pluginCapabilities", PluginCapabilities.manifest())
        put("fileLimits", "每会话私有根；相对路径最多256字符、8层，禁用绝对路径、..、隐藏路径和符号链接；只读写UTF-8文本；单文件256KiB、总量8MiB、最多128文件。读取成功返回hash；修改、移动、删除须以当前SHA-256 hash做CAS；写入通过原子替换。")
        put("contract", "config 草稿仅限当前 Agent 会话拥有的 workspaceId；文件路径仅限此会话私有根。写文件须带 expectedHash；新建为 null。正式应用需用户启用的 commit 权限，BUSY 保留草稿。提示词预览不会发送或保存聊天。")
    }

    private suspend fun config(args: JsonObject): JsonObject {
        val op = args.string("op") ?: return problem("INVALID", "config.op 必填")
        if (op !in CONFIG_TOOLS) return problem("UNKNOWN_TOOL", "配置操作未开放")
        val workspaceId = args.string("workspaceId")
        if ("workspaceId" in args && workspaceId == null) return problem("INVALID", "workspaceId 须为字符串")
        if (workspaceId != null && workspaceId !in ownedWorkspaceIds)
            return problem("NOT_FOUND", "工作区不属于当前 Agent 会话")
        if (op in WORKSPACE_TOOLS && workspaceId == null)
            return problem("INVALID", "此操作需要 workspaceId")
        if (op == "commit" && !allowCommit())
            return problem("APPROVAL_REQUIRED", "草稿已保留；请在原生工作台检查并应用")
        val result = configuration(args)
        if (op == "open" && result.string("status") == "OK") {
            val created = result.string("workspaceId") ?: return problem("TOOL_FAILED", "工作区返回缺少 ID")
            ownedWorkspaceIds += created
        }
        if (op != "list" || result.string("status") != "OK") return result
        val data = result["data"] as? JsonArray ?: return problem("TOOL_FAILED", "工作区列表不可用")
        return buildJsonObject {
            result.forEach { (key, value) ->
                put(key, if (key == "data") JsonArray(data.filter { (it as? JsonObject)?.string("id") in ownedWorkspaceIds }) else value)
            }
        }
    }

    private suspend fun preview(args: JsonObject): JsonObject {
        val mode = args.string("mode") ?: "preview"
        if (mode !in setOf("preview", "recent")) return problem("INVALID", "mode 只能是 preview 或 recent")
        val sessionId = args.string("sessionId")
        if (mode == "preview" && sessionId.isNullOrBlank()) return problem("INVALID", "预览需要 sessionId")
        for (key in listOf("mode", "sessionId", "workflowId", "nodeId", "input", "workspaceId"))
            if (key in args && args.string(key) == null) return problem("INVALID", "$key 须为字符串")
        if (mode == "recent" && "limit" in args) {
            val limit = (args["limit"] as? JsonPrimitive)?.intOrNull
                ?: return problem("INVALID", "limit 须为 1 到 10")
            if (limit !in 1..10) return problem("INVALID", "limit 须为 1 到 10")
        }
        val workspaceId = args.string("workspaceId")
        if (workspaceId != null && workspaceId !in ownedWorkspaceIds)
            return problem("NOT_FOUND", "工作区不属于当前 Agent 会话")
        if ((args.string("input")?.length ?: 0) > MAX_PREVIEW_INPUT_CHARS)
            return problem("LIMIT", "预览输入过长")
        return promptPreview(args)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        private const val MAX_RESULT_BYTES = 512 * 1024
        private const val MAX_PREVIEW_INPUT_CHARS = 32_000
        private val KNOWLEDGE_TOOLS = setOf("knowledge.list", "knowledge.read", "knowledge.search")
        private val FILE_TOOLS = setOf("file.list", "file.read", "file.write", "file.edit", "file.move", "file.delete")
        private val fileExamples: List<JsonObject> = listOf(
            """{"name":"file.list","arguments":{"path":""},"returns":{"path":"","entries":[{"name":"notes.md","directory":false,"bytes":12}]},"notes":"path可省略，默认根目录；仅列直接子项。"}""",
            """{"name":"file.read","arguments":{"path":"notes.md"},"returns":{"path":"notes.md","hash":"<sha256>","bytes":12,"text":"文件文本"}}""",
            """{"name":"file.write","arguments":{"path":"notes.md","text":"新文本","expectedHash":null},"returns":{"path":"notes.md","hash":"<new-sha256>","bytes":9},"notes":"新建必须显式传 expectedHash:null；覆盖已有文件必须传 file.read 的当前hash。"}""",
            """{"name":"file.edit","arguments":{"path":"notes.md","expectedHash":"<current-sha256>","oldText":"旧片段","newText":"新片段","replaceAll":false},"returns":{"path":"notes.md","hash":"<new-sha256>","replacements":1},"notes":"oldText不得为空；出现多次时需明确replaceAll:true，否则AMBIGUOUS。"}""",
            """{"name":"file.move","arguments":{"from":"notes.md","to":"archive/notes.md","expectedHash":"<current-sha256>"},"returns":{"from":"notes.md","to":"archive/notes.md","hash":"<current-sha256>"},"notes":"目标须不存在；目标字段为to，不是newPath。"}""",
            """{"name":"file.delete","arguments":{"path":"notes.md","expectedHash":"<current-sha256>"},"returns":{"path":"notes.md","deletedHash":"<current-sha256>"}}"""
        ).map { Json.parseToJsonElement(it).jsonObject }
        private val WORKSPACE_TOOLS = setOf("stage", "field", "preview", "commit", "export", "discard")
        private val CONFIG_TOOLS = setOf("describe", "open", "list", "schema", "resources", "resource", "fields", "readField",
            "actions", "pluginCapabilities", "pluginAccess", "models", "sessions", "context", "sessionResource", "usages") + WORKSPACE_TOOLS

        private fun ok(data: JsonObject) = buildJsonObject { put("status", "OK"); put("data", data) }
        private fun problem(status: String, message: String) = buildJsonObject {
            put("status", status); put("retryable", false); put("message", message)
        }
    }
}
