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
        val format = (document["format"] as? JsonPrimitive)?.content
        if (format == "companion.config.v1") {
            val preview = ConfigurationCommands.preview(jsonText, current)
            val issues = preview.issues
            return ConfigurationImportPreparation(
                preview.plan?.let { ConfigurationImportDraft.Configuration(id, it, issues.filterNot { issue -> issue.error }) },
                ConfigurationImportPreview(id, preview.plan?.label ?: "配置导入", preview.changes, preview.settings,
                    issues, undoable = true),
                issues,
            )
        }

        val decoded = if (format == "sillytavern.preview.v1") SillyTavernImport.convert(jsonText, current)
            else ImportResult(ModelCatalog.normalize(LibraryFormat.decode(jsonText)), emptyList())
        val imported = LibraryFormat.upgrade(decoded.library.withStandardCharacterFields())
        val warnings = decoded.warnings.map { Issue("import", it, error = false) }
        val hasChats = imported.sessions.isNotEmpty() || imported.chatFolders.isNotEmpty() || imported.savedChats.isNotEmpty()
        if (!hasChats) {
            val preview = ConfigurationCommands.previewResources(imported, current)
            val issues = preview.issues + warnings
            return ConfigurationImportPreparation(
                preview.plan?.let { ConfigurationImportDraft.Configuration(id, it, issues.filterNot { issue -> issue.error }) },
                ConfigurationImportPreview(id, "资源导入", preview.changes, preview.settings, issues, undoable = true),
                issues,
            )
        }

        val combined = LibraryFormat.upgrade(mergeLegacyImport(current, imported, selectImportedSession))
        val issues = LibraryValidator.validate(combined) + warnings + Issue("import.chats",
            "这份备份含聊天或收藏；会一并导入，不能用配置撤回移除聊天", error = false)
        val draft = if (issues.none { it.error }) ConfigurationImportDraft.Legacy(
            id, imported, selectImportedSession, issues.filterNot { it.error }) else null
        val summary = Issue("import.summary",
            "角色 ${imported.characters.size} · 主调用 ${imported.mainCalls.size} · 提示词包 ${imported.packages.size} · 调用组 ${imported.groups.size} · 聊天 ${imported.sessions.size} · 收藏 ${imported.savedChats.size}",
            error = false)
        return ConfigurationImportPreparation(
            draft,
            ConfigurationImportPreview(id, "资源与聊天备份", emptyList(), emptyList(), issues + summary, undoable = false),
            issues,
        )
    }

    fun apply(draft: ConfigurationImportDraft, current: Library): ConfigurationApplyResult = when (draft) {
        is ConfigurationImportDraft.Configuration -> ConfigurationCommands.apply(draft.plan, current)
        is ConfigurationImportDraft.Legacy -> {
            val merged = LibraryFormat.upgrade(mergeLegacyImport(current, draft.imported, draft.selectSession))
            val issues = LibraryValidator.validate(merged)
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
