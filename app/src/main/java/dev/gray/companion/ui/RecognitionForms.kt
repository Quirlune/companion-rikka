package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.Compare
import dev.gray.companion.core.Recognition
import dev.gray.companion.core.RecognitionMode
import dev.gray.companion.core.RecognitionRule
import dev.gray.companion.core.SafePatterns

/** Recognition is a pure read of rendered templates; legacy Condition remains a separate AND guard. */
@Composable
internal fun RecognitionForm(
    label: String,
    recognition: Recognition?,
    references: List<PromptReferenceChoice>,
    onChange: (Recognition?) -> Unit
) {
    ToggleLine(label, recognition != null) {
        onChange(if (it) Recognition(rules = listOf(RecognitionRule())) else null)
    }
    if (recognition != null) RecognitionGroupForm(recognition, references, onChange = { onChange(it) })
}

@Composable
private fun RecognitionGroupForm(
    group: Recognition,
    references: List<PromptReferenceChoice>,
    depth: Int = 0,
    onChange: (Recognition) -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (depth == 0) "识别规则" else "嵌套规则 ${depth + 1}", style = MaterialTheme.typography.titleSmall)
        ChoiceLine("匹配方式", group.mode, listOf(RecognitionMode.ALL to "全部满足", RecognitionMode.ANY to "任意满足")) {
            onChange(group.copy(mode = it))
        }
        ToggleLine("取反（条件不成立时通过）", group.negate) { onChange(group.copy(negate = it)) }
        group.rules.forEachIndexed { index, rule -> key("rule-$index") {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("规则 ${index + 1}", style = MaterialTheme.typography.labelLarge)
                PromptTemplateField("读取资料 / 宏模板", rule.template, references, { value ->
                    onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(template = value)) }))
                }, minLines = 1)
                var regexExpanded by remember { mutableStateOf(rule.pattern.isNotBlank()) }
                TextButton(onClick = { regexExpanded = !regexExpanded }) { Text(if (regexExpanded) "收起正则提取" else "正则提取（可选）") }
                if (regexExpanded) {
                    TextFieldLine("提取正则", rule.pattern, { value -> onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(pattern = value)) })) })
                    var captureDraft by remember { mutableStateOf(rule.captureGroup.toString()) }
                    OutlinedTextField(captureDraft, { value ->
                        if (value.isEmpty() || (value.all(Char::isDigit) && value.toIntOrNull() != null)) {
                            captureDraft = value
                            onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(captureGroup = value.toIntOrNull() ?: 0)) }))
                        }
                    }, label = { Text("捕获组（0 为完整匹配）") }, modifier = Modifier.fillMaxWidth())
                    SafePatterns.validate(rule.pattern, rule.captureGroup)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                ChoiceLine("比较", rule.op, listOf(
                    Compare.EXISTS to "存在", Compare.EQUALS to "等于", Compare.NOT_EQUALS to "不等于",
                    Compare.GREATER to "大于", Compare.GREATER_OR_EQUAL to "大于等于",
                    Compare.LESS to "小于", Compare.LESS_OR_EQUAL to "小于等于"
                )) { value -> onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(op = value)) })) }
                if (rule.op != Compare.EXISTS) TextFieldLine("比较值", rule.value, { value ->
                    onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(value = value)) }))
                })
                ToggleLine("本规则取反", rule.negate) { value ->
                    onChange(group.copy(rules = group.rules.toMutableList().apply { set(index, rule.copy(negate = value)) }))
                }
                TextButton(onClick = { onChange(group.copy(rules = group.rules.filterIndexed { i, _ -> i != index })) }) { Text("删除规则") }
            } }
        } }
        group.groups.forEachIndexed { index, child -> key("group-$index") {
            RecognitionGroupForm(child, references, depth + 1, { value ->
                onChange(group.copy(groups = group.groups.toMutableList().apply { set(index, value) }))
            })
            TextButton(onClick = { onChange(group.copy(groups = group.groups.filterIndexed { i, _ -> i != index })) }) { Text("删除嵌套组") }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(group.copy(rules = group.rules + RecognitionRule())) }) { Text("添加规则") }
            OutlinedButton(onClick = { onChange(group.copy(groups = group.groups + Recognition(rules = listOf(RecognitionRule())))) }) { Text("添加嵌套组") }
        }
        if (depth == 0) Text("识别与旧条件同时生效；这里只读取宏和状态，不调用模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}
