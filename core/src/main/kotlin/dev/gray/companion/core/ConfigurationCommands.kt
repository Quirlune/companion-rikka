package dev.gray.companion.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A local receipt is deliberately separate from the exported resource wire format. */
@Serializable data class ConfigurationChange(
    val kind: String,
    val id: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null,
    val beforeIndex: Int? = null,
    val afterIndex: Int? = null
) {
    val operation: String get() = when {
        before == null -> "create"
        after == null -> "remove"
        else -> "update"
    }
    val changedFields: List<String> get() {
        val old = before as? JsonObject ?: JsonObject(emptyMap())
        val new = after as? JsonObject ?: JsonObject(emptyMap())
        return (old.keys + new.keys).distinct().filter { old[it] != new[it] }
    }
}

@Serializable data class ConfigurationSettingChange(
    val key: String,
    val before: JsonElement? = null,
    val after: JsonElement? = null
)

data class ConfigurationPlan(
    val label: String = "配置导入",
    val changes: List<ConfigurationChange> = emptyList(),
    val settings: List<ConfigurationSettingChange> = emptyList()
)

@Serializable data class ConfigurationEditReceipt(
    val id: String = newId(),
    val createdAt: Long = System.currentTimeMillis(),
    val label: String = "配置导入",
    val changes: List<ConfigurationChange> = emptyList(),
    val settings: List<ConfigurationSettingChange> = emptyList()
)

data class ConfigurationPreview(
    val plan: ConfigurationPlan?,
    val issues: List<Issue> = emptyList(),
    val changes: List<ConfigurationChange> = plan?.changes.orEmpty(),
    val settings: List<ConfigurationSettingChange> = plan?.settings.orEmpty()
)

data class ConfigurationApplyResult(
    val library: Library?,
    val receipt: ConfigurationEditReceipt? = null,
    val issues: List<Issue> = emptyList()
)

/** Declarative resource edits. No command has access to sessions, runtime state, credentials or execution. */
object ConfigurationCommands {
    const val FORMAT = "companion.config.v1"
    private val json = LibraryFormat.json
    private val kinds = listOf("character", "mainCall", "package", "group", "persona", "tag", "profile", "connection", "extension")
    private val resources = mapOf(
        "characters" to "character", "mainCalls" to "mainCall", "packages" to "package", "groups" to "group",
        "personas" to "persona", "tags" to "tag", "profiles" to "profile", "connections" to "connection", "extensions" to "extension"
    )
    private val settingKeys = listOf("selectedProfileId", "selectedPersonaId", "reasoningParsing", "titleConfig")

    fun preview(text: String, base: Library): ConfigurationPreview = try {
        val root = objectAt(json.parseToJsonElement(text), "config")
        keys(root, setOf("format", "label", "resources", "commands"), "config")
        if (stringAt(root["format"], "config.format") != FORMAT) fail("config.format", "不支持的配置包格式")
        val label = root["label"]?.let { stringAt(it, "config.label") }?.takeIf { it.isNotBlank() } ?: "配置导入"
        var working = base
        root["resources"]?.let { element ->
            val table = objectAt(element, "config.resources")
            keys(table, resources.keys, "config.resources")
            table.forEach { (name, values) ->
                val array = values as? JsonArray ?: fail("config.resources.$name", "资源须为数组")
                array.forEachIndexed { index, value ->
                    working = put(working, resources.getValue(name), objectAt(value, "config.resources.$name[$index]"))
                }
            }
        }
        root["commands"]?.let { value ->
            val array = value as? JsonArray ?: fail("config.commands", "命令须为数组")
            array.forEachIndexed { index, value ->
                working = command(working, objectAt(value, "config.commands[$index]"), "config.commands[$index]")
            }
        }
        finishPreview(base, working, label)
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "配置包无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("config", "配置包 JSON 或资源字段无效")))
    }

    /** Convert a previously decoded, resource-only native import without generating its IDs again. */
    fun previewResources(resources: Library, base: Library, label: String = "资源导入"): ConfigurationPreview = try {
        if (resources.sessions.isNotEmpty() || resources.chatFolders.isNotEmpty() || resources.savedChats.isNotEmpty() ||
            resources.selectedSessionId != null) fail("resources", "含会话或收藏的原生包不能作为可撤回配置导入")
        var working = base
        for (kind in kinds) for (value in resourceValues(resources, kind)) {
            val id = idOf(value, "resources.$kind")
            val existing = resourceJson(working, kind, id)
            if (existing != null) {
                if (kind == "tag" && existing == value) continue
                fail("resources.$kind.$id", "资源 ID 已存在；原生导入不能覆盖现有配置")
            }
            working = replaceResource(working, kind, id, value, null)
        }
        finishPreview(base, working, label.ifBlank { "资源导入" })
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "资源导入无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("resources", "原生资源无法形成配置预览")))
    }

