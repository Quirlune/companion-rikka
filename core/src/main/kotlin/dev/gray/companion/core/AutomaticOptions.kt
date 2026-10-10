package dev.gray.companion.core

import com.google.re2j.Pattern
import kotlinx.serialization.Serializable

@Serializable data class AutomaticOptionSpec(val contentTag: String = "option", val titleTag: String = "title")
@Serializable enum class AutomaticOptionsLayout { ABOVE_HORIZONTAL, LEFT_VERTICAL }
data class AutomaticOption(val content: String, val title: String? = null)

/** Global auxiliary UI preferences. Generated choices never become session/runtime data. */
@Serializable data class AutomaticOptionsConfig(
    val enabled: Boolean = false,
    val modelProfileId: String? = null,
    val count: Int = 4,
    val prompt: String = "",
    val blocks: List<PromptBlock>? = null,
    val generation: ModelCallOptions = ModelCallOptions(),
    val reasoningParsing: ReasoningParsingConfig? = null,
    val inputTemplate: String = "",
    val inputProcessing: List<TextProcessStep> = emptyList(),
    val outputProcessing: List<TextProcessStep> = emptyList(),
    val historyTurns: Int = 5,
    val titlesEnabled: Boolean = false,
    val items: List<AutomaticOptionSpec> = emptyList(),
    val layout: AutomaticOptionsLayout = AutomaticOptionsLayout.ABOVE_HORIZONTAL,
)

data class AutomaticOptionsTurn(val user: ChatMessage, val assistants: List<ChatMessage>)
data class AutomaticOptionsSource(val key: String, val turns: List<AutomaticOptionsTurn>)
data class AutomaticOptionsRequest(
    val profile: ModelProfile,
    val messages: List<ChatMessage>,
    val source: AutomaticOptionsSource,
    val contextKey: String = source.key,
    val processOutput: (String) -> String = { it },
)

object AutomaticOptions {
    const val MAX_COUNT = 12
    const val MAX_PROMPT_CHARS = 16384
    const val MAX_RESPONSE_BYTES = 64 * 1024
    const val MAX_OPTION_CHARS = 2000
    const val PROTOCOL_MACRO = "{{state.automatic_options_protocol}}"
    const val MAX_TITLE_CHARS = 120
    const val COUNT_MACRO = "{{state.automatic_options_count}}"
    const val LEGACY_STYLE_MACRO = "{{state.automatic_options_legacy_style}}"
    const val HISTORY_MACRO = "{{type:builtin.in:history.name:messages.source:display}}"
    private val options = Pattern.compile("(?s)<option>([^<>]*)</option>")

    fun effectiveItems(config: AutomaticOptionsConfig): List<AutomaticOptionSpec> =
        (0 until config.count.coerceIn(1, MAX_COUNT)).map { config.items.getOrNull(it) ?: AutomaticOptionSpec() }

    fun protocol(config: AutomaticOptionsConfig): String = buildString {
        if (config.items.isEmpty() && !config.titlesEnabled) {
            append("只输出恰好 ${config.count} 个 <option>回复文字</option>，各项独立且非空。每项最多 $MAX_OPTION_CHARS 字符；选项文字不用尖括号或嵌套标签。标签外不得有解释、序号、Markdown 代码围栏或其他内容。")
            return@buildString
        }
        append("只输出以下 ").append(config.count).append(" 项，不同名称按标签匹配；相同标签按出现顺序分配。标签外只能有空白。\n")
        effectiveItems(config).forEachIndexed { index, item ->
            if (config.titlesEnabled) append("<${item.titleTag}>第${index + 1}项标题</${item.titleTag}>")
            append("<${item.contentTag}>第${index + 1}项回复内容</${item.contentTag}>\n")
        }
        append("内容非空，每项最多 $MAX_OPTION_CHARS 字符。")
        if (config.titlesEnabled) append("标题非空，最多 $MAX_TITLE_CHARS 字符。")
        append("只使用纯文本，不嵌套标签，不带属性；正文中的 &、<、> 使用 XML 转义。不要解释、序号或代码围栏。")
    }

