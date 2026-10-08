package dev.gray.companion.core

enum class ResourceUseKind { BINDING, EXTENSION, TEMPLATE, SESSION, SAVED_CHAT, RECOVERY, ACCEPTED_WORLD }
data class ResourceUse(
    val target: ResourceLocation,
    val location: ResourceLocation?,
    val path: String,
    val label: String,
    val kind: ResourceUseKind,
    val removableAssociation: Boolean = false
)

/** Declared associations and statically identifiable references. Dynamic selectors are not exhaustive. */
object ResourceUsageIndex {
    const val LIMITATION = "用量包括配置关联、扩展、会话、收藏及可静态识别的引用；动态地址和运行时选择不能完整推断。"

    fun usages(library: Library, target: ResourceLocation): List<ResourceUse> = buildList {
        val ref = target.resource
        val entry = target.entryId?.let { id -> ResourceEditCommands.entries(library, ref).singleOrNull { it.id == id } }
        val entryAddress = entry?.let {
            when (ref.kind) {
                ExtensionResourceKind.CHARACTER -> "character.${it.key}"
                ExtensionResourceKind.PACKAGE -> library.packages.singleOrNull { pack -> pack.id == ref.id }?.let { pack -> "pack.${pack.namespace}.${it.key}" }
                else -> null
            }
        }
        fun use(location: ResourceLocation?, path: String, label: String, kind: ResourceUseKind, removable: Boolean = false) {
            add(ResourceUse(target, location, path, label, kind, removable))
        }
        fun binding(owner: ExtensionResourceRef, ids: List<String>, field: String) {
            if (target.entryId != null) return
            val name = when (owner.kind) {
                ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == owner.id }?.name
                ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == owner.id }?.name
                ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == owner.id }?.name
                ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == owner.id }?.name
            } ?: owner.id
            if (ref.id in ids) use(ResourceLocation(owner, section = field), ResourceLocations.path(ResourceLocation(owner, section = field)),
                "配置关联：$name / ${if (field == "boundGroupIds") "调用组" else "提示词包"}", ResourceUseKind.BINDING, target.entryId == null)
        }
        if (ref.kind == ExtensionResourceKind.PACKAGE) {
            library.characters.forEach { binding(ExtensionResourceRef(ExtensionResourceKind.CHARACTER, it.id), it.boundPackageIds, "boundPackageIds") }
            library.mainCalls.forEach { binding(ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id), it.boundPackageIds, "boundPackageIds") }
            library.groups.forEach { binding(ExtensionResourceRef(ExtensionResourceKind.GROUP, it.id), it.boundPackageIds, "boundPackageIds") }
        }
        if (ref.kind == ExtensionResourceKind.GROUP) library.mainCalls.forEach { binding(ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id), it.boundGroupIds, "boundGroupIds") }
        library.extensions.forEach { extension ->
            if (ref in extension.resources && target.entryId == null) use(null, "extension.${extension.id}.resources.${ref.id}", "扩展组成：${extension.name}", ResourceUseKind.EXTENSION, true)
            extension.parameters.filter { it.target.kind == ref.kind && it.target.resourceId == ref.id && (target.entryId == null || it.target.entryId == target.entryId) }.forEach {
                use(ResourceLocations.fromField(it.target), "extension.${extension.id}.parameters.${it.id}", "扩展公开参数：${extension.name} / ${it.title}", ResourceUseKind.EXTENSION, true)
            }
        }
        ResourceTemplateSlots.list(library).forEach { slot ->
            MacroTemplateTokens.scan(slot.text).tokens.filter { it.closed }.forEach { token ->
                val targets = staticTargets(library, slot.location, token.expression).ifEmpty {
                    ResourceLocations.resolveReference(library, slot.location.field, token.expression)
                }
                if (targets.any { candidate ->
                    candidate.resource == ref && (target.entryId == null || candidate.entryId == target.entryId)
                }) use(slot.location, ResourceLocations.path(slot.location) + ".span.${token.start}.${token.endExclusive}",
                    "静态引用：${token.expression}（${token.start}–${token.endExclusive}）", ResourceUseKind.TEMPLATE)
            }
        }
        fun outsideTemplate(path: String, title: String, text: String) {
            MacroTemplateTokens.scan(text).tokens.filter { it.closed }.forEach { token ->
                if (ResourceLocations.resolveReference(library, null, token.expression).any {
                        it.resource == ref && (target.entryId == null || it.entryId == target.entryId)
                    }) use(null, "$path.span.${token.start}.${token.endExclusive}", "$title：${token.expression}", ResourceUseKind.TEMPLATE)
            }
        }
        library.personas.forEach { outsideTemplate("persona.${it.id}.prompt", "用户身份 ${it.name}", it.prompt) }
        outsideTemplate("library.titleConfig.prompt", "标题提示词", library.titleConfig.prompt)
        val ownedWorkflows = ResourceTemplateSlots.workflows(library, ref).map { it.id }.toSet()
        val ownedNodes = ResourceTemplateSlots.workflows(library, ref).flatMap { it.nodes }.map { it.id }.toSet()
        ResourceLocations.resources(library).forEach { owner ->
            if (owner == ref && target.entryId == null) return@forEach
            ResourceTemplateSlots.workflows(library, owner).forEach { workflow ->
                val location = ResourceLocation(owner, workflowId = workflow.id)
                val completed = workflow.afterWorkflowId ?: workflow.trigger.takeIf { it.startsWith(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX) }?.removePrefix(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX)
                if (completed in ownedWorkflows) use(location.copy(section = "afterWorkflowId"), ResourceLocations.path(location) + ".afterWorkflowId", "调用 ${workflow.title} 等待此资源的调用完成", ResourceUseKind.BINDING)
                workflow.nodes.forEach { node ->
                    val nl = location.copy(nodeId = node.id)
                    val descriptor = ActionRegistry.defaults().descriptors().firstOrNull { it.id == node.actionId }
                    val arguments = descriptor?.fields.orEmpty().filter { it.type == ActionFieldType.MACRO }.map { it.key }
                    arguments.forEach { key ->
                        val value = node.arguments[key].orEmpty()
                        if (staticTargets(library, nl, value.removeSurrounding("{{", "}}")).any { it.resource == ref && (target.entryId == null || it.entryId == target.entryId) })
                            use(nl.copy(section = "arguments.$key"), ResourceLocations.path(nl) + ".argument.$key", "动作 ${node.title} 的宏地址：$value", ResourceUseKind.TEMPLATE)
                    }
                    listOfNotNull(descriptor?.injectionTargetWorkflowField, descriptor?.injectionTargetNodeField).forEach { key ->
                        if (node.arguments[key] in ownedWorkflows + ownedNodes) use(nl.copy(section = "arguments.$key"), ResourceLocations.path(nl) + ".argument.$key", "提示注入 ${node.title} 指向此资源的节点", ResourceUseKind.BINDING)
                    }
                }
            }
        }
        fun visitSession(session: Session, path: String, kind: ResourceUseKind, depth: Int = 0) {
            val selected = when (ref.kind) {
                ExtensionResourceKind.CHARACTER -> session.characterId == ref.id
                ExtensionResourceKind.MAIN -> session.mainCallId == ref.id || ref.id in session.historyDigests
                ExtensionResourceKind.PACKAGE -> library.loadedPromptPackages(session).any { it.id == ref.id } || ref.id in session.packageIds
                ExtensionResourceKind.GROUP -> library.loadedCallGroups(session).any { it.id == ref.id } || ref.id in session.groupIds
            }
            if (selected && target.entryId == null) use(null, path, "${if (kind == ResourceUseKind.SAVED_CHAT) "收藏" else "会话"}：${session.title}", kind)
            fun origins(origins: Iterable<ResultOrigin>, at: String) {
                if (origins.any { it.resourceId == ref.id && (target.entryId == null || it.nodeId == target.entryId) })
                    use(null, at, "已接受的运行来源：${session.title}", ResourceUseKind.ACCEPTED_WORLD)
            }
            fun addresses(keys: Collection<String>, at: String) {
                if (entryAddress != null && entryAddress in keys) use(null, at, "已接受的词条宏版本：${session.title} / $entryAddress", ResourceUseKind.ACCEPTED_WORLD)
            }
            fun snapshot(value: RuntimeSnapshot, at: String) {
                origins(value.resultOrigins.values + value.currentOnlyMacros.values.flatMap { listOfNotNull(it.origin, it.valueOrigin) } + value.promptInjections.map { it.origin }, at)
                addresses(value.currentOnlyMacros.keys, "$at.currentOnlyMacros")
                if (ref.kind == ExtensionResourceKind.MAIN && ref.id in value.historyDigests) use(null, at, "已保存的历史摘要来源", ResourceUseKind.ACCEPTED_WORLD)
                if (value.historyMemory.segments.values.any { it.workflowId in ownedWorkflows }) use(null, "$at.historyMemory", "已保存的记忆来源调用", ResourceUseKind.ACCEPTED_WORLD)
            }
            fun messages(values: List<ChatMessage>, at: String) {
                values.filter { it.workflowId in ownedWorkflows || it.nodeId in ownedNodes }.forEach {
                    use(null, "$at.${it.id}", "已接受的消息来源：${session.title}", ResourceUseKind.ACCEPTED_WORLD)
                }
            }
            origins(session.resultOrigins.values + session.currentOnlyMacros.values.flatMap { listOfNotNull(it.origin, it.valueOrigin) } +
                session.macroHistory.values.flatten().flatMap { listOfNotNull(it.origin, it.valueOrigin) } + session.promptInjections.map { it.origin }, "$path.runtime")
            addresses(session.macroHistory.keys + session.currentOnlyMacros.keys, "$path.runtime.macros")
            messages(session.messages, "$path.messages")
            if (session.historyMemory.segments.values.any { it.workflowId in ownedWorkflows }) use(null, "$path.historyMemory", "记忆保留此资源的来源调用", ResourceUseKind.ACCEPTED_WORLD)
            session.turnRecords.forEach { turn ->
                snapshot(turn.checkpoint.runtime, "$path.turn.${turn.userMessageId}.checkpoint")
                turn.variants.forEach { variant ->
                    snapshot(variant.after, "$path.turn.${turn.userMessageId}.variant.${variant.id}")
                    messages(variant.replies, "$path.turn.${turn.userMessageId}.variant.${variant.id}.replies")
                    origins(variant.macroAppends.values.flatten().flatMap { listOfNotNull(it.origin, it.valueOrigin) }, "$path.turn.${turn.userMessageId}.variant.${variant.id}.macros")
                    addresses(variant.macroAppends.keys, "$path.turn.${turn.userMessageId}.variant.${variant.id}.macros")
                }
            }
            val recovery = session.pendingRun
            if (recovery != null && depth >= 8) use(null, "$path.pendingRun", "恢复记录嵌套过深，无法确认删除安全", ResourceUseKind.RECOVERY)
            if (recovery != null && depth < 8) {
                val frozenContainsTarget = if (target.entryId == null) ref in ResourceLocations.resources(recovery.frozenResources)
                    else ResourceEditCommands.entries(recovery.frozenResources, ref).any { it.id == target.entryId }
                if (frozenContainsTarget) use(null, "$path.pendingRun.${recovery.id}", "待恢复运行使用的冻结资源／词条", ResourceUseKind.RECOVERY)
                visitSession(recovery.initialSession, "$path.pendingRun.initialSession", ResourceUseKind.RECOVERY, depth + 1)
                recovery.regenerationBase?.let { visitSession(it, "$path.pendingRun.regenerationBase", ResourceUseKind.RECOVERY, depth + 1) }
                recovery.environments.forEach { visitSession(it.session, "$path.pendingRun.environment.${it.id}", ResourceUseKind.RECOVERY, depth + 1) }
                origins(recovery.resultOrigins.values + recovery.macroHistory.values.flatten().flatMap { listOfNotNull(it.origin, it.valueOrigin) }, "$path.pendingRun.runtime")
                addresses(recovery.macroHistory.keys + recovery.currentOnlyMacros.keys, "$path.pendingRun.macros")
                recovery.accepted.forEach { receipt ->
                    addresses(receipt.publication.macroAppends.keys + receipt.publication.headWrites.keys, "$path.pendingRun.accepted.${receipt.nodeKey}.macros")
                }
            }
        }
        library.sessions.forEach { visitSession(it, "session.${it.id}", ResourceUseKind.SESSION) }
        library.savedChats.forEach { visitSession(it.snapshot, "savedChat.${it.id}", ResourceUseKind.SAVED_CHAT) }
    }.distinct()

    /** Conservative library-wide candidates; character-relative references are narrowed only by a character owner. */
    internal fun staticTargets(library: Library, owner: ResourceLocation, expression: String): List<ResourceLocation> {
        val compiled = PromptReferenceLanguage.compile(expression).reference ?: return emptyList()
        val characters = if (owner.resource.kind == ExtensionResourceKind.CHARACTER)
            library.characters.filter { it.id == owner.resource.id } else library.characters
        return when (compiled) {
            is PromptReferenceLanguage.LegacyReference -> {
                val base = compiled.expression.substringBefore('#').substringBefore('@')
                val parts = base.split('.')
                when {
                    parts.size == 2 && parts[0] == "character" -> characters.flatMap { card ->
                        val ref = ExtensionResourceRef(ExtensionResourceKind.CHARACTER, card.id)
                        if (parts[1] == "name") listOf(ResourceLocation(ref)) else card.entries.filter { it.key == CharacterFields.canonicalKey(parts[1]) }
                            .map { ResourceLocations.fromField(ExtensionFieldRef(ref.kind, ref.id, ExtensionField.ENTRY_TEXT, entryId = it.id)) }
                    }
                    parts.size == 3 && parts[0] == "pack" -> library.packages.filter { it.namespace == parts[1] }.flatMap { pack ->
                        pack.entries.filter { it.key == parts[2] }.map { ResourceLocations.fromField(ExtensionFieldRef(ExtensionResourceKind.PACKAGE, pack.id, ExtensionField.ENTRY_TEXT, entryId = it.id)) }
                    }
                    else -> emptyList()
                }
            }
            is PromptReferenceLanguage.QueryPlan -> {
                if (compiled.name == null || compiled.folder != null || compiled.call != null || compiled.historySelection != null) return emptyList()
                fun source(slug: String) = compiled.source == PromptReferenceLanguage.SourceScope.Loaded || (compiled.source as? PromptReferenceLanguage.SourceScope.Named)?.slug == slug
                when (compiled.sourceType) {
                    PromptReferenceLanguage.SourceType.CHARACTER -> characters.filter { source(it.slug) }.flatMap { card -> card.entries.filter { it.slug == compiled.name }.map {
                        ResourceLocations.fromField(ExtensionFieldRef(ExtensionResourceKind.CHARACTER, card.id, ExtensionField.ENTRY_TEXT, entryId = it.id)) } }
                    PromptReferenceLanguage.SourceType.PACK -> library.packages.filter { source(it.slug) }.flatMap { pack -> pack.entries.filter { it.slug == compiled.name }.map {
                        ResourceLocations.fromField(ExtensionFieldRef(ExtensionResourceKind.PACKAGE, pack.id, ExtensionField.ENTRY_TEXT, entryId = it.id)) } }
                    else -> emptyList()
                }
            }
        }
    }
}
