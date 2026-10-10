package dev.gray.companion.core

/** Resolves runtime results by resource/call/node slugs without changing existing output addresses. */
internal object DynamicPromptQueries {
    data class Target(val resource: ExtensionResourceRef, val workflow: Workflow, val node: WorkflowNode) {
        val origin get() = ResultOrigin(resource.id, workflow.id, node.id, node.resultCategory)
        val address get() = "result.${node.outputKey}"
        val location get() = ResourceLocation(resource, workflowId = workflow.id, nodeId = node.id)
    }
    fun accepts(plan: PromptReferenceLanguage.QueryPlan) = plan.sourceType in setOf(
        PromptReferenceLanguage.SourceType.MAIN, PromptReferenceLanguage.SourceType.GROUP)
    fun supportError(plan: PromptReferenceLanguage.QueryPlan): String? = when {
        !accepts(plan) -> "不是调用结果来源"
        plan.folder != null -> "调用结果没有目录元数据"
        plan.historySelection != null -> "调用结果不能作为历史选择"
        else -> null
    }
    fun targets(library: Library, plan: PromptReferenceLanguage.QueryPlan, session: Session? = null): List<Target> {
        if (supportError(plan) != null) return emptyList()
        val tags = library.promptTags.associate { it.slug to it.id }
        if ((plan.requiredTags + plan.anyTags + plan.excludedTags).any { it !in tags }) return emptyList()
        val required = plan.requiredTags.map { tags.getValue(it) }
        val any = plan.anyTags.map { tags.getValue(it) }
        val excluded = plan.excludedTags.map { tags.getValue(it) }
        fun source(slug: String) = plan.source == PromptReferenceLanguage.SourceScope.Loaded ||
            (plan.source as? PromptReferenceLanguage.SourceScope.Named)?.slug == slug
        val owners = when (plan.sourceType) {
            PromptReferenceLanguage.SourceType.MAIN -> library.mainCalls.filter {
                source(it.slug) && (session == null || it.id == session.mainCallId)
            }.map { Triple(ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id), it.slug, it.configuredWorkflows()) }
            PromptReferenceLanguage.SourceType.GROUP -> (if (session == null) library.groups else library.loadedCallGroups(session))
                .filter { source(it.slug) }.map { Triple(ExtensionResourceRef(ExtensionResourceKind.GROUP, it.id), it.slug, it.workflows) }
            else -> emptyList()
        }
        return owners.flatMap { (ref, _, workflows) -> workflows.filter {
            (session == null || it.enabled) && (plan.call == null || it.slug == plan.call)
        }.flatMap { workflow -> workflow.nodes.filter { node ->
            (session == null || node.enabled) && (plan.name == null || node.slug == plan.name) &&
                node.tagIds.containsAll(required) && (any.isEmpty() || node.tagIds.any { it in any }) &&
                node.tagIds.none { it in excluded }
        }.map { Target(ref, workflow, it) } } }
    }
    fun read(plan: PromptReferenceLanguage.QueryPlan, library: Library, session: Session,
        results: Map<String, String>, origins: Map<String, ResultOrigin>): Pair<String, String?> {
        val unknown = (plan.requiredTags + plan.anyTags + plan.excludedTags).filter { slug -> library.promptTags.none { it.slug == slug } }
        if (unknown.isNotEmpty()) return "" to "标签英文标识不存在：${unknown.joinToString(", ")}"
        val selected = targets(library, plan, session)
        if (plan.name != null && selected.size > 1) return "" to "节点英文标识不唯一；请同时指定来源和调用"
        val selector = when (val at = plan.at) {
            PromptReferenceLanguage.VersionSelector.Latest -> "latest"
            is PromptReferenceLanguage.VersionSelector.Turn -> "turn-${at.offset}"
            is PromptReferenceLanguage.VersionSelector.Update -> "update-${at.offset}"
            is PromptReferenceLanguage.VersionSelector.Version -> "version-${at.number}"
        }
        fun same(a: ResultOrigin?, b: ResultOrigin) = a != null && a.resourceId == b.resourceId && a.workflowId == b.workflowId && a.nodeId == b.nodeId
        val view = MacroReadView(library, session)
        val values = selected.mapNotNull { target ->
            if (!view.originVisible(target.origin) || (selector == "latest" && view.currentValueUnavailable(target.address))) return@mapNotNull null
            val version = view.selectVersion(target.address, selector)
            if (version?.enabled == false) return@mapNotNull null
            if (selector == "latest") results[target.node.outputKey]?.takeIf { same(origins[target.node.outputKey], target.origin) }
            else version?.text?.takeIf { same(version.valueOrigin ?: version.origin, target.origin) }
        }
        return values.joinToString("\n") to null
    }
}
