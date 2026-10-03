package dev.gray.companion.core

/** Reads source references without publishing captures or macro versions. */
object HistoryViews {
    fun selection(expression: String): HistorySelection? {
        val result = PromptReferenceLanguage.compile(expression)
        val ref = result.reference ?: return null
        return when (ref) {
            is PromptReferenceLanguage.LegacyReference -> if (ref.expression in setOf("history", "history.raw", "history.text"))
                HistorySelection(compressed = ref.expression != "history.raw") else null
            is PromptReferenceLanguage.QueryPlan -> ref.historySelection
        }
    }

    fun sources(session: Session, selection: HistorySelection, main: MainCall? = null): List<HistorySourceRef> {
        val all = if (selection.viewId == null) session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }.map(HistoryMemory::source)
        else {
            require(selection.revision != null || selection.viewId !in session.historyMemory.unavailableViewIds) { "当前历史副本尚未生成：${selection.viewId}" }
            val view = HistoryMemory.view(session.historyMemory, selection.viewId, selection.revision)
                ?: error("历史副本或版本不存在：${selection.viewId}")
            session.historyMemory.captures[view.captureId] ?: error("历史副本来源捕获不存在")
        }
        val range = if (selection.compressed && selection.viewId == null && selection.range == HistoryRange())
            main?.history?.range ?: selection.range else selection.range
        require(HistoryContext.rangeValid(range, all.size)) { "历史范围起点晚于终点" }
        return all.slice(HistoryContext.selectedRange(range, all.size))
    }

    fun originalMessages(session: Session, selection: HistorySelection, main: MainCall? = null): List<ChatMessage> {
        val index = HistoryMemory.sourceIndex(session)
        return sources(session, selection, main).map { ref -> HistoryMemory.resolve(ref, index)
            ?: error("历史原文修订已不存在：${ref.messageId}") }
    }

    fun project(library: Library, session: Session, main: MainCall, selection: HistorySelection,
                textOverrides: Map<String, String> = emptyMap(),
                renderInsertion: (String) -> String = { it }): HistoryProjection = try {
        val selectedSources = sources(session, selection, main)
        val index = HistoryMemory.sourceIndex(session)
        val originals = selectedSources.map { ref ->
            val original = HistoryMemory.resolve(ref, index) ?: error("历史原文修订已不存在：${ref.messageId}")
            textOverrides[original.id]?.let { original.copy(text = it) } ?: original
        }
        val state = session.historyMemory
        val view = selection.viewId?.let { HistoryMemory.view(state, it, selection.revision) ?: error("历史副本不存在") }
        val segments = HistoryMemory.effectiveSegments(session, main)
        val selectedPositions = selectedSources.withIndex().associate { it.value.messageId to it.index }
        val modes = view?.let { HistoryMemory.modes(state, it) } ?: if (selection.compressed)
            defaultModes(main, selectedSources, segments) else emptyMap()
        val compressed = modes.filterValues { it == HistorySegmentMode.COMPRESS }.keys.map { id ->
            segments[id] ?: error("记忆段不存在：$id")
        }.filter { segment ->
            HistoryMemory.matches(segment, selectedSources, selectedPositions)
        }
        val coveredIds = compressed.flatMap { it.sources.map { source -> source.messageId } }
        require(coveredIds.distinct().size == coveredIds.size) { "压缩记忆段重叠" }
        // One source-coordinate projection keeps insertions and per-floor wrappers consistent with compression.
        val displayMain = main.copy(history = main.history.copy(range = HistoryRange()))
        val activeNumbers = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
            .withIndex().associate { it.value.id to it.index + 1 }
        val capturedNumbers = view?.let { state.captures[it.captureId] }?.withIndex()
            ?.associate { it.value.messageId to it.index + 1 }.orEmpty()
        val sourceNumbers = capturedNumbers + activeNumbers
        val styled = HistoryContext(library, session.copy(messages = originals), displayMain, processing = true,
            legacyProcessing = true, floorNumbers = sourceNumbers, projectionSegments = compressed).projected(renderInsertion)
        HistoryProjection(styled, sourceIds = selectedSources.map { it.messageId })
    } catch (failure: IllegalArgumentException) {
        HistoryProjection(emptyList(), listOf(Issue("history", failure.message ?: "历史副本无效")))
    } catch (failure: IllegalStateException) {
        HistoryProjection(emptyList(), listOf(Issue("history", failure.message ?: "历史副本来源不存在")))
    }

    fun summaryMessage(segment: HistoryMemorySegment): ChatMessage = ChatMessage(
        id = "history-memory:${segment.id}", role = Role.SYSTEM,
        text = "[记忆 ${segment.id}；来源 ${segment.sources.first().messageId}…${segment.sources.last().messageId}；${segment.sources.size} 楼]\n${segment.summary}"
    )

    fun copy(session: Session, selection: HistorySelection, targetId: String, name: String,
             mode: HistorySegmentMode, segmentIds: List<String>, main: MainCall? = null): HistoryMemoryPatch {
        require(MacroNaming.isValidSlug(targetId)) { "历史副本标识须为英文 slug" }
        // A writer may update its own captured previous version; other pending producers stay masked.
        val resolvedSelection = if (selection.viewId == targetId && selection.revision == null)
            selection.copy(revision = session.historyMemory.views[targetId]?.lastOrNull()?.revision) else selection
        val selected = sources(session, resolvedSelection, main)
        val index = HistoryMemory.sourceIndex(session)
        require(selected.all { HistoryMemory.resolve(it, index) != null }) { "历史原文修订已不存在" }
        val captureId = HistoryMemory.captureId(selected)
        val state = session.historyMemory
        val base = resolvedSelection.viewId?.let { HistoryMemory.view(state, it, resolvedSelection.revision) ?: error("历史副本不存在") }
        val sameCapture = base?.captureId == captureId
        val effective = if (main == null) state.segments else HistoryMemory.effectiveSegments(session, main)
        val inherited = if (base == null && selection.compressed && main != null) defaultModes(main, selected, effective)
        else if (base == null || sameCapture) emptyMap() else HistoryMemory.modes(state, base).filter { (id, _) ->
            state.segments[id]?.sources?.let { refs ->
                val start = selected.indexOf(refs.first())
                start >= 0 && selected.drop(start).take(refs.size) == refs
            } == true
        }
        val revision = state.views[targetId]?.lastOrNull()?.revision ?: 0
        val view = HistoryViewRevision(targetId, name.ifBlank { targetId }, revision + 1, captureId,
            base?.viewId?.takeIf { sameCapture }, base?.revision?.takeIf { sameCapture },
            inherited + segmentIds.distinct().associateWith { mode })
        val needed = (inherited.keys + segmentIds).mapNotNull { id -> effective[id]?.takeIf { it.id !in state.segments } }
        val patch = HistoryMemoryPatch(segments = needed,
            captures = if (captureId in state.captures) emptyMap() else mapOf(captureId to selected),
            views = listOf(view), expectedViewRevisions = mapOf(targetId to revision))
        HistoryMemory.apply(state, patch)
        return patch
    }

    private fun defaultModes(main: MainCall, sources: List<HistorySourceRef>,
                             segments: Map<String, HistoryMemorySegment>): Map<String, HistorySegmentMode> {
        val positions = sources.withIndex().associate { it.value.messageId to it.index }
        return segments.values.filter { it.workflowId == main.history.processor.workflow.id &&
            HistoryMemory.matches(it, sources, positions) }.associate { it.id to HistorySegmentMode.COMPRESS }
    }
}
