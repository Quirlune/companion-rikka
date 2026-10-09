package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors

private sealed interface ExtensionPage {
    data object ListPage : ExtensionPage
    data class Detail(val id: String) : ExtensionPage
    data class Definition(val baseline: ConfigurationExtension?, val initial: ConfigurationExtension) : ExtensionPage
    data class Parameter(val extensionId: String, val parameter: ExtensionParameter,
        val descriptor: ExtensionFieldDescriptor, val initialValue: String?) : ExtensionPage
}

/** Configuration only: all accepted changes are applied by the host's controller transaction. */
@Composable
internal fun ConfigurationExtensionsPage(
    library: Library,
    busy: Boolean,
    onApply: ((Library) -> ExtensionEditResult) -> Unit,
    onOpenResource: (ExtensionResourceRef) -> Unit,
    onBack: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenLocation: (ResourceLocation) -> Unit = {},
) {
    val pages = remember { mutableStateListOf<ExtensionPage>(ExtensionPage.ListPage) }
    // Approval is scoped to this visit and the actual set of declared users of each resource.
    val approvedImpacts = remember { mutableStateMapOf<ExtensionResourceRef, List<ResourceUse>>() }
    fun back() { if (pages.size > 1) pages.removeAt(pages.lastIndex) else onBack() }
    fun editDefinition(definition: ConfigurationExtension?) {
        pages.add(ExtensionPage.Definition(definition, definition ?: ConfigurationExtension(name = "新扩展")))
    }
    when (val page = pages.last()) {
        ExtensionPage.ListPage -> {
            ExtensionPageFrame("扩展", ::back, busy, actions = {
                IconButton(onClick = { editDefinition(null) }, enabled = !busy) { Icon(HugeIcons.Add01, "创建扩展") }
            }) {
                // A system destination, not a name-matched user collection.
                ExtensionDefinitionRow("记忆", "内置 · 分段摘要、记忆处理与历史视图", busy, onClick = onOpenMemory)
                library.extensions.forEach { definition -> key(definition.id) {
                    ExtensionDefinitionRow(definition.name,
                        "${definition.resources.size} 个组成资源 · ${definition.parameters.size} 个公开参数", busy,
                        onClick = { pages.add(ExtensionPage.Detail(definition.id)) })
                } }
                if (library.extensions.isEmpty()) Text("点 + 创建自己的调用、提示词与宏集合。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ExtensionPage.Detail -> {
            val definition = library.extensions.firstOrNull { it.id == page.id }
            if (definition == null) {
                LaunchedEffect(page.id) { back() }
                ExtensionPageFrame("扩展已移除", ::back, busy) {}
            }
            else ExtensionDetailPage(library, definition, busy, onApply, ::back,
                onEdit = { editDefinition(definition) }, onOpenResource = onOpenResource,
                onParameter = { parameter, descriptor -> pages.add(ExtensionPage.Parameter(definition.id, parameter,
                    descriptor, ExtensionFieldRegistry.read(library, parameter.target))) })
        }
        is ExtensionPage.Definition -> key(page.initial.id) {
            ExtensionDefinitionEditor(library, page.baseline, page.initial, busy, onApply, ::back)
        }
        is ExtensionPage.Parameter -> key(page.extensionId, page.parameter.id) {
            ExtensionParameterEditor(library, page, busy, approvedImpacts, onApply, ::back, onOpenLocation)
        }
    }
}

@Composable
private fun ExtensionPageFrame(title: String, onBack: () -> Unit, busy: Boolean,
    actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = !busy, onBack = onBack)
    Scaffold(topBar = { LargeFlexibleTopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { CompanionBackButton(onBack, enabled = !busy) }, actions = actions, colors = CustomColors.topBarColors) },
        containerColor = CustomColors.topBarColors.containerColor) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ExtensionDefinitionRow(title: String, detail: String, busy: Boolean, onClick: () -> Unit) {
    CardGroup { item(onClick = { if (!busy) onClick() }, headlineContent = { Text(title) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }) }
}

@Composable
private fun ExtensionDetailPage(library: Library, definition: ConfigurationExtension,
    busy: Boolean, onApply: ((Library) -> ExtensionEditResult) -> Unit, onBack: () -> Unit,
    onEdit: () -> Unit, onOpenResource: (ExtensionResourceRef) -> Unit,
    onParameter: (ExtensionParameter, ExtensionFieldDescriptor) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConfigurationExtension?>(null) }
    ExtensionPageFrame(definition.name, onBack, busy, actions = {
        IconButton(onClick = onEdit, enabled = !busy) { Icon(HugeIcons.PencilEdit01, "编辑扩展定义") }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(HugeIcons.MoreVertical, "扩展操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("删除集合") }, enabled = !busy,
                    onClick = { menu = false; deleting = definition })
            }
        }
    }) {
        Text("集中整理调用、提示词与宏。公开参数直接修改组成资源；运行时使用仍由原有配置决定。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (definition.parameters.isNotEmpty()) {
            Text("公开参数", style = MaterialTheme.typography.titleMedium)
            definition.parameters.forEach { parameter -> key(parameter.id) {
                val descriptor = ExtensionFieldRegistry.describe(library, parameter.target)
                val value = if (descriptor == null) null else ExtensionFieldRegistry.read(library, parameter.target)
                ExtensionDefinitionRow(parameter.title, if (descriptor == null) "目标字段已不可用，请编辑扩展映射"
                    else extensionValueSummary(descriptor, value), busy || descriptor == null,
                    onClick = { if (descriptor != null) onParameter(parameter, descriptor) })
            } }
        }
        Text("组成", style = MaterialTheme.typography.titleMedium)
        if (definition.resources.isEmpty()) Text("尚未添加组成资源。", style = MaterialTheme.typography.bodySmall)
        definition.resources.forEach { ref ->
            ExtensionDefinitionRow(extensionResourceName(library, ref), extensionKindLabel(ref.kind), busy,
                onClick = { onOpenResource(ref) })
        }
        Text("完整配置仍在各资源原有编辑器中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    deleting?.let { captured ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除「${captured.name}」集合？") },
            text = { Text("仅删除集合及其参数映射，组成资源和现有调用关联都会保留。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                onApply { latest ->
                    if (latest.extensions.firstOrNull { it.id == captured.id } != captured)
                        extensionUiFailure("extension.${captured.id}", "集合已被修改，请重新打开删除确认")
                    else ExtensionCommands.deleteDefinition(latest, captured.id)
                }
            }) { Text("删除集合") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun ExtensionDefinitionEditor(library: Library, baseline: ConfigurationExtension?, initial: ConfigurationExtension,
    busy: Boolean, onApply: ((Library) -> ExtensionEditResult) -> Unit, onBack: () -> Unit) {
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var submitted by remember { mutableStateOf<ConfigurationExtension?>(null) }
    var resourcesOpen by remember { mutableStateOf(false) }
    var mapping by remember { mutableStateOf<ExtensionParameter?>(null) }
    var addMapping by remember { mutableStateOf(false) }
    var proposedResources by remember { mutableStateOf<List<ExtensionResourceRef>?>(null) }
    val current = library.extensions.firstOrNull { it.id == initial.id }
    val stale = current != baseline
    LaunchedEffect(current, submitted, busy) {
        if (submitted != null && current == submitted && !busy) onBack()
    }
    fun save() {
        if (baseline != null && draft == baseline) { onBack(); return }
        val candidate = draft
        submitted = candidate
        onApply { latest ->
            if (latest.extensions.firstOrNull { it.id == initial.id } != baseline)
                extensionUiFailure("extension.${initial.id}", "扩展定义已被其他编辑修改，请重新打开后保存")
            else if (baseline == null) ExtensionCommands.createDefinition(latest, candidate)
            else ExtensionCommands.updateDefinition(latest, candidate)
        }
    }
    fun applyResources(next: List<ExtensionResourceRef>) {
        val orphaned = draft.parameters.filter { ExtensionResourceRef(it.target.kind, it.target.resourceId) !in next }
        if (orphaned.isNotEmpty()) proposedResources = next else draft = draft.copy(resources = next)
    }
    EditorPage(if (baseline == null) "创建扩展" else "编辑扩展", onBack, ::save, dirty = baseline == null || draft != baseline, saving = busy) {
        if (stale) Text("定义已改变。当前草稿保留；返回后重新打开可读取最新内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextFieldLine("扩展名称", draft.name, { draft = draft.copy(name = it) })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("组成资源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { resourcesOpen = true }) { Text("选择") }
        }
        draft.resources.forEach { ref -> Text("${extensionKindLabel(ref.kind)} · ${extensionResourceName(library, ref)}", style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("公开参数", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { addMapping = true }, enabled = draft.resources.isNotEmpty()) { Icon(HugeIcons.Add01, "添加公开参数") }
        }
        draft.parameters.forEach { parameter -> key(parameter.id) {
            val descriptor = ExtensionFieldRegistry.describe(library, parameter.target)
            ExtensionDefinitionRow(parameter.title, descriptor?.title ?: "目标字段已不可用", busy, onClick = { mapping = parameter })
        } }
        Text("公开参数可指向调用设置、提示词正文和调用组的宏默认值。完整设置可在组成资源的原编辑器中修改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (resourcesOpen) ExtensionResourcePicker(library, draft.resources, onSave = { resourcesOpen = false; applyResources(it) }, onDismiss = { resourcesOpen = false })
    if (addMapping || mapping != null) ExtensionMappingEditor(library, draft.resources, mapping,
        onSave = { parameter ->
            draft = draft.copy(parameters = if (draft.parameters.any { it.id == parameter.id }) draft.parameters.map { if (it.id == parameter.id) parameter else it } else draft.parameters + parameter)
            mapping = null; addMapping = false
        }, onRemove = { id -> draft = draft.copy(parameters = draft.parameters.filterNot { it.id == id }); mapping = null },
        onDismiss = { mapping = null; addMapping = false })
    proposedResources?.let { next ->
        val removed = draft.parameters.filter { ExtensionResourceRef(it.target.kind, it.target.resourceId) !in next }
        AlertDialog(onDismissRequest = { proposedResources = null }, title = { Text("同时移除 ${removed.size} 个参数映射？") },
            text = { Text(removed.joinToString("\n") { it.title }) },
            confirmButton = { TextButton(onClick = {
                draft = draft.copy(resources = next, parameters = draft.parameters.filterNot { it in removed }); proposedResources = null
            }) { Text("移除组成与映射") } }, dismissButton = { TextButton(onClick = { proposedResources = null }) { Text("取消") } })
    }
}

@Composable
private fun ExtensionResourcePicker(library: Library, initial: List<ExtensionResourceRef>,
    onSave: (List<ExtensionResourceRef>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    ExtensionSheet("组成资源", onDismiss) {
        Text("调用组提供普通调用与宏，提示词包提供可复用文本。按需选择组成资源。", style = MaterialTheme.typography.bodySmall)
        listOf(ExtensionResourceKind.GROUP, ExtensionResourceKind.PACKAGE,
            ExtensionResourceKind.CHARACTER, ExtensionResourceKind.MAIN).forEach { kind ->
            val optional = kind == ExtensionResourceKind.CHARACTER || kind == ExtensionResourceKind.MAIN
            Text(extensionKindLabel(kind) + if (optional) "（可选）" else "", style = MaterialTheme.typography.titleSmall)
            val choices = extensionResourceChoices(library, kind)
            ResourceBindingList(choices, selected.filter { it.kind == kind }.map { it.id }) { ids ->
                selected = selected.filterNot { it.kind == kind } + ids.map { ExtensionResourceRef(kind, it) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onSave(selected) }) { Text("采用选择") }
        }
    }
}

@Composable
private fun ExtensionMappingEditor(library: Library, resources: List<ExtensionResourceRef>, initial: ExtensionParameter?,
    onSave: (ExtensionParameter) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var target by remember(initial?.id) { mutableStateOf(initial?.target) }
    var resource by remember(initial?.id) { mutableStateOf(initial?.target?.let { ExtensionResourceRef(it.kind, it.resourceId) } ?: resources.firstOrNull()) }
    ExtensionSheet(if (initial == null) "公开一个参数" else "参数映射", onDismiss) {
        TextFieldLine("参数名称", title, { title = it })
        ChoiceLine("组成资源", resource, resources.map { (it as ExtensionResourceRef?) to "${extensionKindLabel(it.kind)} · ${extensionResourceName(library, it)}" }) {
            resource = it; target = null
        }
        val fields = resource?.let { ExtensionFieldRegistry.list(library, it) }.orEmpty()
        ChoiceLine("实际字段", target, fields.map { (it.target as ExtensionFieldRef?) to it.title }) { selected ->
            target = selected
            if (title.isBlank()) title = fields.firstOrNull { it.target == selected }?.title.orEmpty()
        }
        if (fields.isEmpty()) Text("此资源没有可公开的简单字段；完整设置仍可在原编辑器中使用。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (initial != null) TextButton(onClick = { onRemove(initial.id) }) { Text("移除映射") }
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(enabled = title.isNotBlank() && fields.any { it.target == target }, onClick = {
                target?.let { onSave(ExtensionParameter(id = initial?.id ?: newId(), title = title, target = it)) }
            }) { Text("采用映射") }
        }
    }
}

@Composable
private fun ExtensionParameterEditor(library: Library, page: ExtensionPage.Parameter, busy: Boolean,
    approvedImpacts: MutableMap<ExtensionResourceRef, List<ResourceUse>>, onApply: ((Library) -> ExtensionEditResult) -> Unit,
    onBack: () -> Unit, onOpenLocation: (ResourceLocation) -> Unit) {
    var draft by remember { mutableStateOf(page.initialValue) }
    var submitted by remember { mutableStateOf(false) }
    var submittedValue by remember { mutableStateOf<String?>(null) }
    var pendingImpact by remember { mutableStateOf<List<ResourceUse>?>(null) }
    var pendingLocation by remember { mutableStateOf<ResourceLocation?>(null) }
    var navigationPrompt by remember { mutableStateOf(false) }
    var sourceNotice by remember { mutableStateOf<String?>(null) }
    val target = page.parameter.target
    val ref = ExtensionResourceRef(target.kind, target.resourceId)
    val definition = library.extensions.firstOrNull { it.id == page.extensionId }
    val currentParameter = definition?.parameters?.firstOrNull { it.id == page.parameter.id }
    val descriptor = ExtensionFieldRegistry.describe(library, target)
    val currentValue = ExtensionFieldRegistry.read(library, target)
    val stale = currentParameter?.target != target || descriptor != page.descriptor || currentValue != page.initialValue
    LaunchedEffect(currentParameter, descriptor, currentValue, submitted, busy) {
        if (submitted && !busy && currentParameter?.target == target && descriptor == page.descriptor && currentValue == submittedValue) {
            onBack()
            pendingLocation?.let(onOpenLocation)
        }
    }
    val location = ResourceLocations.fromField(target)
    fun openLocation(next: ResourceLocation) {
        pendingImpact = null
        if (draft != page.initialValue) { pendingLocation = next; navigationPrompt = true }
        else { onBack(); onOpenLocation(next) }
    }
    fun apply(impact: List<ResourceUse>) {
        val candidate = draft
        submittedValue = candidate; submitted = true
        onApply { latest ->
            val parameter = latest.extensions.firstOrNull { it.id == page.extensionId }?.parameters?.firstOrNull { it.id == page.parameter.id }
            when {
                parameter?.target != target || ExtensionFieldRegistry.describe(latest, target) != page.descriptor ->
                    extensionUiFailure("extension.${page.extensionId}", "参数映射或字段类型已改变，请重新打开后保存")
                ResourceUsageIndex.usages(latest, location) != impact ->
                    extensionUiFailure("extension.${page.extensionId}", "共享范围已改变，请重新查看影响后保存")
                else -> ExtensionCommands.setParameter(latest, page.extensionId, page.parameter.id, candidate, page.initialValue)
            }
        }
    }
    fun save() {
        if (draft == page.initialValue) { onBack(); return }
        val impact = ResourceUsageIndex.usages(library, location)
        if (approvedImpacts[ref] != impact && impact.isNotEmpty()) pendingImpact = impact
        else apply(impact)
    }
    val main = library.mainCalls.firstOrNull { target.kind == ExtensionResourceKind.MAIN && it.id == target.resourceId }
    val group = library.groups.firstOrNull { target.kind == ExtensionResourceKind.GROUP && it.id == target.resourceId }
    val references = remember(library, main, group) { promptReferenceChoices(group?.workflows ?: main?.workflows.orEmpty(), library, main, group) }
    // This editor owns one mapped scalar, so source operations must pass its mapping/value guard.
    val parameterNavigation = SourceNavigation(library,
        merge = { sourceNotice = "请保存当前参数，再打开实际字段进行来源修改。" },
        leave = { candidate, destination, done ->
            if (candidate == library) openLocation(destination)
            else sourceNotice = "请先保存当前参数，再在实际字段中独立使用。"
            done(false)
        },
        publish = { _, done -> sourceNotice = "请保存当前参数，再打开实际字段进行来源修改。"; done(false) },
        edit = ::openLocation)
    EditorPage(page.parameter.title, onBack, ::save, dirty = draft != page.initialValue, saving = busy) {
        TextButton(enabled = !busy, onClick = { openLocation(location) }, contentPadding = PaddingValues(0.dp)) {
            Text("${extensionResourceName(library, ref)} · ${page.descriptor.title}")
        }
        if (stale) Text("实际字段或映射已改变。当前输入保留；请返回并重新打开读取最新值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        sourceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        CompositionLocalProvider(LocalSourceNavigation provides parameterNavigation) {
            ConfigurationFieldEditor(page.descriptor, draft, references) { draft = it; submitted = false; pendingLocation = null }
        }
    }
    pendingImpact?.let { impact ->
        AlertDialog(onDismissRequest = { pendingImpact = null; pendingLocation = null }, title = { Text("修改共享资源字段") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这次修改会写入「${extensionResourceName(library, ref)}」。已声明的使用位置：")
                ResourceUsageRows(impact, ::openLocation)
                Text("已保存的对话与恢复快照保留原内容；后续读取取决于各自配置。动态宏地址可能有额外使用方。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { approvedImpacts[ref] = impact; pendingImpact = null; apply(impact) }) { Text("保存共享修改") } },
            dismissButton = { TextButton(onClick = { pendingImpact = null; pendingLocation = null }) { Text("继续编辑") } })
    }
    if (navigationPrompt) AlertDialog(onDismissRequest = { navigationPrompt = false; pendingLocation = null },
        title = { Text("先处理当前参数修改") }, text = { Text("保存成功后再打开来源，或放弃当前输入。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { navigationPrompt = false; save() }) { Text("保存并打开") } },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { val next = pendingLocation; pendingLocation = null; navigationPrompt = false; onBack(); next?.let(onOpenLocation) }) { Text("放弃并打开") }
            TextButton(onClick = { navigationPrompt = false; pendingLocation = null }) { Text("继续编辑") }
        })
}

@Composable
private fun ExtensionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun extensionUiFailure(path: String, message: String) = ExtensionEditResult(null, listOf(Issue(path, message)))

private fun extensionKindLabel(kind: ExtensionResourceKind): String = when (kind) {
    ExtensionResourceKind.CHARACTER -> "角色卡"; ExtensionResourceKind.MAIN -> "主调用"
    ExtensionResourceKind.PACKAGE -> "提示词包"; ExtensionResourceKind.GROUP -> "调用组"
}

private fun extensionResourceChoices(library: Library, kind: ExtensionResourceKind): List<Pair<String, String>> = when (kind) {
    ExtensionResourceKind.CHARACTER -> library.characters.map { it.id to it.name }
    ExtensionResourceKind.MAIN -> library.mainCalls.map { it.id to it.name }
    ExtensionResourceKind.PACKAGE -> library.packages.map { it.id to it.name }
    ExtensionResourceKind.GROUP -> library.groups.map { it.id to it.name }
}

private fun extensionResourceName(library: Library, ref: ExtensionResourceRef): String =
    extensionResourceChoices(library, ref.kind).firstOrNull { it.first == ref.id }?.second ?: "资源已不可用 · ${ref.id}"

private fun extensionValueSummary(field: ExtensionFieldDescriptor, value: String?): String = when {
    value == null -> "使用默认值"
    field.choices.any { it.value == value } -> field.choices.first { it.value == value }.title
    field.type == ActionFieldType.BOOLEAN -> if (value == "true") "开启" else "关闭"
    value.isBlank() -> "空文本"
    else -> value
}
