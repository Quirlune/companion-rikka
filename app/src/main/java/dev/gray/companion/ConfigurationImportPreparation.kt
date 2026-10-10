package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A fixed import candidate. Only AppController owns and publishes the current candidate. */
internal sealed interface ConfigurationImportDraft {
    val id: String
    val warnings: List<Issue>

    data class Configuration(
        override val id: String,
        val plan: ConfigurationPlan,
        override val warnings: List<Issue>,
    ) : ConfigurationImportDraft

    data class Legacy(
        override val id: String,
        val imported: Library,
        val selectSession: Boolean,
        override val warnings: List<Issue>,
    ) : ConfigurationImportDraft
}

internal data class ConfigurationImportPreparation(
    val draft: ConfigurationImportDraft?,
    val preview: ConfigurationImportPreview,
    val issues: List<Issue>,
)

/** Decode and validate without publishing state or writing to the repository. */
internal object ConfigurationImportPreparer {
    fun prepare(jsonText: String, current: Library, id: String, selectImportedSession: Boolean): ConfigurationImportPreparation {
        val document = LibraryFormat.json.parseToJsonElement(jsonText) as? JsonObject
            ?: error("资源文件须为对象")
        return prepare(document, current, id, selectImportedSession)
    }

    fun prepare(document: JsonObject, current: Library, id: String, selectImportedSession: Boolean,
                sourceIssues: List<Issue> = emptyList()): ConfigurationImportPreparation {
        val prepared = prepareDocument(document, current, id, selectImportedSession)
        if (sourceIssues.isEmpty()) return prepared
        val issues = prepared.issues + sourceIssues
        val draft = prepared.draft?.takeIf { sourceIssues.none { it.error } }?.let {
            when (it) {
                is ConfigurationImportDraft.Configuration -> it.copy(warnings = it.warnings + sourceIssues.filterNot { issue -> issue.error })
                is ConfigurationImportDraft.Legacy -> it.copy(warnings = it.warnings + sourceIssues.filterNot { issue -> issue.error })
            }
        }
        return prepared.copy(draft = draft, preview = prepared.preview.copy(issues = prepared.preview.issues + sourceIssues), issues = issues)
    }

