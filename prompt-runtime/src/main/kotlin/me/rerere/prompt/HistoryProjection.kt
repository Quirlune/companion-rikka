package me.rerere.prompt

import kotlinx.serialization.Serializable

@Serializable
data class HistoryPolicy(
    val summary: ReferenceId? = null,
    val pinnedMessageIds: Set<String> = emptySet(),
)

data class HistoryProjection(val covered: List<SourceVersion>, val summary: String)

/** Summaries replace a verified prefix only. They never delete or rewrite source history. */
object HistoryProjector {
    fun project(
        policy: HistoryPolicy,
        resolved: ResolvedReference?,
        sources: List<SourceVersion>,
    ): HistoryProjection? {
        if (resolved?.status != ReferenceStatus.READY || resolved.id != policy.summary) return null
        val artifact = resolved.artifact ?: return null
        val covered = artifact.ticket.sources
        if (covered.isEmpty() || covered.size >= sources.size || artifact.text.isBlank())
            return null
        if (sources.take(covered.size) != covered) return null
        if (covered.any { it.id in policy.pinnedMessageIds }) return null
        return HistoryProjection(covered.toList(), artifact.text)
    }
}