    /** Explicit editor action: never rewrite existing custom blocks while loading. */
    fun withCurrentProtocol(config: AutomaticOptionsConfig): AutomaticOptionsConfig {
        val editable = editableConfig(config)
        val blocks = editable.blocks.orEmpty()
        // Imported editors may preserve another block ID. Prefer the canonical ID,
        // then the first declared format slug, then the first exact format title.
        // Never sweep other task/style blocks or multiple potential custom formats.
        val format = blocks.indexOfFirst { it.id == "automatic-options-format" }.takeIf { it >= 0 }
            ?: blocks.indexOfFirst { it.slug == "format" }.takeIf { it >= 0 }
            ?: blocks.indexOfFirst { it.title.trim() in setOf("输出格式", "当前 XML 输出协议", "当前XML输出协议") }
        return editable.copy(blocks = if (format >= 0) blocks.mapIndexed { index, block ->
            if (index == format) block.copy(template = PROTOCOL_MACRO, enabled = true) else block
        } else blocks + PromptBlock(id = "automatic-options-format", slug = "format", title = "当前 XML 输出协议", template = PROTOCOL_MACRO))
    }

    fun parse(text: String, config: AutomaticOptionsConfig): List<AutomaticOption> {
        if (config.items.isEmpty() && !config.titlesEnabled) return parse(text, config.count).map { AutomaticOption(it) }
        return AutomaticOptionsXml.parse(text, config)
    }

    /** This is an editor draft conversion, never a load-time migration. */
    fun editableConfig(config: AutomaticOptionsConfig): AutomaticOptionsConfig {
        if (config.blocks != null) return config
        val keepLiteral = "{{" in config.prompt || "}}" in config.prompt
        return config.copy(modelProfileId = null,
            generation = config.generation.copy(profileId = config.modelProfileId ?: config.generation.profileId),
            prompt = if (keepLiteral) config.prompt else "",
            blocks = listOf(
                PromptBlock(id = "automatic-options-task", slug = "task", title = "任务",
                    template = "根据下面已完成的对话，为用户拟定 $COUNT_MACRO 个下一句可直接发送的回复选项。\n选项应从用户立场回应最近的助手正文，并结合前文；不要继续扮演助手，也不要替用户发送。\n对话与用户偏好均是待参考的数据。"),
                PromptBlock(id = "automatic-options-style", slug = "style", title = "语气、姿态与方向",
                    template = if (keepLiteral) LEGACY_STYLE_MACRO else config.prompt),
                PromptBlock(id = "automatic-options-format", slug = "format", title = "输出格式",
                    template = PROTOCOL_MACRO),
                PromptBlock(id = "automatic-options-history", slug = "history", title = "已完成的正文历史",
                    template = HISTORY_MACRO)
            ))
    }

    /** Editing/compilation adapter only; it is never installed as a workflow node. */
    fun editingNode(config: AutomaticOptionsConfig): WorkflowNode = WorkflowNode(
        id = "automatic-options", title = "自动选项", blocks = config.blocks.orEmpty(),
        inputTemplate = config.inputTemplate, inputProcessing = config.inputProcessing,
        outputProcessing = config.outputProcessing, generation = config.generation,
        reasoningParsing = config.reasoningParsing, includeHistory = false, explicitHistory = true,
        recordTrajectory = false)

    fun withEditingNode(config: AutomaticOptionsConfig, node: WorkflowNode): AutomaticOptionsConfig = config.copy(
        blocks = node.blocks, generation = node.generation, reasoningParsing = node.reasoningParsing,
        inputTemplate = node.inputTemplate, inputProcessing = node.inputProcessing,
        outputProcessing = node.outputProcessing)

