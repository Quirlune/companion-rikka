package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
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
import dev.gray.companion.core.ActionDescriptor
import dev.gray.companion.core.ActionFieldDescriptor
import dev.gray.companion.core.ActionFieldType
import dev.gray.companion.core.MacroReferences
import dev.gray.companion.core.WorkflowNode

/** The editor reflects the captured registry's fields; switching actions never removes old arguments. */
@Composable
internal fun ActionArgumentForm(node: WorkflowNode, action: ActionDescriptor?, onChange: (WorkflowNode) -> Unit, references: List<PromptReferenceChoice> = emptyList()) {
    if (action?.fields?.isNotEmpty() == true) {
        Text("动作参数", style = MaterialTheme.typography.titleSmall)
        action.fields.forEach { field ->
            ActionFieldLine(field, node.arguments[field.key].orEmpty(), field.key in node.arguments, references,
                onReset = { onChange(node.copy(arguments = node.arguments - field.key)) },
                onChange = { value -> onChange(node.copy(arguments = node.arguments + (field.key to value))) })
        }
    }

    // Imported parameters are kept even when their descriptor or action is unavailable.
    val knownKeys = action?.fields?.map { it.key }?.toSet().orEmpty()
    val extraKeys = node.arguments.keys.filterNot { it in knownKeys }
    var advanced by remember(node.id) { mutableStateOf(false) }
    TextButton(onClick = { advanced = !advanced }) {
        Text(if (advanced) "收起其他参数${if (extraKeys.isNotEmpty()) "（${extraKeys.size}）" else ""}" else "其他参数${if (extraKeys.isNotEmpty()) "（${extraKeys.size}）" else ""} · 展开")
    }
    if (advanced) {
        Text("保留导入的参数；修改名称前请确认动作处理器支持它。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extraKeys.forEach { argumentKey -> key(argumentKey) {
            AdvancedArgumentLine(argumentKey, node.arguments[argumentKey].orEmpty(), node.arguments.keys) { newKey, newValue ->
                val arguments = if (newKey == null) node.arguments - argumentKey
                    else (node.arguments - argumentKey) + (newKey to newValue)
                onChange(node.copy(arguments = arguments))
            }
        } }
        var newKey by remember(node.id) { mutableStateOf("") }
        var newValue by remember(node.id) { mutableStateOf("") }
        OutlinedTextField(newKey, { newKey = it }, label = { Text("新参数名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(newValue, { newValue = it }, label = { Text("新参数值") }, modifier = Modifier.fillMaxWidth())
        val candidate = newKey.trim()
        OutlinedButton(onClick = {
            onChange(node.copy(arguments = node.arguments + (candidate to newValue)))
            newKey = ""
            newValue = ""
        }, enabled = candidate.isNotEmpty() && candidate !in node.arguments && candidate !in knownKeys) {
            Text("添加参数")
        }
    }
}

@Composable
private fun ActionFieldLine(field: ActionFieldDescriptor, value: String, isSet: Boolean, references: List<PromptReferenceChoice>, onReset: () -> Unit, onChange: (String) -> Unit) {
    val label = field.title + if (field.required) " *" else ""
    val hint = field.defaultValue?.let { "默认：$it" } ?: when (field.type) {
        ActionFieldType.NUMBER -> "数字或数值宏"
        ActionFieldType.BOOLEAN -> "true、false 或布尔宏"
        ActionFieldType.TEMPLATE -> "可插入宏"
        ActionFieldType.MACRO -> "宏地址，例如 macro.mood（不填 {{ }}）"
        ActionFieldType.STRING -> if (field.required) "必填" else "可选"
    }
    val options = field.choices.map { it.value to it.title }
    var manualInput by remember(field.key) { mutableStateOf(options.isNotEmpty() && isSet && options.none { it.first == value }) }
    if (options.isNotEmpty()) {
        val selected = if (isSet) value else field.defaultValue.orEmpty()
        val choices = options + if (options.none { it.first == selected }) {
            listOf(selected to if (isSet) "当前自定义值：$selected" else "未设置")
        } else emptyList()
        ChoiceLine(label, selected, choices) { chosen ->
            if (chosen.isEmpty() && options.none { it.first.isEmpty() }) onReset() else onChange(chosen)
            manualInput = false
        }
        TextButton(onClick = { manualInput = !manualInput }) { Text(if (manualInput) "收起自定义模板或值" else "高级：输入自定义模板或值") }
    }
    if (options.isEmpty() || manualInput) OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(if (options.isEmpty()) label else "自定义：$label") },
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (field.type == ActionFieldType.TEMPLATE) 2 else 1
    )
    if (field.type == ActionFieldType.NUMBER && value.isNotBlank() && !value.contains("{{")) {
        val invalid = if (field.integer) value.toIntOrNull() == null else value.toBigDecimalOrNull() == null
        if (invalid) Text(if (field.integer) "请填写整数或数值宏。" else "请填写数字或数值宏。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (!isSet && field.defaultValue != null) {
        Text("未填写时使用默认值 ${field.defaultValue}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (isSet && (!field.required || field.defaultValue != null)) {
        TextButton(onClick = onReset) { Text(if (field.defaultValue != null) "恢复默认值" else "不传此参数") }
        if (value.isEmpty() && field.type == ActionFieldType.TEMPLATE) {
            Text("当前传入空文本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (field.type == ActionFieldType.BOOLEAN) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SuggestionChip(onClick = { onChange("true") }, label = { Text("是") })
            SuggestionChip(onClick = { onChange("false") }, label = { Text("否") })
        }
    }
    if (field.type == ActionFieldType.TEMPLATE) {
        var showMacros by remember(field.key) { mutableStateOf(false) }
        TextButton(onClick = { showMacros = !showMacros }) { Text(if (showMacros) "收起宏" else "插入宏") }
        if (showMacros) MacroHelp { onChange(value + it) }
    }
    if (field.type == ActionFieldType.MACRO) {
        val addresses = references.filter { reference ->
            MacroReferences.canonicalAddress(reference.macro.removePrefix("{{").removeSuffix("}}")) != null
        }
        if (addresses.isNotEmpty()) PromptReferencePicker(addresses, onInsert = { reference ->
            val address = reference.removePrefix("{{").removeSuffix("}}")
            MacroReferences.canonicalAddress(address)?.let(onChange)
        }, versioned = false)
    }
}

@Composable
private fun AdvancedArgumentLine(
    argumentKey: String,
    value: String,
    existingKeys: Set<String>,
    onEdit: (String?, String) -> Unit
) {
    var keyDraft by remember(argumentKey) { mutableStateOf(argumentKey) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(keyDraft, { candidate ->
            keyDraft = candidate
            val normalized = candidate.trim()
            if (normalized.isNotEmpty() && (normalized == argumentKey || normalized !in existingKeys)) {
                onEdit(normalized, value)
            }
        }, label = { Text("参数名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { onEdit(argumentKey, it) }, label = { Text("参数值") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onEdit(null, value) }) { Text("删除参数") }
    }
}
