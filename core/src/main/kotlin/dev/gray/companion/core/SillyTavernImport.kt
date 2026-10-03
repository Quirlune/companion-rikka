package dev.gray.companion.core

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Incoming resources only. The caller merges this into its current library transactionally. */
data class ImportResult(val library: Library, val warnings: List<String>)

/** A narrow converter for the curated, nonsexual preview envelope; not a SillyTavern runtime. */
object SillyTavernImport {
    private const val FORMAT = "sillytavern.preview.v1"
    private val token = Regex("\\{\\{([^{}]*)\\}\\}")

    fun convert(jsonText: String, existing: Library): ImportResult {
        require(jsonText.length <= 2_000_000) { "预览包过大" }
        val root = Json.parseToJsonElement(jsonText) as? JsonObject ?: error("预览包不是对象")
        require(root.string("format") == FORMAT && root.boolean("sanitized") == true) {
            "预览包格式无效或未经非成人内容筛选"
        }
        val warnings = linkedSetOf<String>()
        val occupied = buildSet {
            addAll(existing.characters.map { it.id }); addAll(existing.packages.map { it.id })
            addAll(existing.mainCalls.map { it.id }); addAll(existing.groups.map { it.id })
            addAll(existing.sessions.map { it.id }); addAll(existing.personas.map { it.id })
            addAll(existing.profiles.map { it.id }); addAll(existing.connections.map { it.id })
            addAll(existing.chatFolders.map { it.id }); addAll(existing.savedChats.map { it.id })
        }.toMutableSet()
        fun id(kind: String, seed: String): String {
            var suffix = 0
            while (true) {
                val value = UUID.nameUUIDFromBytes("$FORMAT:$kind:$seed:$suffix".toByteArray(StandardCharsets.UTF_8)).toString()
                if (occupied.add(value)) return value
                suffix++
            }
        }
        fun normalized(source: String, path: String): String = token.replace(source) { match ->
            val raw = match.groupValues[1].trim()
            val lower = raw.lowercase()
            val mapped = when {
                raw.startsWith("//") -> return@replace ""
                lower == "char" -> "character.name"
                lower == "user" -> "user.name"
                lower == "lastusermessage" -> "input"
                lower == "description" -> "character.profile"
                lower == "personality" -> "character.personality"
                lower == "scenario" -> "character.world"
                lower in setOf("mesexamples", "mes_example", "dialogueexamples") -> "character.dialogue_examples"
                lower == "personadescription" -> "user.prompt"
                lower.startsWith("random::") || lower.startsWith("roll:") || lower.startsWith("roll::") -> {
                    warnings += "$path 含不支持的随机宏，已移除"
                    return@replace ""
                }
                macroExpressionError(raw) == null -> raw
                else -> {
                    warnings += "$path 含不支持的宏，已移除"
                    return@replace ""
                }
            }
            "{{${mapped}}}"
        }

        val data = root.obj("character").obj("data")
        val sourceName = data.string("name").ifBlank { "导入角色" }
        val characterId = id("character", sourceName)
        val packId = id("pack", sourceName)
        val mainId = id("main", sourceName)
        val groupId = id("group", sourceName)
        val workflowId = id("workflow", "chat:$sourceName")
        val modelNodeId = id("node", "chat:$sourceName")
        val namespace = "st_${characterId.replace("-", "").take(12)}"
        val packNamespace = "st_${packId.replace("-", "").take(12)}"
        val basics = mapOf(
            "profile" to data.string("description"),
            "personality" to data.string("personality"),
            "world" to data.string("scenario"),
            "dialogue_examples" to data.string("mes_example")
        )
        val entries = CharacterFields.definitions.mapIndexed { index, field ->
            val raw = basics[field.key].orEmpty()
            val converted = normalized(raw, "角色字段 ${field.key}")
            PromptEntry(id = id("entry", "character:${field.key}:$sourceName"), key = field.key,
                text = converted, order = index, isTemplate = token.containsMatchIn(converted))
        }

        data class WorldItem(val uid: String, val title: String, val content: String, val position: Int,
                             val depth: Int, val order: Int, val role: Role, val enabled: Boolean,
                             val recognition: Recognition?) {
            val key: String get() = "world_${uid.replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        }
        val worldByUid = linkedMapOf<String, JsonObject>()
        data.obj("character_book").array("entries").forEachIndexed { index, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            worldByUid[item.string("id").ifBlank { "embedded_$index" }] = item
        }
        // External world entry with the same UID overrides the embedded copy.
        root.obj("world").obj("entries").forEach { (uid, element) ->
            val item = element as? JsonObject ?: return@forEach
            worldByUid[item.string("uid").ifBlank { uid }] = item
        }
        fun role(value: String, path: String): Role = when (value.lowercase()) {
            "user", "1" -> Role.USER
            "assistant", "2" -> Role.ASSISTANT
            "system", "0", "" -> Role.SYSTEM
            else -> { warnings += "$path 使用未知角色，已按系统角色保存"; Role.SYSTEM }
        }
        fun recognition(key: String): Recognition? {
            val terms = key.split(',', '，', '|').map(String::trim).filter(String::isNotBlank).take(16)
            if (terms.isEmpty()) return null
            val pattern = terms.joinToString("|") { term ->
                term.flatMap { ch -> if (ch in "\\.^$|?*+()[]{}") listOf('\\', ch) else listOf(ch) }.joinToString("")
            }
            return Recognition(rules = listOf(RecognitionRule(template = "{{input}}", pattern = pattern)))
        }
        val world = worldByUid.map { (uid, item) ->
            val constant = item.boolean("constant") == true
            val disabled = item.boolean("disable") == true || item.boolean("enabled") == false
            val keyword = item.string("key").ifBlank { item.array("key").joinToString(",") { it.asString() } }
                .ifBlank { item.array("keys").joinToString(",") { it.asString() } }
            val rule = if (constant) null else recognition(keyword)
            if (!constant && rule == null) warnings += "世界条目 $uid 缺少可执行关键词，已停用"
            val rawPosition = item.string("position")
            val position = when (rawPosition.lowercase()) {
                "before_char", "before", "0" -> 0
                "after_char", "after", "1" -> 1
                "at_depth", "depth", "4" -> 4
                else -> { warnings += "世界条目 $uid 的插入槽不支持，已停用"; -1 }
            }
            WorldItem(uid, item.string("comment").ifBlank { "世界条目 $uid" },
                normalized(item.string("content"), "世界条目 $uid"), position,
                item.int("depth") ?: item.obj("extensions").int("depth") ?: 0,
                item.int("order") ?: item.int("insertion_order") ?: 0,
                role(item.string("role").ifBlank { item.obj("extensions").string("role") }, "世界条目 $uid"),
                !disabled && position >= 0 && (constant || rule != null), rule)
        }.sortedWith(compareBy<WorldItem> { it.order }.thenBy { it.uid })
        val packageEntries = world.mapIndexed { index, item ->
            PromptEntry(id = id("entry", "world:${item.uid}:$sourceName"), key = item.key,
                text = item.content, category = when (item.position) { 0 -> "before"; 1 -> "after"; else -> "depth" },
                order = index, enabled = item.enabled, isTemplate = token.containsMatchIn(item.content))
        }
        val pack = PromptPackage(id = packId, namespace = packNamespace, name = "$sourceName · 场景", entries = packageEntries)
        val card = CharacterCard(id = characterId, namespace = namespace, name = sourceName,
            description = normalized(data.string("description"), "角色介绍"), entries = entries,
            boundPackageIds = listOf(pack.id), greeting = normalized(data.string("first_mes"), "开场白"))

        val preset = root.obj("preset")
        val promptObjects = preset.array("prompts").mapNotNull { it as? JsonObject }
        val byIdentifier = promptObjects.associateBy { it.string("identifier") }
        val orderGroup = preset.array("prompt_order").mapNotNull { it as? JsonObject }
            .firstOrNull { it.int("character_id") == 100001 }
            ?: preset.array("prompt_order").mapNotNull { it as? JsonObject }
                .firstOrNull { it.int("character_id") == 100000 }
        val order = orderGroup?.array("order")?.mapNotNull { it as? JsonObject }.orEmpty()
        val orderedIds = order.map { it.string("identifier") }.toSet()
        val chatIndex = order.indexOfFirst { it.string("identifier") == "chatHistory" }.let { if (it < 0) order.size else it }
        if (chatIndex == order.size) warnings += "预设缺少历史标记，已补入一次结构化历史"

        fun formatted(field: String, replacement: String): String {
            val source = preset.string(field)
            if (source.isBlank()) return replacement
            val converted = normalized(source, "预设格式 $field")
            return if (replacement in converted) converted else {
                warnings += "预设格式 $field 未引用对应字段，已使用直接引用"
                replacement
            }
        }
        fun builtin(identifier: String): String? = when (identifier) {
            "chatHistory" -> "{{history}}"
            "charDescription" -> "{{character.profile}}"
            "charPersonality" -> formatted("personality_format", "{{character.personality}}")
            "scenario" -> formatted("scenario_format", "{{character.world}}")
            "dialogueExamples" -> "{{character.dialogue_examples}}"
            "personaDescription" -> "{{user.prompt}}"
            else -> null
        }
        fun worldBlock(item: WorldItem): PromptBlock = PromptBlock(
            id = id("block", "world:${item.uid}:$sourceName"), title = item.title,
            template = "{{pack.$packNamespace.${item.key}}}", role = item.role,
            enabled = item.enabled, position = PromptPosition.BEFORE_HISTORY,
            recognition = item.recognition
        )
        val before = mutableListOf<PromptBlock>()
        val after = mutableListOf<PromptBlock>()
        val inChat = mutableListOf<Pair<Int, PromptBlock>>()
        var sawHistory = false
        var sawBefore = false
        var sawAfter = false
        val ordered = order.mapIndexedNotNull { index, item ->
            val identifier = item.string("identifier")
            if (identifier in setOf("nsfw", "jailbreak")) {
                warnings += "预设项 $index 属于排除的来源类别"
                return@mapIndexedNotNull null
            }
            val source = byIdentifier[identifier]
            if (source == null) { warnings += "预设项 $index 未找到定义"; return@mapIndexedNotNull null }
            Triple(index, source, item.boolean("enabled") == true)
        } + promptObjects.withIndex().filter { it.value.string("identifier") !in orderedIds &&
            it.value.string("identifier") !in setOf("nsfw", "jailbreak") }
            .map { Triple(order.size + it.index, it.value, false) }
        for ((index, source, enabled) in ordered) {
            val identifier = source.string("identifier")
            if (identifier == "worldInfoBefore") {
                sawBefore = true
                if (index < chatIndex) before += world.filter { it.position == 0 }.map(::worldBlock)
                else after += world.filter { it.position == 0 }.map { worldBlock(it).copy(position = PromptPosition.AFTER_HISTORY) }
                continue
            }
            if (identifier == "worldInfoAfter") {
                sawAfter = true
                if (index < chatIndex) before += world.filter { it.position == 1 }.map(::worldBlock)
                else after += world.filter { it.position == 1 }.map { worldBlock(it).copy(position = PromptPosition.AFTER_HISTORY) }
                continue
            }
            val template = if (source.string("content").isBlank()) builtin(identifier).orEmpty()
                else normalized(source.string("content"), "预设项 $index")
            val promptRole = role(source.string("role"), "预设项 $index")
            val slot = source.int("injection_position") ?: 0
            val block = PromptBlock(id = id("block", "prompt:$identifier:$index:$sourceName"),
                title = source.string("name").ifBlank { identifier.ifBlank { "预设项 $index" } },
                template = template, role = promptRole, enabled = enabled && slot in 0..1,
                position = when { identifier == "chatHistory" -> PromptPosition.BEFORE_HISTORY
                    slot == 1 -> PromptPosition.IN_HISTORY
                    index < chatIndex -> PromptPosition.BEFORE_HISTORY
                    else -> PromptPosition.AFTER_HISTORY },
                depth = if (slot == 1) (source.int("injection_depth") ?: 0).coerceAtLeast(0) else 0)
            if (slot !in 0..1) warnings += "预设项 $index 使用不支持的插入槽，已停用"
            if (identifier == "chatHistory") {
                if (!sawHistory) {
                    before += block.copy(template = "{{history}}", enabled = true)
                    sawHistory = true
                } else warnings += "重复历史标记已忽略"
            } else when (block.position) {
                PromptPosition.BEFORE_HISTORY -> before += block
                PromptPosition.AFTER_HISTORY -> after += block
                PromptPosition.IN_HISTORY -> inChat += index to block
            }
        }
        if (!sawBefore) before.addAll(0, world.filter { it.position == 0 }.map(::worldBlock))
        if (!sawAfter) before += world.filter { it.position == 1 }.map(::worldBlock)
        if (!sawHistory) before += PromptBlock(id = id("block", "history:$sourceName"),
            title = "对话历史", template = "{{history}}")
        val roleOrder = mapOf(Role.USER to 0, Role.ASSISTANT to 1, Role.SYSTEM to 2)
        val sortedInChat = inChat.sortedWith(compareBy<Pair<Int, PromptBlock>> { it.second.depth }
            .thenBy { roleOrder[it.second.role] ?: 2 }.thenBy { it.first }).map { it.second }
        val systemPrompt = normalized(data.string("system_prompt"), "角色系统提示")
        if (systemPrompt.isNotBlank()) before.add(0, PromptBlock(id = id("block", "card-system:$sourceName"),
            title = "角色系统提示", template = systemPrompt))
        val postHistory = normalized(data.string("post_history_instructions"), "角色历史后提示")
        if (postHistory.isNotBlank()) after += PromptBlock(id = id("block", "card-post:$sourceName"),
            title = "角色历史后提示", template = postHistory, position = PromptPosition.AFTER_HISTORY)
        val newChatPrompt = normalized(preset.string("new_chat_prompt"), "预设新对话提示")
        if (newChatPrompt.isNotBlank()) before.add(0, PromptBlock(id = id("block", "preset-new:$sourceName"),
            title = "新对话设定", template = newChatPrompt))
        if (preset.string("wi_format").trim().let { it.isNotBlank() && it != "{0}" })
            warnings += "世界条目格式分隔符未逐字复制；各条目按原序独立注入"
        val modelNode = WorkflowNode(id = modelNodeId, title = "主回复", actionId = "model.generate",
            inputTemplate = "{{input}}", outputKey = "reply", visible = true,
            includeHistory = true, explicitHistory = true, blocks = before + sortedInChat + after)
        val mainWorkflow = Workflow(id = workflowId, title = "主回复", nodes = listOf(modelNode))

        val depthNodes = world.filter { it.position == 4 }.map { item ->
            WorkflowNode(id = id("node", "world-depth:${item.uid}:$sourceName"),
                title = item.title, actionId = "prompt.inject", outputKey = "world_${item.uid}",
                inputTemplate = "{{pack.$packNamespace.${item.key}}}", enabled = item.enabled,
                includeHistory = false, recognition = item.recognition,
                arguments = mapOf("id" to "world-${item.uid}", "role" to item.role.name,
                    "position" to PromptPosition.IN_HISTORY.name, "depth" to item.depth.coerceAtLeast(0).toString(),
                    "targetWorkflowId" to workflowId, "targetNodeId" to modelNodeId,
                    "scope" to InjectionScope.TURN.name))
        }
        val depthWorkflow = Workflow(id = id("workflow", "world-depth:$sourceName"), title = "场景深度提示",
            trigger = RuntimeEvents.TURN_PREPARE, nodes = depthNodes)
        val group = CallGroup(id = groupId, name = "$sourceName · 场景注入",
            workflows = if (depthNodes.isEmpty()) emptyList() else listOf(depthWorkflow),
            boundPackageIds = listOf(packId))

        val statusTitles = listOf("location" to "地点", "date" to "日期", "time" to "时间", "weather" to "天气",
            "top" to "上装", "bottom" to "下装", "shoes" to "鞋履", "pose" to "姿态", "posture" to "动作",
            "state" to "情绪", "closeup" to "神态", "person" to "在场人物", "love" to "亲近度", "level" to "阶段")
        val statusFields = statusTitles.map { (key, title) ->
            MacroStatusField(key = key, title = title,
                kind = when (key) { "love" -> MacroTileKind.PROGRESS; "level" -> MacroTileKind.NUMBER; else -> MacroTileKind.TEXT },
                minimum = if (key == "level") 1.0 else 0.0,
                maximum = if (key == "level") 7.0 else 100.0)
        }
        val statusInstruction = "根据角色资料和刚完成的对话，简短记录普通、非性化的日常场景状态。" +
            "只输出一个有效的 <StatusBlock> XML，不要解释；其中 location/date/time/weather/top/bottom/shoes/pose/" +
            "posture/state/closeup/person/love/level 各有一个元素。文本中的 &、<、> 等 XML 特殊字符必须转义。" +
            "服饰仅写普通日常衣物；love 只写 0–100 的数字，表示普通友好程度；level 只写 1–7 的数字，表示日常阶段。"
        val statusNode = WorkflowNode(id = id("node", "status:$sourceName"), title = "场景状态",
            actionId = "model.generate", inputTemplate = "{{input}}", outputKey = "status", visible = false,
            includeHistory = true, explicitHistory = true,
            blocks = listOf(PromptBlock(id = id("block", "status-instruction:$sourceName"),
                title = "状态格式", template = statusInstruction),
                PromptBlock(id = id("block", "status-profile:$sourceName"),
                    title = "角色资料", template = "{{character.profile}}"),
                PromptBlock(id = id("block", "status-history:$sourceName"), title = "对话历史", template = "{{history}}")),
            outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT,
                pattern = "(?s)<StatusBlock>.*?</StatusBlock>"), TextProcessStep(TextProcessKind.TRIM)))
        val statusWorkflow = Workflow(id = id("workflow", "status:$sourceName"), title = "回复后状态",
            trigger = RuntimeEvents.REPLY_COMPLETED, nodes = listOf(statusNode))
        val tileTemplate = "{{result.status}}"
        val tiles = listOf(MacroTile(id = id("tile", "chat-status:$sourceName"), title = "场景状态",
            template = tileTemplate, kind = MacroTileKind.STATUS, slot = OutputSlot.AFTER_BODY,
            hideWhenEmpty = false, placement = MacroTilePlacement.CHAT, fields = statusFields),
            MacroTile(id = id("tile", "sidebar-status:$sourceName"), title = "场景侧栏",
                template = tileTemplate, kind = MacroTileKind.STATUS, slot = OutputSlot.AFTER_BODY,
                hideWhenEmpty = false, placement = MacroTilePlacement.SIDEBAR, fields = statusFields))
        val main = MainCall(id = mainId, name = preset.string("name").ifBlank { "$sourceName · 对话" },
            workflows = listOf(mainWorkflow, statusWorkflow), primaryWorkflowId = workflowId,
            boundPackageIds = listOf(packId), boundGroupIds = listOf(groupId), tiles = tiles)

        val persona = if (existing.personas.isEmpty()) UserPersona(id = id("persona", "legacy-user"),
            name = existing.user.name, avatar = existing.user.avatar) else null
        val chosenPersonaId = existing.selectedPersonaId
            ?: existing.sessions.firstOrNull { it.id == existing.selectedSessionId }?.personaId
            ?: persona?.id
        val incoming = Library(characters = listOf(card), mainCalls = listOf(main), packages = listOf(pack),
            groups = listOf(group), personas = listOfNotNull(persona), selectedPersonaId = persona?.id,
            user = existing.user)
        val greetingContext = incoming.copy(personas = existing.personas + listOfNotNull(persona),
            selectedPersonaId = chosenPersonaId)
        val started = greetingContext.startNewConversation(card.id).sessions.single()
        val session = started.copy(id = id("session", sourceName),
            messages = started.messages.mapIndexed { index, message ->
                message.copy(id = id("message", "opening:$sourceName:$index"))
            })
        root.array("notes").map { it.asString().trim() }.filter { it.isNotBlank() }
            .forEach { warnings += "导入说明：$it" }
        return ImportResult(incoming.copy(sessions = listOf(session), selectedSessionId = session.id), warnings.toList())
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String = this[key].asString()
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.asString(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
}
