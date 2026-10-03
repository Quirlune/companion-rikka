package dev.gray.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.gray.companion.core.ReasoningParsingConfig
import dev.gray.companion.core.ReasoningParsingMode

@Composable
internal fun ReasoningParsingSettingsPage(
    baseline: ReasoningParsingConfig,
    onSave: (ReasoningParsingConfig) -> Unit,
    onBack: () -> Unit,
    saving: Boolean,
) {
    var config by remember(baseline) { mutableStateOf(baseline) }
    EditorPage("思考解析", onBack, { onSave(config) }, dirty = config != baseline, saving = saving) {
        Text("原生 API 思考会直接显示；下面仅控制模型把思考写在正文 XML 中时如何分离。思考内容不会进入两种 History。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReasoningParsingFields(config) { config = it }
    }
}

@Composable
internal fun ReasoningParsingFields(config: ReasoningParsingConfig, onChange: (ReasoningParsingConfig) -> Unit) {
    ChoiceLine("解析方式", config.mode, listOf(
        ReasoningParsingMode.OFF to "仅原生 API 思考",
        ReasoningParsingMode.TAGGED to "XML 起始与结束标签",
        ReasoningParsingMode.START_UNTIL_END to "从响应开头到结束标签",
    )) { onChange(config.copy(mode = it)) }
    when (config.mode) {
        ReasoningParsingMode.OFF -> Unit
        ReasoningParsingMode.TAGGED -> {
            TextFieldLine("思考起始标签", config.startTag, { onChange(config.copy(startTag = it)) })
            TextFieldLine("思考结束标签", config.endTag, { onChange(config.copy(endTag = it)) })
        }
        ReasoningParsingMode.START_UNTIL_END -> {
            Text("模型响应从第一个字符开始视为思考，遇到结束标签后才显示正文。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextFieldLine("思考结束标签", config.endTag, { onChange(config.copy(endTag = it)) })
        }
    }
}
