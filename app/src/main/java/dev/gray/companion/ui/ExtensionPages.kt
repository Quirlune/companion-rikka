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
    mainCallId: String,
    busy: Boolean,
    onApply: ((Library) -> ExtensionEditResult) -> Unit,
    onOpenResource: (ExtensionResourceRef) -> Unit,
    onBack: () -> Unit,
) {
    val pages = remember(mainCallId) { mutableStateListOf<ExtensionPage>(ExtensionPage.ListPage) }
    // Approval is scoped to this visit and the actual set of declared users of each resource.
    val approvedImpacts = remember(mainCallId) { mutableStateMapOf<ExtensionResourceRef, ExtensionImpact>() }
    fun back() { if (pages.size > 1) pages.removeAt(pages.lastIndex) else onBack() }
    fun editDefinition(definition: ConfigurationExtension?) {
        pages.add(ExtensionPage.Definition(definition, definition ?: ConfigurationExtension(name = "新扩展")))
    }
    when (val page = pages.last()) {
        ExtensionPage.ListPage -> {
            val main = library.mainCalls.firstOrNull { it.id == mainCallId }
            ExtensionPageFrame("扩展", ::back, busy, actions = {
                IconButton(onClick = { editDefinition(null) }, enabled = !busy) { Icon(HugeIcons.Add01, "创建扩展") }
            }) {
                if (main == null) Text("主调用已不可用。", color = MaterialTheme.colorScheme.error)
                else {
                    Text(main.name, style = MaterialTheme.typography.titleMedium)
                    Text("启用后关联组成中的提示词包与调用组。参数会修改组成资源的实际字段。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (main.extensionBindings.isNotEmpty()) {
                        Text("已安装", style = MaterialTheme.typography.titleSmall)
                        main.extensionBindings.forEach { binding -> key(binding.id) {
                            val definition = library.extensions.firstOrNull { it.id == binding.extensionId }
                            ExtensionDefinitionRow(definition?.name ?: "扩展已不可用", if (binding.enabled) "已启用" else "未启用",
                                busy, onClick = { pages.add(ExtensionPage.Detail(binding.extensionId)) }, trailing = {
                                    Switch(binding.enabled, onCheckedChange = { enabled ->
                                        onApply { ExtensionCommands.setEnabled(it, mainCallId, binding.id, enabled) }
                                    }, enabled = !busy && definition != null)
                                })
                        } }
                    }
                    val available = library.extensions.filter { definition -> main.extensionBindings.none { it.extensionId == definition.id } }
                    if (available.isNotEmpty()) {
                        Text("可安装", style = MaterialTheme.typography.titleSmall)
                        available.forEach { definition -> ExtensionDefinitionRow(definition.name,
                            "${definition.resources.size} 个组成资源 · ${definition.parameters.size} 个公开参数", busy,
                            onClick = { pages.add(ExtensionPage.Detail(definition.id)) }) }
                    }
                    if (library.extensions.isEmpty()) Text("还没有扩展。点 + 从已有资源创建。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is ExtensionPage.Detail -> {
            val definition = library.extensions.firstOrNull { it.id == page.id }
            if (definition == null) ExtensionPageFrame("扩展不可用", ::back, busy) { Text("此扩展定义已不存在。") }
            else ExtensionDetailPage(library, mainCallId, definition, busy, onApply, ::back,
                onEdit = { editDefinition(definition) }, onOpenResource = onOpenResource,
                onParameter = { parameter, descriptor -> pages.add(ExtensionPage.Parameter(definition.id, parameter,
                    descriptor, ExtensionFieldRegistry.read(library, parameter.target))) })
        }
        is ExtensionPage.Definition -> key(page.initial.id) {
            ExtensionDefinitionEditor(library, page.baseline, page.initial, busy, onApply, ::back)
        }
        is ExtensionPage.Parameter -> key(page.extensionId, page.parameter.id) {
            ExtensionParameterEditor(library, mainCallId, page, busy, approvedImpacts, onApply, ::back)
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
private fun ExtensionDefinitionRow(title: String, detail: String, busy: Boolean, onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null) {
    CardGroup { item(onClick = { if (!busy) onClick() }, headlineContent = { Text(title) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }, trailingContent = trailing) }
}

@Composable
private fun ExtensionDetailPage(library: Library, mainId: String, definition: ConfigurationExtension,
    busy: Boolean, onApply: ((Library) -> ExtensionEditResult) -> Unit, onBack: () -> Unit,
    onEdit: () -> Unit, onOpenResource: (ExtensionResourceRef) -> Unit,
    onParameter: (ExtensionParameter, ExtensionFieldDescriptor) -> Unit) {
    val binding = library.mainCalls.firstOrNull { it.id == mainId }?.extensionBindings?.firstOrNull { it.extensionId == definition.id }
    var menu by remember { mutableStateOf(false) }
    val installed = library.mainCalls.filter { main -> main.extensionBindings.any { it.extensionId == definition.id } }
    ExtensionPageFrame(definition.name, onBack, busy, actions = {
        IconButton(onClick = onEdit, enabled = !busy) { Icon(HugeIcons.PencilEdit01, "编辑扩展定义") }
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(HugeIcons.MoreVertical, "扩展操作") }
            DropdownMenu(menu, { menu = false }) {
                if (binding != null) DropdownMenuItem(text = { Text("解除当前安装") }, onClick = {
                    menu = false; onApply { ExtensionCommands.detach(it, mainId, binding.id) }
                })
                DropdownMenuItem(text = { Text(if (installed.isEmpty()) "删除扩展定义" else "已安装的定义不能删除") },
                    enabled = installed.isEmpty(), onClick = { menu = false; onApply { ExtensionCommands.deleteDefinition(it, definition.id) } })
            }
        }
    }) {
        if (binding == null) Button(enabled = !busy, onClick = { onApply { ExtensionCommands.install(it, mainId, definition.id) } }) { Text("安装 · 默认停用") }
        else ListItem(headlineContent = { Text("启用扩展关联") }, trailingContent = {
            Switch(binding.enabled, onCheckedChange = { enabled -> onApply { ExtensionCommands.setEnabled(it, mainId, binding.id, enabled) } }, enabled = !busy)
        }, colors = CustomColors.listItemColors)
        Text("启停控制当前主调用对提示词包、调用组的关联；角色与主调用组成项可直接打开。解除安装会保留资源与已改参数。",
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
        val installed = library.mainCalls.filter { main -> main.extensionBindings.any { it.extensionId == initial.id } }
        if (installed.isNotEmpty()) Text("组成修改会同步到：${installed.joinToString("、") { it.name }}", style = MaterialTheme.typography.bodySmall)
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
        Text("公开参数直接指向组成资源的字段。其他设置保留在原编辑器中。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ExtensionResourceKind.entries.forEach { kind ->
            Text(extensionKindLabel(kind), style = MaterialTheme.typography.titleSmall)
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
private fun ExtensionParameterEditor(library: Library, mainId: String, page: ExtensionPage.Parameter, busy: Boolean,
    approvedImpacts: MutableMap<ExtensionResourceRef, ExtensionImpact>, onApply: ((Library) -> ExtensionEditResult) -> Unit, onBack: () -> Unit) {
    var draft by remember { mutableStateOf(page.initialValue) }
    var submitted by remember { mutableStateOf(false) }
    var submittedValue by remember { mutableStateOf<String?>(null) }
    var pendingImpact by remember { mutableStateOf<ExtensionImpact?>(null) }
    val target = page.parameter.target
    val ref = ExtensionResourceRef(target.kind, target.resourceId)
    val definition = library.extensions.firstOrNull { it.id == page.extensionId }
    val currentParameter = definition?.parameters?.firstOrNull { it.id == page.parameter.id }
    val descriptor = ExtensionFieldRegistry.describe(library, target)
    val currentValue = ExtensionFieldRegistry.read(library, target)
    val stale = currentParameter?.target != target || descriptor != page.descriptor || currentValue != page.initialValue
    LaunchedEffect(currentParameter, descriptor, currentValue, submitted, busy) {
        if (submitted && !busy && currentParameter?.target == target && descriptor == page.descriptor && currentValue == submittedValue) onBack()
    }
    fun apply(impact: ExtensionImpact) {
        val candidate = draft
        submittedValue = candidate; submitted = true
        onApply { latest ->
            val parameter = latest.extensions.firstOrNull { it.id == page.extensionId }?.parameters?.firstOrNull { it.id == page.parameter.id }
            when {
                parameter?.target != target || ExtensionFieldRegistry.describe(latest, target) != page.descriptor ->
                    extensionUiFailure("extension.${page.extensionId}", "参数映射或字段类型已改变，请重新打开后保存")
                ExtensionCommands.impact(latest, target) != impact ->
                    extensionUiFailure("extension.${page.extensionId}", "共享范围已改变，请重新查看影响后保存")
                else -> ExtensionCommands.setParameter(latest, page.extensionId, page.parameter.id, candidate, page.initialValue)
            }
        }
    }
    fun save() {
        if (draft == page.initialValue) { onBack(); return }
        val impact = ExtensionCommands.impact(library, target)
        if (approvedImpacts[ref] != impact && (impact.mainCallIds.isNotEmpty() || impact.extensionIds.isNotEmpty())) pendingImpact = impact
        else apply(impact)
    }
    val main = library.mainCalls.firstOrNull { it.id == if (target.kind == ExtensionResourceKind.MAIN) target.resourceId else mainId }
    val group = library.groups.firstOrNull { target.kind == ExtensionResourceKind.GROUP && it.id == target.resourceId }
    val references = remember(library, main, group) { promptReferenceChoices(group?.workflows ?: main?.workflows.orEmpty(), library, main, group) }
    EditorPage(page.parameter.title, onBack, ::save, dirty = draft != page.initialValue, saving = busy) {
        Text(extensionResourceName(library, ref), style = MaterialTheme.typography.titleSmall)
        Text(page.descriptor.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (stale) Text("实际字段或映射已改变。当前输入保留；请返回并重新打开读取最新值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        ExtensionParameterField(page.descriptor, draft, references) { draft = it; submitted = false }
    }
    pendingImpact?.let { impact ->
        AlertDialog(onDismissRequest = { pendingImpact = null }, title = { Text("修改共享资源字段") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("这次修改会写入「${extensionResourceName(library, ref)}」。以下配置使用这一资源：")
                if (impact.mainCallIds.isNotEmpty()) Text("主调用：" + impact.mainCallIds.joinToString("、") { id -> library.mainCalls.firstOrNull { it.id == id }?.name ?: id })
                if (impact.extensionIds.isNotEmpty()) Text("扩展：" + impact.extensionIds.joinToString("、") { id -> library.extensions.firstOrNull { it.id == id }?.name ?: id })
            }
        }, confirmButton = { TextButton(onClick = { approvedImpacts[ref] = impact; pendingImpact = null; apply(impact) }) { Text("保存共享修改") } },
            dismissButton = { TextButton(onClick = { pendingImpact = null }) { Text("继续编辑") } })
    }
}

@Composable
private fun ExtensionParameterField(field: ExtensionFieldDescriptor, value: String?, references: List<PromptReferenceChoice>, onChange: (String?) -> Unit) {
    var lastDefined by remember { mutableStateOf(value ?: field.choices.firstOrNull()?.value ?: when (field.type) {
        ActionFieldType.BOOLEAN -> "false"
        ActionFieldType.NUMBER -> field.min?.let { if (field.integer) it.toInt().toString() else it.toString() } ?: "0"
        else -> ""
    }) }
    fun update(next: String) { lastDefined = next; onChange(next) }
    if (field.nullable) ToggleLine("使用默认值", value == null) { useDefault -> onChange(if (useDefault) null else lastDefined) }
    if (value == null && field.nullable) return
    val selected = value.orEmpty()
    if (field.choices.isNotEmpty() || field.target.field in setOf(ExtensionField.WORKFLOW_MODEL, ExtensionField.NODE_MODEL)) {
        ChoiceLine("值", selected, field.choices.map { it.value to it.title }) { update(it) }
    } else when (field.type) {
        ActionFieldType.BOOLEAN -> ToggleLine("值", selected == "true") { update(it.toString()) }
        ActionFieldType.TEMPLATE -> PromptTemplateField("内容", selected, references, { update(it) })
        ActionFieldType.NUMBER -> {
            TextFieldLine(if (field.integer) "整数" else "数值", selected, { update(it) })
            if (field.min != null || field.max != null) Text("范围：${field.min ?: "不限"} ～ ${field.max ?: "不限"}", style = MaterialTheme.typography.bodySmall)
        }
        ActionFieldType.MACRO -> TextFieldLine("宏地址", selected, { update(it) })
        ActionFieldType.STRING -> TextFieldLine("值", selected, { update(it) }, minLines = if (field.target.field == ExtensionField.CHARACTER_DESCRIPTION) 3 else 1)
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

private fun extensionValueSummary(field: ExtensionFieldDescriptor, value: String?): String = when {
    value == null -> "使用默认值"
    field.choices.any { it.value == value } -> field.choices.first { it.value == value }.title
    field.type == ActionFieldType.BOOLEAN -> if (value == "true") "开启" else "关闭"
    value.isBlank() -> "空文本"
    else -> value
}
