package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

/** The library here includes every enclosing draft, never just the last persisted resource. */
internal class SourceNavigation(
    val library: Library,
    val resource: ExtensionResourceRef? = null,
    val workflowId: String? = null,
    val focus: ResourceLocation? = null,
    val merge: (Library) -> Unit,
    val leave: (Library, ResourceLocation, (Boolean) -> Unit) -> Unit,
    val publish: (Library, (Boolean) -> Unit) -> Unit,
    val edit: (ResourceLocation) -> Unit = {},
    val canMerge: (ResourceLocation) -> Boolean = { it.resource == resource },
    val leaveAtomic: (Library, ResourceLocation, (Boolean) -> Unit) -> Unit = leave,
) {
    fun open(location: ResourceLocation) = leave(library, location) {}
    fun apply(candidate: Library, target: ResourceLocation, done: (Boolean) -> Unit) {
        if (canMerge(target)) { merge(candidate); done(true) }
        else if (resource == null) publish(candidate, done)
        else leave(candidate, target, done)
    }
}

internal val LocalSourceIssues = staticCompositionLocalOf<List<Issue>> { emptyList() }
internal val LocalSourceFieldFocus = staticCompositionLocalOf<ResourceLocation?> { null }

internal val LocalSourceNavigation = staticCompositionLocalOf<SourceNavigation?> { null }

@Composable
internal fun SourceNavigationProvider(
    library: Library,
    onOpenLocation: (ResourceLocation) -> Unit,
    onApplyExternal: (Library, Library, (Boolean) -> Unit) -> Unit,
    focus: ResourceLocation? = null,
    issues: List<Issue> = emptyList(),
    content: @Composable () -> Unit,
) {
    val navigation = SourceNavigation(library, focus = focus, merge = {},
        leave = { candidate, destination, done ->
            if (candidate == library) { onOpenLocation(destination); done(true) }
            else onApplyExternal(library, candidate) { ok -> if (ok) onOpenLocation(destination); done(ok) }
        }, publish = { candidate, done -> onApplyExternal(library, candidate, done) })
    CompositionLocalProvider(LocalSourceIssues provides issues) { SourceNavigationSurface(navigation, content = content) }
}