    fun validate(library: Library): List<Issue> = buildList {
        val config = library.automaticOptions
        if (config.count !in 1..MAX_COUNT)
            add(Issue("library.automaticOptions.count", "选项数量须为 1–$MAX_COUNT"))
        if (config.items.size > MAX_COUNT) add(Issue("library.automaticOptions.items", "最多配置 $MAX_COUNT 个选项"))
        effectiveItems(config).forEachIndexed { index, item ->
            if (!AutomaticOptionsXml.validTag(item.contentTag)) add(Issue("library.automaticOptions.items.$index.contentTag", "内容标签须为有效简单 XML 名称"))
            if (config.titlesEnabled && !AutomaticOptionsXml.validTag(item.titleTag)) add(Issue("library.automaticOptions.items.$index.titleTag", "标题标签须为有效简单 XML 名称"))
        }
        if (config.historyTurns !in 1..50)
            add(Issue("library.automaticOptions.historyTurns", "历史范围须为 1–50 个完整对话轮次"))
        if (config.prompt.length > MAX_PROMPT_CHARS)
            add(Issue("library.automaticOptions.prompt", "选项提示词不能超过 $MAX_PROMPT_CHARS 字符"))
        if (config.blocks == null && config.modelProfileId != null && library.profiles.none { it.id == config.modelProfileId })
            add(Issue("library.automaticOptions.modelProfileId", "选项模型不存在"))
        if (config.blocks != null) {
            val path = "library.automaticOptions"
            val generation = config.generation
            if (generation.profileId != null && library.profiles.none { it.id == generation.profileId })
                add(Issue("$path.generation.profileId", "选项模型不存在"))
            generation.temperature?.let { if (!it.isFinite()) add(Issue("$path.generation.temperature", "温度必须是有限数字")) }
            generation.topP?.let { if (!it.isFinite() || it !in 0f..1f) add(Issue("$path.generation.topP", "Top P 必须在 0 到 1 之间")) }
            generation.maxTokens?.let { if (it <= 0) add(Issue("$path.generation.maxTokens", "最大令牌数必须大于零")) }
            config.reasoningParsing?.validationError()?.let { add(Issue("$path.reasoningParsing", it)) }
            fun template(text: String, at: String) {
                if (text.length > MAX_PROMPT_CHARS) add(Issue(at, "提示词不能超过 $MAX_PROMPT_CHARS 字符"))
                val scan = MacroTemplateTokens.scan(text)
                scan.diagnostics.forEach { add(Issue(at, it.message)) }
                scan.tokens.filter { it.closed }.forEach { token ->
                    macroExpressionError(token.expression.trim(), ExecutionSemantics.CURRENT)?.let { add(Issue(at, it)) }
                }
            }
            template(config.inputTemplate, "$path.inputTemplate")
            addAll(TextProcessing.validate(config.inputProcessing, "$path.inputProcessing"))
            addAll(TextProcessing.validate(config.outputProcessing, "$path.outputProcessing"))
            if (config.blocks.map { it.id }.distinct().size != config.blocks.size)
                add(Issue("$path.blocks", "提示词块标识不能重复"))
            config.blocks.forEach { block ->
                val at = "$path.blocks.${block.id}"
                template(block.template, at)
                addAll(DefaultPromptSlots.blockIssues(block, at, ExecutionSemantics.CURRENT))
                addAll(TextProcessing.validate(block.processing, "$at.processing"))
                addAll(RecognitionEvaluator.validate(block.recognition, "$at.recognition"))
                block.condition?.expression?.takeIf { it.isNotBlank() }?.let { expression ->
                    ConditionExpressions.validate(expression)?.let { add(Issue("$at.condition", it)) }
                }
                if (block.depth < 0) add(Issue("$at.depth", "插入深度不能小于零"))
                if (containsStructuredHistoryToken(block.template) && !isStructuredHistoryBlock(block.template))
                    add(Issue(at, "结构化 history 须单独占一个提示块；混合文本请使用 history.text"))
            }
        }
    }

