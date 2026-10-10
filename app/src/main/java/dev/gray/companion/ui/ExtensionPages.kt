package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    onSetMemoryEnabled: (Boolean) -> Unit = {},
    onOpenLocation: (ResourceLocation) -> Unit = {},
    onOpenAutomaticOptions: () -> Unit = {},
    onSetAutomaticOptionsEnabled: (Boolean) -> Unit = {},
    onSetExtensionEnabled: (ConfigurationExtension, Boolean) -> Unit = { _, _ -> },
) {
    var selectedExtensionId by rememberSaveable { mutableStateOf<String?>(null) }
    val pages = remember {
        mutableStateListOf<ExtensionPage>(ExtensionPage.ListPage).apply {
            selectedExtensionId?.let { add(ExtensionPage.Detail(it)) }
        }
    }
    fun openDetail(definition: ConfigurationExtension) {
        selectedExtensionId = definition.id
        pages.add(ExtensionPage.Detail(definition.id))
    }
    fun back() {
        if (pages.size > 1) {
            pages.removeAt(pages.lastIndex)
            selectedExtensionId = pages.filterIsInstance<ExtensionPage.Detail>().lastOrNull()?.id
        } else onBack()
    }
    fun editDefinition(definition: ConfigurationExtension?) {
        pages.add(ExtensionPage.Definition(definition, definition ?: ConfigurationExtension(name = "新扩展", enabled = false)))
    }
    when (val page = pages.last()) {
        ExtensionPage.ListPage -> {
            ExtensionPageFrame("扩展", ::back, busy, actions = {
                IconButton(onClick = { editDefinition(null) }, enabled = !busy) { Icon(HugeIcons.Add01, "创建扩展") }
            }) {
                Text("原生扩展", style = MaterialTheme.typography.titleMedium)
                val nativeExtensions = library.extensions.filter { it.memory == null && it.code == null }
                ExtensionCollectionRows(library, nativeExtensions, busy, ::openDetail, onSetExtensionEnabled)
                if (nativeExtensions.isEmpty()) Text("暂无原生扩展，可创建或导入资产包。", style = MaterialTheme.typography.bodySmall)
                Text("额外扩展", style = MaterialTheme.typography.titleMedium)
                ExtensionDefinitionRow("自动选项", "设置模型、数量与回复方向", busy, onClick = onOpenAutomaticOptions,
                    checked = library.automaticOptions.enabled, onCheckedChange = onSetAutomaticOptionsEnabled)
                ExtensionDefinitionRow("记忆", "全局设置 · 各聊天分别保存摘要", busy, onClick = onOpenMemory,
                    checked = library.memoryConfig?.enabled == true, onCheckedChange = onSetMemoryEnabled,
                    switchEnabled = library.memoryConfig?.extensionId != null)
                ExtensionCollectionRows(library, library.extensions.filter { it.memory == null && it.code != null }, busy, ::openDetail, onSetExtensionEnabled)
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
                onOpenLocation = onOpenLocation)
        }
        is ExtensionPage.Definition -> key(page.initial.id) {
            ExtensionDefinitionEditor(library, page.baseline, page.initial, busy, onApply, ::back)
        }
    }
}

