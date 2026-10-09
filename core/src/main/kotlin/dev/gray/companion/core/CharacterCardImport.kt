package dev.gray.companion.core

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Foreign cards become ordinary passive resources, with the full source kept for later editing. */
internal object CharacterCardImport {
    private val v1Fields = listOf("name", "description", "personality", "scenario", "first_mes", "mes_example")
    fun recognizes(root: JsonObject): Boolean = root["spec"]?.let { (it as? JsonPrimitive)?.content in setOf("chara_card_v2", "chara_card_v3") } == true ||
        v1Fields.all { (root[it] as? JsonPrimitive)?.isString == true }

    fun convert(root: JsonObject, path: String, id: String?, avatar: AvatarImage?, budget: ResourceArchiveBudget,
                issues: MutableList<Issue>): JsonObject {
        archiveRequire(recognizes(root), path, "不是支持的角色卡 v1/v2/v3 JSON")
        val data = if (root["spec"] != null) root["data"] as? JsonObject
            ?: throw ResourceArchiveProblem("$path.data", "角色卡 data 须为对象") else root
        fun string(key: String, default: String = "") = data[key]?.archiveString("$path.$key") ?: default
        v1Fields.forEach { archiveRequire(data[it] is JsonPrimitive && (data[it] as JsonPrimitive).isString, "$path.$it", "角色卡标准字段须为字符串") }
        val cardId = id ?: newId()
        val token = MessageDigest.getInstance("SHA-256").digest(cardId.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entries = mutableListOf<PromptEntry>()
        fun entry(key: String, title: String, source: String, active: Boolean = true) {
            val mapped = template(source)
            budget.materialized((if (mapped.second) source else mapped.first).toByteArray(Charsets.UTF_8).size.toLong(), "$path.$key")
            if (mapped.second) issues += Issue("$path.$key", "包含无法等价转换的角色卡宏；保留为原文，请在词条编辑器中调整", false)
            entries += PromptEntry(id = "$cardId.$key", key = key, title = title, text = if (mapped.second) source else mapped.first,
                enabled = active, isTemplate = active && !mapped.second && mapped.first.contains("{{"), order = entries.size)
        }
        entry("profile", "角色概览", string("description"))
        entry("personality", "性格特征", string("personality"))
        entry("world", "世界设定", string("scenario"))
        entry("dialogue_examples", "对话示例", string("mes_example"))
        fun greeting(source: String, key: String): String {
            val mapped = template(source)
            if (!mapped.second) {
                budget.materialized(mapped.first.toByteArray(Charsets.UTF_8).size.toLong(), "$path.$key")
                return mapped.first
            }
            entry("import_$key", "未转换开场白", source, false)
            issues += Issue("$path.$key", "开场白含不支持的动态宏，已保存在停用词条；修订后可放回开场白", false)
            return ""
        }
        val first = greeting(string("first_mes"), "first_mes")
        val alternates = data["alternate_greetings"]?.let { value ->
            val list = value as? JsonArray ?: throw ResourceArchiveProblem("$path.alternate_greetings", "备用开场白须为数组")
            list.mapIndexed { index, item -> greeting(item.archiveString("$path.alternate_greetings[$index]"), "alternate_$index") }.filter { it.isNotEmpty() }
        }.orEmpty()
        listOf("creator_notes", "system_prompt", "post_history_instructions").forEach { key ->
            val text = string(key)
            if (text.isNotEmpty()) {
                entry("import_$key", key, text, false)
                issues += Issue("$path.$key", "已保存为停用词条，不自动覆盖主调用或全局提示词", false)
            }
        }
        val original = root.toString()
        budget.materialized(original.toByteArray(Charsets.UTF_8).size.toLong(), path)
        entries += PromptEntry(id = "$cardId.import_source", key = "import_source", title = "原始角色卡 JSON（完整保留）",
            text = original, enabled = false, isTemplate = false, order = entries.size)
        val packages = mutableListOf<PromptPackage>()
        data["character_book"]?.takeIf { it != JsonNull }?.let { value ->
            val book = value as? JsonObject ?: throw ResourceArchiveProblem("$path.character_book", "世界书须为对象")
            val bookEntries = book["entries"] as? JsonArray ?: throw ResourceArchiveProblem("$path.character_book.entries", "世界书词条须为数组")
            val packId = "$cardId.book"
            val converted = bookEntries.mapIndexed { index, item ->
                budget.checkCancelled()
                val source = item as? JsonObject ?: throw ResourceArchiveProblem("$path.character_book.entries[$index]", "世界书词条须为对象")
                val content = source["content"].archiveString("$path.character_book.entries[$index].content")
                val mapped = template(content)
                budget.materialized((if (mapped.second) content else mapped.first).toByteArray(Charsets.UTF_8).size.toLong(), "$path.character_book.entries[$index]")
                // Scanning keys, insertion positions and recursive activation have no equivalent native contract.
                PromptEntry(id = "$packId.$index", key = "entry_$index", title = (source["name"] as? JsonPrimitive)?.content ?: "世界书词条 ${index + 1}",
                    text = if (mapped.second) content else mapped.first, order = index, enabled = false,
                    isTemplate = !mapped.second && mapped.first.contains("{{"))
            }
            packages += PromptPackage(id = packId, namespace = "book_$token", name = (book["name"] as? JsonPrimitive)?.content ?: "${string("name")} · 世界书", entries = converted)
            issues += Issue("$path.character_book", "世界书已转成关联词条包，词条默认停用；关键词扫描、递归激活与插入位置请在原生编辑器中配置", false)
        }
        if (data["assets"] != null || data["extensions"] != null || data["group_only_greetings"] != null)
            issues += Issue(path, "附加资产、扩展配置及群聊专用字段保存在原始 JSON；不会下载外部文件或执行扩展", false)
        val card = CharacterFields.normalize(CharacterCard(id = cardId, namespace = "card_$token", name = string("name"),
            entries = entries, boundPackageIds = packages.map { it.id }, avatar = avatar, greeting = first, alternateGreetings = alternates))
        return buildJsonObject {
            put("format", ConfigurationCommands.FORMAT)
            put("resources", buildJsonObject {
                put("characters", JsonArray(listOf(LibraryFormat.json.encodeToJsonElement(CharacterCard.serializer(), card))))
                if (packages.isNotEmpty()) put("packages", JsonArray(packages.map { LibraryFormat.json.encodeToJsonElement(PromptPackage.serializer(), it) }))
            })
        }
    }

    private fun template(source: String): Pair<String, Boolean> {
        val mapped = source.replace(Regex("\\{\\{char}}|<BOT>|<char>", RegexOption.IGNORE_CASE), "{{character.name}}")
            .replace(Regex("\\{\\{user}}|<USER>", RegexOption.IGNORE_CASE), "{{user.name}}")
        val remainder = mapped.replace("{{character.name}}", "").replace("{{user.name}}", "")
        return mapped to ("{{" in remainder || "}}" in remainder)
    }
}