    /** Only committed main BODY floors in the selected branch count as conversation turns. */
    fun source(library: Library, session: Session): AutomaticOptionsSource? {
        val effective = CompletedWorlds.executionLibrary(library, session)
        val main = effective.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return null
        val primary = DefaultCallRules.primary(main) ?: return null
        val turns = mutableListOf<AutomaticOptionsTurn>()
        var user: ChatMessage? = null
        val replies = mutableListOf<ChatMessage>()
        fun finish() {
            user?.let { if (replies.isNotEmpty()) turns += AutomaticOptionsTurn(it, replies.toList()) }
            replies.clear()
        }
        for (message in session.messages) {
            if (message.deleted || message.authoredOpening || message.outputSlot != OutputSlot.BODY) continue
            if (message.role == Role.USER) {
                finish()
                user = message
            } else if (message.role == Role.ASSISTANT && message.text.isNotBlank() && user != null &&
                (message.turnId == user?.id || message.turnId == null) &&
                (message.workflowId == primary.id || message.workflowId == null)) {
                replies += message
            }
        }
        finish()
        // A newly appended unanswered user must not receive choices for an earlier round.
        val latestUser = user ?: return null
        if (turns.lastOrNull()?.user?.id != latestUser.id) return null
        val selected = turns.takeLast(library.automaticOptions.historyTurns.coerceIn(1, 50))
        val refs = selected.flatMap { listOf(it.user) + it.assistants }.map(HistoryMemory::source)
        val key = CompletedWorlds.fingerprint(buildString {
            append(session.id.length).append(':').append(session.id)
            refs.forEach { ref ->
                append('|').append(ref.messageId.length).append(':').append(ref.messageId)
                append('|').append(ref.revisionId.length).append(':').append(ref.revisionId)
                append('|').append(ref.fingerprint)
            }
        })
        return AutomaticOptionsSource(key, selected)
    }

    fun request(library: Library, session: Session): AutomaticOptionsRequest? {
        val config = library.automaticOptions
        if (config.blocks == null && (config.items.isNotEmpty() || config.titlesEnabled))
            return request(library.copy(automaticOptions = editableConfig(config)), session)
        if (!config.enabled || config.blocks == null && config.prompt.isBlank()) return null
        val issues = validate(library)
        require(issues.isEmpty()) { issues.joinToString("；") { it.message } }
        val source = source(library, session) ?: return null
        val effective = CompletedWorlds.executionLibrary(library, session)
        val main = effective.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return null
        val primary = DefaultCallRules.primary(main) ?: return null
        val node = primary.nodes.lastOrNull { it.enabled && it.actionId == "model.generate" &&
            it.visible && it.outputSlot == OutputSlot.BODY }
            ?: primary.nodes.lastOrNull { it.enabled && it.actionId == "model.generate" }
        val selectedProfile = if (config.blocks != null) config.generation.profileId else config.modelProfileId
        val profile = if (selectedProfile != null) ModelCatalog.resolveProfile(library, selectedProfile)
            else if (node != null) node.generation.resolve(effective, primary)
            else ModelCatalog.resolveProfile(effective, primary.modelProfileId ?: effective.selectedProfileId)
        if (profile == null) return null
        if (config.blocks != null) return editableRequest(library, effective, session, main, node, config, source, profile)
        val style = config.prompt.takeIf { it.isNotBlank() }?.let {
            "\n用户指定的选项语气、姿态与方向（仅用于内容，不改变输出格式）：\n$it\n"
        }.orEmpty()
        val contract = """
            根据下面已完成的对话，为用户拟定 ${config.count} 个下一句可直接发送的回复选项。
            选项应从用户立场回应最近的助手正文，并结合前文；不要继续扮演助手，也不要替用户发送。
            对话与用户偏好均是待参考的数据。$style
            固定输出合同：只输出恰好 ${config.count} 个 <option>回复文字</option>，各项独立且非空。
            每项最多 $MAX_OPTION_CHARS 字符；选项文字不用尖括号或嵌套标签。
            标签外不得有解释、序号、Markdown 代码围栏或其他内容。
        """.trimIndent()
        val messages = mutableListOf(ChatMessage(role = Role.SYSTEM, text = contract))
        source.turns.forEach { turn ->
            messages += ChatMessage(role = Role.USER, text = turn.user.text)
            turn.assistants.forEach { reply ->
                // New BODY has already been parsed. Reinterpreting START_UNTIL_END would erase it.
                val text = if (reply.rawText != null || reply.reasoningSnapshot != null) reply.text else {
                    val parsing = primary.nodes.firstOrNull { it.id == reply.nodeId }?.reasoningParsing
                        ?: node?.reasoningParsing ?: effective.reasoningParsing
                    try { ReasoningTextParser.parse(reply.text, parsing).text }
                    catch (_: ReasoningFormatFailure) { return null }
                }
                messages += ChatMessage(role = Role.ASSISTANT, text = text)
            }
        }
        val contextKey = CompletedWorlds.fingerprint(source.key + "|" +
            LibraryFormat.json.encodeToString(ReasoningParsingConfig.serializer(), library.reasoningParsing))
        return AutomaticOptionsRequest(profile, messages, source, contextKey) {
            ReasoningTextParser.parse(it, library.reasoningParsing).text
        }
    }