    /** The controller calls this under its library mutex, then saves the returned library and receipt together. */
    fun apply(plan: ConfigurationPlan, current: Library): ConfigurationApplyResult = transform(
        current, plan.changes, plan.settings, forward = true, label = plan.label
    )

    /** Only values this receipt installed may be restored; unrelated later changes are retained. */
    fun undo(receipt: ConfigurationEditReceipt, current: Library): ConfigurationApplyResult = transform(
        current, receipt.changes, receipt.settings, forward = false, label = receipt.label
    )

    private fun finishPreview(base: Library, working: Library, label: String): ConfigurationPreview {
        val reconciled = if (base.extensions != working.extensions) {
            val result = ExtensionCommands.reconcile(base, working)
            result.library ?: return ConfigurationPreview(null, result.issues)
        } else working
        val normalized = LibraryFormat.upgrade(reconciled.withStandardCharacterFields())
        excludedChange(base, normalized)?.let { fail(it, "配置包不能修改会话、收藏或运行状态") }
        val issues = LibraryValidator.validate(normalized).filter { it.error }
        if (issues.isNotEmpty()) return ConfigurationPreview(null, issues)
        val plan = ConfigurationPlan(label, diffResources(base, normalized), diffSettings(base, normalized))
        return ConfigurationPreview(plan)
    }

    private fun transform(current: Library, changes: List<ConfigurationChange>, settings: List<ConfigurationSettingChange>,
                          forward: Boolean, label: String): ConfigurationApplyResult = try {
        if (changes.map { it.kind to it.id }.distinct().size != changes.size || settings.map { it.key }.distinct().size != settings.size)
            fail("config", "配置计划含重复目标")
        for (change in changes) {
            if (change.kind !in kinds) fail("config.${change.kind}", "未知资源类型")
            val expected = if (forward) change.before else change.after
            if (resourceJson(current, change.kind, change.id) != canonicalImage(change.kind, change.id, expected))
                fail("config.${change.kind}.${change.id}", "资源已被后续编辑，不能直接${if (forward) "应用" else "撤回"}")
        }
        for (change in settings) {
            if (change.key !in settingKeys) fail("config.${change.key}", "未知配置项")
            val expected = if (forward) change.before else change.after
            if (settingValue(current, change.key) != expected)
                fail("config.${change.key}", "设置已被后续编辑，不能直接${if (forward) "应用" else "撤回"}")
        }
        var working = current
        changes.forEach { change ->
            val value = if (forward) change.after else change.before
            val index = if (forward) change.afterIndex else change.beforeIndex
            working = replaceResource(working, change.kind, change.id, value, index)
        }
        settings.forEach { change ->
            working = setSetting(working, change.key, (if (forward) change.after else change.before) ?: JsonNull)
        }
        val normalized = LibraryFormat.upgrade(working.withStandardCharacterFields())
        excludedChange(current, normalized)?.let { fail(it, "配置操作会改变会话、收藏或运行状态") }
        changes.forEach { change ->
            val expected = if (forward) change.after else change.before
            if (resourceJson(normalized, change.kind, change.id) != canonicalImage(change.kind, change.id, expected))
                fail("config.${change.kind}.${change.id}", "当前资源关系使配置结果与预览不一致")
        }
        settings.forEach { change ->
            val expected = if (forward) change.after else change.before
            if (settingValue(normalized, change.key) != expected)
                fail("config.${change.key}", "当前设置关系使配置结果与预览不一致")
        }
        val touched = changes.map { it.kind to it.id }.toSet()
        val incidental = diffResources(current, normalized).firstOrNull { (it.kind to it.id) !in touched }
        if (incidental != null) fail("config.${incidental.kind}.${incidental.id}", "配置规范化会改动未触及资源")
        val settingNames = settings.map { it.key }.toSet()
        val incidentalSetting = diffSettings(current, normalized).firstOrNull { it.key !in settingNames }
        if (incidentalSetting != null) fail("config.${incidentalSetting.key}", "配置规范化会改动未触及设置")
        val issues = LibraryValidator.validate(normalized).filter { it.error }
        if (issues.isNotEmpty()) return ConfigurationApplyResult(null, issues = issues)
        if (changes.isEmpty() && settings.isEmpty()) ConfigurationApplyResult(current)
        else ConfigurationApplyResult(normalized, if (forward) ConfigurationEditReceipt(label = label, changes = changes, settings = settings) else null)
    } catch (failure: ConfigProblem) {
        ConfigurationApplyResult(null, issues = listOf(Issue(failure.path, failure.message ?: "配置操作冲突")))
    } catch (_: Exception) {
        ConfigurationApplyResult(null, issues = listOf(Issue("config", "配置操作无法完成")))
    }

