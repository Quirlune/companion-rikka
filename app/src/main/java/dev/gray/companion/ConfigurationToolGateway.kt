package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** In-process configuration tools shared by the human console and a future agent host. */
class ConfigurationToolGateway(private val controller: AppController) {
    private val json = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = true }

    suspend fun execute(text: String): String {
        if (text.length > ConfigurationWorkspaces.MAX_COMMAND_CHARS)
            return json.encodeToString(failure("INVALID", "请求超过字符限额"))
        val request = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        return json.encodeToString(request?.let { execute(it) } ?: failure("INVALID", "请求须为 JSON 对象"))
    }

    suspend fun execute(request: JsonObject): JsonObject = try {
        val op = request.string("op") ?: error("op")
        when (op) {
            "describe" -> success(buildJsonObject {
                put("manifest", json.encodeToJsonElement(ConfigurationContext.manifest()))
                put("tools", json.encodeToJsonElement(toolExamples))
                put("pluginCapabilities", PluginCapabilities.manifest())
                put("pluginAccess", "插件声明随资源编辑；设备授权和密钥仅由原生扩展设置管理。工具可查询状态，不能授权或读取密钥。")
                put("contract", "读取可不带 workspaceId，表示活资源；带 workspaceId 表示隔离草稿。修改必须携带当前 expectedRevision。preview 返回累计差异，commit 仅接受同版本预览。BUSY/STALE 可在等待或重读后重试。")
            })
            "open" -> operation(controller.openConfigurationWorkspace(request.string("label") ?: "配置工作台"))
            "list" -> success(JsonArray(controller.configurationWorkspaces.value.map(::summary)))
            "schema" -> ConfigurationAuthoringSchema.describe(request.string("type")).let {
                if ("error" in it) failure("NOT_FOUND", "此类型不属于工作台资源；不带 type 查看根类型") else success(it)
            }
            "stage", "field", "preview", "commit", "discard" -> {
                val id = requireNotNull(request.string("workspaceId"))
                val revision = requireNotNull((request["expectedRevision"] as? JsonPrimitive)?.longOrNull)
                operation(when (op) {
                    "stage" -> controller.stageConfigurationWorkspace(id, revision,
                        requireNotNull(request["commands"]).let { if (it is JsonPrimitive && it.isString) it.content else it.toString() })
                    "field" -> {
                        require("expectedValue" in request && "value" in request)
                        controller.stageConfigurationField(id, revision,
                            json.decodeFromJsonElement(requireNotNull(request["target"])),
                            request.nullableValue("expectedValue"), request.nullableValue("value"))
                    }
                    "preview" -> controller.previewConfigurationWorkspace(id, revision)
                    "commit" -> controller.commitConfigurationWorkspace(id, revision)
                    else -> controller.discardConfigurationWorkspace(id, revision)
                })
            }
            else -> read(op, request)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: IllegalArgumentException) { failure("INVALID", "请求参数无效；用 describe 查看工具及参数示例") }
    catch (_: IllegalStateException) { failure("INVALID", "请求缺少必要参数；用 describe 查看工具及参数示例") }
    catch (_: Exception) { failure("FAILED", "操作未完成，请读取当前工作区状态后重试") }

    private fun read(op: String, request: JsonObject): JsonObject {
        val id = request.string("workspaceId")
        val workspace = id?.let { key -> controller.configurationWorkspaces.value.firstOrNull { it.id == key } }
        if (id != null && workspace == null) return failure("NOT_FOUND", "草稿已不存在")
        val library = workspace?.draft ?: controller.library.value
        val data: JsonElement? = when (op) {
            "resources" -> json.encodeToJsonElement(ConfigurationContext.listResources(library, request.string("kind"), request.string("query").orEmpty()))
            "resource" -> ConfigurationContext.readResource(library, requireNotNull(request.string("kind")), requireNotNull(request.string("id")))
            "fields" -> json.encodeToJsonElement(ConfigurationContext.fields(library,
                json.decodeFromJsonElement<ExtensionResourceRef>(requireNotNull(request["resource"]))))
            "readField" -> ConfigurationContext.readField(library,
                json.decodeFromJsonElement<ExtensionFieldRef>(requireNotNull(request["target"])))?.let { json.encodeToJsonElement(it) }
            "actions" -> json.encodeToJsonElement(controller.actionDescriptors)
            "pluginCapabilities" -> PluginCapabilities.manifest()
            "pluginAccess" -> library.extensions.firstOrNull { it.id == request.string("id") }?.let {
                json.encodeToJsonElement(controller.pluginAccessStatus(it))
            }
            "models" -> json.encodeToJsonElement(ConfigurationContext.models(library))
            // Conversation reads use a current live snapshot; resource reads explicitly expose its frozen world.
            "sessions" -> json.encodeToJsonElement(ConfigurationContext.sessions(controller.library.value))
            "context" -> ConfigurationContext.sessionContext(controller.library.value, requireNotNull(request.string("sessionId")),
                (request["maxMessages"] as? JsonPrimitive)?.intOrNull ?: 20,
                (request["maxChars"] as? JsonPrimitive)?.intOrNull ?: 16_000)?.let { json.encodeToJsonElement(it) }
            "sessionResource" -> ConfigurationContext.readSessionResource(controller.library.value,
                requireNotNull(request.string("sessionId")), requireNotNull(request.string("kind")), requireNotNull(request.string("id")))
            "usages" -> json.encodeToJsonElement(ConfigurationContext.usages(library,
                json.decodeFromJsonElement<ResourceLocation>(requireNotNull(request["location"]))))
            "export" -> workspace?.let(ConfigurationWorkspaces::exportCommands)?.let(json::parseToJsonElement)
            else -> return failure("UNKNOWN_TOOL", "未知工具；用 describe 查看可用操作")
        }
        return if (data == null) failure("NOT_FOUND", "对象不可用或超出工作台范围")
        else success(data, workspace)
    }

    private fun operation(result: WorkspaceOperationResult): JsonObject = buildJsonObject {
        put("status", result.status)
        put("retryable", result.retryable)
        put("issues", json.encodeToJsonElement(result.issues))
        result.workspace?.let { workspace ->
            put("workspaceId", workspace.id)
            put("revision", workspace.revision)
            put("data", summary(workspace))
        }
    }

    private fun summary(workspace: ConfigurationWorkspace): JsonObject = buildJsonObject {
        put("id", workspace.id)
        put("label", workspace.label)
        put("revision", workspace.revision)
        put("state", workspace.status.name)
        workspace.receiptId?.let { put("receiptId", it) }
        workspace.fixedPreview?.let { preview ->
            put("previewRevision", preview.revision)
            put("valid", preview.plan != null && preview.issues.none { it.error })
            put("changes", json.encodeToJsonElement(preview.plan?.changes.orEmpty()))
            put("issues", json.encodeToJsonElement(preview.issues))
        }
    }

    private fun success(data: JsonElement, workspace: ConfigurationWorkspace? = null) = buildJsonObject {
        put("status", "OK")
        put("retryable", false)
        put("data", data)
        workspace?.let { put("workspaceId", it.id); put("revision", it.revision) }
    }

    private fun failure(status: String, message: String) = buildJsonObject {
        put("status", status)
        put("retryable", false)
        put("issues", json.encodeToJsonElement(listOf(Issue("tool", message))))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.nullableValue(key: String): String? {
        val value = getValue(key)
        require(value == JsonNull || value is JsonPrimitive && value.isString)
        return if (value == JsonNull) null else (value as JsonPrimitive).content
    }

    companion object {
        /** Executable shapes; IDs and stable field targets come from discovery responses. */
        val toolExamples: List<JsonObject> = listOf(
            """{"op":"describe"}""",
            """{"op":"pluginCapabilities"}""",
            """{"op":"pluginAccess","id":"<扩展ID>"}""",
            """{"op":"open","label":"调整语气"}""",
            """{"op":"list"}""",
            """{"op":"schema"}""",
            """{"op":"schema","type":"WorkflowNode"}""",
            """{"op":"resources","query":"语气"}""",
            """{"op":"resource","kind":"package","id":"<资源ID>","workspaceId":"<草稿ID>"}""",
            """{"op":"fields","resource":{"kind":"PACKAGE","id":"<资源ID>"},"workspaceId":"<草稿ID>"}""",
            """{"op":"readField","target":{"kind":"PACKAGE","resourceId":"<资源ID>","field":"ENTRY_TEXT","entryId":"<条目ID>"},"workspaceId":"<草稿ID>"}""",
            """{"op":"actions"}""",
            """{"op":"models"}""",
            """{"op":"sessions"}""",
            """{"op":"context","sessionId":"<聊天ID>","maxMessages":20,"maxChars":16000}""",
            """{"op":"sessionResource","sessionId":"<聊天ID>","kind":"package","id":"<资源ID>"}""",
            """{"op":"usages","location":{"resource":{"kind":"PACKAGE","id":"<资源ID>"}}}""",
            """{"op":"stage","workspaceId":"<草稿ID>","expectedRevision":0,"commands":${ConfigurationContext.exampleCommands()}}""",
            """{"op":"field","workspaceId":"<草稿ID>","expectedRevision":1,"target":{"kind":"PACKAGE","resourceId":"<资源ID>","field":"ENTRY_TEXT","entryId":"<条目ID>"},"expectedValue":"原文本","value":"新文本"}""",
            """{"op":"preview","workspaceId":"<草稿ID>","expectedRevision":2}""",
            """{"op":"commit","workspaceId":"<草稿ID>","expectedRevision":2}""",
            """{"op":"export","workspaceId":"<草稿ID>"}""",
            """{"op":"discard","workspaceId":"<草稿ID>","expectedRevision":2}""",
        ).map { LibraryFormat.json.parseToJsonElement(it).jsonObject }
    }
}
