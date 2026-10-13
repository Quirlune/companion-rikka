package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

/** Presentation metadata only: insertion and activation still belong to the shared compiler. */
internal data class PromptReferenceSource(
    val id: String,
    val title: String,
    val detail: String = "",
    val inMenu: Boolean = true,
    val menuTitle: String = title,
    val location: ResourceLocation? = null,
)

internal data class PromptReferenceChoice(
    val title: String, val macro: String, val section: String, val detail: String = "",
    val producerNodeId: String? = null,
    val sourceId: String = section,
    val sourceTitle: String = section,
    val sourceDetail: String = "",
    val owners: List<PromptReferenceSource> = emptyList(),
    val locations: List<ResourceLocation> = owners.mapNotNull { it.location }.let { all ->
        all.filter { candidate -> all.none { other -> other != candidate && other.resource == candidate.resource &&
            (candidate.workflowId == null || candidate.workflowId == other.workflowId) &&
            ((candidate.workflowId == null && other.workflowId != null) || (candidate.nodeId == null && other.nodeId != null)) } } }.distinct(),
    val origin: String = owners.joinToString(" · ") { it.title }.ifEmpty { sourceTitle },
)

internal fun promptReferenceChoices(
    workflows: List<Workflow>,
    library: Library?,
    mainCall: MainCall? = null,
    callGroup: CallGroup? = null,
): List<PromptReferenceChoice> = buildList {
    val session = library?.sessions?.firstOrNull { it.id == library.selectedSessionId }
    val card = library?.characters?.firstOrNull { it.id == session?.characterId }
    val persona = if (session != null) library?.effectivePersona(session)
        else library?.personas?.firstOrNull { it.id == library.selectedPersonaId }
    val personaName = persona?.name ?: library?.user?.name ?: "对话用户"
    val personaOwner = PromptReferenceSource("persona:${persona?.id ?: "legacy"}", personaName.ifBlank { "未命名身份" },
        when {
            persona != null && session != null -> "本段对话选定的用户身份"
            persona != null -> "新对话默认用户身份"
            session?.personaId != null -> "所选身份已不可用；名称回退到旧版用户资料，身份设定为空"
            else -> "旧版用户资料；未指定身份时保留原昵称，身份设定为空"
        })
    val selectedMain = mainCall ?: library?.mainCalls?.firstOrNull { it.id == session?.mainCallId }
    // A different main-resource draft must not inherit the selected session's main ID.
    val referenceSession = (session ?: Session(id = "", characterId = "", updatedAt = 0L)).copy(
        mainCallId = selectedMain?.id.orEmpty(),
        groupIds = (session?.groupIds.orEmpty() + listOfNotNull(callGroup?.id)).distinct(),
    )
    val referenceLibrary = library?.copy(
        mainCalls = library.mainCalls.filterNot { it.id == mainCall?.id } + listOfNotNull(mainCall),
        groups = library.groups.filterNot { it.id == callGroup?.id } + listOfNotNull(callGroup),
    )
    val groups = referenceLibrary?.loadedCallGroups(referenceSession, selectedMain).orEmpty()
    val packages = referenceLibrary?.loadedPromptPackages(referenceSession, selectedMain).orEmpty()
    val loadedPackageIds = packages.map { it.id }.toSet()
    val currentVersions = if (library != null && session != null) MacroReferences.currentVersions(library, session) else emptyMap()
    val emittedDynamicAddresses = mutableSetOf<String>()
    val characterOwner = PromptReferenceSource("character:${card?.id ?: "selected"}", card?.name?.ifBlank { "未命名角色" } ?: "聊天中选定的角色",
        if (card == null) "尚未选择角色，发送时需有角色来源" else "当前聊天角色",
        location = card?.let { ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.CHARACTER, it.id)) })
    val mainOwner = selectedMain?.let { PromptReferenceSource("main:${it.id}", it.name.ifBlank { "未命名主调用" },
        if (mainCall != null) "编辑中的主调用配置" else "当前聊天主调用",
        location = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id))) }
    fun choice(
        title: String, macro: String, sourceId: String, sourceTitle: String, purpose: String,
        owners: List<PromptReferenceSource> = emptyList(), nodeId: String? = null, sourceDetail: String = "",
        locations: List<ResourceLocation> = owners.mapNotNull { it.location }.let { all ->
        all.filter { candidate -> all.none { other -> other != candidate && other.resource == candidate.resource &&
            (candidate.workflowId == null || candidate.workflowId == other.workflowId) &&
            ((candidate.workflowId == null && other.workflowId != null) || (candidate.nodeId == null && other.nodeId != null)) } } }.distinct(),
    ) = PromptReferenceChoice(title, macro, sourceTitle, purpose, nodeId, sourceId, sourceTitle, sourceDetail, owners, locations)
    add(choice("本次用户消息", "{{input}}", "builtin", "内置资料", "本次发送的用户输入"))
    add(choice("用户名称", "{{user.name}}", "users", "用户身份", "读取本段对话选定用户身份的名称", listOf(personaOwner)))
    add(choice("用户身份设定", "{{user.prompt}}", "users", "用户身份", "读取身份设定文本；只有被提示词引用时才会发送，未填写时为空", listOf(personaOwner)))
    add(choice("角色名称", "{{character.name}}", "characters", "角色资料", "聊天角色的显示名称", listOf(characterOwner), locations = listOfNotNull(characterOwner.location?.copy(section = "name"))))
    val historyOwners = listOfNotNull(mainOwner?.copy(location = mainOwner.location?.copy(section = "history")))
    add(choice("结构化历史", "{{history}}", "history", "聊天历史", "按主调用的历史规则插入消息", historyOwners))
    add(choice("原始历史文本", "{{history.raw}}", "history", "聊天历史", "将原始 BODY 楼层展开为带发言者前缀的一段文本", historyOwners))
    add(choice("最终展示正文历史", "{{history.display}}", "history", "聊天历史", "读取最终语义 BODY 正文；与用户看到的正文一致", historyOwners))
    add(choice("模型原文历史", "{{history.model}}", "history", "聊天历史", "读取已归档的初始模型原文；旧楼层若未留存原文会报告缺失", historyOwners))
    add(choice("历史纯文本", "{{history.text}}", "history", "聊天历史", "将处理后的历史展开为带发言者前缀的一段文本；标签不是 API 角色", historyOwners))
    add(choice("历史楼层数量", "{{history.count}}", "history", "聊天历史", "投影后的历史消息数量", historyOwners))
    add(choice("历史摘要与记忆标识", "{{history.summary}}", "history", "聊天历史", "历史处理成功生成的摘要和可供普通调用选择的记忆段标识；未生成时为空", historyOwners))
    add(choice("展示正文记忆标识", "{{history.display.summary}}", "history", "聊天历史", "最终展示正文来源的有效摘要与记忆段标识", historyOwners))
    add(choice("模型原文记忆标识", "{{history.model.summary}}", "history", "聊天历史", "模型初始原文来源的有效摘要与记忆段标识", historyOwners))
    add(choice("完整原始结构化历史", "{{type:builtin.in:history.name:messages}}", "history", "聊天历史", "原始 BODY 楼层；整块插入时保留角色与消息顺序", historyOwners))
    add(choice("完整模型原文结构化历史", "{{type:builtin.in:history.name:messages.source:model}}", "history", "聊天历史", "读取已归档的初始模型原文；整块插入时保留角色与消息顺序", historyOwners))
    add(choice("完整处理后结构化历史", "{{type:builtin.in:history.name:context}}", "history", "聊天历史", "按主调用历史规则与记忆摘要投影；整块插入时保留消息角色", historyOwners))
    add(choice("最早三层原文", "{{type:builtin.in:history.name:messages.from:start-1.through:start-3}}", "history", "聊天历史", "按最早三条 BODY 楼层选取", historyOwners))
    add(choice("最近三层原文", "{{type:builtin.in:history.name:messages.from:end-3.through:end-1}}", "history", "聊天历史", "按最近三条 BODY 楼层选取", historyOwners))
    add(choice("最近三条原始助手消息", "{{type:builtin.in:history.name:messages.speaker:assistant.from:end-3.through:end-1}}", "history", "聊天历史", "先筛原 Assistant，再选最近三条；API 发送角色可独立调整", historyOwners))
    add(choice("最近一条原始用户消息", "{{type:builtin.in:history.name:messages.speaker:user.from:end-1.through:end-1}}", "history", "聊天历史", "读取最新的原 User 楼层，保留来源身份", historyOwners))
    add(choice("中间区段原文", "{{type:builtin.in:history.name:messages.from:percent-34.through:percent-66}}", "history", "聊天历史", "按历史楼层位置的百分比选取", historyOwners))
    session?.historyMemory?.views.orEmpty().forEach { (viewId, revisions) ->
        revisions.lastOrNull()?.let { latest ->
            val source = if (latest.sourceKind == HistorySourceKind.MODEL_RAW) ".source:model" else ""
            val sourceTitle = if (latest.sourceKind == HistorySourceKind.MODEL_RAW) "模型初始原文" else "最终展示正文"
            add(choice("历史副本 · ${latest.name}", "{{type:builtin.in:history.name:context$source.view:$viewId}}", "history", "聊天历史",
                "副本第 ${latest.revision} 版 · $sourceTitle · 结构化历史，可再指定范围；原聊天楼层保持独立", historyOwners))
            add(choice("历史副本 · ${latest.name} · 第 ${latest.revision} 版", "{{type:builtin.in:history.name:context$source.view:$viewId.revision:${latest.revision}}}", "history", "聊天历史",
                "固定读取第 ${latest.revision} 版 · $sourceTitle", historyOwners))
        }
    }
    val entries = card?.let(CharacterFields::normalize)?.entries.orEmpty()
    if (card == null) CharacterFields.definitions.forEach { field ->
        add(choice(field.title, "{{character.${field.key}}}", "characters", "角色资料", "读取所选角色的${field.title}", listOf(characterOwner)))
    }
    entries.forEach { entry ->
        val title = entry.title.ifBlank { CharacterFields.definitions.firstOrNull { it.key == entry.key }?.title ?: entry.key }
        add(choice(title, "{{character.${entry.key}}}", "characters", "角色资料",
            entryReferencePurpose(entry, library?.promptTags.orEmpty()), listOf(characterOwner),
            locations = listOf(ResourceLocations.fromField(ExtensionFieldRef(ExtensionResourceKind.CHARACTER, card!!.id, ExtensionField.ENTRY_TEXT, entryId = entry.id)))) )
    }
    val allPackages = (packages + library?.packages.orEmpty()).distinctBy { it.id }
    allPackages.forEach { pack ->
        val loaded = pack.id in loadedPackageIds
        val owner = PromptReferenceSource("pack:${pack.id}", "${pack.name.ifBlank { "未命名提示词包" }} · ${pack.namespace}",
            if (loaded) "当前聊天或编辑配置中的绑定来源；词条仍受开关与条件控制" else "未加载；需先绑定或在聊天中加载",
            location = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.PACKAGE, pack.id)))
        pack.entries.forEach { entry ->
            add(choice(entry.title.ifBlank { entry.key }, "{{pack.${pack.namespace}.${entry.key}}}", "packages", "提示词包",
                (if (loaded) "" else "未加载时引用为空 · ") + entryReferencePurpose(entry, library?.promptTags.orEmpty()), listOf(owner),
                locations = listOf(ResourceLocations.fromField(ExtensionFieldRef(ExtensionResourceKind.PACKAGE, pack.id, ExtensionField.ENTRY_TEXT, entryId = entry.id)))))
        }
    }
    (entries + packages.flatMap { it.entries }).map { it.category }.filter { it.isNotBlank() }.distinct().forEach { category ->
        val origins = buildList {
            if (entries.any { it.category == category }) add(characterOwner.title)
            packages.filter { pack -> pack.entries.any { it.category == category } }.forEach { add(it.name.ifBlank { it.namespace }) }
        }.joinToString("、")
        add(choice(category, "{{category.$category}}", "entry_categories", "资料分类",
            "合并已加载、启用且符合条件的同类词条 · 配置来源：$origins"))
    }
    val callOwners = linkedMapOf<Pair<String, String>, Pair<PromptReferenceSource, Workflow>>()
    fun collect(owner: PromptReferenceSource, calls: List<Workflow>) {
        calls.forEach { callOwners[owner.id to it.id] = owner to it }
    }
    if (callGroup != null) {
        val groupOwner = PromptReferenceSource("group:${callGroup.id}", callGroup.name.ifBlank { "未命名调用组" }, "编辑中的调用组配置", location = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, callGroup.id)))
        selectedMain?.let { collect(mainOwner!!, it.configuredWorkflows()) }
        collect(groupOwner, workflows)
    } else if (mainOwner != null) {
        collect(mainOwner, workflows)
    } else if (workflows.isNotEmpty()) {
        collect(PromptReferenceSource("editor", "当前编辑调用", "编辑中的配置"), workflows)
    }
    groups.filterNot { it.id == callGroup?.id }.forEach { group ->
        collect(PromptReferenceSource("group:${group.id}", group.name.ifBlank { "未命名调用组" }, "聊天或主调用绑定的调用组", location = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id))), group.workflows)
    }
    selectedMain?.let { collect(mainOwner!!, it.configuredWorkflows()) }
    callOwners.values.forEach { (owner, call) ->
        val callTitle = call.title.ifBlank { "未命名调用" }
        val callOwner = PromptReferenceSource("call:${owner.id}:${call.id}", callTitle,
            "${owner.detail} · ${call.afterWorkflowId?.let { "指定调用完成后（${it.take(8)}）" } ?: callPhaseLabel(call.trigger)} · ${callScheduleLabel(call)}", menuTitle = "${owner.title} · $callTitle", location = owner.location?.copy(workflowId = call.id))
        call.nodes.forEach { node ->
            val nodeTitle = node.title.ifBlank { "未命名节点" }
            val ownerType = owner.id.substringBefore(':')
            val ownerId = owner.id.substringAfter(':')
            val resourceSlug = when (ownerType) {
                "main" -> selectedMain?.takeIf { it.id == ownerId }?.slug ?: library?.mainCalls?.firstOrNull { it.id == ownerId }?.slug
                "group" -> callGroup?.takeIf { it.id == ownerId }?.slug ?: library?.groups?.firstOrNull { it.id == ownerId }?.slug
                else -> null
            }
            val slugMacro = resourceSlug?.takeIf { MacroNaming.isValidSlug(it) && MacroNaming.isValidSlug(call.slug) && MacroNaming.isValidSlug(node.slug) }
                ?.let { "{{type:$ownerType.in:$it.call:${call.slug}.name:${node.slug}}}" }
            val owners = listOf(owner.copy(inMenu = false), callOwner,
                PromptReferenceSource("node:${node.id}", nodeTitle, nodeActionPurpose(node), inMenu = false,
                    location = owner.location?.copy(workflowId = call.id, nodeId = node.id)))
            val inactive = if (!call.enabled || !node.enabled) " · 配置已停用" else ""
            add(choice("$nodeTitle · 本轮结果", slugMacro ?: "{{result.${node.outputKey}}}", "calls", "调用结果",
                "${nodeActionPurpose(node)} · 本轮该节点成功完成后可用$inactive", owners, node.id,
                "调用自动发布结果宏，无需放入提示词包"))
            node.regexOutputCopy.takeIf { it.enabled && it.outputKey.isNotBlank() }?.let { copy ->
                add(choice("$nodeTitle · 正则副本", "{{result.${copy.outputKey}}}", "calls", "调用结果",
                    "由此节点的结果处理成的独立副本$inactive", owners, node.id,
                    "原输出 result.${node.outputKey} 仍可单独引用"))
                add(choice("$nodeTitle · 正则副本最新版", "{{macro.${copy.outputKey}}}", "calls", "调用结果",
                    "副本成功发布后可读取最新保存版本$inactive", owners, node.id))
                val publishedCopy = currentVersions["result.${copy.outputKey}"]
                if (publishedCopy?.origin?.let { it.resourceId == owner.id.substringAfter(':', "") && it.workflowId == call.id && it.nodeId == node.id } == true)
                    emittedDynamicAddresses.add("result.${copy.outputKey}")
            }
            val address = "result.${node.outputKey}"
            val current = currentVersions[address]
            val currentOwner = owner.id.substringAfter(':', "")
            val publishedHere = current?.origin?.let { it.resourceId == currentOwner && it.workflowId == call.id && it.nodeId == node.id } == true
            val availability = when {
                publishedHere && current?.enabled == true -> "当前聊天已发布最新版；可选历史版本"
                publishedHere -> "当前聊天已发布版本已停用；可选历史版本"
                else -> "成功发布后可读取保存的最新版；当前目录不表示本轮已完成"
            }
            add(choice("$nodeTitle · 动态宏最新版", "{{macro.${node.outputKey}}}", "calls", "调用结果",
                "$availability$inactive", owners, node.id, "调用自动发布结果宏，无需放入提示词包"))
            if (node.actionId == "macro.process") {
                val target = node.macroProcess.key.ifBlank { node.arguments["key"].orEmpty() }.trim()
                if (target.startsWith("macro.") || target.startsWith("state.")) {
                    add(choice("$nodeTitle · 处理目标", "{{$target}}", "calls", "配置的宏目标",
                        "目标地址：$target · 保存配置后可引用；成功执行后才有本轮新值$inactive", owners, node.id))
                }
            }
            if (publishedHere) emittedDynamicAddresses.add(address)
        }
    }
    callOwners.values.flatMap { (_, call) -> call.nodes.map { call to it } }.groupBy { it.second.resultCategory }
        .filterKeys { it.isNotBlank() }.forEach { (category, producers) ->
            val phases = producers.map { callPhaseLabel(it.first.trigger) }.distinct().joinToString("、")
            add(choice(category, "{{results.$category}}", "result_categories", "结果分类",
                "${producers.size} 项配置 · $phases · 合并已完成且来源仍可见的同类结果", nodeId = producers.singleOrNull()?.second?.id))
        }
    (session?.state?.keys.orEmpty() + callGroup?.stateDefaults?.keys.orEmpty()).distinct().forEach { key ->
        add(choice(key, "{{state.$key}}", "state", "聊天状态",
            if (key in session?.state.orEmpty()) "读取当前聊天保存的状态值" else "调用组声明的初始状态；加载后才会提供"))
    }
    currentVersions.filterKeys { it !in emittedDynamicAddresses }.forEach { (address, current) ->
        val alias = if (address.startsWith("result.")) "macro.${address.removePrefix("result.")}" else address
        val resourceName = library?.mainCalls?.firstOrNull { it.id == current.origin.resourceId }?.name
            ?: library?.groups?.firstOrNull { it.id == current.origin.resourceId }?.name
            ?: library?.characters?.firstOrNull { it.id == current.origin.resourceId }?.name
            ?: library?.packages?.firstOrNull { it.id == current.origin.resourceId }?.name
            ?: "已发布来源"
        val owner = PromptReferenceSource("published:${current.origin.resourceId}", resourceName.ifBlank { "未命名来源" }, "当前聊天可见的已发布版本")
        add(choice(address, "{{$alias}}", "published", "其他已发布宏",
            if (current.enabled) "已发布最新版；可选择历史版本" else "当前版本已停用；可选择历史版本", listOf(owner)))
    }
}

