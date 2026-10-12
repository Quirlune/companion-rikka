package dev.gray.companion.core

/** The currently loaded, enabled nodes that are allowed to publish named results. */
data class ResultDeclaration(val key: String, val origin: ResultOrigin)

object ResultReferences {
    fun declarations(library: Library, session: Session): List<ResultDeclaration> = buildList {
        fun addWorkflows(resourceId: String, workflows: List<Workflow>) {
            workflows.filter { it.enabled }.forEach { workflow ->
                workflow.nodes.filter { it.enabled && PluginParticipation.nodeActive(library, session, it) }.forEach node@ { node ->
                    if (node.actionId == "user.macro.create") return@node
                    val keys = linkedSetOf(node.outputKey)
                    if (node.regexOutputCopy.enabled) keys += node.regexOutputCopy.outputKey
                    if (node.actionId in setOf("macro.write", "macro.activate", "macro.process")) {
                        val target = if (node.actionId == "macro.process") canonicalWriteAddress(
                            node.macroProcess.key.ifBlank { node.arguments["key"].orEmpty() })
                        else MacroReferences.canonicalAddress(node.arguments["key"].orEmpty())
                        if (target?.startsWith("result.") == true) keys += target.substring(7)
                    }
                    val origin = ResultOrigin(resourceId, workflow.id, node.id, node.resultCategory)
                    keys.forEach { add(ResultDeclaration(it, origin)) }
                }
            }
        }
        library.mainCalls.firstOrNull { it.id == session.mainCallId }?.let { main ->
            addWorkflows(main.id, main.configuredWorkflows())
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
