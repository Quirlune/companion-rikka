package dev.gray.companion.core

/** Captures definitions reachable from the actual participants, without adding participants. */
internal object CapturedResourceClosure {
    fun capture(library: Library, session: Session, rootExtension: ConfigurationExtension? = null,
                executionVersion: Int = ExecutionSemantics.CURRENT): Library {
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val loadedGroups = library.loadedCallGroups(session, main)
        val loadedPackages = library.loadedPromptPackages(session, main)
        // A memory view needs the actual main for native History projection, but other session
        // owners are only query context. They enter the image when a root actually references them.
        val refs = if (rootExtension == null) linkedSetOf(
            ExtensionResourceRef(ExtensionResourceKind.CHARACTER, session.characterId),
            ExtensionResourceRef(ExtensionResourceKind.MAIN, session.mainCallId)).apply {
                addAll(loadedGroups.map { ExtensionResourceRef(ExtensionResourceKind.GROUP, it.id) })
                addAll(loadedPackages.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it.id) })
                // A disabled extension does not erase the session's explicit resource references.
                addAll(session.groupIds.map { ExtensionResourceRef(ExtensionResourceKind.GROUP, it) })
                addAll(session.packageIds.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it) })
            } else linkedSetOf(ExtensionResourceRef(ExtensionResourceKind.MAIN, session.mainCallId))
        val extensions = if (rootExtension == null) PluginParticipation.activeExtensions(library, session).toMutableList()
            else mutableListOf(rootExtension)
        if (rootExtension == null) GlobalMemory.extension(library)?.let { definition ->
            if (extensions.none { it.id == definition.id }) extensions += definition
        }
        extensions.forEach { extension ->
            refs += extension.resources
            refs += extension.parameters.map { ExtensionResourceRef(it.target.kind, it.target.resourceId) }
        }
        val owners = ResourceLocations.resources(library)
        val workflows = owners.associateWith { ResourceTemplateSlots.workflows(library, it) }
        val workflowOwners = workflows.flatMap { (owner, values) -> values.map { it.id to owner } }.toMap()
        val nodeOwners = workflows.flatMap { (owner, values) -> values.flatMap { it.nodes }.map { it.id to owner } }.toMap()
        val blockOwners = workflows.flatMap { (owner, values) -> values.flatMap { it.nodes }.flatMap { it.blocks }.map { it.id to owner } }.toMap()
        val descriptors = ActionRegistry.defaults().descriptors().associateBy { it.id }
        val slots = ResourceTemplateSlots.list(library, executionVersion).groupBy { it.location.resource }

        fun targets(owner: ResourceLocation, expression: String): List<ResourceLocation> {
            val reference = PromptReferenceLanguage.compile(expression).reference ?: return emptyList()
            val legacy = (reference as? PromptReferenceLanguage.LegacyReference)?.expression
                ?.substringBefore('@')?.substringBefore('#')
            // A shared legacy result key does not identify every unrelated project's producer.
            if (legacy?.startsWith("result.") == true || legacy?.startsWith("macro.") == true) {
                val key = legacy.substringAfter('.')
                val accepted = session.resultOrigins[key]?.resourceId
                return owners.filter { ref -> ref.id == accepted || ref in refs &&
                    workflows[ref].orEmpty().any { flow -> flow.nodes.any { it.outputKey == key ||
                        it.regexOutputCopy.enabled && it.regexOutputCopy.outputKey == key } } }.map { ResourceLocation(it) }
            }
            val loaded = (reference as? PromptReferenceLanguage.QueryPlan)?.source == PromptReferenceLanguage.SourceScope.Loaded
            val scoped = library.copy(
                characters = library.characters.filter { it.id == if (owner.resource.kind == ExtensionResourceKind.CHARACTER)
                    owner.resource.id else session.characterId },
                packages = if (loaded) loadedPackages else library.packages,
                mainCalls = if (loaded) listOfNotNull(main) else library.mainCalls,
                groups = if (loaded) loadedGroups else library.groups
            )
            return ResourceUsageIndex.staticTargets(scoped, owner, expression)
        }

        val rootLocation = ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, session.mainCallId))
        val outsideTemplates = if (rootExtension != null) emptyList() else session.promptInjections.flatMap { injection ->
            listOf(injection.block.template) + injection.block.processing.flatMap { listOf(it.text, it.replacement) }
        }
        outsideTemplates.forEach { text ->
            MacroTemplateTokens.scan(text).tokens.filter { it.closed }.forEach { token ->
                refs += targets(rootLocation, token.expression).map { it.resource }
            }
        }
        session.promptInjections.takeIf { rootExtension == null }.orEmpty().forEach { injection ->
            injection.targetWorkflowId?.let(workflowOwners::get)?.let(refs::add)
            injection.targetNodeId?.let(nodeOwners::get)?.let(refs::add)
            injection.targetBlockId?.let(blockOwners::get)?.let(refs::add)
        }
        val visited = mutableSetOf<ExtensionResourceRef>()
        while (true) {
            // Retain disabled owners of reachable resources, otherwise a captured manual binding
            // could escape the global off gate. Retaining metadata does not load their other groups.
            library.extensions.filter { definition -> definition.memory == null && definition.enabled != null &&
                definition.resources.any { it in refs } && extensions.none { it.id == definition.id }
            }.forEach { definition ->
                extensions += definition
                refs += definition.resources
                refs += definition.parameters.map { ExtensionResourceRef(it.target.kind, it.target.resourceId) }
            }
            // An inactive Memory group may still be manually loaded or referenced. Keep its
            // declaration so the old world can validate and suppress its owned calls, without
            // changing participation or loading the newly retained definition's other members.
            library.extensions.filter { definition -> definition.memory?.let { rules ->
                ExtensionResourceRef(ExtensionResourceKind.GROUP, rules.groupId) in refs
            } == true && extensions.none { it.id == definition.id } }.forEach { definition ->
                extensions += definition
                refs += definition.resources
                refs += definition.parameters.map { ExtensionResourceRef(it.target.kind, it.target.resourceId) }
            }
            val owner = refs.firstOrNull { it !in visited } ?: break
            visited += owner
            when (owner.kind) {
                ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == owner.id }?.let { card ->
                    refs += card.boundPackageIds.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it) }
                    refs += card.boundGroupIds.map { ExtensionResourceRef(ExtensionResourceKind.GROUP, it) }
                }
                ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == owner.id }?.let { value ->
                    refs += value.boundPackageIds.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it) }
                    refs += value.boundGroupIds.map { ExtensionResourceRef(ExtensionResourceKind.GROUP, it) }
                }
                ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == owner.id }?.let { value ->
                    refs += value.boundPackageIds.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it) }
                }
                ExtensionResourceKind.PACKAGE -> Unit
            }
            workflows[owner].orEmpty().forEach { flow ->
                val after = flow.afterWorkflowId ?: flow.trigger.takeIf { it.startsWith(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX) }
                    ?.removePrefix(RuntimeEvents.WORKFLOW_COMPLETED_PREFIX)
                after?.let(workflowOwners::get)?.let(refs::add)
                flow.nodes.forEach { node ->
                    if (node.actionId == "plugin.run") library.extensions.firstOrNull {
                        it.id == node.arguments["pluginId"]
                    }?.takeIf { definition -> extensions.none { it.id == definition.id } }?.let { definition ->
                        extensions += definition
                        refs += definition.resources
                        refs += definition.parameters.map { ExtensionResourceRef(it.target.kind, it.target.resourceId) }
                    }
                    val descriptor = descriptors[node.actionId]
                    descriptor?.injectionTargetWorkflowField?.let { field -> node.arguments[field]?.let(workflowOwners::get)?.let(refs::add) }
                    descriptor?.injectionTargetNodeField?.let { field -> node.arguments[field]?.let(nodeOwners::get)?.let(refs::add) }
                    descriptor?.injectionTargetBlockField?.let { field -> node.arguments[field]?.let(blockOwners::get)?.let(refs::add) }
                    descriptor?.fields.orEmpty().filter { it.type == ActionFieldType.MACRO }.forEach { field ->
                        refs += targets(ResourceLocation(owner, workflowId = flow.id, nodeId = node.id),
                            node.arguments[field.key].orEmpty()).map { it.resource }
                    }
                }
            }
            slots[owner].orEmpty().forEach { slot ->
                MacroTemplateTokens.scan(slot.text).tokens.filter { it.closed }.forEach { token ->
                    refs += targets(slot.location, token.expression).map { it.resource }
                }
            }
        }
        val capturedMains = library.mainCalls.filter { ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id) in refs }
        val capturedGroups = library.groups.filter { ExtensionResourceRef(ExtensionResourceKind.GROUP, it.id) in refs }
        val profileIds = (capturedMains.flatMap { it.configuredWorkflows() } + capturedGroups.flatMap { it.workflows })
            .flatMap { flow -> listOfNotNull(flow.modelProfileId) + flow.nodes.mapNotNull { it.generation.profileId } }
            .toSet() + listOfNotNull(library.selectedProfileId)
        val profiles = library.profiles.filter { it.id in profileIds }.map { it.copy(resolvedHeaders = emptyList(), resolvedProvider = null) }
        return library.copy(
            characters = library.characters.filter { ExtensionResourceRef(ExtensionResourceKind.CHARACTER, it.id) in refs },
            mainCalls = capturedMains, groups = capturedGroups,
            packages = library.packages.filter { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it.id) in refs },
            profiles = profiles, connections = library.connections.filter { it.id in profiles.mapNotNull { profile -> profile.apiConnectionId } },
            personas = library.personas.filter { it.id == session.personaId || it.id == library.selectedPersonaId },
            extensions = extensions.toList(), sessions = emptyList(), selectedSessionId = null,
            chatFolders = emptyList(), savedChats = emptyList(), configurationEdits = emptyList(),
            automaticOptions = AutomaticOptionsConfig(),
            titleConfig = ChatTitleConfig(),
            memoryExecutionView = library.memoryExecutionView?.let { view -> view.copy(library = view.library.copy(
                profiles = view.library.profiles.map { it.copy(resolvedHeaders = emptyList(), resolvedProvider = null) })) }
        )
    }
}