    private fun prepareDocument(document: JsonObject, current: Library, id: String, selectImportedSession: Boolean): ConfigurationImportPreparation {
        val format = (document["format"] as? JsonPrimitive)?.content
        if (format == "companion.config.v1") {
            val preview = ConfigurationCommands.preview(document, current, ConfigurationAdmission::validateNative)
            val issues = preview.issues
            return ConfigurationImportPreparation(
                preview.plan?.let { ConfigurationImportDraft.Configuration(id, it, issues.filterNot { issue -> issue.error }) },
                ConfigurationImportPreview(id, preview.plan?.label ?: "配置导入", preview.changes, preview.settings,
                    issues + sourceSummary("原生声明式配置或已展开的 ZIP 资源", preview.changes), undoable = true),
                issues,
            )
        }

        if ("format" in document) {
            val issues = listOf(Issue("import.format", "不支持的导入格式；请使用标准角色卡、PNG、原生资源/聊天备份或 companion.config.v1/ZIP 配置包"))
            return ConfigurationImportPreparation(null,
                ConfigurationImportPreview(id, "导入格式不受支持", emptyList(), emptyList(), issues, undoable = false), issues)
        }
        val imported = LibraryFormat.upgrade(ModelCatalog.normalize(LibraryFormat.decode(document)).withStandardCharacterFields())
        val hasChats = imported.sessions.isNotEmpty() || imported.chatFolders.isNotEmpty() || imported.savedChats.isNotEmpty()
        if (!hasChats) {
            val preview = ConfigurationCommands.previewResources(imported, current, additionalValidation = ConfigurationAdmission::validateNative)
            val issues = preview.issues
            return ConfigurationImportPreparation(
                preview.plan?.let { ConfigurationImportDraft.Configuration(id, it, issues.filterNot { issue -> issue.error }) },
                ConfigurationImportPreview(id, "资源导入", preview.changes, preview.settings,
                    issues + sourceSummary("原生资源文件", preview.changes), undoable = true),
                issues,
            )
        }

        val combined = LibraryFormat.upgrade(mergeLegacyImport(current, imported, selectImportedSession))
        // Reuse the ordinary resource differ, with archived chats present only for reference
        // validation. This does not apply its configuration plan or offer a backup undo receipt.
        val resources = imported.copy(sessions = emptyList(), chatFolders = emptyList(), savedChats = emptyList(), selectedSessionId = null)
        val previewBase = current.copy(sessions = combined.sessions, chatFolders = combined.chatFolders,
            savedChats = combined.savedChats)
        val resourcePreview = ConfigurationCommands.previewResources(resources, previewBase,
            additionalValidation = ConfigurationAdmission::validateNative)
        val issues = ConfigurationAdmission.validate(combined) + resourcePreview.issues + Issue("import.chats",
            "这份备份含聊天或收藏；会一并导入，不能用配置撤回移除聊天", error = false)
        val draft = if (issues.none { it.error }) ConfigurationImportDraft.Legacy(
            id, imported, selectImportedSession, issues.filterNot { it.error }) else null
        val summary = Issue("import.summary",
            "角色 ${imported.characters.size} · 主调用 ${imported.mainCalls.size} · 提示词包 ${imported.packages.size} · 调用组 ${imported.groups.size} · 聊天 ${imported.sessions.size} · 收藏 ${imported.savedChats.size}",
            error = false)
        return ConfigurationImportPreparation(
            draft,
            ConfigurationImportPreview(id, "资源与聊天备份", resourcePreview.changes, resourcePreview.settings,
                issues + summary + sourceSummary("原生聊天备份", resourcePreview.changes),
                undoable = false),
            issues,
        )
    }

    private fun sourceSummary(source: String, changes: List<ConfigurationChange>): Issue {
        val callCount = changes.filter { it.kind == "mainCall" || it.kind == "group" }.sumOf {
            ((it.after as? JsonObject)?.get("workflows") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        }
        return Issue("import.source", "来源：$source · 主调用/调用组内调用配置 $callCount 项。" +
            if (callCount > 0) "应用会保存这些调用配置；导入不会启动模型。" else "此预览没有新增主调用或调用组内的调用。", error = false)
    }

    fun apply(draft: ConfigurationImportDraft, current: Library): ConfigurationApplyResult = when (draft) {
        is ConfigurationImportDraft.Configuration -> ConfigurationCommands.apply(draft.plan, current, ConfigurationAdmission::validateNative)
        is ConfigurationImportDraft.Legacy -> {
            val merged = LibraryFormat.upgrade(mergeLegacyImport(current, draft.imported, draft.selectSession))
            val issues = ConfigurationAdmission.validate(merged)
            ConfigurationApplyResult(merged.takeIf { issues.none { it.error } }, null, issues)
        }
    }

    private fun mergeLegacyImport(old: Library, imported: Library, selectSession: Boolean): Library {
        val existingTags = old.promptTags.toSet()
        return old.copy(characters = old.characters + imported.characters, mainCalls = old.mainCalls + imported.mainCalls,
            packages = old.packages + imported.packages, groups = old.groups + imported.groups,
            profiles = old.profiles + imported.profiles, sessions = old.sessions + imported.sessions,
            connections = old.connections + imported.connections, chatFolders = old.chatFolders + imported.chatFolders,
            savedChats = old.savedChats + imported.savedChats, personas = old.personas + imported.personas,
            promptTags = old.promptTags + imported.promptTags.filterNot { it in existingTags },
            extensions = old.extensions + imported.extensions,
            selectedSessionId = if (selectSession) imported.selectedSessionId ?: old.selectedSessionId else old.selectedSessionId)
    }
}
