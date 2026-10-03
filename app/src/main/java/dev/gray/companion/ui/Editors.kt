package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders

@Composable
private fun EntryForm(entry: PromptEntry, index: Int, count: Int, onChange: (PromptEntry) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, onDuplicate: () -> Unit, canMove: Boolean = true, initiallyExpanded: Boolean = false, macro: String? = null, siblings: List<PromptEntry> = emptyList(), tags: List<PromptTag> = emptyList()) {
    var confirmDelete by remember(entry.id) { mutableStateOf(false) }
    var rename by remember(entry.id) { mutableStateOf(false) }
    val summary = listOfNotNull(
        entry.title.takeIf { it.isNotBlank() },
        entry.key.takeIf { it.isNotBlank() }?.let { "宏键 $it" },
        entry.tagIds.takeIf { it.isNotEmpty() }?.joinToString("、") { id -> tags.firstOrNull { it.id == id }?.name ?: "未找到标签" },
        entry.text.lineSequence().firstOrNull()?.trim()?.take(56)?.takeIf { it.isNotBlank() },
        if (!entry.enabled) "已停用" else null,
        if (entry.condition != null) "有条件" else null
    ).joinToString(" · ").ifBlank { "尚未填写内容" }
    EditorSection("条目 ${index + 1} · ${entry.title.ifBlank { entry.key.ifBlank { "新条目" } }}", summary, initiallyExpanded) {
        EditorTitleRow("${entry.title} / ${entry.slug}", { rename = true })
        LegacyAddressLine(macro ?: "character.${entry.key}")
        PromptTagSelector(tags, entry.tagIds) { onChange(entry.copy(tagIds = it)) }
        TextFieldLine("分类（可选）", entry.category, { onChange(entry.copy(category = it)) })
        TextFieldLine("内容", entry.text, { onChange(entry.copy(text = it)) }, minLines = 3)
        var advanced by remember(entry.id) { mutableStateOf(false) }
        if (macro != null) TextButton(onClick = { advanced = !advanced }) {
            Text("${if (advanced) "收起可选设置" else "可选设置"}${if (!entry.enabled) " · 已停用" else if (entry.condition != null) " · 有激活条件" else ""}")
        }
        if (macro == null || advanced) {
            ToggleLine("启用", entry.enabled) { onChange(entry.copy(enabled = it)) }
            ConditionForm(entry.condition) { onChange(entry.copy(condition = it)) }
        }
        if (canMove) MoveButtons(index, count, onUp, onDown, { confirmDelete = true })
        else TextButton(onClick = { confirmDelete = true }) { Text("删除") }
        TextButton(onClick = onDuplicate) { Text("复制词条") }
    }
    if (rename) NamingDialog("词条名称", entry.title, entry.slug,
        siblings.filterNot { it.id == entry.id }.map { it.title }, siblings.filterNot { it.id == entry.id }.map { it.slug },
        { rename = false }) { title, slug -> onChange(entry.copy(title = title, slug = slug)); rename = false }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除词条？") },
        text = { Text("「${entry.key.ifBlank { "未命名词条" }}」将从当前草稿移除。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onRemove() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } }
    )
}

private val macroEntryKey = Regex("[\\p{L}_][\\p{L}\\p{N}_-]*")

private fun duplicateEntry(entry: PromptEntry, existing: List<PromptEntry>, character: Boolean = false): PromptEntry {
    val stem = entry.key.takeIf { macroEntryKey.matches(it) && (!character || it != "name") } ?: "entry"
    val used = existing.map { it.key }.toSet() + if (character) setOf("name") else emptySet()
    val base = "${stem}_copy"
    val key = generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_${it}" }.first { it !in used }
    return entry.copy(id = newId(), key = key,
        title = nextDisplayName(existing.map { it.title }, entry.title.ifBlank { "词条" }),
        slug = nextEditorSlug(existing.map { it.slug }, "entry"))
}

private fun entryMatches(entry: PromptEntry, query: String, tags: List<PromptTag>): Boolean =
    (listOf(entry.title, entry.slug, entry.key, entry.category, entry.text) +
        tags.filter { it.id in entry.tagIds }.flatMap { listOf(it.name, it.slug) })
        .any { it.contains(query, ignoreCase = true) }

@Composable
internal fun CharacterEditor(initial: CharacterCard, packages: List<PromptPackage>, onSave: (CharacterCard) -> Unit, onBack: () -> Unit, saving: Boolean = false, library: Library? = null) {
    val cardBaseline = remember(initial.id) { MacroNaming.normalize(CharacterFields.normalize(initial),
        library?.characters.orEmpty().filterNot { it.id == initial.id }.map { it.slug }).let { card ->
        if (card.name.isBlank() || (library != null && library.characters.none { it.id == card.id }))
            card.copy(name = nextDisplayName(library?.characters.orEmpty().filterNot { it.id == card.id }.map { it.name }, "角色"))
        else card
    } }
    var card by remember(initial.id) { mutableStateOf(cardBaseline) }
    var renameCard by remember(initial.id) { mutableStateOf(false) }
    var avatarProcessing by remember(initial.id) { mutableStateOf(false) }
    var avatarEditorOpen by remember(initial.id) { mutableStateOf(false) }
    var extraSearch by remember(initial.id) { mutableStateOf("") }
    var extrasRevealKey by remember(initial.id) { mutableIntStateOf(0) }
    val newlyExpandedEntries = remember(initial.id) { mutableStateListOf<String>() }
    val greetingReferences = remember(card, library, packages) {
        val base = library ?: Library(packages = packages)
        val selected = base.sessions.firstOrNull { it.id == base.selectedSessionId }
        // Opening messages belong to a fresh chat; old results/versions are not a preview source.
        val context = Session(id = "greeting-editor:${card.id}", characterId = card.id,
            mainCallId = selected?.mainCallId ?: base.mainCalls.firstOrNull()?.id.orEmpty(),
            packageIds = selected?.packageIds.orEmpty(), groupIds = selected?.groupIds.orEmpty(),
            personaId = base.selectedPersonaId ?: selected?.personaId, updatedAt = 0L)
        val sources = base.copy(characters = base.characters.filterNot { it.id == card.id } + card,
            sessions = listOf(context), selectedSessionId = context.id)
        val main = sources.mainCalls.firstOrNull { it.id == context.mainCallId }
        promptReferenceChoices(main?.workflows.orEmpty(), sources)
    }
    EditorPage("角色卡", onBack, { onSave(card.copy(name = card.name.trim())) }, dirty = card != cardBaseline, saving = saving || avatarProcessing) {
        CardGroup {
            item(
                onClick = { avatarEditorOpen = !avatarEditorOpen },
                leadingContent = {
                    UIAvatar(
                        name = card.name,
                        value = card.avatar?.let { Avatar.Image("data:${it.mediaType};base64,${it.base64}") } ?: Avatar.Dummy,
                        onClick = { avatarEditorOpen = !avatarEditorOpen },
                        modifier = Modifier.size(48.dp),
                    )
                },
                headlineContent = { Text("头像") },
                supportingContent = { Text("点击更换或移除头像") },
            )
        }
        if (avatarEditorOpen) CharacterAvatarEditor(card, { avatarProcessing = it }) { card = card.copy(avatar = it) }
        EditorTitleRow("${card.name} / ${card.slug}", { renameCard = true })
        EditorSection("卡片信息", card.namespace.ifBlank { card.description.lineSequence().firstOrNull()?.take(48) ?: "说明与命名空间" }) {
            TextFieldLine("说明", card.description, { card = card.copy(description = it) }, minLines = 2)
            LegacyAddressLine("character.${card.namespace}.*")
        }
        Text("基础设定", style = MaterialTheme.typography.titleMedium)
        CharacterFields.definitions.forEach { field ->
            val entry = card.entries.first { it.key == field.key }
            var renameBase by remember(entry.id) { mutableStateOf(false) }
            EditorTitleRow("${entry.title.ifBlank { field.title }} / ${entry.slug}", { renameBase = true })
            if (renameBase) NamingDialog("${field.title}名称", entry.title.ifBlank { field.title }, entry.slug,
                card.entries.filterNot { it.id == entry.id }.map { it.title }, card.entries.filterNot { it.id == entry.id }.map { it.slug },
                { renameBase = false }) { title, slug ->
                card = card.copy(entries = card.entries.map { if (it.id == entry.id) it.copy(title = title, slug = slug) else it })
                renameBase = false
            }
            PromptTagSelector(library?.promptTags.orEmpty(), entry.tagIds) { selected ->
                card = card.copy(entries = card.entries.map { if (it.id == entry.id) it.copy(tagIds = selected) else it })
            }
            OutlinedTextField(
                value = entry.text,
                onValueChange = { text ->
                    card = card.copy(entries = card.entries.map {
                        if (it.key == field.key) it.copy(text = text) else it
                    })
                },
                label = { Text(field.title) },
                placeholder = { Text(field.hint) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                shape = MaterialTheme.shapes.large
            )
            SelectionContainer { Text("{{character.${field.key}}}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (field.key == "profile") {
                PromptTemplateField("开场消息（仅新对话）", card.greeting, greetingReferences,
                    { card = card.copy(greeting = it) }, minLines = 3)
                Text("新建对话时显示一次，不请求模型；可引用 {{user.name}} 与角色资料。留空时不添加开场消息。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val boundNames = card.boundPackageIds.map { id ->
            packages.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.namespace } } ?: id
        }
        EditorSection("关联提示词包", boundNames.joinToString("、").ifBlank { "暂无绑定" }) {
            Text("一起加载；引用后才进入提示词。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ResourceBindingList(packages.map { it.id to it.name.ifBlank { it.namespace } }, card.boundPackageIds) {
                card = card.copy(boundPackageIds = it)
            }
        }
        val extras = card.entries.filterNot { CharacterFields.isBasic(it.key) }
        key(extrasRevealKey) {
            EditorSection("附加条目", "${extras.size} 项 · ${extras.take(2).joinToString("、") { it.key.ifBlank { "未命名" } }}", initiallyExpanded = extrasRevealKey > 0) {
                Text("宏键不能与七项基础设定或 name 重复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextFieldLine("搜索名称、宏键、标签或内容", extraSearch, { extraSearch = it })
                if (extraSearch.isNotBlank()) Text("筛选期间暂停排序；保存仍包含全部条目。", style = MaterialTheme.typography.bodySmall)
                extras.forEachIndexed { i, entry -> if (extraSearch.isBlank() || entryMatches(entry, extraSearch.trim(), library?.promptTags.orEmpty())) key(entry.id) {
                    EntryForm(entry, i, extras.size,
                        { changed -> card = card.copy(entries = card.entries.map { if (it.id == entry.id) changed else it }) },
                        { card = card.copy(entries = card.entries.withCharacterExtras(extras.moved(i, i - 1))) },
                        { card = card.copy(entries = card.entries.withCharacterExtras(extras.moved(i, i + 1))) },
                        { card = card.copy(entries = card.entries.filterNot { it.id == entry.id }) },
                        {
                            val duplicate = duplicateEntry(entry, card.entries, character = true)
                            card = card.copy(entries = card.entries + duplicate)
                            newlyExpandedEntries += duplicate.id
                        },
                        canMove = extraSearch.isBlank(), initiallyExpanded = entry.id in newlyExpandedEntries,
                        siblings = card.entries, tags = library?.promptTags.orEmpty())
                } }
            }
        }
        OutlinedButton(onClick = {
            val key = nextEditorSlug(card.entries.map { it.key }, "entry")
            val entry = PromptEntry(key = key, title = nextDisplayName(card.entries.map { it.title }, "词条"),
                slug = nextEditorSlug(card.entries.map { it.slug }, "entry"))
            card = card.copy(entries = card.entries + entry)
            extraSearch = ""
            newlyExpandedEntries += entry.id
            extrasRevealKey++
        }) { Text("添加附加条目") }
    }
    if (renameCard) NamingDialog("角色卡名称", card.name, card.slug,
        library?.characters.orEmpty().filterNot { it.id == card.id }.map { it.name },
        library?.characters.orEmpty().filterNot { it.id == card.id }.map { it.slug },
        { renameCard = false }) { name, slug -> card = card.copy(name = name, slug = slug); renameCard = false }
}

private fun List<PromptEntry>.withCharacterExtras(extras: List<PromptEntry>): List<PromptEntry> {
    val replacement = extras.iterator()
    return map { entry -> if (CharacterFields.isBasic(entry.key)) entry else replacement.next() }
}

@Composable
internal fun PackageEditor(initial: PromptPackage, library: Library, onSave: (PromptPackage, Set<String>, Set<String>) -> Unit, onBack: () -> Unit, saving: Boolean = false) {
    val baseline = remember(initial.id) { MacroNaming.normalize(initial.copy(
        namespace = if (initial.namespace.isBlank() && library.packages.none { it.id == initial.id })
            nextEditorSlug((library.characters.map { it.namespace } + library.packages.map { it.namespace }), "pack")
            else initial.namespace,
        entries = initial.entries.sortedBy { it.order }),
        library.packages.filterNot { it.id == initial.id }.map { it.slug }).let { pack ->
        if (pack.name.isBlank()) pack.copy(name = nextDisplayName(library.packages.filterNot { it.id == pack.id }.map { it.name }, "提示词包")) else pack
    } }
    var pack by remember(initial.id) { mutableStateOf(baseline) }
    var renamePack by remember(initial.id) { mutableStateOf(false) }
    val initialCharacters = remember(initial.id) { library.characters.filter { initial.id in it.boundPackageIds }.map { it.id }.toSet() }
    val initialMainCalls = remember(initial.id) { library.mainCalls.filter { initial.id in it.boundPackageIds }.map { it.id }.toSet() }
    var characterIds by remember(initial.id) { mutableStateOf(initialCharacters) }
    var mainCallIds by remember(initial.id) { mutableStateOf(initialMainCalls) }
    var bindingsExpanded by remember(initial.id) { mutableStateOf(false) }
    var entrySearch by remember(initial.id) { mutableStateOf("") }
    val newlyExpandedEntries = remember(initial.id) { mutableStateListOf<String>() }
    EditorPage("提示词包", onBack, { onSave(pack.copy(entries = pack.entries.mapIndexed { index, entry -> entry.copy(order = index) }), characterIds, mainCallIds) }, dirty = pack != baseline || characterIds != initialCharacters || mainCallIds != initialMainCalls, saving = saving) {
        EditorTitleRow("${pack.name} / ${pack.slug}", { renamePack = true })
        EditorSection("引用地址", "pack.${pack.namespace}.*") { LegacyAddressLine("pack.${pack.namespace}.*") }
        Text("载入后通过宏引用内容。可以随角色卡、主调用加载，也可以在当前对话中单独加载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val boundNames = remember(library.characters, library.mainCalls, characterIds, mainCallIds) {
            library.characters.filter { it.id in characterIds }.map { "角色：${it.name}" } + library.mainCalls.filter { it.id in mainCallIds }.map { "主调用：${it.name}" }
        }
        Text("角色卡／主调用关联：${boundNames.joinToString("、").ifBlank { "暂无绑定" }}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { bindingsExpanded = !bindingsExpanded }) { Text(if (bindingsExpanded) "收起绑定" else "设置绑定") }
        if (bindingsExpanded) {
            Text("角色卡", style = MaterialTheme.typography.titleSmall)
            ResourceBindingList(library.characters.map { it.id to it.name }, characterIds.toList()) { characterIds = it.toSet() }
            Text("主调用", style = MaterialTheme.typography.titleSmall)
            ResourceBindingList(library.mainCalls.map { it.id to it.name }, mainCallIds.toList()) { mainCallIds = it.toSet() }
        }
        Text("条目（{{pack.${pack.namespace.ifBlank { "namespace" }}.键}}）", style = MaterialTheme.typography.titleMedium)
        if (pack.entries.isEmpty()) Text("添加词条后直接填写内容，无需配置调用。", style = MaterialTheme.typography.bodySmall)
        TextFieldLine("搜索名称、宏键、分类或内容", entrySearch, { entrySearch = it })
        if (entrySearch.isNotBlank()) Text("筛选期间暂停排序；保存仍包含全部条目。", style = MaterialTheme.typography.bodySmall)
        pack.entries.forEachIndexed { i, entry -> if (entrySearch.isBlank() || entryMatches(entry, entrySearch.trim(), library.promptTags)) key(entry.id) {
            EntryForm(entry, i, pack.entries.size,
                { changed -> pack = pack.copy(entries = pack.entries.toMutableList().apply { set(i, changed) }) },
                { pack = pack.copy(entries = pack.entries.moved(i, i - 1)) },
                { pack = pack.copy(entries = pack.entries.moved(i, i + 1)) },
                { pack = pack.copy(entries = pack.entries.filterNot { it.id == entry.id }) },
                {
                    val duplicate = duplicateEntry(entry, pack.entries)
                    pack = pack.copy(entries = pack.entries.toMutableList().apply { add(i + 1, duplicate) })
                    entrySearch = ""
                    newlyExpandedEntries += duplicate.id
                },
                canMove = entrySearch.isBlank(), initiallyExpanded = entry.id in newlyExpandedEntries,
                macro = "{{pack.${pack.namespace.ifBlank { "namespace" }}.${entry.key.ifBlank { "键" }}}}",
                siblings = pack.entries, tags = library.promptTags)
        } }
        OutlinedButton(onClick = {
            val entry = PromptEntry(key = nextEditorSlug(pack.entries.map { it.key }, "entry"),
                title = nextDisplayName(pack.entries.map { it.title }, "词条"),
                slug = nextEditorSlug(pack.entries.map { it.slug }, "entry"))
            pack = pack.copy(entries = pack.entries + entry)
            entrySearch = ""
            newlyExpandedEntries += entry.id
        }) { Text("添加条目") }
    }
    if (renamePack) NamingDialog("提示词包名称", pack.name, pack.slug,
        library.packages.filterNot { it.id == pack.id }.map { it.name },
        library.packages.filterNot { it.id == pack.id }.map { it.slug },
        { renamePack = false }) { name, slug -> pack = pack.copy(name = name, slug = slug); renamePack = false }
}

@Composable
internal fun WorkflowForm(workflow: Workflow, index: Int, count: Int, actions: List<ActionDescriptor>, profiles: List<ModelProfile>, usedOutputKeys: Set<String>, references: List<PromptReferenceChoice>, onChange: (Workflow) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, fixedTrigger: Boolean = false, compactSettings: Boolean = false, tags: List<PromptTag> = emptyList(), siblings: List<Workflow> = emptyList()) {
    var promptRequest by remember(workflow.id) { mutableStateOf<PromptEditRequest?>(null) }
    var rename by remember(workflow.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EditorTitleRow(workflow.title, { rename = true }) { Switch(workflow.enabled, { onChange(workflow.copy(enabled = it)) }) }
        WorkflowEditorContent(workflow, actions, profiles, usedOutputKeys, references, onChange, { promptRequest = it }, fixedTrigger, tags)
        if (!fixedTrigger) MoveButtons(index, count, onUp, onDown, onRemove)
    }
    if (rename) NamingDialog("调用名称", workflow.title, workflow.slug,
        siblings.filterNot { it.id == workflow.id }.map { it.title }, siblings.filterNot { it.id == workflow.id }.map { it.slug },
        { rename = false }) { title, slug -> onChange(workflow.copy(title = title, slug = slug)); rename = false }
    promptRequest?.let { request ->
        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            PromptBlocksEditor(request.blocks, request.references, { request.save(it); promptRequest = null }, { promptRequest = null }, tags = tags)
        }
    }
}
@Composable
internal fun ProfileEditor(initial: ModelProfile, connections: List<ApiConnection>, onSave: (ModelProfile) -> Unit, onBack: () -> Unit, saving: Boolean = false) {
    var profile by remember(initial.id) { mutableStateOf(initial) }
    var temperature by remember(initial.id) { mutableStateOf(profile.temperature?.toString().orEmpty()) }
    var topP by remember(initial.id) { mutableStateOf(profile.topP?.toString().orEmpty()) }
    var maxTokens by remember(initial.id) { mutableStateOf(profile.maxTokens?.toString().orEmpty()) }
    var numericError by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf(false) }
    EditorPage("模型配置", onBack, {
        val parsedTemperature = temperature.toFloatOrNull()
        val parsedTopP = topP.toFloatOrNull()
        val parsedMaxTokens = maxTokens.toIntOrNull()
        numericError = (temperature.isNotBlank() && (parsedTemperature == null || parsedTemperature !in 0f..2f)) ||
            (topP.isNotBlank() && (parsedTopP == null || parsedTopP !in 0f..1f)) ||
            (maxTokens.isNotBlank() && (parsedMaxTokens == null || parsedMaxTokens <= 0))
        connectionError = connections.none { it.id == profile.apiConnectionId }
        if (!numericError && !connectionError) onSave(profile.copy(temperature = parsedTemperature, topP = parsedTopP, maxTokens = parsedMaxTokens))
    }, dirty = profile != initial || temperature != initial.temperature?.toString().orEmpty() ||
        topP != initial.topP?.toString().orEmpty() || maxTokens != initial.maxTokens?.toString().orEmpty(), saving = saving) {
        TextFieldLine("名称", profile.name, { profile = profile.copy(name = it) })
        TextFieldLine("模型 ID", profile.model, { profile = profile.copy(model = it) })
        val connectionChoices = listOf<String?>(null).map { it to "请选择 API 连接" } + connections.map { it.id to it.name.ifBlank { it.protocol.name } } +
            if (profile.apiConnectionId != null && connections.none { it.id == profile.apiConnectionId }) listOf(profile.apiConnectionId to "未找到连接：${profile.apiConnectionId}") else emptyList()
        ChoiceLine("API 连接", profile.apiConnectionId, connectionChoices) { profile = profile.copy(apiConnectionId = it); connectionError = false }
        if (connections.isEmpty()) Text("先在模型连接页创建 API 连接，再选择它。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (connectionError) Text("请先选择一个 API 连接。", color = MaterialTheme.colorScheme.error)
        TextFieldLine("温度（留空使用默认）", temperature, { temperature = it })
        TextFieldLine("Top P（留空使用默认）", topP, { topP = it })
        TextFieldLine("最大输出 token（留空使用默认）", maxTokens, { maxTokens = it })
        if (numericError) Text("温度须在 0–2、Top P 须在 0–1，最大输出 token 须为正整数；也可留空。", color = MaterialTheme.colorScheme.error)
        val nativeReasoning = ReasoningLevel.valueOf(profile.reasoning.name)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("推理等级")
            ReasoningButton(reasoningLevel = nativeReasoning, onUpdateReasoningLevel = {
                profile = profile.copy(reasoning = ModelReasoning.valueOf(it.name))
            })
        }
        EditorSection("自定义请求参数", "${profile.customBody.size} 项") {
            CustomBodies(profile.customBody.map { CustomBody(it.key, it.value) }) { bodies ->
                profile = profile.copy(customBody = bodies.map { RequestBodyField(it.key, it.value) })
            }
        }
    }
}

@Composable
internal fun ConnectionEditor(initial: ApiConnection, apiKey: String, onSave: (ApiConnection, String) -> Unit, onBack: () -> Unit, saving: Boolean = false) {
    var connection by remember(initial.id) { mutableStateOf(initial) }
    var key by remember(initial.id) { mutableStateOf(apiKey) }
    EditorPage("API 连接", onBack, { onSave(connection, key) }, dirty = connection != initial || key != apiKey, saving = saving) {
        TextFieldLine("名称", connection.name, { connection = connection.copy(name = it) })
        ChoiceLine("协议", connection.protocol, listOf(Protocol.OPENAI to "OpenAI", Protocol.GOOGLE to "Google", Protocol.CLAUDE to "Claude")) { protocol ->
            connection = connection.copy(protocol = protocol, baseUrl = when (protocol) {
                Protocol.OPENAI -> "https://api.openai.com/v1"
                Protocol.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta"
                Protocol.CLAUDE -> "https://api.anthropic.com"
            })
        }
        TextFieldLine("接口地址", connection.baseUrl, { connection = connection.copy(baseUrl = it) })
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key（本机加密保存，导出不包含）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (connection.protocol == Protocol.OPENAI) ToggleLine("使用 Responses API", connection.responseApi) { connection = connection.copy(responseApi = it) }
        ChoiceLine("同时请求", connection.maxConcurrentRequests, listOf(1 to "1 个（串行）", 2 to "最多 2 个")) {
            connection = connection.copy(maxConcurrentRequests = it)
        }
        EditorSection("自定义请求头", "${connection.customHeaders.size} 项") {
            CustomHeaders(connection.customHeaders.map { CustomHeader(it.name, it.value) }) { headers ->
                connection = connection.copy(customHeaders = headers.map { RequestHeader(it.name, it.value) })
            }
        }
        ToggleLine("启用连接", connection.enabled) { connection = connection.copy(enabled = it) }
    }
}
