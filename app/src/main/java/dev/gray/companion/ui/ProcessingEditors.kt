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
                ChoiceLine("处理", step.kind, processKindChoices) { kind ->
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
    OutlinedButton(onClick = { onChange(steps + TextProcessStep(kind = TextProcessKind.TRIM)); expanded = true }, enabled = steps.size < 64) { Text("添加处理步骤") }
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
    val options = node.generation
    PromptTemplateField("输入模板", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 2)
    TextProcessingEditor("输入内容处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
    ChoiceLine("此节点模型", options.profileId, listOf<Pair<String?, String>>(null to "继承调用 / 对话模型") + profiles.map { it.id to it.name.ifBlank { it.model } } +
        if (options.profileId != null && profiles.none { it.id == options.profileId }) listOf(options.profileId to "未找到模型：${options.profileId}") else emptyList()) {
        onChange(node.copy(generation = options.copy(profileId = it)))
    }
    EditorSection("生成参数", "温度、Top P、输出上限与推理级别 · 留空继承") {
        OptionalFloatLine("温度", options.temperature) { onChange(node.copy(generation = options.copy(temperature = it))) }
        OptionalFloatLine("Top P", options.topP) { onChange(node.copy(generation = options.copy(topP = it))) }
        OptionalIntLine("输出上限", options.maxTokens) { onChange(node.copy(generation = options.copy(maxTokens = it))) }
        ChoiceLine("推理级别", options.reasoning, listOf<Pair<ModelReasoning?, String>>(null to "继承") + ModelReasoning.entries.map { it to it.name }) {
            onChange(node.copy(generation = options.copy(reasoning = it)))
        }
        ToggleLine("此节点覆盖全局思考解析", node.reasoningParsing != null) { enabled ->
            onChange(node.copy(reasoningParsing = if (enabled) ReasoningParsingConfig() else null))
        }
        node.reasoningParsing?.let { parsing ->
            ReasoningParsingFields(parsing) { onChange(node.copy(reasoningParsing = it)) }
        }
    }
    TextProcessingEditor("输出内容处理", node.outputProcessing, references) { onChange(node.copy(outputProcessing = it)) }
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
internal fun PromptManagementEditor(node: WorkflowNode, nodes: List<WorkflowNode>, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice>) {
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
        val targets = references.mapNotNull { ref ->
            val call = ref.owners.firstOrNull { it.id.startsWith("call:main:") } ?: return@mapNotNull null
            if (ref.owners.none { it.id.startsWith("node:") && it.detail.contains("模型生成") }) return@mapNotNull null
            val target = ref.producerNodeId ?: return@mapNotNull null
            Triple(call.id.substringAfterLast(':'), target, "${call.title} · ${ref.owners.lastOrNull()?.title ?: ref.title}")
        }.distinctBy { it.first to it.second }
        val current = node.arguments["targetWorkflowId"].orEmpty() to node.arguments["targetNodeId"].orEmpty()
        ChoiceLine("目标主调用节点", current, listOf(("" to "") to "主调用默认模型节点") + targets.map { (call, target, label) -> (call to target) to label } +
            if (current.first.isNotBlank() && targets.none { it.first == current.first && it.second == current.second }) listOf(current to "已有目标：${current.first} / ${current.second}") else emptyList()) { (call, target) ->
            onChange(node.copy(arguments = node.arguments + ("targetWorkflowId" to call) + ("targetNodeId" to target)))
        }
        ChoiceLine("角色", node.arguments["role"] ?: Role.SYSTEM.name, Role.entries.map { it.name to it.name }) { onChange(node.copy(arguments = node.arguments + ("role" to it))) }
        ChoiceLine("位置", node.arguments["position"] ?: PromptPosition.BEFORE_HISTORY.name, listOf(
            PromptPosition.BEFORE_HISTORY.name to "历史之前", PromptPosition.IN_HISTORY.name to "历史中", PromptPosition.AFTER_HISTORY.name to "历史之后")) { onChange(node.copy(arguments = node.arguments + ("position" to it))) }
        if (node.arguments["position"] == PromptPosition.IN_HISTORY.name) TextFieldLine("距历史末尾的消息数", node.arguments["depth"] ?: "0", { onChange(node.copy(arguments = node.arguments + ("depth" to it))) })
        ChoiceLine("生命周期", node.arguments["scope"] ?: InjectionScope.TURN.name, InjectionScope.entries.map { it.name to it.name }) { onChange(node.copy(arguments = node.arguments + ("scope" to it))) }
        ChoiceLine("内容为空时", node.arguments["emptyBehavior"] ?: "ALLOW", listOf("ALLOW" to "仍插入空内容", "SKIP" to "跳过本次注入", "REMOVE" to "移除同标识注入")) { onChange(node.copy(arguments = node.arguments + ("emptyBehavior" to it))) }
        PromptTemplateField("注入内容", node.inputTemplate, references, { onChange(node.copy(inputTemplate = it)) }, minLines = 3)
        TextProcessingEditor("注入内容处理", node.inputProcessing, references) { onChange(node.copy(inputProcessing = it)) }
        Text("注入或移除在目标提示词编译前生效；请设置相应触发时机和依赖。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val known = if (inject) setOf("id", "role", "position", "depth", "targetWorkflowId", "targetNodeId", "scope", "emptyBehavior") else setOf("id")
    if (node.arguments.keys.any { it !in known }) ActionArgumentForm(node, null, onChange, references)
}
