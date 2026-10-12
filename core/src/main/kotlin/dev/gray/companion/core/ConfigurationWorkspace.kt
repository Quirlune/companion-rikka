package dev.gray.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable enum class ConfigurationWorkspaceStatus { OPEN, COMMITTED, DISCARDED }
@Serializable enum class ConfigurationWorkspaceResultStatus { OK, INVALID, STALE, CLOSED }

/** Local immutable authoring state. It is never serialized into a Library or sent as agent context. */
data class ConfigurationWorkspace(
    val id: String,
    val label: String,
    val base: Library,
    val draft: Library,
    val revision: Long = 0,
    val status: ConfigurationWorkspaceStatus = ConfigurationWorkspaceStatus.OPEN,
    val fixedPreview: ConfigurationWorkspacePreview? = null,
    val receiptId: String? = null
)

data class ConfigurationWorkspacePreview(
    val revision: Long,
    val plan: ConfigurationPlan?,
    val issues: List<Issue> = emptyList()
)

data class ConfigurationWorkspaceResult(
    val workspace: ConfigurationWorkspace,
    val status: ConfigurationWorkspaceResultStatus,
    val issues: List<Issue> = emptyList()
) {
    val retryable: Boolean get() = status == ConfigurationWorkspaceResultStatus.STALE
}

data class ConfigurationWorkspacePlanResult(
    val plan: ConfigurationPlan?,
    val status: ConfigurationWorkspaceResultStatus,
    val issues: List<Issue> = emptyList()
) {
    val retryable: Boolean get() = status == ConfigurationWorkspaceResultStatus.STALE
}

/** Pure draft operations. The host alone applies a fixed plan under its admission mutex. */
object ConfigurationWorkspaces {
    val editableKinds: Set<String> = setOf("character", "mainCall", "package", "group", "persona", "tag", "extension")
    const val MAX_COMMAND_CHARS = 2 * 1024 * 1024

    fun open(base: Library, label: String = "配置工作台", id: String = newId()): ConfigurationWorkspace =
        ConfigurationWorkspace(id, label.ifBlank { "配置工作台" }, base, base)

