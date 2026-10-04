package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import dev.gray.companion.core.*
import kotlinx.serialization.Serializable
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.RikkaNavDisplay

@Serializable
private sealed interface CallEditorRoute : NavKey {
    @Serializable data object Home : CallEditorRoute
    @Serializable data object Calls : CallEditorRoute
    @Serializable data class Details(val id: String) : CallEditorRoute
    @Serializable data class Prompt(val id: String) : CallEditorRoute
    @Serializable data object History : CallEditorRoute
    @Serializable data object BodyDisplay : CallEditorRoute
    @Serializable data object Bindings : CallEditorRoute
    @Serializable data object Info : CallEditorRoute
}

private data class PendingMainSwitch(val targetId: String?)

/** Apply new-editor defaults only when a main call is created, never to a loaded resource. */
internal fun newEditorMainCall(id: String): MainCall {
    val main = MainCall(id = id, name = "新主调用")
    val processor = main.history.processor
    return main.copy(
        workflows = main.workflows.map { call -> call.copy(nodes = call.nodes.map { it.copy(recordTrajectory = false) }) },
        primaryWorkflowId = main.workflows.firstOrNull()?.id,
        history = main.history.copy(processor = processor.copy(workflow = processor.workflow.copy(
            nodes = processor.workflow.nodes.map { it.copy(recordTrajectory = false) }))),
    )
}