/** A real draft owner supplies its exact overlay and inverse; no field/text matching is involved. */
@Composable
internal fun SourceDraftBoundary(
    resource: ExtensionResourceRef,
    overlay: (Library) -> Library,
    restore: (Library, Library) -> Library,
    accept: (Library) -> Unit,
    workflowId: String? = null,
    ownedLocation: ((ResourceLocation) -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val parent = LocalSourceNavigation.current
    if (parent == null) { content(); return }
    val effective = overlay(parent.library)
    data class Departure(val candidate: Library, val target: ResourceLocation, val done: (Boolean) -> Unit, val atomic: Boolean)
    var pending by remember { mutableStateOf<Departure?>(null) }
    fun depart(candidate: Library, target: ResourceLocation, done: (Boolean) -> Unit, atomic: Boolean) {
        if (restore(candidate, parent.library) != candidate) pending = Departure(candidate, target, done, atomic)
        else if (atomic) parent.leaveAtomic(candidate, target, done) else parent.leave(candidate, target, done)
    }
    val navigation = SourceNavigation(effective, resource, workflowId ?: parent.workflowId, parent.focus,
        merge = { candidate ->
            accept(candidate)
            val remainder = restore(candidate, parent.library)
            if (remainder != parent.library) parent.merge(remainder)
        },
        leave = { candidate, target, done -> depart(candidate, target, done, false) }, publish = parent.publish,
        canMerge = if (parent.resource == resource) parent.canMerge else ownedLocation ?: { it.resource == resource },
        leaveAtomic = { candidate, target, done -> depart(candidate, target, done, true) })
    SourceNavigationSurface(navigation, autoFocus = parent.resource != resource, content = content)
    pending?.let { departure ->
        AlertDialog(onDismissRequest = { pending = null; departure.done(false) },
            title = { Text("离开当前草稿？") },
            text = { Text(if (departure.atomic) "独立词条与这一处引用必须一起保存。继续会保留当前草稿；取消则不创建独立词条。" else "保存当前层的修改后继续；下一层仍有草稿时会继续确认。所有保存通过完整校验后才会离开。") },
            confirmButton = { TextButton(onClick = {
                pending = null
                if (departure.atomic) parent.leaveAtomic(departure.candidate, departure.target, departure.done)
                else parent.leave(departure.candidate, departure.target, departure.done)
            }) { Text("保存并继续") } },
            dismissButton = { Row {
                TextButton(onClick = { pending = null; departure.done(false) }) { Text("取消") }
                if (!departure.atomic) TextButton(onClick = { pending = null; parent.leave(restore(departure.candidate, parent.library), departure.target, departure.done) }) { Text("放弃本层修改") }
            } })
    }
}

@Composable
private fun SourceNavigationSurface(navigation: SourceNavigation, autoFocus: Boolean = false, content: @Composable () -> Unit) {
    var selected by remember { mutableStateOf<ResourceLocation?>(null) }
    LaunchedEffect(navigation.focus) {
        val focus = navigation.focus
        if (autoFocus && focus != null && focus.resource == navigation.resource && focus.field != null) selected = focus
    }
    val local = SourceNavigation(navigation.library, navigation.resource, navigation.workflowId, navigation.focus,
        navigation.merge, navigation.leave, navigation.publish, edit = { location ->
            if (location.field == null && location.section != null) navigation.open(location) else selected = location
        }, canMerge = navigation.canMerge, leaveAtomic = navigation.leaveAtomic)
    CompositionLocalProvider(LocalSourceNavigation provides local) {
        content()
        selected?.let { location -> ConfigurationSourceSheet(location, onDismiss = { selected = null }) }
    }
}

internal fun Library.withSourceResource(source: Library, ref: ExtensionResourceRef): Library = when (ref.kind) {
    ExtensionResourceKind.CHARACTER -> copy(characters = characters.mapNotNull { old -> if (old.id == ref.id) source.characters.firstOrNull { it.id == ref.id } else old } + source.characters.filter { replacement -> replacement.id == ref.id && characters.none { it.id == ref.id } })
    ExtensionResourceKind.MAIN -> copy(mainCalls = mainCalls.mapNotNull { old -> if (old.id == ref.id) source.mainCalls.firstOrNull { it.id == ref.id } else old } + source.mainCalls.filter { replacement -> replacement.id == ref.id && mainCalls.none { it.id == ref.id } })
    ExtensionResourceKind.PACKAGE -> copy(packages = packages.mapNotNull { old -> if (old.id == ref.id) source.packages.firstOrNull { it.id == ref.id } else old } + source.packages.filter { replacement -> replacement.id == ref.id && packages.none { it.id == ref.id } })
    ExtensionResourceKind.GROUP -> copy(groups = groups.mapNotNull { old -> if (old.id == ref.id) source.groups.firstOrNull { it.id == ref.id } else old } + source.groups.filter { replacement -> replacement.id == ref.id && groups.none { it.id == ref.id } })
}

internal fun Library.sourceWorkflow(ref: ExtensionResourceRef, id: String): Workflow? = when (ref.kind) {
    ExtensionResourceKind.MAIN -> mainCalls.firstOrNull { it.id == ref.id }?.configuredWorkflows()
    ExtensionResourceKind.GROUP -> groups.firstOrNull { it.id == ref.id }?.workflows
    else -> null
}?.firstOrNull { it.id == id }

internal fun Library.withSourceWorkflow(ref: ExtensionResourceRef, workflow: Workflow): Library {
    fun List<Workflow>.changed() = if (any { it.id == workflow.id }) map { if (it.id == workflow.id) workflow else it } else this + workflow
    return when (ref.kind) {
        ExtensionResourceKind.MAIN -> copy(mainCalls = mainCalls.map { if (it.id != ref.id) it else when (workflow.id) {
            it.history.processor.workflow.id -> it.copy(history = it.history.copy(processor = it.history.processor.copy(workflow = workflow)))
            it.history.processor.aggregationWorkflow?.id -> it.copy(history = it.history.copy(processor = it.history.processor.copy(aggregationWorkflow = workflow)))
            else -> it.copy(workflows = it.workflows.changed())
        } })
        ExtensionResourceKind.GROUP -> copy(groups = groups.map { if (it.id == ref.id) it.copy(workflows = it.workflows.changed()) else it })
        else -> this
    }
}

@Composable
internal fun WorkflowSourceBoundary(workflow: Workflow, onChange: (Workflow) -> Unit, content: @Composable () -> Unit) {
    val parent = LocalSourceNavigation.current
    val resource = parent?.resource
    if (parent == null || resource == null) { content(); return }
    SourceDraftBoundary(resource, workflowId = workflow.id,
        overlay = { it.withSourceWorkflow(resource, workflow) },
        restore = { candidate, original ->
            val previous = original.sourceWorkflow(resource, workflow.id)
            if (previous != null) candidate.withSourceWorkflow(resource, previous)
            else when (resource.kind) {
                ExtensionResourceKind.MAIN -> candidate.copy(mainCalls = candidate.mainCalls.map { if (it.id == resource.id) it.copy(workflows = it.workflows.filterNot { it.id == workflow.id }) else it })
                ExtensionResourceKind.GROUP -> candidate.copy(groups = candidate.groups.map { if (it.id == resource.id) it.copy(workflows = it.workflows.filterNot { it.id == workflow.id }) else it })
                else -> candidate
            }
        }, accept = { it.sourceWorkflow(resource, workflow.id)?.let(onChange) }, content = content)
}

@Composable
internal fun BlocksSourceBoundary(owner: ResourceLocation?, blocks: List<PromptBlock>, onChange: (List<PromptBlock>) -> Unit, content: @Composable () -> Unit) {
    val parent = LocalSourceNavigation.current
    val workflowId = owner?.workflowId
    val nodeId = owner?.nodeId
    if (parent == null || owner == null || workflowId == null || nodeId == null) { content(); return }
    fun replace(base: Library, value: List<PromptBlock>): Library {
        val workflow = base.sourceWorkflow(owner.resource, workflowId) ?: return base
        return base.withSourceWorkflow(owner.resource, workflow.copy(nodes = workflow.nodes.map { if (it.id == nodeId) it.copy(blocks = value) else it }))
    }
    SourceDraftBoundary(owner.resource, workflowId = workflowId,
        overlay = { replace(it, blocks) },
        restore = { candidate, original -> replace(candidate, original.sourceWorkflow(owner.resource, workflowId)?.nodes?.firstOrNull { it.id == nodeId }?.blocks.orEmpty()) },
        accept = { it.sourceWorkflow(owner.resource, workflowId)?.nodes?.firstOrNull { it.id == nodeId }?.blocks?.let(onChange) }, content = content)
}

internal fun sourceResourceTitle(library: Library, ref: ExtensionResourceRef): String = when (ref.kind) {
    ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == ref.id }?.name
    ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == ref.id }?.name
    ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == ref.id }?.name
    ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == ref.id }?.name
} ?: "来源已不可用"

