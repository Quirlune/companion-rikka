package dev.gray.companion.core

/** The currently loaded, enabled nodes that are allowed to publish named results. */
data class ResultDeclaration(val key: String, val origin: ResultOrigin)

object ResultReferences {
    fun declarations(library: Library, session: Session): List<ResultDeclaration> = buildList {
        fun addWorkflows(resourceId: String, workflows: List<Workflow>) {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.filter { it.enabled }.forEach { node ->
                    add(ResultDeclaration(node.outputKey, ResultOrigin(resourceId, workflow.id, node.id, node.resultCategory)))
                    if (node.actionId in setOf("macro.write", "macro.activate", "macro.process")) {
                        val target = if (node.actionId == "macro.process") canonicalWriteAddress(
                            node.macroProcess.key.ifBlank { node.arguments["key"].orEmpty() })
                        else MacroReferences.canonicalAddress(node.arguments["key"].orEmpty())
                        if (target?.startsWith("result.") == true && target != "result.${node.outputKey}")
                            add(ResultDeclaration(target.substring(7), ResultOrigin(resourceId, workflow.id, node.id, node.resultCategory)))
                    }
                }
            }
        }
        library.mainCalls.firstOrNull { it.id == session.mainCallId }?.let { main ->
            addWorkflows(main.id, main.workflows)
            main.history.processor.workflow.takeIf { it.enabled }?.let { addWorkflows(main.id, listOf(it)) }
        }
        library.loadedCallGroups(session).forEach { addWorkflows(it.id, it.workflows) }
    }

    fun category(
        category: String,
        declarations: List<ResultDeclaration>,
        results: Map<String, String>,
        origins: Map<String, ResultOrigin>,
        enabled: (String) -> Boolean = { true }
    ): String = declarations
        .filter { it.origin.category == category && category.isNotEmpty() }
        .groupBy { it.key }
        .mapNotNull { (key, writers) ->
            val value = results[key]
            if (enabled(key) && writers.any { it.origin == origins[key] } && !value.isNullOrEmpty()) value else null
        }
        .joinToString("\n")
}
