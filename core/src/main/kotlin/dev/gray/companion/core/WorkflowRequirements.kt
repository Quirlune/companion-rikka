package dev.gray.companion.core

/** Shared admission metadata for adapters; execution still validates the complete plan. */
object WorkflowRequirements {
    /** Potentially needed model profiles for the selected, enabled resources. */
    fun requiredProfiles(library: Library, session: Session, actions: List<ActionDescriptor>): List<ModelProfile?> {
        val modelActions = actions.filter { it.usesModel }.mapTo(mutableSetOf()) { it.id }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val workflows = main?.configuredWorkflows().orEmpty() + library.loadedCallGroups(session, main).flatMap { it.workflows }
        val executionVersion = session.executableWorld?.executionVersion ?: ExecutionSemantics.CURRENT
        return workflows.filter { it.enabled && MemoryPlugins.workflowActive(library, session, it.id, executionVersion) }.flatMap { workflow ->
            workflow.nodes.filter { it.enabled && it.actionId in modelActions }.map { node ->
                node.generation.resolve(ExecutionResources.forWorkflow(library, workflow.id, executionVersion), workflow,
                    executionVersion)
            }
        }.distinctBy { it?.id }
    }

    fun requiresModel(library: Library, session: Session, actions: List<ActionDescriptor>): Boolean {
        val modelActions = actions.filter { it.usesModel }.mapTo(mutableSetOf()) { it.id }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val workflows = main?.configuredWorkflows().orEmpty() + library.loadedCallGroups(session, main).flatMap { it.workflows }
        val executionVersion = session.executableWorld?.executionVersion ?: ExecutionSemantics.CURRENT
        return workflows.any { workflow -> workflow.enabled &&
            MemoryPlugins.workflowActive(library, session, workflow.id, executionVersion) &&
            workflow.nodes.any { it.enabled && it.actionId in modelActions } }
    }
}
