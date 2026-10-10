package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.gray.companion.CompanionModelCallDefaults
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.ModelReasoning
import kotlin.uuid.Uuid
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.ui.components.ai.ReasoningButton

/** Modal-local request defaults. Disposal on cancel cannot leak a draft into a later model save. */
internal data class ModelCallDefaultsDraft(
    val temperature: String,
    val topP: String,
    val maxTokens: String,
    val reasoning: ModelReasoning,
) {
    fun parsed(): CompanionModelCallDefaults? {
        val t = temperature.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
        val p = topP.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
        val m = maxTokens.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (temperature.isNotBlank() && (t == null || !t.isFinite()) ||
            topP.isNotBlank() && (p == null || !p.isFinite() || p !in 0f..1f) ||
            maxTokens.isNotBlank() && (m == null || m <= 0)) return null
        return CompanionModelCallDefaults(t, p, m, reasoning)
    }

    companion object {
        fun from(profile: ModelProfile?) = ModelCallDefaultsDraft(profile?.temperature?.toString().orEmpty(),
            profile?.topP?.toString().orEmpty(), profile?.maxTokens?.toString().orEmpty(), profile?.reasoning ?: ModelReasoning.OFF)
    }
}

@Composable
internal fun ModelCallDefaultsEditor(modelId: Uuid, initial: ModelProfile?, enabled: Boolean,
    drafts: MutableMap<Uuid, ModelCallDefaultsDraft>) {
    var draft by remember(modelId) { mutableStateOf(drafts[modelId] ?: ModelCallDefaultsDraft.from(initial)) }
    fun change(value: ModelCallDefaultsDraft) {
        if (enabled) { draft = value; drafts[modelId] = value }
    }
    Text("本模型的请求默认值；调用和节点仍可单独覆盖。留空时不额外指定。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(draft.temperature, { change(draft.copy(temperature = it)) }, enabled = enabled,
        label = { Text("温度（留空使用接口默认）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(draft.topP, { change(draft.copy(topP = it)) }, enabled = enabled,
        label = { Text("Top P（0–1，可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(draft.maxTokens, { change(draft.copy(maxTokens = it)) }, enabled = enabled,
        label = { Text("最大输出 token（正整数，可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("推理等级", modifier = Modifier.weight(1f))
        if (enabled) ReasoningButton(reasoningLevel = ReasoningLevel.valueOf(draft.reasoning.name), onUpdateReasoningLevel = {
            change(draft.copy(reasoning = ModelReasoning.valueOf(it.name)))
        }) else Text(draft.reasoning.name)
    }
    if (draft.parsed() == null) Text("温度须为有限数字，Top P 须在0–1之间，token须为正整数；也可留空。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
