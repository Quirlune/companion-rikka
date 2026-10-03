package dev.gray.companion.core

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

private val structuredHistoryToken = Regex("\\{\\{\\s*history\\s*\\}\\}")
internal fun isStructuredHistoryBlock(template: String): Boolean =
    structuredHistoryToken.matches(template.trim())
internal fun containsStructuredHistoryToken(template: String): Boolean =
    structuredHistoryToken.containsMatchIn(template)

internal class LoadedResources(
    val character: CharacterCard?,
    val packages: List<PromptPackage>,
    val groups: List<CallGroup>,
    private val entrySources: List<Pair<String, PromptEntry>>,
    val resultDeclarations: List<ResultDeclaration>,
    val macroView: MacroReadView,
    private val activate: (String, PromptEntry) -> PromptEntry?
) {
    val entries: List<Pair<String, PromptEntry>> get() = entrySources.mapNotNull { (source, entry) ->
        activate(source, entry)?.let { source to it }
    }
    fun entry(source: String, key: String): PromptEntry? = entrySources.firstOrNull {
        it.first == source && it.second.key == key
    }?.let { activate(it.first, it.second) }
    fun category(category: String): List<PromptEntry> = entrySources.filter { it.second.category == category }
        .mapNotNull { activate(it.first, it.second) }
}

internal fun loadedResources(
    library: Library,
    session: Session,
    state: Map<String, String>,
    results: Map<String, String> = session.results,
    input: String = "",
    issues: MutableList<Issue>? = null,
    resultOrigins: Map<String, ResultOrigin> = session.resultOrigins,
    historyContext: HistoryContext? = null
): LoadedResources {
    val view = MacroReadView(library, session)
    val character = view.character
    val groups = view.groups
    val packages = view.packages
    val activationIssues = issues ?: mutableListOf()
    val resolved = mutableMapOf<String, PromptEntry?>()
    val resolving = linkedSetOf<String>()
    val invalid = mutableSetOf<String>()
    lateinit var renderer: TemplateRenderer
    fun active(source: String, entry: PromptEntry): PromptEntry? {
        val address = "$source.${entry.key}"
        if (address in resolved) return resolved[address]
        val path = if (source == "character") "character.${character?.id}.entry.${entry.id}"
            else "pack.${packages.firstOrNull { "pack.${it.namespace}" == source }?.id}.entry.${entry.id}"
        if (!resolving.add(address)) {
            invalid += resolving
            activationIssues += Issue("$path.condition.expression", "条目启用条件循环引用：$address")
            return null
        }
        val version = view.selectVersion(address, "latest")
        val enabled = version?.enabled ?: (entry.enabled && checkedCondition(entry.condition, path, state, results, input,
            activationIssues) { renderer.read(it, "$path.condition.expression") })
        resolving.remove(address)
        val value = if (!enabled || address in invalid) null else if (version == null) entry else entry.copy(text = version.text)
        resolved[address] = value
        return value
    }
    val entries = buildList {
        character?.entries?.sortedBy { it.order }?.forEach { add("character" to it) }
        packages.forEach { pack -> pack.entries.sortedBy { it.order }.forEach { add("pack.${pack.namespace}" to it) } }
    }
    val resources = LoadedResources(character, packages, groups, entries, ResultReferences.declarations(library, session), view, ::active)
    renderer = TemplateRenderer(resources, input, results, state, activationIssues, resultOrigins, session, historyContext)
    resources.entries // Resolve and validate each source once, including sources unused by a prompt.
    return resources
}

internal fun checkedCondition(
    condition: Condition?, path: String, state: Map<String, String>,
    results: Map<String, String>, input: String, issues: MutableList<Issue>?,
    readMacro: ((String) -> String?)? = null
): Boolean {
    condition?.expression?.takeIf { it.isNotBlank() }?.let { expression ->
        ConditionExpressions.validate(expression)?.let { message ->
            issues?.add(Issue("$path.condition.expression", message))
            return false
        }
        return try {
            ConditionExpressions.matchesStrict(expression, state, results, input, readMacro)
        } catch (_: RuntimeException) {
            issues?.add(Issue("$path.condition.expression", "条件表达式计算失败"))
            false
        }
    }
    return conditionMatches(condition, state, results, input, readMacro)
}

