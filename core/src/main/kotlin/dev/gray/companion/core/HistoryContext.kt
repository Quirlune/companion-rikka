package dev.gray.companion.core

import kotlin.math.floor

/** A captured, read-only projection of BODY chat messages for one main call. */
class HistoryContext(
    private val library: Library,
    private val session: Session,
    private val main: MainCall,
    private val processing: Boolean = false,
    private val capturedPending: Pending? = null,
    private val legacyProcessing: Boolean = false,
    private val floorNumbers: Map<String, Int> = emptyMap(),
    private val projectionSegments: List<HistoryMemorySegment>? = null,
    val executionVersion: Int = ExecutionSemantics.CURRENT,
    private val projectionVersion: Int? = null
) {
    internal fun withResources(resources: Library): HistoryContext = HistoryContext(resources, session,
        resources.mainCalls.firstOrNull { it.id == main.id } ?: main, processing, capturedPending,
        legacyProcessing, floorNumbers, projectionSegments, executionVersion, projectionVersion)

    data class Pending(
        val messages: List<ChatMessage>, val text: String, val throughMessageId: String,
        val coveredFloors: Int, val newFloors: Int,
        val sources: List<HistorySourceRef> = messages.map(HistoryMemory::source),
        val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY
    )

    private val semantics = ExecutionSemantics(executionVersion)
    private val memoryBinding: MemoryBinding? = MemoryPlugins.binding(library, session, main, executionVersion)
    private val processor: HistoryProcessor? = memoryBinding?.processor
    // Only a read projection: legacy digest checks must use the resolved leaf identity.
    private val memoryMain = processor?.let { main.copy(history = main.history.copy(processor = it)) } ?: main
    private val mutableIssues = mutableListOf<Issue>()
    val issues: List<Issue> get() = mutableIssues.toList()
    private val sourceKind = if (semantics.historySources && processing) capturedPending?.sourceKind ?: HistorySourceKind.DISPLAY
        else HistorySourceKind.DISPLAY
    val isProcessing: Boolean get() = processing
    private val floors = if (processing && capturedPending != null) capturedPending.messages
        else session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
    private val archivedFloors = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
    private val digest = session.historyDigests[main.id]?.takeIf { processor != null && it.coveredFloors in 1..archivedFloors.size &&
        archivedFloors[it.coveredFloors - 1].id == it.throughMessageId &&
        it.workflowId == processor.workflow.id }
    private val selected: IntRange = if (processing) range(HistoryRange(), floors.size)
        else range(main.history.range, floors.size)
    private val selectedFloors = if (selected.isEmpty()) emptyList() else floors.slice(selected)
    private val allSegments: Map<String, HistoryMemorySegment> = if (semantics.globalMemory) GlobalMemory.segments(library, session, memoryMain, sourceKind)
        else HistoryMemory.effectiveSegments(session, memoryMain, sourceKind)
    private val archiveSources = archivedFloors.map {
        if (sourceKind == HistorySourceKind.MODEL_RAW && it.role == Role.ASSISTANT && !it.authoredOpening && it.rawText == null) null
        else HistoryMemory.source(it, sourceKind)
    }
    private val hierarchy: HistoryHierarchy = HistoryHierarchy(allSegments, archiveSources, ordered = semantics.hierarchicalMemory)
    private val validSegments: List<HistoryMemorySegment> = if (legacyProcessing || allSegments.isEmpty())
        emptyList() else hierarchy.validSegments().values.toList()
    private val segments: List<HistoryMemorySegment> = if (processor == null) emptyList() else if (semantics.hierarchicalMemory) hierarchy.roots(validSegments.filter {
        if (semantics.globalMemory && library.memoryConfig != null) GlobalMemory.inFamily(hierarchy, it.id, library, processor.workflow.id)
        else hierarchy.belongsToFamily(it.id, processor.workflow.id)
    }) else validSegments.filter { it.workflowId == processor.workflow.id }
    private val orderedProjection = (projectionVersion ?: if (semantics.hierarchicalMemory) 2 else 1) >= 2
    private val pending = capturedPending ?: candidate()
    private val speakerRules = main.history.speakers.associateBy { it.speaker }
    private val floorRules = main.history.floors.associateBy { it.messageId }
    private val characterName = library.characters.firstOrNull { it.id == session.characterId }?.name ?: "助手"
    private val wrapperToken = Regex("\\{\\{\\s*(speaker(?:\\.name)?|floor)\\s*\\}\\}")

    fun pendingCandidate(): Pending? = pending
    fun selectedSourceIds(): List<String> = selectedFloors.map { it.id }
    fun containsMessage(id: String?): Boolean = id != null && selectedFloors.any { it.id == id }
    fun withMessages(messages: List<ChatMessage>): HistoryContext = HistoryContext(
        library, session.copy(messages = messages, inactivePaths = emptyList()), main, processing, capturedPending, legacyProcessing, floorNumbers, projectionSegments, executionVersion, projectionVersion
    )

    fun read(key: String, renderInsertion: (String) -> String = { it }): String? = when (key) {
        "raw" -> rawText(selectedFloors)
        "text" -> projected(renderInsertion).joinToString("\n") { it.text }
        "pending" -> pending?.text.orEmpty()
        "summary" -> if (legacyProcessing) digest?.text.orEmpty()
            else (if (sourceKind == HistorySourceKind.DISPLAY) {
                if (semantics.nativeMemoryPlugins) segments else validSegments
            } else HistoryViews.summaries(session, main, HistorySourceKind.DISPLAY, executionVersion, library))
                .joinToString("\n") { HistoryViews.summaryMessage(it).text }
        "count" -> selectedFloors.size.toString()
        "pending.count" -> (pending?.newFloors ?: 0).toString()
        else -> null
    }

    fun projected(renderInsertion: (String) -> String = { it }): List<ChatMessage> {
        val insertions = main.history.insertions.filter { it.enabled }.groupBy { gap(it.at, selectedFloors.size) }
        val useDigest = digest != null && !processing && selected.first == 0 && selected.last >= digest.coveredFloors - 1 &&
            insertions.keys.none { it in 1 until digest.coveredFloors }
        val output = mutableListOf<ChatMessage>()
        val selectedPositions = selectedFloors.withIndex().associate { it.value.id to it.index }
        val candidateSegments = (projectionSegments ?: if (processing) emptyList() else segments).filter { segment ->
            val start = selectedPositions[segment.sources.first().messageId] ?: -1
            if (orderedProjection) segment.sources.all { it.messageId in selectedPositions }
            else start >= 0 && start + segment.sources.size <= selectedFloors.size &&
                insertions.keys.none { it > start && it < start + segment.sources.size }
        }
        val overlaps = candidateSegments.flatMap { it.sources.map { source -> source.messageId } }.groupingBy { it }.eachCount()
        // An ambiguous automatic mapping exposes original floors instead of choosing an arbitrary summary.
        val segmentStarts = candidateSegments.filter { segment -> segment.sources.none { overlaps[it.messageId]!! > 1 } }
            .associateBy { it.sources.first().messageId }
        val compressedIds = segmentStarts.values.flatMapTo(mutableSetOf()) { it.sources.map { source -> source.messageId } }
        var cursor = 0
        while (cursor <= selectedFloors.size) {
            insertions[cursor].orEmpty().forEach { insertion ->
                output += ChatMessage(role = insertion.role, text = renderInsertion(insertion.template))
            }
            if (cursor == selectedFloors.size) break
            if (useDigest && cursor == 0) {
                output += ChatMessage(role = main.history.apiRole ?: Role.SYSTEM, text = digest!!.text,
                    id = "history-digest:${main.id}:${digest.throughMessageId}")
                cursor = digest.coveredFloors
            } else {
                val segment = segmentStarts[selectedFloors[cursor].id]
                if (segment != null) {
                    output += HistoryViews.summaryMessage(segment).copy(role = main.history.apiRole ?: Role.SYSTEM)
                    cursor += if (orderedProjection) 1 else segment.sources.size
                } else if (orderedProjection && selectedFloors[cursor].id in compressedIds) {
                    cursor++
                } else {
                    val originalIndex = selected.first + cursor
                    output += wrapped(selectedFloors[cursor], floorNumbers[selectedFloors[cursor].id] ?: originalIndex + 1)
                    cursor++
                }
            }
        }
        if (output.isEmpty()) return output
        val prefix = main.history.prefix
        val suffix = main.history.suffix
        if (prefix.isNotEmpty()) output[0] = output[0].copy(text = prefix + output[0].text)
        if (suffix.isNotEmpty()) output[output.lastIndex] = output.last().copy(text = output.last().text + suffix)
        return if (main.history.mergeMessages && output.size > 1) listOf(ChatMessage(
            role = main.history.apiRole ?: Role.SYSTEM, text = output.joinToString("\n") { it.text }
        )) else output
    }

    private fun wrapped(message: ChatMessage, number: Int): ChatMessage {
        val speaker = speakerRules[message.role]
        val specific = floorRules[message.id]
        val role = specific?.apiRole ?: speaker?.apiRole ?: main.history.apiRole ?: message.role
        val prefix = specific?.prefix ?: speaker?.prefix ?: main.history.floorPrefix
        val suffix = specific?.suffix ?: speaker?.suffix ?: main.history.floorSuffix
        return message.copy(role = role, text = wrapper(prefix, message, number) + message.text + wrapper(suffix, message, number))
    }

    private fun wrapper(template: String, original: ChatMessage, floorNumber: Int): String =
        wrapperToken.replace(template) { match -> when (match.groupValues[1]) {
            "speaker.name" -> original.speakerName ?: when (original.role) {
                Role.USER -> library.userName(session)
                Role.ASSISTANT -> characterName
                Role.SYSTEM -> "系统"
            }
            "speaker" -> original.role.name.lowercase()
            else -> floorNumber.toString()
        } }

    private fun candidate(): Pending? {
        val config = processor ?: return null
        if (!config.workflow.enabled || processing || floors.isEmpty()) return null
        val currentUser = floors.indexOfLast { it.role == Role.USER && it.id == MacroReferences.currentTurnId(session) }
        if (currentUser < 0) return null
        if (semantics.globalMemory && library.memoryConfig != null) return globalCandidate(currentUser)
        if (semantics.hierarchicalMemory && config.thresholdBasis == HistoryThresholdBasis.UNCOMPRESSED_FLOORS)
            return uncompressedCandidate(currentUser)
        val eligible = floors.subList(0, currentUser)
        var cutoff = (eligible.size - config.keepRecentFloors).coerceAtLeast(0)
        if (cutoff < eligible.size && cutoff > 0 && eligible[cutoff].role != Role.USER) {
            cutoff = (cutoff downTo 0).firstOrNull { eligible[it].role == Role.USER } ?: 0
        }
        if (semantics.allValidSummaryMarks) return unmarkedCandidate(eligible, cutoff)
        val covered = digest?.coveredFloors ?: 0
        if (!legacyProcessing) {
            val coveredIds = segments.flatMapTo(mutableSetOf()) { it.sources.map { source -> source.messageId } }
            val uncovered = (covered until cutoff).filter { eligible[it].id !in coveredIds }
            if (uncovered.size < config.minimumNewFloors || uncovered.isEmpty()) return null
            val start = uncovered.first()
            var end = start
            while (end < cutoff && eligible[end].id !in coveredIds) end++
            val source = eligible.subList(start, end)
            return Pending(source, rawText(source), source.last().id, end, source.size)
        }
        val newFloors = cutoff - covered
        if (newFloors < config.minimumNewFloors || newFloors <= 0) return null
        val source = buildList {
            digest?.let { add(ChatMessage(role = Role.SYSTEM, text = it.text)) }
            addAll(eligible.subList(covered, cutoff))
        }
        return Pending(source, rawText(source), eligible[cutoff - 1].id, cutoff, newFloors)
    }

    private fun globalCandidate(currentUser: Int): Pending? {
        val config = processor ?: return null
        val eligible = floors.take(currentUser + 1)
        val sources = eligible.map(HistoryMemory::source)
        val family = HistoryHierarchy(GlobalMemory.segments(library, session, memoryMain), sources, ordered = true)
        val marked = family.validSegments().values.filter {
            GlobalMemory.inFamily(family, it.id, library, config.workflow.id)
        }.flatMapTo(mutableSetOf()) { it.sources.map(HistorySourceRef::messageId) }
        val unmarked = eligible.indices.filter { it >= config.protectFirstFloors && eligible[it].id !in marked }
        if (unmarked.size < config.minimumNewFloors) return null
        val selected = unmarked.dropLast(config.keepRecentFloors.coerceAtMost(unmarked.size)).filter { it != currentUser }
            .take(config.maximumBatchFloors ?: Int.MAX_VALUE)
        if (selected.isEmpty()) return null
        val messages = selected.map(eligible::get)
        return Pending(messages, rawText(messages), messages.last().id, selected.last() + 1, messages.size,
            selected.map(sources::get), HistorySourceKind.DISPLAY)
    }

    private fun uncompressedCandidate(currentUser: Int): Pending? {
        val config = processor ?: return null
        val kind = config.sourceKind
        val eligible = floors.take(currentUser + 1)
        val sources = eligible.map { message ->
            if (kind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening && message.rawText == null) {
                mutableIssues += Issue("history.model", "初始模型原文缺失，保留此楼层：${message.id}", false)
                null
            } else HistoryMemory.source(message, kind)
        }
        val memory = HistoryHierarchy(HistoryMemory.effectiveSegments(session, memoryMain, kind), sources, ordered = true)
        val marked = memory.validSegments().values.filter {
            memoryBinding?.extensionId == null || memory.belongsToFamily(it.id, config.workflow.id)
        }.flatMapTo(mutableSetOf()) { it.sources.map { ref -> ref.messageId } }
        val unmarked = eligible.indices.filter { sources[it] != null && eligible[it].id !in marked }
        if (unmarked.size < config.minimumNewFloors) return null
        val start = config.protectFirstFloors.coerceAtLeast(0).coerceAtMost(unmarked.size)
        val end = (unmarked.size - maxOf(1, config.keepRecentFloors)).coerceAtLeast(start)
        val selected = unmarked.subList(start, end).filter { it != currentUser }
            .take(config.maximumBatchFloors ?: Int.MAX_VALUE)
        if (selected.isEmpty()) return null
        val messages = selected.map { HistoryMemory.sourceMessage(eligible[it], kind) }
        return Pending(messages, rawText(messages), messages.last().id, selected.last() + 1, messages.size,
            selected.map { requireNotNull(sources[it]) }, kind)
    }

    private fun unmarkedCandidate(eligible: List<ChatMessage>, cutoff: Int): Pending? {
        val config = processor ?: return null
        val kind = config.sourceKind
        val sources = eligible.map { message ->
            if (kind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening && message.rawText == null) null
            else HistoryMemory.source(message, kind)
        }
        val positions = eligible.withIndex().associate { it.value.id to it.index }
        val effective = HistoryMemory.effectiveSegments(session, memoryMain, kind)
        val family = HistoryHierarchy(effective, sources, ordered = semantics.hierarchicalMemory)
        val coveredIds = effective.values
            .filter { memoryBinding?.extensionId == null || family.belongsToFamily(it.id, config.workflow.id) }
            .filter { HistoryMemory.matches(it, sources, positions) }
            .flatMapTo(mutableSetOf()) { it.sources.map { ref -> ref.messageId } }
        var cursor = 0
        while (cursor < cutoff) {
            if (sources[cursor] == null) {
                mutableIssues += Issue("history.model", "初始模型原文缺失，保留此楼层：${eligible[cursor].id}", false)
                cursor++
                continue
            }
            if (eligible[cursor].id in coveredIds) { cursor++; continue }
            val start = cursor
            while (cursor < cutoff && sources[cursor] != null && eligible[cursor].id !in coveredIds) cursor++
            if (cursor - start < config.minimumNewFloors) continue
            val messages = eligible.subList(start, cursor).map { HistoryMemory.sourceMessage(it, kind) }
            return Pending(messages, rawText(messages), messages.last().id, cursor, messages.size,
                sources.subList(start, cursor).map { requireNotNull(it) }, kind)
        }
        return null
    }

    private fun rawText(messages: List<ChatMessage>): String = messages.joinToString("\n") { "${it.role.name.lowercase()}: ${it.text}" }

    companion object {
        fun rangeValid(range: HistoryRange, count: Int): Boolean = count == 0 ||
            endpoint(range.from, count) <= endpoint(range.through, count) ||
            endpoint(range.from, count) >= count || endpoint(range.through, count) < 0
        fun selectedRange(selection: HistoryRange, count: Int): IntRange = range(selection, count)
        private fun range(range: HistoryRange, count: Int): IntRange {
            if (count == 0) return IntRange.EMPTY
            val first = endpoint(range.from, count)
            val last = endpoint(range.through, count)
            val visibleFirst = maxOf(0, first)
            val visibleLast = minOf(count - 1, last)
            return if (visibleFirst <= visibleLast) visibleFirst..visibleLast else IntRange.EMPTY
        }
        private fun endpoint(point: HistoryPoint, count: Int): Int = when (point.anchor) {
            HistoryAnchor.START -> point.value.toInt() - 1
            HistoryAnchor.END -> count - point.value.toInt()
            HistoryAnchor.PERCENT -> floor((count - 1) * point.value / 100.0).toInt().coerceIn(0, count - 1)
        }
        private fun gap(point: HistoryPoint, count: Int): Int = when (point.anchor) {
            HistoryAnchor.START -> point.value.toInt().coerceIn(0, count)
            HistoryAnchor.END -> (count - point.value.toInt()).coerceIn(0, count)
            HistoryAnchor.PERCENT -> floor(count * point.value / 100.0).toInt().coerceIn(0, count)
        }
    }
}
