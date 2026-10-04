package dev.gray.companion.core

private val versionSelectorPattern = Regex("latest|(?:turn|update|version)-[0-9]+")
internal fun validVersionSelector(value: String): Boolean = versionSelectorPattern.matches(value)

/** The same address syntax is used by templates and read-only trigger lookups. */
internal fun macroExpressionError(expression: String, executionVersion: Int = ExecutionSemantics.CURRENT): String? {
    if (Regex("^[a-zA-Z_][a-zA-Z0-9_-]*\\s*:").containsMatchIn(expression.trim())) {
        val compiled = PromptReferenceLanguage.compile(expression)
        if (!ExecutionSemantics(executionVersion).localHistoryFormats && compiled.fields.any { it.name == "format" }) return "此恢复版本不支持 History 局部格式"
        return compiled.diagnostics.firstOrNull()?.message ?: if ((compiled.reference as? PromptReferenceLanguage.QueryPlan)?.historySelection != null)
            null else "此来源的新式引用尚未绑定运行时；目前仅支持 builtin history messages"
    }
    val tagged = expression.substringBefore('#')
    val base = tagged.substringBefore('@')
    val parts = base.split('.')
    val valid = base == "input" || base in setOf("user.name", "user.prompt", "history", "history.raw", "history.text",
        "history.pending", "history.summary", "history.count", "history.pending.count",
        "history.display", "history.model", "history.display.summary", "history.model.summary") ||
        (parts.size == 2 && parts[0] == "character" && parts[1].isNotBlank()) ||
        (parts[0] in setOf("category", "result", "results", "state", "macro") && base.substringAfter('.', "").isNotBlank()) ||
        (parts.size == 3 && parts[0] == "pack" && parts[1].isNotBlank() && parts[2].isNotBlank())
    if (!valid || expression.any { it in "{}" }) return "未知宏：$expression"
    if ('@' in tagged && (canonicalWriteAddress(base) == null || !validVersionSelector(tagged.substringAfter('@'))))
        return "宏版本选择无效：$expression"
    if ('#' in expression && (expression.substringAfter('#').isBlank() || parts[0] !in setOf("character", "pack", "category")))
        return "XML 选择器无效：$expression"
    return null
}

internal fun injectedBlocks(library: Library, session: Session, node: WorkflowNode): List<PromptBlock> {
    val main = library.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return emptyList()
    val owners = MacroReferences.visibleOwners(library, session)
    val currentTurnId = MacroReferences.currentTurnId(session)
    return session.promptInjections.mapNotNull { injection ->
        if (injection.origin.resourceId !in owners || (injection.scope == InjectionScope.TURN && injection.turnId != currentTurnId)) return@mapNotNull null
        val workflow = main.workflows.firstOrNull { it.id == (injection.targetWorkflowId ?: main.primaryWorkflowId) }
            ?: if (injection.targetWorkflowId == null && main.primaryWorkflowId == null) main.workflows.firstOrNull() else null
        val target = injection.targetNodeId ?: workflow?.nodes?.lastOrNull { it.enabled && it.actionId == "model.generate" }?.id
        if (workflow?.nodes?.any { it.id == node.id } == true && node.id == target) injection.block else null
    }
}

/** Version storage is independent of a run's masked dependency-result projection. */
object MacroReferences {
    private val plainKey = Regex("[\\p{L}_][\\p{L}\\p{N}_.-]*")