private fun entryReferencePurpose(entry: PromptEntry, catalog: List<PromptTag>): String = buildList {
    add("读取词条文本")
    if (entry.slug.isNotBlank()) add("英文：${entry.slug}")
    if (entry.tagIds.isNotEmpty()) add("标签：${entry.tagIds.map { id -> catalog.firstOrNull { it.id == id }?.let { "${it.name} / ${it.slug}" } ?: "未找到：$id" }.joinToString("、")}")
    if (entry.category.isNotBlank()) add("分类：${entry.category}")
    if (!entry.enabled) add("词条已停用")
    if (entry.condition != null) add("满足识别条件时提供")
}.joinToString(" · ")

private fun nodeActionPurpose(node: WorkflowNode): String = when (node.actionId) {
    "model.generate" -> if (node.visible) "模型生成的聊天输出" else "模型生成的内部资料"
    "text.render" -> "宏与模板合成的文本"
    "state.set" -> "状态写入动作的结果"
    "state.increment" -> "状态数值更新的结果"
    "event.emit" -> "事件触发动作的结果"
    "macro.write" -> "宏写入动作的结果"
    "macro.process" -> "宏处理动作的结果"
    "text.extract" -> "正则提取的独立文本副本"
    "history.memory.write" -> "来源可追溯的历史记忆"
    "history.view.copy" -> "结构化历史副本版本"
    "prompt.inject", "prompt.remove" -> "提示词管理动作的结果"
    else -> "${node.actionId} 动作的输出"
}

