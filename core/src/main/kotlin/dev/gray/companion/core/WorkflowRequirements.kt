package dev.gray.companion.core

/** Shared admission metadata for adapters; execution still validates the complete plan. */
object WorkflowRequirements {
    /** Potentially needed model profiles for the selected, enabled resources. */
    fun requiredProfiles(library: Library, session: Session, actions: List<ActionDescriptor>): List<ModelProfile?> {
        val modelActions = actions.filter { it.usesModel }.mapTo(mutableSetOf()) { it.id }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val workflows = main?.configuredWorkflows().orEmpty() + library.loadedCallGroups(session, main).flatMap { it.workflows }
        return workflows.filter { it.enabled }.flatMap { workflow ->
            workflow.nodes.filter { it.enabled && it.actionId in modelActions }.map { node ->
                node.generation.resolve(library, workflow)
            }
        }.distinctBy { it?.id }
    }

    fun requiresModel(library: Library, session: Session, actions: List<ActionDescriptor>): Boolean {
        val modelActions = actions.filter { it.usesModel }.mapTo(mutableSetOf()) { it.id }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        val workflows = main?.configuredWorkflows().orEmpty() + library.loadedCallGroups(session, main).flatMap { it.workflows }
        return workflows.any { workflow -> workflow.enabled && workflow.nodes.any { it.enabled && it.actionId in modelActions } }
    }
}