    fun canonicalAddress(value: String): String? {
        val raw = value.trim().removeSurrounding("{{", "}}").trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it in "{}@#" }) return null
        val address = when {
            raw.startsWith("macro.") -> "result." + raw.substring(6)
            raw.startsWith("result.") || raw.startsWith("character.") || raw.startsWith("pack.") -> raw
            '.' !in raw -> "result.$raw"
            else -> return null
        }
        val parts = address.split('.')
        return address.takeIf {
            when (parts.first()) {
                "result" -> plainKey.matches(address.substring(7))
                "character" -> parts.size == 2 && plainKey.matches(parts[1])
                "pack" -> parts.size == 3 && plainKey.matches(parts[1]) && plainKey.matches(parts[2])
                else -> false
            }
        }
    }

    fun currentTurn(session: Session): Int = session.messages.count { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }
    fun currentTurnId(session: Session): String? = session.messages.lastOrNull { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }?.id

    fun visibleOwners(library: Library, session: Session): Set<String> = buildSet {
        if (library.mainCalls.any { it.id == session.mainCallId }) add(session.mainCallId)
        if (library.characters.any { it.id == session.characterId }) add(session.characterId)
        library.loadedCallGroups(session).forEach { add(it.id) }
        library.loadedPromptPackages(session).forEach { add(it.id) }
    }

    fun targetLoaded(library: Library, session: Session, address: String): Boolean = when {
        address.startsWith("character.") -> library.characters.any { it.id == session.characterId }
        address.startsWith("pack.") -> library.loadedPromptPackages(session).any { it.namespace == address.split('.')[1] }
        else -> true
    }

    fun originVisible(library: Library, session: Session, origin: ResultOrigin?): Boolean =
        origin == null || origin.resourceId in visibleOwners(library, session)

    fun currentVersion(library: Library, session: Session, address: String): MacroVersion? =
        selectVersion(library, session, address, "latest")

    fun currentVersions(library: Library, session: Session): Map<String, MacroVersion> {
        val view = MacroReadView(library, session)
        return buildMap {
            (session.macroHistory.keys + session.currentOnlyMacros.keys).forEach { address ->
                view.selectVersion(address, "latest")?.let { put(address, it) }
            }
        }
    }

    fun selectVersion(library: Library, session: Session, address: String, selector: String): MacroVersion? {
        return MacroReadView(library, session).selectVersion(address, selector)
    }

    fun baseValue(library: Library, session: Session, address: String): String? = when {
        address.startsWith("result.") -> session.results[address.substring(7)].takeIf {
            (address !in session.currentOnlyMacros || currentVersion(library, session, address) != null) &&
            originVisible(library, session, session.resultOrigins[address.substring(7)])
        }
        address == "character.name" -> library.characters.firstOrNull { it.id == session.characterId }?.name
        address.startsWith("character.") -> library.characters.firstOrNull { it.id == session.characterId }
            ?.let(CharacterFields::normalize)?.entries?.firstOrNull { it.key == CharacterFields.canonicalKey(address.substring(10)) }?.text
        address.startsWith("pack.") -> {
            val parts = address.split('.')
            library.loadedPromptPackages(session).firstOrNull { it.namespace == parts[1] }?.entries?.firstOrNull { it.key == parts[2] }?.text
        }
        else -> null
    }

    fun publish(
        history: MutableMap<String, List<MacroVersion>>, address: String, text: String, enabled: Boolean,
        origin: ResultOrigin, turn: Int, turnId: String?, valueOrigin: ResultOrigin? = null,
        minimumVersion: Int = 0, precedingGap: MacroGap? = null, rawText: String? = null,
        fullResponseText: String? = null, reasoningSnapshot: ReasoningSnapshot? = null
    ): MacroVersion {
        val previous = history[address].orEmpty()
        val version = MacroVersion(Math.addExact(maxOf(previous.lastOrNull()?.version ?: 0, minimumVersion), 1),
            text, origin, enabled, turn, turnId, valueOrigin, precedingGap, rawText, fullResponseText, reasoningSnapshot)
        history[address] = previous + version
        return version
    }
}

