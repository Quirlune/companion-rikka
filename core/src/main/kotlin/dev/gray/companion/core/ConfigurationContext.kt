package dev.gray.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable data class ConfigurationResourceSummary(
    val kind: String, val id: String, val name: String, val slug: String? = null,
    val fieldResource: ExtensionResourceRef? = null
)
@Serializable data class ConfigurationFieldValue(
    val target: ExtensionFieldRef, val title: String, val type: ActionFieldType,
    val required: Boolean, val nullable: Boolean, val integer: Boolean,
    val min: Double?, val max: Double?, val choices: List<ActionChoice>, val value: String?
)
@Serializable data class ConfigurationModelSummary(val id: String, val name: String, val model: String)
@Serializable data class ConfigurationSessionSummary(val id: String, val title: String, val selected: Boolean, val frozen: Boolean)
@Serializable data class ConfigurationContextMessage(
    val id: String, val role: Role, val text: String, val speakerName: String?, val truncated: Boolean = false
)
@Serializable data class ConfigurationSessionContext(
    val sessionId: String, val title: String, val characterId: String, val mainCallId: String,
    val packageIds: List<String>, val groupIds: List<String>, val resourceWorld: String,
    val messages: List<ConfigurationContextMessage>, val truncated: Boolean
)
@Serializable data class ConfigurationResourceUse(
    val target: ResourceLocation, val location: ResourceLocation?, val path: String,
    val label: String, val kind: String, val removableAssociation: Boolean
)
@Serializable data class ConfigurationWorkspaceCapability(val name: String, val description: String)
@Serializable data class ConfigurationWorkspaceManifest(
    val format: String, val editableKinds: List<String>, val capabilities: List<ConfigurationWorkspaceCapability>,
    val commandExample: String, val limitations: List<String>
)

