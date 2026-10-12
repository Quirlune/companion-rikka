package dev.gray.companion.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val kinds = ResourceKindCatalog.names
    private val resources = ResourceKindCatalog.arrays
    private val settingKeys = listOf("selectedProfileId", "selectedPersonaId", "reasoningParsing", "titleConfig", "automaticOptions", "memoryConfig")

    fun preview(text: String, base: Library, additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationPreview = try {
        preview(objectAt(json.parseToJsonElement(text), "config"), base, additionalValidation)
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "配置包无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("config", "配置包 JSON 或资源字段无效")))
    }

    /** Already parsed archive content follows the same strict keys, candidate and receipt path. */
    fun preview(document: JsonObject, base: Library, additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationPreview = try {
        val root = document
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
                    working = put(working, resources.getValue(name), objectAt(value, "config.resources.$name[$index]"), "config.resources.$name[$index]")
                }
            }
        }
        root["commands"]?.let { value ->
            val array = value as? JsonArray ?: fail("config.commands", "命令须为数组")
            array.forEachIndexed { index, value ->
                working = command(working, objectAt(value, "config.commands[$index]"), "config.commands[$index]")
            }
        }
        finishPreview(base, working, label, additionalValidation)
    } catch (failure: InvalidResourceField) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "资源字段无效")))
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "配置包无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("config", "配置包 JSON 或资源字段无效")))
    }

    /** Convert a previously decoded, resource-only native import without generating its IDs again. */
    fun previewResources(resources: Library, base: Library, label: String = "资源导入",
                         additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationPreview = try {
        if (resources.sessions.isNotEmpty() || resources.chatFolders.isNotEmpty() || resources.savedChats.isNotEmpty() ||
            resources.selectedSessionId != null) fail("resources", "含会话或收藏的原生包不能作为可撤回配置导入")
        var working = base
        for (kind in ResourceKindCatalog.ordered.map { it.name }) for (value in resourceValues(resources, kind)) {
            val id = idOf(value, "resources.$kind")
            val existing = resourceJson(working, kind, id)
            if (existing != null) {
                if (kind == "tag" && existing == value) continue
                fail("resources.$kind", "资源 ID 已存在；原生导入不能覆盖现有配置")
            }
            working = replaceResource(working, kind, id, value, null, "resources.$kind")
        }
        finishPreview(base, working, label.ifBlank { "资源导入" }, additionalValidation)
    } catch (failure: InvalidResourceField) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "资源字段无效")))
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "资源导入无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("resources", "原生资源无法形成配置预览")))
    }

    /** The controller calls this under its library mutex, then saves the returned library and receipt together. */
    fun apply(plan: ConfigurationPlan, current: Library, additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationApplyResult = transform(
        current, plan.changes.map(CollectionCompatibility::canonicalChange), plan.settings, forward = true, label = plan.label,
        additionalValidation = additionalValidation
    )

    /** Typed editor candidate, with the same normalization, excluded-state guard and validation as imports. */
    fun previewEdit(before: Library, candidate: Library, label: String = "配置编辑",
                    additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationPreview = try {
        finishPreview(before, candidate, label, additionalValidation)
    } catch (failure: ConfigProblem) {
        ConfigurationPreview(null, listOf(Issue(failure.path, failure.message ?: "配置编辑无效")))
    } catch (_: Exception) {
        ConfigurationPreview(null, listOf(Issue("config", "配置编辑无法形成预览")))
    }

    /** Only values this receipt installed may be restored; unrelated later changes are retained. */
    fun undo(receipt: ConfigurationEditReceipt, current: Library,
             additionalValidation: (Library) -> List<Issue> = { emptyList() }): ConfigurationApplyResult = transform(
        current, CollectionCompatibility.canonicalReceipt(receipt).changes, receipt.settings, forward = false, label = receipt.label,
        additionalValidation = additionalValidation
    )

    private fun finishPreview(base: Library, working: Library, label: String,
                              additionalValidation: (Library) -> List<Issue>): ConfigurationPreview {
        requireCanonicalLive(base)
        excludedChange(base, working)?.let { fail(it, "配置包不能修改会话、收藏或运行状态") }
        val normalized = LibraryFormat.upgrade(working.withStandardCharacterFields())
        excludedChange(base, normalized)?.let { fail(it, "配置包不能修改会话、收藏或运行状态") }
        val issues = (LibraryValidator.validate(normalized) + removedPersonaIssues(base, normalized) +
            removedResourceIssues(base, normalized) +
            additionalValidation(normalized)).filter { it.error }
        if (issues.isNotEmpty()) return ConfigurationPreview(null, issues)
        val plan = ConfigurationPlan(label, diffResources(base, normalized), diffSettings(base, normalized))
        return ConfigurationPreview(plan)
    }

    private fun transform(current: Library, changes: List<ConfigurationChange>, settings: List<ConfigurationSettingChange>,
                          forward: Boolean, label: String,
                          additionalValidation: (Library) -> List<Issue>): ConfigurationApplyResult = try {
        requireCanonicalLive(current)
        if (changes.map { it.kind to it.id }.distinct().size != changes.size || settings.map { it.key }.distinct().size != settings.size)
            fail("config", "配置计划含重复目标")
        for ((index, change) in changes.withIndex()) {
            if (change.kind !in kinds) fail("config.changes[$index].kind", "未知资源类型")
            val expected = if (forward) change.before else change.after
            if (resourceJson(current, change.kind, change.id) != canonicalImage(change.kind, change.id, expected))
                fail("config.changes[$index]", "资源已被后续编辑，不能直接${if (forward) "应用" else "撤回"}")
        }
        for (change in settings) {
            if (change.key !in settingKeys) fail("config.settings", "未知配置项")
            val expected = if (forward) change.before else change.after
            if (settingValue(current, change.key) != expected)
                fail("config.${change.key}", "设置已被后续编辑，不能直接${if (forward) "应用" else "撤回"}")
        }
        var working = current
        changes.forEachIndexed { changeIndex, change ->
            val value = if (forward) change.after else change.before
            val index = if (forward) change.afterIndex else change.beforeIndex
            working = replaceResource(working, change.kind, change.id, value, index, "config.changes[$changeIndex].value")
        }
        settings.forEach { change ->
            working = setSetting(working, change.key, (if (forward) change.after else change.before) ?: JsonNull)
        }
        val normalized = LibraryFormat.upgrade(working.withStandardCharacterFields())
        excludedChange(current, normalized)?.let { fail(it, "配置操作会改变会话、收藏或运行状态") }
        changes.forEachIndexed { changeIndex, change ->
            val expected = if (forward) change.after else change.before
            if (resourceJson(normalized, change.kind, change.id) != canonicalImage(change.kind, change.id, expected))
                fail("config.changes[$changeIndex]", "当前资源关系使配置结果与预览不一致")
        }
        settings.forEach { change ->
            val expected = if (forward) change.after else change.before
            if (settingValue(normalized, change.key) != expected)
                fail("config.${change.key}", "当前设置关系使配置结果与预览不一致")
        }
        val touched = changes.map { it.kind to it.id }.toSet()
        val incidental = diffResources(current, normalized).firstOrNull { (it.kind to it.id) !in touched }
        if (incidental != null) fail("config.resources", "配置规范化会改动未触及资源")
        val settingNames = settings.map { it.key }.toSet()
        val incidentalSetting = diffSettings(current, normalized).firstOrNull { it.key !in settingNames }
        if (incidentalSetting != null) fail("config.${incidentalSetting.key}", "配置规范化会改动未触及设置")
        val issues = (LibraryValidator.validate(normalized) + removedPersonaIssues(current, normalized) +
            removedResourceIssues(current, normalized) +
            additionalValidation(normalized)).filter { it.error }
        if (issues.isNotEmpty()) return ConfigurationApplyResult(null, issues = issues)
        if (changes.isEmpty() && settings.isEmpty()) ConfigurationApplyResult(current)
        else ConfigurationApplyResult(normalized, if (forward) ConfigurationEditReceipt(label = label, changes = changes, settings = settings) else null)
    } catch (failure: InvalidResourceField) {
        ConfigurationApplyResult(null, issues = listOf(Issue(failure.path, failure.message ?: "资源字段无效")))
    } catch (failure: ConfigProblem) {
        ConfigurationApplyResult(null, issues = listOf(Issue(failure.path, failure.message ?: "配置操作冲突")))
    } catch (_: Exception) {
        ConfigurationApplyResult(null, issues = listOf(Issue("config", "配置操作无法完成")))
    }

    private fun removedPersonaIssues(before: Library, after: Library): List<Issue> =
        before.personas.filter { old -> after.personas.none { it.id == old.id } }.flatMap { old ->
            after.personaReferences(old.id).map { path -> Issue(path, "用户身份仍被引用，不能删除：${old.name}") }
        }

    /** Evaluate deletions against the final candidate, including a plan's repairs in the same transaction.
     * The former target definition is used only to resolve its addresses and workflow IDs.
     */
    private fun removedResourceIssues(before: Library, after: Library): List<Issue> {
        val previous = ResourceLocations.resources(before)
        val current = ResourceLocations.resources(after).toSet()
        val removed = previous.filter { it !in current }.map(::ResourceLocation)
        val removedEntries = previous.filter { it in current &&
            it.kind in setOf(ExtensionResourceKind.CHARACTER, ExtensionResourceKind.PACKAGE) }.flatMap { ref ->
            val remaining = ResourceEditCommands.entries(after, ref).map { it.id }.toSet()
            ResourceEditCommands.entries(before, ref).filter { it.id !in remaining }
                .map { ResourceLocation(ref, entryId = it.id) }
        }
        return (removed + removedEntries).flatMap { target ->
            ResourceEditCommands.deletionUses(after, target, before).map { use ->
                Issue(use.path, "仍被使用：${use.label}")
            }
        }.distinct()
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
                put(library, kind, objectAt(command["value"], "$path.value"), "$path.value")
            }
            "mergePatch" -> {
                keys(command, setOf("op", "kind", "id", "slug", "patch"), path)
                val id = targetId(library, kind, command, path)
                val old = objectAt(resourceJson(library, kind, id), path)
                val patch = objectAt(command["patch"], "$path.patch")
                if ("id" in patch) fail("$path.patch.id", "稳定 ID 不可修改")
                keys(patch, old.keys, "$path.patch")
                val merged = mergePatch(old, patch, "$path.patch")
                replaceResource(library, kind, id, merged, null, "$path.patch")
            }
            "remove" -> {
                keys(command, setOf("op", "kind", "id", "slug"), path)
                val id = targetId(library, kind, command, path)
                replaceResource(library, kind, id, null, null, path)
            }
            else -> {
                keys(command, setOf("op", "kind", "id", "slug", "packages", "groups"), path)
                if (kind !in setOf("character", "mainCall", "group")) fail(path, "此资源不支持绑定命令")
                if ("packages" !in command && "groups" !in command) fail(path, "绑定命令缺少目标列表")
                if ("groups" in command && kind !in setOf("character", "mainCall")) fail("$path.groups", "只有角色或主调用可绑定调用组")
                val id = targetId(library, kind, command, path)
                val old = objectAt(resourceJson(library, kind, id), path)
                val patch = buildMap<String, JsonElement> {
                    command["packages"]?.let { put("boundPackageIds", idsAt(it, "$path.packages")) }
                    command["groups"]?.let { put("boundGroupIds", idsAt(it, "$path.groups")) }
                }
                replaceResource(library, kind, id, JsonObject(old + patch), null, path)
            }
        }
    }

    private fun put(library: Library, kind: String, value: JsonObject, path: String): Library {
        val id = value["id"]?.let { stringAt(it, "$path.id") } ?: newId()
        if (id.isBlank()) fail("$path.id", "资源 ID 不能为空")
        return replaceResource(library, kind, id, JsonObject(value + ("id" to JsonPrimitive(id))), null, path)
    }

    private fun targetId(library: Library, kind: String, command: JsonObject, path: String): String {
        val id = command["id"]?.let { stringAt(it, "$path.id") }
        val slug = command["slug"]?.let { stringAt(it, "$path.slug") }
        if ((id == null) == (slug == null)) fail(path, "须用 id 或 slug 唯一定位资源")
        if (id != null) {
            if (resourceJson(library, kind, id) == null) fail("$path.id", "资源不存在")
            return id
        }
        val descriptor = ResourceKindCatalog.get(kind) ?: fail("$path.kind", "未知资源类型")
        if (descriptor.slug == null) fail("$path.slug", "此资源类型没有 slug，请使用 ID")
        val matches = descriptor.idsForSlug(library, slug ?: "")
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
        for (kind in ResourceKindCatalog.ordered.map { it.name }) {
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
        "automaticOptions" -> json.encodeToJsonElement(AutomaticOptionsConfig.serializer(), library.automaticOptions)
        "memoryConfig" -> library.memoryConfig?.let { json.encodeToJsonElement(GlobalMemoryConfig.serializer(), it) } ?: JsonNull
        else -> fail("config.$key", "未知配置项")
    }

    private fun setSetting(library: Library, key: String, value: JsonElement): Library = when (key) {
        "selectedProfileId" -> library.copy(selectedProfileId = if (value == JsonNull) null else stringAt(value, "config.$key"))
        "selectedPersonaId" -> library.copy(selectedPersonaId = if (value == JsonNull) null else stringAt(value, "config.$key"))
        "reasoningParsing" -> library.copy(reasoningParsing = decodeSetting(ReasoningParsingConfig.serializer(), value, "config.$key"))
        "titleConfig" -> library.copy(titleConfig = decodeSetting(ChatTitleConfig.serializer(), value, "config.$key"))
        "automaticOptions" -> library.copy(automaticOptions = decodeSetting(AutomaticOptionsConfig.serializer(), value, "config.$key"))
        "memoryConfig" -> library.copy(memoryConfig = if (value == JsonNull) null else decodeSetting(GlobalMemoryConfig.serializer(), value, "config.$key"))
        else -> fail("config.$key", "不允许修改此设置")
    }

    private fun <T> decodeSetting(serializer: kotlinx.serialization.KSerializer<T>, value: JsonElement, path: String): T {
        return try { json.decodeFromJsonElement(serializer, value) }
            catch (_: Exception) {
                SafeJsonFieldDiagnostics.requireValid(serializer.descriptor, value, path)
                throw InvalidResourceField(path, "设置字段无效")
            }
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

    private fun resourceValues(library: Library, kind: String): List<JsonElement> =
        (ResourceKindCatalog.get(kind) ?: fail("config.kind", "未知资源类型")).values(library)

    private fun resourceJson(library: Library, kind: String, id: String): JsonElement? =
        (ResourceKindCatalog.get(kind) ?: fail("config.kind", "未知资源类型")).find(library, id)

    private fun idOf(value: JsonElement, path: String): String =
        stringAt((value as? JsonObject)?.get("id"), "$path.id")

    /** Saved receipts may predate appended fields; compare typed defaults, without renaming or migrating chats. */
    private fun canonicalImage(kind: String, id: String, value: JsonElement?): JsonElement? =
        value?.let { resourceJson(replaceResource(Library(), kind, id, it, null, "config.$kind"), kind, id) }

    private fun replaceResource(library: Library, kind: String, id: String, value: JsonElement?, index: Int?, path: String): Library =
        (ResourceKindCatalog.get(kind) ?: fail("config.kind", "未知资源类型")).replace(library, id, value, index, path)

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
        (value.keys - allowed).firstOrNull()?.let { key ->
            val segment = if (key.length <= 80 && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) key else "[unknown]"
            fail("$path.$segment", "未知字段")
        }
    }

    private fun requireCanonicalLive(library: Library) {
        if (CollectionCompatibility.canonicalLive(library) !== library)
            fail("library.extensions", "旧集合安装信息尚未迁移，请重新加载资源库后再进行配置操作")
    }

    private fun fail(path: String, message: String): Nothing = throw ConfigProblem(path, message)
    private class ConfigProblem(val path: String, message: String) : IllegalArgumentException(message)
}
