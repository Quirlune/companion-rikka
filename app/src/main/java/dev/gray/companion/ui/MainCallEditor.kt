package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Serializable
private sealed interface CallEditorRoute : NavKey {
    @Serializable data object Home : CallEditorRoute
    @Serializable data object Calls : CallEditorRoute
    @Serializable data class Details(val id: String) : CallEditorRoute
    @Serializable data class Prompt(val id: String) : CallEditorRoute
    @Serializable data object History : CallEditorRoute
    @Serializable data object Cards : CallEditorRoute
    @Serializable data object BodyAppearance : CallEditorRoute
    @Serializable data object Bindings : CallEditorRoute
    @Serializable data object Info : CallEditorRoute
}

private data class PendingMainSwitch(val targetId: String?)

/** Apply new-editor defaults only when a main call is created, never to a loaded resource. */
internal fun newEditorMainCall(id: String): MainCall {
    val main = MainCall(id = id, name = "新主调用", specialDefault = true)
    val processor = main.history.processor
    return main.copy(
        workflows = main.workflows.map { call -> DefaultPromptSlots.ensureMissing(call.copy(nodes = call.nodes.map { it.copy(
            actionId = "model.generate", visible = true, outputSlot = OutputSlot.BODY,
            dependsOn = emptyList(), recordTrajectory = true,
        ) })) },
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
    var avatarEditorOpen by remember(initial.id) { mutableStateOf(false) }
    var avatarProcessing by remember(initial.id) { mutableStateOf(false) }
    val editingBusy = saving || avatarProcessing
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    fun open(route: CallEditorRoute) { stack.add(route) }
    fun openDetails(workflow: Workflow) {
        if (!item.specialDefault && DefaultCallRules.primary(item)?.id == workflow.id && DefaultCallRules.compatible(workflow))
            item = item.copy(specialDefault = true, primaryWorkflowId = workflow.id)
        callDrafts[workflow.id] = CallDetailDraft(workflow, workflow)
        open(CallEditorRoute.Details(workflow.id))
    }
    fun requestPrompt(request: PromptEditRequest) { promptRequest = request.copy(workflowDraft = stack.last() is CallEditorRoute.Details); open(CallEditorRoute.Prompt(newId())) }
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
        if (editingBusy || targetId == item.id) return
        if (item != baseline) pendingSwitch = PendingMainSwitch(targetId)
        else onSwitchResource?.invoke(null, targetId)
    }
    fun addCall() {
        val siblings = item.configuredWorkflows()
        val workflow = Workflow(title = nextDisplayName(siblings.map { it.title }, "调用"),
            slug = nextEditorSlug(siblings.map { it.slug }, "call"),
            nodes = emptyList())
        openDetails(workflow)
    }
    // Legacy resources without an explicit primary keep their original runtime/output contract.
    val default = DefaultCallRules.primary(item)
    val sourceResource = ExtensionResourceRef(ExtensionResourceKind.MAIN, item.id)
    val sourceFocus = LocalSourceNavigation.current?.focus
    LaunchedEffect(sourceFocus) {
        if (sourceFocus != null && sourceFocus.resource == sourceResource && sourceFocus.field == null) {
            when {
                sourceFocus.section == "avatar" -> avatarEditorOpen = true
                sourceFocus.workflowId == item.history.processor.workflow.id || (sourceFocus.workflowId != null && sourceFocus.workflowId == item.history.processor.aggregationWorkflow?.id) -> open(CallEditorRoute.History)
                sourceFocus.workflowId != null -> item.workflows.firstOrNull { it.id == sourceFocus.workflowId }?.let { workflow ->
                    openDetails(workflow)
                    val node = workflow.nodes.firstOrNull { it.id == sourceFocus.nodeId }
                    if (sourceFocus.blockId != null && node != null) requestPrompt(PromptEditRequest(node.blocks,
                        referencesForWritingNode(referenceChoices(), node.id), sourceFocus) { changedBlocks ->
                        callDrafts[workflow.id]?.let { current ->
                            callDrafts[workflow.id] = current.copy(value = current.value.copy(nodes = current.value.nodes.map { if (it.id == node.id) it.copy(blocks = changedBlocks) else it }))
                        }
                    })
                }
                sourceFocus.section?.startsWith("history") == true -> open(CallEditorRoute.History)
            }
        }
    }
    SourceDraftBoundary(sourceResource,
        overlay = { base -> base.withSourceResource(base.copy(mainCalls = listOf(item)), sourceResource) },
        restore = { candidate, parent -> candidate.withSourceResource(parent, sourceResource) },
        accept = { candidate -> candidate.mainCalls.firstOrNull { it.id == item.id }?.let { item = it } }) {
    RikkaNavDisplay(stack, { backs[stack.last()]?.invoke() ?: pop() }, entryProvider {
        entry<CallEditorRoute.Home> { route ->
            CallEditorPage("主调用", item != baseline, onBack, { if (!editingBusy) onSave(item.copy(name = item.name.trim())) }, editingBusy, { backs[route] = it },
                titleContent = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.semantics { contentDescription = "编辑主调用头像" }) {
                            ResourceAvatarBadge(item.name.ifBlank { "主调用" }, item.avatar, 36.dp,
                                onClick = { if (!editingBusy) avatarEditorOpen = !avatarEditorOpen })
                        }
                        Spacer(Modifier.width(8.dp))
                        if (onSwitchResource != null && !editingBusy) {
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
                        IconButton(onClick = { rename = true }, enabled = !editingBusy) {
                            Icon(HugeIcons.PencilEdit01, "重命名主调用", Modifier.size(20.dp))
                        }
                    }
                }) {
                if (avatarEditorOpen) ResourceAvatarEditor(item.name, item.avatar,
                    onProcessingChange = { avatarProcessing = it }, onChange = { item = item.copy(avatar = it) })
                if (default != null) {
                    CallModelChoice(default.modelProfileId, profiles) { updateWorkflow(default.copy(modelProfileId = it)) }
                    val modelNodes = default.nodes.filter { node -> actions.firstOrNull { it.id == node.actionId }?.usesModel == true }
                    val modelNode = modelNodes.lastOrNull { it.enabled && it.visible && it.outputSlot == OutputSlot.BODY }
                        ?: modelNodes.lastOrNull { it.visible && it.outputSlot == OutputSlot.BODY }
                        ?: modelNodes.lastOrNull { it.enabled } ?: modelNodes.lastOrNull()
                    if (modelNode != null) SharedModelGenerationEditor(
                        options = modelNode.generation,
                        reasoningParsing = modelNode.reasoningParsing,
                        profiles = profiles,
                        modelLabel = "主回复行为模型",
                    ) { generation, parsing ->
                        val current = item.workflows.firstOrNull { it.id == default.id }
                            ?: return@SharedModelGenerationEditor
                        updateWorkflow(current.copy(nodes = current.nodes.map { node ->
                            if (node.id == modelNode.id) node.copy(generation = generation, reasoningParsing = parsing) else node
                        }))
                    }
                    val removableDefaultSlotIds = remember(item, library, actions) {
                        val cleanupLibrary = library?.let { current ->
                            current.copy(mainCalls = current.mainCalls.filterNot { it.id == item.id } + item)
                        }
                        modelNode?.let { node ->
                            cleanupLibrary?.let { DefaultPromptSlots.unusedDefaultDuplicates(node, it) }
                        }.orEmpty()
                    }
                    if (modelNode != null) EditorDestinationRow("提示词", "${modelNode.blocks.size} 个提示词块") {
                        requestPrompt(PromptEditRequest(modelNode.blocks, referencesForWritingNode(referenceChoices(), modelNode.id), ResourceLocation(sourceResource, workflowId = default.id, nodeId = modelNode.id)) { blocks ->
                            val current = item.workflows.first { it.id == default.id }
                            updateWorkflow(current.copy(nodes = current.nodes.map { if (it.id == modelNode.id) it.copy(blocks = blocks) else it }))
                        })
                    }
                    if (DefaultCallRules.compatible(default) && DefaultPromptSlots.ensureMissing(default) != default) {
                        OutlinedButton(onClick = {
                            item.workflows.firstOrNull { it.id == default.id }?.let { updateWorkflow(DefaultPromptSlots.ensureMissing(it)) }
                        }, enabled = !editingBusy) { Text("添加四个默认接收槽") }
                        Text("仅在当前默认模型还没有接收槽时创建一组；已有接收槽与原聊天保留，保存后生效。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (removableDefaultSlotIds.isNotEmpty() && modelNode != null) {
                        val removableNames = modelNode.blocks.filter { it.id in removableDefaultSlotIds }.map { it.title }
                        OutlinedButton(onClick = {
                            val current = item.workflows.firstOrNull { it.id == default.id }
                            if (current != null) updateWorkflow(current.copy(nodes = current.nodes.map { node ->
                                if (node.id == modelNode.id) node.copy(blocks = node.blocks.filterNot { it.id in removableDefaultSlotIds }) else node
                            }))
                        }, enabled = !editingBusy) { Text("移除 ${removableDefaultSlotIds.size} 个空默认槽") }
                        Text("仅清理完整默认配置且未被任何提示注入引用的槽：${removableNames.joinToString("、")}。自定义槽与已保存聊天的冻结内容保留；保存主调用后生效。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (modelNode != null) NodeTrajectoryControl(modelNode, {}, usesModel = true)
                    if (modelNode != null) RegexOutputCopyEditor(modelNode, outputKeysForNewNodes(library, item.configuredWorkflows())) { copy ->
                        val current = item.workflows.firstOrNull { it.id == default.id } ?: return@RegexOutputCopyEditor
                        updateWorkflow(current.copy(nodes = current.nodes.map { if (it.id == modelNode.id) it.copy(regexOutputCopy = copy) else it }))
                    }
                    EditorDestinationRow(if (item.specialDefault) "默认模型调用" else "默认调用设置",
                        if (!DefaultCallRules.compatible(default)) "旧配置 · ${default.nodes.size} 个节点" else "模型主体 · 正文与后处理") { openDetails(default) }
                    if (item.specialDefault) Text("默认调用固定一个模型主体，结果作为正文并可后处理；初始由用户消息触发一次，触发和条件仍可自定义，不限制其他调用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text(if (DefaultCallRules.compatible(default))
                        "打开编辑时将本草稿按专用默认模型调用管理；保存后生效，原聊天不改动。"
                        else "这项旧默认调用保留完整节点与原有规则。可先整理为一个生成正文的模型主体，再采用专用默认调用。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else OutlinedButton(onClick = {
                    val key = nextCallOutputKey(outputKeysForNewNodes(library, item.configuredWorkflows()), "reply")
                    val workflow = DefaultPromptSlots.ensureMissing(Workflow(title = nextDisplayName(item.configuredWorkflows().map { it.title }, "调用"),
                        slug = nextEditorSlug(item.configuredWorkflows().map { it.slug }, "call"),
                        nodes = listOf(WorkflowNode(actionId = "model.generate", outputKey = key, slug = key,
                            visible = true, outputSlot = OutputSlot.BODY, recordTrajectory = true))))
                    item = item.copy(workflows = item.workflows + workflow, primaryWorkflowId = workflow.id, specialDefault = true)
                }) { Text("添加默认调用") }
                HorizontalDivider()
                EditorDestinationRow("正文外观", if (BodyAppearanceTemplates.isCustom(item.bodyAppearanceTemplate)) "自定义 HTML 与 CSS" else "默认正文样式") { open(CallEditorRoute.BodyAppearance) }
                EditorDestinationRow("历史配置", "范围、角色、包装与插入项") { open(CallEditorRoute.History) }
                EditorDestinationRow("关联资源", "${item.boundPackageIds.size} 个提示词包 · ${item.boundGroupIds.size} 个调用组") { open(CallEditorRoute.Bindings) }
                HorizontalDivider()
                EditorDestinationRow("其他调用", "${item.workflows.count { it.id != default?.id }} 项") { open(CallEditorRoute.Calls) }
            }
        }
        entry<CallEditorRoute.Calls> { route ->
            CallListEditor("其他调用", item.workflows.filterNot { it.id == default?.id }, ::pop, { id -> item.workflows.firstOrNull { it.id == id }?.let(::openDetails) }, ::addCall,
                { workflow, enabled -> updateWorkflow(workflow.copy(enabled = enabled)) }, { backs[route] = it }, leadingContent = {
                    if (item.specialDefault) ChoiceLine("默认模型调用", item.primaryWorkflowId ?: default?.id,
                        item.workflows.filter(DefaultCallRules::compatible).map { (it.id as String?) to it.title.ifBlank { "未命名调用" } }) { selected ->
                        if (selected != null) item = item.copy(primaryWorkflowId = selected)
                    } else ChoiceLine("默认调用", item.primaryWorkflowId,
                            listOf<Pair<String?, String>>(null to "保留旧配置的显示方式") +
                                item.workflows.map { it.id to it.title.ifBlank { "未命名调用" } }) {
                            item = item.copy(primaryWorkflowId = it)
                        }
                    if (!item.specialDefault && item.primaryWorkflowId == null) Text("此资源保留旧配置；首页展示第一项调用，原节点和输出设置保持原样。",
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
                    tags = library?.promptTags.orEmpty(), triggerTargets = completionSources(),
                    specialDefault = item.specialDefault && call.id == default?.id)
            }
        }
        entry<CallEditorRoute.Prompt> { route -> promptRequest?.let { request ->
            val workflowDraft = if (request.workflowDraft) request.location?.workflowId?.let { callDrafts[it] } else null
            val promptContent: @Composable () -> Unit = {
                PromptBlocksEditor(request.blocks, request.references, { changedBlocks ->
                    val location = request.location
                    val currentDraft = if (request.workflowDraft) location?.workflowId?.let { callDrafts[it] } else null
                    val current = currentDraft?.value ?: item.workflows.firstOrNull { it.id == location?.workflowId }
                    if (current != null && location?.nodeId != null) {
                        val changed = current.copy(nodes = current.nodes.map { if (it.id == location.nodeId) it.copy(blocks = changedBlocks) else it })
                        if (currentDraft != null) callDrafts[current.id] = currentDraft.copy(value = changed) else updateWorkflow(changed)
                    } else request.save(changedBlocks)
                    pop()
                }, ::pop,
                    { backs[route] = it }, tags = library?.promptTags.orEmpty(), owner = request.location)
            }
            if (workflowDraft != null) WorkflowSourceBoundary(workflowDraft.value,
                { changed -> callDrafts[changed.id] = workflowDraft.copy(value = changed) }, promptContent)
            else promptContent()
        } }
        entry<CallEditorRoute.History> { route ->
            var history by remember { mutableStateOf(item.history) }
            val baseline = remember { item.history }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(mainCalls = base.mainCalls.map { if (it.id == item.id) it.copy(history = history) else it }) },
                restore = { candidate, parent -> candidate.copy(mainCalls = candidate.mainCalls.map { main -> if (main.id == item.id) main.copy(history = parent.mainCalls.first { it.id == item.id }.history) else main }) },
                accept = { candidate -> candidate.mainCalls.firstOrNull { it.id == item.id }?.let { history = it.history } }) {
            CallEditorPage("历史配置", history != baseline, ::pop, { item = item.copy(history = history); pop() }, onRegisterBack = { backs[route] = it }) {
                HistoryConfigEditor(history, library, referenceChoices(item.copy(history = history)), editedMainId = item.id) { history = it }
            }
            }
        }
        entry<CallEditorRoute.BodyAppearance> { route ->
            val original = remember { item.bodyAppearanceTemplate }
            var template by remember { mutableStateOf(original) }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(mainCalls = base.mainCalls.map { if (it.id == item.id) it.copy(bodyAppearanceTemplate = template) else it }) },
                restore = { candidate, parent -> candidate.copy(mainCalls = candidate.mainCalls.map { main ->
                    if (main.id == item.id) main.copy(bodyAppearanceTemplate = parent.mainCalls.first { it.id == item.id }.bodyAppearanceTemplate) else main
                }) },
                accept = { candidate -> candidate.mainCalls.firstOrNull { it.id == item.id }?.let { template = it.bodyAppearanceTemplate } }) {
                BodyAppearanceEditor(original, template, { template = it },
                    { item = item.copy(bodyAppearanceTemplate = template); pop() }, ::pop,
                    onRegisterBack = { backs[route] = it })
            }
        }
        entry<CallEditorRoute.Bindings> { route ->
            val baseline = remember { item }
            var packages by remember { mutableStateOf(item.boundPackageIds) }
            var groups by remember { mutableStateOf(item.boundGroupIds) }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(mainCalls = base.mainCalls.map { if (it.id == item.id) it.copy(boundPackageIds = packages, boundGroupIds = groups) else it }) },
                restore = { candidate, parent ->
                    val old = parent.mainCalls.first { it.id == item.id }
                    candidate.copy(mainCalls = candidate.mainCalls.map { if (it.id == item.id) it.copy(boundPackageIds = old.boundPackageIds, boundGroupIds = old.boundGroupIds) else it })
                }, accept = { candidate -> candidate.mainCalls.firstOrNull { it.id == item.id }?.let { packages = it.boundPackageIds; groups = it.boundGroupIds } }) {
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
        }
    })
    }
    if (rename) NamingDialog("主调用名称", item.name, item.slug,
        library?.mainCalls.orEmpty().filterNot { it.id == item.id }.map { it.name },
        library?.mainCalls.orEmpty().filterNot { it.id == item.id }.map { it.slug },
        { rename = false }) { name, slug -> item = item.copy(name = name, slug = slug); rename = false }
    pendingSwitch?.let { pending -> AlertDialog(onDismissRequest = { pendingSwitch = null }, title = { Text("切换主调用？") }, text = { Text("当前主调用有未保存的修改。") },
        confirmButton = { TextButton(onClick = { pendingSwitch = null; onSwitchResource?.invoke(item, pending.targetId) }, enabled = !editingBusy) { Text("保存并切换") } },
        dismissButton = { Row { TextButton(onClick = { pendingSwitch = null }) { Text("取消") }; TextButton(onClick = { pendingSwitch = null; onSwitchResource?.invoke(null, pending.targetId) }, enabled = !editingBusy) { Text("放弃并切换") } } }) }

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
    fun requestPrompt(request: PromptEditRequest) { promptRequest = request.copy(workflowDraft = stack.last() is CallEditorRoute.Details); open(CallEditorRoute.Prompt(newId())) }
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
        val workflow = Workflow(title = nextDisplayName(item.workflows.map { it.title }, "调用"),
            slug = nextEditorSlug(item.workflows.map { it.slug }, "call"),
            nodes = emptyList())
        openDetails(workflow)
    }
    val sourceResource = ExtensionResourceRef(ExtensionResourceKind.GROUP, item.id)
    val sourceFocus = LocalSourceNavigation.current?.focus
    LaunchedEffect(sourceFocus) {
        if (sourceFocus != null && sourceFocus.resource == sourceResource && sourceFocus.field == null) {
            if (sourceFocus.workflowId != null) item.workflows.firstOrNull { it.id == sourceFocus.workflowId }?.let { workflow ->
                    openDetails(workflow)
                    val node = workflow.nodes.firstOrNull { it.id == sourceFocus.nodeId }
                    if (sourceFocus.blockId != null && node != null) requestPrompt(PromptEditRequest(node.blocks,
                        referencesForWritingNode(referenceChoices(), node.id), sourceFocus) { changedBlocks ->
                        callDrafts[workflow.id]?.let { current ->
                            callDrafts[workflow.id] = current.copy(value = current.value.copy(nodes = current.value.nodes.map { if (it.id == node.id) it.copy(blocks = changedBlocks) else it }))
                        }
                    })
                }
            else if (sourceFocus.section?.startsWith("cards") == true) open(CallEditorRoute.Cards)
            else if (sourceFocus.section != null) open(CallEditorRoute.Info)
        }
    }
    SourceDraftBoundary(sourceResource,
        overlay = { base -> base.withSourceResource(base.copy(groups = listOf(item)), sourceResource) },
        restore = { candidate, parent -> candidate.withSourceResource(parent, sourceResource) },
        accept = { candidate -> candidate.groups.firstOrNull { it.id == item.id }?.let { item = it } }) {
    RikkaNavDisplay(stack, { backs[stack.last()]?.invoke() ?: pop() }, entryProvider {
        entry<CallEditorRoute.Home> { route ->
            CallEditorPage("调用组", item != baseline, onBack, { onSave(item.copy(name = item.name.trim())) }, saving, { backs[route] = it }) {
                EditorTitleRow(item.name, { rename = true })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { open(CallEditorRoute.Cards) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                        Text("卡片", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { open(CallEditorRoute.Bindings) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "关联提示词包" },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                        Text("提示词包", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { open(CallEditorRoute.Info) }, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)) {
                        Text("资源信息", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("调用 · ${item.workflows.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::addCall) { Text("添加调用") }
                }
                if (item.workflows.isEmpty()) Text("暂无调用，添加后可配置一个或多个行为。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CardGroup {
                    item.workflows.sortedBy { !it.enabled }.forEach { workflow ->
                        item(onClick = { openDetails(workflow) },
                            headlineContent = { Text(workflow.title.ifBlank { "未命名调用" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    val types = workflow.nodes.map { node -> callBehaviorTypeLabel(node.actionId, actions) }.distinct()
                                    val summary = types.take(2).joinToString("、") + if (types.size > 2) "等" else ""
                                    Text("${workflow.nodes.size} 个行为" + if (summary.isBlank()) "" else " · $summary",
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(workflowScheduleLabel(workflow), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            trailingContent = {
                                Switch(workflow.enabled, { updateWorkflow(workflow.copy(enabled = it)) },
                                    modifier = Modifier.semantics { contentDescription = "启用${workflow.title.ifBlank { "调用" }}" })
                            })
                    }
                }
            }
        }
        entry<CallEditorRoute.Details> { route -> callDrafts[route.id]?.let { draft ->
            val call = draft.value
            CallDetailsEditor(draft.baseline, call, { callDrafts[route.id] = draft.copy(value = it) }, actions, profiles, outputKeysForNewNodes(library, item.workflows + call),
                { changed -> referenceChoices(item.copy(workflows = mergeWorkflow(changed))) },
                { item = item.copy(workflows = mergeWorkflow(it)); pop() }, ::pop, ::requestPrompt, { item = item.copy(workflows = item.workflows.filterNot { it.id == call.id }); pop() }, { backs[route] = it },
                siblings = item.workflows, tags = library?.promptTags.orEmpty(), triggerTargets = completionSources())
        } }
        entry<CallEditorRoute.Prompt> { route -> promptRequest?.let { request ->
            val workflowDraft = if (request.workflowDraft) request.location?.workflowId?.let { callDrafts[it] } else null
            val promptContent: @Composable () -> Unit = {
                PromptBlocksEditor(request.blocks, request.references, { changedBlocks ->
                    val location = request.location
                    val currentDraft = if (request.workflowDraft) location?.workflowId?.let { callDrafts[it] } else null
                    val current = currentDraft?.value ?: item.workflows.firstOrNull { it.id == location?.workflowId }
                    if (current != null && location?.nodeId != null) {
                        val changed = current.copy(nodes = current.nodes.map { if (it.id == location.nodeId) it.copy(blocks = changedBlocks) else it })
                        if (currentDraft != null) callDrafts[current.id] = currentDraft.copy(value = changed) else updateWorkflow(changed)
                    } else request.save(changedBlocks)
                    pop()
                }, ::pop,
                    { backs[route] = it }, tags = library?.promptTags.orEmpty(), owner = request.location)
            }
            if (workflowDraft != null) WorkflowSourceBoundary(workflowDraft.value,
                { changed -> callDrafts[changed.id] = workflowDraft.copy(value = changed) }, promptContent)
            else promptContent()
        } }
        entry<CallEditorRoute.Cards> { route ->
            val original = remember { item.cards }
            var cards by remember { mutableStateOf(item.cards) }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(groups = base.groups.map { if (it.id == item.id) it.copy(cards = cards) else it }) },
                restore = { candidate, parent -> candidate.copy(groups = candidate.groups.map { if (it.id == item.id) it.copy(cards = parent.groups.first { old -> old.id == item.id }.cards) else it }) },
                accept = { candidate -> candidate.groups.firstOrNull { it.id == item.id }?.let { cards = it.cards } }) {
                CallEditorPage("卡片", cards != original, ::pop, { item = item.copy(cards = cards); pop() }, onRegisterBack = { backs[route] = it }) {
                    CardResourcesEditor(cards, referenceChoices()) { cards = it }
                }
            }
        }
        entry<CallEditorRoute.Bindings> { route ->
            val baseline = remember { item.boundPackageIds }
            var selected by remember { mutableStateOf(item.boundPackageIds) }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(groups = base.groups.map { if (it.id == item.id) it.copy(boundPackageIds = selected) else it }) },
                restore = { candidate, parent -> candidate.copy(groups = candidate.groups.map { if (it.id == item.id) it.copy(boundPackageIds = parent.groups.first { old -> old.id == item.id }.boundPackageIds) else it }) },
                accept = { candidate -> candidate.groups.firstOrNull { it.id == item.id }?.let { selected = it.boundPackageIds } }) {
            CallEditorPage("关联提示词包", selected != baseline, ::pop, { item = item.copy(boundPackageIds = selected); pop() }, onRegisterBack = { backs[route] = it }) {
                ResourceBindingList(packages.map { it.id to it.name.ifBlank { it.namespace } }, selected) { selected = it }
            }
            }
        }
        entry<CallEditorRoute.Info> { route ->
            val baseline = remember { item }
            var description by remember { mutableStateOf(item.description) }
            var state by remember { mutableStateOf(item.stateDefaults) }
            SourceDraftBoundary(sourceResource,
                overlay = { base -> base.copy(groups = base.groups.map { if (it.id == item.id) it.copy(description = description, stateDefaults = state) else it }) },
                restore = { candidate, parent ->
                    val old = parent.groups.first { it.id == item.id }
                    candidate.copy(groups = candidate.groups.map { if (it.id == item.id) it.copy(description = old.description, stateDefaults = old.stateDefaults) else it })
                }, accept = { candidate -> candidate.groups.firstOrNull { it.id == item.id }?.let { description = it.description; state = it.stateDefaults } }) {
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
        }
    })
    }
    if (rename) NamingDialog("调用组名称", item.name, item.slug,
        library?.groups.orEmpty().filterNot { it.id == item.id }.map { it.name },
        library?.groups.orEmpty().filterNot { it.id == item.id }.map { it.slug },
        { rename = false }) { name, slug -> item = item.copy(name = name, slug = slug); rename = false }
}