    fun stageCommands(workspace: ConfigurationWorkspace, expectedRevision: Long, text: String,
                      additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationWorkspaceResult {
        guard(workspace, expectedRevision)?.let { return it }
        if (text.length > MAX_COMMAND_CHARS) return invalid(workspace, "workspace.commands", "命令文本超过 2,097,152 字符限额")
        val document = try { LibraryFormat.json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: return invalid(workspace, "workspace.commands", "命令须为配置 JSON 对象")
        scopeIssues(document).takeIf { it.isNotEmpty() }?.let {
            return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, it)
        }
        val prepared = ConfigurationCommands.preview(document, workspace.draft, additionalValidation)
        val plan = prepared.plan ?: return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, prepared.issues)
        val allowed = planIssues(plan)
        if (allowed.isNotEmpty()) return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, allowed)
        val applied = ConfigurationCommands.apply(plan, workspace.draft, additionalValidation)
        val candidate = applied.library ?: return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, applied.issues)
        return staged(workspace, candidate)
    }

    fun stageField(workspace: ConfigurationWorkspace, expectedRevision: Long, target: ExtensionFieldRef,
                   expectedValue: String?, value: String?,
                   additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationWorkspaceResult {
        guard(workspace, expectedRevision)?.let { return it }
        val edited = ExtensionFieldRegistry.writeDraft(workspace.draft, target, value, expectedValue)
        val candidate = edited.library ?: return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, edited.issues)
        val prepared = ConfigurationCommands.previewEdit(workspace.draft, candidate, workspace.label, additionalValidation)
        val plan = prepared.plan ?: return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, prepared.issues)
        val allowed = planIssues(plan)
        if (allowed.isNotEmpty()) return ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, allowed)
        val applied = ConfigurationCommands.apply(plan, workspace.draft, additionalValidation)
        return applied.library?.let { staged(workspace, it) }
            ?: ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, applied.issues)
    }

    fun preview(workspace: ConfigurationWorkspace, expectedRevision: Long,
                additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationWorkspaceResult {
        guard(workspace, expectedRevision)?.let { return it }
        val preview = ConfigurationCommands.previewEdit(workspace.base, workspace.draft, workspace.label, additionalValidation)
        val issues = preview.issues + preview.plan?.let(::planIssues).orEmpty()
        val plan = preview.plan?.takeIf { issues.none { it.error } }
        return ConfigurationWorkspaceResult(workspace.copy(fixedPreview = ConfigurationWorkspacePreview(workspace.revision, plan, issues)),
            if (plan == null) ConfigurationWorkspaceResultStatus.INVALID else ConfigurationWorkspaceResultStatus.OK, issues)
    }

    /** This does not commit. Apply the returned plan to the latest live library, validate, then save once. */
    fun commitPlan(workspace: ConfigurationWorkspace, expectedRevision: Long): ConfigurationWorkspacePlanResult {
        guard(workspace, expectedRevision)?.let { return ConfigurationWorkspacePlanResult(null, it.status, it.issues) }
        val preview = workspace.fixedPreview
        if (preview == null || preview.revision != expectedRevision) return ConfigurationWorkspacePlanResult(null,
            ConfigurationWorkspaceResultStatus.STALE, listOf(Issue("workspace.preview", "请检查当前草稿后再应用")))
        val plan = preview.plan ?: return ConfigurationWorkspacePlanResult(null, ConfigurationWorkspaceResultStatus.INVALID, preview.issues)
        val issues = preview.issues + planIssues(plan)
        return ConfigurationWorkspacePlanResult(plan.takeIf { issues.none { it.error } },
            if (issues.any { it.error }) ConfigurationWorkspaceResultStatus.INVALID else ConfigurationWorkspaceResultStatus.OK, issues)
    }

    fun committed(workspace: ConfigurationWorkspace, receiptId: String?): ConfigurationWorkspace =
        workspace.copy(status = ConfigurationWorkspaceStatus.COMMITTED, receiptId = receiptId, fixedPreview = null)

    /** Portable cumulative draft, including edits made through native field controls. Not a commit token. */
    fun exportCommands(workspace: ConfigurationWorkspace): String? {
        val plan = ConfigurationCommands.previewEdit(workspace.base, workspace.draft, workspace.label).plan ?: return null
        if (planIssues(plan).isNotEmpty()) return null
        return buildJsonObject {
            put("format", ConfigurationCommands.FORMAT)
            put("label", workspace.label)
            put("commands", buildJsonArray {
                plan.changes.forEach { change -> add(buildJsonObject {
                    put("op", if (change.after == null) "remove" else "put")
                    put("kind", change.kind)
                    if (change.after == null) put("id", change.id) else put("value", change.after)
                }) }
            })
        }.toString()
    }

    fun discard(workspace: ConfigurationWorkspace): ConfigurationWorkspace =
        if (workspace.status == ConfigurationWorkspaceStatus.OPEN) workspace.copy(status = ConfigurationWorkspaceStatus.DISCARDED, fixedPreview = null)
        else workspace

    private fun staged(workspace: ConfigurationWorkspace, candidate: Library) = ConfigurationWorkspaceResult(
        workspace.copy(draft = candidate, revision = workspace.revision + 1, fixedPreview = null), ConfigurationWorkspaceResultStatus.OK)

    private fun guard(workspace: ConfigurationWorkspace, expectedRevision: Long): ConfigurationWorkspaceResult? = when {
        workspace.status != ConfigurationWorkspaceStatus.OPEN -> ConfigurationWorkspaceResult(workspace,
            ConfigurationWorkspaceResultStatus.CLOSED, listOf(Issue("workspace", "工作区已应用或放弃，请新建工作区")))
        workspace.revision != expectedRevision -> ConfigurationWorkspaceResult(workspace,
            ConfigurationWorkspaceResultStatus.STALE, listOf(Issue("workspace.revision", "草稿已改变，请重新读取当前版本")))
        else -> null
    }

    private fun invalid(workspace: ConfigurationWorkspace, path: String, message: String) =
        ConfigurationWorkspaceResult(workspace, ConfigurationWorkspaceResultStatus.INVALID, listOf(Issue(path, message)))

    private fun planIssues(plan: ConfigurationPlan): List<Issue> = buildList {
        plan.changes.filter { it.kind !in editableKinds }.forEach { add(Issue("workspace.${it.kind}", "工作台只编辑设计资源；连接与模型配置请使用原生设置")) }
        if (plan.settings.isNotEmpty()) add(Issue("workspace.settings", "工作台不能修改全局设置或会话选择"))
    }

    /** Reject even no-op requests against privileged resources before decoding their values. */
    private fun scopeIssues(document: JsonObject): List<Issue> = buildList {
        (document["resources"] as? JsonObject)?.keys?.forEach { array ->
            val kind = ResourceKindCatalog.arrays[array]
            if (kind != null && kind !in editableKinds) add(Issue("config.resources.$array", "工作台不开放此资源类型"))
        }
        (document["commands"] as? JsonArray)?.forEachIndexed { index, element ->
            val command = element as? JsonObject ?: return@forEachIndexed
            val op = (command["op"] as? JsonPrimitive)?.content
            val kind = (command["kind"] as? JsonPrimitive)?.content
            if (op == "select" || (kind != null && kind !in editableKinds))
                add(Issue("config.commands[$index]", "工作台只编辑设计资源，不修改连接、模型或全局设置"))
        }
    }
}