    private fun editableRequest(library: Library, effective: Library, session: Session, main: MainCall,
        mainNode: WorkflowNode?, config: AutomaticOptionsConfig, source: AutomaticOptionsSource,
        inheritedProfile: ModelProfile): AutomaticOptionsRequest {
        val messages = source.turns.flatMap { turn -> listOf(turn.user) + turn.assistants }.map { message ->
            val body = if (message.role != Role.ASSISTANT || message.rawText != null || message.reasoningSnapshot != null) message.text
                else ReasoningTextParser.parse(message.text,
                    DefaultCallRules.primary(main)?.nodes?.firstOrNull { it.id == message.nodeId }?.reasoningParsing
                        ?: mainNode?.reasoningParsing ?: effective.reasoningParsing).text
            // The auxiliary window contains DISPLAY BODY only, even if a custom reference asks for model raw.
            message.copy(text = body, rawText = null, reasoningSnapshot = null, textRevisions = emptyList(), bodyDisplay = null)
        }
        val localAddresses = setOf("state.automatic_options_count", "state.automatic_options_legacy_style", "state.automatic_options_protocol")
        val contextSession = session.copy(messages = messages, promptInjections = emptyList(),
            historyMemory = HistoryMemoryState(), historyDigests = emptyMap(), turnRecords = emptyList(),
            pendingRun = null, executableWorld = null, inactivePaths = emptyList(),
            macroHistory = session.macroHistory - localAddresses, currentOnlyMacros = session.currentOnlyMacros - localAddresses,
            state = session.state + mapOf("automatic_options_count" to config.count.toString(),
                "automatic_options_legacy_style" to config.prompt, "automatic_options_protocol" to protocol(config)))
        // This read-only projection never schedules a history processor. Avoid randomly generated
        // default workflow/node IDs changing the identity of an otherwise identical request.
        val contextMain = main.copy(history = HistoryConfig(processor = HistoryProcessor(workflow = Workflow(
            id = "automatic-options-history-disabled", trigger = RuntimeEvents.HISTORY_PROCESS,
            enabled = false, nodes = emptyList()))))
        val contextLibrary = effective.copy(mainCalls = effective.mainCalls.map { if (it.id == main.id) contextMain else it },
            sessions = listOf(contextSession), selectedSessionId = contextSession.id)
        val preview = PromptCompiler().preview(contextLibrary, contextSession, editingNode(config),
            input = "", currentUserMessageId = null)
        require(preview.issues.none { it.error }) { preview.issues.filter { it.error }.joinToString("；") { it.message } }
        require(preview.messages.any { it.text.isNotBlank() }) { "自动选项提示词为空；请添加内容或历史引用" }
        val generation = config.generation
        val profile = inheritedProfile.copy(temperature = generation.temperature ?: inheritedProfile.temperature,
            topP = generation.topP ?: inheritedProfile.topP, maxTokens = generation.maxTokens ?: inheritedProfile.maxTokens,
            reasoning = generation.reasoning ?: inheritedProfile.reasoning)
        val processingIssues = mutableListOf<Issue>()
        val history = HistoryContext(contextLibrary, contextSession, contextMain)
        val resources = loadedResources(contextLibrary, contextSession, contextSession.state, input = "", issues = processingIssues,
            historyContext = history)
        val renderer = TemplateRenderer(resources, "", contextSession.results, contextSession.state, processingIssues,
            contextSession.resultOrigins, contextSession, history)
        val parsing = config.reasoningParsing ?: library.reasoningParsing
        val contextKey = CompletedWorlds.fingerprint(buildString {
            fun field(value: String) { append('|').append(value.length).append(':').append(value) }
            append(source.key)
            preview.messages.forEach { append('|').append(it.role).append(':').append(it.text.length).append(':').append(it.text) }
            field(LibraryFormat.json.encodeToString(ReasoningParsingConfig.serializer(), parsing))
            // Output-only reads are just as much a part of the frozen request as prompt reads.
            // Resolve their values with the native renderer, including nested resource templates.
            config.outputProcessing.forEach { step ->
                MacroTemplateTokens.scan(TextProcessing.macroTemplate(step)).tokens.filter { it.closed }.forEach { token ->
                    val address = token.expression.trim()
                    field(address)
                    field(renderer.read(address, "automaticOptions.outputProcessing"))
                }
            }
            processingIssues.forEach { field(it.toString()) }
            if (config.outputProcessing.any { it.kind == TextProcessKind.EXPRESSION }) {
                field(expressionContextKey(contextLibrary, contextSession))
            }
        })
        return AutomaticOptionsRequest(profile, preview.messages, source, contextKey) { output ->
            val body = ReasoningTextParser.parse(output, parsing).text
            val processed = TextProcessing.apply(config.outputProcessing, body, contextSession.state, contextSession.results) {
                renderer.read(it, "automaticOptions.outputProcessing")
            }
            require(processingIssues.none { it.error }) { processingIssues.filter { it.error }.joinToString("；") { it.message } }
            processed
        }
    }