    private fun command(library: Library, command: JsonObject, path: String): Library {
        val op = stringAt(command["op"], "$path.op")
        if (op == "select") {
            keys(command, setOf("op", "key", "value"), path)
            val key = stringAt(command["key"], "$path.key")
            if (key !in settingKeys) fail("$path.key", "不允许修改此设置")
            return setSetting(library, key, command["value"] ?: fail("$path.value", "缺少设置值"))
        }
        if (op !in setOf("put", "mergePatch", "remove", "bind")) fail("$path.op", "未知配置命令")
        val kind = stringAt(command["kind"], "$path.kind")
        if (kind !in kinds) fail("$path.kind", "不允许修改此资源类型")
        return when (op) {
            "put" -> {
                keys(command, setOf("op", "kind", "value"), path)
                put(library, kind, objectAt(command["value"], "$path.value"))
            }
            "mergePatch" -> {
                keys(command, setOf("op", "kind", "id", "slug", "patch"), path)
                val id = targetId(library, kind, command, path)
                val old = objectAt(resourceJson(library, kind, id), path)
                val patch = objectAt(command["patch"], "$path.patch")
                if ("id" in patch) fail("$path.patch.id", "稳定 ID 不可修改")
                keys(patch, old.keys, "$path.patch")
                val merged = mergePatch(old, patch, "$path.patch")
                replaceResource(library, kind, id, merged, null)
            }
            "remove" -> {
                keys(command, setOf("op", "kind", "id", "slug"), path)
                val id = targetId(library, kind, command, path)
                replaceResource(library, kind, id, null, null)
            }
            else -> {
                keys(command, setOf("op", "kind", "id", "slug", "packages", "groups"), path)
                if (kind !in setOf("character", "mainCall", "group")) fail(path, "此资源不支持绑定命令")
                if ("packages" !in command && "groups" !in command) fail(path, "绑定命令缺少目标列表")
                if ("groups" in command && kind != "mainCall") fail("$path.groups", "只有主调用可绑定调用组")
                val id = targetId(library, kind, command, path)
                val old = objectAt(resourceJson(library, kind, id), path)
                val patch = buildMap<String, JsonElement> {
                    command["packages"]?.let { put("boundPackageIds", idsAt(it, "$path.packages")) }
                    command["groups"]?.let { put("boundGroupIds", idsAt(it, "$path.groups")) }
                }
                replaceResource(library, kind, id, JsonObject(old + patch), null)
            }
        }
    }

    private fun put(library: Library, kind: String, value: JsonObject): Library {
        val id = value["id"]?.let { stringAt(it, "$kind.id") } ?: newId()
        if (id.isBlank()) fail("$kind.id", "资源 ID 不能为空")
        return replaceResource(library, kind, id, JsonObject(value + ("id" to JsonPrimitive(id))), null)
    }

    private fun targetId(library: Library, kind: String, command: JsonObject, path: String): String {
        val id = command["id"]?.let { stringAt(it, "$path.id") }
        val slug = command["slug"]?.let { stringAt(it, "$path.slug") }
        if ((id == null) == (slug == null)) fail(path, "须用 id 或 slug 唯一定位资源")
        if (id != null) {
            if (resourceJson(library, kind, id) == null) fail("$path.id", "资源不存在：$id")
            return id
        }
        if (kind in setOf("profile", "connection", "extension")) fail("$path.slug", "此资源类型没有 slug，请使用 ID")
        val matches = when (kind) {
            "character" -> library.characters.filter { it.slug == slug }.map { it.id }
            "mainCall" -> library.mainCalls.filter { it.slug == slug }.map { it.id }
            "package" -> library.packages.filter { it.slug == slug }.map { it.id }
            "group" -> library.groups.filter { it.slug == slug }.map { it.id }
            "persona" -> library.personas.filter { it.slug == slug }.map { it.id }
            "tag" -> library.promptTags.filter { it.slug == slug }.map { it.id }
            else -> emptyList()
        }
        if (matches.size != 1) fail("$path.slug", "slug 必须匹配唯一资源")
        return matches.single()
    }

