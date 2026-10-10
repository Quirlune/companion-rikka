package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

/** Shared content operations. Empty lists leave content untouched. */
@Composable
internal fun TextProcessingEditor(
    title: String, steps: List<TextProcessStep>, references: List<PromptReferenceChoice>,
    allowedKinds: List<TextProcessKind> = processKindChoices.map { it.first },
    onChange: (List<TextProcessStep>) -> Unit,
) {
    var expanded by remember { mutableStateOf(steps.isNotEmpty()) }
    TextButton(onClick = { expanded = !expanded }) { Text("$title · ${steps.size} 步 ${if (expanded) "收起" else "展开"}") }
    if (!expanded) return
    Text("按顺序处理内容；正则在这里作用于当前文本。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    steps.forEachIndexed { index, step ->
        key(index) {
            val titleText = processKindChoices.firstOrNull { it.first == step.kind }?.second ?: step.kind.name
            EditorSection("${index + 1}. $titleText", "当前文本 → 下一步", initiallyExpanded = true) {
                if (step.kind !in allowedKinds) {
                    Text("旧版处理步骤已保留；此页只编辑正则步骤。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onChange(steps.filterIndexed { i, _ -> i != index }) }) {
                        Text("移除这项旧版处理")
                    }
                    return@EditorSection
                }
                ChoiceLine("处理", step.kind, processKindChoices.filter { it.first in allowedKinds }) { kind ->
                    val strict = if (kind == TextProcessKind.REGEX_EXTRACT && step.kind != TextProcessKind.REGEX_EXTRACT) true else step.requireMatch
                    onChange(steps.toMutableList().also { it[index] = step.copy(kind = kind, requireMatch = strict) })
                }
                fun update(changed: TextProcessStep) = onChange(steps.toMutableList().also { it[index] = changed })
                when (step.kind) {
                    TextProcessKind.REGEX_EXTRACT -> {
                        TextFieldLine("正则表达式", step.pattern, { update(step.copy(pattern = it)) })
                        SafePatterns.validate(step.pattern, step.group)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = step.requireMatch, onCheckedChange = { update(step.copy(requireMatch = it)) })
                            Text("匹配失败视为格式错误", style = MaterialTheme.typography.bodyMedium)
                        }
                        var group by remember(index, step.group) { mutableStateOf(step.group.toString()) }
                        OutlinedTextField(group, { value -> group = value; value.toIntOrNull()?.takeIf { it >= 0 }?.let { update(step.copy(group = it)) } }, label = { Text("捕获组 · 0 为完整匹配") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        if (group.toIntOrNull()?.let { it >= 0 } != true) Text("请输入非负整数；当前输入尚未应用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextProcessKind.REGEX_REPLACE -> {
                        TextFieldLine("正则表达式", step.pattern, { update(step.copy(pattern = it)) })
                        SafePatterns.validate(step.pattern)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        PromptTemplateField("替换内容", step.replacement, references, { update(step.copy(replacement = it)) }, minLines = 2)
                    }
                    TextProcessKind.TRIM -> Text("移除内容首尾空白。", style = MaterialTheme.typography.bodySmall)
                    TextProcessKind.APPEND, TextProcessKind.PREPEND, TextProcessKind.FORMAT -> {
                        PromptTemplateField(if (step.kind == TextProcessKind.FORMAT) "格式模板" else "添加文本", step.text, references, { update(step.copy(text = it)) }, minLines = 2)
                        if (step.kind == TextProcessKind.FORMAT) Text("{value} 代表当前文本；也可插入全局宏。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextProcessKind.INCREASE -> TextFieldLine("增减量（可为负数）", step.amount, { update(step.copy(amount = it)) })
                    TextProcessKind.CLAMP -> {
                        TextFieldLine("下限（可留空）", step.min, { update(step.copy(min = it)) })
                        TextFieldLine("上限（可留空）", step.max, { update(step.copy(max = it)) })
                    }
                    TextProcessKind.EXPRESSION -> {
                        PromptTemplateField("表达式", step.expression, references, { update(step.copy(expression = it)) }, minLines = 2, transformMacro = { token ->
                            val address = token.removeSurrounding("{{", "}}")
                                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                            "macro(\"$address\")"
                        })
                        ConditionExpressions.validate(step.expression)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text("当前文本可作为表达式输入。键入 {{ 插入 macro(\"地址\")；只读且不执行任意代码。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onChange(steps.toMutableList().also { it.add(index - 1, it.removeAt(index)) }) }, enabled = index > 0) { Text("前移") }
                    TextButton(onClick = { onChange(steps.toMutableList().also { it.add(index + 1, it.removeAt(index)) }) }, enabled = index < steps.lastIndex) { Text("后移") }
                    TextButton(onClick = { onChange(steps.filterIndexed { i, _ -> i != index }) }) { Text("删除") }
                }
            }
        }
    }
    TextProcessing.validate(steps, "processing").map { it.message }.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = { onChange(steps + TextProcessStep(kind = allowedKinds.firstOrNull() ?: TextProcessKind.TRIM)); expanded = true }, enabled = steps.size < 64 && allowedKinds.isNotEmpty()) { Text("添加处理步骤") }
}

private val processKindChoices = listOf(
    TextProcessKind.REGEX_EXTRACT to "正则提取", TextProcessKind.REGEX_REPLACE to "正则替换",
    TextProcessKind.TRIM to "修剪空白", TextProcessKind.APPEND to "追加文本",
    TextProcessKind.PREPEND to "前置文本", TextProcessKind.FORMAT to "格式化",
    TextProcessKind.INCREASE to "数值增减", TextProcessKind.CLAMP to "限制数值范围",
    TextProcessKind.EXPRESSION to "表达式",
)

@Composable
internal fun NodeModelOptionsEditor(node: WorkflowNode, profiles: List<ModelProfile>, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice>) {
    PromptTemplateField("输入模板", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
    TextProcessingEditor("输入内容处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
    SharedModelGenerationEditor(node.generation, node.reasoningParsing, profiles) { generation, parsing ->
        onChange(node.copy(generation = generation, reasoningParsing = parsing))
    }
    TextProcessingEditor("输出内容处理", node.outputProcessing, references) { onChange(node.copy(outputProcessing = it)) }
}

/** Model choice and native generation overrides shared by focused editors. */
@Composable
internal fun SharedModelGenerationEditor(
    options: ModelCallOptions, reasoningParsing: ReasoningParsingConfig?, profiles: List<ModelProfile>,
    modelLabel: String = "此节点模型",
    onChange: (ModelCallOptions, ReasoningParsingConfig?) -> Unit,
) {
    ChoiceLine(modelLabel, options.profileId, listOf<Pair<String?, String>>(null to "继承调用 / 对话模型") + profiles.map { it.id to it.name.ifBlank { it.model } } +
        if (options.profileId != null && profiles.none { it.id == options.profileId }) listOf(options.profileId to "未找到模型：${options.profileId}") else emptyList()) {
        onChange(options.copy(profileId = it), reasoningParsing)
    }
    EditorSection("生成参数", "温度、Top P、输出上限与推理级别 · 留空继承") {
        OptionalFloatLine("温度", options.temperature) { onChange(options.copy(temperature = it), reasoningParsing) }
        OptionalFloatLine("Top P", options.topP) { onChange(options.copy(topP = it), reasoningParsing) }
        OptionalIntLine("输出上限", options.maxTokens) { onChange(options.copy(maxTokens = it), reasoningParsing) }
        ChoiceLine("推理级别", options.reasoning, listOf<Pair<ModelReasoning?, String>>(null to "继承") + ModelReasoning.entries.map { it to it.name }) {
            onChange(options.copy(reasoning = it), reasoningParsing)
        }
        ToggleLine("覆盖全局思考解析", reasoningParsing != null) { enabled ->
            onChange(options, if (enabled) ReasoningParsingConfig() else null)
        }
        reasoningParsing?.let { parsing ->
            ReasoningParsingFields(parsing) { onChange(options, it) }
        }
    }
}

@Composable
private fun OptionalFloatLine(label: String, value: Float?, onChange: (Float?) -> Unit) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val invalid = draft.isNotBlank() && (draft.toFloatOrNull() == null || !draft.toFloat().isFinite())
    OutlinedTextField(draft, { text -> draft = text; if (text.isBlank()) onChange(null) else text.toFloatOrNull()?.takeIf { it.isFinite() }?.let(onChange) }, label = { Text("$label · 留空继承") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = invalid)
    if (invalid) Text("请输入有效数字；当前输入尚未应用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun OptionalIntLine(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var draft by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val invalid = draft.isNotBlank() && draft.toIntOrNull() == null
    OutlinedTextField(draft, { text -> draft = text; if (text.isBlank()) onChange(null) else text.toIntOrNull()?.let(onChange) }, label = { Text("$label · 留空继承") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = invalid)
    if (invalid) Text("请输入整数；当前输入尚未应用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun MacroProcessEditor(node: WorkflowNode, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice>) {
    val config = node.macroProcess
    val targetPrefixes = listOf("macro.", "state.", "character.", "pack.")
    val available = references.mapNotNull { ref -> ref.macro.removePrefix("{{").removeSuffix("}}").substringBefore('@').takeIf { address -> targetPrefixes.any(address::startsWith) }?.let { it to ref.title } }.distinctBy { it.first }
    val sourceChoices = references.filter { ref ->
        val address = ref.macro.removePrefix("{{").removeSuffix("}}")
        (targetPrefixes + "result.").any(address::startsWith)
    }
    var creation by remember(node.id) { mutableStateOf<MacroProcessMode?>(null) }
    var copySource by remember(node.id) { mutableStateOf(false) }
    val used = available.map { it.first }.toSet()
    var newName by remember(node.id, creation) { mutableStateOf(generateSequence(1) { it + 1 }.map { "new_$it" }.first { "macro.$it" !in used }) }
    ChoiceLine("处理目标", config.key, available + if (config.key !in available.map { it.first }) listOf(config.key to if (config.key.isBlank()) "请选择或新建宏" else "当前目标：${config.key}") else emptyList()) { onChange(node.copy(macroProcess = config.copy(key = it))) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { creation = MacroProcessMode.SET }) { Text("新建宏") }
        OutlinedButton(onClick = { creation = MacroProcessMode.COPY }) { Text("制作副本") }
    }
    EditorSection("高级目标地址", config.key.ifBlank { "macro. 或 state. 地址" }) {
        TextFieldLine("目标地址", config.key, { onChange(node.copy(macroProcess = config.copy(key = it))) })
        Text("现有状态可以直接选取；新宏使用 macro. 地址。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ChoiceLine("方式", config.mode, listOf(
        MacroProcessMode.SET to "设定内容", MacroProcessMode.UPDATE to "更新现有值",
        MacroProcessMode.COPY to "复制来源快照", MacroProcessMode.ENABLE to "启用宏", MacroProcessMode.DISABLE to "停用宏")) {
        onChange(node.copy(macroProcess = config.copy(mode = it)))
    }
    when (config.mode) {
        MacroProcessMode.COPY -> {
            TextFieldLine("来源宏及版本", config.source, { onChange(node.copy(macroProcess = config.copy(source = it))) })
            PromptReferencePicker(sourceChoices, { onChange(node.copy(macroProcess = config.copy(source = it))) })
            Text("复制选定版本当时的值，之后独立更新；指定版本缺失会报错。", style = MaterialTheme.typography.bodySmall)
        }
        MacroProcessMode.ENABLE, MacroProcessMode.DISABLE -> Text("只改变目标宏的可见状态。", style = MaterialTheme.typography.bodySmall)
        MacroProcessMode.SET -> {
            PromptTemplateField("设定内容", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
            TextProcessingEditor("设定内容处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
        }
        MacroProcessMode.UPDATE -> Unit
    }
    if (config.mode == MacroProcessMode.UPDATE) {
        PromptTemplateField("目标尚不存在时的初始内容", config.initial, references, { onChange(node.copy(macroProcess = config.copy(initial = it))) }, minLines = 2)
    }
    if (config.mode !in setOf(MacroProcessMode.ENABLE, MacroProcessMode.DISABLE)) {
        TextProcessingEditor("处理目标内容", config.steps, references) { onChange(node.copy(macroProcess = config.copy(steps = it))) }
    }
    if (node.arguments.isNotEmpty()) Text("旧动作参数仍保存在此节点；切换回原动作可继续编辑。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (creation != null) AlertDialog(onDismissRequest = { creation = null }, title = { Text(if (creation == MacroProcessMode.COPY) "制作宏副本" else "新建宏") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("宏名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("地址：macro.${newName.trim()}", style = MaterialTheme.typography.bodySmall)
                if ("macro.${newName.trim()}" in used) Text("此地址已经存在，请换一个名称。", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            val selectedMode = creation ?: return@TextButton
            onChange(node.copy(macroProcess = config.copy(key = "macro.${newName.trim()}", mode = selectedMode)))
            creation = null
            if (selectedMode == MacroProcessMode.COPY) copySource = true
        }, enabled = MacroReferences.canonicalAddress("macro.${newName.trim()}") != null && "macro.${newName.trim()}" !in used) { Text("创建") } },
        dismissButton = { TextButton(onClick = { creation = null }) { Text("取消") } })
    if (copySource) PromptReferenceSheet(sourceChoices, { source -> onChange(node.copy(macroProcess = config.copy(source = source))) }, { copySource = false })
}

@Composable
internal fun PromptManagementEditor(node: WorkflowNode, nodes: List<WorkflowNode>, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice>,
    actions: List<ActionDescriptor> = ActionRegistry.defaults().descriptors()) {
    val inject = node.actionId == "prompt.inject"
    ChoiceLine("管理方式", inject, listOf(true to "插入或更新", false to "移除")) { onChange(node.copy(actionId = if (it) "prompt.inject" else "prompt.remove")) }
    val id = node.arguments["id"].orEmpty()
    val configured = nodes.filter { it.actionId == "prompt.inject" && it.id != node.id }
        .mapNotNull { other -> other.arguments["id"]?.takeIf(String::isNotBlank)?.let { it to "${other.title.ifBlank { "提示词节点" }} · $it" } }.distinctBy { it.first }
    if (!inject && configured.isNotEmpty()) ChoiceLine("已配置的注入", id, configured +
        if (id !in configured.map { it.first }) listOf(id to "当前标识：$id") else emptyList()) {
        onChange(node.copy(arguments = node.arguments + ("id" to it)))
    }
    TextFieldLine("注入标识", id, { onChange(node.copy(arguments = node.arguments + ("id" to it))) })
    Text("同一所有者以此标识更新或移除；新节点默认使用稳定标识。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (inject) {
        val navigation = LocalSourceNavigation.current
        val effective = navigation?.library
        val mainId = navigation?.resource?.takeIf { it.kind == ExtensionResourceKind.MAIN }?.id
            ?: effective?.sessions?.firstOrNull { it.id == effective.selectedSessionId }?.mainCallId
        val main = effective?.mainCalls?.firstOrNull { it.id == mainId }
        val modelActions = actions.filter { it.usesModel }.map { it.id }.toSet()
        val targets = main?.workflows.orEmpty().filter { it.enabled }.flatMap { workflow ->
            workflow.nodes.filter { it.enabled && it.actionId in modelActions }.map { workflow to it }
        }
        val current = node.arguments["targetWorkflowId"].orEmpty() to node.arguments["targetNodeId"].orEmpty()
        val targetChoices = listOf(("" to "") to "主调用默认模型节点") + targets.map { (call, target) ->
            (call.id to target.id) to "${call.title.ifBlank { "未命名调用" }} · ${target.title.ifBlank { "模型节点" }}"
        }
        ChoiceLine("目标主调用节点", current, targetChoices +
            if (targetChoices.none { it.first == current }) listOf(current to "已有目标：${current.first} / ${current.second}") else emptyList()) { (call, target) ->
            onChange(node.copy(arguments = node.arguments + ("targetWorkflowId" to call) + ("targetNodeId" to target)))
        }
        val targetWorkflows = main?.workflows.orEmpty().filter { it.enabled && (current.first.isBlank() || it.id == current.first) }
        val targetNode = if (current.second.isNotBlank()) targets.singleOrNull { (call, target) ->
            (current.first.isBlank() || call.id == current.first) && target.id == current.second
        }?.second else {
            val targetWorkflow = if (current.first.isNotBlank()) targetWorkflows.singleOrNull()
                else if (main?.primaryWorkflowId != null) targetWorkflows.firstOrNull { it.id == main.primaryWorkflowId }
                else targetWorkflows.firstOrNull()
            targetWorkflow?.nodes?.lastOrNull { it.enabled && it.actionId in modelActions }
        }
        val slots = targetNode?.blocks.orEmpty().filter { it.injectionSlot && it.enabled }
        val blockId = node.arguments["targetBlockId"].orEmpty()
        val slot = slots.firstOrNull { it.id == blockId }
        ChoiceLine("接收槽", blockId, listOf("" to "普通注入 · 自行设置角色与位置") + slots.map { it.id to it.title.ifBlank { "未命名接收槽" } } +
            if (blockId.isNotBlank() && slot == null) listOf(blockId to "已有槽不可用：$blockId") else emptyList()) {
            val arguments = node.arguments + ("targetBlockId" to it)
            onChange(node.copy(arguments = if (it.isNotBlank() && "contextOrder" !in arguments) arguments + ("contextOrder" to "1") else arguments))
        }
        if (effective == null || main == null) Text("当前未定位到主调用草稿或会话主调用；选定主调用后可选择真实模型节点及接收槽。", style = MaterialTheme.typography.bodySmall)
        if (blockId.isNotBlank()) {
            Text(slot?.let { "继承接收槽：${it.role.name} · ${promptPositionLabel(it)}。槽停用或条件不满足时，内容不发送。" }
                ?: "所选槽已删除、停用或不属于当前目标；运行会报告，不会改为普通注入。请明确重选槽或普通注入。",
                style = MaterialTheme.typography.bodySmall, color = if (slot == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            val contextOrder = node.arguments["contextOrder"] ?: "1"
            val orderChoices = (1..4).map { it.toString() to it.toString() }
            ChoiceLine("上下文顺序", contextOrder, orderChoices +
                if (orderChoices.none { it.first == contextOrder }) listOf(contextOrder to "当前：$contextOrder") else emptyList()) {
                onChange(node.copy(arguments = node.arguments + ("contextOrder" to it)))
            }
            var orderDraft by remember(node.id, contextOrder) { mutableStateOf(contextOrder) }
            val invalidOrder = orderDraft.toIntOrNull()?.let { it > 0 } != true
            OutlinedTextField(orderDraft, { value ->
                orderDraft = value
                if (value.toIntOrNull()?.let { it > 0 } == true) onChange(node.copy(arguments = node.arguments + ("contextOrder" to value)))
            }, label = { Text("自定义上下文顺序 · 正整数") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = invalidOrder)
            if (invalidOrder) Text("请输入正整数；当前输入尚未应用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text("仅控制同一接收槽内提示内容的位置，数字小的在前；同号按固定定义顺序排列。与调用的排队级别、并行或排队执行无关。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            ChoiceLine("角色", node.arguments["role"] ?: Role.SYSTEM.name, Role.entries.map { it.name to it.name }) { onChange(node.copy(arguments = node.arguments + ("role" to it))) }
            ChoiceLine("位置", node.arguments["position"] ?: PromptPosition.BEFORE_HISTORY.name, listOf(
                PromptPosition.BEFORE_HISTORY.name to "历史之前", PromptPosition.IN_HISTORY.name to "历史中", PromptPosition.AFTER_HISTORY.name to "历史之后")) { onChange(node.copy(arguments = node.arguments + ("position" to it))) }
            if (node.arguments["position"] == PromptPosition.IN_HISTORY.name) TextFieldLine("距历史末尾的消息数", node.arguments["depth"] ?: "0", { onChange(node.copy(arguments = node.arguments + ("depth" to it))) })
        }
        ChoiceLine("生命周期", node.arguments["scope"] ?: InjectionScope.TURN.name, InjectionScope.entries.map { it.name to it.name }) { onChange(node.copy(arguments = node.arguments + ("scope" to it))) }
        ChoiceLine("内容为空时", node.arguments["emptyBehavior"] ?: "ALLOW", listOf("ALLOW" to "仍插入空内容", "SKIP" to "跳过本次注入", "REMOVE" to "移除同标识注入")) { onChange(node.copy(arguments = node.arguments + ("emptyBehavior" to it))) }
        PromptTemplateField("注入内容", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 3)
        TextProcessingEditor("注入内容处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
        Text("注入须在目标模型请求开始前完成；可在调用的时机设置中选择正文准备前。上下文顺序不改变触发时机或调用排队，选择接收槽也不会自动修改其他节点。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val known = if (inject) setOf("id", "role", "position", "depth", "targetWorkflowId", "targetNodeId", "targetBlockId", "contextOrder", "scope", "emptyBehavior") else setOf("id")
    if (node.arguments.keys.any { it !in known }) ActionArgumentForm(node, null, onChange, references)
}
