package dev.gray.companion.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable enum class HistorySegmentMode { COMPRESS, EXPAND }

/** References an existing text revision, never a second stored copy of its text. */
@Serializable data class HistorySourceRef(val messageId: String, val revisionId: String, val fingerprint: String,
    val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY)
@Serializable data class HistoryMemorySegment(
    val id: String, val title: String, val summary: String,
    val sources: List<HistorySourceRef>, val workflowId: String
)
@Serializable data class HistoryViewRevision(
    val viewId: String, val name: String, val revision: Int, val captureId: String,
    val baseViewId: String? = null, val baseRevision: Int? = null,
    val segmentModes: Map<String, HistorySegmentMode> = emptyMap(),
    val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY
)
@Serializable data class HistoryMemoryState(
    val segments: Map<String, HistoryMemorySegment> = emptyMap(),
    val captures: Map<String, List<HistorySourceRef>> = emptyMap(),
    val views: Map<String, List<HistoryViewRevision>> = emptyMap(),
    val unavailableViewIds: Set<String> = emptySet()
)
@Serializable data class HistoryMemoryPatch(
    val segments: List<HistoryMemorySegment> = emptyList(),
    val captures: Map<String, List<HistorySourceRef>> = emptyMap(),
    val views: List<HistoryViewRevision> = emptyList(),
    val expectedViewRevisions: Map<String, Int> = emptyMap()
) {
    fun isEmpty(): Boolean = segments.isEmpty() && captures.isEmpty() && views.isEmpty() && expectedViewRevisions.isEmpty()
}
@Serializable data class HistorySelection(
    val viewId: String? = null, val revision: Int? = null, val range: HistoryRange = HistoryRange(),
    val compressed: Boolean = false, val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY
)
data class HistoryProjection(
    val messages: List<ChatMessage>, val issues: List<Issue> = emptyList(),
    val sourceIds: List<String> = emptyList()
)

