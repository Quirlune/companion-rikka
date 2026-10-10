package dev.gray.companion.core

/** A read-only bound on native History; floor coordinates always remain those of the original branch. */
class MemoryPromptScope internal constructor(
    private val library: Library,
    private val session: Session,
    private val main: MainCall,
    private val allowed: List<HistorySourceRef>,
    private val children: List<HistoryMemorySegment> = emptyList(),
    internal val legacyReadKeys: Set<String> = emptySet(),
    private val executionVersion: Int = ExecutionSemantics.CURRENT,
    private val resultReads: Map<String, HistoryPromptRead> = emptyMap()
) {
    private val allowedById = allowed.associateBy { it.messageId }
    private val positions = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
        .withIndex().associate { it.value.id to it.index + 1 }
    val count: Int get() = allowed.size
    /** A dependency receipt proves only its current value, never an older version of the same address. */
    internal fun resultReadSourceIds(address: String, value: String?, version: MacroVersion?): List<String> {
        if (!address.startsWith("result.") || value.isNullOrBlank()) return emptyList()
        val key = address.substring(7)
        if (value != session.results[key]) return emptyList()
        val current = MacroReferences.selectVersion(library, session, address, "latest")
        if (version != null && version != current) return emptyList()
        if (current?.enabled == false || key in session.unavailableResultKeys ||
            key in session.pendingRun?.unavailableKeys.orEmpty()) return emptyList()
        return resultSourceIds(key)
    }
    internal fun resultSourceIds(key: String): List<String> = resultReads[key]?.sources
        ?.filter { allowedById[it.messageId] == it }?.map { it.messageId }
        ?: if (key in legacyReadKeys) project().sourceIds else emptyList()

    fun project(selection: HistorySelection = HistorySelection()): HistoryProjection = try {
        require(selection.sourceKind == HistorySourceKind.DISPLAY && selection.viewId == null) {
            "摘要提示词只能读取当前分支的展示正文；大摘要自动映射对应小摘要"
        }
        val selected = HistoryViews.sources(session, selection.copy(compressed = false), main, executionVersion)
            .filter { allowedById[it.messageId] == it }
        val selectedIds = selected.mapTo(mutableSetOf()) { it.messageId }
        val includedChildren = children.filter { child -> child.sources.all { it.messageId in selectedIds } }
        val refs = if (children.isEmpty()) selected else includedChildren.flatMap { it.sources }
        val index = HistoryMemory.sourceIndex(session)
        val messages = if (children.isEmpty()) refs.map { HistoryMemory.resolve(it, index) ?: error("摘要楼层修订已改变") }
            else includedChildren.map { child -> HistoryViews.summaryMessage(child).copy(text =
                "[小摘要；原文第 ${positions[child.sources.first().messageId]}–${positions[child.sources.last().messageId]} 楼；共 ${child.sources.size} 楼]\n${child.summary}") }
        val format = selection.format
        val display = main.copy(history = if (format == null) HistoryConfig() else HistoryConfig(
            apiRole = format.apiRole, floorPrefix = format.floorPrefix, floorSuffix = format.floorSuffix, speakers = format.speakers))
        val numbers = positions + includedChildren.associate { "history-memory:${it.id}" to requireNotNull(positions[it.sources.first().messageId]) }
        val pending = HistoryContext.Pending(messages, messages.joinToString("\n") { it.text }, messages.lastOrNull()?.id.orEmpty(),
            messages.size, refs.size)
        val styled = HistoryContext(library, session, display, processing = true, capturedPending = pending,
            legacyProcessing = true, floorNumbers = numbers, projectionSegments = emptyList(), executionVersion = executionVersion).projected()
        HistoryProjection(styled, sourceIds = refs.map { it.messageId })
    } catch (failure: IllegalArgumentException) {
        HistoryProjection(emptyList(), listOf(Issue("history", failure.message ?: "摘要来源无效")))
    } catch (failure: IllegalStateException) {
        HistoryProjection(emptyList(), listOf(Issue("history", failure.message ?: "摘要来源已改变")))
    }

    fun manifest(sourceIds: Collection<String>): HistoryPromptRead {
        val used = sourceIds.toSet()
        val selectedChildren = children.filter { child -> child.sources.all { it.messageId in used } }
        return if (children.isEmpty()) HistoryPromptRead(allowed.filter { it.messageId in used }) else
            HistoryPromptRead(selectedChildren.flatMap { it.sources }, selectedChildren.map { it.id })
    }

    companion object {
        internal fun forInvocation(library: Library, ref: PlannedNode, environment: InvocationEnvironment): MemoryPromptScope? {
            if (!ExecutionSemantics(environment.executionVersion).globalMemory || library.memoryConfig == null ||
                ref.node.actionId in setOf("history.memory.select", "history.memory.read", "history.memory.write")) return null
            val session = environment.session
            val main = library.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return null
            val binding = MemoryPlugins.binding(library, session, main, environment.executionVersion) ?: return null
            if (ref.workflow.id == binding.processor.workflow.id) {
                val pending = environment.history.pendingCandidate() ?: return null
                return MemoryPromptScope(library, session, main, pending.sources, executionVersion = environment.executionVersion,
                    resultReads = environment.historyReadsByKey)
            }
            val aggregate = binding.processor.aggregationWorkflow?.takeIf { it.id == ref.workflow.id } ?: return null
            val selector = aggregate.nodes.firstOrNull { it.actionId == "history.memory.select" } ?: return null
            val ids = HistoryActions.selectedIds(session.results[selector.outputKey].orEmpty(), "", 0)
            val all = GlobalMemory.segments(library, session, main)
            val children = ids.map { all[it] ?: error("所选小摘要不存在：$it") }
            return MemoryPromptScope(library, session, main, children.flatMap { it.sources }, children,
                aggregate.nodes.filter { it.actionId == "history.memory.read" }.mapTo(mutableSetOf()) { it.outputKey }, environment.executionVersion,
                environment.historyReadsByKey)
        }
    }
}
