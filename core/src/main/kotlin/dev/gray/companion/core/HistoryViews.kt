package dev.gray.companion.core

/** Reads source references without publishing captures or macro versions. */
object HistoryViews {
    fun selection(expression: String, executionVersion: Int = ExecutionSemantics.CURRENT): HistorySelection? {
        val semantics = ExecutionSemantics(executionVersion)
        val result = PromptReferenceLanguage.compile(expression)
        val ref = result.reference ?: return null
        if (!semantics.historySources && result.fields.any { it.name == "source" }) return null
        if (!semantics.localHistoryFormats && result.fields.any { it.name == "format" }) return null
        if (!semantics.historySourceRoleSelection && result.fields.any { it.name == "speaker" }) return null
        return when (ref) {
            is PromptReferenceLanguage.LegacyReference -> when (ref.expression) {
                "history", "history.raw", "history.text" -> HistorySelection(compressed = ref.expression != "history.raw")
                "history.display" -> if (semantics.historySources) HistorySelection() else null
                "history.model" -> if (semantics.historySources) HistorySelection(sourceKind = HistorySourceKind.MODEL_RAW) else null
                else -> null
            }
            is PromptReferenceLanguage.QueryPlan -> ref.historySelection?.let {
                if (!semantics.explicitHistoryRanges) it.copy(rangeExplicit = false) else it
            }
        }
    }

    fun sources(session: Session, selection: HistorySelection, main: MainCall? = null,
                executionVersion: Int = ExecutionSemantics.CURRENT): List<HistorySourceRef> {
        require(selection.sourceRole == null || ExecutionSemantics(executionVersion).historySourceRoleSelection) {
            "此恢复版本不支持 History 原始发言方筛选"
        }
        // With a source-role filter, an omitted range means the complete matching pool.
        val range = if (selection.sourceRole == null && selection.compressed && selection.viewId == null && !selection.rangeExplicit && selection.range == HistoryRange())
            main?.history?.range ?: selection.range else selection.range
        if (selection.viewId == null) {
            val floors = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY &&
                (selection.sourceRole == null || it.role == selection.sourceRole) }
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
        val pool = if (selection.sourceRole == null) all else {
            val index = HistoryMemory.sourceIndex(session)
            all.filter { ref ->
                val original = HistoryMemory.resolve(ref, index) ?: error("历史原文修订已不存在：${ref.messageId}")
                original.role == selection.sourceRole
            }
        }
        require(HistoryContext.rangeValid(range, pool.size)) { "历史范围起点晚于终点" }
        return pool.slice(HistoryContext.selectedRange(range, pool.size))
    }

    fun summaries(session: Session, main: MainCall, kind: HistorySourceKind, executionVersion: Int = ExecutionSemantics.CURRENT,
                  library: Library? = null): List<HistoryMemorySegment> {
        val semantics = ExecutionSemantics(executionVersion)
        val floors = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
        val sources = floors.map { message ->
            if (kind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening && message.rawText == null) null
            else HistoryMemory.source(message, kind)
        }
        val processor = projectionProcessor(library, session, main, executionVersion)
        val memoryMain = processor?.let { main.copy(history = main.history.copy(processor = it)) } ?: main
        val hierarchy = HistoryHierarchy(if (semantics.globalMemory) GlobalMemory.segments(library, session, memoryMain, kind)
            else HistoryMemory.effectiveSegments(session, memoryMain, kind), sources,
            ordered = semantics.hierarchicalMemory)
        val valid = hierarchy.validSegments().values.toList()
        if (!semantics.nativeMemoryPlugins) return valid
        if (processor == null) return emptyList()
        return hierarchy.roots(valid.filter {
            if (semantics.globalMemory && library?.memoryConfig != null) GlobalMemory.inFamily(hierarchy, it.id, library, processor.workflow.id)
            else hierarchy.belongsToFamily(it.id, processor.workflow.id)
        })
    }

    fun originalMessages(session: Session, selection: HistorySelection, main: MainCall? = null,
                         executionVersion: Int = ExecutionSemantics.CURRENT): List<ChatMessage> {
        val index = HistoryMemory.sourceIndex(session)
        return sources(session, selection, main, executionVersion).map { ref -> HistoryMemory.resolve(ref, index)
            ?: error("历史原文修订已不存在：${ref.messageId}") }
    }