/** Source identity, capture sharing and immutable view revisions are shared by actions and readers. */
object HistoryMemory {
    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) { bytes.forEach { byte ->
            val valueByte = byte.toInt() and 255
            append(hex[valueByte ushr 4]); append(hex[valueByte and 15])
        } }
    }

    /** Model text is immutable provenance. User/system floors keep their selected text revision. */
    fun source(message: ChatMessage): HistorySourceRef = source(message, HistorySourceKind.DISPLAY)

    fun source(message: ChatMessage, kind: HistorySourceKind): HistorySourceRef {
        val original = sourceMessage(message, kind)
        val identity = "${original.role}\u0000${original.outputSlot}\u0000${original.text}"
        val fingerprint = hash(if (kind == HistorySourceKind.DISPLAY) identity else "MODEL_RAW\u0000$identity")
        return HistorySourceRef(original.id, original.activeTextRevisionId ?: original.id, fingerprint, kind)
    }

    internal fun sourceMessage(message: ChatMessage, kind: HistorySourceKind): ChatMessage =
        if (kind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening) message.copy(
            text = requireNotNull(message.rawText) { "初始模型原文缺失：${message.id}" },
            activeTextRevisionId = message.id, activeTextRevisionCreatedAt = message.createdAt, bodyDisplay = null
        ) else message

    fun captureId(sources: List<HistorySourceRef>): String = "capture-" + hash(sources.joinToString("\n") {
        val identity = "${it.messageId}\u0000${it.revisionId}\u0000${it.fingerprint}"
        if (it.sourceKind == HistorySourceKind.DISPLAY) identity else "MODEL_RAW\u0000$identity"
    })

    fun segment(title: String, summary: String, messages: List<ChatMessage>, workflowId: String): HistoryMemorySegment {
        require(messages.all { it.outputSlot == OutputSlot.BODY && !it.deleted }) { "记忆来源必须是有效正文楼层" }
        return segmentFromSources(title, summary, messages.map(::source), workflowId)
    }

    fun segmentFromSources(title: String, summary: String, sources: List<HistorySourceRef>, workflowId: String): HistoryMemorySegment {
        require(summary.isNotBlank() && sources.isNotEmpty()) { "记忆摘要和来源不能为空" }
        require(sources.map { it.messageId }.distinct().size == sources.size) { "记忆来源不能重复" }
        require(sources.map { it.sourceKind }.distinct().size == 1) { "记忆段不能混合历史来源" }
        val id = "memory-" + hash("$workflowId\u0000${captureId(sources)}\u0000$summary")
        return HistoryMemorySegment(id, title, summary, sources, workflowId)
    }

    /** A pure compatibility view; callers decide whether an explicit operation persists it. */
    fun legacySegment(session: Session, main: MainCall): HistoryMemorySegment? {
        val digest = session.historyDigests[main.id] ?: return null
        val body = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
        if (digest.coveredFloors !in 1..body.size || body[digest.coveredFloors - 1].id != digest.throughMessageId ||
            digest.workflowId != main.history.processor.workflow.id || digest.text.isBlank()) return null
        return segment("旧版历史摘要", digest.text, body.take(digest.coveredFloors), digest.workflowId)
    }

    fun effectiveSegments(session: Session, main: MainCall,
                          sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY): Map<String, HistoryMemorySegment> =
        session.historyMemory.segments.filterValues { segment ->
            segment.sources.isNotEmpty() && segment.sources.all { it.sourceKind == sourceKind }
        } + listOfNotNull(if (sourceKind == HistorySourceKind.DISPLAY) legacySegment(session, main) else null).associateBy { it.id }

    /** The selected messages are authoritative; inactive generations provide only missing identities. */
    fun sourceIndex(session: Session): Map<String, ChatMessage> = buildMap {
        session.turnRecords.forEach { record -> record.variants.forEach { variant ->
            variant.replies.forEach { putIfAbsent(it.id, it) }
        } }
        session.messages.forEach { put(it.id, it) }
    }

    fun resolve(ref: HistorySourceRef, index: Map<String, ChatMessage>): ChatMessage? {
        val message = index[ref.messageId]?.takeUnless { it.deleted || it.outputSlot != OutputSlot.BODY } ?: return null
        val resolved = if (ref.sourceKind == HistorySourceKind.MODEL_RAW && message.role == Role.ASSISTANT && !message.authoredOpening) {
            if (message.rawText == null || ref.revisionId != message.id) return null
            sourceMessage(message, ref.sourceKind)
        } else if ((message.activeTextRevisionId ?: message.id) == ref.revisionId) message else {
            val revision = message.textRevisions.firstOrNull { it.id == ref.revisionId } ?: return null
            message.copy(text = revision.text, activeTextRevisionId = revision.id,
                activeTextRevisionCreatedAt = revision.createdAt, bodyDisplay = revision.bodyDisplay)
        }
        return resolved.takeIf { source(it, ref.sourceKind) == ref }
    }

    fun isCurrent(segment: HistoryMemorySegment, messages: List<ChatMessage>): Boolean {
        val kind = segment.sources.firstOrNull()?.sourceKind ?: return false
        val sources = messages.filterNot { it.deleted }.map { runCatching { source(it, kind) }.getOrNull() }
        return matches(segment, sources, sources.withIndex().mapNotNull { indexed -> indexed.value?.let { it.messageId to indexed.index } }.toMap())
    }

    internal fun matches(segment: HistoryMemorySegment, sources: List<HistorySourceRef?>, positions: Map<String, Int>): Boolean {
        val start = segment.sources.firstOrNull()?.messageId?.let(positions::get) ?: return false
        return segment.sources.indices.all { offset -> sources.getOrNull(start + offset) == segment.sources[offset] }
    }

    fun view(state: HistoryMemoryState, id: String, revision: Int? = null): HistoryViewRevision? =
        state.views[id]?.let { versions -> if (revision == null) versions.lastOrNull() else versions.firstOrNull { it.revision == revision } }

    fun modes(state: HistoryMemoryState, selected: HistoryViewRevision): Map<String, HistorySegmentMode> {
        val visiting = mutableSetOf<Pair<String, Int>>()
        val chain = mutableListOf<HistoryViewRevision>()
        var current: HistoryViewRevision? = selected
        while (current != null) {
            val item = current
            require(visiting.add(item.viewId to item.revision)) { "历史副本存在循环引用" }
            chain += item
            current = if (item.baseViewId == null) null else {
                val base = view(state, item.baseViewId, requireNotNull(item.baseRevision))
                    ?: error("历史副本的基础版本不存在")
                require(base.captureId == item.captureId && base.sourceKind == item.sourceKind) { "历史副本捕获来源不一致" }
                base
            }
        }
        return buildMap { chain.asReversed().forEach { putAll(it.segmentModes) } }
    }

    fun apply(state: HistoryMemoryState, patch: HistoryMemoryPatch): HistoryMemoryState {
        if (patch.isEmpty()) return state
        patch.expectedViewRevisions.forEach { (id, revision) ->
            require((state.views[id]?.lastOrNull()?.revision ?: 0) == revision) { "历史副本版本已变化：$id" }
        }
        val segments = state.segments.toMutableMap()
        patch.segments.forEach { segment ->
            require(segment.sources.isNotEmpty() && segment.summary.isNotBlank()) { "记忆来源或摘要为空" }
            require(segment.sources.map { it.sourceKind }.distinct().size == 1) { "记忆段不能混合历史来源" }
            require(segment.sources.map { it.messageId }.distinct().size == segment.sources.size) { "记忆来源重复" }
            require(segments[segment.id] == null || segments[segment.id] == segment) { "记忆身份冲突" }
            segments[segment.id] = segment
        }
        val captures = state.captures.toMutableMap()
        patch.captures.forEach { (id, sources) ->
            require(id == captureId(sources)) { "历史捕获身份不一致" }
            require(sources.map { it.sourceKind }.distinct().size <= 1) { "历史捕获不能混合来源" }
            require(sources.map { it.messageId }.distinct().size == sources.size) { "历史来源楼层重复" }
            require(captures[id] == null || captures[id] == sources) { "历史捕获身份冲突" }
            captures[id] = sources
        }
        val views = state.views.toMutableMap()
        for (item in patch.views) {
            require(MacroNaming.isValidSlug(item.viewId)) { "历史副本标识须为英文 slug" }
            require(item.name.isNotBlank()) { "历史副本名称不能为空" }
            val previous = views[item.viewId].orEmpty()
            require(item.revision == (previous.lastOrNull()?.revision ?: 0) + 1) { "历史副本版本不连续" }
            require(item.viewId in patch.expectedViewRevisions) { "历史副本缺少版本前置条件" }
            require(captures.containsKey(item.captureId)) { "历史副本缺少来源捕获" }
            require((item.baseViewId == null) == (item.baseRevision == null)) { "历史副本基础版本不完整" }
            val interim = HistoryMemoryState(segments, captures, views)
            if (item.baseViewId != null) require(view(interim, item.baseViewId, item.baseRevision) != null) { "历史副本基础版本不存在" }
            val effective = modes(interim, item)
            val sources = captures.getValue(item.captureId)
            require(sources.all { it.sourceKind == item.sourceKind }) { "历史副本来源类型不一致" }
            val compressed = mutableSetOf<String>()
            effective.forEach { (id, mode) ->
                val segment = segments[id] ?: error("记忆段不存在：$id")
                val start = sources.indexOf(segment.sources.first())
                require(start >= 0 && sources.drop(start).take(segment.sources.size) == segment.sources) { "记忆段不属于此历史捕获：$id" }
                if (mode == HistorySegmentMode.COMPRESS) require(segment.sources.all { compressed.add(it.messageId) }) { "压缩记忆段重叠" }
            }
            views[item.viewId] = previous + item
        }
        return HistoryMemoryState(segments, captures, views, state.unavailableViewIds - patch.views.map { it.viewId }.toSet())
    }
}
