package dev.gray.companion.core

/** Edits only user layout preferences, never accepted card/runtime values or resource bindings. */
object CardLayoutCommands {
    fun update(library: Library, sessionId: String, expected: List<CardPlacement>,
               next: List<CardPlacement>): ExtensionEditResult {
        fun failure(message: String) = ExtensionEditResult(null, listOf(Issue("session.$sessionId.cardPlacements", message)))
        if (library.selectedSessionId != sessionId) return failure("当前会话已切换，请重新打开卡片布局")
        val session = library.sessions.firstOrNull { it.id == sessionId } ?: return failure("当前会话不存在")
        if (session.pendingRun != null) return failure("当前会话仍有断点，请先恢复或结束这一轮")
        if (session.cardPlacements != expected) return failure("卡片布局已改变，请重新打开后调整")
        val issues = validate(next, "session.$sessionId.cardPlacements")
        if (issues.isNotEmpty()) return ExtensionEditResult(null, issues)
        val cards = CardDisplays.configured(library, session).associateBy { it.nodeId }
        cards.values.filter { !it.definition.userAdjustable }.forEach { card ->
            if (expected.firstOrNull { it.nodeId == card.nodeId } != next.firstOrNull { it.nodeId == card.nodeId })
                return failure("作者已锁定此卡片的位置与显示设置")
        }
        next.filter { placement -> expected.firstOrNull { it.nodeId == placement.nodeId } != placement }
            .forEach { placement ->
                val card = cards[placement.nodeId]
                if (card != null && placement.region !in CardDisplays.allowedRegions(card.definition.presentation))
                    return failure("此行为的显示品类不支持所选区域，请重新放置")
            }
        val candidate = library.copy(sessions = library.sessions.map {
            if (it.id == sessionId) it.copy(cardPlacements = next.toList()) else it
        })
        return ExtensionEditResult(candidate, changes = listOf("调整当前会话卡片布局"))
    }

    /** Orphan IDs intentionally remain valid: unloading a behavior must preserve user choices. */
    fun validate(placements: List<CardPlacement>, path: String): List<Issue> = buildList {
        if (placements.map { it.nodeId }.distinct().size != placements.size)
            add(Issue(path, "同一卡片不能重复放置"))
        placements.forEachIndexed { index, placement ->
            if (placement.nodeId.isBlank()) add(Issue("$path.$index.nodeId", "卡片行为标识不能为空"))
            if (placement.order < 0) add(Issue("$path.$index.order", "卡片顺序不能小于零"))
        }
    }
}
