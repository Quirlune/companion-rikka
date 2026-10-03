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

    fun bind(plan: QueryPlan, resources: LoadedResources): Selection {
        if (plan.sourceType !in setOf(SourceType.PACK, SourceType.CHARACTER))
            return Selection(diagnostic = "此来源的新式引用尚未绑定运行时：${plan.sourceType.keyword}")
        if (plan.call != null || plan.historySelection != null)
            return Selection(diagnostic = "静态词条引用不支持调用或历史选择")
        if (plan.folder != null)
            return Selection(diagnostic = "静态词条尚无目录元数据，不能使用 folder/under")
        if (plan.at != VersionSelector.Latest)
            return Selection(diagnostic = "静态词条新式引用目前只支持 at:latest")

        val sources = when (plan.sourceType) {
            SourceType.CHARACTER -> resources.character?.takeIf { matchesSource(plan.source, it.slug) }
                ?.let { setOf("character") }.orEmpty()
            SourceType.PACK -> resources.packages.asSequence()
                .filter { matchesSource(plan.source, it.slug) }
                .map { "pack.${it.namespace}" }.toSet()
            else -> emptySet()
        }
        // Catalogue slugs are aliases. Assignments on entries contain stable tag IDs.
        val tagsBySlug = resources.macroView.library.promptTags.associate { it.slug to it.id }
        val requested = plan.requiredTags + plan.anyTags + plan.excludedTags
        val missing = requested.filterNot { it in tagsBySlug }
        if (missing.isNotEmpty())
            return Selection(diagnostic = "标签英文标识不存在：${missing.sorted().joinToString(", ")}")
        val required = plan.requiredTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) }
        val any = plan.anyTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) }
        val excluded = plan.excludedTags.mapTo(mutableSetOf()) { tagsBySlug.getValue(it) }
        val entries = resources.entries.filter { (source, entry) ->
            source in sources && (plan.name == null || entry.slug == plan.name) &&
                entry.tagIds.containsAll(required) &&
                (any.isEmpty() || entry.tagIds.any { it in any }) &&
                entry.tagIds.none { it in excluded }
        }
        if (plan.name != null && entries.size > 1)
            return Selection(diagnostic = "词条英文标识定位不唯一：${plan.name}")
        return Selection(entries)
    }

    private fun matchesSource(scope: SourceScope, slug: String): Boolean =
        scope == SourceScope.Loaded || (scope as? SourceScope.Named)?.slug == slug
}