internal fun conditionMatches(
    condition: Condition?, state: Map<String, String>,
    results: Map<String, String> = emptyMap(), input: String = "",
    readMacro: ((String) -> String?)? = null
): Boolean {
    if (condition == null) return true
    if (condition.expression.isNotBlank()) return ConditionExpressions.matches(condition.expression, state, results, input, readMacro)
    if (condition.key.isBlank() && condition.value.isBlank()) return true
    val actual = state[condition.key]
    if (condition.op == Compare.EXISTS) return !actual.isNullOrEmpty()
    if (actual == null) return false
    return when (condition.op) {
        Compare.EQUALS -> actual == condition.value
        Compare.NOT_EQUALS -> actual != condition.value
        Compare.GREATER, Compare.GREATER_OR_EQUAL, Compare.LESS, Compare.LESS_OR_EQUAL -> {
            val a = actual.toDoubleOrNull() ?: return false
            val b = condition.value.toDoubleOrNull() ?: return false
            when (condition.op) {
                Compare.GREATER -> a > b
                Compare.GREATER_OR_EQUAL -> a >= b
                Compare.LESS -> a < b
                else -> a <= b
            }
        }
        Compare.EXISTS -> true
    }
}

internal class TemplateRenderer(
    private val resources: LoadedResources,
    private val input: String,
    private val results: Map<String, String>,
    private val state: Map<String, String>,
    private val issues: MutableList<Issue>,
    private val resultOrigins: Map<String, ResultOrigin> = emptyMap(),
    session: Session? = null,
    historyContext: HistoryContext? = null
) {
    private val token = Regex("\\{\\{([^{}]*)\\}\\}")
    private val currentSession = session ?: resources.macroView.session
    private val macroView = session?.takeUnless { it === resources.macroView.session }?.let {
        MacroReadView(resources.macroView.library, it)
    } ?: resources.macroView
    private val history = historyContext ?: run {
        resources.macroView.library.mainCalls.firstOrNull { it.id == currentSession.mainCallId }?.let {
            HistoryContext(resources.macroView.library, currentSession, it)
        }
    }
    private var renderingHistory = false
    private val entryCache = mutableMapOf<String, String>()
    private val entryStack = linkedSetOf<String>()

    fun read(address: String, path: String): String {
        macroExpressionError(address)?.let { issues.add(Issue(path, it)); return "" }
        return resolve(address, path)
    }

    fun render(template: String, path: String, hideWhenAllReferencesEmpty: Boolean = false): String {
        val remainder = token.replace(template, "")
        if (remainder.contains("{{") || remainder.contains("}}")) issues.add(Issue(path, "宏格式不完整"))
        var hadReference = false
        var hadValue = false
        val rendered = token.replace(template) { match ->
            hadReference = true
            val expression = match.groupValues[1].trim()
            val value = resolve(expression, path)
            if (value.isNotEmpty()) hadValue = true
            value
        }
        return if (hideWhenAllReferencesEmpty && hadReference && !hadValue) "" else rendered
    }

    private fun resolve(expression: String, path: String): String {
        val tagged = expression.substringBefore('#')
        val base = tagged.substringBefore('@')
        val parts = base.split('.')
        val selector = expression.substringAfter('#', "")
        if (base == "history" || base.startsWith("history.")) {
            val key = if (base == "history") "text" else base.substringAfter("history.")
            if ('@' in tagged || '#' in expression) {
                issues.add(Issue(path, "历史视图不支持版本或 XML 选择器：$expression"))
                return ""
            }
            val value = if (renderingHistory) history?.read(key) else {
                renderingHistory = true
                try { history?.read(key) { insertion -> render(insertion, "history.insertion") } }
                finally { renderingHistory = false }
            }
            return value ?: run {
                issues.add(Issue(path, "未知历史视图：$expression"))
                ""
            }
        }
        if ('@' in tagged || base.startsWith("macro.")) {
            val address = canonicalWriteAddress(base)
            val versionSelector = tagged.substringAfter('@', "latest")
            if (address == null || !validVersionSelector(versionSelector)) {
                issues.add(Issue(path, "宏地址或版本选择无效：$expression"))
                return ""
            }
            val version = macroView.selectVersion(address, versionSelector)
            val value = if (address.startsWith("state.") && versionSelector == "latest")
                state[address.substring(6)]?.takeIf { version?.enabled != false }
                else if (version != null) version.text.takeIf { version.enabled } else if (versionSelector == "latest")
                unversioned(address) else null
            if (value == null) {
                issues.add(Issue(path, "引用的宏版本不存在或已屏蔽：$expression", false))
                return ""
            }
            return if (selector.isEmpty()) value else if (address.startsWith("character.") || address.startsWith("pack."))
                selectXml(value, selector, path) else {
                    issues.add(Issue(path, "此宏不支持 XML 选择器：$expression"))
                    ""
                }
        }
        val value = when {
            expression == "input" -> input
            expression == "user.name" -> macroView.library.userName(macroView.session)
            expression == "user.prompt" -> macroView.library.userPrompt(macroView.session)
            base.startsWith("state.") && base.length > 6 -> {
                val version = macroView.selectVersion(base, "latest")
                state[base.substring(6)]?.takeIf { version?.enabled != false }
            }
            base.startsWith("result.") && base.length > 7 -> unversioned(base)
            base.startsWith("results.") && base.length > 8 -> ResultReferences.category(
                base.substring(8), resources.resultDeclarations, results, resultOrigins,
                { key -> macroView.selectVersion("result.$key", "latest")?.enabled != false })
            base.startsWith("category.") && base.length > 9 -> resources.entries
                .filter { it.second.category == base.substring(9) }
                .joinToString("\n") { (source, entry) -> entryText("$source.${entry.key}", entry) }
            parts.size == 2 && parts[0] == "character" && parts[1] == "name" -> unversioned(base)
            parts.size == 2 && parts[0] == "character" && parts[1].isNotBlank() -> characterText(parts[1])
            parts.size == 3 && parts[0] == "pack" && parts[1].isNotBlank() && parts[2].isNotBlank() -> unversioned(base)
            else -> {
                issues.add(Issue(path, "未知宏：$expression"))
                return ""
            }
        }
        if (value == null) {
            issues.add(Issue(path, "引用为空：$expression", false))
            return ""
        }
        if (selector.isEmpty()) return value
        if (parts[0] !in setOf("character", "pack", "category")) {
            issues.add(Issue(path, "此宏不支持 XML 选择器：$expression"))
            return ""
        }
        return selectXml(value, selector, path)
    }

    private fun unversioned(address: String): String? = when {
        address.startsWith("result.") -> {
            val key = address.substring(7)
            if (key in currentSession.unavailableResultKeys || key in currentSession.pendingRun?.unavailableKeys.orEmpty() ||
                !macroView.originVisible(resultOrigins[key]) || macroView.selectVersion(address, "latest")?.enabled == false) null else results[key]
        }
        address == "character.name" -> {
            val version = macroView.selectVersion(address, "latest")
            if (version == null) resources.character?.name else version.text.takeIf { version.enabled }
        }
        address.startsWith("character.") -> characterText(address.substring(10))
        address.startsWith("pack.") -> {
            val parts = address.split('.')
            val version = macroView.selectVersion(address, "latest")
            if (version != null) version.text.takeIf { version.enabled } else resources.entry("pack.${parts[1]}", parts[2])
                ?.let { entryText(address, it) }
        }
        else -> null
    }

    private fun characterText(key: String): String? {
        val version = macroView.selectVersion("character.$key", "latest")
        if (version != null) return version.text.takeIf { version.enabled }
        val direct = resources.entry("character", key)
        if (direct != null) return entryText("character.$key", direct)
        // An explicit legacy entry keeps its activation semantics, even when currently inactive.
        if (resources.character?.entries?.any { it.key == key } == true) return null
        val canonical = CharacterFields.canonicalKey(key)
        return resources.entry("character", canonical)?.let { entryText("character.$canonical", it) }
    }

    private fun entryText(address: String, entry: PromptEntry): String {
        // Published versions are snapshots; their text is never interpreted as a template.
        if (!entry.isTemplate || macroView.selectVersion(address, "latest") != null) return entry.text
        entryCache[address]?.let { return it }
        if (entryStack.size >= 16 || !entryStack.add(address)) {
            issues += Issue("entry.$address", "条目模板循环引用或嵌套过深")
            return ""
        }
        return try {
            render(entry.text, "entry.$address").also { entryCache[address] = it }
        } finally {
            entryStack.remove(address)
        }
    }

    private fun selectXml(content: String, selector: String, path: String): String {
        val segments = selector.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty() || segments.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_.:-]*")) }) {
            issues.add(Issue(path, "XML 路径无效：$selector"))
            return ""
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
                issues.add(Issue(path, "XML 不允许声明实体"))
                return ""
            }
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                XMLConstants.FEATURE_SECURE_PROCESSING to true
            ).forEach { (feature, enabled) -> try { factory.setFeature(feature, enabled) } catch (_: Exception) { } }
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
            })
            val document = builder.parse(InputSource(StringReader("<root>$content</root>")))
            var nodes = listOf(document.documentElement as org.w3c.dom.Node)
            segments.forEach { segment ->
                nodes = nodes.flatMap { parent -> (0 until parent.childNodes.length).map { parent.childNodes.item(it) } }
                    .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE && it.nodeName == segment }
            }
            if (nodes.isEmpty()) issues.add(Issue(path, "XML 节点为空：$selector", false))
            nodes.joinToString("\n") { it.textContent }
        } catch (_: Exception) {
            issues.add(Issue(path, "XML 内容无法解析"))
            ""
        }
    }
}

