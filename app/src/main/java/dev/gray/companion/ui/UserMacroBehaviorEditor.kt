package dev.gray.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.UserInputMacros
import dev.gray.companion.core.UserMacroDefinition
import dev.gray.companion.core.UserMacroValue
import dev.gray.companion.core.UserMacroValueKind
import dev.gray.companion.core.WorkflowNode

@Composable
internal fun UserMacroBehaviorEditor(
    node: WorkflowNode,
    references: List<PromptReferenceChoice>,
    onChange: (WorkflowNode) -> Unit,
) {
    val definition = node.userMacro ?: UserMacroDefinition(
        shortcut = "",
        value = UserMacroValue(),
    )
    val value = definition.value
    val valueChoices = remember(references) { scalarUserMacroReferences(references) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node.outputProcessing.isNotEmpty() || node.regexOutputCopy.enabled) {
            Text("这个行为仍保留了旧类型的输出处理，登记用户宏前需要清除。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onChange(node.copy(outputProcessing = emptyList(),
                regexOutputCopy = node.regexOutputCopy.copy(enabled = false))) }) { Text("清除旧输出处理") }
        }
        OutlinedTextField(
            value = definition.shortcut,
            onValueChange = { shortcut ->
                onChange(node.copy(userMacro = definition.copy(shortcut = shortcut)))
            },
            label = { Text("快捷词") },
            placeholder = { Text("/抽天气") },
            supportingText = { Text("在聊天输入快捷词并加 ASCII 空格后，会显示为待执行标签；对应行为运行并被接受后，才会登记到当前聊天。") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        ChoiceLine(
            label = "展开内容",
            selected = value.kind,
            choices = listOf(
                UserMacroValueKind.TEXT to "固定文本",
                UserMacroValueKind.REFERENCE to "现有值引用",
                UserMacroValueKind.RANDOM_REFERENCE to "从候选值随机一项",
            ),
        ) { kind ->
            val nextValue = when (kind) {
                UserMacroValueKind.TEXT -> value.copy(kind = kind, references = emptyList())
                UserMacroValueKind.REFERENCE, UserMacroValueKind.RANDOM_REFERENCE -> value.copy(kind = kind, text = "")
            }
            onChange(node.copy(userMacro = definition.copy(value = nextValue)))
        }

        when (value.kind) {
            UserMacroValueKind.TEXT -> {
                OutlinedTextField(
                    value = value.text,
                    onValueChange = { text ->
                        onChange(node.copy(userMacro = definition.copy(value = value.copy(text = text))))
                    },
                    label = { Text("固定文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Text(
                    "固定文本只在发送新消息时展开。输入和预览阶段不会执行宏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UserMacroValueKind.REFERENCE -> UserMacroReferenceValues(
                references = value.references,
                choices = valueChoices,
                allowMany = false,
                onChange = { next ->
                    onChange(node.copy(userMacro = definition.copy(value = value.copy(references = next))))
                },
            )

            UserMacroValueKind.RANDOM_REFERENCE -> UserMacroReferenceValues(
                references = value.references,
                choices = valueChoices,
                allowMany = true,
                onChange = { next ->
                    onChange(node.copy(userMacro = definition.copy(value = value.copy(references = next))))
                },
            )
        }

        UserInputMacros.validate(definition).filter { it.error }.forEach { issue ->
            Text(
                issue.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun UserMacroReferenceValues(
    references: List<String>,
    choices: List<PromptReferenceChoice>,
    allowMany: Boolean,
    onChange: (List<String>) -> Unit,
) {
    Text(
        if (allowMany) "发送新消息时从候选值中选择一项；这里只保存引用，不会提前读取或抽取。"
        else "发送新消息时读取这个值；输入和预览阶段不会执行宏。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    references.forEachIndexed { index, reference ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = reference,
                    onValueChange = { changed ->
                        onChange(references.mapIndexed { at, old -> if (at == index) changed else old })
                    },
                    label = { Text(if (allowMany) "候选值 ${index + 1}" else "引用值") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TextButton(onClick = { onChange(references.filterIndexed { at, _ -> at != index }) }) {
                    Text("删除")
                }
            }
            PromptReferencePicker(choices, onInsert = { selected ->
                onChange(references.mapIndexed { at, old -> if (at == index) selected else old })
            })
        }
    }

    if (allowMany || references.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.End) {
            PromptReferencePicker(choices, onInsert = { selected ->
                if (allowMany) {
                    if (selected !in references) onChange(references + selected)
                } else onChange(listOf(selected))
            })
        }
    }
}

private fun scalarUserMacroReferences(
    references: List<PromptReferenceChoice>,
): List<PromptReferenceChoice> = references.filter { choice ->
    val macro = choice.macro.trim()
    if (!macro.startsWith("{{") || !macro.endsWith("}}")) return@filter false
    val expression = macro.removePrefix("{{").removeSuffix("}}").trim()
    val base = expression.substringBefore('@').substringBefore('#')
    base == "user.name" || base == "user.prompt" ||
        listOf("character.", "pack.", "result.", "macro.", "state.", "category.")
            .any(base::startsWith)
}.distinctBy { it.macro }
