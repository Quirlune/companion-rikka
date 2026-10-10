package dev.gray.companion.core

/** Explicit switches are global; absent switches retain frozen session participation semantics. */
object PluginParticipation {
    fun activeExtensions(library: Library, session: Session,
                         executionVersion: Int = ExecutionSemantics.CURRENT): List<ConfigurationExtension> {
        val definitions = library.extensions.associateBy { it.id }
        val ordinary = library.extensions.filter {
            (it.mainScopeId == null || it.mainScopeId == session.mainCallId) &&
                (it.enabled ?: (it.id in session.enabledExtensionIds))
        }
        val memory = library.memoryConfig?.takeIf { ExecutionSemantics(executionVersion).globalMemory } ?: return ordinary
        return ordinary.filter { it.memory == null } + listOfNotNull(
            memory.extensionId?.let(definitions::get)?.takeIf { memory.enabled && it.memory != null })
    }

    fun members(library: Library, session: Session, kind: ExtensionResourceKind): List<String> =
        activeExtensions(library, session).flatMap { it.resources }.filter { it.kind == kind }.map { it.id }.distinct()

    /** Explicit live owners gate even manual bindings. Null owners retain historical semantics. */
    fun resourceActive(library: Library, session: Session, kind: ExtensionResourceKind, id: String): Boolean {
        val ref = ExtensionResourceRef(kind, id)
        val owners = library.extensions.filter { it.memory == null && it.enabled != null && ref in it.resources }
        return owners.isEmpty() || owners.any { it.enabled == true && (it.mainScopeId == null || it.mainScopeId == session.mainCallId) }
    }

    /** Current preferences hide historical cards without changing their accepted definitions/data. */
    fun cardVisible(live: Library, session: Session, resourceId: String, cardId: String): Boolean {
        val migratedOwner = live.groups.firstOrNull { group -> group.cards.any { it.id == cardId } }
        return resourceActive(live, session, ExtensionResourceKind.GROUP, migratedOwner?.id ?: resourceId)
    }

    fun executionOwners(library: Library, session: Session): Set<String> {
        val groups = library.loadedCallGroups(session)
        val workflows = library.mainCalls.firstOrNull { it.id == session.mainCallId }?.configuredWorkflows().orEmpty() +
            groups.flatMap { it.workflows }
        val codeIds = workflows.filter { it.enabled }.flatMap { it.nodes }.filter { it.enabled && it.actionId == "plugin.run" }
            .mapNotNull { it.arguments["pluginId"] }.toSet()
        val packages = library.loadedPromptPackages(session).map { it.id }.toSet()
        return activeExtensions(library, session).filter { extension -> extension.id in codeIds ||
            extension.resources.any { ref -> when (ref.kind) {
                ExtensionResourceKind.GROUP -> groups.any { it.id == ref.id && it.workflows.any { flow -> flow.enabled && flow.nodes.any { node -> node.enabled } } }
                ExtensionResourceKind.PACKAGE -> ref.id in packages
                else -> false
            } }
        }.map { it.id }.toSet()
    }

    fun injectionOwners(library: Library, session: Session, resourceId: String): List<String> {
        if (library.groups.none { it.id == resourceId } || resourceId in session.groupIds ||
            library.characters.firstOrNull { it.id == session.characterId }?.boundGroupIds?.contains(resourceId) == true ||
            library.mainCalls.firstOrNull { it.id == session.mainCallId }?.boundGroupIds?.contains(resourceId) == true) return emptyList()
        val member = ExtensionResourceRef(ExtensionResourceKind.GROUP, resourceId)
        return activeExtensions(library, session).filter { member in it.resources }.map { it.id }
    }

    fun injectionActive(library: Library, session: Session, owners: List<String>, resourceId: String? = null): Boolean =
        (resourceId == null || resourceActive(library, session, ExtensionResourceKind.GROUP, resourceId)) &&
        (owners.isEmpty() || activeExtensions(library, session).any {
            it.id in owners && (resourceId == null || ExtensionResourceRef(ExtensionResourceKind.GROUP, resourceId) in it.resources)
        })

    fun nodeActive(library: Library, session: Session, node: WorkflowNode): Boolean =
        node.actionId != "plugin.run" || activeExtensions(library, session).any {
            it.id == node.arguments["pluginId"] && it.code != null
        }

    fun originActive(library: Library, session: Session, origin: ResultOrigin?): Boolean {
        if (origin == null) return true
        val workflows = library.mainCalls.firstOrNull { it.id == origin.resourceId }?.configuredWorkflows()
            ?: library.groups.firstOrNull { it.id == origin.resourceId }?.workflows.orEmpty()
        val node = workflows.firstOrNull { it.id == origin.workflowId }?.nodes?.firstOrNull { it.id == origin.nodeId }
            ?: return true // Existing accepted records may predate their live definition.
        return nodeActive(library, session, node)
    }

    /** Withdraw only this owner from live injections. Accepted messages/worlds remain untouched. */
    fun disable(session: Session, extensionId: String): Session = session.copy(
        enabledExtensionIds = session.enabledExtensionIds.filterNot { it == extensionId },
        promptInjections = session.promptInjections.mapNotNull { injection ->
            if (extensionId !in injection.pluginOwnerIds) injection
            else {
                val remaining = injection.pluginOwnerIds.filterNot { it == extensionId }
                if (remaining.isEmpty()) null else injection.copy(pluginOwnerIds = remaining)
            }
        })
}