@Composable
internal fun MainCallEditor(
    initial: MainCall, onSave: (MainCall) -> Unit, onBack: () -> Unit, saving: Boolean = false,
    actions: List<ActionDescriptor> = ActionRegistry.defaults().descriptors(),
    profiles: List<ModelProfile> = emptyList(), library: Library? = null,
    onSwitchResource: ((MainCall?, String?) -> Unit)? = null,
) {
    val baseline = remember(initial.id) { MacroNaming.normalize(initial,
        library?.mainCalls.orEmpty().filterNot { it.id == initial.id }.map { it.slug }).let { main ->
        if (main.name.isBlank() || (library != null && library.mainCalls.none { it.id == main.id }))
            main.copy(name = nextDisplayName(library?.mainCalls.orEmpty().filterNot { it.id == main.id }.map { it.name }, "主调用"))
        else main
    } }
    var item by remember(initial.id) { mutableStateOf(baseline) }
    val stack = remember(initial.id) { mutableStateListOf<NavKey>(CallEditorRoute.Home) }
    val backs = remember(initial.id) { mutableStateMapOf<NavKey, () -> Unit>() }
    var promptRequest by remember(initial.id) { mutableStateOf<PromptEditRequest?>(null) }
    val callDrafts = remember(initial.id) { mutableStateMapOf<String, CallDetailDraft>() }
    var rename by remember(initial.id) { mutableStateOf(false) }
    var switchMenuOpen by remember(initial.id) { mutableStateOf(false) }
    var pendingSwitch by remember(initial.id) { mutableStateOf<PendingMainSwitch?>(null) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    fun open(route: CallEditorRoute) { stack.add(route) }
    fun openDetails(workflow: Workflow) { callDrafts[workflow.id] = CallDetailDraft(workflow, workflow); open(CallEditorRoute.Details(workflow.id)) }
    fun requestPrompt(request: PromptEditRequest) { promptRequest = request; open(CallEditorRoute.Prompt(newId())) }
    fun updateWorkflow(changed: Workflow) { item = item.copy(workflows = item.workflows.map { if (it.id == changed.id) changed else it }) }
    fun mergeWorkflow(changed: Workflow) = if (item.workflows.any { it.id == changed.id }) item.workflows.map { if (it.id == changed.id) changed else it } else item.workflows + changed
    fun referenceChoices(draft: MainCall = item) = promptReferenceChoices(draft.workflows, library, mainCall = draft)
    fun completionSources(): List<Workflow> {
        val selected = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }
        val loadedGroups = if (selected != null) library?.loadedCallGroups(selected, item).orEmpty()
            else library?.groups.orEmpty().filter { it.id in item.boundGroupIds }
        return (item.workflows + loadedGroups.flatMap { it.workflows }).distinctBy { it.id }
    }
    fun requestSwitch(targetId: String?) {
        if (saving || targetId == item.id) return
        if (item != baseline) pendingSwitch = PendingMainSwitch(targetId)
        else onSwitchResource?.invoke(null, targetId)
    }
    fun addCall() {
        val key = nextCallOutputKey(outputKeysForNewNodes(library, item.configuredWorkflows()), "call")
        val siblings = item.configuredWorkflows()
        val workflow = Workflow(title = nextDisplayName(siblings.map { it.title }, "调用"),
            slug = nextEditorSlug(siblings.map { it.slug }, "call"),
            nodes = listOf(WorkflowNode(outputKey = key, slug = key, recordTrajectory = false)))
        openDetails(workflow)
    }
    // Legacy resources without an explicit primary keep their original runtime/output contract.
    val default = item.workflows.firstOrNull { it.id == item.primaryWorkflowId } ?: item.workflows.firstOrNull()
    RikkaNavDisplay(stack, { backs[stack.last()]?.invoke() ?: pop() }, entryProvider {
        entry<CallEditorRoute.Home> { route ->
            CallEditorPage("主调用", item != baseline, onBack, { onSave(item.copy(name = item.name.trim())) }, saving, { backs[route] = it },
                titleContent = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onSwitchResource != null && !saving) {
                            val resources = (library?.mainCalls.orEmpty() + item).distinctBy { it.id }
                                .map { it.id to (if (it.id == item.id) item.name else it.name).ifBlank { "未命名主调用" } }
                            val options = resources.map { (id, name) -> (id as String?) to name } + listOf(null to "新建主调用…")
                            Box(Modifier.weight(1f, fill = false)) {
                                TextButton(onClick = { switchMenuOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text(item.name.ifBlank { "未命名主调用" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Icon(HugeIcons.ArrowDown01, null, Modifier.size(16.dp))
                                }
                                DropdownMenu(expanded = switchMenuOpen, onDismissRequest = { switchMenuOpen = false }) {
                                    options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name, maxLines = 1) }, onClick = {
                                        switchMenuOpen = false
                                        requestSwitch(id)
                                    }) }
                                }
                            }
                        } else Text(item.name.ifBlank { "未命名主调用" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = { rename = true }, enabled = !saving) {
                            Icon(HugeIcons.PencilEdit01, "重命名主调用", Modifier.size(20.dp))
                        }
                    }
                }) {
                if (default != null) {
                    CallModelChoice(default.modelProfileId, profiles) { updateWorkflow(default.copy(modelProfileId = it)) }
                    val modelNodes = default.nodes.filter { node -> actions.firstOrNull { it.id == node.actionId }?.usesModel == true }
                    val modelNode = modelNodes.lastOrNull { it.enabled && it.visible && it.outputSlot == OutputSlot.BODY }
                        ?: modelNodes.lastOrNull { it.visible && it.outputSlot == OutputSlot.BODY }
                        ?: modelNodes.lastOrNull { it.enabled } ?: modelNodes.lastOrNull()
                    modelNode?.generation?.profileId?.let { overrideId ->
                        val label = profiles.firstOrNull { it.id == overrideId }?.name?.takeIf { it.isNotBlank() } ?: overrideId
                        Text("当前正文节点使用：$label（在默认调用设置中调整）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (modelNode != null) EditorDestinationRow("提示词", "${modelNode.blocks.size} 个提示词块") {
                        requestPrompt(PromptEditRequest(modelNode.blocks, referencesForWritingNode(referenceChoices(), modelNode.id)) { blocks ->
                            val current = item.workflows.first { it.id == default.id }
                            updateWorkflow(current.copy(nodes = current.nodes.map { if (it.id == modelNode.id) it.copy(blocks = blocks) else it }))
                        })
                    }
                    if (modelNode != null) NodeTrajectoryControl(modelNode, {}, usesModel = true)
                    if (modelNode != null) RegexOutputCopyEditor(modelNode, outputKeysForNewNodes(library, item.configuredWorkflows())) { copy ->
                        val current = item.workflows.firstOrNull { it.id == default.id } ?: return@RegexOutputCopyEditor
                        updateWorkflow(current.copy(nodes = current.nodes.map { if (it.id == modelNode.id) it.copy(regexOutputCopy = copy) else it }))
                    }
                    EditorDestinationRow("默认调用设置", if (default.nodes.size > 1) "${default.nodes.size} 个节点" else null) { openDetails(default) }
                } else OutlinedButton(onClick = {
                    val key = nextCallOutputKey(outputKeysForNewNodes(library, item.configuredWorkflows()), "reply")
                    val workflow = Workflow(title = nextDisplayName(item.configuredWorkflows().map { it.title }, "调用"),
                        slug = nextEditorSlug(item.configuredWorkflows().map { it.slug }, "call"),
                        nodes = listOf(WorkflowNode(outputKey = key, slug = key, visible = true, recordTrajectory = false)))
                    item = item.copy(workflows = item.workflows + workflow, primaryWorkflowId = workflow.id)
                }) { Text("添加默认调用") }
                HorizontalDivider()
                EditorDestinationRow("正文显示", item.bodySourceTileId?.let { selected ->
                    (item.tiles + library?.groups.orEmpty().flatMap { it.tiles }).firstOrNull { it.id == selected }?.title ?: "所选模板已不可用"
                } ?: "原模型正文") { open(CallEditorRoute.BodyDisplay) }
                EditorDestinationRow("历史配置", "范围、角色、包装与插入项") { open(CallEditorRoute.History) }
                EditorDestinationRow("关联资源", "${item.boundPackageIds.size} 个提示词包 · ${item.boundGroupIds.size} 个调用组") { open(CallEditorRoute.Bindings) }
                HorizontalDivider()
                EditorDestinationRow("其他调用", "${item.workflows.count { it.id != default?.id }} 项") { open(CallEditorRoute.Calls) }
            }
        }
        entry<CallEditorRoute.Calls> { route ->
            CallListEditor("其他调用", item.workflows.filterNot { it.id == default?.id }, ::pop, { id -> item.workflows.firstOrNull { it.id == id }?.let(::openDetails) }, ::addCall,
                { workflow, enabled -> updateWorkflow(workflow.copy(enabled = enabled)) }, { backs[route] = it }, leadingContent = {
                    ChoiceLine("默认调用", item.primaryWorkflowId,
                        listOf<Pair<String?, String>>(null to "保留旧配置的显示方式") +
                            item.workflows.map { it.id to it.title.ifBlank { "未命名调用" } }) {
                        item = item.copy(primaryWorkflowId = it)
                    }
                    if (item.primaryWorkflowId == null) Text("此资源保留旧配置；首页展示第一项调用，原节点和输出设置保持原样。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                })
        }
        entry<CallEditorRoute.Details> { route ->
            callDrafts[route.id]?.let { draft ->
                val call = draft.value
                CallDetailsEditor(draft.baseline, call, { callDrafts[route.id] = draft.copy(value = it) }, actions, profiles, outputKeysForNewNodes(library, item.configuredWorkflows() + call),
                    { changed -> referenceChoices(item.copy(workflows = mergeWorkflow(changed))) },
                    { item = item.copy(workflows = mergeWorkflow(it)); pop() }, ::pop, ::requestPrompt,
                    onDelete = if (call.id == default?.id) null else ({ item = item.copy(workflows = item.workflows.filterNot { it.id == call.id }); pop() }),
                    onRegisterBack = { backs[route] = it }, siblings = item.configuredWorkflows(),
                    tags = library?.promptTags.orEmpty(), triggerTargets = completionSources())
            }
        }
        entry<CallEditorRoute.Prompt> { route -> promptRequest?.let { request ->
            PromptBlocksEditor(request.blocks, request.references, { request.save(it); pop() }, ::pop, { backs[route] = it }, tags = library?.promptTags.orEmpty())
        } }
        entry<CallEditorRoute.History> { route ->
            var history by remember { mutableStateOf(item.history) }
            val baseline = remember { item.history }
            CallEditorPage("历史配置", history != baseline, ::pop, { item = item.copy(history = history); pop() }, onRegisterBack = { backs[route] = it }) {
                HistoryConfigEditor(history, library, referenceChoices(item.copy(history = history))) { history = it }
            }
        }
        entry<CallEditorRoute.BodyDisplay> { route ->
            val baseline = remember { item }
            var tiles by remember { mutableStateOf(item.tiles) }
            var sourceId by remember { mutableStateOf(item.bodySourceTileId) }
            var textTemplate by remember { mutableStateOf(item.bodyTextTemplate) }
            var invalid by remember { mutableStateOf(false) }
            val selectedSession = library?.sessions?.firstOrNull { it.id == library.selectedSessionId && it.mainCallId == item.id }
            val groups = if (selectedSession != null) library?.loadedCallGroups(selectedSession, item).orEmpty()
                else library?.groups.orEmpty().filter { it.id in item.boundGroupIds }
            val candidates = (tiles + groups.flatMap { it.tiles }).filter {
                it.enabled && it.placement == MacroTilePlacement.CHAT && it.kind in setOf(MacroTileKind.TEXT, MacroTileKind.HTML)
            }.distinctBy { it.id }
            val chosen = candidates.firstOrNull { it.id == sourceId }
            val choices = listOf<Pair<String?, String>>(null to "原模型正文") + candidates.map { it.id to it.title.ifBlank { "未命名显示模板" } }
            CallEditorPage("正文显示", tiles != baseline.tiles || sourceId != baseline.bodySourceTileId || textTemplate != baseline.bodyTextTemplate,
                ::pop, {
                    invalid = sourceId != null && (chosen == null || (chosen.kind == MacroTileKind.HTML && textTemplate.isBlank()))
                    if (!invalid) { item = item.copy(tiles = tiles, bodySourceTileId = sourceId, bodyTextTemplate = textTemplate); pop() }
                }, onRegisterBack = { backs[route] = it }) {
                Text("每轮完成后，将所选显示模板冻结为该轮正文；原模型输出仍保留。旧聊天及下方面板的显示保持原样。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceLine("正文来源", sourceId, choices + if (sourceId != null && chosen == null) listOf(sourceId to "所选模板已不可用") else emptyList()) {
                    sourceId = it; invalid = false
                }
                if (chosen?.kind == MacroTileKind.HTML) {
                    PromptTemplateField("HTML 正文的语义文本", textTemplate, referenceChoices(item.copy(tiles = tiles)), { textTemplate = it }, minLines = 3)
                    Text("这段文字供历史、复制和导出使用；HTML 只负责画面。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (invalid) Text("请选择可用的聊天文本或 HTML 模板；HTML 还需填写语义文本。", color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                Text("本主调用的显示模板", style = MaterialTheme.typography.titleMedium)
                MacroTilesEditor(tiles, referenceChoices(item.copy(tiles = tiles))) { tiles = it; invalid = false }
                if (groups.isNotEmpty()) Text("已关联调用组的可用模板也能作为正文来源；编辑它们请进入对应调用组的显示磁贴设置。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        entry<CallEditorRoute.Bindings> { route ->
            val baseline = remember { item }
            var packages by remember { mutableStateOf(item.boundPackageIds) }
            var groups by remember { mutableStateOf(item.boundGroupIds) }
            CallEditorPage("关联资源", packages != baseline.boundPackageIds || groups != baseline.boundGroupIds, ::pop,
                { item = item.copy(boundPackageIds = packages, boundGroupIds = groups); pop() }, onRegisterBack = { backs[route] = it }) {
                Text("提示词包", style = MaterialTheme.typography.titleSmall)
                ResourceBindingList(library?.packages.orEmpty().map { it.id to it.name.ifBlank { it.namespace } }, packages) { packages = it }
                HorizontalDivider()
                Text("调用组", style = MaterialTheme.typography.titleSmall)
                ResourceBindingList(library?.groups.orEmpty().map { it.id to it.name.ifBlank { "未命名调用组" } }, groups) { groups = it }
                Text("关联资源随主调用加载；提示词包通过宏引用参与提示词。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    })
    if (rename) NamingDialog("主调用名称", item.name, item.slug,
        library?.mainCalls.orEmpty().filterNot { it.id == item.id }.map { it.name },
        library?.mainCalls.orEmpty().filterNot { it.id == item.id }.map { it.slug },
        { rename = false }) { name, slug -> item = item.copy(name = name, slug = slug); rename = false }
    pendingSwitch?.let { pending -> AlertDialog(onDismissRequest = { pendingSwitch = null }, title = { Text("切换主调用？") }, text = { Text("当前主调用有未保存的修改。") },
        confirmButton = { TextButton(onClick = { pendingSwitch = null; onSwitchResource?.invoke(item, pending.targetId) }, enabled = !saving) { Text("保存并切换") } },
        dismissButton = { Row { TextButton(onClick = { pendingSwitch = null }) { Text("取消") }; TextButton(onClick = { pendingSwitch = null; onSwitchResource?.invoke(null, pending.targetId) }, enabled = !saving) { Text("放弃并切换") } } }) }
}

@Composable
internal fun GroupEditor(
    initial: CallGroup, packages: List<PromptPackage>, onSave: (CallGroup) -> Unit, onBack: () -> Unit,
    saving: Boolean = false, actions: List<ActionDescriptor> = ActionRegistry.defaults().descriptors(),
    profiles: List<ModelProfile> = emptyList(), library: Library? = null,
) {
    val baseline = remember(initial.id) { MacroNaming.normalize(initial,
        library?.groups.orEmpty().filterNot { it.id == initial.id }.map { it.slug }).let { group ->
        if (group.name.isBlank() || (library != null && library.groups.none { it.id == group.id }))
            group.copy(name = nextDisplayName(library?.groups.orEmpty().filterNot { it.id == group.id }.map { it.name }, "调用组"))
        else group
    } }
    var item by remember(initial.id) { mutableStateOf(baseline) }
    val stack = remember(initial.id) { mutableStateListOf<NavKey>(CallEditorRoute.Home) }
    val backs = remember(initial.id) { mutableStateMapOf<NavKey, () -> Unit>() }
    var promptRequest by remember(initial.id) { mutableStateOf<PromptEditRequest?>(null) }
    val callDrafts = remember(initial.id) { mutableStateMapOf<String, CallDetailDraft>() }
    var rename by remember(initial.id) { mutableStateOf(false) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    fun open(route: CallEditorRoute) { stack.add(route) }
    fun openDetails(workflow: Workflow) { callDrafts[workflow.id] = CallDetailDraft(workflow, workflow); open(CallEditorRoute.Details(workflow.id)) }
    fun requestPrompt(request: PromptEditRequest) { promptRequest = request; open(CallEditorRoute.Prompt(newId())) }
    fun updateWorkflow(changed: Workflow) { item = item.copy(workflows = item.workflows.map { if (it.id == changed.id) changed else it }) }
    fun mergeWorkflow(changed: Workflow) = if (item.workflows.any { it.id == changed.id }) item.workflows.map { if (it.id == changed.id) changed else it } else item.workflows + changed
    fun referenceChoices(draft: CallGroup = item) = promptReferenceChoices(draft.workflows, library, callGroup = draft)
    fun completionSources(): List<Workflow> {
        val selected = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }
        val main = library?.mainCalls?.firstOrNull { it.id == selected?.mainCallId }
        val loadedGroups = if (selected != null) library?.loadedCallGroups(selected, main).orEmpty()
            else library?.groups.orEmpty().filter { it.id in main?.boundGroupIds.orEmpty() }
        return (item.workflows + main?.workflows.orEmpty() + loadedGroups.flatMap { it.workflows }).distinctBy { it.id }
    }
    fun addCall() {
        val key = nextCallOutputKey(outputKeysForNewNodes(library, item.workflows), "call")
        val workflow = Workflow(title = nextDisplayName(item.workflows.map { it.title }, "调用"),
            slug = nextEditorSlug(item.workflows.map { it.slug }, "call"),
            nodes = listOf(WorkflowNode(outputKey = key, slug = key, recordTrajectory = false)))
        openDetails(workflow)
    }
    RikkaNavDisplay(stack, { backs[stack.last()]?.invoke() ?: pop() }, entryProvider {
        entry<CallEditorRoute.Home> { route ->
            CallEditorPage("调用组", item != baseline, onBack, { onSave(item.copy(name = item.name.trim())) }, saving, { backs[route] = it }) {
                EditorTitleRow(item.name, { rename = true })
                EditorDestinationRow("组内调用", "${item.workflows.size} 项") { open(CallEditorRoute.Calls) }
                HorizontalDivider()
                EditorDestinationRow("关联提示词包", "${item.boundPackageIds.size} 项") { open(CallEditorRoute.Bindings) }
                EditorDestinationRow("资源信息与默认状态", item.description.lineSequence().firstOrNull()?.take(48)?.takeIf { it.isNotBlank() }) { open(CallEditorRoute.Info) }
            }
        }
        entry<CallEditorRoute.Calls> { route -> CallListEditor("组内调用", item.workflows, ::pop, { id -> item.workflows.firstOrNull { it.id == id }?.let(::openDetails) }, ::addCall,
            { workflow, enabled -> updateWorkflow(workflow.copy(enabled = enabled)) }, { backs[route] = it }) }
        entry<CallEditorRoute.Details> { route -> callDrafts[route.id]?.let { draft ->
            val call = draft.value
            CallDetailsEditor(draft.baseline, call, { callDrafts[route.id] = draft.copy(value = it) }, actions, profiles, outputKeysForNewNodes(library, item.workflows + call),
                { changed -> referenceChoices(item.copy(workflows = mergeWorkflow(changed))) },
                { item = item.copy(workflows = mergeWorkflow(it)); pop() }, ::pop, ::requestPrompt, { item = item.copy(workflows = item.workflows.filterNot { it.id == call.id }); pop() }, { backs[route] = it },
                siblings = item.workflows, tags = library?.promptTags.orEmpty(), triggerTargets = completionSources())
        } }
        entry<CallEditorRoute.Prompt> { route -> promptRequest?.let { request -> PromptBlocksEditor(request.blocks, request.references, { request.save(it); pop() }, ::pop, { backs[route] = it }, tags = library?.promptTags.orEmpty()) } }
        entry<CallEditorRoute.Bindings> { route ->
            val baseline = remember { item.boundPackageIds }
            var selected by remember { mutableStateOf(item.boundPackageIds) }
            CallEditorPage("关联提示词包", selected != baseline, ::pop, { item = item.copy(boundPackageIds = selected); pop() }, onRegisterBack = { backs[route] = it }) {
                ResourceBindingList(packages.map { it.id to it.name.ifBlank { it.namespace } }, selected) { selected = it }
            }
        }
        entry<CallEditorRoute.Info> { route ->
            val baseline = remember { item }
            var description by remember { mutableStateOf(item.description) }
            var state by remember { mutableStateOf(item.stateDefaults) }
            CallEditorPage("资源信息与默认状态", description != baseline.description || state != baseline.stateDefaults, ::pop,
                { item = item.copy(description = description, stateDefaults = state); pop() }, onRegisterBack = { backs[route] = it }) {
                TextFieldLine("说明", description, { description = it }, minLines = 2)
                HorizontalDivider()
                Text("默认状态", style = MaterialTheme.typography.titleSmall)
                state.forEach { (stateKey, value) -> key(stateKey) {
                    TextFieldLine(stateKey, value, { state = state + (stateKey to it) })
                    TextButton(onClick = { state = state - stateKey }) { Text("删除 $stateKey") }
                } }
                var newKey by remember { mutableStateOf("") }
                TextFieldLine("新状态键", newKey, { newKey = it })
                val candidate = newKey.trim()
                if (candidate in state) Text("此状态键已存在。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { state = state + (candidate to ""); newKey = "" }, enabled = candidate.isNotBlank() && candidate !in state) { Text("添加默认状态") }
            }
        }
    })
    if (rename) NamingDialog("调用组名称", item.name, item.slug,
        library?.groups.orEmpty().filterNot { it.id == item.id }.map { it.name },
        library?.groups.orEmpty().filterNot { it.id == item.id }.map { it.slug },
        { rename = false }) { name, slug -> item = item.copy(name = name, slug = slug); rename = false }
}
