package dev.gray.companion.core

/** Reads source references without publishing captures or macro versions. */
object HistoryViews {
    fun selection(expression: String, executionVersion: Int = 8): HistorySelection? {
        val result = PromptReferenceLanguage.compile(expression)
        val ref = result.reference ?: return null
        if (executionVersion < 5 && result.fields.any { it.name == "source" }) return null
        if (executionVersion < 8 && result.fields.any { it.name == "format" }) return null
        return when (ref) {
            is PromptReferenceLanguage.LegacyReference -> when (ref.expression) {
                "history", "history.raw", "history.text" -> HistorySelection(compressed = ref.expression != "history.raw")
                "history.display" -> if (executionVersion >= 5) HistorySelection() else null
                "history.model" -> if (executionVersion >= 5) HistorySelection(sourceKind = HistorySourceKind.MODEL_RAW) else null
                else -> null
            }
            is PromptReferenceLanguage.QueryPlan -> ref.historySelection?.let {
                if (executionVersion < 8) it.copy(rangeExplicit = false) else it
            }
        }
    }

    fun sources(session: Session, selection: HistorySelection, main: MainCall? = null): List<HistorySourceRef> {
        val range = if (selection.compressed && selection.viewId == null && !selection.rangeExplicit && selection.range == HistoryRange())
            main?.history?.range ?: selection.range else selection.range
        if (selection.viewId == null) {
            val floors = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
            require(HistoryContext.rangeValid(range, floors.size)) { "历史范围起点晚于终点" }
            return floors.slice(HistoryContext.selectedRange(range, floors.size)).map {
                HistoryMemory.source(it, selection.sourceKind)
            }
        }
        require(selection.revision != null || selection.viewId !in session.historyMemory.unavailableViewIds) {
            "当前历史副本尚未生成：${selection.viewId}"
        }
        val view = HistoryMemory.view(session.historyMemory, selection.viewId, selection.revision)
            ?: error("历史副本或版本不存在：${selection.viewId}")
        require(view.sourceKind == selection.sourceKind) { "历史副本来源类型不一致：${selection.viewId}" }
        val all = HistoryMemory.captureSources(session.historyMemory, view.captureId)
        require(all.all { it.sourceKind == selection.sourceKind }) { "历史捕获来源类型不一致" }
        require(HistoryContext.rangeValid(range, all.size)) { "历史范围起点晚于终点" }
        return all.slice(HistoryContext.selectedRange(range, all.size))
    }

    fun summaries(session: Session, main: MainCall, kind: HistorySourceKind, executionVersion: Int = 8): List<HistoryMemorySegment> {
        val floors = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
        val sources = floors.map { message ->
            if (kind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening && message.rawText == null) null
            else HistoryMemory.source(message, kind)
        }
        return HistoryHierarchy(HistoryMemory.effectiveSegments(session, main, kind), sources,
            ordered = executionVersion >= 7).validSegments().values.toList()
    }

    fun originalMessages(session: Session, selection: HistorySelection, main: MainCall? = null): List<ChatMessage> {
        val index = HistoryMemory.sourceIndex(session)
        return sources(session, selection, main).map { ref -> HistoryMemory.resolve(ref, index)
            ?: error("历史原文修订已不存在：${ref.messageId}") }
    }