internal fun sourceLocationTitle(library: Library, location: ResourceLocation): String {
    location.field?.let { ExtensionFieldRegistry.describe(library, it)?.let { field -> return sourceResourceTitle(library, location.resource) + " · " + field.title } }
    val workflow = location.workflowId?.let { library.sourceWorkflow(location.resource, it) }
    val node = workflow?.nodes?.firstOrNull { it.id == location.nodeId }
    val entry = when (location.resource.kind) {
        ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == location.resource.id }?.entries
        ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == location.resource.id }?.entries
        else -> null
    }?.firstOrNull { it.id == location.entryId }
    return listOfNotNull(sourceResourceTitle(library, location.resource), entry?.title?.ifBlank { entry.key }, workflow?.title,
        node?.title, node?.blocks?.firstOrNull { it.id == location.blockId }?.title).joinToString(" · ")
}

@Composable
internal fun ConfigurationFieldEditor(field: ExtensionFieldDescriptor, value: String?, references: List<PromptReferenceChoice>, onChange: (String?) -> Unit) {
    var lastDefined by remember(field.target) { mutableStateOf(value ?: field.choices.firstOrNull()?.value ?: when (field.type) {
        ActionFieldType.BOOLEAN -> "false"
        ActionFieldType.NUMBER -> field.min?.let { if (field.integer) it.toLong().toString() else it.toString() } ?: "0"
        else -> ""
    }) }
    fun update(next: String) { lastDefined = next; onChange(next) }
    if (field.nullable) ToggleLine("使用默认值", value == null) { onChange(if (it) null else lastDefined) }
    if (value == null && field.nullable) return
    val selected = value.orEmpty()
    if (field.choices.isNotEmpty() || field.target.field in setOf(ExtensionField.WORKFLOW_MODEL, ExtensionField.NODE_MODEL))
        ChoiceLine("值", selected, field.choices.map { it.value to it.title }, ::update)
    else when (field.type) {
        ActionFieldType.BOOLEAN -> ToggleLine("值", selected == "true") { update(it.toString()) }
        ActionFieldType.TEMPLATE -> PromptTemplateField("内容", selected, references, ::update, owner = field.target)
        ActionFieldType.NUMBER -> TextFieldLine(if (field.integer) "整数" else "数值", selected, ::update)
        ActionFieldType.MACRO -> TextFieldLine("宏地址", selected, ::update)
        ActionFieldType.STRING -> TextFieldLine("值", selected, ::update, minLines = if (field.target.field == ExtensionField.CHARACTER_DESCRIPTION) 3 else 1)
    }
}

