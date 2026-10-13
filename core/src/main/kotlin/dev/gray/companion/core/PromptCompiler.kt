package dev.gray.companion.core

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal fun isStructuredHistoryBlock(template: String, executionVersion: Int = ExecutionSemantics.CURRENT): Boolean {
    val text = template.trim()
    val scan = MacroTemplateTokens.scan(text, executionVersion)
    val token = scan.tokens.singleOrNull() ?: return false
    return scan.diagnostics.isEmpty() && token.closed && token.start == 0 && token.endExclusive == text.length &&
        !HistoryReferences.isText(token.expression, executionVersion) &&
        HistoryViews.selection(token.expression, executionVersion) != null
}
internal fun containsStructuredHistoryToken(template: String, executionVersion: Int = ExecutionSemantics.CURRENT): Boolean =
    MacroTemplateTokens.scan(template, executionVersion).tokens.any { it.closed && it.expression.trim() == "history" }

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
    historyContext: HistoryContext? = null,
    executionVersion: Int = ExecutionSemantics.CURRENT
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
    renderer = TemplateRenderer(resources, input, results, state, activationIssues, resultOrigins, session, historyContext, executionVersion)
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
    historyContext: HistoryContext? = null,
    private val executionVersion: Int = ExecutionSemantics.CURRENT,
    private val memoryScope: MemoryPromptScope? = null,
    private val onResultRead: ((String, String) -> Unit)? = null,
    private val onHistoryRead: ((List<String>) -> Unit)? = null
) {
    private val semantics = ExecutionSemantics(executionVersion)
    private val currentSession = session ?: resources.macroView.session
    private val macroView = session?.takeUnless { it === resources.macroView.session }?.let {
        MacroReadView(resources.macroView.library, it)
    } ?: resources.macroView
    private val history = historyContext ?: run {
        resources.macroView.library.mainCalls.firstOrNull { it.id == currentSession.mainCallId }?.let {
            HistoryContext(resources.macroView.library, currentSession, it, executionVersion = executionVersion)
        }
    }
    private var renderingHistory = false
    private val entryCache = mutableMapOf<String, String>()
    private val entryStack = linkedSetOf<String>()
    private var trackingHistory = false

    fun <T> captureHistoryReads(block: () -> T): T {
        val previous = trackingHistory
        trackingHistory = true
        if (!previous && onHistoryRead != null) entryCache.clear()
        return try { block() } finally { trackingHistory = previous }
    }

    private fun recordHistory(ids: List<String>) { if (trackingHistory) onHistoryRead?.invoke(ids) }

    fun read(address: String, path: String): String {
        macroExpressionError(address, executionVersion)?.let { issues.add(Issue(path, it)); return "" }
        return resolve(address, path)
    }

    fun render(template: String, path: String, hideWhenAllReferencesEmpty: Boolean = false): String =
        renderBlock(template, path, hideWhenAllReferencesEmpty).orEmpty()

    /** Null omits the entire block, before any processing can recreate a heading or wrapper. */
    fun renderBlock(template: String, path: String, hideWhenAllReferencesEmpty: Boolean = false): String? {
        val scan = MacroTemplateTokens.scan(template, executionVersion)
        scan.diagnostics.forEach { issues += Issue(path, it.message) }
        var hadReference = false
        var hadValue = false
        val rendered = MacroTemplateTokens.replace(template, scan) { token ->
            hadReference = true
            val expression = token.expression.trim()
            val value = resolve(expression, path)
            if (if (semantics.omitEmptyReferencesBeforeProcessing) value.isNotBlank() else value.isNotEmpty()) hadValue = true
            value
        }
        return if (hideWhenAllReferencesEmpty && hadReference && !hadValue) {
            if (semantics.omitEmptyReferencesBeforeProcessing) null else ""
        } else rendered
    }

    private fun resolve(expression: String, path: String): String {
        if (Regex("^[a-zA-Z_][a-zA-Z0-9_-]*\\s*:").containsMatchIn(expression)) {
            val compiled = PromptReferenceLanguage.compile(expression)
            val plan = compiled.reference as? PromptReferenceLanguage.QueryPlan
            val selection = plan?.historySelection?.let { if (!semantics.explicitHistoryRanges) it.copy(rangeExplicit = false) else it }
            if (!semantics.historySources && compiled.fields.any { it.name == "source" }) {
                issues += Issue(path, "此恢复版本不支持 History 来源选择")
                return ""
            }
            if (!semantics.localHistoryFormats && compiled.fields.any { it.name == "format" }) {
                issues += Issue(path, "此恢复版本不支持 History 局部格式")
                return ""
            }
            if (!semantics.historySourceRoleSelection && compiled.fields.any { it.name == "speaker" }) {
                issues += Issue(path, "此恢复版本不支持 History 原说话人筛选")
                return ""
            }
            if (executionVersion < 26 && compiled.fields.any { it.name in setOf("as", "prefixes") }) {
                issues += Issue(path, "此恢复版本不支持 History 文本读取方式")
                return ""
            }
            if (selection == null) {
                if (plan == null || !semantics.staticResourceQueries) {
                    issues += Issue(path, compiled.diagnostics.firstOrNull()?.message ?: "此来源的新式引用尚未绑定运行时")
                    return ""
                }
                if (DynamicPromptQueries.accepts(plan)) {
                    if (executionVersion < 9) { issues += Issue(path, "此恢复版本不支持调用slug引用"); return "" }
                    DynamicPromptQueries.supportError(plan)?.let { issues += Issue(path, it); return "" }
                    if (onResultRead != null) {
                        val selector = when (val at = plan.at) {
                            PromptReferenceLanguage.VersionSelector.Latest -> "latest"
                            is PromptReferenceLanguage.VersionSelector.Turn -> "turn-${at.offset}"
                            is PromptReferenceLanguage.VersionSelector.Update -> "update-${at.offset}"
                            is PromptReferenceLanguage.VersionSelector.Version -> "version-${at.number}"
                        }
                        DynamicPromptQueries.targets(macroView.library, plan, currentSession).forEach {
                            onResultRead.invoke(it.address, selector)
                        }
                    }
                    val (value, diagnostic) = DynamicPromptQueries.read(plan, macroView.library, currentSession, results, resultOrigins)
                    diagnostic?.let { issues += Issue(path, it); return "" }
                    return plan.xml?.let { selectXml(value, it.joinToString("/"), path) } ?: value
                }
                StaticPromptQueries.supportError(plan)?.let { diagnostic ->
                    issues += Issue(path, diagnostic)
                    return ""
                }
                val selected = StaticPromptQueries.bind(plan, resources)
                selected.diagnostic?.let { issues += Issue(path, it); return "" }
                val content = selected.entries.joinToString("\n") { (source, entry) ->
                    entryText("$source.${entry.key}", entry)
                }
                return plan.xml?.let { selectXml(content, it.joinToString("/"), path) } ?: content
            }
            return renderHistory(selection, path)
        }
        val tagged = expression.substringBefore('#')
        val base = tagged.substringBefore('@')
        val parts = base.split('.')
        val selector = expression.substringAfter('#', "")
        if (base == "history" || base.startsWith("history.")) {
            if (memoryScope != null && base.startsWith("history.model")) {
                issues += Issue(path, "摘要仅使用展示正文来源")
                return ""
            }
            if (memoryScope != null && base !in setOf("history.display", "history.model")) {
                val key = if (base == "history") "text" else base.substringAfter("history.")
                if ('@' in tagged || '#' in expression) { issues += Issue(path, "摘要历史不支持版本选择"); return "" }
                if (key == "count" || key == "pending.count") return memoryScope.count.toString()
                if (key in setOf("raw", "text", "pending", "summary", "display.summary")) {
                    val projection = memoryScope.project()
                    issues += projection.issues.map { it.copy(path = path) }
                    recordHistory(projection.sourceIds)
                    return if (executionVersion >= 26 && key in setOf("raw", "text"))
                        HistoryReferences.plainText(projection.messages)
                    else projection.messages.joinToString("\n") { it.text }
                }
            }
            if (base in setOf("history.display.summary", "history.model.summary")) {
                if (!semantics.historySources || '@' in tagged || '#' in expression) {
                    issues += Issue(path, "此 History 摘要来源引用不受支持：$expression")
                    return ""
                }
                val main = macroView.library.mainCalls.firstOrNull { it.id == currentSession.mainCallId } ?: return ""
                val kind = if (base == "history.model.summary") HistorySourceKind.MODEL_RAW else HistorySourceKind.DISPLAY
                return HistoryViews.summaries(currentSession, main, kind, executionVersion, library = macroView.library).joinToString("\n") { HistoryViews.summaryMessage(it).text }
            }
            if (base in setOf("history.display", "history.model")) {
                val selection = HistoryViews.selection(base, executionVersion)
                if (selection == null || '@' in tagged || '#' in expression) {
                    issues += Issue(path, "此 History 来源引用不受支持：$expression")
                    return ""
                }
                return renderHistory(selection, path)
            }
            val key = if (base == "history") "text" else base.substringAfter("history.")
            if ('@' in tagged || '#' in expression) {
                issues.add(Issue(path, "历史视图不支持版本或 XML 选择器：$expression"))
                return ""
            }
            if (executionVersion >= 26 && base in setOf("history.text", "history.raw")) {
                return renderHistory(HistoryViews.selection(base, executionVersion)!!, path)
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
            if (address.startsWith("result.")) onResultRead?.invoke(address, versionSelector)
            val version = macroView.selectVersion(address, versionSelector)
            val value = if (address.startsWith("state.") && versionSelector == "latest")
                state[address.substring(6)]?.takeIf { !macroView.currentValueUnavailable(address) && version?.enabled != false }
                else if (version != null) version.text.takeIf { version.enabled } else if (versionSelector == "latest")
                unversioned(address) else null
            if (value == null) {
                issues.add(Issue(path, "引用的宏版本不存在或已屏蔽：$expression", false))
                return ""
            }
            memoryScope?.let { recordHistory(it.resultReadSourceIds(address, value, version)) }
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
                state[base.substring(6)]?.takeIf { !macroView.currentValueUnavailable(base) && version?.enabled != false }
            }
            base.startsWith("result.") && base.length > 7 -> unversioned(base)
            base.startsWith("results.") && base.length > 8 -> ResultReferences.category(
                base.substring(8), resources.resultDeclarations, results, resultOrigins,
                { key -> onResultRead?.invoke("result.$key", "latest"); !macroView.currentValueUnavailable("result.$key") &&
                    macroView.selectVersion("result.$key", "latest")?.enabled != false })
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

    private fun renderHistory(selection: HistorySelection, path: String): String {
        memoryScope?.let { scope ->
            val projection = scope.project(selection)
            issues += projection.issues.map { it.copy(path = path) }
            recordHistory(projection.sourceIds)
            return if (executionVersion >= 26 && selection.readMode == HistoryReadMode.TEXT)
            HistoryReferences.plainText(projection.messages, selection.textPrefixes) else projection.messages.joinToString("\n") { it.text }
        }
        val main = macroView.library.mainCalls.firstOrNull { it.id == currentSession.mainCallId } ?: return ""
        val projection = if (renderingHistory) HistoryViews.project(macroView.library, currentSession, main, selection,
            executionVersion = executionVersion) else {
            renderingHistory = true
            try { HistoryViews.project(macroView.library, currentSession, main, selection,
                executionVersion = executionVersion) { render(it, "history.insertion") } }
            finally { renderingHistory = false }
        }
        issues += projection.issues.map { it.copy(path = path) }
        return if (executionVersion >= 26 && selection.readMode == HistoryReadMode.TEXT)
            HistoryReferences.plainText(projection.messages, selection.textPrefixes) else projection.messages.joinToString("\n") { it.text }
    }

    private fun unversioned(address: String): String? = when {
        address.startsWith("result.") -> {
            onResultRead?.invoke(address, "latest")
            val key = address.substring(7)
            val value = if (key in currentSession.unavailableResultKeys || key in currentSession.pendingRun?.unavailableKeys.orEmpty() ||
                macroView.currentValueUnavailable(address) || !macroView.originVisible(resultOrigins[key]) ||
                macroView.selectVersion(address, "latest")?.enabled == false) null else results[key]
            memoryScope?.let { recordHistory(it.resultReadSourceIds(address, value, null)) }
            value
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
        preprocessedInput: String? = null,
        executionVersion: Int = ExecutionSemantics.CURRENT,
        memoryScope: MemoryPromptScope? = null,
        preprocessedInputSourceIds: List<String>? = null
    ): PromptPreview {
        val semantics = ExecutionSemantics(executionVersion)
        val issues = mutableListOf<Issue>()
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val capturedHistory = historyContext ?: main?.let { HistoryContext(library, session, it, executionVersion = executionVersion) }
        val resources = loadedResources(library, session, state, results, input, issues, resultOrigins, capturedHistory, executionVersion)
        val inputSourceIds = linkedSetOf<String>()
        val contentSourceIds = linkedSetOf<String>()
        val renderer = TemplateRenderer(resources, input, results, state, issues, resultOrigins, session, capturedHistory,
            executionVersion, memoryScope, onHistoryRead = { inputSourceIds += it })
        checkedCondition(node.condition, "node.${node.id}", state, results, input, issues) {
            renderer.read(it, "node.${node.id}.condition.expression")
        }
        fun renderInput() = renderer.captureHistoryReads {
            TextProcessing.apply(node.inputProcessing,
                renderer.render(node.inputTemplate, "node.${node.id}.input"), state, results, executionVersion) {
                renderer.read(it, "node.${node.id}.inputProcessing")
            }
        }
        if (preprocessedInput != null && memoryScope != null) {
            if (preprocessedInputSourceIds != null) inputSourceIds += preprocessedInputSourceIds
            else {
                // Legacy journals have no input provenance. Purely replay the complete frozen
                // transformation and accept its reads only if it reproduces the exact saved input.
                val replayed = try { renderInput() } catch (_: Exception) { null }
                if (replayed != preprocessedInput) {
                    inputSourceIds.clear()
                    issues += Issue("node.${node.id}.inputProcessing", "冻结输入无法重建，不能确认摘要历史来源")
                }
            }
        }
        val renderedInput = preprocessedInput ?: try { renderInput() } catch (_: Exception) {
            issues += Issue("node.${node.id}.inputProcessing", "输入处理失败")
            ""
        }
        val processingHistory = capturedHistory?.isProcessing == true
        val replacesCurrentInput = !processingHistory && (!semantics.preserveInputWithoutTemplate ||
            node.inputTemplate.isNotBlank() || node.inputProcessing.isNotEmpty())
        val originalHistory = if (processingHistory) capturedHistory.pendingCandidate()?.messages.orEmpty()
            else session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
        val currentIndex = originalHistory.indexOfLast { it.id == currentUserMessageId && it.role == Role.USER }
        val transformedHistory = if (replacesCurrentInput && currentIndex >= 0) originalHistory.mapIndexed { index, message ->
            if (index == currentIndex) message.copy(text = renderedInput) else message
        } else originalHistory
        val formedMessages = session.messages.map { message ->
            if (replacesCurrentInput && message.id == currentUserMessageId &&
                message.role == Role.USER && message.outputSlot == OutputSlot.BODY)
                message.copy(text = renderedInput) else message
        }
        val formedContext = if (processingHistory) capturedHistory else capturedHistory?.withMessages(formedMessages)
        val blockRenderer = TemplateRenderer(
            resources, input, results, state, issues, resultOrigins, session.copy(messages = formedMessages, inactivePaths = emptyList()), formedContext,
            executionVersion, memoryScope, onHistoryRead = { contentSourceIds += it }
        )
        if (!semantics.anchoredPromptSlots && session.promptInjections.any { it.targetBlockId != null })
            issues += Issue("node.${node.id}.injectionSlot", "此恢复版本不支持指定注入槽")
        val blocks = (node.blocks.flatMap { block ->
            if (semantics.anchoredPromptSlots && block.injectionSlot) {
                val path = "node.${node.id}.block.${block.id}"
                issues += DefaultPromptSlots.blockIssues(block, path, executionVersion)
                block.slotReferences.mapIndexed { index, reference ->
                    block.copy(id = "${block.id}:source:$index", template = reference,
                        injectionSlot = false, slotReferences = emptyList()) to false
                } + slotInjections(library, session, node, block.id, executionVersion).map { injection ->
                    // The slot owns role/position/depth/condition. Accepted injection text is literal.
                    block.copy(id = "${block.id}:injection:${injection.origin.resourceId}:${injection.id}",
                        template = injection.block.template, injectionSlot = false, slotReferences = emptyList()) to true
                }
            } else {
                if (block.injectionSlot) issues += DefaultPromptSlots.blockIssues(block, "node.${node.id}.block.${block.id}", executionVersion)
                listOf(block to false)
            }
        } + injectedBlocks(library, session, node).map { it to true })
            .filter { (block, _) -> block.enabled && checkedCondition(block.condition,
                "node.${node.id}.block.${block.id}", state, results, input, issues) {
                    blockRenderer.read(it, "node.${node.id}.block.${block.id}.condition.expression")
                } && checkedRecognition(block.recognition, "node.${node.id}.block.${block.id}.recognition", issues, executionVersion) {
                    blockRenderer.render(it, "node.${node.id}.block.${block.id}.recognition")
                } }
        // Unsupported structured references must not silently become implicit History.
        val supportedBlocks = blocks.filter { (block, resolved) ->
            val unsupportedSpeaker = !resolved && !semantics.historySourceRoleSelection &&
                MacroTemplateTokens.scan(block.template, executionVersion).tokens.any { token ->
                    token.closed && PromptReferenceLanguage.compile(token.expression).fields.any { it.name == "speaker" }
                }
            if (unsupportedSpeaker) issues += Issue("node.${node.id}.block.${block.id}",
                "此恢复版本不支持 History 原说话人筛选")
            !unsupportedSpeaker
        }
        if (supportedBlocks.size != blocks.size) return PromptPreview(emptyList(), issues)
        val structured = supportedBlocks.filter { (block, resolved) -> !resolved && isStructuredHistoryBlock(block.template, executionVersion) }
            .mapNotNull { (block, _) ->
                val selection = HistoryViews.selection(block.template, executionVersion)
                    .takeUnless { block.template.trim().removeSurrounding("{{", "}}").trim() == "history" }
                val projection = if (memoryScope != null) memoryScope.project(selection ?: HistorySelection()) else if (selection == null || main == null) HistoryProjection(
                    formedContext?.projected { blockRenderer.render(it, "history.insertion") }.orEmpty(),
                    sourceIds = formedContext?.selectedSourceIds().orEmpty()) else
                    HistoryViews.project(library, session, main, selection,
                        textOverrides = currentUserMessageId?.takeIf { replacesCurrentInput }?.let { mapOf(it to renderedInput) }.orEmpty(),
                        executionVersion = executionVersion) {
                        blockRenderer.render(it, "history.insertion")
                    }
                issues += projection.issues.map { it.copy(path = "node.${node.id}.block.${block.id}") }
                if (semantics.omitEmptyReferencesBeforeProcessing && block.hideWhenReferencesEmpty &&
                    projection.messages.none { it.text.isNotBlank() }) null else block.id to
                    if (semantics.perBlockMessageMerging && !block.mergeAdjacentMessages)
                        projection.copy(messages = projection.messages.map { it.copy(mergeAdjacentMessages = false) })
                    else projection
            }.toMap()
        val hasStructuredBlock = structured.isNotEmpty()
        val usesStructuredHistory = hasStructuredBlock && formedContext != null
        val projectedHistory = if (usesStructuredHistory) structured.values.first().messages else if (node.includeHistory && !node.explicitHistory)
            memoryScope?.project()?.also { contentSourceIds += it.sourceIds }?.messages
                ?: formedContext?.projected { blockRenderer.render(it, "history.insertion") } ?: transformedHistory else emptyList()
        val implicitHistory = projectedHistory
        val historyContainsCurrent = if (usesStructuredHistory) {
            if (semantics.historySources) structured.values.any { currentUserMessageId in it.sourceIds }
            else structured.values.flatMap { it.messages }.any { it.id == currentUserMessageId }
        }
            else formedContext?.containsMessage(currentUserMessageId) ?: (currentIndex >= 0)
        val needsInput = if (processingHistory) renderedInput.isNotBlank() else
            (!node.includeHistory || node.explicitHistory || !historyContainsCurrent) &&
                (!usesStructuredHistory || !historyContainsCurrent)
        val formedHistory = implicitHistory + if (needsInput && renderedInput.isNotBlank())
            listOf(ChatMessage(role = Role.USER, text = renderedInput)) else emptyList()
        val before = mutableListOf<ChatMessage>()
        val after = mutableListOf<ChatMessage>()
        val inserted = MutableList(formedHistory.size + 1) { mutableListOf<ChatMessage>() }
        val historyMarkerId = "structured-history:${node.id}"
        val primaryStructuredId = structured.keys.firstOrNull()
        supportedBlocks.forEach { (block, resolved) ->
            val path = "node.${node.id}.block.${block.id}"
            if (block.depth < 0) issues += Issue("$path.depth", "插入深度不能小于零")
            val messages = if (!resolved && isStructuredHistoryBlock(block.template, executionVersion)) {
                if (usesStructuredHistory && block.id in structured) listOf(ChatMessage(id = "$historyMarkerId:${block.id}", role = block.role)) else emptyList()
            } else {
                val beforeRead = contentSourceIds.toSet()
                val source = if (resolved) block.template else blockRenderer.captureHistoryReads {
                    blockRenderer.renderBlock(block.template, path, block.hideWhenReferencesEmpty)
                } ?: return@forEach
                val value = try {
                    blockRenderer.captureHistoryReads { TextProcessing.apply(block.processing, source, state, results, executionVersion) {
                        blockRenderer.read(it, "$path.processing")
                    } }
                } catch (_: Exception) {
                    issues += Issue("$path.processing", "提示块处理失败")
                    ""
                }
                if (value.isBlank()) { contentSourceIds.retainAll(beforeRead); emptyList() }
                else listOf(ChatMessage(role = block.role, text = value,
                    mergeAdjacentMessages = !semantics.perBlockMessageMerging || block.mergeAdjacentMessages))
            }
            if (messages.isEmpty()) return@forEach
            when (block.position) {
                PromptPosition.BEFORE_HISTORY -> before += messages
                PromptPosition.AFTER_HISTORY -> after += messages
                PromptPosition.IN_HISTORY -> if (messages.any { it.id.startsWith("$historyMarkerId:") }) before += messages else
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
                section.forEach { message ->
                    if (message.id == "$historyMarkerId:$primaryStructuredId") addAll(historyMessages)
                    else if (message.id.startsWith("$historyMarkerId:")) addAll(structured[message.id.substringAfter("$historyMarkerId:")]?.messages.orEmpty())
                    else add(message)
                }
            }
            appendSection(before)
            if (!usesStructuredHistory) addAll(historyMessages)
            appendSection(after)
        }
        val historyReads = if (memoryScope == null) emptyList() else {
            structured.values.filter { it.messages.any { message -> message.text.isNotBlank() } }.forEach { contentSourceIds += it.sourceIds }
            if (needsInput && renderedInput.isNotBlank()) contentSourceIds += inputSourceIds
            contentSourceIds.toList()
        }
        return PromptPreview(ModelRequestMessages.prepare(messages, executionVersion), issues, historyReads)
    }
}