/** Imported collections use native assets; built-in features retain their dedicated settings routes. */
@Composable
private fun ExtensionCollectionRows(library: Library, definitions: List<ConfigurationExtension>, busy: Boolean,
    onOpen: (ConfigurationExtension) -> Unit, onSetEnabled: (ConfigurationExtension, Boolean) -> Unit) {
    val selected = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    definitions.forEach { definition -> key(definition.id) {
        val participating = definition.enabled ?: (selected?.enabledExtensionIds?.contains(definition.id) == true)
        ExtensionDefinitionRow(definition.name,
            "${definition.resources.size} 个关联资产" + (definition.mainScopeId?.let { id ->
                " · ${library.mainCalls.firstOrNull { it.id == id }?.name ?: "原主调用"}"
            } ?: " · 全局"), busy,
            onClick = { onOpen(definition) }, checked = participating, onCheckedChange = { onSetEnabled(definition, it) })
    } }
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
private fun ExtensionDefinitionRow(title: String, detail: String, busy: Boolean, onClick: () -> Unit,
    checked: Boolean? = null, onCheckedChange: (Boolean) -> Unit = {}, switchEnabled: Boolean = true) {
    CardGroup { item(onClick = { if (!busy) onClick() }, headlineContent = { Text(title) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { if (checked != null) Switch(checked = checked, onCheckedChange = onCheckedChange,
            enabled = !busy && switchEnabled) }) }
}

@Composable
private fun ExtensionDetailPage(library: Library, definition: ConfigurationExtension,
    busy: Boolean, onApply: ((Library) -> ExtensionEditResult) -> Unit, onBack: () -> Unit,
    onEdit: () -> Unit, onOpenResource: (ExtensionResourceRef) -> Unit,
    onOpenLocation: (ResourceLocation) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConfigurationExtension?>(null) }
    var pendingCardGroupId by remember(definition.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingCardGroupId, library.groups) {
        val id = pendingCardGroupId
        if (id != null && library.groups.any { it.id == id }) {
            pendingCardGroupId = null
            onOpenLocation(ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, id), section = "cards"))
        }
    }
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
        val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
        if (session?.executableWorld != null) Text("此会话保留当时的资源；新加入的扩展请新建聊天使用。关闭开关会立即隐藏其卡片。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        definition.mainScopeId?.let { id -> Text("卡片适用于：${library.mainCalls.firstOrNull { it.id == id }?.name ?: "原主调用"}",
            style = MaterialTheme.typography.bodySmall) }
        definition.code?.let { code ->
            Text("代码 · ${code.runtime} v${code.version}", style = MaterialTheme.typography.titleMedium)
            Text("由普通调用的 plugin.run 行为执行；入口 execute(context) 返回文本。导入和开启不会直接执行代码。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("插件 ID：${definition.id}", style = MaterialTheme.typography.bodySmall)
            Text(code.source, style = MaterialTheme.typography.bodySmall, maxLines = 12, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onEdit, enabled = !busy) { Text("编辑代码") }
            OutlinedButton(enabled = !busy, onClick = {
                val node = WorkflowNode(title = "运行插件", actionId = "plugin.run", includeHistory = false,
                    arguments = mapOf("pluginId" to definition.id), outputKey = "plugin_${newId().replace("-", "")}", recordTrajectory = false)
                val name = generateSequence(1) { it + 1 }.map { if (it == 1) "${definition.name} · 代码" else "${definition.name} · 代码 $it" }
                    .first { candidate -> library.groups.none { it.name == candidate } }
                val group = CallGroup(name = name, workflows = listOf(Workflow(title = "代码功能", nodes = listOf(node))))
                pendingCardGroupId = group.id
                onApply { latest -> ExtensionCommands.addDisplayGroup(latest, definition, group) }
            }) { Text("添加代码调用") }
        }
        Text("资产与完整编辑", style = MaterialTheme.typography.titleMedium)
        Text("以下入口直接打开资产的原生编辑器。调用中可编辑完整提示词顺序、历史引用、模型参数和输出处理；提示词包中可编辑被引用的文本。修改写回同一份共享资源。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (definition.resources.isEmpty()) Text("尚未添加资产，点击右上角编辑扩展定义。", style = MaterialTheme.typography.bodySmall)
        definition.resources.forEach { ref -> key(ref.kind, ref.id) {
            val exists = extensionResourceChoices(library, ref.kind).any { it.first == ref.id }
            ExtensionDefinitionRow(extensionResourceName(library, ref),
                "${extensionKindLabel(ref.kind)} · 打开完整编辑器", busy || !exists,
                onClick = { onOpenResource(ref) })
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val entries = when (ref.kind) {
                    ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == ref.id }?.entries.orEmpty()
                    ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == ref.id }?.entries.orEmpty()
                    else -> emptyList()
                }
                entries.forEach { entry ->
                    ExtensionDefinitionRow(entry.title.ifBlank { entry.key }, "提示词条目 · 打开原生编辑器", busy,
                        onClick = { onOpenLocation(ResourceLocation(ref, entryId = entry.id)) })
                }
                val workflows = when (ref.kind) {
                    ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == ref.id }?.workflows.orEmpty()
                    ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == ref.id }?.configuredWorkflows().orEmpty()
                    else -> emptyList()
                }
                workflows.forEach { workflow ->
                    ExtensionDefinitionRow(workflow.title, "调用 · 触发、模型与完整行为顺序", busy,
                        onClick = { onOpenLocation(ResourceLocation(ref, workflowId = workflow.id)) })
                    workflow.nodes.forEachIndexed { index, node ->
                        val detail = when (node.actionId) {
                            "model.generate" -> "模型生成 · 完整提示词、模型参数与输出处理"
                            "card.define" -> "卡片定义 · 数据来源、字段与提取规则"
                            "macro.process" -> "宏处理 · 输入来源与状态写入"
                            else -> "${node.actionId} · 完整行为设置"
                        }
                        ExtensionDefinitionRow("${index + 1}. ${node.title}", detail, busy,
                            onClick = { onOpenLocation(ResourceLocation(ref, workflowId = workflow.id, nodeId = node.id)) })
                    }
                }
                if (ref.kind == ExtensionResourceKind.GROUP)
                    ExtensionDefinitionRow("卡片", "独立卡片 · 数据字段、重复引用、CSS 与布局", busy,
                        onClick = { onOpenLocation(ResourceLocation(ref, section = "cards")) })
            }
        } }
        if (definition.parameters.isNotEmpty()) {
            Text("字段快捷入口", style = MaterialTheme.typography.titleMedium)
            Text("旧参数映射直接定位原生编辑器，保留字段所在的完整上下文。", style = MaterialTheme.typography.bodySmall)
            definition.parameters.forEach { parameter -> key(parameter.id) {
                val descriptor = ExtensionFieldRegistry.describe(library, parameter.target)
                ExtensionDefinitionRow(parameter.title, descriptor?.title ?: "目标字段已不可用，请编辑扩展映射",
                    busy || descriptor == null, onClick = { onOpenLocation(ResourceLocations.fromField(parameter.target)) })
            } }
        }
        OutlinedButton(enabled = !busy, onClick = {
            val stem = "${definition.name} · 卡片"
            val name = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem $it" }
                .first { candidate -> library.groups.none { it.name == candidate } }
            val group = CallGroup(name = name, workflows = emptyList(), cards = listOf(CardResource(
                definition = defaultCardDefinition().copy(defaultRegion = CardRegion.CHAT))))
            pendingCardGroupId = group.id
            onApply { latest -> ExtensionCommands.addDisplayGroup(latest, definition, group) }
        }) { Text("新增卡片资源") }

    }
    deleting?.let { captured ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除「${captured.name}」集合？") },
            text = { Text("删除定义与公开参数，并关闭各会话对此插件的参与；组成资源、主调用与手工关联、历史正文保留。") },
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
            Text("JavaScript 代码入口", modifier = Modifier.weight(1f))
            Switch(checked = draft.code != null, enabled = !busy,
                onCheckedChange = { enabled -> draft = draft.copy(code = if (enabled) PluginCode(source = "function execute(context) {\n  return context.input;\n}") else null) })
        }
        draft.code?.let { code ->
            TextFieldLine("execute(context)", code.source, { draft = draft.copy(code = code.copy(source = it)) }, minLines = 8)
            Text("JavaScript v1 · UTF-8 代码最多 64 KiB。可读取 input/state/results/messages，返回字符串或 {text:字符串}；没有文件、网络或主机 API，不支持时间和随机数。其他作用通过普通调用行为组合。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("组成资源", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { resourcesOpen = true }) { Text("选择") }
        }
        draft.resources.forEach { ref -> Text("${extensionKindLabel(ref.kind)} · ${extensionResourceName(library, ref)}", style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("字段快捷入口", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { addMapping = true }, enabled = draft.resources.isNotEmpty()) { Icon(HugeIcons.Add01, "添加字段快捷入口") }
        }
        draft.parameters.forEach { parameter -> key(parameter.id) {
            val descriptor = ExtensionFieldRegistry.describe(library, parameter.target)
            ExtensionDefinitionRow(parameter.title, descriptor?.title ?: "目标字段已不可用", busy, onClick = { mapping = parameter })
        } }
        Text("字段快捷入口定位到原生编辑器中的实际字段，不另设简化参数编辑器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ExtensionSheet(if (initial == null) "添加字段快捷入口" else "字段快捷入口映射", onDismiss) {
        TextFieldLine("入口名称", title, { title = it })
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