class PromptCompiler {
    fun preview(
        library: Library,
        session: Session,
        node: WorkflowNode,
        input: String,
        results: Map<String, String> = session.results,
        state: Map<String, String> = session.state,
        currentUserMessageId: String? = session.messages.lastOrNull { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }?.id,
        resultOrigins: Map<String, ResultOrigin> = session.resultOrigins,
        historyContext: HistoryContext? = null,
        preprocessedInput: String? = null
    ): PromptPreview {
        val issues = mutableListOf<Issue>()
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val capturedHistory = historyContext ?: main?.let { HistoryContext(library, session, it) }
        val resources = loadedResources(library, session, state, results, input, issues, resultOrigins, capturedHistory)
        val renderer = TemplateRenderer(resources, input, results, state, issues, resultOrigins, session, capturedHistory)
        checkedCondition(node.condition, "node.${node.id}", state, results, input, issues) {
            renderer.read(it, "node.${node.id}.condition.expression")
        }
        val renderedInput = preprocessedInput ?: try {
            TextProcessing.apply(node.inputProcessing,
                renderer.render(node.inputTemplate, "node.${node.id}.input"), state, results) {
                renderer.read(it, "node.${node.id}.inputProcessing")
            }
        } catch (_: Exception) {
            issues += Issue("node.${node.id}.inputProcessing", "输入处理失败")
            ""
        }
        val processingHistory = capturedHistory?.isProcessing == true
        val originalHistory = if (processingHistory) capturedHistory.pendingCandidate()?.messages.orEmpty()
            else session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
        val currentIndex = originalHistory.indexOfLast { it.id == currentUserMessageId && it.role == Role.USER }
        val transformedHistory = if (!processingHistory && currentIndex >= 0) originalHistory.mapIndexed { index, message ->
            if (index == currentIndex) message.copy(text = renderedInput) else message
        } else originalHistory
        val formedMessages = session.messages.map { message ->
            if (message.id == currentUserMessageId && message.role == Role.USER && message.outputSlot == OutputSlot.BODY)
                if (processingHistory) message else message.copy(text = renderedInput) else message
        }
        val formedContext = if (processingHistory) capturedHistory else capturedHistory?.withMessages(formedMessages)
        val blockRenderer = if (formedContext == null) renderer else TemplateRenderer(
            resources, input, results, state, issues, resultOrigins, session.copy(messages = formedMessages), formedContext
        )
        val blocks = (node.blocks.map { it to false } + injectedBlocks(library, session, node).map { it to true })
            .filter { (block, _) -> block.enabled && checkedCondition(block.condition,
                "node.${node.id}.block.${block.id}", state, results, input, issues) {
                    blockRenderer.read(it, "node.${node.id}.block.${block.id}.condition.expression")
                } && checkedRecognition(block.recognition, "node.${node.id}.block.${block.id}.recognition", issues) {
                    blockRenderer.render(it, "node.${node.id}.block.${block.id}.recognition")
                } }
        val hasStructuredBlock = blocks.any { (block, resolved) -> !resolved && isStructuredHistoryBlock(block.template) }
        val usesStructuredHistory = hasStructuredBlock && formedContext != null
        val projectedHistory = if (usesStructuredHistory || node.includeHistory && !node.explicitHistory)
            formedContext?.projected { blockRenderer.render(it, "history.insertion") } ?: transformedHistory else emptyList()
        val implicitHistory = projectedHistory
        val historyContainsCurrent = formedContext?.containsMessage(currentUserMessageId) ?: (currentIndex >= 0)
        val needsInput = if (processingHistory) renderedInput.isNotBlank() else
            (!node.includeHistory || node.explicitHistory || !historyContainsCurrent) &&
                (!usesStructuredHistory || !historyContainsCurrent)
        val formedHistory = implicitHistory + if (needsInput && renderedInput.isNotBlank())
            listOf(ChatMessage(role = Role.USER, text = renderedInput)) else emptyList()
        val before = mutableListOf<ChatMessage>()
        val after = mutableListOf<ChatMessage>()
        val inserted = MutableList(formedHistory.size + 1) { mutableListOf<ChatMessage>() }
        val historyMarkerId = "structured-history:${node.id}"
        blocks.forEach { (block, resolved) ->
            val path = "node.${node.id}.block.${block.id}"
            if (block.depth < 0) issues += Issue("$path.depth", "插入深度不能小于零")
            val messages = if (!resolved && isStructuredHistoryBlock(block.template)) {
                if (usesStructuredHistory) listOf(ChatMessage(id = historyMarkerId, role = block.role)) else emptyList()
            } else {
                val source = if (resolved) block.template else blockRenderer.render(block.template, path, block.hideWhenReferencesEmpty)
                val value = try {
                    TextProcessing.apply(block.processing, source, state, results) {
                        blockRenderer.read(it, "$path.processing")
                    }
                } catch (_: Exception) {
                    issues += Issue("$path.processing", "提示块处理失败")
                    ""
                }
                if (value.isBlank()) emptyList() else listOf(ChatMessage(role = block.role, text = value))
            }
            if (messages.isEmpty()) return@forEach
            when (block.position) {
                PromptPosition.BEFORE_HISTORY -> before += messages
                PromptPosition.AFTER_HISTORY -> after += messages
                PromptPosition.IN_HISTORY -> if (messages.any { it.id == historyMarkerId }) before += messages else
                    inserted[(formedHistory.size.toLong() - block.depth.toLong())
                        .coerceIn(0L, formedHistory.size.toLong()).toInt()] += messages
            }
        }
        val historyMessages = buildList {
            formedHistory.forEachIndexed { index, message ->
                addAll(inserted[index])
                add(message)
            }
            addAll(inserted.last())
        }
        val messages = buildList {
            fun appendSection(section: List<ChatMessage>) {
                section.forEach { if (it.id == historyMarkerId) addAll(historyMessages) else add(it) }
            }
            appendSection(before)
            if (!usesStructuredHistory) addAll(historyMessages)
            appendSection(after)
        }
        return PromptPreview(messages, issues)
    }
}