    private fun mergePatch(base: JsonObject, patch: JsonObject, path: String): JsonObject {
        val result = base.toMutableMap()
        patch.forEach { (key, value) ->
            if (value == JsonNull) {
                if (key !in result) fail("$path.$key", "要移除的字段不存在")
                result.remove(key)
            }
            else result[key] = if (value is JsonObject && result[key] is JsonObject)
                mergePatch(result.getValue(key) as JsonObject, value, "$path.$key") else value
        }
        return JsonObject(result)
    }

    private fun diffResources(before: Library, after: Library): List<ConfigurationChange> = buildList {
        for (kind in kinds) {
            val old = resourceValues(before, kind).withIndex().associate { idOf(it.value, kind) to (it.index to it.value) }
            val next = resourceValues(after, kind).withIndex().associate { idOf(it.value, kind) to (it.index to it.value) }
            for (id in (old.keys + next.keys).distinct()) {
                val oldIndex = old[id]?.first
                val newIndex = next[id]?.first
                val oldValue = old[id]?.second
                val newValue = next[id]?.second
                if (oldValue != newValue) add(ConfigurationChange(kind, id, oldValue, newValue, oldIndex, newIndex))
            }
        }
    }

    private fun diffSettings(before: Library, after: Library): List<ConfigurationSettingChange> = settingKeys.mapNotNull { key ->
        val old = settingValue(before, key)
        val next = settingValue(after, key)
        if (old == next) null else ConfigurationSettingChange(key, old, next)
    }

    private fun settingValue(library: Library, key: String): JsonElement = when (key) {
        "selectedProfileId" -> library.selectedProfileId?.let(::JsonPrimitive) ?: JsonNull
        "selectedPersonaId" -> library.selectedPersonaId?.let(::JsonPrimitive) ?: JsonNull
        "reasoningParsing" -> json.encodeToJsonElement(ReasoningParsingConfig.serializer(), library.reasoningParsing)
        "titleConfig" -> json.encodeToJsonElement(ChatTitleConfig.serializer(), library.titleConfig)
        else -> fail("config.$key", "未知配置项")
    }

    private fun setSetting(library: Library, key: String, value: JsonElement): Library = when (key) {
        "selectedProfileId" -> library.copy(selectedProfileId = if (value == JsonNull) null else stringAt(value, "config.$key"))
        "selectedPersonaId" -> library.copy(selectedPersonaId = if (value == JsonNull) null else stringAt(value, "config.$key"))
        "reasoningParsing" -> library.copy(reasoningParsing = json.decodeFromJsonElement(ReasoningParsingConfig.serializer(), value))
        "titleConfig" -> library.copy(titleConfig = json.decodeFromJsonElement(ChatTitleConfig.serializer(), value))
        else -> fail("config.$key", "不允许修改此设置")
    }

    private fun excludedChange(before: Library, after: Library): String? = when {
        before.sessions != after.sessions -> "library.sessions"
        before.savedChats != after.savedChats -> "library.savedChats"
        before.chatFolders != after.chatFolders -> "library.chatFolders"
        before.selectedSessionId != after.selectedSessionId -> "library.selectedSessionId"
        before.user != after.user -> "library.user"
        before.configurationEdits != after.configurationEdits -> "library.configurationEdits"
        else -> null
    }

    private fun resourceValues(library: Library, kind: String): List<JsonElement> = when (kind) {
        "character" -> library.characters.map { json.encodeToJsonElement(CharacterCard.serializer(), it) }
        "mainCall" -> library.mainCalls.map { json.encodeToJsonElement(MainCall.serializer(), it) }
        "package" -> library.packages.map { json.encodeToJsonElement(PromptPackage.serializer(), it) }
        "group" -> library.groups.map { json.encodeToJsonElement(CallGroup.serializer(), it) }
        "persona" -> library.personas.map { json.encodeToJsonElement(UserPersona.serializer(), it) }
        "tag" -> library.promptTags.map { json.encodeToJsonElement(PromptTag.serializer(), it) }
        "profile" -> library.profiles.map { json.encodeToJsonElement(ModelProfile.serializer(), it) }
        "connection" -> library.connections.map { json.encodeToJsonElement(ApiConnection.serializer(), it) }
        "extension" -> library.extensions.map { json.encodeToJsonElement(ConfigurationExtension.serializer(), it) }
        else -> fail("config.$kind", "未知资源类型")
    }

