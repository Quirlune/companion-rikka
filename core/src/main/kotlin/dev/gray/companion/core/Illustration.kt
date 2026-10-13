package dev.gray.companion.core

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** The value is an editable preset. The generated scene is appended to positiveInput.value. */
@Serializable data class IllustrationInput(val name: String, val value: String)

@Serializable data class IllustrationConfig(
    val enabled: Boolean = false,
    val modelProfileId: String? = null,
    val maxImages: Int = 1,
    val concurrency: Int = 2,
    val generation: ModelCallOptions = ModelCallOptions(),
    val taskPrompt: String = "Read the last two completed user and assistant turns. Plan optional illustrations for the latest assistant BODY only; zero images is valid.",
    val guidancePrompt: String = "Write each positive scene prompt in English. Describe only the scene, character appearance, composition and action. Do not add quality tags, style scores or rendering presets; the image workflow supplies those separately. Follow the configured character appearance when present.",
    val requirementsPrompt: String = "严格只输出 XML，示例：<illustrations><image after=\"p1\"><prompt>月下街道与角色侧影</prompt></image></illustrations>。根标签只能是 illustrations；每个 image 只能有 after 属性和恰好一个非空 prompt 子标签，不能把描述直接写在 image 内。最多 {{state.illustration_max_images}} 张；after 只可使用最新助手正文中的 p1、p2 等段落编号，且不能重复。不需要插图时输出 <illustrations/>。标签外不要解释或代码围栏。",
    val contextTemplate: String = "本轮角色外观：{{state.illustration_appearance}}",
    /** Null keeps pre-block requests byte-for-byte compatible until explicitly saved in the editor. */
    val blocks: List<PromptBlock>? = null,
    val appearanceByCharacterId: Map<String, String> = emptyMap(),
    val appId: Int = 57992,
    val positiveInput: IllustrationInput = IllustrationInput("5:CLIPTextEncode.text", "masterpiece, best quality, score_9, score_8, score_7, year 2025, newest, highres, absurdres, very aesthetic, scenery, semi-realistic cel-shading, anime realism, refined lineart, realistic skin texture, realistic fabric and material rendering, cinematic figure photography, soft natural lighting, subtle depth of field, vintage film look, film grain, coarse grain texture, slightly muted colors, atmospheric image, high detail, 2.5D anime character in a photorealistic environment"),
    val negativeInput: IllustrationInput = IllustrationInput("6:CLIPTextEncode.text", "lworst quality, low quality, score_1, score_2, score_3, artist name,\nThis image suffers from low resolution and poor quality, appearing pixelated, blurry, and full of compression or jpeg artifacts."),
    val seedInput: IllustrationInput = IllustrationInput("9:KSampler.seed", "-1"),
    val stepsInput: IllustrationInput = IllustrationInput("9:KSampler.steps", "25"),
    val cfgInput: IllustrationInput = IllustrationInput("9:KSampler.cfg", "6.5"),
    val widthInput: IllustrationInput = IllustrationInput("8:EmptyLatentImage.width", "1024"),
    val heightInput: IllustrationInput = IllustrationInput("8:EmptyLatentImage.height", "1280")
)

/** Display annotation, independent of session execution and BODY storage. */
@Serializable data class IllustrationImage(
    val sessionId: String, val messageId: String, val sourceKey: String,
    val afterParagraphId: String, val localPath: String,
    val width: Int? = null, val height: Int? = null
)

data class IllustrationSource(val sessionId: String, val messageId: String, val revisionId: String, val key: String)
data class IllustrationParagraph(val id: String, val text: String)
data class IllustrationPrompt(val afterParagraphId: String, val prompt: String)
data class IllustrationPlan(
    val config: IllustrationConfig, val profile: ModelProfile, val messages: List<ChatMessage>,
    val source: IllustrationSource, val paragraphs: List<IllustrationParagraph>
)