    fun project(library: Library, session: Session, main: MainCall, selection: HistorySelection,
                textOverrides: Map<String, String> = emptyMap(), executionVersion: Int = ExecutionSemantics.CURRENT,
                renderInsertion: (String) -> String = { it }): HistoryProjection = try {
        val semantics = ExecutionSemantics(executionVersion)
        val selectedSources = sources(session, selection, main, executionVersion)
        val index = HistoryMemory.sourceIndex(session)
        val originals = selectedSources.map { ref ->
            val original = HistoryMemory.resolve(ref, index) ?: error("历史原文修订已不存在：${ref.messageId}")
            textOverrides[original.id]?.let { original.copy(text = it) } ?: original
        }
        val state = session.historyMemory
        val view = selection.viewId?.let { HistoryMemory.view(state, it, selection.revision) ?: error("历史副本不存在") }
        val processor = projectionProcessor(library, session, main, executionVersion)
        val memoryMain = processor?.let { main.copy(history = main.history.copy(processor = it)) } ?: main
        val segments = if (semantics.globalMemory) GlobalMemory.segments(library, session, memoryMain, selection.sourceKind)
            else HistoryMemory.effectiveSegments(session, memoryMain, selection.sourceKind)
        val projectionVersion = view?.projectionVersion ?: if (semantics.hierarchicalMemory) 2 else 1
        val modes = view?.let { HistoryMemory.modes(state, it) } ?: if (selection.compressed)
            defaultModes(processor, selectedSources, segments, projectionVersion, library.takeIf { semantics.globalMemory }) else emptyMap()
        val compressed = HistoryHierarchy(segments, selectedSources, ordered = projectionVersion >= 2)
            .compressed(modes, projectionVersion)
        // One source-coordinate projection keeps insertions and per-floor wrappers consistent with compression.
        require(semantics.localHistoryFormats || selection.format == null) { "此恢复版本不支持 History 局部格式" }
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
        val styled = HistoryContext(library, session.copy(messages = originals, inactivePaths = emptyList()), displayMain, processing = true,
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
             inheritTargetModes: Boolean = false, executionVersion: Int = ExecutionSemantics.CURRENT,
             library: Library? = null): HistoryMemoryPatch {
        val semantics = ExecutionSemantics(executionVersion)
        require(MacroNaming.isValidSlug(targetId)) { "历史副本标识须为英文 slug" }
        // A writer may update its own captured previous version; other pending producers stay masked.
        val resolvedSelection = if (selection.viewId == targetId && selection.revision == null)
            selection.copy(revision = session.historyMemory.views[targetId]?.lastOrNull()?.revision) else selection
        val selected = sources(session, resolvedSelection, main, executionVersion)
        val index = HistoryMemory.sourceIndex(session)
        require(selected.all { HistoryMemory.resolve(it, index) != null }) { "历史原文修订已不存在" }
        val captureId = HistoryMemory.captureId(selected)
        val state = session.historyMemory
        val base = resolvedSelection.viewId?.let { HistoryMemory.view(state, it, resolvedSelection.revision) ?: error("历史副本不存在") }
        val sameCapture = base?.captureId == captureId
        val target = HistoryMemory.view(state, targetId)?.takeIf { it.sourceKind == selection.sourceKind }
        val previousSources = if (semantics.hierarchicalMemory && captureId !in state.captures)
            target?.let { HistoryMemory.captureSources(state, it.captureId) } else null
        val appendBase = target?.takeIf { previousSources != null && selected.size > previousSources.size &&
            selected.subList(0, previousSources.size) == previousSources }
        val addedCaptures = if (captureId in state.captures) emptyMap() else mapOf(captureId to
            if (appendBase == null) selected else selected.drop(requireNotNull(previousSources).size))
        val addedBases = appendBase?.let { mapOf(captureId to it.captureId) }.orEmpty()
        val captureState = state.copy(captures = state.captures + addedCaptures, captureBases = state.captureBases + addedBases)
        val processor = main?.let { projectionProcessor(library, session, it, executionVersion) }
        val memoryMain = if (main != null && processor != null) main.copy(history = main.history.copy(processor = processor)) else main
        val effective = if (memoryMain == null) state.segments.filterValues { segment ->
            segment.sources.all { it.sourceKind == selection.sourceKind }
        } else if (semantics.globalMemory) GlobalMemory.segments(library, session, memoryMain, selection.sourceKind)
        else HistoryMemory.effectiveSegments(session, memoryMain, selection.sourceKind)
        val projectionVersion = if (semantics.hierarchicalMemory) 2 else 1
        val selectedHierarchy = HistoryHierarchy(effective, selected, ordered = projectionVersion >= 2)
        val compatibleBase = sameCapture && base?.projectionVersion == projectionVersion
        val inherited = if (base == null && selection.compressed && main != null) defaultModes(
            processor, selected, effective, projectionVersion, library.takeIf { semantics.globalMemory })
        else if (base == null) emptyMap()
        else HistoryMemory.modes(state, base).filterKeys(selectedHierarchy::valid)
        val revision = state.views[targetId]?.lastOrNull()?.revision ?: 0
        val priorPreferences = if (inheritTargetModes && semantics.hierarchicalMemory) {
            target?.let { HistoryMemory.modes(state, it).filterKeys(selectedHierarchy::valid) }.orEmpty()
        } else emptyMap()
        val requested = segmentIds.distinct().associateWith { mode }
        // Explicit refresh preserves prior choices; normal copy commands still override them.
        val chosen = if (inheritTargetModes && semantics.hierarchicalMemory) inherited + requested + priorPreferences
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

    private fun projectionProcessor(library: Library?, session: Session, main: MainCall, executionVersion: Int): HistoryProcessor? =
        if (library != null) MemoryPlugins.binding(library, session, main, executionVersion)?.processor
        else main.history.processor.takeIf { !ExecutionSemantics(executionVersion).nativeMemoryPlugins || it.workflow.enabled }

    private fun defaultModes(processor: HistoryProcessor?, sources: List<HistorySourceRef>,
                             segments: Map<String, HistoryMemorySegment>, projectionVersion: Int, library: Library? = null): Map<String, HistorySegmentMode> {
        if (processor == null) return emptyMap()
        val hierarchy = HistoryHierarchy(segments, sources, ordered = projectionVersion >= 2)
        val matching = hierarchy.validSegments().values.filter { segment ->
            if (projectionVersion >= 2 && library?.memoryConfig != null) GlobalMemory.inFamily(hierarchy, segment.id, library, processor.workflow.id)
            else if (projectionVersion >= 2) hierarchy.belongsToFamily(segment.id, processor.workflow.id)
            else segment.workflowId == processor.workflow.id
        }
        return (if (projectionVersion >= 2) hierarchy.roots(matching) else matching)
            .associate { it.id to HistorySegmentMode.COMPRESS }
    }
}