@Composable
internal fun PromptReferencePicker(choices: List<PromptReferenceChoice>, onInsert: (String) -> Unit, versioned: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }) { Text("选取资料与结果") }
    if (open) PromptReferenceSheet(choices, onInsert, { open = false }, versioned)
}

@Composable
internal fun PromptReferenceSheet(
    choices: List<PromptReferenceChoice>,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    versioned: Boolean = true,
    includeMacroHelp: Boolean = false,
    onConfigureHistory: (() -> Unit)? = null,
) {
    val sourceNavigation = LocalSourceNavigation.current
    var selectedSource by remember { mutableStateOf<PromptReferenceChoice?>(null) }
    var search by remember { mutableStateOf("") }
    var versionMode by remember { mutableStateOf("latest") }
    var versionNumber by remember { mutableStateOf("1") }
    var formatsExpanded by remember { mutableStateOf(false) }
    val sections = remember(choices, search, versionMode, versioned) {
        val query = search.trim()
        choices.filter { reference ->
            (versionMode == "latest" || !versioned || isVersionedMacro(reference.macro)) &&
                (query.isBlank() || (listOf(reference.section, reference.title, reference.detail, reference.macro, reference.origin) +
                    reference.owners.flatMap { listOf(it.title, it.detail) })
                    .any { it.contains(query, true) })
        }.groupBy { it.section }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("插入引用", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(search, { search = it }, label = { Text("搜索资料、调用或分类") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large)
            }
            if (choices.any { it.macro.startsWith("{{type:builtin.in:history.name:messages") || it.macro.startsWith("{{type:builtin.in:history.name:context") }) {
                onConfigureHistory?.let { configure -> item {
                    TextButton(onClick = { configure(); onDismiss() }) { Text("历史上下文…") }
                } }
                item { HistoryReferenceComposer(choices, { onInsert(it); onDismiss() }) }
            }
            if (versioned) {
                item {
                    ChoiceLine("版本", versionMode, listOf(
                        "latest" to "最新", "turn" to "前 N 轮", "update" to "前 N 个记录点", "version" to "指定版本号"
                    )) { versionMode = it }
                }
                if (versionMode != "latest") item {
                    OutlinedTextField(
                        versionNumber, { value -> if (value.isEmpty() || (value.all(Char::isDigit) && value.toIntOrNull() != null)) versionNumber = value },
                        label = { Text(if (versionMode == "version") "版本号" else "N") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.large,
                    )
                }
                item { Text("记录点只计算勾选了轨迹的更新；关闭轨迹期间的旧轮次可能无法按历史引用。找不到所选版本时，引用结果为空。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (sections.isEmpty()) item { Text("没有匹配的引用。") }
            sections.forEach { (section, references) ->
                item { Text(section, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items(references) { reference ->
                    TextButton(
                        onClick = {
                            onInsert(if (versioned) withSelectedVersion(reference.macro, versionMode, versionNumber.toIntOrNull()) else reference.macro)
                            onDismiss()
                        },
                        enabled = !versioned || versionMode == "latest" || (versionNumber.toIntOrNull() ?: 0) > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(reference.title, style = MaterialTheme.typography.bodyMedium)
                            if (sourceNavigation != null && reference.locations.isNotEmpty()) TextButton(onClick = { selectedSource = reference }, contentPadding = PaddingValues(0.dp)) {
                                Text(reference.origin, style = MaterialTheme.typography.labelSmall)
                            } else Text(reference.origin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (reference.detail.isNotEmpty()) Text(reference.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(reference.macro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (includeMacroHelp) {
                item { TextButton(onClick = { formatsExpanded = !formatsExpanded }) { Text(if (formatsExpanded) "收起其他宏格式" else "其他宏格式") } }
                if (formatsExpanded) item {
                    MacroHelp { macro -> onInsert(macro); onDismiss() }
                }
            }
            item { TextButton(onClick = onDismiss) { Text("关闭") } }
        }
    }
    selectedSource?.let { reference ->
        AlertDialog(onDismissRequest = { selectedSource = null }, title = { Text("实际来源") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                reference.locations.forEach { location ->
                    TextButton(onClick = { selectedSource = null; onDismiss(); sourceNavigation?.edit(location) }) {
                        Text(sourceNavigation?.library?.let { sourceLocationTitle(it, location) } ?: reference.origin)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { selectedSource = null }) { Text("关闭") } })
    }
}

@Composable
private fun HistoryReferenceComposer(choices: List<PromptReferenceChoice>, onInsert: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var processed by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("display") }
    var sourceRole by remember { mutableStateOf<Role?>(null) }
    var range by remember { mutableStateOf(true) }
    var fromAnchor by remember { mutableStateOf("end") }
    var fromNumber by remember { mutableStateOf("3") }
    var throughAnchor by remember { mutableStateOf("end") }
    var throughNumber by remember { mutableStateOf("1") }
    var viewId by remember { mutableStateOf<String?>(null) }
    var fixedRevision by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf("1") }
    val viewPattern = Regex("\\.view:([a-z][a-z0-9_-]*)")
    val views = choices.filter { (".source:model" in it.macro) == (source == "model") }
        .mapNotNull { choice -> viewPattern.find(choice.macro)?.groupValues?.getOrNull(1)?.let { it to choice.title.substringBefore(" · 第") } }
        .distinctBy { it.first }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起历史范围选择" else "自选历史范围与副本") }
    if (!open) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoiceLine("读取内容", processed, listOf(false to "原始楼层", true to "历史规则与摘要处理后")) { processed = it }
        ChoiceLine("正文来源", source, listOf("display" to "最终展示正文", "model" to "模型初始原文")) {
            source = it; viewId = null; fixedRevision = false
        }
        if (source == "model") Text("早期楼层若没有归档模型原文，会明确提示缺失。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceLine("来源发言方", sourceRole, historySourceRoleChoices) { sourceRole = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SuggestionChip(onClick = { range = true; fromAnchor = "start"; fromNumber = "1"; throughAnchor = "start"; throughNumber = "3" }, label = { Text("最早三层") })
            SuggestionChip(onClick = { range = true; fromAnchor = "end"; fromNumber = "3"; throughAnchor = "end"; throughNumber = "1" }, label = { Text("最近三层") })
            SuggestionChip(onClick = { range = true; fromAnchor = "percent"; fromNumber = "34"; throughAnchor = "percent"; throughNumber = "66" }, label = { Text("中间区段") })
        }
        ToggleLine("指定范围", range) { range = it }
        if (range) {
            val anchors = listOf("start" to "从开头", "end" to "从末尾", "percent" to "百分比")
            ChoiceLine("起点", fromAnchor, anchors) { fromAnchor = it }
            OutlinedTextField(fromNumber, { fromNumber = it.filter(Char::isDigit) }, label = { Text(if (fromAnchor == "percent") "起点百分比（0–100）" else if (sourceRole != null) "匹配来源起点（从 1 开始）" else "起点楼层（从 1 开始）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            ChoiceLine("终点（包含）", throughAnchor, anchors) { throughAnchor = it }
            OutlinedTextField(throughNumber, { throughNumber = it.filter(Char::isDigit) }, label = { Text(if (throughAnchor == "percent") "终点百分比（0–100）" else if (sourceRole != null) "匹配来源终点（从 1 开始）" else "终点楼层（从 1 开始）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (views.isNotEmpty()) {
            ChoiceLine("历史副本", viewId, listOf<Pair<String?, String>>(null to "当前历史") + views.map { (id, title) -> (id as String?) to title }) { viewId = it; fixedRevision = false }
            if (viewId != null) {
                ToggleLine("固定副本版本", fixedRevision) { fixedRevision = it }
                if (fixedRevision) OutlinedTextField(revision, { revision = it.filter(Char::isDigit) }, label = { Text("版本号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        }
        fun valid(anchor: String, number: String): Boolean = number.toIntOrNull()?.let { if (anchor == "percent") it in 0..100 else it > 0 } == true
        val okay = (!range || (valid(fromAnchor, fromNumber) && valid(throughAnchor, throughNumber))) && (!fixedRevision || viewId == null || (revision.toIntOrNull() ?: 0) > 0)
        val expression = buildString {
            append("{{type:builtin.in:history.name:${if (processed) "context" else "messages"}")
            append(".source:$source")
            sourceRole?.let { append(".speaker:${it.name.lowercase()}") }
            if (range) append(".from:$fromAnchor-$fromNumber.through:$throughAnchor-$throughNumber")
            viewId?.let { append(".view:$it") }
            if (viewId != null && fixedRevision) append(".revision:$revision")
            append("}}")
        }
        Text(expression, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        OutlinedButton(onClick = { onInsert(expression) }, enabled = okay) { Text("插入历史引用") }
    }
}
private fun withSelectedVersion(macro: String, mode: String, number: Int?): String {
    if (mode == "latest") return macro
    val n = number ?: return macro
    val body = macro.removePrefix("{{").removeSuffix("}}")
    val address = body.substringBefore('#')
    val selector = when (mode) {
        "turn" -> "turn-$n"
        "update" -> "update-$n"
        "version" -> "version-$n"
        else -> "latest"
    }
    val xml = body.substringAfter('#', "")
    return "{{$address@$selector${if (xml.isNotEmpty()) "#$xml" else ""}}}"
}

private fun isVersionedMacro(macro: String): Boolean {
    val body = macro.removePrefix("{{").removeSuffix("}}")
    return listOf("character.", "pack.", "macro.", "result.").any(body::startsWith)
}

internal fun callPhaseLabel(trigger: String): String = when (trigger) {
    RuntimeEvents.TURN_PREPARE -> "发送前准备"
    RuntimeEvents.USER_MESSAGE -> "用户消息后"
    RuntimeEvents.REPLY_COMPLETED -> "正文完成后"
    else -> "自定义事件：$trigger"
}

private fun callScheduleLabel(workflow: Workflow): String =
    if (workflow.queued) "排队级别 ${workflow.priority}" else "并行 · 级别 0"

@Composable
internal fun CallScheduleOverview(workflows: List<Workflow>, primaryId: String?, selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(workflows.size > 1) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起调用顺序" else "查看调用顺序") }
    if (expanded) OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val phases = listOf(RuntimeEvents.TURN_PREPARE to "① 发送前准备", RuntimeEvents.USER_MESSAGE to "② 用户消息后的调用", RuntimeEvents.REPLY_COMPLETED to "③ 正文完成后")
            phases.forEach { (trigger, title) ->
                Text(title, style = MaterialTheme.typography.titleSmall)
                val calls = workflows.filter { it.afterWorkflowId == null && it.trigger == trigger }
                if (calls.isEmpty()) Text("暂无调用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                calls.forEach { call -> ScheduleCallRow(call, primaryId, selectedId, onSelect) }
            }
            val completed = workflows.filter { it.afterWorkflowId != null }
            if (completed.isNotEmpty()) {
                Text("指定调用完成后的调用", style = MaterialTheme.typography.titleSmall)
                completed.forEach { call -> ScheduleCallRow(call, primaryId, selectedId, onSelect) }
            }
            val custom = workflows.filter { call -> call.afterWorkflowId == null && phases.none { it.first == call.trigger } }
            if (custom.isNotEmpty()) {
                Text("自定义事件触发", style = MaterialTheme.typography.titleSmall)
                custom.forEach { call -> ScheduleCallRow(call, primaryId, selectedId, onSelect) }
            }
        }
    }
}

@Composable
private fun ScheduleCallRow(call: Workflow, primaryId: String?, selectedId: String?, onSelect: (String) -> Unit) {
    val annotations = listOfNotNull(
        callScheduleLabel(call),
        "默认正文".takeIf { call.id == primaryId }, "有识别条件".takeIf { call.condition != null || call.recognition != null },
        "已停用".takeIf { !call.enabled }, "当前编辑".takeIf { call.id == selectedId }
    ).joinToString(" · ")
    TextButton(onClick = { onSelect(call.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(call.title)
            if (annotations.isNotBlank()) Text(annotations, style = MaterialTheme.typography.bodySmall)
        }
    }


}