/** Deterministic planner: no network, storage mutation, or model call. */
object IllustrationCore {
    const val MAX_IMAGES = 4
    const val MAX_XML_BYTES = 64 * 1024
    const val MAX_PROMPT_CHARS = 2_000
    private const val MAX_TEMPLATE_CHARS = 16 * 1024
    private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,})")
    private val mappingName = Regex("[0-9]+:[A-Za-z][A-Za-z0-9_.]*")

    const val HISTORY_MACRO = "{{history}}"

    /** Editor-only migration: the old fields remain serialized, while every request part becomes visible. */
    fun editableConfig(config: IllustrationConfig): IllustrationConfig = if (config.blocks != null) config else
        config.copy(blocks = listOf(
            PromptBlock(id = "illustration-instructions", title = "规划任务、引导与输出要求",
                template = listOf(config.taskPrompt, config.guidancePrompt, config.requirementsPrompt)
                    .filter { it.isNotBlank() }.joinToString("\n\n")),
            PromptBlock(id = "illustration-history", title = "最近两轮正文历史", template = HISTORY_MACRO),
            PromptBlock(id = "illustration-context", title = "本轮角色外观", role = Role.USER,
                template = config.contextTemplate),
        ))

    fun editingNode(config: IllustrationConfig): WorkflowNode = WorkflowNode(
        id = "illustration-planner", title = "插图规划", blocks = config.blocks.orEmpty(),
        inputTemplate = "", includeHistory = false, explicitHistory = true, recordTrajectory = false)

    fun validate(config: IllustrationConfig): List<Issue> = buildList {
        fun check(valid: Boolean, path: String, message: String) { if (!valid) add(Issue("illustration.$path", message)) }
        check(config.maxImages in 1..MAX_IMAGES, "maxImages", "每条正文插图上限须在 1–4 张之间")
        check(config.concurrency in 1..2, "concurrency", "并发数须为 1 或 2")
        check(config.appId > 0, "appId", "应用 ID 须为正整数")
        if (config.enabled) check(!config.modelProfileId.isNullOrBlank() || !config.generation.profileId.isNullOrBlank(),
            "modelProfileId", "启用插图须选择规划模型")
        listOf("taskPrompt" to config.taskPrompt, "guidancePrompt" to config.guidancePrompt,
            "requirementsPrompt" to config.requirementsPrompt, "contextTemplate" to config.contextTemplate).forEach { (name, text) ->
            check(text.length <= MAX_TEMPLATE_CHARS, name, "提示词超过 16 KiB 字符")
        }
        if (config.enabled) {
            if (config.blocks == null) {
                check(config.taskPrompt.isNotBlank(), "taskPrompt", "任务提示词不能为空")
                check(config.requirementsPrompt.isNotBlank(), "requirementsPrompt", "输出要求不能为空")
            } else check(config.blocks.any { it.enabled && it.template.isNotBlank() }, "blocks", "至少启用一条非空提示词")
        }
        config.blocks?.let { blocks ->
            check(blocks.map { it.id }.distinct().size == blocks.size, "blocks", "提示词块标识不能重复")
            blocks.forEach { block ->
                val path = "blocks.${block.id}"
                // The migrated instruction block may contain three previously independent fields.
                check(block.template.length <= MAX_TEMPLATE_CHARS * 3 + 4, path, "提示词块超过 48 KiB 字符")
                val scan = MacroTemplateTokens.scan(block.template)
                scan.diagnostics.forEach { add(Issue("illustration.$path", it.message)) }
                scan.tokens.filter { it.closed }.forEach { token ->
                    macroExpressionError(token.expression.trim(), ExecutionSemantics.CURRENT)?.let {
                        add(Issue("illustration.$path", it))
                    }
                }
                DefaultPromptSlots.blockIssues(block, "illustration.$path", ExecutionSemantics.CURRENT).forEach(::add)
                TextProcessing.validate(block.processing, "illustration.$path.processing").forEach(::add)
                RecognitionEvaluator.validate(block.recognition, "illustration.$path.recognition").forEach(::add)
                block.condition?.expression?.takeIf { it.isNotBlank() }?.let { expression ->
                    ConditionExpressions.validate(expression)?.let { add(Issue("illustration.$path.condition", it)) }
                }
                check(block.depth >= 0, "$path.depth", "插入深度不能小于零")
                if (containsStructuredHistoryToken(block.template) && !isStructuredHistoryBlock(block.template))
                    add(Issue("illustration.$path", "结构化 history 须单独占一个提示块；混合文本请使用 history.text"))
            }
        }
        config.appearanceByCharacterId.forEach { (id, text) ->
            check(id.isNotBlank() && text.length <= MAX_TEMPLATE_CHARS, "appearanceByCharacterId", "角色外观条目无效或过长")
        }
        val inputs = listOf("positiveInput" to config.positiveInput, "negativeInput" to config.negativeInput,
            "seedInput" to config.seedInput, "stepsInput" to config.stepsInput, "cfgInput" to config.cfgInput,
            "widthInput" to config.widthInput, "heightInput" to config.heightInput)
        inputs.forEach { (field, input) ->
            check(mappingName.matches(input.name), "$field.name", "输入名须为节点编号:输入名")
            check(input.value.length <= MAX_TEMPLATE_CHARS, "$field.value", "输入默认值过长")
        }
        check(inputs.map { it.second.name }.distinct().size == inputs.size, "inputs", "输入名不能重复")
        check(config.seedInput.value.toLongOrNull()?.let { it == -1L || it >= 0 } == true,
            "seedInput.value", "种子须为 -1 或非负整数")
        check(config.stepsInput.value.toIntOrNull()?.let { it > 0 } == true, "stepsInput.value", "步数须为正整数")
        check(config.cfgInput.value.toDoubleOrNull()?.let { it > 0 && it.isFinite() } == true,
            "cfgInput.value", "CFG 须为正数")
        check(config.widthInput.value.toIntOrNull()?.let { it > 0 } == true, "widthInput.value", "宽度须为正整数")
        check(config.heightInput.value.toIntOrNull()?.let { it > 0 } == true, "heightInput.value", "高度须为正整数")
    }

    /** The active revision ID and exact BODY text both participate; no trim or normalization. */
    fun sourceKey(message: ChatMessage): String = CompletedWorlds.fingerprint(buildString {
        val revision = message.activeTextRevisionId ?: message.id
        append(revision.length).append(':').append(revision)
        append('|').append(message.text.length).append(':').append(message.text)
    })

    fun isCurrentSource(session: Session, source: IllustrationSource): Boolean =
        session.id == source.sessionId && session.messages.any { message ->
            message.id == source.messageId && !message.deleted && message.outputSlot == OutputSlot.BODY &&
                message.role == Role.ASSISTANT && (message.activeTextRevisionId ?: message.id) == source.revisionId &&
                sourceKey(message) == source.key
        }

    fun isCurrentSource(session: Session, messageId: String, key: String): Boolean =
        session.messages.any { message -> message.id == messageId && !message.deleted &&
            message.role == Role.ASSISTANT && message.outputSlot == OutputSlot.BODY && sourceKey(message) == key }

    /** Blank lines split Markdown paragraphs, except while a fenced code block is open. */
    fun paragraphs(body: String): List<IllustrationParagraph> {
        val pieces = mutableListOf<String>()
        val buffer = mutableListOf<String>()
        var fence: Char? = null
        var fenceLength = 0
        fun flush() {
            if (buffer.isNotEmpty()) {
                val text = buffer.joinToString("\n").trim('\r', '\n')
                if (text.isNotBlank()) pieces += text
                buffer.clear()
            }
        }
        body.split('\n').forEach { raw ->
            val line = raw.removeSuffix("\r")
            val fenceMatch = fenceStart.find(line)
            val marker = fenceMatch?.groupValues?.get(1)
            if (line.isBlank() && fence == null) { flush(); return@forEach }
            buffer += line
            if (marker != null) {
                if (fence == null) { fence = marker[0]; fenceLength = marker.length }
                else if (marker[0] == fence && marker.length >= fenceLength &&
                    line.substring(fenceMatch!!.range.last + 1).isBlank()) { fence = null; fenceLength = 0 }
            }
        }
        flush()
        return pieces.mapIndexed { index, text -> IllustrationParagraph("p${index + 1}", text) }
    }

    fun prepare(library: Library, session: Session, accepted: ChatMessage): IllustrationPlan? {
        val config = library.illustration
        if (!config.enabled) return null
        require(validate(config).none { it.error }) { "插图配置无效" }
        val effective = CompletedWorlds.executionLibrary(library, session)
        val main = effective.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return null
        val primary = DefaultCallRules.primary(main) ?: return null
        val latest = session.messages.lastOrNull { !it.deleted && it.outputSlot == OutputSlot.BODY &&
            (it.role == Role.USER || it.role == Role.ASSISTANT &&
                (it.workflowId == primary.id || it.workflowId == null)) } ?: return null
        if (latest.id != accepted.id || latest.role != Role.ASSISTANT || latest.text.isBlank() ||
            latest.workflowId != null && latest.workflowId != primary.id) return null
        val source = IllustrationSource(session.id, latest.id, latest.activeTextRevisionId ?: latest.id, sourceKey(latest))
        if (sourceKey(accepted) != source.key) return null
        val profileId = config.modelProfileId ?: config.generation.profileId ?: return null
        val base = ModelCatalog.resolveProfile(library, profileId) ?: return null
        val profile = base.copy(temperature = config.generation.temperature, topP = config.generation.topP,
            maxTokens = config.generation.maxTokens, reasoning = config.generation.reasoning,
            customBody = emptyList(), legacyGeneration = false)
        val paragraphs = paragraphs(latest.text)
        if (paragraphs.isEmpty()) return null
        val turns = completedTurns(session, primary.id).takeLast(2)
        if (turns.lastOrNull()?.second?.lastOrNull()?.id != latest.id) return null
        val appearance = config.appearanceByCharacterId[session.characterId].orEmpty()
        val markedBody = paragraphs.joinToString("\n\n") { "[${it.id}] ${it.text}" }
        val local = session.copy(state = session.state + mapOf(
            "illustration_body" to latest.text,
            "illustration_appearance" to appearance,
            "illustration_paragraphs" to markedBody,
            "illustration_max_images" to config.maxImages.toString()))
        val messages = if (config.blocks == null) {
            // Existing saved configurations keep their exact historical request shape.
            val resolver = MacroResolver(library, local, latest.text)
            val system = listOf(config.taskPrompt, config.guidancePrompt, config.requirementsPrompt)
                .map(resolver::render).filter { it.isNotBlank() }.joinToString("\n\n")
            val context = resolver.render(config.contextTemplate)
            require(resolver.issues.none { it.error }) { resolver.issues.filter { it.error }.joinToString("；") { it.message } }
            buildList {
                add(ChatMessage(role = Role.SYSTEM, text = system))
                turns.forEach { (user, assistants) ->
                    add(ChatMessage(role = Role.USER, text = user.text))
                    assistants.forEach { assistant ->
                        add(ChatMessage(role = Role.ASSISTANT,
                            text = if (assistant.id == latest.id) markedBody else assistant.text))
                    }
                }
                if (context.isNotBlank()) add(ChatMessage(role = Role.USER, text = context))
            }
        } else {
            // Compile the same two BODY turns through the shared block engine. An explicit
            // history block owns their location, so no separate history is silently appended.
            val contextMessages = turns.flatMap { (user, assistants) -> listOf(user) + assistants }.map { message ->
                message.copy(text = if (message.id == latest.id) markedBody else message.text,
                    rawText = null, reasoningSnapshot = null, textRevisions = emptyList(), bodyDisplay = null)
            }
            val contextSession = local.copy(messages = contextMessages, promptInjections = emptyList(),
                historyMemory = HistoryMemoryState(), historyDigests = emptyMap(), turnRecords = emptyList(),
                pendingRun = null, executableWorld = null, inactivePaths = emptyList())
            val contextMain = main.copy(history = HistoryConfig())
            val contextLibrary = library.copy(memoryConfig = GlobalMemoryConfig(enabled = false),
                mainCalls = library.mainCalls.filterNot { it.id == main.id } + contextMain,
                sessions = listOf(contextSession), selectedSessionId = contextSession.id)
            val preview = PromptCompiler().preview(contextLibrary, contextSession, editingNode(config),
                input = latest.text, currentUserMessageId = null)
            require(preview.issues.none { it.error }) {
                preview.issues.filter { it.error }.joinToString("；") { it.message }
            }
            require(preview.messages.any { it.text.isNotBlank() }) { "插图规划提示词为空" }
            preview.messages
        }
        return IllustrationPlan(config, profile, messages, source, paragraphs)
    }

    private fun completedTurns(session: Session, primaryWorkflowId: String): List<Pair<ChatMessage, List<ChatMessage>>> {
        val turns = mutableListOf<Pair<ChatMessage, List<ChatMessage>>>()
        var user: ChatMessage? = null
        val assistants = mutableListOf<ChatMessage>()
        fun finish() {
            user?.let { if (assistants.isNotEmpty()) turns += it to listOf(assistants.last()) }
            assistants.clear()
        }
        session.messages.forEach { message ->
            if (message.deleted || message.authoredOpening || message.outputSlot != OutputSlot.BODY) return@forEach
            when (message.role) {
                Role.USER -> { finish(); user = message }
                Role.ASSISTANT -> if (user != null && message.text.isNotBlank() &&
                    (message.workflowId == primaryWorkflowId || message.workflowId == null) &&
                    (message.turnId == null || message.turnId == user?.id)) assistants += message
                else -> Unit
            }
        }
        finish()
        return turns
    }

    /** Strict XML with exact anchors; no DTD, nested markup, comments or trailing prose. */
    fun parseXml(text: String, paragraphs: List<IllustrationParagraph>, maxImages: Int): List<IllustrationPrompt> {
        require(maxImages in 1..MAX_IMAGES) { "插图数量上限无效" }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_XML_BYTES) { "插图规划回复过长" }
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true) && !text.contains("<!--")) {
            "插图 XML 不支持 DTD、实体声明或注释"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = try { factory.newDocumentBuilder().parse(InputSource(StringReader(text))) }
            catch (failure: Exception) { throw IllegalArgumentException("插图 XML 格式无效", failure) }
        require(document.doctype == null && document.documentElement?.tagName == "illustrations" &&
            document.documentElement.attributes.length == 0) { "插图 XML 根标签须为 <illustrations>" }
        val anchors = paragraphs.map { it.id }.toSet()
        val used = mutableSetOf<String>()
        val prompts = mutableListOf<IllustrationPrompt>()
        children(document.documentElement).forEach { image ->
            require(image.nodeType == Node.ELEMENT_NODE && image.nodeName == "image" &&
                image.attributes.length == 1 && image.attributes.getNamedItem("after") != null) { "插图只允许带 after 的 <image>" }
            val after = image.attributes.getNamedItem("after").nodeValue
            require(after in anchors && used.add(after)) { "插图段落编号无效或重复" }
            val parts = children(image)
            require(parts.size == 1 && parts[0].nodeType == Node.ELEMENT_NODE && parts[0].nodeName == "prompt" &&
                parts[0].attributes.length == 0) { "每张插图须有且仅有一个 <prompt>" }
            val prompt = parts[0]
            require((0 until prompt.childNodes.length).all { index ->
                prompt.childNodes.item(index).nodeType == Node.TEXT_NODE
            }) { "插图描述不能嵌套标签" }
            val value = prompt.textContent.trim()
            require(value.isNotEmpty() && value.length <= MAX_PROMPT_CHARS) { "插图描述为空或过长" }
            prompts += IllustrationPrompt(after, value)
            require(prompts.size <= maxImages) { "插图数量超过上限" }
        }
        return prompts
    }

    private fun children(node: Node): List<Node> = (0 until node.childNodes.length).map { node.childNodes.item(it) }
        .filterNot { it.nodeType == Node.TEXT_NODE && it.nodeValue.isNullOrBlank() }
}

/** Compatibility spelling for the background runtime; IllustrationCore is the canonical API. */
object IllustrationPlanner {
    fun plan(library: Library, session: Session, accepted: ChatMessage) = IllustrationCore.prepare(library, session, accepted)
    fun prepare(library: Library, session: Session, accepted: ChatMessage) = IllustrationCore.prepare(library, session, accepted)
    fun parseXml(text: String, paragraphs: List<IllustrationParagraph>, maxImages: Int) = IllustrationCore.parseXml(text, paragraphs, maxImages)
    fun sourceKey(message: ChatMessage) = IllustrationCore.sourceKey(message)
    fun isCurrentSource(session: Session, source: IllustrationSource) = IllustrationCore.isCurrentSource(session, source)
    fun paragraphs(body: String) = IllustrationCore.paragraphs(body)
}
