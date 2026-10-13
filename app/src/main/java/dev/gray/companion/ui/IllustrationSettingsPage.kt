package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.gray.companion.IllustrationActivity
import dev.gray.companion.core.*

@Composable
internal fun IllustrationSettingsPage(
    library: Library,
    activity: IllustrationActivity,
    bizyAirConfigured: Boolean,
    onSave: (IllustrationConfig) -> Unit,
    onSetBizyAirCredential: (String) -> List<Issue>,
    onAcknowledgeRemoteStatus: () -> Unit = {},
    onBack: () -> Unit,
    saving: Boolean,
) {
    val baseline = remember(library.illustration) { IllustrationCore.editableConfig(library.illustration) }
    var draft by remember(baseline) { mutableStateOf(baseline) }
    var editingPrompts by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var credential by remember { mutableStateOf("") }
    var hasCredential by remember(bizyAirConfigured) { mutableStateOf(bizyAirConfigured) }
    var credentialIssues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var credentialSaved by remember { mutableStateOf(false) }
    var acknowledgeRemoteStatus by remember { mutableStateOf(false) }
    var appIdText by remember(draft.appId) { mutableStateOf(draft.appId.toString()) }

    val selectedSession = library.sessions.firstOrNull { it.id == library.selectedSessionId }
    val latestAssistant = selectedSession?.messages?.lastOrNull {
        !it.deleted && it.outputSlot == OutputSlot.BODY && it.role == Role.ASSISTANT
    }
    val currentAppearance = selectedSession?.characterId?.let(draft.appearanceByCharacterId::get).orEmpty()
    val localMacroValues = remember(latestAssistant, selectedSession, currentAppearance, draft.maxImages) {
        buildMap {
            put("state.illustration_body", latestAssistant?.text.orEmpty())
            put("state.illustration_appearance", currentAppearance)
            put("state.illustration_paragraphs", latestAssistant?.text?.let(IllustrationCore::paragraphs)
                ?.joinToString("\n\n") { "[${it.id}] ${it.text}" }.orEmpty())
            put("state.illustration_max_images", draft.maxImages.toString())
        }
    }
    val references = remember(library) {
        promptReferenceChoices(emptyList(), library) + listOf(
            PromptReferenceChoice("最新助手正文", "{{state.illustration_body}}", "插图上下文", "最新已完成的 BODY 正文"),
            PromptReferenceChoice("正文段落", "{{state.illustration_paragraphs}}", "插图上下文", "带 p1、p2 等锚点编号的正文段落"),
            PromptReferenceChoice("当前角色外观", "{{state.illustration_appearance}}", "插图上下文", "按当前聊天角色读取外观描述"),
            PromptReferenceChoice("单条正文插图上限", "{{state.illustration_max_images}}", "插图上下文", "当前配置的最大插图数量"),
        )
    }
    val previewMacro: (String) -> PromptMacroPreview = { token ->
        val address = token.trim().removeSurrounding("{{", "}}").trim()
        if (address in localMacroValues) {
            val value = localMacroValues[address].orEmpty()
            when {
                address == "state.illustration_body" && latestAssistant == null ->
                    PromptMacroPreview("", unavailableReason = "当前聊天还没有助手正文", sourceLabel = "插图上下文")
                address == "state.illustration_appearance" && value.isBlank() ->
                    PromptMacroPreview("", unavailableReason = "当前角色尚未填写外观描述", sourceLabel = "插图上下文")
                else -> PromptMacroPreview(value, sourceLabel = "插图上下文")
            }
        } else resolvePromptMacroPreview(token, library, selectedSession)
    }
    val validation = remember(draft, appIdText) {
        IllustrationCore.validate(draft.copy(appId = appIdText.toIntOrNull() ?: -1))
    }

    fun save() {
        val candidate = draft.copy(appId = appIdText.toIntOrNull() ?: -1)
        val issues = IllustrationCore.validate(candidate)
        if (issues.none { it.error }) onSave(candidate)
    }

    CompositionLocalProvider(LocalPromptMacroPreview provides previewMacro) {
        if (editingPrompts) {
            PromptBlocksEditor(initial = draft.blocks.orEmpty(), references = references,
                onSave = { draft = draft.copy(blocks = it); editingPrompts = false },
                onBack = { editingPrompts = false }, title = "插图规划提示词", saving = saving,
                tags = library.promptTags)
            return@CompositionLocalProvider
        }
        EditorPage(
            title = "插图",
            onBack = onBack,
            onSave = ::save,
            dirty = draft != baseline || appIdText != baseline.appId.toString(),
            saving = saving,
            scrollKey = section,
            navigationContent = { EditorSectionTabs(listOf("规划", "工作流", "BizyAir"), section) { section = it } },
        ) {
            if (activity.planning > 0 || activity.queued > 0 || activity.running > 0 || activity.congested) {
                EditorSection("当前任务") {
                    Text("规划中 ${activity.planning} · 排队 ${activity.queued} · 生成中 ${activity.running}")
                    if (activity.congested) Text("插图任务较多，后续请求会继续排队。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            activity.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (activity.uncertainDelivery) {
                Text("最近一次图片提交状态尚未确认。请先检查 BizyAir，并确认没有仍在运行的任务。", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { acknowledgeRemoteStatus = true }, enabled = !saving) { Text("已确认没有未完成任务") }
            }
            val errors = validation.filter { it.error }.distinctBy { it.path to it.message }
            errors.forEach { issue -> Text("${issue.path}: ${issue.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            when (section) {
                0 -> {
                    SharedModelGenerationEditor(
                        options = draft.generation.copy(profileId = draft.modelProfileId ?: draft.generation.profileId),
                        reasoningParsing = null,
                        profiles = library.profiles,
                        modelLabel = "规划模型",
                        onChange = { generation, _ -> draft = draft.copy(
                            modelProfileId = generation.profileId,
                            generation = generation,
                        ) },
                    )
                    ChoiceLine("每条正文最多生成", draft.maxImages,
                        (1..IllustrationCore.MAX_IMAGES).map { it to "$it 张" }) { draft = draft.copy(maxImages = it) }
                    ChoiceLine("并发生成数", draft.concurrency, listOf(1 to "1 个任务", 2 to "2 个任务")) {
                        draft = draft.copy(concurrency = it)
                    }
                    EditorDestinationRow("完整提示词", "${draft.blocks.orEmpty().size} 个提示词块") { editingPrompts = true }
                }
                1 -> {
                    OutlinedTextField(appIdText, { appIdText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("BizyAir 应用 ID") }, enabled = !saving)
                    IllustrationInputEditor("正向提示词", draft.positiveInput, !saving) { draft = draft.copy(positiveInput = it) }
                    IllustrationInputEditor("负向提示词", draft.negativeInput, !saving) { draft = draft.copy(negativeInput = it) }
                    IllustrationInputEditor("种子", draft.seedInput, !saving) { draft = draft.copy(seedInput = it) }
                    IllustrationInputEditor("采样步数", draft.stepsInput, !saving) { draft = draft.copy(stepsInput = it) }
                    IllustrationInputEditor("CFG", draft.cfgInput, !saving) { draft = draft.copy(cfgInput = it) }
                    IllustrationInputEditor("宽度", draft.widthInput, !saving) { draft = draft.copy(widthInput = it) }
                    IllustrationInputEditor("高度", draft.heightInput, !saving) { draft = draft.copy(heightInput = it) }
                }
                else -> {
                    Text("BizyAir 密钥保存在系统安全存储中，不会写入配置库。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (hasCredential) "密钥已配置" else "尚未配置密钥", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(credential, { credential = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("BizyAir API 密钥") }, singleLine = true, enabled = !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !saving && credential.isNotBlank(), onClick = {
                            credentialIssues = onSetBizyAirCredential(credential)
                            credentialSaved = credentialIssues.none { it.error }
                            if (credentialSaved) { credential = ""; hasCredential = true }
                        }) { Text("保存密钥") }
                        if (hasCredential) OutlinedButton(enabled = !saving, onClick = {
                            credentialIssues = onSetBizyAirCredential("")
                            credentialSaved = credentialIssues.none { it.error }
                            if (credentialSaved) { credential = ""; hasCredential = false }
                        }) { Text("删除密钥") }
                    }
                    if (credentialSaved) Text("安全存储已更新。", color = MaterialTheme.colorScheme.primary)
                    credentialIssues.forEach { issue -> Text(issue.message, color = if (issue.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    val knownIds = library.characters.map { it.id }.toSet()
                    library.characters.forEach { character ->
                        key(character.id) {
                            AppearanceEditor(character.name.ifBlank { "未命名角色" },
                                draft.appearanceByCharacterId[character.id].orEmpty(), !saving,
                                onChange = { value -> draft = draft.withAppearance(character.id, value) },
                                onRemove = { draft = draft.copy(appearanceByCharacterId = draft.appearanceByCharacterId - character.id) })
                        }
                    }
                    draft.appearanceByCharacterId.filterKeys { it !in knownIds }.forEach { (id, value) ->
                        key(id) {
                            AppearanceEditor("已删除角色 · $id", value, !saving,
                                onChange = { next -> draft = draft.withAppearance(id, next) },
                                onRemove = { draft = draft.copy(appearanceByCharacterId = draft.appearanceByCharacterId - id) })
                        }
                    }
                }
            }
        }
    }
    if (acknowledgeRemoteStatus) AlertDialog(
        onDismissRequest = { acknowledgeRemoteStatus = false },
        title = { Text("确认远端任务状态") },
        text = { Text("只有在 BizyAir 中确认没有仍在运行的插图任务后，才继续释放本地提交暂停。继续中的请求可能产生额外费用。") },
        confirmButton = { TextButton(onClick = { acknowledgeRemoteStatus = false; onAcknowledgeRemoteStatus() }) { Text("我已确认") } },
        dismissButton = { TextButton(onClick = { acknowledgeRemoteStatus = false }) { Text("返回检查") } },
    )
}

private fun IllustrationConfig.withAppearance(characterId: String, value: String): IllustrationConfig {
    val entries = appearanceByCharacterId.toMutableMap()
    if (value.isBlank()) entries.remove(characterId) else entries[characterId] = value
    return copy(appearanceByCharacterId = entries)
}

@Composable
private fun IllustrationInputEditor(label: String, input: IllustrationInput, enabled: Boolean, onChange: (IllustrationInput) -> Unit) {
    EditorSection(label, input.name) {
        OutlinedTextField(input.name, { onChange(input.copy(name = it)) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("节点输入名") }, enabled = enabled)
        OutlinedTextField(input.value, { onChange(input.copy(value = it)) }, modifier = Modifier.fillMaxWidth(),
            minLines = if (label.contains("提示词")) 2 else 1, label = { Text("默认值") }, enabled = enabled)
    }
}

@Composable
private fun AppearanceEditor(
    title: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    EditorSection(title, value.ifBlank { "未设置" }) {
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("外观描述") }, enabled = enabled)
        TextButton(onClick = onRemove, enabled = enabled) { Text("移除外观描述") }
    }
}