/** Resource indexes are built once per reader, not once per entry or recognition rule. */
internal class MacroReadView(val library: Library, val session: Session) {
    val character = library.characters.firstOrNull { it.id == session.characterId }?.let(CharacterFields::normalize)
    val groups = library.loadedCallGroups(session)
    val packages = library.loadedPromptPackages(session)
    private val namespaces = packages.map { it.namespace }.toSet()
    val owners = buildSet {
        character?.let { add(it.id) }
        library.mainCalls.firstOrNull { it.id == session.mainCallId }?.let { add(it.id) }
        groups.forEach { add(it.id) }
        packages.forEach { add(it.id) }
    }
    private val turn = MacroReferences.currentTurn(session)
    fun originVisible(origin: ResultOrigin?) = origin == null || origin.resourceId in owners
    fun targetLoaded(address: String) = when {
        address.startsWith("character.") -> character != null
        address.startsWith("pack.") -> address.split('.')[1] in namespaces
        else -> true
    }
    fun currentValueUnavailable(address: String): Boolean = address in session.currentOnlyMacros &&
        selectVersion(address, "latest") == null

    fun selectVersion(address: String, selector: String): MacroVersion? {
        if (!targetLoaded(address)) return null
        if (selector == "latest" && address.startsWith("result.") &&
            (address.substring(7) in session.unavailableResultKeys ||
                address.substring(7) in session.pendingRun?.unavailableKeys.orEmpty())) return null
        val versions = session.macroHistory[address].orEmpty()
        fun visible(version: MacroVersion) = version.origin.resourceId in owners &&
            (version.valueOrigin == null || version.valueOrigin.resourceId in owners) &&
            (version.turn == null || version.turn <= turn)
        val head = session.currentOnlyMacros[address]
        fun currentHead(): MacroVersion? {
            if (head == null) return null
            val text = when {
                address.startsWith("result.") -> session.results[address.substring(7)]
                address.startsWith("state.") -> session.state[address.substring(6)]
                else -> head.text
            } ?: return null
            return MacroVersion(head.version, text, head.origin, head.enabled, head.turn, head.turnId,
                head.valueOrigin).takeIf(::visible)
        }
        fun omitted(target: Int): Boolean {
            fun covers(gap: MacroGap, end: Int?): Boolean =
                (gap.firstTurn == null || target >= gap.firstTurn) && (end == null || target < end)
            // Omission is a fact about this address, even if the writer is no longer loaded.
            return (head != null && covers(head.gap, null)) ||
                versions.any { version -> version.precedingGap?.let { covers(it, version.turn) } == true }
        }
        return when {
            selector == "latest" -> if (head != null) currentHead() else versions.lastOrNull(::visible)
            selector.startsWith("turn-") -> {
                val offset = selector.substring(5).toIntOrNull() ?: return null
                val target = turn - offset
                if (offset < 0 || target < 0) null
                else if (offset == 0 && head != null) currentHead()
                else if (omitted(target)) null
                else versions.lastOrNull { visible(it) && it.turn != null && it.turn <= target }
            }
            selector.startsWith("update-") -> {
                var remaining = selector.substring(7).toIntOrNull() ?: return null
                if (remaining < 0) return null
                versions.asReversed().firstOrNull { visible(it) && remaining-- == 0 }
            }
            selector.startsWith("version-") -> {
                val wanted = selector.substring(8).toIntOrNull() ?: return null
                versions.firstOrNull { it.version == wanted && visible(it) }
            }
            else -> null
        }
    }
}

/** Shared display/template entry point. It never invokes a model or executes behavior. */
class MacroResolver(library: Library, session: Session, input: String = "", executionVersion: Int = ExecutionSemantics.CURRENT) {
    private val mutableIssues = mutableListOf<Issue>()
    private val renderer = TemplateRenderer(
        loadedResources(library, session, session.state, session.results, input, mutableIssues, executionVersion = executionVersion),
        input, session.results, session.state, mutableIssues, session.resultOrigins, session, executionVersion = executionVersion
    )
    val issues: List<Issue> get() = mutableIssues.toList()
    fun render(template: String): String = renderer.render(template, "macro")
    fun read(address: String): String = renderer.read(address, "macro")
}
