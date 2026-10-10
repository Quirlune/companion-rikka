package dev.gray.companion.core

import dev.gray.companion.core.PromptReferenceLanguage.QueryPlan
import dev.gray.companion.core.PromptReferenceLanguage.SourceScope
import dev.gray.companion.core.PromptReferenceLanguage.SourceType
import dev.gray.companion.core.PromptReferenceLanguage.VersionSelector

/** Selects static entries already activated by loadedResources; rendering remains the caller's job. */
internal object StaticPromptQueries {
    data class Selection(
        val entries: List<Pair<String, PromptEntry>> = emptyList(),
        val diagnostic: String? = null
    )

    /** No activation policy: runtime and configuration dependency scans choose their own candidates. */
    class SelectionPredicate internal constructor(
        private val plan: QueryPlan,
        private val required: Set<String>,
        private val any: Set<String>,
        private val excluded: Set<String>
    ) {
        fun matchesSource(slug: String): Boolean =
            plan.source == SourceScope.Loaded || (plan.source as? SourceScope.Named)?.slug == slug

        fun matchesEntry(entry: PromptEntry): Boolean =
            (plan.name == null || entry.slug == plan.name) && entry.tagIds.containsAll(required) &&
                (any.isEmpty() || entry.tagIds.any { it in any }) && entry.tagIds.none { it in excluded }
    }

    data class PredicateResult(val predicate: SelectionPredicate? = null, val diagnostic: String? = null)

    /** Pure capability check shared by binding, saved-template validation and rendering. */
    fun supportError(plan: QueryPlan): String? = when {
        plan.sourceType !in setOf(SourceType.PACK, SourceType.CHARACTER) ->
            "此来源的新式引用尚未绑定运行时：${plan.sourceType.keyword}"
        plan.call != null || plan.historySelection != null -> "静态词条引用不支持调用或历史选择"
        plan.folder != null -> "静态词条尚无目录元数据，不能使用 folder/under"
        plan.at != VersionSelector.Latest -> "静态词条新式引用目前只支持 at:latest"
        else -> null
    }

    fun predicate(plan: QueryPlan, tags: List<PromptTag>): PredicateResult {
        supportError(plan)?.let { return PredicateResult(diagnostic = it) }
        // Catalogue slugs are aliases. Assignments on entries contain stable tag IDs.
        val tagsBySlug = tags.associate { it.slug to it.id }
        val missing = (plan.requiredTags + plan.anyTags + plan.excludedTags).filterNot { it in tagsBySlug }
        if (missing.isNotEmpty())
            return PredicateResult(diagnostic = "标签英文标识不存在：${missing.sorted().joinToString(", ")}")
        return PredicateResult(SelectionPredicate(plan,
            plan.requiredTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) },
            plan.anyTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) },
            plan.excludedTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) }))
    }

    fun bind(plan: QueryPlan, resources: LoadedResources): Selection {
        val resolved = predicate(plan, resources.macroView.library.promptTags)
        val predicate = resolved.predicate ?: return Selection(diagnostic = resolved.diagnostic)

        val sources = when (plan.sourceType) {
            SourceType.CHARACTER -> resources.character?.takeIf { predicate.matchesSource(it.slug) }
                ?.let { setOf("character") }.orEmpty()
            SourceType.PACK -> resources.packages.asSequence()
                .filter { predicate.matchesSource(it.slug) }
                .map { "pack.${it.namespace}" }.toSet()
            else -> emptySet()
        }
        val entries = resources.entries.filter { (source, entry) ->
            source in sources && predicate.matchesEntry(entry)
        }
        if (plan.name != null && entries.size > 1)
            return Selection(diagnostic = "词条英文标识定位不唯一：${plan.name}")
        return Selection(entries)
    }
}