    fun project(library: Library, session: Session, main: MainCall, selection: HistorySelection,
                textOverrides: Map<String, String> = emptyMap(), executionVersion: Int = 8,
                renderInsertion: (String) -> String = { it }): HistoryProjection = try {
        val selectedSources = sources(session, selection, main)
        val index = HistoryMemory.sourceIndex(session)
        val originals = selectedSources.map { ref ->
            val original = HistoryMemory.resolve(ref, index) ?: error("历史原文修订已不存在：${ref.messageId}")
            textOverrides[original.id]?.let { original.copy(text = it) } ?: original
        }
        val state = session.historyMemory
        val view = selection.viewId?.let { HistoryMemory.view(state, it, selection.revision) ?: error("历史副本不存在") }
        val segments = HistoryMemory.effectiveSegments(session, main, selection.sourceKind)
        val projectionVersion = view?.projectionVersion ?: if (executionVersion >= 7) 2 else 1
        val modes = view?.let { HistoryMemory.modes(state, it) } ?: if (selection.compressed)
            defaultModes(main, selectedSources, segments, projectionVersion) else emptyMap()
        val compressed = HistoryHierarchy(segments, selectedSources, ordered = projectionVersion >= 2)
            .compressed(modes, projectionVersion)
        // One source-coordinate projection keeps insertions and per-floor wrappers consistent with compression.
        require(executionVersion >= 8 || selection.format == null) { "此恢复版本不支持 History 局部格式" }
        val local = selection.format
        val displayHistory = if (local == null) main.history.copy(range = HistoryRange()) else main.history.copy(
            range = HistoryRange(), apiRole = local.apiRole, mergeMessages = false, prefix = "", suffix = "",
            floorPrefix = local.floorPrefix, floorSuffix = local.floorSuffix, speakers = local.speakers,
            floors = emptyList(), insertions = emptyList())
        val displayMain = main.copy(history = displayHistory)
        val activeNumbers = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
            .withIndex().associate { it.value.id to it.index + 1 }
        val capturedNumbers = view?.let { HistoryMemory.captureSources(state, it.captureId) }?.withIndex()
            ?.associate { it.value.messageId to it.index + 1 }.orEmpty()
        val sourceNumbers = capturedNumbers + activeNumbers
        val styled = HistoryContext(library, session.copy(messages = originals), displayMain, processing = true,
            legacyProcessing = true, floorNumbers = sourceNumbers, projectionSegments = compressed,
            executionVersion = executionVersion, projectionVersion = projectionVersion).projected(renderInsertion)
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
             mode: HistorySegmentMode, segmentIds: List<String>, main: MainCall? = null,
             inheritTargetModes: Boolean = false, executionVersion: Int = 8): HistoryMemoryPatch {
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
        val target = HistoryMemory.view(state, targetId)?.takeIf { it.sourceKind == selection.sourceKind }
        val previousSources = if (executionVersion >= 7 && captureId !in state.captures)
            target?.let { HistoryMemory.captureSources(state, it.captureId) } else null
        val appendBase = target?.takeIf { previousSources != null && selected.size > previousSources.size &&
            selected.subList(0, previousSources.size) == previousSources }
        val addedCaptures = if (captureId in state.captures) emptyMap() else mapOf(captureId to
            if (appendBase == null) selected else selected.drop(requireNotNull(previousSources).size))
        val addedBases = appendBase?.let { mapOf(captureId to it.captureId) }.orEmpty()
        val captureState = state.copy(captures = state.captures + addedCaptures, captureBases = state.captureBases + addedBases)
        val effective = if (main == null) state.segments.filterValues { segment ->
            segment.sources.all { it.sourceKind == selection.sourceKind }
        } else HistoryMemory.effectiveSegments(session, main, selection.sourceKind)
        val projectionVersion = if (executionVersion >= 7) 2 else 1
        val selectedHierarchy = HistoryHierarchy(effective, selected, ordered = projectionVersion >= 2)
        val compatibleBase = sameCapture && base?.projectionVersion == projectionVersion
        val inherited = if (base == null && selection.compressed && main != null) defaultModes(main, selected, effective, projectionVersion)
        else if (base == null) emptyMap()
        else HistoryMemory.modes(state, base).filterKeys(selectedHierarchy::valid)
        val revision = state.views[targetId]?.lastOrNull()?.revision ?: 0
        val priorPreferences = if (inheritTargetModes && executionVersion >= 7) {
            target?.let { HistoryMemory.modes(state, it).filterKeys(selectedHierarchy::valid) }.orEmpty()
        } else emptyMap()
        val requested = segmentIds.distinct().associateWith { mode }
        // Explicit refresh preserves prior choices; normal copy commands still override them.
        val chosen = if (inheritTargetModes && executionVersion >= 7) inherited + requested + priorPreferences
            else inherited + requested
        // A refresh carries only changed preferences when its previous capture is an exact prefix.
        val refreshBase = if (inheritTargetModes && projectionVersion >= 2) target?.takeIf {
            it.projectionVersion == projectionVersion && HistoryMemory.captureExtends(captureState, it.captureId, captureId)
        } else null
        val modeBase = refreshBase ?: base?.takeIf { compatibleBase }
        val baseModes = modeBase?.let { HistoryMemory.modes(state, it) }.orEmpty()
        require(baseModes.keys.all { it in chosen }) { "历史副本基础模式缺少继承项" }
        val storedModes = if (modeBase == null) chosen else chosen.filter { (id, value) -> baseModes[id] != value }
        val view = HistoryViewRevision(targetId, name.ifBlank { targetId }, revision + 1, captureId,
            modeBase?.viewId, modeBase?.revision, storedModes, sourceKind = selection.sourceKind, projectionVersion = projectionVersion)
        val needed = chosen.keys.mapNotNull { id -> effective[id]?.takeIf { it.id !in state.segments } }
        val patch = HistoryMemoryPatch(segments = needed,
            captures = addedCaptures, views = listOf(view), expectedViewRevisions = mapOf(targetId to revision), captureBases = addedBases)
        HistoryMemory.apply(state, patch)
        return patch
    }

    private fun defaultModes(main: MainCall, sources: List<HistorySourceRef>,
                             segments: Map<String, HistoryMemorySegment>, projectionVersion: Int): Map<String, HistorySegmentMode> {
        val hierarchy = HistoryHierarchy(segments, sources, ordered = projectionVersion >= 2)
        val matching = hierarchy.validSegments().values.filter { segment ->
            if (projectionVersion >= 2) hierarchy.belongsToFamily(segment.id, main.history.processor.workflow.id)
            else segment.workflowId == main.history.processor.workflow.id
        }
        return (if (projectionVersion >= 2) hierarchy.roots(matching) else matching)
            .associate { it.id to HistorySegmentMode.COMPRESS }
    }
}
