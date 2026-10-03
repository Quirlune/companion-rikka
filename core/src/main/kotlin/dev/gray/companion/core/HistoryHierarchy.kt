package dev.gray.companion.core

/** One source index and one memoized graph walk per projection or selection. */
internal class HistoryHierarchy(
    private val segments: Map<String, HistoryMemorySegment>,
    private val sources: List<HistorySourceRef?>,
    private val ordered: Boolean
) {
    private val positions = sources.withIndex().mapNotNull { (index, ref) -> ref?.let { it.messageId to index } }.toMap()
    private val validity = mutableMapOf<String, Boolean>()
    private val visiting = mutableSetOf<String>()
    private val families = mutableMapOf<Pair<String, String>, Boolean>()

    fun valid(id: String): Boolean {
        validity[id]?.let { return it }
        val segment = segments[id] ?: return false
        if (!visiting.add(id)) return false
        val matches = if (ordered) HistoryMemory.matchesOrdered(segment, sources, positions)
            else HistoryMemory.matches(segment, sources, positions)
        val result = matches && (!ordered || segment.childSegmentIds.all(::valid))
        visiting.remove(id)
        validity[id] = result
        return result
    }

    fun validSegments(): Map<String, HistoryMemorySegment> = segments.filterKeys(::valid)

    fun belongsToFamily(id: String, workflowId: String): Boolean {
        if (workflowId.isBlank()) return true
        return families.getOrPut(id to workflowId) {
            val item = segments[id]
            item != null && if (item.childSegmentIds.isEmpty()) item.workflowId == workflowId
                else item.childSegmentIds.all { belongsToFamily(it, workflowId) }
        }
    }

    fun roots(eligible: Collection<HistoryMemorySegment>): List<HistoryMemorySegment> {
        val children = eligible.flatMapTo(mutableSetOf()) { it.childSegmentIds }
        return eligible.filter { it.id !in children }.sortedWith(compareBy<HistoryMemorySegment> {
            positions[it.sources.first().messageId] ?: Int.MAX_VALUE
        }.thenBy { it.id })
    }

    fun compressed(modes: Map<String, HistorySegmentMode>, projectionVersion: Int): List<HistoryMemorySegment> {
        require(projectionVersion in 1..2) { "历史副本投影版本不受支持" }
        val chosen = if (projectionVersion == 1) modes.filterValues { it == HistorySegmentMode.COMPRESS }.keys
            .map { id -> segments[id] ?: error("记忆段不存在：$id") }.filter { valid(it.id) }
        else {
            val referenced = modes.keys.map { id -> segments[id] ?: error("记忆段不存在：$id") }
            val descendants = mutableSetOf<String>()
            fun collect(id: String) {
                segments[id]?.childSegmentIds.orEmpty().forEach { if (descendants.add(it)) collect(it) }
            }
            referenced.forEach { collect(it.id) }
            buildList {
                fun visit(id: String) {
                    val item = segments[id] ?: error("记忆段不存在：$id")
                    if (!valid(id) || modes[id] == HistorySegmentMode.EXPAND) item.childSegmentIds.forEach(::visit)
                    else add(item)
                }
                referenced.filter { it.id !in descendants }.forEach { visit(it.id) }
            }
        }
        val covered = mutableSetOf<String>()
        require(chosen.all { segment -> segment.sources.all { covered.add(it.messageId) } }) { "压缩记忆段重叠" }
        return chosen.sortedBy { positions[it.sources.first().messageId] }
    }
}
