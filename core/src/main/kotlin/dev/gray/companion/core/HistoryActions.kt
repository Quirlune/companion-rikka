package dev.gray.companion.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal object HistoryActions {
    fun selectedIds(text: String, pattern: String, group: Int, distinct: Boolean = true): List<String> {
        if (text.isBlank()) return emptyList()
        val values = if (pattern.isNotBlank()) {
            SafePatterns.validate(pattern, group)?.let { error(it) }
            SafePatterns.extractAll(pattern, text, group).also {
                if (it.isEmpty()) throw OutputFormatException("记忆选择正则未匹配任何 ID")
            }
        } else if (text.trim().startsWith("[")) {
            val array = Json.parseToJsonElement(text) as? JsonArray ?: error("记忆选择须为 ID 数组")
            array.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("记忆 ID 须为字符串") }
        } else text.split(Regex("[\\s,;]+"))
        return values.map { it.trim() }.filter { it.isNotBlank() }.let { if (distinct) it.distinct() else it }
    }

    fun select(session: Session, main: MainCall, selection: HistorySelection, leafWorkflowId: String,
               mode: String, count: Int, requireCount: Boolean, rules: MemoryStageRules? = null,
               library: Library? = null, executionVersion: Int = ExecutionSemantics.CURRENT): String {
        require(rules != null || count >= 0 && (!requireCount || count > 0)) { "记忆选择数量无效" }
        require(mode in setOf("ROOTS", "UNMERGED_LEAVES")) { "记忆选择模式无效" }
        val sources = HistoryViews.sources(session, selection, main, executionVersion)
        val all = GlobalMemory.segments(library, session, main, selection.sourceKind)
        val hierarchy = HistoryHierarchy(all, sources, ordered = true)
        val valid = hierarchy.validSegments()
        val family = valid.values.filter {
            if (rules != null && library != null) GlobalMemory.inFamily(hierarchy, it.id, library, leafWorkflowId)
            else hierarchy.belongsToFamily(it.id, leafWorkflowId)
        }
        val merged = valid.values.flatMapTo(mutableSetOf()) { it.childSegmentIds }
        val candidates = if (mode == "ROOTS") hierarchy.roots(family) else hierarchy.roots(family.filter {
            it.childSegmentIds.isEmpty() && it.id !in merged
        })
        val selected = if (rules == null) {
            val items = if (count == 0) candidates else candidates.take(count)
            if (requireCount && items.size < count) return ""
            items
        } else {
            require(rules.minimumNewFloors >= 1 && rules.protectFirstFloors >= 0 && rules.keepRecentFloors >= 0 &&
                rules.maximumBatchFloors?.let { it >= 1 } != false) { "大摘要楼层范围无效" }
            val body = session.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }
            val positions = body.withIndex().associate { it.value.id to it.index }
            val eligible = candidates.filter { child -> child.sources.all { (positions[it.messageId] ?: -1) >= rules.protectFirstFloors } }
            val eligibleSources = eligible.flatMap { it.sources }.distinct().sortedBy { positions[it.messageId] }
            if (eligibleSources.size < rules.minimumNewFloors) return ""
            val retained = eligibleSources.takeLast(rules.keepRecentFloors.coerceAtMost(eligibleSources.size)).toSet()
            val available = eligible.filter { child -> child.sources.none { it in retained } }
            var size = 0
            available.takeWhile { child ->
                val next = size.toLong() + child.sources.size
                (next <= (rules.maximumBatchFloors ?: Int.MAX_VALUE)).also { if (it) size = next.toInt() }
            }.takeIf { it.size >= 2 } ?: return ""
        }
        if (selected.isEmpty()) return ""
        val used = mutableSetOf<String>()
        require(selected.all { it.sources.all { ref -> used.add(ref.messageId) } }) { "所选记忆来源重叠" }
        return JsonArray(selected.map { JsonPrimitive(it.id) }).toString()
    }

    fun read(session: Session, main: MainCall, ids: List<String>, library: Library? = null): String {
        val all = HistorySourceKind.entries.flatMap { GlobalMemory.segments(library, session, main, it).entries }
            .associate { it.key to it.value }
        val index = HistoryMemory.sourceIndex(session)
        return ids.map { id ->
            val segment = all[id] ?: error("记忆段不存在：$id")
            require(segment.sources.all { HistoryMemory.resolve(it, index) != null }) { "记忆原文修订不存在：$id" }
            HistoryViews.summaryMessage(segment).text
        }.joinToString("\n\n")
    }

    fun parent(session: Session, main: MainCall, selection: HistorySelection, ids: List<String>,
               title: String, summary: String, workflowId: String, library: Library? = null,
               executionVersion: Int = ExecutionSemantics.CURRENT): HistoryMemoryPatch {
        require(ids.size >= 2 && ids.distinct().size == ids.size) { "父摘要需要至少两个不重复的子记忆" }
        val all = GlobalMemory.segments(library, session, main, selection.sourceKind)
        val children = ids.map { all[it] ?: error("子记忆段不存在或来源不一致：$it") }
        val parent = HistoryMemory.segmentFromSources(title, summary, children.flatMap { it.sources }, workflowId, ids)
        val hierarchy = HistoryHierarchy(all + (parent.id to parent), HistoryViews.sources(session, selection, main, executionVersion), ordered = true)
        require(hierarchy.valid(parent.id)) { "子记忆来源已变化、顺序无效或不属于此历史" }
        val needed = children.filter { it.id !in session.historyMemory.segments }
        return HistoryMemoryPatch(segments = needed + parent)
    }

}