@Composable
internal fun ConfigurationSourceSheet(location: ResourceLocation, onDismiss: () -> Unit) {
    val navigation = LocalSourceNavigation.current ?: return
    if (location.field == null && location.section != null) {
        LaunchedEffect(location) { onDismiss(); navigation.open(location) }
        return
    }
    val library = navigation.library
    val fields = ExtensionFieldRegistry.list(library, location.resource).filter {
        (location.entryId == null || it.target.entryId == location.entryId) &&
            (location.workflowId == null || it.target.workflowId == location.workflowId) &&
            (location.nodeId == null || it.target.nodeId == location.nodeId) &&
            (location.blockId == null || it.target.blockId == location.blockId)
    }
    var target by remember(location) { mutableStateOf(location.field ?: fields.firstOrNull()?.target) }
    val descriptor = fields.firstOrNull { it.target == target }
    val captured = remember(target) { target?.let { ExtensionFieldRegistry.read(library, it) } }
    val capturedDescriptor = remember(target) { descriptor }
    var value by remember(target) { mutableStateOf(captured) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    val references = promptReferenceChoices(emptyList(), library)
    fun dismiss() { if (value != captured) leave = true else onDismiss() }
    fun apply() {
        val field = target ?: return
        if (ExtensionFieldRegistry.describe(library, field) != capturedDescriptor) { error = "字段类型已改变，请重新打开。"; return }
        val result = ExtensionFieldRegistry.writeDraft(library, field, value, captured)
        val candidate = result.library
        if (candidate == null) { error = result.issues.joinToString("\n") { it.message }; return }
        busy = true
        navigation.apply(candidate, ResourceLocations.fromField(field)) { ok -> busy = false; if (ok) onDismiss() else error = "尚未保存；输入已保留。" }
    }
    ModalBottomSheet(onDismissRequest = ::dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(sourceResourceTitle(library, location.resource), style = MaterialTheme.typography.titleLarge)
            if (fields.isNotEmpty()) ChoiceLine("实际来源字段", target, fields.map { (it.target as ExtensionFieldRef?) to it.title }) {
                if (value == captured) target = it else error = "请先应用当前字段的修改，再切换字段。输入仍保留在这里。"
            }
            if (descriptor != null) {
                val fieldNavigation = SourceNavigation(library, navigation.resource, navigation.workflowId, navigation.focus,
                    navigation.merge,
                    leave = { candidate, destination, done ->
                        if (value == captured) navigation.leave(candidate, destination, done)
                        else { error = "请先应用当前字段的修改，再打开其他来源。"; done(false) }
                    }, publish = { candidate, done ->
                        if (value == captured) navigation.publish(candidate, done)
                        else { error = "请先应用当前字段的修改。"; done(false) }
                    }, edit = { destination ->
                        if (value == captured) navigation.edit(destination) else error = "请先应用当前字段的修改，再打开其他来源。"
                    }, canMerge = { value == captured && navigation.canMerge(it) },
                    leaveAtomic = { candidate, destination, done ->
                        if (value == captured) navigation.leaveAtomic(candidate, destination, done)
                        else { error = "请先应用当前字段的修改，再独立使用来源。"; done(false) }
                    })
                CompositionLocalProvider(LocalSourceNavigation provides fieldNavigation, LocalSourceFieldFocus provides location) {
                    ConfigurationFieldEditor(descriptor, value, references) { value = it }
                }
            }
            else Text("此位置包含高级设置，请在原编辑器中继续。")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = {
                    val full = location.copy(field = null)
                    if (value == captured) navigation.open(full)
                    else error = "当前字段还有输入；请先应用修改，或关闭并放弃后打开完整来源。"
                }) { Text("打开完整来源") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = ::dismiss) { Text("关闭") }
                Button(enabled = descriptor != null && !busy && value != captured, onClick = { confirm = true }) { Text(if (navigation.canMerge(location)) "应用到当前草稿" else "保存") }
            }
        }
    }
    if (confirm) {
        val usages = ResourceUsageIndex.usages(library, location)
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text("修改共享来源") }, text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("其他引用也会读取修改后的来源。")
                usages.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                Text("动态生成的地址无法静态穷举。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { confirm = false; apply() }) { Text("保存共享修改") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续编辑") } })
    }
    if (leave) DraftLeaveDialog({ leave = false }, { leave = false; confirm = true }, { leave = false; onDismiss() })
}

@Composable
internal fun SourceIssuesSheet(library: Library, issues: List<Issue>, onOpenLocation: (ResourceLocation) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要调整的设置", style = MaterialTheme.typography.titleLarge)
            issues.forEach { issue ->
                val resolved = ResourceLocations.resolveIssue(library, issue)
                val text = resolved?.field?.let { ExtensionFieldRegistry.read(library, it) }
                val problem = text?.let { source -> promptReferenceProblems(source, MacroTemplateTokens.scan(source)).filter { it.message == issue.message }.singleOrNull() }
                val location = if (resolved != null && problem != null) resolved.copy(spanStart = problem.start, spanEndExclusive = problem.endExclusive) else resolved
                TextButton(enabled = location != null, onClick = { location?.let(onOpenLocation); onDismiss() }) {
                    Column { Text(issue.message); location?.let { Text(sourceLocationTitle(library, it), style = MaterialTheme.typography.bodySmall) } }
                }
                if (location == null) Text("此项没有可定位的资源字段。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