    /** Expressions may short-circuit on the future model output: retain potential reads in every branch.
     * The session is already BODY-bounded; omit presentation bookkeeping and other chats/transport settings. */
    private fun expressionContextKey(library: Library, session: Session): String {
        val context = session.copy(title = "", updatedAt = 0L, isPinned = false,
            titlePending = false, cardPlacements = emptyList(), inactivePaths = emptyList(), folderId = null)
        val resources = Library(characters = library.characters, mainCalls = library.mainCalls,
            packages = library.packages, groups = library.groups, user = library.user,
            personas = library.personas, selectedPersonaId = library.selectedPersonaId,
            promptTags = library.promptTags, extensions = library.extensions, memoryConfig = library.memoryConfig)
        return CompletedWorlds.fingerprint(LibraryFormat.json.encodeToString(Session.serializer(), context) + "|" +
            LibraryFormat.json.encodeToString(Library.serializer(), resources))
    }

    /** Call after the shared ReasoningTextParser has removed configured thought content. */
    fun parse(text: String, count: Int): List<String> {
        require(count in 1..MAX_COUNT) { "选项数量无效" }
        require(text.length <= MAX_RESPONSE_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "选项回复超过 64 KiB"
        }
        val matcher = options.matcher(text)
        val result = mutableListOf<String>()
        var end = 0
        while (matcher.find()) {
            require(text.substring(end, matcher.start()).isBlank()) { "选项标签外存在额外内容" }
            require(result.size < count) { "返回的选项数量过多" }
            val option = matcher.group(1).trim()
            require(option.isNotEmpty()) { "选项不能为空" }
            require(option.length <= MAX_OPTION_CHARS) { "单个选项超过 $MAX_OPTION_CHARS 字符" }
            result += option
            end = matcher.end()
        }
        require(text.substring(end).isBlank()) { "选项格式无效或标签外存在额外内容" }
        require(result.size == count) { "返回的选项数量不符，应为 $count 个" }
        return result
    }
}
