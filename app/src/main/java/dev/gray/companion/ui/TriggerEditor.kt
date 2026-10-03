package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

/** All three producer levels edit the same pure recognition and Condition contracts. */
@Composable
internal fun TriggerEditor(
    recognition: Recognition?,
    condition: Condition?,
    references: List<PromptReferenceChoice>,
    onChange: (Recognition?, Condition?) -> Unit,
) {
    var clearVisual by remember { mutableStateOf(false) }
    var codeOpen by remember { mutableStateOf(condition?.expression?.isNotBlank() == true) }
    var legacyOpen by remember { mutableStateOf(condition != null && condition.expression.isBlank()) }
    Text(
        if (recognition == null && condition == null) "始终触发" else "满足以下条件时触发",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (recognition == null) {
        TextButton(onClick = { onChange(Recognition(rules = listOf(RecognitionRule())), condition) }) { Text("添加可视条件") }
    } else {
        TriggerGroupEditor(recognition, references) { onChange(it, condition) }
        RecognitionEvaluator.validate(recognition).map { it.message }.distinct().forEach { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { clearVisual = true }) { Text("移除可视条件") }
    }
    TextButton(onClick = { codeOpen = !codeOpen }) { Text(if (codeOpen) "收起高级代码" else "高级代码") }
    if (codeOpen) {
        val expression = condition?.expression.orEmpty()
        val error = expression.takeIf(String::isNotBlank)?.let(ConditionExpressions::validate)
        PromptTemplateField(
            label = if (condition?.key?.isNotBlank() == true) "条件表达式（空白恢复旧状态条件）" else "条件表达式（空白为始终）", value = expression, references = references,
            onChange = { value ->
                // Simple legacy fields remain stored while an expression is being edited.
                onChange(recognition, (condition ?: Condition()).copy(expression = value))
            },
            minLines = 2, transformMacro = { token ->
                val address = token.removeSurrounding("{{", "}}")
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
                "macro(\"$address\")"
            },
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("输入 {{ 选取资料后自动插入 macro(\"地址\")。只读取资料；支持 extract(值, \"正则\", 捕获组)、number / text / exists、比较、&&、||、!。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (recognition != null) Text("代码与可视条件按 AND 同时判断。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (condition != null) TextButton(onClick = {
            if (condition.key.isBlank()) onChange(recognition, null)
            else onChange(recognition, condition.copy(expression = ""))
        }) { Text(if (condition.key.isBlank()) "移除代码条件" else "清空代码，恢复保留的旧条件") }
    }
    if (condition != null && condition.expression.isBlank() && condition.key.isNotBlank()) {
        Text("保留的旧状态条件 · 与可视条件按 AND 判断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { legacyOpen = !legacyOpen }) { Text(if (legacyOpen) "收起旧条件" else "编辑旧条件") }
        if (legacyOpen) {
            TextFieldLine("状态键", condition.key, { onChange(recognition, condition.copy(key = it)) })
            ChoiceLine("比较", condition.op, triggerCompareChoices) { onChange(recognition, condition.copy(op = it)) }
            if (condition.op != Compare.EXISTS) TextFieldLine("比较值", condition.value, { onChange(recognition, condition.copy(value = it)) })
            TextButton(onClick = {
                val converted = RecognitionRule(template = "{{state.${condition.key}}}", op = condition.op, value = condition.value)
                val merged = if (recognition == null) Recognition(rules = listOf(converted))
                else Recognition(groups = listOf(recognition), rules = listOf(converted))
                onChange(merged, null)
            }) { Text("转换为可视条件（保留 AND 关系）") }
            TextButton(onClick = { onChange(recognition, null) }) { Text("移除旧状态条件") }
        }
    }
    if (clearVisual) AlertDialog(
        onDismissRequest = { clearVisual = false }, title = { Text("移除可视条件？") },
        text = { Text("已配置的规则会从当前草稿移除，代码和旧状态条件会保留。") },
        confirmButton = { TextButton(onClick = { clearVisual = false; onChange(null, condition) }) { Text("移除") } },
        dismissButton = { TextButton(onClick = { clearVisual = false }) { Text("取消") } },
    )
}

private val triggerCompareChoices = listOf(
    Compare.EXISTS to "存在", Compare.EQUALS to "等于", Compare.NOT_EQUALS to "不等于",
    Compare.GREATER to "大于", Compare.GREATER_OR_EQUAL to "大于等于",
    Compare.LESS to "小于", Compare.LESS_OR_EQUAL to "小于等于",
)

@Composable
private fun TriggerGroupEditor(group: Recognition, references: List<PromptReferenceChoice>, depth: Int = 0, onChange: (Recognition) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceLine(if (depth == 0) "组合" else "子条件组合", group.mode, listOf(RecognitionMode.ALL to "全部满足（AND）", RecognitionMode.ANY to "任意满足（OR）")) { onChange(group.copy(mode = it)) }
        ToggleLine("取反（NOT）", group.negate) { onChange(group.copy(negate = it)) }
        group.rules.forEachIndexed { index, rule -> key(index) {
            if (index > 0) HorizontalDivider()
            fun update(value: RecognitionRule) = onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, value) }))
            PromptTemplateField("读取资料 / 宏模板", rule.template, references, { update(rule.copy(template = it)) }, minLines = 1)
            ChoiceLine("读取为", rule.valueType, listOf(RecognitionValueType.TEXT to "文本", RecognitionValueType.NUMBER to "数字")) { update(rule.copy(valueType = it)) }
            var regexOpen by remember { mutableStateOf(rule.pattern.isNotBlank() || rule.captureGroup != 0) }
            TextButton(onClick = { regexOpen = !regexOpen }) { Text(if (regexOpen) "收起正则提取" else "正则提取（可选）") }
            if (regexOpen) {
                TextFieldLine("提取正则", rule.pattern, { update(rule.copy(pattern = it)) })
                var capture by remember(rule.captureGroup) { mutableStateOf(rule.captureGroup.toString()) }
                OutlinedTextField(capture, { value ->
                    capture = value
                    value.toIntOrNull()?.takeIf { it >= 0 }?.let { update(rule.copy(captureGroup = it)) }
                }, label = { Text("捕获组（0 为完整匹配）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SafePatterns.validate(rule.pattern, rule.captureGroup)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            ChoiceLine("比较", rule.op, triggerCompareChoices) { update(rule.copy(op = it)) }
            if (rule.op != Compare.EXISTS) TextFieldLine("比较值", rule.value, { update(rule.copy(value = it)) })
            ToggleLine("本条件取反", rule.negate) { update(rule.copy(negate = it)) }
            TextButton(onClick = { onChange(group.copy(rules = group.rules.filterIndexed { i, _ -> i != index })) }) { Text("删除条件") }
        } }
        group.groups.forEachIndexed { index, child -> key("nested-$index") {
            EditorSection("子条件组 ${index + 1}", "${child.rules.size} 个条件 · ${if (child.mode == RecognitionMode.ALL) "AND" else "OR"}") {
                TriggerGroupEditor(child, references, depth + 1) { value -> onChange(group.copy(groups = group.groups.toMutableList().apply { set(index, value) })) }
                TextButton(onClick = { onChange(group.copy(groups = group.groups.filterIndexed { i, _ -> i != index })) }) { Text("删除子条件组") }
            }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(group.copy(rules = group.rules + RecognitionRule())) }) { Text("添加条件") }
            TextButton(onClick = { onChange(group.copy(groups = group.groups + Recognition(rules = listOf(RecognitionRule())))) }) { Text("添加子条件组") }
        }
    }
}
