package dev.gray.companion.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.gray.companion.core.*

/** Legacy nodes remain executable and editable until the user explicitly chooses conversion. */
@Composable
internal fun LegacyConversionOffer(node: WorkflowNode, onChange: (WorkflowNode) -> Unit) {
    node.legacyAction?.let { backup ->
        TextButton(onClick = { onChange(node.copy(
            actionId = backup.actionId, inputTemplate = backup.inputTemplate,
            inputProcessing = backup.inputProcessing, outputProcessing = backup.outputProcessing,
            macroProcess = backup.macroProcess, arguments = backup.arguments, legacyAction = null,
        )) }) { Text("恢复转换前配置") }
    }
    val converted = legacyConversion(node) ?: return
    var confirm by remember(node.id) { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }) { Text("转换为${if (converted.actionId == "macro.process") "宏处理" else "组合文本"}…") }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("转换旧动作？") },
        text = { Text("原动作参数会完整保留在节点中；转换后使用新的处理配置。请检查目标地址、步骤和结果，再保存调用。") },
        confirmButton = { TextButton(onClick = {
            onChange(converted.copy(legacyAction = LegacyActionBackup(
                actionId = node.actionId, inputTemplate = node.inputTemplate,
                inputProcessing = node.inputProcessing, outputProcessing = node.outputProcessing,
                macroProcess = node.macroProcess, arguments = node.arguments,
            )))
            confirm = false
        }) { Text("转换") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

private fun legacyConversion(node: WorkflowNode): WorkflowNode? {
    val args = node.arguments
    return when (node.actionId) {
        "state.set" -> {
            val key = args["key"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
            node.copy(actionId = "macro.process", inputTemplate = args["value"] ?: node.inputTemplate,
                macroProcess = MacroProcessConfig(key = "state.$key", mode = MacroProcessMode.SET))
        }
        "state.increment" -> {
            val key = args["key"]?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val amount = args["amount"].orEmpty().ifBlank { "1" }
            if (amount.toBigDecimalOrNull() == null) return null
            val min = args["min"].orEmpty()
            val max = args["max"].orEmpty()
            if ((min.isNotBlank() && min.toBigDecimalOrNull() == null) || (max.isNotBlank() && max.toBigDecimalOrNull() == null)) return null
            val steps = listOf(TextProcessStep(TextProcessKind.INCREASE, amount = amount)) +
                if (min.isNotBlank() || max.isNotBlank()) listOf(TextProcessStep(TextProcessKind.CLAMP, min = min, max = max)) else emptyList()
            node.copy(actionId = "macro.process", macroProcess = MacroProcessConfig(key = "state.$key", mode = MacroProcessMode.UPDATE, initial = "0", steps = steps))
        }
        "macro.write" -> {
            val key = args["key"]?.trim()?.takeIf { MacroReferences.canonicalAddress(it) != null } ?: return null
            node.copy(actionId = "macro.process", inputTemplate = args["value"] ?: node.inputTemplate,
                macroProcess = MacroProcessConfig(key = key, mode = MacroProcessMode.SET))
        }
        "macro.activate" -> {
            val key = args["key"]?.trim()?.takeIf { MacroReferences.canonicalAddress(it) != null } ?: return null
            val mode = when (args["enabled"]) { "true" -> MacroProcessMode.ENABLE; "false" -> MacroProcessMode.DISABLE; else -> return null }
            node.copy(actionId = "macro.process", macroProcess = MacroProcessConfig(key = key, mode = mode))
        }
        "text.extract" -> {
            val pattern = args["pattern"]?.takeIf { it.isNotBlank() } ?: return null
            val group = args["captureGroup"]?.toIntOrNull() ?: 0
            node.copy(actionId = "text.render", outputProcessing = listOf(TextProcessStep(TextProcessKind.REGEX_EXTRACT, pattern = pattern, group = group)) + node.outputProcessing)
        }
        else -> null
    }
}
