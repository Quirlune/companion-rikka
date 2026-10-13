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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.gray.companion.core.*
import kotlinx.serialization.Serializable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.RikkaNavDisplay
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors

@Serializable
private sealed interface CharacterEditorRoute : NavKey {
    @Serializable data object Home : CharacterEditorRoute
    @Serializable data object BasicSettings : CharacterEditorRoute
}

@Composable
private fun EntryForm(entry: PromptEntry, index: Int, count: Int, onChange: (PromptEntry) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit, onDuplicate: () -> Unit, canMove: Boolean = true, initiallyExpanded: Boolean = false, macro: String? = null, siblings: List<PromptEntry> = emptyList(), tags: List<PromptTag> = emptyList(), owner: ExtensionFieldRef? = null) {
    val sourceNavigation = LocalSourceNavigation.current
    val references = sourceNavigation?.library?.let { promptReferenceChoices(emptyList(), it) }.orEmpty()
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
    EditorSection("条目 ${index + 1} · ${entry.title.ifBlank { entry.key.ifBlank { "新条目" } }}", summary, initiallyExpanded || sourceNavigation?.focus?.entryId == entry.id) {
        EditorTitleRow("${entry.title} / ${entry.slug}", { rename = true }) {
            PromptTagSelector(tags, entry.tagIds, compact = true) { onChange(entry.copy(tagIds = it)) }
        }
        LegacyAddressLine(macro ?: "character.${entry.key}")
        TextFieldLine("分类（可选）", entry.category, { onChange(entry.copy(category = it)) })
        PromptTemplateField("内容", entry.text, references, { onChange(entry.copy(text = it)) }, minLines = 3, owner = owner)
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
    if (confirmDelete) {
        val location = owner?.let { ResourceLocations.fromField(it).copy(field = null) }
        val plan = if (sourceNavigation != null && location != null) ResourceEditCommands.previewDelete(sourceNavigation.library, location) else null
        AlertDialog(
            onDismissRequest = { confirmDelete = false }, title = { Text("删除词条？") }, text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("「${entry.title.ifBlank { entry.key }}」将从当前草稿移除。已有引用须先处理。")
                    plan?.issues.orEmpty().forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    plan?.usages.orEmpty().forEach { use ->
                        val target = use.location
                        if (target != null) TextButton(onClick = { confirmDelete = false; sourceNavigation?.edit(target) }) { Text(use.label) }
                        else Text(use.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(enabled = plan?.canApply ?: true, onClick = { confirmDelete = false; onRemove() }) { Text("删除词条") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留") } })
    }

}

private fun duplicateEntry(entry: PromptEntry, existing: List<PromptEntry>, character: Boolean = false): PromptEntry {
    return ResourceEditCommands.duplicateEntry(entry, existing, character)
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
    val stack = remember(initial.id) { mutableStateListOf<NavKey>(CharacterEditorRoute.Home) }
    val backs = remember(initial.id) { mutableStateMapOf<NavKey, () -> Unit>() }
    var bindingSheet by remember(initial.id) { mutableStateOf<String?>(null) }
    fun openBasics() { if (stack.last() != CharacterEditorRoute.BasicSettings) stack.add(CharacterEditorRoute.BasicSettings) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    val newlyExpandedEntries = remember(initial.id) { mutableStateListOf<String>() }
    val greetingReferences = remember(card, library, packages) {
        val base = library ?: Library(packages = packages)
        val selected = base.sessions.firstOrNull { it.id == base.selectedSessionId }
        // Opening messages belong to a fresh chat; old results/versions are not a preview source.
        val context = Session(id = "greeting-editor:${card.id}", characterId = card.id,
            mainCallId = selected?.mainCallId ?: base.mainCalls.firstOrNull()?.id.orEmpty(),
            packageIds = selected?.packageIds.orEmpty(), groupIds = selected?.groupIds.orEmpty(),
            personaId = card.boundPersonaId ?: base.selectedPersonaId ?: selected?.personaId, updatedAt = 0L)
        val sources = base.copy(characters = base.characters.filterNot { it.id == card.id } + card,
            sessions = listOf(context), selectedSessionId = context.id)
        val main = sources.mainCalls.firstOrNull { it.id == context.mainCallId }
        promptReferenceChoices(main?.workflows.orEmpty(), sources)
    }
    val sourceResource = ExtensionResourceRef(ExtensionResourceKind.CHARACTER, card.id)
    val sourceFocus = LocalSourceNavigation.current?.focus
    LaunchedEffect(sourceFocus) {
        if (sourceFocus != null && sourceFocus.resource == sourceResource) {
            when {
                sourceFocus.section == "boundPackageIds" -> bindingSheet = "packages"
                sourceFocus.section == "boundGroupIds" -> bindingSheet = "groups"
                sourceFocus.entryId != null -> {
                    if (card.entries.any { it.id == sourceFocus.entryId && CharacterFields.isBasic(it.key) }) openBasics()
                    else {
                        extraSearch = ""
                        sourceFocus.entryId?.let { newlyExpandedEntries.add(it) }
                        extrasRevealKey++
                    }
                }
            }
        }
    }
    SourceDraftBoundary(sourceResource,
        overlay = { base -> base.withSourceResource(base.copy(characters = listOf(card)), sourceResource) },
        restore = { candidate, parent -> candidate.withSourceResource(parent, sourceResource) },
        accept = { candidate -> candidate.characters.firstOrNull { it.id == card.id }?.let { card = it } }) {
    RikkaNavDisplay(stack, { backs[stack.last()]?.invoke() ?: pop() }, entryProvider {
    entry<CharacterEditorRoute.Home> { route ->
    CallEditorPage("角色卡", card != cardBaseline, onBack, { onSave(card.copy(name = card.name.trim())) },
        saving = saving || avatarProcessing, onRegisterBack = { backs[route] = it }) {
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
            )
        }
        if (avatarEditorOpen) CharacterAvatarEditor(card, { avatarProcessing = it }) { card = card.copy(avatar = it) }
        EditorTitleRow("${card.name} / ${card.slug}", { renameCard = true })
        HorizontalDivider()
        EditorSection("卡片信息", card.namespace.ifBlank { card.description.lineSequence().firstOrNull()?.take(48) ?: "说明与命名空间" }) {
            TextFieldLine("说明", card.description, { card = card.copy(description = it) }, minLines = 2)
            LegacyAddressLine("character.${card.namespace}.*")
        }
        HorizontalDivider()
        val filledBasics = card.entries.count { CharacterFields.isBasic(it.key) && it.text.isNotBlank() }
        EditorDestinationRow("基础设定", "${CharacterFields.definitions.size} 项设定 · 已填写 $filledBasics 项", ::openBasics)
        HorizontalDivider()
        EditorSection("开场消息", "${1 + card.alternateGreetings.size} 个选项",
            initiallyExpanded = sourceFocus?.resource == sourceResource &&
                (sourceFocus.section == "greeting" || sourceFocus.section?.startsWith("alternateGreetings") == true)) {
                PromptTemplateField("首选开场消息（仅新对话）", card.greeting, greetingReferences,
                    { card = card.copy(greeting = it) }, minLines = 3)
                card.alternateGreetings.forEachIndexed { index, greeting -> key(index) {
                    PromptTemplateField("备选开场 ${index + 2}", greeting, greetingReferences,
                        { changed -> card = card.copy(alternateGreetings = card.alternateGreetings.toMutableList().also { it[index] = changed }) }, minLines = 3)
                    MoveButtons(index, card.alternateGreetings.size,
                        { card = card.copy(alternateGreetings = card.alternateGreetings.moved(index, index - 1)) },
                        { card = card.copy(alternateGreetings = card.alternateGreetings.moved(index, index + 1)) },
                        { card = card.copy(alternateGreetings = card.alternateGreetings.filterIndexed { i, _ -> i != index }) })
                } }
                OutlinedButton(onClick = { card = card.copy(alternateGreetings = card.alternateGreetings + "") }) { Text("添加备选开场") }
        }
        val boundNames = card.boundPackageIds.map { id ->
            packages.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.namespace } } ?: id
        }
        HorizontalDivider()
        Text("关联资源", style = MaterialTheme.typography.titleMedium)
        val personaChoices = listOf<String?>(null).map { it to "不绑定 · 使用新聊天默认身份" } +
            library?.personas.orEmpty().map { it.id to it.name.ifBlank { "未命名身份" } } +
            if (card.boundPersonaId != null && library?.personas.orEmpty().none { it.id == card.boundPersonaId })
                listOf(card.boundPersonaId to "身份已不可用：${card.boundPersonaId}") else emptyList()
        ChoiceLine("新对话默认用户身份", card.boundPersonaId, personaChoices) { card = card.copy(boundPersonaId = it) }
        EditorSheetButton("关联提示词包", boundNames.joinToString("、").ifBlank { "暂无绑定" }) { bindingSheet = "packages" }
        val groups = library?.groups.orEmpty()
        val boundGroupNames = card.boundGroupIds.map { id ->
            groups.firstOrNull { it.id == id }?.name?.ifBlank { "未命名调用组" } ?: "未找到调用组"
        }
        EditorSheetButton("关联调用组", boundGroupNames.joinToString("、").ifBlank { "暂无绑定" }) { bindingSheet = "groups" }
        HorizontalDivider()
        val extras = card.entries.filterNot { CharacterFields.isBasic(it.key) }
        key(extrasRevealKey) {
            EditorSection("附加条目", "${extras.size} 项 · ${extras.take(2).joinToString("、") { it.key.ifBlank { "未命名" } }}", initiallyExpanded = extrasRevealKey > 0) {
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
                        siblings = card.entries, tags = library?.promptTags.orEmpty(),
                        owner = ExtensionFieldRef(ExtensionResourceKind.CHARACTER, card.id, ExtensionField.ENTRY_TEXT, entryId = entry.id))
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
    }
    entry<CharacterEditorRoute.BasicSettings> { route ->
        SideEffect { backs[route] = ::pop }
        CharacterBasicSettingsPage(card, library?.promptTags.orEmpty(), greetingReferences,
            focusedEntryId = sourceFocus?.takeIf { it.resource == sourceResource }?.entryId,
            onChange = { card = it }, onBack = ::pop)
    }
    })
    bindingSheet?.let { selected ->
        EditorModalSheet(if (selected == "packages") "关联提示词包" else "关联调用组",
            subtitle = "角色卡 · ${card.name}", onDismiss = { bindingSheet = null }) {
            if (selected == "packages") {
                ResourceBindingList(packages.map { it.id to it.name.ifBlank { it.namespace } }, card.boundPackageIds) {
                    card = card.copy(boundPackageIds = it)
                }
            } else {
                ResourceBindingList(library?.groups.orEmpty().map { it.id to it.name.ifBlank { "未命名调用组" } }, card.boundGroupIds) {
                    card = card.copy(boundGroupIds = it)
                }
            }
        }
    }
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
    val sourceResource = ExtensionResourceRef(ExtensionResourceKind.PACKAGE, pack.id)
    val sourceFocus = LocalSourceNavigation.current?.focus
    LaunchedEffect(sourceFocus) {
        if (sourceFocus != null && sourceFocus.resource == sourceResource && sourceFocus.entryId != null) {
            entrySearch = ""
            sourceFocus.entryId?.let { newlyExpandedEntries.add(it) }
        }
    }
    fun bind(ids: List<String>, selected: Boolean) = if (selected) (ids + pack.id).distinct() else ids - pack.id
    SourceDraftBoundary(sourceResource,
        overlay = { base -> base.withSourceResource(base.copy(packages = listOf(pack)), sourceResource).copy(
            characters = base.characters.map { it.copy(boundPackageIds = bind(it.boundPackageIds, it.id in characterIds)) },
            mainCalls = base.mainCalls.map { it.copy(boundPackageIds = bind(it.boundPackageIds, it.id in mainCallIds)) }) },
        restore = { candidate, parent -> candidate.withSourceResource(parent, sourceResource).copy(
            characters = candidate.characters.map { character -> character.copy(boundPackageIds = bind(character.boundPackageIds, parent.characters.firstOrNull { it.id == character.id }?.boundPackageIds?.contains(pack.id) == true)) },
            mainCalls = candidate.mainCalls.map { main -> main.copy(boundPackageIds = bind(main.boundPackageIds, parent.mainCalls.firstOrNull { it.id == main.id }?.boundPackageIds?.contains(pack.id) == true)) }) },
        accept = { candidate ->
            candidate.packages.firstOrNull { it.id == pack.id }?.let { pack = it }
            characterIds = candidate.characters.filter { pack.id in it.boundPackageIds }.map { it.id }.toSet()
            mainCallIds = candidate.mainCalls.filter { pack.id in it.boundPackageIds }.map { it.id }.toSet()
        }) {
    EditorPage("提示词包", onBack, { onSave(pack.copy(entries = pack.entries.mapIndexed { index, entry -> entry.copy(order = index) }), characterIds, mainCallIds) }, dirty = pack != baseline || characterIds != initialCharacters || mainCallIds != initialMainCalls, saving = saving) {
        EditorTitleRow("${pack.name} / ${pack.slug}", { renamePack = true })
        EditorSection("引用地址", "pack.${pack.namespace}.*") { LegacyAddressLine("pack.${pack.namespace}.*") }
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
                siblings = pack.entries, tags = library.promptTags,
                owner = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, pack.id, ExtensionField.ENTRY_TEXT, entryId = entry.id))
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
    WorkflowSourceBoundary(workflow, onChange) {
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
            PromptBlocksEditor(request.blocks, request.references, { changed ->
                val nodeId = request.location?.nodeId
                if (nodeId != null) onChange(workflow.copy(nodes = workflow.nodes.map { if (it.id == nodeId) it.copy(blocks = changed) else it }))
                else request.save(changed)
                promptRequest = null
            }, { promptRequest = null }, tags = tags, owner = request.location)
        }
    }
    }
}