    private fun resourceJson(library: Library, kind: String, id: String): JsonElement? = when (kind) {
        "character" -> library.characters.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(CharacterCard.serializer(), it) }
        "mainCall" -> library.mainCalls.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(MainCall.serializer(), it) }
        "package" -> library.packages.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(PromptPackage.serializer(), it) }
        "group" -> library.groups.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(CallGroup.serializer(), it) }
        "persona" -> library.personas.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(UserPersona.serializer(), it) }
        "tag" -> library.promptTags.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(PromptTag.serializer(), it) }
        "profile" -> library.profiles.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(ModelProfile.serializer(), it) }
        "connection" -> library.connections.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(ApiConnection.serializer(), it) }
        "extension" -> library.extensions.firstOrNull { it.id == id }?.let { json.encodeToJsonElement(ConfigurationExtension.serializer(), it) }
        else -> fail("config.$kind", "未知资源类型")
    }

    private fun idOf(value: JsonElement, path: String): String =
        stringAt((value as? JsonObject)?.get("id"), "$path.id")

    /** Saved receipts may predate appended fields; compare typed defaults, without renaming or migrating chats. */
    private fun canonicalImage(kind: String, id: String, value: JsonElement?): JsonElement? =
        value?.let { resourceJson(replaceResource(Library(), kind, id, it, null), kind, id) }

    private fun replaceResource(library: Library, kind: String, id: String, value: JsonElement?, index: Int?): Library = when (kind) {
        "character" -> library.copy(characters = replace(library.characters, id, value, index, CharacterCard.serializer()) { it.id })
        "mainCall" -> library.copy(mainCalls = replace(library.mainCalls, id, value, index, MainCall.serializer()) { it.id })
        "package" -> library.copy(packages = replace(library.packages, id, value, index, PromptPackage.serializer()) { it.id })
        "group" -> library.copy(groups = replace(library.groups, id, value, index, CallGroup.serializer()) { it.id })
        "persona" -> library.copy(personas = replace(library.personas, id, value, index, UserPersona.serializer()) { it.id })
        "tag" -> library.copy(promptTags = replace(library.promptTags, id, value, index, PromptTag.serializer()) { it.id })
        "profile" -> library.copy(profiles = replace(library.profiles, id, value, index, ModelProfile.serializer()) { it.id })
        "connection" -> library.copy(connections = replace(library.connections, id, value, index, ApiConnection.serializer()) { it.id })
        "extension" -> library.copy(extensions = replace(library.extensions, id, value, index, ConfigurationExtension.serializer()) { it.id })
        else -> fail("config.$kind", "未知资源类型")
    }

    private fun <T> replace(values: List<T>, id: String, value: JsonElement?, index: Int?, serializer: KSerializer<T>,
                            identity: (T) -> String): List<T> {
        val updated = values.toMutableList()
        val oldIndex = updated.indexOfFirst { identity(it) == id }
        if (value == null) {
            if (oldIndex >= 0) updated.removeAt(oldIndex)
        } else {
            val decoded = json.decodeFromJsonElement(serializer, value)
            if (identity(decoded) != id) fail("config.$id", "资源 ID 与目标不一致")
            if (oldIndex >= 0) updated[oldIndex] = decoded
            else updated.add((index ?: updated.size).coerceIn(0, updated.size), decoded)
        }
        return updated
    }

    private fun idsAt(value: JsonElement, path: String): JsonArray {
        val array = value as? JsonArray ?: fail(path, "绑定值须为 ID 数组")
        array.forEachIndexed { index, id -> stringAt(id, "$path[$index]") }
        return array
    }

    private fun objectAt(value: JsonElement?, path: String): JsonObject =
        value as? JsonObject ?: fail(path, "须为 JSON 对象")

    private fun stringAt(value: JsonElement?, path: String): String {
        val primitive = value as? JsonPrimitive ?: fail(path, "须为字符串")
        if (!primitive.isString) fail(path, "须为字符串")
        return primitive.content
    }

    private fun keys(value: JsonObject, allowed: Set<String>, path: String) {
        (value.keys - allowed).firstOrNull()?.let { fail("$path.$it", "未知字段") }
    }

    private fun fail(path: String, message: String): Nothing = throw ConfigProblem(path, message)
    private class ConfigProblem(val path: String, message: String) : IllegalArgumentException(message)
}
