package dev.gray.companion.core

data class ResourceMutationPlan(
    val configuration: ConfigurationPlan?,
    val issues: List<Issue> = emptyList(),
    val usages: List<ResourceUse> = emptyList(),
    val changes: List<String> = emptyList(),
    val destination: ResourceLocation? = null,
    val draftLibrary: Library? = null,
    internal val source: ResourceLocation? = null,
    internal val sourceBefore: Any? = null,
    internal val deletionTarget: ResourceLocation? = null,
    internal val cleanupAssociations: Boolean = false,
    internal val separationOwner: ExtensionFieldRef? = null,
    internal val separationExpression: String? = null
) {
    val canApply: Boolean get() = configuration != null && issues.none { it.error }
}

/** Fixed candidate IDs and the existing strict configuration transaction; never touches runtime. */
object ResourceEditCommands {
    private val macroKey = Regex("[\\p{L}_][\\p{L}\\p{N}_-]*")

    internal fun entries(library: Library, ref: ExtensionResourceRef): List<PromptEntry> = when (ref.kind) {
        ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == ref.id }?.entries.orEmpty()
        ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == ref.id }?.entries.orEmpty()
        else -> emptyList()
    }

    private fun resource(library: Library, ref: ExtensionResourceRef): Any? = when (ref.kind) {
        ExtensionResourceKind.CHARACTER -> library.characters.singleOrNull { it.id == ref.id }
        ExtensionResourceKind.PACKAGE -> library.packages.singleOrNull { it.id == ref.id }
        ExtensionResourceKind.MAIN -> library.mainCalls.singleOrNull { it.id == ref.id }
        ExtensionResourceKind.GROUP -> library.groups.singleOrNull { it.id == ref.id }
    }

    private fun unique(base: String, used: Collection<String>): String = generateSequence(1) { it + 1 }
        .map { if (it == 1) base else "${base}_$it" }.first { it !in used }

    /** Shared pure local draft copy helper. Existing macro keys, conditions and tags are retained. */
    fun duplicateEntry(entry: PromptEntry, existing: List<PromptEntry>, character: Boolean = false): PromptEntry {
        val stem = entry.key.takeIf { macroKey.matches(it) && (!character || it != "name") } ?: "entry"
        return entry.copy(id = newId(), key = unique("${stem}_copy", existing.map { it.key } + if (character) listOf("name") else emptyList()),
            title = unique(entry.title.ifBlank { "词条" } + " 副本", existing.map { it.title }),
            slug = unique((entry.slug.takeIf(MacroNaming::isValidSlug) ?: "entry") + "_copy", existing.map { it.slug }))
    }

    fun previewCopy(library: Library, source: ResourceLocation): ResourceMutationPlan {
        val old = resource(library, source.resource) ?: return failure(source, "复制来源不存在或不唯一")
        if (source.workflowId != null || source.nodeId != null || source.blockId != null) return failure(source, "此处支持复制完整资源或角色／提示词包词条")
        if (source.entryId != null) {
            val entry = entries(library, source.resource).singleOrNull { it.id == source.entryId } ?: return failure(source, "词条不存在或不唯一")
            val copied = duplicateEntry(entry, entries(library, source.resource), source.resource.kind == ExtensionResourceKind.CHARACTER)
            val candidate = withEntries(library, source.resource, entries(library, source.resource) + copied)
            val destination = ResourceLocations.fromField(ExtensionFieldRef(source.resource.kind, source.resource.id, ExtensionField.ENTRY_TEXT, entryId = copied.id))
            return preview(library, candidate, "复制词条", source, destination, listOf("创建词条 ${copied.title}；现有精确引用继续指向原词条", "副本保留分类和标签，可能加入现有批量引用；正文中的其他来源引用保留，不会自动复制其依赖"))
        }
        val copiedId = newId()
        val destination = ResourceLocation(source.resource.copy(id = copiedId))
        val candidate = when (old) {
            is CharacterCard -> library.copy(characters = library.characters + old.copy(id = copiedId,
                name = unique(old.name + " 副本", library.characters.map { it.name }),
                slug = unique((old.slug.takeIf(MacroNaming::isValidSlug) ?: "character") + "_copy", library.characters.map { it.slug }),
                namespace = unique(old.namespace + "_copy", library.characters.map { it.namespace } + library.packages.map { it.namespace }),
                entries = old.entries.map { it.copy(id = newId()) }))
            is PromptPackage -> {
                val namespace = unique(old.namespace + "_copy", library.characters.map { it.namespace } + library.packages.map { it.namespace })
                // Retain existing internal templates explicitly: no namespace-wide consumer rewrite.
                library.copy(packages = library.packages + old.copy(id = copiedId,
                    name = unique(old.name + " 副本", library.packages.map { it.name }), namespace = namespace,
                    slug = unique((old.slug.takeIf(MacroNaming::isValidSlug) ?: "package") + "_copy", library.packages.map { it.slug }),
                    entries = old.entries.map { it.copy(id = newId()) }))
            }
            is MainCall -> {
                val ids = graphIds(old.configuredWorkflows()) + old.tiles.associate { it.id to newId() } +
                    old.history.insertions.associate { it.id to newId() } + old.extensionBindings.associate { it.id to newId() }
                val copy = old.copy(id = copiedId, name = unique(old.name + " 副本", library.mainCalls.map { it.name }),
                    slug = unique((old.slug.takeIf(MacroNaming::isValidSlug) ?: "main") + "_copy", library.mainCalls.map { it.slug }),
                    workflows = old.workflows.map { copyWorkflow(it, ids) }, primaryWorkflowId = old.primaryWorkflowId?.let { ids[it] ?: it },
                    tiles = old.tiles.map { it.copy(id = ids.getValue(it.id)) }, bodySourceTileId = old.bodySourceTileId?.let { ids[it] ?: it },
                    history = old.history.copy(insertions = old.history.insertions.map { it.copy(id = ids.getValue(it.id)) },
                        processor = old.history.processor.copy(workflow = copyWorkflow(old.history.processor.workflow, ids),
                            aggregationWorkflow = old.history.processor.aggregationWorkflow?.let { copyWorkflow(it, ids) })),
                    extensionBindings = old.extensionBindings.map { it.copy(id = ids.getValue(it.id)) },
                    extensionBindingOrigins = old.extensionBindingOrigins.map { it.copy(ownerBindingIds = it.ownerBindingIds.map { id -> ids[id] ?: id }) })
                library.copy(mainCalls = library.mainCalls + copy)
            }
            is CallGroup -> {
                val ids = graphIds(old.workflows) + old.tiles.associate { it.id to newId() }
                library.copy(groups = library.groups + old.copy(id = copiedId, name = unique(old.name + " 副本", library.groups.map { it.name }),
                    slug = unique((old.slug.takeIf(MacroNaming::isValidSlug) ?: "group") + "_copy", library.groups.map { it.slug }),
                    workflows = old.workflows.map { copyWorkflow(it, ids) }, tiles = old.tiles.map { it.copy(id = ids.getValue(it.id)) }))
            }
            else -> return failure(source, "不支持此复制来源")
        }
        return preview(library, candidate, "复制资源", source, destination, listOf(
            "创建独立资源及内部稳定 ID；现有使用者仍关联原资源", "保留外部资源关联和文本内宏地址；调用输出、状态键、注入地址仍可能共享，副本不会自动获得运行时隔离"))
    }

    private fun graphIds(workflows: List<Workflow>): Map<String, String> = buildMap {
        workflows.forEach { workflow -> put(workflow.id, newId()); workflow.nodes.forEach { node ->
            put(node.id, newId()); node.blocks.forEach { put(it.id, newId()) }
        } }
    }

    private fun copyWorkflow(workflow: Workflow, ids: Map<String, String>): Workflow = workflow.copy(
        id = ids.getValue(workflow.id), afterWorkflowId = workflow.afterWorkflowId?.let { ids[it] ?: it },
        trigger = if (workflow.trigger.startsWith(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX)) {
            val target = workflow.trigger.removePrefix(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX)
            RuntimeEvents.workflowCompleted(ids[target] ?: target)
        } else workflow.trigger,
        nodes = workflow.nodes.map { node ->
            val descriptor = ActionRegistry.defaults().descriptors().firstOrNull { it.id == node.actionId }
            val referenceKeys = listOfNotNull(descriptor?.injectionTargetWorkflowField, descriptor?.injectionTargetNodeField)
            node.copy(id = ids.getValue(node.id), dependsOn = node.dependsOn.map { ids[it] ?: it },
                blocks = node.blocks.map { it.copy(id = ids.getValue(it.id)) },
                arguments = node.arguments.mapValues { (key, value) -> if (key in referenceKeys) ids[value] ?: value else value })
        })

    fun previewDelete(library: Library, target: ResourceLocation, removeConfiguredAssociations: Boolean = false): ResourceMutationPlan {
        if (resource(library, target.resource) == null) return failure(target, "删除目标不存在或不唯一")
        if (target.workflowId != null || target.nodeId != null || target.blockId != null) return failure(target, "此处支持删除完整资源或角色／提示词包词条")
        if (target.entryId != null) {
            val entry = entries(library, target.resource).singleOrNull { it.id == target.entryId } ?: return failure(target, "词条不存在或不唯一")
            if (target.resource.kind == ExtensionResourceKind.CHARACTER && CharacterFields.isBasic(entry.key))
                return failure(target, "标准角色字段须保留；可在原编辑器清空正文")
        }
        val usages = deletionUses(library, target)
        val blockers = usages.filterNot { removeConfiguredAssociations && it.removableAssociation }
        if (blockers.isNotEmpty()) return ResourceMutationPlan(null, blockers.map { Issue(it.path, "仍被使用：${it.label}") }, usages,
            listOf("可显式移除配置关联；会话、收藏、已接受状态与模板引用必须保留", ResourceUsageIndex.LIMITATION))
        var candidate = if (removeConfiguredAssociations) cleanup(library, target) else library
        candidate = if (target.entryId != null) withEntries(candidate, target.resource, entries(candidate, target.resource).filterNot { it.id == target.entryId }) else when (target.resource.kind) {
            ExtensionResourceKind.CHARACTER -> candidate.copy(characters = candidate.characters.filterNot { it.id == target.resource.id })
            ExtensionResourceKind.PACKAGE -> candidate.copy(packages = candidate.packages.filterNot { it.id == target.resource.id })
            ExtensionResourceKind.MAIN -> candidate.copy(mainCalls = candidate.mainCalls.filterNot { it.id == target.resource.id })
            ExtensionResourceKind.GROUP -> candidate.copy(groups = candidate.groups.filterNot { it.id == target.resource.id })
        }
        return preview(library, candidate, "删除资源／词条", target, null,
            listOf(if (removeConfiguredAssociations) "在同一事务删除目标并移除列出的配置关联和公开参数" else "删除未被引用的目标", ResourceUsageIndex.LIMITATION))
            .copy(usages = usages, deletionTarget = target, cleanupAssociations = removeConfiguredAssociations)
    }

    private fun deletionUses(library: Library, target: ResourceLocation) = ResourceUsageIndex.usages(library, target).filterNot {
        // References located wholly inside the removed object disappear with that object.
        it.kind == ResourceUseKind.TEMPLATE && it.location?.resource == target.resource &&
            (target.entryId == null || it.location.entryId == target.entryId)
    }.filterNot { target.entryId != null && it.kind == ResourceUseKind.BINDING }

    private fun cleanup(library: Library, target: ResourceLocation): Library {
        val ref = target.resource
        val definitions = library.extensions.map { extension -> extension.copy(
            resources = if (target.entryId == null) extension.resources - ref else extension.resources,
            parameters = extension.parameters.filterNot { it.target.kind == ref.kind && it.target.resourceId == ref.id && (target.entryId == null || it.target.entryId == target.entryId) }) }
        if (target.entryId != null) return library.copy(extensions = definitions)
        return library.copy(extensions = definitions,
            characters = library.characters.map { if (ref.kind == ExtensionResourceKind.PACKAGE) it.copy(boundPackageIds = it.boundPackageIds - ref.id) else it },
            groups = library.groups.map { if (ref.kind == ExtensionResourceKind.PACKAGE) it.copy(boundPackageIds = it.boundPackageIds - ref.id) else it },
            mainCalls = library.mainCalls.map { it.copy(
                boundPackageIds = if (ref.kind == ExtensionResourceKind.PACKAGE) it.boundPackageIds - ref.id else it.boundPackageIds,
                boundGroupIds = if (ref.kind == ExtensionResourceKind.GROUP) it.boundGroupIds - ref.id else it.boundGroupIds,
                extensionBindingOrigins = it.extensionBindingOrigins.filterNot { origin -> origin.kind == ref.kind && origin.resourceId == ref.id }) })
    }

    fun previewSeparate(library: Library, owner: ExtensionFieldRef, expectedText: String, start: Int, endExclusive: Int,
                        expectedSource: ExtensionFieldRef): ResourceMutationPlan =
        separate(library, owner, expectedText, start, endExclusive, expectedSource, draftOnly = false)

    /** Same-resource owning-draft staging only. This result cannot be published via apply. */
    fun previewSeparateDraft(library: Library, owner: ExtensionFieldRef, expectedText: String, start: Int, endExclusive: Int,
                             expectedSource: ExtensionFieldRef): ResourceMutationPlan =
        if (owner.kind != expectedSource.kind || owner.resourceId != expectedSource.resourceId)
            failure(ResourceLocations.fromField(owner), "跨资源分离须保存当前草稿后，整体预览并应用")
        else separate(library, owner, expectedText, start, endExclusive, expectedSource, draftOnly = true)

    private fun separate(library: Library, owner: ExtensionFieldRef, expectedText: String, start: Int, endExclusive: Int,
                         expectedSource: ExtensionFieldRef, draftOnly: Boolean): ResourceMutationPlan {
        val ownerLocation = ResourceLocations.fromField(owner)
        fun reject(message: String) = failure(ownerLocation, message)
        val descriptor = ExtensionFieldRegistry.describe(library, owner) ?: return reject("正文所属字段不存在或不支持直接编辑")
        if (descriptor.type !in setOf(ActionFieldType.STRING, ActionFieldType.TEMPLATE) || ExtensionFieldRegistry.read(library, owner) != expectedText)
            return reject("正文已改变，请重新选择引用")
        if (expectedSource.field != ExtensionField.ENTRY_TEXT || expectedSource.kind !in setOf(ExtensionResourceKind.CHARACTER, ExtensionResourceKind.PACKAGE))
            return reject("仅支持分离单个角色或提示词包词条；动态结果、分类和 History 请使用原编辑器")
        val token = MacroTemplateTokens.scan(expectedText).tokens.singleOrNull { it.closed && it.start == start && it.endExclusive == endExclusive }
            ?: return reject("引用范围已经改变，请重新选择完整宏")
        val reference = PromptReferenceLanguage.compile(token.expression).reference as? PromptReferenceLanguage.LegacyReference
            ?: return reject("仅支持 character.key 或 pack.namespace.key 的单个静态引用")
        val selector = reference.expression.substringBefore('#').substringAfter('@', "latest")
        if (selector != "latest") return reject("历史版本不能分离为当前词条；请选择最新值")
        val candidates = ResourceUsageIndex.staticTargets(library, ownerLocation, reference.expression)
        val source = candidates.singleOrNull() ?: return reject("来源不存在或不唯一，无法安全分离")
        if (source.field != expectedSource) return reject("引用来源已改变，请重新选择")
        val entry = entries(library, source.resource).singleOrNull { it.id == source.entryId } ?: return reject("来源词条不存在")
        if (owner == expectedSource || (entry.isTemplate && unsafeTemplateDependency(library, source, source, emptySet())))
            return reject("来源模板存在自身、循环或无法确定的依赖，不能安全分离；请在原编辑器处理")
        // A local clone must not join any existing category or tag-based bulk reference.
        val copied = duplicateEntry(entry, entries(library, source.resource), source.resource.kind == ExtensionResourceKind.CHARACTER)
            .copy(category = "", tagIds = emptyList())
        val broadIssues = broadSelectionIssues(library, source)
        if (broadIssues.isNotEmpty()) return ResourceMutationPlan(null, broadIssues,
            changes = listOf("此来源还有会自动包含新增词条的批量引用；无法只分离当前使用"))
        val oldExpression = reference.expression
        val oldAddress = oldExpression.substringBefore('#').substringBefore('@')
        val newAddress = oldAddress.substringBeforeLast('.') + "." + copied.key
        val tokenText = expectedText.substring(start, endExclusive)
        val addressStart = tokenText.indexOf(oldAddress)
        if (addressStart < 0) return reject("无法识别引用地址")
        val replacedToken = tokenText.replaceRange(addressStart, addressStart + oldAddress.length, newAddress)
        val rewritten = expectedText.replaceRange(start, endExclusive, replacedToken)
        val withCopy = withEntries(library, source.resource, entries(library, source.resource) + copied)
        val written = ExtensionFieldRegistry.writeDraft(withCopy, owner, rewritten, expectedText)
        val candidate = written.library ?: return ResourceMutationPlan(null, written.issues)
        val destination = ResourceLocations.fromField(expectedSource.copy(entryId = copied.id))
        val changes = listOf("在原资源新增词条 ${copied.title}；仅替换选中的 $start–$endExclusive 引用", "副本不继承分类和标签，以免加入其他批量引用；新词条内部的外部来源引用仍共享")
        return if (draftOnly) ResourceMutationPlan(null, changes = changes, destination = destination, draftLibrary = candidate)
        else preview(library, candidate, "分离当前引用", source, destination, changes)
            .copy(separationOwner = owner, separationExpression = reference.expression)
    }

    /** Tagless clones still join unqualified/all or exclusion-only queries; refuse those known consumers. */
    private fun broadSelectionIssues(library: Library, source: ResourceLocation): List<Issue> {
        val expectedType = if (source.resource.kind == ExtensionResourceKind.CHARACTER)
            PromptReferenceLanguage.SourceType.CHARACTER else PromptReferenceLanguage.SourceType.PACK
        val sourceSlug = when (source.resource.kind) {
            ExtensionResourceKind.CHARACTER -> library.characters.singleOrNull { it.id == source.resource.id }?.slug
            ExtensionResourceKind.PACKAGE -> library.packages.singleOrNull { it.id == source.resource.id }?.slug
            else -> null
        }
        val templates = ResourceTemplateSlots.list(library).map { Triple(ResourceLocations.path(it.location), it.text, it.location.resource) } +
            library.personas.map { Triple("persona.${it.id}.prompt", it.prompt, null) } +
            listOf(Triple("library.titleConfig.prompt", library.titleConfig.prompt, null))
        return buildList {
            templates.forEach { (path, text, owner) ->
                MacroTemplateTokens.scan(text).tokens.filter { it.closed }.forEach tokenLoop@ { token ->
                    val query = PromptReferenceLanguage.compile(token.expression).reference as? PromptReferenceLanguage.QueryPlan ?: return@tokenLoop
                    if (query.sourceType != expectedType || query.name != null || query.requiredTags.isNotEmpty() || query.anyTags.isNotEmpty()) return@tokenLoop
                    if (expectedType == PromptReferenceLanguage.SourceType.CHARACTER && owner?.kind == ExtensionResourceKind.CHARACTER && owner.id != source.resource.id) return@tokenLoop
                    if (query.source != PromptReferenceLanguage.SourceScope.Loaded && (query.source as? PromptReferenceLanguage.SourceScope.Named)?.slug != sourceSlug) return@tokenLoop
                    add(Issue("$path.span.${token.start}.${token.endExclusive}", "批量引用会同时包含新增词条，不能只分离当前使用：${token.expression}"))
                }
            }
        }
    }

    private fun unsafeTemplateDependency(library: Library, origin: ResourceLocation, current: ResourceLocation, visited: Set<Pair<ExtensionResourceRef, String?>>): Boolean {
        val identity = current.resource to current.entryId
        if (identity in visited) return true
        val entry = entries(library, current.resource).singleOrNull { it.id == current.entryId } ?: return true
        if (!entry.isTemplate) return false
        val scan = MacroTemplateTokens.scan(entry.text)
        if (scan.diagnostics.isNotEmpty()) return true
        return scan.tokens.any { token ->
            val targets = ResourceUsageIndex.staticTargets(library, current, token.expression)
            // Dynamic templated sources cannot establish per-occurrence independence safely.
            targets.size != 1 || targets.single().let { it.resource == origin.resource && it.entryId == origin.entryId } ||
                unsafeTemplateDependency(library, origin, targets.single(), visited + identity)
        }
    }

    fun apply(plan: ResourceMutationPlan, current: Library): ConfigurationApplyResult {
        if (!plan.canApply) return ConfigurationApplyResult(null, issues = plan.issues.ifEmpty { listOf(Issue("resource", "操作预览不可应用")) })
        plan.source?.let { if (resource(current, it.resource) != plan.sourceBefore)
            return ConfigurationApplyResult(null, issues = listOf(Issue(ResourceLocations.path(it), "来源已被修改，请重新预览"))) }
        plan.separationOwner?.let { owner ->
            val expected = requireNotNull(plan.source)
            val resolved = ResourceUsageIndex.staticTargets(current, ResourceLocations.fromField(owner), requireNotNull(plan.separationExpression)).singleOrNull()
            if (resolved?.field != expected.field || unsafeTemplateDependency(current, expected, expected, emptySet()))
                return ConfigurationApplyResult(null, issues = listOf(Issue(ResourceLocations.path(ResourceLocations.fromField(owner)), "来源解析或依赖已改变，请重新预览")))
            val broadIssues = broadSelectionIssues(current, expected)
            if (broadIssues.isNotEmpty()) return ConfigurationApplyResult(null, issues = broadIssues)
        }
        plan.deletionTarget?.let { target ->
            val blockers = deletionUses(current, target).filterNot { plan.cleanupAssociations && it.removableAssociation }
            if (blockers.isNotEmpty()) return ConfigurationApplyResult(null, issues = blockers.map { Issue(it.path, "仍被使用：${it.label}") })
        }
        return ConfigurationCommands.apply(requireNotNull(plan.configuration), current)
    }

    private fun preview(before: Library, candidate: Library, label: String, source: ResourceLocation,
                        destination: ResourceLocation?, changes: List<String>): ResourceMutationPlan {
        val preview = ConfigurationCommands.previewEdit(before, candidate, label)
        return ResourceMutationPlan(preview.plan, preview.issues, ResourceUsageIndex.usages(before, source), changes, destination,
            source = source, sourceBefore = resource(before, source.resource))
    }

    private fun withEntries(library: Library, ref: ExtensionResourceRef, entries: List<PromptEntry>): Library = when (ref.kind) {
        ExtensionResourceKind.CHARACTER -> library.copy(characters = library.characters.map { if (it.id == ref.id) it.copy(entries = entries) else it })
        ExtensionResourceKind.PACKAGE -> library.copy(packages = library.packages.map { if (it.id == ref.id) it.copy(entries = entries) else it })
        else -> library
    }

    private fun failure(target: ResourceLocation, message: String) = ResourceMutationPlan(null, listOf(Issue(ResourceLocations.path(target), message)))
}