/** Explicit, serializable views. Never serialize the workspace's private base/draft Library as context. */
object ConfigurationContext {
    fun listResources(library: Library, kind: String? = null, query: String = ""): List<ConfigurationResourceSummary> =
        ResourceKindCatalog.ordered.filter { it.name in ConfigurationWorkspaces.editableKinds && (kind == null || it.name == kind) }
            .flatMap { descriptor -> descriptor.values(library).mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null
                val id = value.string("id") ?: return@mapNotNull null
                ConfigurationResourceSummary(descriptor.name, id, value.string("name").orEmpty(), value.string("slug"), fieldResource(descriptor.name, id))
            } }.filter { query.isBlank() || listOf(it.id, it.name, it.slug.orEmpty()).any { text -> text.contains(query, ignoreCase = true) } }

    /** Null means unavailable or outside the workspace scope; model choices have a separate safe projection. */
    fun readResource(library: Library, kind: String, id: String): JsonObject? =
        if (kind !in ConfigurationWorkspaces.editableKinds) null else ResourceKindCatalog.get(kind)?.find(library, id) as? JsonObject

    /** Read the definitions belonging to a frozen chat without accidentally substituting live editor resources. */
    fun readSessionResource(library: Library, sessionId: String, kind: String, id: String): JsonObject? {
        val session = library.sessions.firstOrNull { it.id == sessionId } ?: return null
        val resources = session.pendingRun?.frozenResources ?: session.executableWorld?.library ?: library
        return readResource(resources, kind, id)
    }

    fun fields(library: Library, resource: ExtensionResourceRef): List<ConfigurationFieldValue> =
        ExtensionFieldRegistry.list(library, resource).map { projectField(it, ExtensionFieldRegistry.read(library, it.target)) }

    fun readField(library: Library, target: ExtensionFieldRef): ConfigurationFieldValue? =
        ExtensionFieldRegistry.describe(library, target)?.let { projectField(it, ExtensionFieldRegistry.read(library, target)) }

    fun actions(): List<ActionDescriptor> = ActionRegistry.defaults().descriptors()

    fun models(library: Library): List<ConfigurationModelSummary> =
        library.profiles.map { ConfigurationModelSummary(it.id, it.name, it.model) }

    fun sessions(library: Library): List<ConfigurationSessionSummary> = library.sessions.map {
        ConfigurationSessionSummary(it.id, it.title, it.id == library.selectedSessionId, it.pendingRun != null || it.executableWorld != null)
    }

    /** Bounded BODY projection of one current visible branch; reasoning, raw text and recovery internals are omitted. */
    fun sessionContext(library: Library, sessionId: String, maxMessages: Int = 20, maxChars: Int = 16_000): ConfigurationSessionContext? {
        val session = library.sessions.firstOrNull { it.id == sessionId } ?: return null
        val body = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
        val chosen = body.takeLast(maxMessages.coerceIn(0, 100))
        var remaining = maxChars.coerceIn(0, 64_000)
        val messages = chosen.asReversed().mapNotNull { message ->
            if (remaining <= 0) return@mapNotNull null
            // Read the stored BODY text, never a raw provider response or reasoning archive.
            val text = message.text.take(remaining)
            remaining -= text.length
            ConfigurationContextMessage(message.id, message.role, text, message.speakerName, text.length < message.text.length)
        }.asReversed()
        return ConfigurationSessionContext(session.id, session.title, session.characterId, session.mainCallId,
            session.packageIds, session.groupIds,
            when { session.pendingRun != null -> "pendingRun.frozenResources"; session.executableWorld != null -> "session.executableWorld"; else -> "live" },
            messages, messages.size < body.size || messages.any { it.truncated })
    }

    fun usages(library: Library, location: ResourceLocation): List<ConfigurationResourceUse> =
        ResourceUsageIndex.usages(library, location).map {
            ConfigurationResourceUse(it.target, it.location, it.path, it.label, it.kind.name, it.removableAssociation)
        }

    fun exampleCommands(): String = """
        {
          "format": "${ConfigurationCommands.FORMAT}",
          "label": "新建示例提示词包",
          "resources": {
            "packages": [{
              "id": "workspace_example_package",
              "name": "工作台示例",
              "namespace": "workspace_example",
              "slug": "workspace_example",
              "entries": [{"id":"workspace_example_entry","key":"tone","title":"语气","text":"简洁、自然地回应。"}]
            }]
          }
        }
    """.trimIndent()

    fun manifest(): ConfigurationWorkspaceManifest = ConfigurationWorkspaceManifest(
        ConfigurationCommands.FORMAT, ConfigurationWorkspaces.editableKinds.toList(), listOf(
            ConfigurationWorkspaceCapability("listResources / readResource", "按类别、名称或 ID 查找并读取设计资源"),
            ConfigurationWorkspaceCapability("fields / readField", "读取带稳定子对象 ID、类型、选项和当前值的字段"),
            ConfigurationWorkspaceCapability("actions / models", "读取现有行为契约与可选模型摘要"),
            ConfigurationWorkspaceCapability("sessions / sessionContext / readSessionResource", "按需读取一个聊天的有限 BODY 上下文及其实际资源世界"),
            ConfigurationWorkspaceCapability("usages", "读取可静态识别的引用与删除影响"),
            ConfigurationWorkspaceCapability("stageCommands", "以现有配置 JSON 原子暂存一批资源改动，无须文件导入"),
            ConfigurationWorkspaceCapability("stageField", "按字段 ID 和预期原值编辑实际配置"),
            ConfigurationWorkspaceCapability("preview / commitPlan", "检查累计前后差异；宿主对最新资源复核后原子应用"),
            ConfigurationWorkspaceCapability("exportCommands", "复制累计改动为现有配置 JSON；再次暂存与应用仍须检查"),
            ConfigurationWorkspaceCapability("discard", "放弃工作区，不写入资源库")
        ), exampleCommands(), listOf(
            "工作区草稿仅在本次应用进程中保留；不会自动保存或运行配置中的代码。",
            "运行中可以读取和起草；应用遇忙须等待，不停止正在进行的调用。",
            "应用不会改写聊天、断点或已冻结分支；完整依赖读集一致性尚未提供。",
            "连接、凭据、模型配置与全局设置不在工作台编辑范围。",
            ResourceUsageIndex.LIMITATION
        )
    )

    private fun projectField(field: ExtensionFieldDescriptor, value: String?) = ConfigurationFieldValue(
        field.target, field.title, field.type, field.required, field.nullable, field.integer,
        field.min, field.max, field.choices, value)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun fieldResource(kind: String, id: String): ExtensionResourceRef? = when (kind) {
        "character" -> ExtensionResourceKind.CHARACTER
        "mainCall" -> ExtensionResourceKind.MAIN
        "package" -> ExtensionResourceKind.PACKAGE
        "group" -> ExtensionResourceKind.GROUP
        else -> null
    }?.let { ExtensionResourceRef(it, id) }
}
